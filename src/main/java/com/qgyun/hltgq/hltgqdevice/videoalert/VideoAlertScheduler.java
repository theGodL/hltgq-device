package com.qgyun.hltgq.hltgqdevice.videoalert;

import com.qgyun.hltgq.hltgqdevice.service.DahuaAuthService;
import com.qgyun.hltgq.hltgqdevice.service.StationStatusSyncService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 视频故障轮巡调度器：定时遍历ICC设备树全部视频通道，并发检测并联动防抖状态机与告警入库。
 * <p>轮巡流程（按计划"二、总体架构"）：
 * <ol>
 *   <li>轮级登录检查：token 不可获取时重试 3 次（间隔 2s，防偶发网络抖动），仍失败 → 本轮标记 LOGIN_FAIL 并跳过（数据缺失时不检测，防止误报/误关）；</li>
 *   <li>复用 {@link StationStatusSyncService#collectVideoChannels()} 构建通道池（遍历不完整则整轮跳过）；</li>
 *   <li>线程池并发检测（默认 4 路），在线通道取子码流抽帧分析，离线通道直接判信号丢失候选；</li>
 *   <li>检测结果进入 {@link AlertDebouncer} 防抖状态机（连续 N 轮异常才告警，避免单轮抖动误报）；</li>
 *   <li>NEW_ALERT → {@link VideoAlertService#reportFault}，RECOVERED → {@link VideoAlertService#recoverFault}。</li>
 * </ol>
 * <p>轮巡互斥：同一时刻只允许一轮执行（定时与手动触发共用互斥锁）。
 * 服务重启后防抖状态清零，detectThreshold 轮内可恢复告警（可接受）。
 */
@Slf4j
@Component
public class VideoAlertScheduler {

    /** 单路检测超时余量（秒）：流探测+抽帧排队上限之外的等待缓冲 */
    private static final int PER_CHANNEL_TIMEOUT_SLACK_SECONDS = 10;

    /** 轮级登录重试次数与间隔（防偶发网络抖动导致整轮跳过） */
    private static final int LOGIN_MAX_RETRY = 3;
    private static final long LOGIN_RETRY_INTERVAL_MS = 2000L;

    @Value("${video-alert.enabled:true}")
    private boolean enabled;

    @Value("${video-alert.parallel:4}")
    private int parallel;

    @Value("${video-alert.detect-threshold:2}")
    private int detectThreshold;

    @Value("${video-alert.recover-threshold:2}")
    private int recoverThreshold;

    /** 单路检测时长上限（秒，含流探测+抽帧+分析），配置项 video-alert.stream-seconds */
    @Value("${video-alert.stream-seconds:15}")
    private int streamSeconds;

    @Resource
    private StationStatusSyncService stationSyncService;

    @Resource
    private ChannelInspector channelInspector;

    @Resource
    private VideoAlertService alertService;

    @Resource
    private DahuaAuthService authService;

    /** 防抖状态机（阈值来自配置，@PostConstruct 构建后不再变更） */
    private volatile AlertDebouncer debouncer;

    /** 轮巡互斥锁：同一时刻只允许一轮 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 轮巡状态快照（volatile 整对象替换，供 /status 接口无锁读取） */
    private volatile RoundSnapshot snapshot = new RoundSnapshot();

    @PostConstruct
    public void init() {
        debouncer = new AlertDebouncer(detectThreshold, recoverThreshold);
        log.info("[视频告警] 轮巡调度初始化: enabled={}, 并发{}路, 告警阈值{}轮, 恢复阈值{}轮, 单路超时{}s",
                enabled, parallel, detectThreshold, recoverThreshold, streamSeconds);
    }

    /**
     * 定时轮巡（默认每30分钟一轮，避开站点同步整点任务错峰5分钟）。
     */
    @Scheduled(cron = "${video-alert.cron:0 */30 * * * ?}")
    public void scheduledRound() {
        if (!enabled) {
            log.debug("[视频告警] 功能未启用，跳过定时轮巡");
            return;
        }
        runRound();
    }

    /**
     * 手动触发一轮检测（管理接口调用）：后台异步执行，立即返回是否受理。
     *
     * @return true-已受理并开始执行，false-上一轮尚未结束
     */
    public boolean triggerRound() {
        if (!running.compareAndSet(false, true)) {
            log.warn("[视频告警] 手动触发被拒：上一轮轮巡尚未结束");
            return false;
        }
        Thread t = new Thread(this::runRound0, "video-alert-round");
        t.setDaemon(true);
        t.start();
        return true;
    }

    /**
     * 同步执行一轮检测（定时任务线程直接阻塞执行）。
     */
    public void runRound() {
        if (!running.compareAndSet(false, true)) {
            log.warn("[视频告警] 上一轮轮巡尚未结束，本轮跳过");
            return;
        }
        runRound0();
    }

    /** 当前轮巡状态快照（供 /status 接口与运维排查） */
    public RoundSnapshot getSnapshot() {
        return snapshot;
    }

    // ======================== 轮巡主体 ========================

    private void runRound0() {
        RoundSnapshot snap = new RoundSnapshot();
        snap.state = "RUNNING";
        snap.startTime = System.currentTimeMillis();
        snapshot = snap;
        try {
            doRound(snap);
        } catch (Exception e) {
            log.error("[视频告警] 轮巡执行异常", e);
            snap.state = "ABORTED";
            snap.note = "轮巡异常: " + e.getMessage();
        } finally {
            snap.endTime = System.currentTimeMillis();
            if ("RUNNING".equals(snap.state)) {
                snap.state = "FINISHED";
            }
            running.set(false);
            log.info("[视频告警] 轮巡结束: state={}, 通道{}个(在线{})完成{}个, 新增告警{}条, 恢复关警{}条, 耗时{}ms",
                    snap.state, snap.totalChannels, snap.onlineChannels, snap.completedChannels.get(),
                    snap.newAlerts, snap.recoveredAlerts, snap.endTime - snap.startTime);
        }
    }

    private void doRound(RoundSnapshot snap) {
        // ============ A 层轮级：登录检查（token 获取失败重试，防偶发网络抖动） ============
        String token = null;
        for (int attempt = 1; attempt <= LOGIN_MAX_RETRY && (token == null || token.trim().isEmpty()); attempt++) {
            try {
                token = authService.getAccessToken();
            } catch (Exception e) {
                token = null;
            }
            if (token == null || token.trim().isEmpty()) {
                if (attempt < LOGIN_MAX_RETRY) {
                    log.warn("[视频告警] ICC登录获取token失败(第{}/{}次)，{}s后重试",
                            attempt, LOGIN_MAX_RETRY, LOGIN_RETRY_INTERVAL_MS / 1000);
                    try {
                        Thread.sleep(LOGIN_RETRY_INTERVAL_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                } else {
                    log.error("[视频告警] ICC登录获取token失败(第{}/{}次)，本轮中止",
                            attempt, LOGIN_MAX_RETRY);
                }
            }
        }
        if (token == null || token.trim().isEmpty()) {
            snap.state = "ABORTED";
            snap.loginFailed = true;
            snap.note = "ICC登录失败（token不可获取，重试" + LOGIN_MAX_RETRY + "次），本轮跳过全部通道检测";
            log.error("[视频告警] {}", snap.note);
            return;
        }

        // ============ 通道池构建（复用站点同步设备树遍历） ============
        List<StationStatusSyncService.VideoChannel> channels = stationSyncService.collectVideoChannels();
        if (channels == null) {
            snap.state = "ABORTED";
            snap.note = "ICC设备树遍历不完整，本轮跳过（数据缺失不检测，防止误报/误关）";
            log.warn("[视频告警] {}", snap.note);
            return;
        }
        snap.totalChannels = channels.size();
        int online = 0;
        for (StationStatusSyncService.VideoChannel ch : channels) {
            if (ch.isOnline()) online++;
        }
        snap.onlineChannels = online;
        log.info("[视频告警] 本轮轮巡开始: 通道总数={}, 在线={}, 离线={}",
                channels.size(), online, channels.size() - online);

        // ============ 线程池并发检测 ============
        Map<String, Set<VideoFaultType>> abnormalByChannel = new ConcurrentHashMap<>();
        // 本轮实际完成检测的通道（正常+异常）：未检测（超时取消/检测异常）的通道不参与防抖判定，
        // 防止排队任务被误杀后把"没检测到"误判为"已恢复"而错误关闭告警与工单
        Set<String> inspectedChannels = ConcurrentHashMap.newKeySet();
        int poolSize = Math.max(1, Math.min(parallel, channels.size()));
        ExecutorService pool = Executors.newFixedThreadPool(poolSize, r -> {
            Thread t = new Thread(r, "video-alert-inspect");
            t.setDaemon(true);
            return t;
        });
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (StationStatusSyncService.VideoChannel ch : channels) {
                futures.add(pool.submit(() -> {
                    try {
                        Set<VideoFaultType> faults = channelInspector.inspect(ch);
                        snap.channelResults.put(ch.getDevicecode(), summarize(ch, faults));
                        abnormalByChannel.put(ch.getDevicecode(), faults);
                        inspectedChannels.add(ch.getDevicecode());
                    } catch (Exception e) {
                        log.warn("[视频告警] 单路检测异常: code={}, err={}", ch.getDevicecode(), e.getMessage());
                        snap.channelResults.put(ch.getDevicecode(), "检测异常");
                        // 检测异常通道不加入 inspectedChannels（异常≠正常，防抖计数保持不变）
                    } finally {
                        snap.completedChannels.incrementAndGet();
                    }
                }));
            }
            // 等待全部完成：总预算 = 批数 × 单路硬超时（streamSeconds + 余量）。
            // 预算耗尽仍未完成的通道被取消且不参与防抖判定（计数保持），不会误关已有告警。
            int batches = (futures.size() + poolSize - 1) / poolSize;
            long perChannelMillis = (streamSeconds + PER_CHANNEL_TIMEOUT_SLACK_SECONDS) * 1000L;
            long deadline = System.currentTimeMillis() + perChannelMillis * Math.max(batches, 1);
            for (Future<?> f : futures) {
                long remain = deadline - System.currentTimeMillis();
                if (remain <= 0) {
                    f.cancel(true);
                    log.warn("[视频告警] 轮巡总预算耗尽，取消剩余未完成通道（本轮不参与防抖判定）");
                    continue;
                }
                try {
                    f.get(Math.min(remain, perChannelMillis), TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    f.cancel(true);
                    log.warn("[视频告警] 单路检测超时被取消（该通道本轮不参与防抖判定）");
                } catch (Exception e) {
                    // 单路执行异常已在上方 catch 记录，不影响整轮
                }
            }
        } finally {
            pool.shutdownNow();
        }

        // ============ 防抖判定 → 告警新增/恢复 ============
        List<AlertDebouncer.FaultEvent> events = debouncer.acceptRound(abnormalByChannel, inspectedChannels);
        for (AlertDebouncer.FaultEvent ev : events) {
            if (ev.getType() == AlertDebouncer.FaultEvent.Type.NEW_ALERT) {
                if (alertService.reportFault(ev.getChannelId(), ev.getFault())) {
                    snap.newAlerts++;
                }
            } else {
                // 实际关闭条数>0才计入恢复统计（人工已关闭的行不重复计数）
                if (alertService.recoverFault(ev.getChannelId(), ev.getFault()) > 0) {
                    snap.recoveredAlerts++;
                }
            }
        }
        log.info("[视频告警] 本轮检测完成: 完成{}路, 异常通道{}个, 防抖事件{}个",
                snap.completedChannels.get(), abnormalByChannel.size(), events.size());
    }

    /** 单路检测结果摘要（状态快照展示用） */
    private String summarize(StationStatusSyncService.VideoChannel ch, Set<VideoFaultType> faults) {
        if (faults.isEmpty()) {
            return "正常";
        }
        StringBuilder sb = new StringBuilder();
        for (VideoFaultType f : faults) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(f.getLabel());
        }
        return sb.toString();
    }

    // ======================== 轮巡状态快照 ========================

    /**
     * 轮巡状态快照（内存对象，供 /status 接口返回与日志排查）。
     */
    public static class RoundSnapshot {
        /** 轮巡状态：IDLE-从未执行 / RUNNING-执行中 / FINISHED-完成 / ABORTED-中止（登录失败/遍历不完整/异常） */
        private volatile String state = "IDLE";
        private volatile long startTime;
        private volatile long endTime;
        private volatile int totalChannels;
        private volatile int onlineChannels;
        private final AtomicInteger completedChannels = new AtomicInteger();
        private volatile int newAlerts;
        private volatile int recoveredAlerts;
        /** 轮级登录失败标记（LOGIN_FAIL 故障项） */
        private volatile boolean loginFailed;
        /** 中止原因/备注 */
        private volatile String note;
        /** 各通道最近检测结果摘要：devicecode → 结果描述（如"正常"/"信号丢失"/"图像过暗,图像模糊"） */
        private final ConcurrentMap<String, String> channelResults = new ConcurrentHashMap<>();

        public String getState() {
            return state;
        }

        public long getStartTime() {
            return startTime;
        }

        public long getEndTime() {
            return endTime;
        }

        public int getTotalChannels() {
            return totalChannels;
        }

        public int getOnlineChannels() {
            return onlineChannels;
        }

        public int getCompletedChannels() {
            return completedChannels.get();
        }

        public int getNewAlerts() {
            return newAlerts;
        }

        public int getRecoveredAlerts() {
            return recoveredAlerts;
        }

        public boolean isLoginFailed() {
            return loginFailed;
        }

        public String getNote() {
            return note;
        }

        public Map<String, String> getChannelResults() {
            return channelResults;
        }
    }
}
