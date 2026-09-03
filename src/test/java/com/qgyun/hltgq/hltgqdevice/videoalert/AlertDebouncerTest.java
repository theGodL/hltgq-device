package com.qgyun.hltgq.hltgqdevice.videoalert;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 防抖状态机单测：连续异常N轮告警、连续正常N轮恢复、单轮抖动不告警、
 * 正常通道未出现在映射中仍正确走恢复判定（历史告警通道不丢恢复）、
 * 未完成检测的通道计数保持不变（超时/检测异常≠恢复，不误关告警）。
 */
class AlertDebouncerTest {

    private static final String CHANNEL = "1000231$1$0$10";

    private AlertDebouncer debouncer;

    @BeforeEach
    void setUp() {
        debouncer = new AlertDebouncer(2, 2);
    }

    /** 单轮异常不告警（未达阈值） */
    @Test
    void singleRoundAbnormalDoesNotAlert() {
        List<AlertDebouncer.FaultEvent> events =
                debouncer.acceptRound(abnormal(VideoFaultType.SIGNAL_LOSS), inspected(CHANNEL));
        assertTrue(events.isEmpty());
        assertTrue(debouncer.activeFaults(CHANNEL).isEmpty());
    }

    /** 连续2轮异常 → 第2轮触发 NEW_ALERT，之后持续异常不再重复告警 */
    @Test
    void twoAbnormalRoundsTriggerAlertOnce() {
        assertTrue(debouncer.acceptRound(abnormal(VideoFaultType.SIGNAL_LOSS), inspected(CHANNEL)).isEmpty());
        List<AlertDebouncer.FaultEvent> events =
                debouncer.acceptRound(abnormal(VideoFaultType.SIGNAL_LOSS), inspected(CHANNEL));
        assertEquals(1, events.size());
        assertEquals(AlertDebouncer.FaultEvent.Type.NEW_ALERT, events.get(0).getType());
        assertEquals(VideoFaultType.SIGNAL_LOSS, events.get(0).getFault());
        assertEquals(CHANNEL, events.get(0).getChannelId());
        assertEquals(signalLossSet(), debouncer.activeFaults(CHANNEL));
        // 第3轮仍异常：不重复触发
        assertTrue(debouncer.acceptRound(abnormal(VideoFaultType.SIGNAL_LOSS), inspected(CHANNEL)).isEmpty());
    }

    /** 告警后仅1轮正常不关警，连续2轮正常才 RECOVERED */
    @Test
    void twoNormalRoundsRecover() {
        debouncer.acceptRound(abnormal(VideoFaultType.SIGNAL_LOSS), inspected(CHANNEL));
        debouncer.acceptRound(abnormal(VideoFaultType.SIGNAL_LOSS), inspected(CHANNEL));
        // 第1轮正常：不恢复
        assertTrue(debouncer.acceptRound(normal(), inspected(CHANNEL)).isEmpty());
        assertEquals(signalLossSet(), debouncer.activeFaults(CHANNEL));
        // 第2轮正常：恢复
        List<AlertDebouncer.FaultEvent> events = debouncer.acceptRound(normal(), inspected(CHANNEL));
        assertEquals(1, events.size());
        assertEquals(AlertDebouncer.FaultEvent.Type.RECOVERED, events.get(0).getType());
        assertEquals(VideoFaultType.SIGNAL_LOSS, events.get(0).getFault());
        assertTrue(debouncer.activeFaults(CHANNEL).isEmpty());
        // 恢复后再正常：无事件
        assertTrue(debouncer.acceptRound(normal(), inspected(CHANNEL)).isEmpty());
    }

    /** 单轮抖动：异常1轮后即恢复正常，不告警且计数清零（不累积） */
    @Test
    void jitterDoesNotAlertAndCountResets() {
        debouncer.acceptRound(abnormal(VideoFaultType.SIGNAL_LOSS), inspected(CHANNEL));
        assertTrue(debouncer.acceptRound(normal(), inspected(CHANNEL)).isEmpty());
        // 再次单轮异常仍不告警（若计数未清零则此轮会触发）
        debouncer.acceptRound(abnormal(VideoFaultType.SIGNAL_LOSS), inspected(CHANNEL));
        assertTrue(debouncer.activeFaults(CHANNEL).isEmpty());
    }

    /** 正常通道未出现在映射中（调用方只传异常通道）→ 历史告警通道仍正确恢复 */
    @Test
    void recoverWhenChannelAbsentFromRoundMap() {
        debouncer.acceptRound(abnormal(VideoFaultType.SIGNAL_LOSS), inspected(CHANNEL));
        debouncer.acceptRound(abnormal(VideoFaultType.SIGNAL_LOSS), inspected(CHANNEL));
        // 本轮映射不含该通道（完全正常，但已完成检测）
        assertTrue(debouncer.acceptRound(new HashMap<>(), inspected(CHANNEL)).isEmpty());
        List<AlertDebouncer.FaultEvent> events = debouncer.acceptRound(new HashMap<>(), inspected(CHANNEL));
        assertEquals(1, events.size());
        assertEquals(AlertDebouncer.FaultEvent.Type.RECOVERED, events.get(0).getType());
    }

