package com.qgyun.hltgq.hltgqdevice.videoalert;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.Invocation;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 视频巡检留痕与采集统计单测：落库三态映射、统计聚合口径（已结束完整窗）、区间查询与参数校验、窗数与比率纯函数。
 */
class VideoPatrolRecordServiceTest {

    private static final String CHANNEL_A = "1000231$1$0$10";
    private static final String CHANNEL_B = "1000232$1$0$0";
    private static final String CHANNEL_C = "1000233$1$0$0";

    private JdbcTemplate jdbcTemplate;
    private VideoPatrolRecordService service;
    /** doAnswer 捕获的全部 update 调用（rawArguments[0]=sql, rawArguments[1]=Object[] args） */
    private final List<InvocationOnMock> updateInvocations = new ArrayList<>();
    /** doAnswer 捕获的全部 queryForList 调用（聚合统计 SQL） */
    private final List<InvocationOnMock> queryInvocations = new ArrayList<>();

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        service = new VideoPatrolRecordService();
        ReflectionTestUtils.setField(service, "jdbcTemplate", jdbcTemplate);
        ReflectionTestUtils.setField(service, "corpCode", "hltgq");
        ReflectionTestUtils.setField(service, "windowMinutes", 30);
        updateInvocations.clear();
        queryInvocations.clear();
    }

    /** 落库：故障通道=fail+枚举名逗号分隔、正常通道=ok、检测异常通道=error，SQL 带 ON CONFLICT 幂等 */
    @Test
    void saveRoundMapsThreeStates() {
        Map<String, Set<VideoFaultType>> abnormal = new HashMap<>();
        abnormal.put(CHANNEL_A, EnumSet.of(VideoFaultType.SIGNAL_LOSS, VideoFaultType.BLUR));
        Set<String> inspected = new HashSet<>(Arrays.asList(CHANNEL_A, CHANNEL_B));
        Set<String> errors = new HashSet<>(Arrays.asList(CHANNEL_C));

        doAnswer(inv -> {
            updateInvocations.add(inv);
            return 1;
        }).when(jdbcTemplate).update(
                argThat(sql -> sql.contains("t_auto_hltgq_water_video_patrol")),
                any(), any(), any(), any(), any(), any(), any(), any());

        service.saveRound(1757400000000L, abnormal, inspected, errors);

        assertEquals(3, updateInvocations.size());
        for (InvocationOnMock inv : updateInvocations) {
            String sql = ((Invocation) inv).getRawArguments()[0].toString();
            assertTrue(sql.contains("ON CONFLICT (round_time, channel_code) DO NOTHING"));
        }
        // 按 channel_code 归类校验 result 与 fault_types
        Map<String, Object[]> byCode = new HashMap<>();
        for (InvocationOnMock inv : updateInvocations) {
            Object[] args = (Object[]) ((Invocation) inv).getRawArguments()[1];
            byCode.put(String.valueOf(args[3]), args);
        }
        assertEquals(3, byCode.size());
        assertEquals("fail", byCode.get(CHANNEL_A)[4]);
        assertEquals("SIGNAL_LOSS,BLUR", byCode.get(CHANNEL_A)[5]); // 枚举声明序拼接
        assertEquals(new Timestamp(1757400000000L), byCode.get(CHANNEL_A)[2]); // round_time=轮开始时间
        assertEquals("ok", byCode.get(CHANNEL_B)[4]);
        assertEquals(null, byCode.get(CHANNEL_B)[5]);
        assertEquals("error", byCode.get(CHANNEL_C)[4]);
        assertEquals("SYSTEM", byCode.get(CHANNEL_C)[7]);
    }

    /** 落库：全部集合为空时不做任何数据库调用 */
    @Test
    void saveRoundEmptySetsNoDbCall() {
        service.saveRound(1757400000000L, new HashMap<>(), new HashSet<>(), new HashSet<>());
        assertTrue(updateInvocations.isEmpty());
    }

    /** 落库：单行入库失败不影响其余通道（逐行 try-catch） */
    @Test
    void saveRoundSingleRowFailureDoesNotAbortRound() {
        Map<String, Set<VideoFaultType>> abnormal = new HashMap<>();
        abnormal.put(CHANNEL_A, EnumSet.of(VideoFaultType.SIGNAL_LOSS));
        Set<String> inspected = new HashSet<>(Arrays.asList(CHANNEL_B));

        doAnswer(inv -> {
            updateInvocations.add(inv);
            Object[] args = (Object[]) ((Invocation) inv).getRawArguments()[1];
            if (CHANNEL_A.equals(args[3])) {
                throw new RuntimeException("db down");
            }
            return 1;
        }).when(jdbcTemplate).update(
                argThat(sql -> sql.contains("t_auto_hltgq_water_video_patrol")),
                any(), any(), any(), any(), any(), any(), any(), any());

        service.saveRound(1757400000000L, abnormal, inspected, new HashSet<>());

        // 两通道都尝试落库，A 失败不阻断 B
        assertEquals(2, updateInvocations.size());
    }

    /** 统计：expected=参与通道数×已结束窗数，聚合只算已结束窗（round_time < cutoff），比率 1 位小数 */
    @Test
    void todayStatsAggregatesOnlyCompletedWindows() {
        when(jdbcTemplate.queryForObject(
                argThat(sql -> sql.contains("t_auto_hltgq_5nw74_vnqqef")), eq(Long.class)))
                .thenReturn(15L);
        Map<String, Object> rowOk = new HashMap<>();
        rowOk.put("result", "ok");
        rowOk.put("cnt", 10L);
        Map<String, Object> rowFail = new HashMap<>();
        rowFail.put("result", "fail");
        rowFail.put("cnt", 3L);
        doAnswer(inv -> {
            queryInvocations.add(inv);
            return Arrays.asList(rowOk, rowFail);
        }).when(jdbcTemplate).queryForList(
                argThat(sql -> sql.contains("t_auto_hltgq_water_video_patrol")),
                any(Timestamp.class), any(Timestamp.class));

        long now = System.currentTimeMillis();
        long dayStart = VideoPatrolRecordService.dayStartMillis();
        int windows = VideoPatrolRecordService.finishedWindows(now, dayStart, 30);
        Map<String, Object> stats = service.todayStats(now);

        assertEquals("视频数据", stats.get("dataType"));
        assertEquals(15L * windows, stats.get("expected"));
        assertEquals(13L, stats.get("collected"));
        assertEquals(10L, stats.get("success"));
        assertEquals(3L, stats.get("failed"));
        assertEquals(VideoPatrolRecordService.rate(10, 15L * windows), stats.get("successRate"));
        assertEquals(VideoPatrolRecordService.rate(3, 15L * windows), stats.get("failRate"));

        // 聚合 SQL 参数：dayStart=当日0点，cutoff=已结束窗上界（进行中窗起点）
        assertEquals(1, queryInvocations.size());
        String sql = ((Invocation) queryInvocations.get(0)).getRawArguments()[0].toString();
        assertTrue(sql.contains("GROUP BY result"));
        assertTrue(sql.contains("epjutj LIKE '%#5#%'"));
        Object[] args = (Object[]) ((Invocation) queryInvocations.get(0)).getRawArguments()[1];
        assertEquals(new Timestamp(dayStart), args[0]);
        assertEquals(new Timestamp(dayStart + windows * 1800000L), args[1]);
    }

    /** 统计：无视频站点（expected=0）时比率 0.0，不除零 */
    @Test
    void todayStatsZeroChannelsNoDivisionByZero() {
        when(jdbcTemplate.queryForObject(
                argThat(sql -> sql.contains("t_auto_hltgq_5nw74_vnqqef")), eq(Long.class)))
                .thenReturn(0L);
        doAnswer(inv -> {
            queryInvocations.add(inv);
            return new ArrayList<>();
        }).when(jdbcTemplate).queryForList(
                argThat(sql -> sql.contains("t_auto_hltgq_water_video_patrol")),
                any(Timestamp.class), any(Timestamp.class));

        Map<String, Object> stats = service.todayStats();

        assertEquals(0L, stats.get("expected"));
        assertEquals(0L, stats.get("collected"));
        assertEquals(0.0, stats.get("successRate"));
        assertEquals(0.0, stats.get("failRate"));
    }

    /** 统计：聚合查询失败降级返回 0 行语义，不抛异常（大屏不中断） */
    @Test
    void todayStatsQueryFailureDegradesGracefully() {
        when(jdbcTemplate.queryForObject(
                argThat(sql -> sql.contains("t_auto_hltgq_5nw74_vnqqef")), eq(Long.class)))
                .thenReturn(15L);
        doThrow(new RuntimeException("db down")).when(jdbcTemplate).queryForList(
                argThat(sql -> sql.contains("t_auto_hltgq_water_video_patrol")),
                any(Timestamp.class), any(Timestamp.class));

        Map<String, Object> stats = service.todayStats();

        assertEquals(0L, stats.get("collected"));
        assertEquals(0L, stats.get("success"));
        assertEquals(0L, stats.get("failed"));
    }

    /** 纯函数：已结束完整窗数（进行中窗不计、windowMinutes≤0 防除零）与比率四舍五入 */
    @Test
    void finishedWindowsAndRatePureFunctions() {
        assertEquals(0, VideoPatrolRecordService.finishedWindows(0, 0, 30));
        assertEquals(0, VideoPatrolRecordService.finishedWindows(1799999L, 0, 30));
        assertEquals(1, VideoPatrolRecordService.finishedWindows(1800000L, 0, 30));
        assertEquals(1, VideoPatrolRecordService.finishedWindows(2000000L, 0, 30));
        assertEquals(2, VideoPatrolRecordService.finishedWindows(3600000L, 0, 30));
        assertEquals(0, VideoPatrolRecordService.finishedWindows(1000L, 0, 0));

        assertEquals(0.0, VideoPatrolRecordService.rate(5, 0));
        assertEquals(100.0, VideoPatrolRecordService.rate(15, 15));
        assertEquals(97.6, VideoPatrolRecordService.rate(703, 720));
        assertEquals(0.0, VideoPatrolRecordService.rate(0, 720));
    }

    // ======================== 区间查询（2026-09-10 落地，口径见 数据统计.md 7.1 区间口径定稿） ========================

    /** 区间统计：expected=通道数×区间已结束窗数（末日<今天=全天窗），聚合 SQL 区间 [start0点, 末日次日0点) */
    @Test
    void rangeStatsMultiDayAggregatesRange() {
        when(jdbcTemplate.queryForObject(
                argThat(sql -> sql.contains("t_auto_hltgq_5nw74_vnqqef")), eq(Long.class)))
                .thenReturn(15L);
        Map<String, Object> rowOk = new HashMap<>();
        rowOk.put("result", "ok");
        rowOk.put("cnt", 10L);
        Map<String, Object> rowFail = new HashMap<>();
        rowFail.put("result", "fail");
        rowFail.put("cnt", 3L);
        doAnswer(inv -> {
            queryInvocations.add(inv);
            return Arrays.asList(rowOk, rowFail);
        }).when(jdbcTemplate).queryForList(
                argThat(sql -> sql.contains("t_auto_hltgq_water_video_patrol")),
                any(Timestamp.class), any(Timestamp.class));

        LocalDate start = LocalDate.of(2026, 9, 1);
        LocalDate end = LocalDate.of(2026, 9, 8);   // 历史区间（末日<今天 2026-09-10）
        LocalDate today = LocalDate.of(2026, 9, 10);
        long todayStartMs = VideoPatrolRecordService.dayStartMillis(today);
        long nowMs = todayStartMs + 15 * 3600000L;
        Map<String, Object> stats = service.rangeStats(start, end, today, todayStartMs, nowMs);

        long windows = 8L * 48; // 8 天 × 30min 窗
        assertEquals("视频数据", stats.get("dataType"));
        assertEquals(15L * windows, stats.get("expected"));
        assertEquals(13L, stats.get("collected"));
        assertEquals(10L, stats.get("success"));
        assertEquals(3L, stats.get("failed"));
        assertEquals(VideoPatrolRecordService.rate(10, 15L * windows), stats.get("successRate"));

        Object[] args = (Object[]) ((Invocation) queryInvocations.get(0)).getRawArguments()[1];
        assertEquals(new Timestamp(VideoPatrolRecordService.dayStartMillis(start)), args[0]);
        assertEquals(new Timestamp(VideoPatrolRecordService.dayStartMillis(end.plusDays(1))), args[1]);
    }

    /** 区间统计：末日=今天时截断到已结束窗上界（进行中窗不计入分母与聚合） */
    @Test
    void rangeStatsEndTodayCutsOffUnfinishedWindow() {
        when(jdbcTemplate.queryForObject(
                argThat(sql -> sql.contains("t_auto_hltgq_5nw74_vnqqef")), eq(Long.class)))
                .thenReturn(15L);
        doAnswer(inv -> {
            queryInvocations.add(inv);
            return new ArrayList<>();
        }).when(jdbcTemplate).queryForList(
                argThat(sql -> sql.contains("t_auto_hltgq_water_video_patrol")),
                any(Timestamp.class), any(Timestamp.class));

        LocalDate today = LocalDate.of(2026, 9, 10);
        long todayStartMs = VideoPatrolRecordService.dayStartMillis(today);
        long nowMs = todayStartMs + 15 * 3600000L;   // 15:00，已结束 30 个窗，第 31 个进行中
        Map<String, Object> stats = service.rangeStats(today, today, today, todayStartMs, nowMs);

        assertEquals(15L * 30, stats.get("expected")); // 只算已结束窗
        Object[] args = (Object[]) ((Invocation) queryInvocations.get(0)).getRawArguments()[1];
        assertEquals(new Timestamp(todayStartMs), args[0]);
        assertEquals(new Timestamp(todayStartMs + 30 * 1800000L), args[1]); // cutoff=15:00 进行中窗起点
    }

    /** 区间统计：endDate=未来日 → 未来日贡献 0 窗，末日按今天截断 */
    @Test
    void rangeStatsFutureEndDateZeroContribution() {
        when(jdbcTemplate.queryForObject(
                argThat(sql -> sql.contains("t_auto_hltgq_5nw74_vnqqef")), eq(Long.class)))
                .thenReturn(15L);
        doAnswer(inv -> {
            queryInvocations.add(inv);
            return new ArrayList<>();
        }).when(jdbcTemplate).queryForList(
                argThat(sql -> sql.contains("t_auto_hltgq_water_video_patrol")),
                any(Timestamp.class), any(Timestamp.class));

        LocalDate today = LocalDate.of(2026, 9, 10);
        long todayStartMs = VideoPatrolRecordService.dayStartMillis(today);
        long nowMs = todayStartMs + 15 * 3600000L;
        Map<String, Object> stats = service.rangeStats(
                LocalDate.of(2026, 9, 8), LocalDate.of(2026, 9, 20), today, todayStartMs, nowMs);

        long windows = 2L * 48 + 30; // 09-08/09-09 全天 + 09-10 已结束 30 窗（09-11~20 贡献 0）
        assertEquals(15L * windows, stats.get("expected"));
        Object[] args = (Object[]) ((Invocation) queryInvocations.get(0)).getRawArguments()[1];
        assertEquals(new Timestamp(todayStartMs + 30 * 1800000L), args[1]); // cutoff=今日已结束窗上界
    }

    /** 区间统计：聚合查询失败降级返回 0 值不抛异常（大屏不中断） */
    @Test
    void rangeStatsQueryFailureDegradesGracefully() {
        when(jdbcTemplate.queryForObject(
                argThat(sql -> sql.contains("t_auto_hltgq_5nw74_vnqqef")), eq(Long.class)))
                .thenReturn(15L);
        doThrow(new RuntimeException("db down")).when(jdbcTemplate).queryForList(
                argThat(sql -> sql.contains("t_auto_hltgq_water_video_patrol")),
                any(Timestamp.class), any(Timestamp.class));

        LocalDate today = LocalDate.of(2026, 9, 10);
        long todayStartMs = VideoPatrolRecordService.dayStartMillis(today);
        Map<String, Object> stats = service.rangeStats(today, today, today, todayStartMs, todayStartMs);

        assertEquals(0L, stats.get("collected"));
        assertEquals(0L, stats.get("success"));
        assertEquals(0L, stats.get("failed"));
        assertEquals(0.0, stats.get("successRate"));
    }

    /** 纯函数：区间已结束窗数三态（历史区间/末日今天/末日未来/区间全未来/防除零） */
    @Test
    void totalFinishedWindowsPureFunction() {
        LocalDate today = LocalDate.of(2026, 9, 10);
        long todayStartMs = VideoPatrolRecordService.dayStartMillis(today);
        long nowMs = todayStartMs + 15 * 3600000L;

        // 末日<今天：全历史日 × 48
        assertEquals(5L * 48,
                VideoPatrolRecordService.totalFinishedWindows(
                        LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 5), today, todayStartMs, nowMs, 30));
        // 末日=今天：完整历史日 × 48 + 今日已结束窗
        assertEquals(9L * 48 + 30,
                VideoPatrolRecordService.totalFinishedWindows(
                        LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 10), today, todayStartMs, nowMs, 30));
        // 末日>今天：与末日=今天同结果（未来日 0 窗）
        assertEquals(9L * 48 + 30,
                VideoPatrolRecordService.totalFinishedWindows(
                        LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 20), today, todayStartMs, nowMs, 30));
        // 单日=今天：与 todayStats 的 finishedWindows 一致
        assertEquals(30L,
                VideoPatrolRecordService.totalFinishedWindows(
                        today, today, today, todayStartMs, nowMs, 30));
        // 区间全在未来：0
        assertEquals(0L,
                VideoPatrolRecordService.totalFinishedWindows(
                        LocalDate.of(2026, 9, 11), LocalDate.of(2026, 9, 20), today, todayStartMs, nowMs, 30));
        // 窗长非法：0
        assertEquals(0L,
                VideoPatrolRecordService.totalFinishedWindows(
                        LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 5), today, todayStartMs, nowMs, 0));
    }

    /** 纯函数：聚合 SQL 上界（末日<今天 → 次日0点；末日≥今天 → 今日已结束窗上界） */
    @Test
    void rangeCutoffPureFunction() {
        LocalDate today = LocalDate.of(2026, 9, 10);
        long todayStartMs = VideoPatrolRecordService.dayStartMillis(today);
        long nowMs = todayStartMs + 15 * 3600000L;

        assertEquals(VideoPatrolRecordService.dayStartMillis(LocalDate.of(2026, 9, 9)),
                VideoPatrolRecordService.rangeCutoff(
                        LocalDate.of(2026, 9, 8), today, todayStartMs, nowMs, 30));
        assertEquals(todayStartMs + 30 * 1800000L,
                VideoPatrolRecordService.rangeCutoff(today, today, todayStartMs, nowMs, 30));
        assertEquals(todayStartMs + 30 * 1800000L,
                VideoPatrolRecordService.rangeCutoff(
                        LocalDate.of(2026, 9, 20), today, todayStartMs, nowMs, 30));
        assertEquals(todayStartMs,
                VideoPatrolRecordService.rangeCutoff(today, today, todayStartMs, nowMs, 0));
    }

    /** 参数解析校验：缺省规则/只传一端/格式非法/倒置/730 天上限 */
    @Test
    void parseRangeDefaultsAndValidation() {
        LocalDate today = LocalDate.of(2026, 9, 10);

        // 都空 = 今日
        assertArrayEquals(new LocalDate[]{today, today},
                VideoPatrolRecordService.parseRange(null, null, today, 730));
        assertArrayEquals(new LocalDate[]{today, today},
                VideoPatrolRecordService.parseRange("", "  ", today, 730));
        // 只传 startDate = 该日至今日
        assertArrayEquals(new LocalDate[]{LocalDate.of(2026, 9, 1), today},
                VideoPatrolRecordService.parseRange("2026-09-01", null, today, 730));
        // 只传 endDate = 单日
        assertArrayEquals(new LocalDate[]{LocalDate.of(2026, 9, 3), LocalDate.of(2026, 9, 3)},
                VideoPatrolRecordService.parseRange(null, "2026-09-03", today, 730));
        // 两端都传 = 原样
        assertArrayEquals(new LocalDate[]{LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 3)},
                VideoPatrolRecordService.parseRange("2026-09-01", "2026-09-03", today, 730));

        // 格式非法
        assertThrows(IllegalArgumentException.class,
                () -> VideoPatrolRecordService.parseRange("2026/09/01", null, today, 730));
        // 倒置
        assertThrows(IllegalArgumentException.class,
                () -> VideoPatrolRecordService.parseRange("2026-09-05", "2026-09-01", today, 730));
        // 上限：DAYS.between=729（含两端 730 天）允许；=730（731 天）拒绝
        assertArrayEquals(new LocalDate[]{LocalDate.of(2024, 9, 10), LocalDate.of(2026, 9, 9)},
                VideoPatrolRecordService.parseRange("2024-09-10", "2026-09-09", today, 730));
        assertThrows(IllegalArgumentException.class,
                () -> VideoPatrolRecordService.parseRange("2024-09-10", "2026-09-10", today, 730));
    }
}
