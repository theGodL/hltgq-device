package com.qgyun.hltgq.hltgqdevice.videoalert;

import com.qgyun.hltgq.hltgqdevice.service.DahuaVideoService;
import com.qgyun.hltgq.hltgqdevice.service.StationStatusSyncService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.awt.image.BufferedImage;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 单路通道检测器：A 层流协议检测 + B 层图像质量检测。
 * <p>检测流程（按计划"三、15类故障分级实施"一期范围）：
 * <ol>
 *   <li>流可用性：获取子码流地址并探测（失败 → 离线=信号丢失，在线=取流异常）——
 *       设备树 isOnline 仅作参考，以流能否实际取到为准（isOnline 会失真误报）；
 *       首次探测失败间隔重试一次再确认（2026-10-30 证据充足口径）：间歇断流通道
 *       偶发毛刺不计故障，两次均失败才判流故障；</li>
 *   <li>流可取 → ffmpeg 抽帧（为空 → 取流异常）；</li>
 *   <li>帧图像指标分析 → B 层 7 类图像故障（过亮/过暗/偏色/黑白/模糊/对比度/噪声）；
 *       智能类告警（画面冻结/视频遮挡等）由大华 IVSS 事件推送接入，不在自研检测范围。</li>
 * </ol>
 * <p>异常单路不影响整轮：除轮级取消中断（抛 IllegalStateException 由调度器记 error）外，
 * 本类不抛异常，全部以故障候选形式返回。
 */
@Slf4j
@Component
public class ChannelInspector {

    @Resource
    private DahuaVideoService videoService;

    @Resource
    private FfmpegFrameGrabber frameGrabber;

    @Resource
    private ImageQualityAnalyzer imageAnalyzer;

    /** 每路抽帧数（配置项 video-alert.frames，默认 3） */
    @Value("${video-alert.frames:3}")
    private int frames;

    /** ffmpeg 抽帧整体超时（秒，配置项 video-alert.grab-timeout-seconds，默认 15） */
    @Value("${video-alert.grab-timeout-seconds:15}")
    private int grabTimeoutSeconds;

    /** 流探测失败后的重试间隔（秒，0=不重试），配置项 video-alert.retry-delay-seconds */
    @Value("${video-alert.retry-delay-seconds:10}")
    private int retryDelaySeconds;

    /**
     * 检测单路通道，返回故障候选集合（无异常返回空集合）。
     *
     * @param channel 设备树通道信息（含在线状态）
     * @return 故障候选集合
     * @throws IllegalStateException 探测重试/抽帧等待被调度器取消中断（本轮不参与防抖判定，不应记为故障）
     */
    public Set<VideoFaultType> inspect(StationStatusSyncService.VideoChannel channel) {
        Set<VideoFaultType> faults = EnumSet.noneOf(VideoFaultType.class);
        if (channel == null || channel.getDevicecode() == null) {
            return faults;
        }
        String code = channel.getDevicecode();

        // ============ A 层：流可用性判定（业务口径 2026-10-29：以"流能否实际取到"为准） ============
        // 设备树 isOnline 存在失真（树报离线但流实际可取），若单凭 isOnline 直接判信号丢失，
        // 会把能正常观看的通道误报为严重故障。因此离线通道也先尝试拉流：
        // 能取到流 → 状态失真，按正常通道继续检测（含画质分析）；取不到流 → 信号丢失。
        // 探测失败间隔重试一次再确认（2026-10-30）：间歇断流通道偶发抖动不计故障。
        boolean treeOffline = !channel.isOnline();
        String streamUrl = resolveReachableStreamUrl(code);
        if (streamUrl == null) {
            log.debug("[视频告警] 流两次探测均不可取 → 流故障候选: code={}, treeOffline={}", code, treeOffline);
            faults.add(treeOffline ? VideoFaultType.SIGNAL_LOSS : VideoFaultType.STREAM_ERROR);
            return faults;
        }
        if (treeOffline) {
            log.info("[视频告警] 设备树离线但流可取（isOnline状态失真），按正常通道继续检测: {}", code);
        }

        // ============ 抽帧 ============
        List<BufferedImage> imgs = frameGrabber.grabFrames(streamUrl, frames, grabTimeoutSeconds);
        if (imgs.isEmpty()) {
            // 树离线且流地址可拉但抽帧为空：取不到画面，与流探测失败同级语义按信号丢失处理
            // （never-ok 未投运豁免得以覆盖此路径，避免漏网通道误报严重故障）
            log.debug("[视频告警] 抽帧为空 → 取流失败候选: code={}, treeOffline={}", code, treeOffline);
            faults.add(treeOffline ? VideoFaultType.SIGNAL_LOSS : VideoFaultType.STREAM_ERROR);
            return faults;
        }

        // ============ B 层：图像指标分析 ============
        Set<VideoFaultType> imageFaults = imageAnalyzer.analyze(imgs);
        faults.addAll(imageFaults);
        if (!faults.isEmpty()) {
            log.info("[视频告警] 通道检测异常: code={}, name={}, faults={}",
                    code, channel.getName(), faults);
        }
        return faults;
    }

    /**
     * 获取可用的流地址（证据充足判定 2026-10-30）：首次探测失败后间隔 {@code retryDelaySeconds}
     * 重试一次再确认——间歇断流通道（设备在线、偶发毛刺）首次探测可能恰好赶上断流瞬间，
     * 重试仍失败才判定流故障，避免把"平时能看、偶发抖动"的通道误报为严重故障。
     *
     * @param code 通道设备编码
     * @return 可用的流地址；两次探测均失败返回 null
     * @throws IllegalStateException 重试等待被中断（本轮已取消，不得记为故障）
     */
    private String resolveReachableStreamUrl(String code) {
        String streamUrl = videoService.resolveInspectionStreamUrl(code);
        if (streamUrl != null && videoService.isStreamAvailable(streamUrl)) {
            return streamUrl;
        }
        if (retryDelaySeconds <= 0) {
            return null;
        }
        log.info("[视频告警] 流首次探测失败，{}s 后重试确认: {}", retryDelaySeconds, code);
        try {
            Thread.sleep(retryDelaySeconds * 1000L);
        } catch (InterruptedException e) {
            // 轮巡总预算耗尽被取消：不伪装成故障候选（否则被取消的通道会以严重故障进入防抖）
            Thread.currentThread().interrupt();
            throw new IllegalStateException("流探测重试被中断（本轮已取消）", e);
        }
        String retryUrl = videoService.resolveInspectionStreamUrl(code);
        if (retryUrl != null && videoService.isStreamAvailable(retryUrl)) {
            log.info("[视频告警] 流重试探测恢复（首测为瞬时断流，不计故障）: {}", code);
            return retryUrl;
        }
        log.info("[视频告警] 流两次探测均失败（确认故障）: {}", code);
        return null;
    }
}
