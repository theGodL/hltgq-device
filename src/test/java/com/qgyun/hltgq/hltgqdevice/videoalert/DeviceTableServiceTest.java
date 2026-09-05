package com.qgyun.hltgq.hltgqdevice.videoalert;

import com.qgyun.hltgq.hltgqdevice.service.DeviceTableService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatcher;
import org.mockito.invocation.Invocation;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 视频设备表服务单测：设备名派生、首次创建、缓存复用、动态列适配、
 * 状态更新（仅变化写库）、批量标离线、创建失败重试、启动迁移（历史device迁移，幂等）。
 */
class DeviceTableServiceTest {

    private static final String SITE_ID = "site-001";
    private static final String SITE_NAME = "渠首电站上游";
    private static final String DEVICE_NAME = SITE_NAME + "摄像机#";
    private static final String DEVICECODE = "1000231$1$0$10";
    private static final String ORG_NAME = "库上防汛办";
    private static final String LOCATION = ORG_NAME + "-" + SITE_NAME;

    private JdbcTemplate jdbcTemplate;
    private DeviceTableService service;
    /** doAnswer 捕获的全部 update 调用（原始参数，绕开 Mockito varargs 展开匹配） */
    private final List<InvocationOnMock> updateInvocations = new ArrayList<>();

    /**
     * null 安全的 SQL 子串匹配（Mockito 对参数数量不匹配的 varargs 调用会以 null 调 matcher，
     * 直接 s.contains 会 NPE）。
     */
    private static ArgumentMatcher<String> sqlContains(String... subs) {
        return s -> {
            if (s == null) {
                return false;
            }
            for (String sub : subs) {
                if (!s.contains(sub)) {
                    return false;
                }
            }
            return true;
        };
    }

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        service = new DeviceTableService();
        ReflectionTestUtils.setField(service, "jdbcTemplate", jdbcTemplate);
        ReflectionTestUtils.setField(service, "corpCode", "hltgq");
        ReflectionTestUtils.setField(service, "deviceSuffix", "摄像机");
        updateInvocations.clear();
        doAnswer(inv -> {
            updateInvocations.add(inv);
            return 1;
        }).when(jdbcTemplate).update(anyString(), any(Object.class));
    }

    /** 设备名派生：站点名 + 后缀 + "#"，空站点名返回 null */
    @Test
    void deviceNameOfAppendsSuffixAndHash() {
        assertEquals(DEVICE_NAME, service.deviceNameOf(SITE_NAME));
        assertNull(service.deviceNameOf(null));
        assertNull(service.deviceNameOf("  "));
    }

    /** 首次创建：INSERT 设备表，字段含 name/site/type=#5#/code/status/位置/启用日期/系统字段 */
    @Test
    void createDeviceFirstTime() {
        when(jdbcTemplate.queryForList(argThat(sqlContains("SELECT id FROM", "WHERE code")),
                eq(String.class), eq(DEVICECODE)))
                .thenReturn(new ArrayList<>());
        when(jdbcTemplate.queryForList(argThat(sqlContains("SELECT id FROM", "WHERE name")),
                eq(String.class), eq(DEVICE_NAME)))
                .thenReturn(new ArrayList<>());

        String id = service.lookupOrCreateDevice(DEVICE_NAME, SITE_ID,
                DeviceTableService.DEVICE_TYPE_VIDEO, DEVICECODE, "#1#", LOCATION);

        assertNotNull(id);
        assertEquals(20, id.length());
        assertEquals(1, updateInvocations.size());
        String sql = (String) ((Invocation) updateInvocations.get(0)).getRawArguments()[0];
        Object[] args = (Object[]) ((Invocation) updateInvocations.get(0)).getRawArguments()[1];
        assertTrue(sql.contains("INSERT INTO"));
        assertTrue(sql.contains("t_auto_hltgq_water_device"));
        // type 为 SQL 方言关键字，加双引号保险
        assertTrue(sql.contains("\"type\""));
        List<Object> vals = Arrays.asList(args);
        assertTrue(vals.contains(id));
        assertTrue(vals.contains(DEVICE_NAME));
        assertTrue(vals.contains(SITE_ID));
        assertTrue(vals.contains(DeviceTableService.DEVICE_TYPE_VIDEO));
        assertTrue(vals.contains(DEVICECODE));
        assertTrue(vals.contains("#1#"));
        assertTrue(vals.contains("hltgq"));
        // 安装位置 wlcvig=组织-站点名；tm/ptlink 无真实数据源不写入（宁缺毋滥）
        assertTrue(sql.contains("wlcvig"));
        assertTrue(vals.contains(LOCATION));
        assertFalse(sql.contains(", tm,"));
        assertFalse(sql.contains("ptlink"));
        long timestampCount = vals.stream().filter(v -> v instanceof java.sql.Timestamp).count();
        assertEquals(2, timestampCount, "仅 created_at/updated_at 两个系统时间戳");
    }

    /** 缓存复用：同设备 code 第二次调用不再查库/写库（code 为唯一匹配键） */
    @Test
    void cachedReuseSkipsSecondLookup() {
        when(jdbcTemplate.queryForList(argThat(sqlContains("SELECT id FROM", "WHERE code")),
                eq(String.class), eq(DEVICECODE)))
                .thenReturn(new ArrayList<>());
        when(jdbcTemplate.queryForList(argThat(sqlContains("SELECT id FROM", "WHERE name")),
                eq(String.class), eq(DEVICE_NAME)))
                .thenReturn(new ArrayList<>());

        String id1 = service.lookupOrCreateDevice(DEVICE_NAME, SITE_ID, "#5#", DEVICECODE, "#1#", LOCATION);
        String id2 = service.lookupOrCreateDevice(DEVICE_NAME, SITE_ID, "#5#", DEVICECODE, "#1#", LOCATION);

        assertEquals(id1, id2);
        // 首次调用：按 code 查（未命中）→ 按 name 查（未命中）→ 创建；第二次全走缓存
        verify(jdbcTemplate, times(1)).queryForList(
                argThat(sqlContains("SELECT id FROM", "WHERE code")),
                eq(String.class), eq(DEVICECODE));
        verify(jdbcTemplate, times(1)).queryForList(
                argThat(sqlContains("SELECT id FROM", "WHERE name")),
                eq(String.class), eq(DEVICE_NAME));
        verify(jdbcTemplate, times(1)).update(anyString(), any(Object.class));
    }

    /** 动态列适配：设备表无 code/wlcvig 列时 INSERT SQL 跳过这些列 */
    @Test
    void dynamicColumnAdaptionSkipsMissingColumns() {
        ReflectionTestUtils.setField(service, "deviceColumns", new HashSet<>(Arrays.asList(
                "id", "corp_code", "created_at", "created_by", "updated_at", "updated_by",
                "name", "site", "type", "status")));
        when(jdbcTemplate.queryForList(argThat(sqlContains("SELECT id FROM", "WHERE code")),
                eq(String.class), eq(DEVICECODE)))
                .thenReturn(new ArrayList<>());
        when(jdbcTemplate.queryForList(argThat(sqlContains("SELECT id FROM", "WHERE name")),
                eq(String.class), eq(DEVICE_NAME)))
                .thenReturn(new ArrayList<>());

        String id = service.lookupOrCreateDevice(DEVICE_NAME, SITE_ID, "#5#", DEVICECODE, "#1#", LOCATION);

        assertNotNull(id);
        String sql = (String) ((Invocation) updateInvocations.get(0)).getRawArguments()[0];
        // 列名形式匹配（", code,"），避免误匹配 corp_code 子串
        assertFalse(sql.contains(", code,"));
        assertFalse(sql.contains("wlcvig"));
        assertTrue(sql.contains("\"type\""));
    }

    /** 状态更新：按 code（通道devicecode）精确匹配，仅状态变化写库（SQL 含 status IS DISTINCT FROM 条件） */
    @Test
    void updateDeviceStatusOnlyUpdatesWhenChanged() {
        when(jdbcTemplate.update(contains("t_auto_hltgq_water_device"), any(), any(), any(), any()))
                .thenReturn(1);

        int rows = service.updateDeviceStatus(DEVICECODE, "#2#");

        assertEquals(1, rows);
        verify(jdbcTemplate).update(
                argThat(s -> s.contains("status IS DISTINCT FROM")),
                eq("#2#"), any(), eq(DEVICECODE), eq("#2#"));
    }

    /** 安装位置更新：按 code 精确匹配，仅位置变化写库（SQL 含 wlcvig IS DISTINCT FROM 条件，值截去首尾空白） */
    @Test
    void updateDeviceLocationOnlyUpdatesWhenChanged() {
        when(jdbcTemplate.update(contains("t_auto_hltgq_water_device"), any(), any(), any(), any()))
                .thenReturn(1);

        int rows = service.updateDeviceLocation(DEVICECODE, " " + LOCATION + " ");

        assertEquals(1, rows);
        verify(jdbcTemplate).update(
                argThat(s -> s.contains("wlcvig IS DISTINCT FROM")),
                eq(LOCATION), any(), eq(DEVICECODE), eq(LOCATION));
    }

    /** 创建失败：返回 null 且不缓存，下次调用重试成功 */
    @Test
    void createFailureReturnsNullAndRetriesNextCall() {
        when(jdbcTemplate.queryForList(argThat(sqlContains("SELECT id FROM", "WHERE code")),
                eq(String.class), eq(DEVICECODE)))
                .thenReturn(new ArrayList<>());
        when(jdbcTemplate.queryForList(argThat(sqlContains("SELECT id FROM", "WHERE name")),
                eq(String.class), eq(DEVICE_NAME)))
                .thenReturn(new ArrayList<>());
        // 覆盖 setUp 的 doAnswer：第一次 UPDATE 抛异常（模拟数据库不可达），第二次成功
        when(jdbcTemplate.update(anyString(), any(Object.class)))
                .thenThrow(new RuntimeException("db down"))
                .thenReturn(1);

        assertNull(service.lookupOrCreateDevice(DEVICE_NAME, SITE_ID, "#5#", DEVICECODE, "#1#", LOCATION));
        // 失败未缓存，重试成功
        String id = service.lookupOrCreateDevice(DEVICE_NAME, SITE_ID, "#5#", DEVICECODE, "#1#", LOCATION);
        assertNotNull(id);
    }

    /** 批量标离线：按站点ID分批 UPDATE 设备 status=#2#，仅状态非离线行 */
    @Test
    void markOfflineBySiteIdsUpdatesDevices() {
        when(jdbcTemplate.update(anyString(), any(Object.class))).thenReturn(2);

        int rows = service.markOfflineBySiteIds(Arrays.asList("s1", "s2", "s3"));

        assertEquals(2, rows);
        verify(jdbcTemplate).update(
                argThat(sqlContains("t_auto_hltgq_water_device", "site IN (?, ?, ?)",
                        "status IS DISTINCT FROM")),
                any(Object.class));
    }

    /** 设备表无 status 列：批量标离线自动跳过（动态列适配） */
    @Test
    void markOfflineSkipsWhenNoStatusColumn() {
        ReflectionTestUtils.setField(service, "deviceColumns",
                new HashSet<>(Arrays.asList("id", "name", "site")));

        int rows = service.markOfflineBySiteIds(Arrays.asList("s1"));

        assertEquals(0, rows);
        verify(jdbcTemplate, never()).update(anyString(), any(Object.class));
    }

    /**
     * 启动迁移：为历史视频站点补建设备，并把告警/工单中 device=站点ID 的历史行
     * 更新为设备ID（UPDATE 条件 device=站点ID → 幂等，迁移后不再命中）。
     */
    @Test
    void migrateLegacyDeviceRefsUpdatesAlertAndWorkOrder() {
        // 列元数据：设备表空（不跳过列）、工单表含 device 列
        when(jdbcTemplate.queryForList(argThat(sqlContains("information_schema", "t_auto_hltgq_water_device")),
                eq(String.class)))
                .thenReturn(new ArrayList<>());
        when(jdbcTemplate.queryForList(argThat(sqlContains("information_schema", "t_auto_hltgq_water_work_order")),
                eq(String.class)))
                .thenReturn(Collections.singletonList("device"));
        // 历史视频站点（mivbcz=站点位置/组织名，zebpsu=站点状态）
        Map<String, Object> station = new HashMap<>();
        station.put("id", SITE_ID);
        station.put("devicecode", DEVICECODE);
        station.put("zzkaec", SITE_NAME);
        station.put("mivbcz", ORG_NAME);
        station.put("zebpsu", "#1#");
        when(jdbcTemplate.queryForList(argThat(sqlContains("SELECT id, devicecode, zzkaec, mivbcz, zebpsu",
                "t_auto_hltgq_5nw74_vnqqef"))))
                .thenReturn(Collections.singletonList(station));
        // 设备查找：按 code 未命中 → 按 name 兜底未命中 → 创建
        when(jdbcTemplate.queryForList(argThat(sqlContains("SELECT id FROM", "WHERE code")),
                eq(String.class), eq(DEVICECODE)))
                .thenReturn(new ArrayList<>());
        when(jdbcTemplate.queryForList(argThat(sqlContains("SELECT id FROM", "WHERE name")),
                eq(String.class), eq(DEVICE_NAME)))
                .thenReturn(new ArrayList<>());

        service.init();

        // 5 次 UPDATE：INSERT 设备 + 状态对齐 + 安装位置回填 + UPDATE 告警 + UPDATE 工单
        assertEquals(5, updateInvocations.size());
        String insertSql = (String) ((Invocation) updateInvocations.get(0)).getRawArguments()[0];
        Object[] insertArgs = (Object[]) ((Invocation) updateInvocations.get(0)).getRawArguments()[1];
        assertTrue(insertSql.contains("INSERT INTO"));
        String deviceId = String.valueOf(insertArgs[0]);
        assertNotNull(deviceId);
        assertTrue(Arrays.asList(insertArgs).contains(DEVICE_NAME));
        // 安装位置：与站点位置 mivbcz 同源直取（不拼接站点名）
        assertTrue(insertSql.contains("wlcvig"));
        assertTrue(Arrays.asList(insertArgs).contains(ORG_NAME));
        // 创建即带站点状态（zebpsu 传入 createDevice）
        assertTrue(insertSql.contains("status"));
        assertTrue(Arrays.asList(insertArgs).contains("#1#"));

        // 状态对齐 UPDATE：SET status = ?（变化才写库），按 code 精确匹配
        String statusSql = (String) ((Invocation) updateInvocations.get(1)).getRawArguments()[0];
        Object[] statusArgs = (Object[]) ((Invocation) updateInvocations.get(1)).getRawArguments()[1];
        assertTrue(statusSql.contains("SET status = ?"));
        assertEquals(DEVICECODE, statusArgs[2]);

        // 安装位置回填 UPDATE：SET wlcvig = ?（仅空值写入，值取站点 mivbcz），按 code 精确匹配
        String locSql = (String) ((Invocation) updateInvocations.get(2)).getRawArguments()[0];
        Object[] locArgs = (Object[]) ((Invocation) updateInvocations.get(2)).getRawArguments()[1];
        assertTrue(locSql.contains("SET wlcvig = ?"));
        assertTrue(locSql.contains("wlcvig IS NULL OR wlcvig = ''"));
        assertEquals(ORG_NAME, locArgs[0]);
        assertEquals(DEVICECODE, locArgs[2]);

        // 告警 UPDATE：参数序 (deviceId, now, siteId, siteId)，条件 site=站点ID AND device=站点ID（幂等）
        String alertSql = (String) ((Invocation) updateInvocations.get(3)).getRawArguments()[0];
        Object[] alertArgs = (Object[]) ((Invocation) updateInvocations.get(3)).getRawArguments()[1];
        assertTrue(alertSql.contains("t_auto_hltgq_water_alert"));
        assertEquals(deviceId, alertArgs[0]);
        assertEquals(SITE_ID, alertArgs[2]);
        assertEquals(SITE_ID, alertArgs[3]);

        // 工单 UPDATE：同告警条件
        String orderSql = (String) ((Invocation) updateInvocations.get(4)).getRawArguments()[0];
        Object[] orderArgs = (Object[]) ((Invocation) updateInvocations.get(4)).getRawArguments()[1];
        assertTrue(orderSql.contains("t_auto_hltgq_water_work_order"));
        assertEquals(deviceId, orderArgs[0]);
        assertEquals(SITE_ID, orderArgs[2]);
        assertEquals(SITE_ID, orderArgs[3]);
    }

    /** 工单表无 device 列：迁移仅补建设备与告警，跳过工单 UPDATE（动态列适配） */
    @Test
    void migrateSkipsWorkOrderWhenNoDeviceColumn() {
        when(jdbcTemplate.queryForList(argThat(sqlContains("information_schema", "t_auto_hltgq_water_device")),
                eq(String.class)))
                .thenReturn(new ArrayList<>());
        when(jdbcTemplate.queryForList(argThat(sqlContains("information_schema", "t_auto_hltgq_water_work_order")),
                eq(String.class)))
                .thenReturn(new ArrayList<>());
        Map<String, Object> station = new HashMap<>();
        station.put("id", SITE_ID);
        station.put("devicecode", DEVICECODE);
        station.put("zzkaec", SITE_NAME);
        station.put("mivbcz", ORG_NAME);
        station.put("zebpsu", "#1#");
        when(jdbcTemplate.queryForList(argThat(sqlContains("SELECT id, devicecode, zzkaec, mivbcz, zebpsu",
                "t_auto_hltgq_5nw74_vnqqef"))))
                .thenReturn(Collections.singletonList(station));
        when(jdbcTemplate.queryForList(argThat(sqlContains("SELECT id FROM", "WHERE code")),
                eq(String.class), eq(DEVICECODE)))
                .thenReturn(new ArrayList<>());
        when(jdbcTemplate.queryForList(argThat(sqlContains("SELECT id FROM", "WHERE name")),
                eq(String.class), eq(DEVICE_NAME)))
                .thenReturn(new ArrayList<>());

        service.init();

        // 4 次 UPDATE：INSERT 设备 + 状态对齐 + 安装位置回填 + 告警（工单跳过）
        assertEquals(4, updateInvocations.size());
        String lastSql = (String) ((Invocation) updateInvocations.get(3)).getRawArguments()[0];
        assertTrue(lastSql.contains("t_auto_hltgq_water_alert"));
        assertFalse(lastSql.contains("work_order"));
    }

    /** code 命中已有设备：不再按 name 兜底查询/创建（防重名站点命中同名僵尸设备） */
    @Test
    void codeMatchSkipsNameFallbackAndCreation() {
        when(jdbcTemplate.queryForList(argThat(sqlContains("SELECT id FROM", "WHERE code")),
                eq(String.class), eq(DEVICECODE)))
                .thenReturn(Collections.singletonList("device-001"));

        String id = service.lookupOrCreateDevice(DEVICE_NAME, SITE_ID, "#5#", DEVICECODE, "#1#", LOCATION);

        assertEquals("device-001", id);
        verify(jdbcTemplate, never()).queryForList(
                argThat(sqlContains("SELECT id FROM", "WHERE name")), eq(String.class), any());
        verify(jdbcTemplate, never()).update(anyString(), any(Object.class));
    }

    /** 历史设备 code 缺失：按 name 兜底命中后回填 code（按设备ID精确更新，防重名波及多行） */
    @Test
    void nameFallbackBackfillsMissingCode() {
        when(jdbcTemplate.queryForList(argThat(sqlContains("SELECT id FROM", "WHERE code")),
                eq(String.class), eq(DEVICECODE)))
                .thenReturn(new ArrayList<>());
        when(jdbcTemplate.queryForList(argThat(sqlContains("SELECT id FROM", "WHERE name")),
                eq(String.class), eq(DEVICE_NAME)))
                .thenReturn(Collections.singletonList("device-001"));

        String id = service.lookupOrCreateDevice(DEVICE_NAME, SITE_ID, "#5#", DEVICECODE, "#1#", LOCATION);

        assertEquals("device-001", id);
        // 回填 code：按 id 精确 UPDATE，仅 code 为空时写入
        verify(jdbcTemplate).update(
                argThat(s -> s.contains("SET code = ?")
                        && s.contains("WHERE id = ?")
                        && s.contains("(code IS NULL OR code = '')")),
                any(), any(), any());
        // 不创建新设备
        verify(jdbcTemplate, never()).update(argThat(s -> s.contains("INSERT INTO")), any(), any(), any());
    }
}
