package com.qgyun.hltgq.hltgqdevice.videoalert;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.Invocation;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * 告警查询服务单测（H5 移动端）：按通道聚合未关闭告警 / 单通道未关闭告警列表。
 */
class AlertQueryServiceTest {

    private static final String CHANNEL_A = "1000231$1$0$10";
    private static final String CHANNEL_B = "1000232$1$0$0";

    private JdbcTemplate jdbcTemplate;
    private AlertQueryService service;
    /** doAnswer 捕获的全部 queryForList 调用（rawArguments[0]=sql, rawArguments[1]=Object[] args） */
    private final List<InvocationOnMock> queryInvocations = new ArrayList<>();

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        service = new AlertQueryService();
        ReflectionTestUtils.setField(service, "jdbcTemplate", jdbcTemplate);
        queryInvocations.clear();
    }

    /** 汇总：告警表 JOIN 设备表按通道 code 分组计数，返回 total 与 byChannel；未关闭判定传 #4# */
    @Test
    void activeSummaryGroupsByChannelAndTotals() {
        Map<String, Object> rowA = new HashMap<>();
        rowA.put("channel", CHANNEL_A);
        rowA.put("cnt", 2L);
        Map<String, Object> rowB = new HashMap<>();
        rowB.put("channel", CHANNEL_B);
        rowB.put("cnt", 1L);
        // varargs 展开逐个匹配（与 DeviceTableServiceTest 惯例一致）：SQL + 单个参数 #4#
        doAnswer(inv -> {
            queryInvocations.add(inv);
            return Arrays.asList(rowA, rowB);
        }).when(jdbcTemplate).queryForList(
                argThat(sql -> sql.contains("t_auto_hltgq_water_alert")), eq("#4#"));

        Map<String, Object> result = service.activeSummary();

        assertEquals(3, result.get("total"));
        Map<?, ?> byChannel = (Map<?, ?>) result.get("byChannel");
        assertEquals(2, byChannel.get(CHANNEL_A));
        assertEquals(1, byChannel.get(CHANNEL_B));

        assertEquals(1, queryInvocations.size());
        String sql = ((Invocation) queryInvocations.get(0)).getRawArguments()[0].toString();
        Object[] args = (Object[]) ((Invocation) queryInvocations.get(0)).getRawArguments()[1];
        assertTrue(sql.contains("t_auto_hltgq_water_device"));
        assertTrue(sql.contains("GROUP BY d.code"));
        assertTrue(sql.contains("IS DISTINCT FROM"));
        assertEquals("#4#", args[0]);
    }

    /** 单通道：按设备 code 过滤，返回字段完整列表（content/time/type/level/status） */
    @Test
    void activeAlertsOfChannelQueriesByDeviceCode() {
        Map<String, Object> row = new HashMap<>();
        row.put("content", "渠首电站上游-视频智能事件 区域入侵！");
        row.put("time", new java.sql.Timestamp(1756800000000L));
        row.put("type", "#3#");
        row.put("level", "#2#");
        row.put("status", "#1#");
        // varargs 展开逐个匹配：SQL + 参数 channelId + 参数 #4#
        doAnswer(inv -> {
            queryInvocations.add(inv);
            return Arrays.asList(row);
        }).when(jdbcTemplate).queryForList(
                argThat(sql -> sql.contains("t_auto_hltgq_water_alert")), eq(CHANNEL_A), eq("#4#"));

        List<Map<String, Object>> list = service.activeAlertsOfChannel(CHANNEL_A);

        assertEquals(1, list.size());
        Map<String, Object> item = list.get(0);
        assertEquals("渠首电站上游-视频智能事件 区域入侵！", item.get("content"));
        assertEquals("#3#", item.get("type"));
        assertEquals("#2#", item.get("level"));
        assertEquals("#1#", item.get("status"));

        assertEquals(1, queryInvocations.size());
        String sql = ((Invocation) queryInvocations.get(0)).getRawArguments()[0].toString();
        Object[] args = (Object[]) ((Invocation) queryInvocations.get(0)).getRawArguments()[1];
        assertTrue(sql.contains("d.code = ?"));
        assertEquals(CHANNEL_A, args[0]);
        assertEquals("#4#", args[1]);
    }

    /** 空结果：返回空列表，不抛异常 */
    @Test
    void activeAlertsOfChannelEmptyResult() {
        doAnswer(inv -> {
            queryInvocations.add(inv);
            return new ArrayList<>();
        }).when(jdbcTemplate).queryForList(
                argThat(sql -> sql.contains("t_auto_hltgq_water_alert")), eq(CHANNEL_A), eq("#4#"));

        assertTrue(service.activeAlertsOfChannel(CHANNEL_A).isEmpty());
    }
}
