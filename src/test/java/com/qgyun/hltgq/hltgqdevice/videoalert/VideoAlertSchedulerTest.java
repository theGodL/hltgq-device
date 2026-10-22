package com.qgyun.hltgq.hltgqdevice.videoalert;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 轮巡调度器单测：夜间豁免时段判断（跨天窗口边界、同日窗口、未启用）、全天提示类与夜间豁免合并口径、
 * 重启后恢复对账调用。
 */
class VideoAlertSchedulerTest {

    /** 黑白/偏色全天提示；过暗仅夜间提示（规则性现象不报警、不计故障） */
    @Test
    void noticeFaultsAllDayAndNightFaultsOnlyAtNight() {
        VideoAlertScheduler scheduler = new VideoAlertScheduler();
        ReflectionTestUtils.setField(scheduler, "noticeFaultsConfig", "GRAYSCALE,COLOR_CAST");
        ReflectionTestUtils.setField(scheduler, "nightExemptEnabled", true);
        ReflectionTestUtils.setField(scheduler, "nightExemptWindow", "18:30-07:30");
        ReflectionTestUtils.setField(scheduler, "nightExemptFaultsConfig", "TOO_DARK");
        scheduler.init();

        // 白天：只有全天提示类（黑白/偏色），过暗仍按故障处理
        assertEquals(EnumSet.of(VideoFaultType.GRAYSCALE, VideoFaultType.COLOR_CAST),
                scheduler.effectiveExemptFaults(at(12, 0)));
        // 夜间：叠加过暗
        assertEquals(EnumSet.of(VideoFaultType.GRAYSCALE, VideoFaultType.TOO_DARK, VideoFaultType.COLOR_CAST),
                scheduler.effectiveExemptFaults(at(20, 0)));
    }

    /** 黑白为设备正常输出（黑白机/夜间红外）：检测到也直接剔除，混合故障只保留其余项 */
    @Test
    void ignoreGrayscaleBeforeNoticeAndAlert() {
        Set<VideoFaultType> ignored = EnumSet.of(VideoFaultType.GRAYSCALE);
        assertTrue(VideoAlertScheduler.removeIgnored(
                EnumSet.of(VideoFaultType.GRAYSCALE), ignored).isEmpty(), "纯黑白应完全忽略");
        assertEquals(EnumSet.of(VideoFaultType.NOISE), VideoAlertScheduler.removeIgnored(
                EnumSet.of(VideoFaultType.GRAYSCALE, VideoFaultType.NOISE), ignored), "混合故障应保留其余项");
        assertEquals(EnumSet.of(VideoFaultType.GRAYSCALE), VideoAlertScheduler.removeIgnored(
                EnumSet.of(VideoFaultType.GRAYSCALE), Collections.emptySet()), "未配置忽略时保持原样");
    }

    /** 18:30-07:30 跨天窗口：左闭右开，覆盖前夜后半段与当日凌晨 */
    @Test
    void crossMidnightWindow() {
        int start = 18 * 60 + 30;
        int end = 7 * 60 + 30;
        assertFalse(VideoAlertScheduler.inTimeWindow(start, end, at(18, 29)), "窗口开始前不算");
        assertTrue(VideoAlertScheduler.inTimeWindow(start, end, at(18, 30)), "窗口开始含边界");
        assertTrue(VideoAlertScheduler.inTimeWindow(start, end, at(23, 59)), "前夜在窗口内");
        assertTrue(VideoAlertScheduler.inTimeWindow(start, end, at(0, 0)), "跨天后凌晨在窗口内");
        assertTrue(VideoAlertScheduler.inTimeWindow(start, end, at(7, 0)), "凌晨仍在窗口内");
        assertFalse(VideoAlertScheduler.inTimeWindow(start, end, at(7, 30)), "窗口结束不含边界");
        assertFalse(VideoAlertScheduler.inTimeWindow(start, end, at(12, 0)), "白天不在窗口内");
    }

    /** 同日窗口（start<=end）：常规区间判定 */
    @Test
    void sameDayWindow() {
        assertTrue(VideoAlertScheduler.inTimeWindow(9 * 60, 17 * 60, at(10, 0)));
        assertFalse(VideoAlertScheduler.inTimeWindow(9 * 60, 17 * 60, at(8, 0)));
        assertFalse(VideoAlertScheduler.inTimeWindow(9 * 60, 17 * 60, at(17, 0)));
    }

    /** 未启用/解析失败（任一端为负）→ 恒 false（宁可照常告警，不静默丢警） */
    @Test
    void disabledWhenNotConfigured() {
        assertFalse(VideoAlertScheduler.inTimeWindow(-1, 7 * 60 + 30, at(23, 0)));
        assertFalse(VideoAlertScheduler.inTimeWindow(18 * 60 + 30, -1, at(23, 0)));
    }

    /** 重启后对账：每轮按 recover-threshold 调用服务对账（防抖内存清零兜底） */
    @Test
    void reconcileRecoveredAlertsUsesConfiguredThreshold() {
        VideoAlertScheduler scheduler = new VideoAlertScheduler();
        VideoAlertService alertService = mock(VideoAlertService.class);
        ReflectionTestUtils.setField(scheduler, "alertService", alertService);
        ReflectionTestUtils.setField(scheduler, "recoverThreshold", 2);
        when(alertService.reconcileRecovered(2)).thenReturn(3);

        assertEquals(3, scheduler.reconcileRecoveredAlerts());
        verify(alertService).reconcileRecovered(2);
    }

    /** 对账异常不影响巡检：返回 0 */
    @Test
    void reconcileRecoveredAlertsSwallowsException() {
        VideoAlertScheduler scheduler = new VideoAlertScheduler();
        VideoAlertService alertService = mock(VideoAlertService.class);
        ReflectionTestUtils.setField(scheduler, "alertService", alertService);
        ReflectionTestUtils.setField(scheduler, "recoverThreshold", 2);
        when(alertService.reconcileRecovered(2)).thenThrow(new RuntimeException("db down"));

        assertEquals(0, scheduler.reconcileRecoveredAlerts());
    }

    /** 启动后立即对账一次：重启清零防抖状态的历史告警不等下一轮巡检 */
    @Test
    void initReconcilesRecoveredAlertsOnStartup() {
        VideoAlertScheduler scheduler = new VideoAlertScheduler();
        VideoAlertService alertService = mock(VideoAlertService.class);
        ReflectionTestUtils.setField(scheduler, "alertService", alertService);
        ReflectionTestUtils.setField(scheduler, "recoverThreshold", 2);
        when(alertService.reconcileRecovered(2)).thenReturn(1);

        scheduler.init();

        verify(alertService).reconcileRecovered(2);
    }

    /** 本轮对账使用本轮开始时间作为巡检轮次下界，避免陈旧巡检数据误关 */
    @Test
    void roundReconcileUsesRoundStartBoundary() {
        VideoAlertScheduler scheduler = new VideoAlertScheduler();
        VideoAlertService alertService = mock(VideoAlertService.class);
        ReflectionTestUtils.setField(scheduler, "alertService", alertService);
        ReflectionTestUtils.setField(scheduler, "recoverThreshold", 2);
        Timestamp roundStart = new Timestamp(1757400000000L);
        when(alertService.reconcileRecovered(2, roundStart)).thenReturn(1);

        assertEquals(1, scheduler.reconcileRecoveredAlerts(roundStart));
        verify(alertService).reconcileRecovered(2, roundStart);
    }

    private static long at(int hour, int minute) {
        return LocalDateTime.of(2026, 9, 23, hour, minute)
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}
