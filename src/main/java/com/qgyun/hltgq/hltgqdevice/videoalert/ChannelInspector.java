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
 *   <li>离线通道 → 信号丢失候选（不取流，避免无效取流占用带宽）；</li>
 *   <li>在线通道 → 获取子码流地址（失败 → 取流异常）；</li>
 *   <li>流探测 M3U8 不可取 → 取流异常；</li>
 *   <li>ffmpeg 抽帧为空 → 取流异常；</li>
 *   <li>帧图像指标分析 → B 层 7 类图像故障（过亮/过暗/偏色/黑白/模糊/对比度/噪声）；
 *       智能类告警（画面冻结/视频遮挡等）由大华 IVSS 事件推送接入，不在自研检测范围。</li>
 * </ol>
 * <p>异常单路不影响整轮：本类不抛异常，全部以故障候选形式返回。
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

    /**
     * 检测单路通道，返回故障候选集合（无异常返回空集合，本方法不抛异常）。
     *
     * @param channel 设备树通道信息（含在线状态）
     * @return 故障候选集合
     */
    public Set<VideoFaultType> inspect(StationStatusSyncService.VideoChannel channel) {
        Set<VideoFaultType> faults = EnumSet.noneOf(VideoFaultType.class);
        if (channel == null || channel.getDevicecode() == null) {
            return faults;
        }
        String code = channel.getDevicecode();

        // ============ A 层：信号丢失（离线通道直接判定，不取流） ============
        if (!channel.isOnline()) {
            log.debug("[视频告警] 通道离线 → 信号丢失候选: {}", code);
            faults.add(VideoFaultType.SIGNAL_LOSS);
            return faults;
        }

        // ============ A 层：取流异常（在线但流不可取） ============
        String streamUrl = videoService.resolveInspectionStreamUrl(code);
        if (streamUrl == null) {
            log.debug("[视频告警] 子码流地址获取失败 → 取流异常候选: {}", code);
            faults.add(VideoFaultType.STREAM_ERROR);
            return faults;
        }
        if (!videoService.isStreamAvailable(streamUrl)) {
            log.debug("[视频告警] 流探测不可取 → 取流异常候选: {}", code);
            faults.add(VideoFaultType.STREAM_ERROR);
            return faults;
        }

        // ============ 抽帧 ============
        List<BufferedImage> imgs = frameGrabber.grabFrames(streamUrl, frames, grabTimeoutSeconds);
        if (imgs.isEmpty()) {
            log.debug("[视频告警] 抽帧为空 → 取流异常候选: {}", code);
            faults.add(VideoFaultType.STREAM_ERROR);
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
}