    /** 多故障项独立计数：信号丢失恢复不影响图像过暗的告警状态 */
    @Test
    void faultsCountIndependently() {
        Map<String, Set<VideoFaultType>> round1 = abnormal(VideoFaultType.SIGNAL_LOSS, VideoFaultType.TOO_DARK);
        debouncer.acceptRound(round1, inspected(CHANNEL));
        List<AlertDebouncer.FaultEvent> events = debouncer.acceptRound(round1, inspected(CHANNEL));
        assertEquals(2, events.size());
        // 信号丢失恢复第1轮：无事件，两项均仍处于告警状态
        assertTrue(debouncer.acceptRound(abnormal(VideoFaultType.TOO_DARK), inspected(CHANNEL)).isEmpty());
        assertEquals(EnumSet.of(VideoFaultType.SIGNAL_LOSS, VideoFaultType.TOO_DARK),
                debouncer.activeFaults(CHANNEL));
        // 信号丢失恢复第2轮：触发 RECOVERED；过暗持续异常不受影响
        List<AlertDebouncer.FaultEvent> round4 =
                debouncer.acceptRound(abnormal(VideoFaultType.TOO_DARK), inspected(CHANNEL));
        assertEquals(1, round4.size());
        assertEquals(AlertDebouncer.FaultEvent.Type.RECOVERED, round4.get(0).getType());
        assertEquals(VideoFaultType.SIGNAL_LOSS, round4.get(0).getFault());
        assertEquals(EnumSet.of(VideoFaultType.TOO_DARK), debouncer.activeFaults(CHANNEL));
    }

    /** 阈值下限保护：构造参数小于1时按1处理 */
    @Test
    void thresholdClampedToMinimum() {
        AlertDebouncer d = new AlertDebouncer(0, -1);
        List<AlertDebouncer.FaultEvent> events =
                d.acceptRound(abnormal(VideoFaultType.SIGNAL_LOSS), inspected(CHANNEL));
        assertEquals(1, events.size());
        assertEquals(AlertDebouncer.FaultEvent.Type.NEW_ALERT, events.get(0).getType());
    }

    /** 已告警通道本轮未完成检测（超时取消/检测异常）→ 计数保持、不触发恢复（不误关告警） */
    @Test
    void uninspectedChannelKeepsCounter() {
        debouncer.acceptRound(abnormal(VideoFaultType.SIGNAL_LOSS), inspected(CHANNEL));
        debouncer.acceptRound(abnormal(VideoFaultType.SIGNAL_LOSS), inspected(CHANNEL));
        assertEquals(signalLossSet(), debouncer.activeFaults(CHANNEL));
        // 连续2轮未检测（映射为空 + inspected 为空）：均无事件，告警状态保持
        assertTrue(debouncer.acceptRound(new HashMap<>(), new HashSet<>()).isEmpty());
        assertTrue(debouncer.acceptRound(new HashMap<>(), new HashSet<>()).isEmpty());
        assertEquals(signalLossSet(), debouncer.activeFaults(CHANNEL));
        // 之后真正检测到连续2轮正常：正常走恢复判定（计数未被误清零/误恢复）
        assertTrue(debouncer.acceptRound(normal(), inspected(CHANNEL)).isEmpty());
        List<AlertDebouncer.FaultEvent> events = debouncer.acceptRound(normal(), inspected(CHANNEL));
        assertEquals(1, events.size());
        assertEquals(AlertDebouncer.FaultEvent.Type.RECOVERED, events.get(0).getType());
    }

    /** 告警计数中途未检测：既不加异常计数也不加恢复计数，告警阈值判定不被破坏 */
    @Test
    void uninspectedRoundDoesNotBreakAlertThreshold() {
        // 第1轮异常：计数1（未达阈值2）
        assertTrue(debouncer.acceptRound(abnormal(VideoFaultType.SIGNAL_LOSS), inspected(CHANNEL)).isEmpty());
        // 第2轮未检测：计数保持1，不误触发告警
        assertTrue(debouncer.acceptRound(new HashMap<>(), new HashSet<>()).isEmpty());
        assertTrue(debouncer.activeFaults(CHANNEL).isEmpty());
        // 第3轮异常：计数2 → 触发告警（未检测轮没有打断连续异常计数）
        List<AlertDebouncer.FaultEvent> events =
                debouncer.acceptRound(abnormal(VideoFaultType.SIGNAL_LOSS), inspected(CHANNEL));
        assertEquals(1, events.size());
        assertEquals(AlertDebouncer.FaultEvent.Type.NEW_ALERT, events.get(0).getType());
    }

    /** inspectedChannels 传 null 兼容旧调用：全部视为已检测 */
    @Test
    void nullInspectedTreatsAllAsInspected() {
        debouncer.acceptRound(abnormal(VideoFaultType.SIGNAL_LOSS), null);
        List<AlertDebouncer.FaultEvent> events = debouncer.acceptRound(abnormal(VideoFaultType.SIGNAL_LOSS), null);
        assertEquals(1, events.size());
        assertEquals(AlertDebouncer.FaultEvent.Type.NEW_ALERT, events.get(0).getType());
    }

    private Map<String, Set<VideoFaultType>> abnormal(VideoFaultType... faults) {
        Map<String, Set<VideoFaultType>> map = new HashMap<>();
        map.put(CHANNEL, EnumSet.noneOf(VideoFaultType.class));
        for (VideoFaultType f : faults) {
            map.get(CHANNEL).add(f);
        }
        return map;
    }

    private Map<String, Set<VideoFaultType>> normal() {
        Map<String, Set<VideoFaultType>> map = new HashMap<>();
        map.put(CHANNEL, EnumSet.noneOf(VideoFaultType.class));
        return map;
    }

    private static Set<String> inspected(String... channels) {
        return new HashSet<>(Arrays.asList(channels));
    }

    private static Set<VideoFaultType> signalLossSet() {
        return EnumSet.of(VideoFaultType.SIGNAL_LOSS);
    }
}
