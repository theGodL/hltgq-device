package com.qgyun.hltgq.hltgqdevice.videoalert;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 故障防抖状态机：避免单轮网络抖动/瞬时干扰导致的告警误报与闪断。
 * <p>每个通道-故障项维护一个有符号连续计数（正=连续异常轮数，负=连续正常轮数）：
 * <ul>
 *   <li>连续异常达到 {@code detectThreshold} 轮 → 触发 {@link FaultEvent.Type#NEW_ALERT}；</li>
 *   <li>已告警后连续正常达到 {@code recoverThreshold} 轮 → 触发 {@link FaultEvent.Type#RECOVERED} 并归零计数；</li>
 *   <li>服务重启状态清零，首轮从 0 计数（detectThreshold 轮内可重新告警，可接受）。</li>
 * </ul>
 * <p>两项告警质量治理机制（2026-09 生产告警复盘后引入，对齐 hltgq-mq 核查报告建议）：
 * <ul>
 *   <li><b>时段豁免</b>（{@code exemptFaults} 入参）：指定故障类别在豁免时段（如夜间红外黑白/过暗/偏色）
 *       跳过判定——不新增、不恢复、计数保持不变。已告警项时段内不会被误关警，时段结束后恢复判定；
 *       巡检留痕（统计口径）不经过本状态机，不受影响；</li>
 *   <li><b>告警抑制期</b>（{@code suppressMillis}）：故障恢复关警后窗口内同类故障再次达到告警条件时不立即告警，
 *       而是挂起；窗口结束后仍异常则补发告警，期间恢复正常则挂起解除。
 *       压制"告警→关闭→再告警"的循环放大（取流不稳等间歇故障）。</li>
 * </ul>
 */
@Slf4j
public class AlertDebouncer {

    private final int detectThreshold;
    private final int recoverThreshold;

    /** 告警抑制窗口（毫秒，0=不启用）：故障恢复关警后，窗口内同类故障不再新增告警，窗口结束仍异常则补发 */
    private final long suppressMillis;

    /** channelId → (故障项 → 连续计数，正=连续异常，负=连续正常) */
    private final ConcurrentMap<String, ConcurrentMap<VideoFaultType, Integer>> counters = new ConcurrentHashMap<>();

    /** 抑制窗口起点：channelId|故障项 → 上次恢复关警时刻（毫秒） */
    private final ConcurrentMap<String, Long> lastRecoveredAt = new ConcurrentHashMap<>();

    /** 被抑制挂起的新告警（窗口内已达告警条件未发出；窗口结束仍异常则补发、恢复则解除） */
    private final Set<String> suppressedPending = ConcurrentHashMap.newKeySet();

    public AlertDebouncer(int detectThreshold, int recoverThreshold) {
        this(detectThreshold, recoverThreshold, 0);
    }

    public AlertDebouncer(int detectThreshold, int recoverThreshold, long suppressMillis) {
        if (detectThreshold < 1) {
            detectThreshold = 1;
        }
        if (recoverThreshold < 1) {
            recoverThreshold = 1;
        }
        this.detectThreshold = detectThreshold;
        this.recoverThreshold = recoverThreshold;
        this.suppressMillis = Math.max(0, suppressMillis);
    }

    /**
     * 接受一轮检测结果，输出本轮应触发的事件（无时段豁免，兼容旧调用）。
     */
    public synchronized List<FaultEvent> acceptRound(Map<String, Set<VideoFaultType>> abnormalByChannel,
                                                     Set<String> inspectedChannels) {
        return acceptRound(abnormalByChannel, inspectedChannels, null);
    }

    /**
     * 接受一轮检测结果，输出本轮应触发的事件。
     * <p>通道键取 {@code abnormalByChannel} 与状态机已有计数键的并集：
     * 调用方可以只传异常通道，历史告警通道本轮正常时仍会正确走恢复判定。
     * <p>未完成检测的通道（不在 {@code inspectedChannels} 中）跳过判定、计数保持不变：
     * 单路检测超时/异常≠恢复，防止把"没检测到"误判为"已恢复正常"而错误关闭告警与工单。
     *
     * @param abnormalByChannel 本轮各通道的异常故障集合（正常通道可不包含或为空集合）
     * @param inspectedChannels 本轮实际完成检测的通道集合（正常+异常通道；null=全部视为已检测，兼容旧调用）
     * @param exemptFaults 本轮豁免判定的故障类别（如夜间红外黑白/过暗/偏色）：跳过判定、计数保持不变，null/空=无豁免
     * @return 本轮事件列表（NEW_ALERT 告警 / RECOVERED 恢复）
     */
    public synchronized List<FaultEvent> acceptRound(Map<String, Set<VideoFaultType>> abnormalByChannel,
                                                     Set<String> inspectedChannels,
                                                     Set<VideoFaultType> exemptFaults) {
        long now = System.currentTimeMillis();
        List<FaultEvent> events = new ArrayList<>();
        // 本轮异常通道 ∪ 已有计数通道（后者本轮可能已恢复，必须参与恢复判定）
        Set<String> allChannels = new HashSet<>();
        if (abnormalByChannel != null) {
            allChannels.addAll(abnormalByChannel.keySet());
        }
        allChannels.addAll(counters.keySet());
        for (String channelId : allChannels) {
            // 未完成检测的通道（超时被取消/检测异常）：跳过判定，计数保持不变
            boolean inspected = inspectedChannels == null
                    || inspectedChannels.contains(channelId)
                    || (abnormalByChannel != null && abnormalByChannel.containsKey(channelId));
            if (!inspected) {
                continue;
            }
            Set<VideoFaultType> abnormal = (abnormalByChannel != null && abnormalByChannel.get(channelId) != null)
                    ? abnormalByChannel.get(channelId) : EnumSet.noneOf(VideoFaultType.class);
            ConcurrentMap<VideoFaultType, Integer> channelCounters =
                    counters.computeIfAbsent(channelId, k -> new ConcurrentHashMap<>());

            // 候选故障项 = 本轮异常 ∪ 已有计数项（历史异常但本轮正常 → 走恢复判定）
            Set<VideoFaultType> candidates = EnumSet.noneOf(VideoFaultType.class);
            candidates.addAll(abnormal);
            candidates.addAll(channelCounters.keySet());

            for (VideoFaultType fault : candidates) {
                // 时段豁免（如夜间红外黑白/过暗/偏色）：跳过判定、计数保持不变（不新增、不恢复）
                if (exemptFaults != null && exemptFaults.contains(fault)) {
                    continue;
                }
                String key = channelId + "|" + fault.name();
                Integer oldCount = channelCounters.getOrDefault(fault, 0);
                if (abnormal.contains(fault)) {
                    // 本轮异常：连续计数正向累加，达到阈值且此前未告警 → 新增告警
                    int count = Math.max(oldCount, 0) + 1;
                    if (count >= detectThreshold && oldCount < detectThreshold) {
                        if (suppressedRecently(key, now)) {
                            // 抑制窗口内（恢复关警后 N 分钟内）：挂起不告警，窗口结束仍异常则补发
                            suppressedPending.add(key);
                            log.info("[视频告警] 恢复后{}分钟内重复告警被抑制（挂起）: channel={}, fault={}",
                                    suppressMillis / 60000, channelId, fault);
                        } else {
                            events.add(new FaultEvent(channelId, fault, FaultEvent.Type.NEW_ALERT));
                        }
                    } else if (oldCount >= detectThreshold && suppressedPending.contains(key)
                            && !suppressedRecently(key, now)) {
                        // 挂起中且抑制窗口已结束：故障持续未恢复 → 补发告警
                        suppressedPending.remove(key);
                        events.add(new FaultEvent(channelId, fault, FaultEvent.Type.NEW_ALERT));
                        log.info("[视频告警] 抑制窗口结束，故障仍异常补发告警: channel={}, fault={}",
                                channelId, fault);
                    }
                    channelCounters.put(fault, count);
                } else {
                    // 本轮正常：已告警（正计数达阈值）或恢复计数中（负计数）→ 继续恢复判定
                    if (oldCount >= detectThreshold || oldCount < 0) {
                        int count = Math.min(oldCount, 0) - 1;
                        if (-count >= recoverThreshold) {
                            if (suppressMillis > 0) {
                                lastRecoveredAt.put(key, now);
                            }
                            suppressedPending.remove(key);
                            events.add(new FaultEvent(channelId, fault, FaultEvent.Type.RECOVERED));
                            channelCounters.remove(fault);
                        } else {
                            channelCounters.put(fault, count);
                        }
                    } else {
                        // 未达到告警阈值即恢复正常（含挂起中的短抖动恢复）：直接清零，不产生事件
                        suppressedPending.remove(key);
                        channelCounters.remove(fault);
                    }
                }
            }
            if (channelCounters.isEmpty()) {
                counters.remove(channelId);
            }
        }
        return events;
    }

    /** 是否处于抑制窗口内（该通道-故障项恢复关警后 suppressMillis 毫秒内） */
    private boolean suppressedRecently(String key, long now) {
        if (suppressMillis <= 0) {
            return false;
        }
        Long recoveredAt = lastRecoveredAt.get(key);
        return recoveredAt != null && now - recoveredAt < suppressMillis;
    }

    /**
     * 获取指定通道当前处于"已告警"状态的故障项（供状态快照展示）。
     * <p>已告警 = 正计数达到阈值（异常持续中）或负计数（恢复计数中，告警尚未关闭）。
     */
    public Set<VideoFaultType> activeFaults(String channelId) {
        Set<VideoFaultType> active = EnumSet.noneOf(VideoFaultType.class);
        ConcurrentMap<VideoFaultType, Integer> channelCounters = counters.get(channelId);
        if (channelCounters != null) {
            for (Map.Entry<VideoFaultType, Integer> e : channelCounters.entrySet()) {
                if (e.getValue() >= detectThreshold || e.getValue() < 0) {
                    active.add(e.getKey());
                }
            }
        }
        return active;
    }

    /**
     * 防抖事件：通道 + 故障项 + 动作（新增告警/恢复关警）。
     */
    public static class FaultEvent {
        public enum Type { NEW_ALERT, RECOVERED }

        private final String channelId;
        private final VideoFaultType fault;
        private final Type type;

        FaultEvent(String channelId, VideoFaultType fault, Type type) {
            this.channelId = channelId;
            this.fault = fault;
            this.type = type;
        }

        public String getChannelId() {
            return channelId;
        }

        public VideoFaultType getFault() {
            return fault;
        }

        public Type getType() {
            return type;
        }
    }
}
