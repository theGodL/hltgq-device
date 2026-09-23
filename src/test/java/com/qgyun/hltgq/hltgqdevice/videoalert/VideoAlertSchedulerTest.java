package com.qgyun.hltgq.hltgqdevice.videoalert;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 轮巡调度器单测：夜间豁免时段判断（跨天窗口边界、同日窗口、未启用）。
 */
class VideoAlertSchedulerTest {

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

    private static long at(int hour, int minute) {
        return LocalDateTime.of(2026, 9, 23, hour, minute)
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}
