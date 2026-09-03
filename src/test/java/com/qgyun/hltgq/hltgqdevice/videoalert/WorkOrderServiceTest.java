package com.qgyun.hltgq.hltgqdevice.videoalert;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.Invocation;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 视频告警工单服务单测：工单编号规则、负责部门判定（库上/库下/站码为空）、
 * 生成去重、恢复联动关闭仅#1#待处理（人工介入保护）、动态列适配。
 */
class WorkOrderServiceTest {

    private static final String SITE_ID = "site-001";
    private static final String DEVICE_ID = "device-001";
    private static final String TITLE = "渠首电站上游-视频 信号丢失";
    private static final String CONTENT = "渠首电站上游-视频 信号丢失！";
    private static final String ALERT_ID = "alert-001";

    private JdbcTemplate jdbcTemplate;
    private WorkOrderService service;
    /** doAnswer 捕获的全部 update 调用（原始参数，绕开 Mockito varargs 展开匹配） */
    private final List<InvocationOnMock> updateInvocations = new ArrayList<>();

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        service = new WorkOrderService();
        ReflectionTestUtils.setField(service, "jdbcTemplate", jdbcTemplate);
        ReflectionTestUtils.setField(service, "corpCode", "hltgq");
        // 绕过 init 的 DB 依赖，直接预置解析结果（init 的加载逻辑由 initLoadsMetadataAndOrgIds 单独验证）
        ReflectionTestUtils.setField(service, "workOrderColumns", new HashSet<>());
        ReflectionTestUtils.setField(service, "upStcdSet",
                new HashSet<>(Arrays.asList("3206400001", "3206400002")));
        ReflectionTestUtils.setField(service, "upOrgId", "org-up-id");
        ReflectionTestUtils.setField(service, "downOrgId", "org-down-id");
        updateInvocations.clear();
        doAnswer(inv -> {
            updateInvocations.add(inv);
            return 1;
        }).when(jdbcTemplate).update(anyString(), any(Object.class));
    }

    /** 工单编号：GD+yyyyMMddHHmmss+3位序号，同秒递增、跨秒重置（与 hltgq-mq 一致） */
    @Test
    void workOrderCodeSequence() throws Exception {
        Method m = WorkOrderService.class.getDeclaredMethod("genWorkOrderCode", Timestamp.class);
        m.setAccessible(true);
        Timestamp t1 = Timestamp.valueOf("2026-01-01 10:00:00");
        assertEquals("GD20260101100000001", m.invoke(service, t1));
        assertEquals("GD20260101100000002", m.invoke(service, t1));
        assertEquals("GD20260101100000003", m.invoke(service, t1));
        Timestamp t2 = Timestamp.valueOf("2026-01-01 10:00:01");
        assertEquals("GD20260101100001001", m.invoke(service, t2));
    }

    /** init：列元数据归一化 + 库上清单解析（大写归一）+ 部门ID按code解析 */
    @Test
    @SuppressWarnings("unchecked")
    void initLoadsMetadataAndOrgIds() {
        when(jdbcTemplate.queryForList(anyString(), eq(String.class)))
                .thenReturn(Arrays.asList("ID", "Corp_Code", "title", "org"));
        Map<String, Object> up = new HashMap<>();
        up.put("id", "org-up-id");
        up.put("code", "00000003");
        Map<String, Object> down = new HashMap<>();
        down.put("id", "org-down-id");
        down.put("code", "00000004");
        when(jdbcTemplate.queryForList(anyString(), eq("hltgq"), eq("00000003"), eq("00000004")))
                .thenReturn(Arrays.asList(up, down));
        ReflectionTestUtils.setField(service, "upStcdConfig", "3206400001, 3206400002,320640000A");

        service.init();

        assertEquals("org-up-id", ReflectionTestUtils.getField(service, "upOrgId"));
        assertEquals("org-down-id", ReflectionTestUtils.getField(service, "downOrgId"));
        Set<String> upStcd = (Set<String>) ReflectionTestUtils.getField(service, "upStcdSet");
        assertEquals(new HashSet<>(Arrays.asList("3206400001", "3206400002", "320640000A")), upStcd);
        Set<String> cols = (Set<String>) ReflectionTestUtils.getField(service, "workOrderColumns");
        assertTrue(cols.contains("id"));
        assertTrue(cols.contains("corp_code"));
        assertTrue(cols.contains("title"));
        assertTrue(cols.contains("org"));
    }

    /** 库上站点（stcd 在清单内）→ org=库上防汛办ID，status=#1#，alert 精确关联 */
    @Test
    void createIfAbsentUpstreamAssignsUpOrg() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(), any(), any(), any(), any()))
                .thenReturn(0);
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), eq(SITE_ID)))
                .thenReturn(Arrays.asList("3206400001"));

        service.createIfAbsent(ALERT_ID, SITE_ID, DEVICE_ID, TITLE, CONTENT);

        assertEquals(1, updateInvocations.size());
        String sql = (String) ((Invocation) updateInvocations.get(0)).getRawArguments()[0];
        Object[] args = (Object[]) ((Invocation) updateInvocations.get(0)).getRawArguments()[1];
        List<Object> vals = new ArrayList<>(Arrays.asList(args));
        // org 为方言保留字需双引号；status=#1# 待处理；alert 存告警ID精确关联
        assertTrue(sql.contains("\"org\""));
        assertTrue(vals.contains("org-up-id"));
        assertTrue(vals.contains("#1#"));
        assertTrue(vals.contains(ALERT_ID));
        assertTrue(vals.contains(SITE_ID));
        assertTrue(vals.contains(DEVICE_ID));
        assertTrue(vals.contains(TITLE));
        assertTrue(vals.contains(CONTENT));
        assertTrue(vals.contains("hltgq"));
        // code 以 GD 开头、总长 19 位（GD + yyyyMMddHHmmss 14位 + 3位序号）
        boolean codeChecked = false;
        for (Object v : vals) {
            if (v instanceof String && ((String) v).startsWith("GD")) {
                assertEquals(19, ((String) v).length());
                codeChecked = true;
            }
        }
        assertTrue(codeChecked, "工单编号 code 应存在且以 GD 开头");
    }

    /** 清单外站点 → org=库下防汛办ID（水库下站点归库下防汛办） */
    @Test
    void createIfAbsentDownstreamAssignsDownOrg() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(), any(), any(), any(), any()))
                .thenReturn(0);
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), eq(SITE_ID)))
                .thenReturn(Arrays.asList("3206409999"));

        service.createIfAbsent(ALERT_ID, SITE_ID, DEVICE_ID, TITLE, CONTENT);

        Object[] args = (Object[]) ((Invocation) updateInvocations.get(0)).getRawArguments()[1];
        List<Object> vals = Arrays.asList(args);
        assertTrue(vals.contains("org-down-id"));
        assertFalse(vals.contains("org-up-id"));
    }

    /** 站码为空（视频站点 iofhpi 多为空）→ 默认归库下防汛办 */
    @Test
    void createIfAbsentBlankStcdDefaultsDownOrg() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(), any(), any(), any(), any()))
                .thenReturn(0);
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), eq(SITE_ID)))
                .thenReturn(Arrays.asList(""));

        service.createIfAbsent(ALERT_ID, SITE_ID, DEVICE_ID, TITLE, CONTENT);

        Object[] args = (Object[]) ((Invocation) updateInvocations.get(0)).getRawArguments()[1];
        List<Object> vals = Arrays.asList(args);
        assertTrue(vals.contains("org-down-id"));
        assertFalse(vals.contains("org-up-id"));
    }

    /** 去重：同 site+device+title 未关闭工单已存在 → 不重复生成（且不再查站码） */
    @Test
    void createIfAbsentSkipsWhenUnclosedExists() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(), any(), any(), any(), any()))
                .thenReturn(1);

        service.createIfAbsent(ALERT_ID, SITE_ID, DEVICE_ID, TITLE, CONTENT);

        assertTrue(updateInvocations.isEmpty());
        verify(jdbcTemplate, never()).queryForList(anyString(), eq(String.class), eq(SITE_ID));
    }

    /** siteId 为空 → 直接跳过，无任何数据库写操作 */
    @Test
    void createIfAbsentNullSiteSkips() {
        service.createIfAbsent(ALERT_ID, null, DEVICE_ID, TITLE, CONTENT);
        assertTrue(updateInvocations.isEmpty());
        verify(jdbcTemplate, never())
                .queryForObject(anyString(), eq(Integer.class), any(), any(), any(), any(), any());
    }

    /** 恢复联动关闭：仅关闭 status=#1# 待处理工单，人工已介入（#2#处理中/#3#已关闭/#4#已取消）不被扭转 */
    @Test
    void closeByContentClosesOnlyPendingOrders() {
        service.closeByContent(SITE_ID, DEVICE_ID, CONTENT);

        assertEquals(1, updateInvocations.size());
        String sql = (String) ((Invocation) updateInvocations.get(0)).getRawArguments()[0];
        Object[] args = (Object[]) ((Invocation) updateInvocations.get(0)).getRawArguments()[1];
        List<Object> vals = Arrays.asList(args);
        // SET status=#3# 已关闭
        assertEquals("#3#", vals.get(0));
        assertTrue(vals.contains(SITE_ID));
        assertTrue(vals.contains(DEVICE_ID));
        assertTrue(vals.contains(CONTENT));
        // WHERE 仅匹配 status=#1# 待处理（人工介入后系统不再扭转）
        assertTrue(vals.contains("#1#"));
        assertTrue(sql.contains("AND status = ?"));
        assertEquals(0, countOccurrences(sql, "status IS DISTINCT FROM"));
    }

    /** 动态列适配：列元数据不含 alert 时，insert SQL 跳过该列（工单表无 alert 列也能入库） */
    @Test
    void insertSkipsMissingAlertColumn() {
        Set<String> columns = new HashSet<>(Arrays.asList(
                "id", "corp_code", "created_at", "created_by", "updated_at", "updated_by",
                "code", "title", "content", "site", "device", "org", "status"));
        ReflectionTestUtils.setField(service, "workOrderColumns", columns);
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(), any(), any(), any(), any()))
                .thenReturn(0);
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), eq(SITE_ID)))
                .thenReturn(Arrays.asList("3206400001"));

        service.createIfAbsent(ALERT_ID, SITE_ID, DEVICE_ID, TITLE, CONTENT);

        String sql = (String) ((Invocation) updateInvocations.get(0)).getRawArguments()[0];
        assertFalse(sql.toLowerCase().contains("alert"));
        assertTrue(sql.toLowerCase().contains("title"));
    }

    private static int countOccurrences(String s, String sub) {
        int count = 0;
        int idx = 0;
        while ((idx = s.indexOf(sub, idx)) >= 0) {
            count++;
            idx += sub.length();
        }
        return count;
    }
}
