package com.qgyun.hltgq.hltgqdevice.videoalert;

import com.qgyun.hltgq.hltgqdevice.service.DeviceTableService;
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
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 视频告警入库服务单测：告警编号生成规则、站点解析（缓存TTL过期刷新）、设备解析（设备表视频设备ID）、
 * 新增去重、恢复关警、动态列适配、级别覆盖配置、工单联动（新增建单/恢复关单）。
 */
class VideoAlertServiceTest {

    private static final String DEVICECODE = "1000231$1$0$10";
    private static final String SITE_ID = "site-001";
    private static final String SITE_NAME = "渠首电站上游";
    private static final String DEVICE_ID = "device-001";
    private static final String DEVICE_NAME = SITE_NAME + "摄像机#";
    private static final String CONTENT = SITE_NAME + "-视频 信号丢失！";
    private static final String TITLE = SITE_NAME + "-视频 信号丢失";
    private static final String ORG_NAME = "库上防汛办";
    private static final String LOCATION = ORG_NAME + "-" + SITE_NAME;

    private JdbcTemplate jdbcTemplate;
    private WorkOrderService workOrderService;
    private DeviceTableService deviceTableService;
    private VideoAlertService service;
    /** doAnswer 捕获的全部 update 调用（原始参数，绕开 Mockito varargs 展开匹配） */
    private final List<InvocationOnMock> updateInvocations = new ArrayList<>();

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        workOrderService = mock(WorkOrderService.class);
        deviceTableService = mock(DeviceTableService.class);
        service = new VideoAlertService();
        ReflectionTestUtils.setField(service, "jdbcTemplate", jdbcTemplate);
        ReflectionTestUtils.setField(service, "workOrderService", workOrderService);
        ReflectionTestUtils.setField(service, "deviceTableService", deviceTableService);
        ReflectionTestUtils.setField(service, "corpCode", "hltgq");
        // 站点预置，避免测试重复模拟查询（mivbcz=站点位置/组织名，供设备安装位置派生）
        Map<String, Object> row = new HashMap<>();
        row.put("id", SITE_ID);
        row.put("zzkaec", SITE_NAME);
        row.put("mivbcz", ORG_NAME);
        when(jdbcTemplate.queryForList(anyString(), eq(DEVICECODE)))
                .thenReturn(Arrays.asList(row));
        // 设备预置：设备名派生 + 兜底查/建设备返回设备ID（status 传 null、位置=组织-站点名）
        when(deviceTableService.deviceNameOf(anyString())).thenReturn(DEVICE_NAME);
        when(deviceTableService.lookupOrCreateDevice(anyString(), anyString(), anyString(), anyString(),
                nullable(String.class), anyString())).thenReturn(DEVICE_ID);
        updateInvocations.clear();
        doAnswer(inv -> {
            updateInvocations.add(inv);
            return 1;
        }).when(jdbcTemplate).update(anyString(), any(Object.class));
    }

    /** 告警编号：GJ+yyyyMMddHHmmss+3位序号，同秒递增、跨秒重置（与 hltgq-mq 一致） */
    @Test
    void alertCodeSequence() throws Exception {
        Method m = VideoAlertService.class.getDeclaredMethod("genAlertCode", Timestamp.class);
        m.setAccessible(true);
        Timestamp t1 = Timestamp.valueOf("2026-01-01 10:00:00");
        assertEquals("GJ20260101100000001", m.invoke(service, t1));
        assertEquals("GJ20260101100000002", m.invoke(service, t1));
        assertEquals("GJ20260101100000003", m.invoke(service, t1));
        Timestamp t2 = Timestamp.valueOf("2026-01-01 10:00:01");
        assertEquals("GJ20260101100001001", m.invoke(service, t2));
    }

    /** 新增告警：无未关闭同内容告警 → 插入，字段与 hltgq-mq 语义一致，并联动生成工单 */
    @Test
    void reportFaultInsertsAlertAndCreatesWorkOrder() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(), any(), any(), any()))
                .thenReturn(0);

        assertTrue(service.reportFault(DEVICECODE, VideoFaultType.SIGNAL_LOSS));

        assertEquals(1, updateInvocations.size());
        String sql = (String) ((Invocation) updateInvocations.get(0)).getRawArguments()[0];
        Object[] args = (Object[]) ((Invocation) updateInvocations.get(0)).getRawArguments()[1];

        // 动态列：level/type 为关键字需双引号
        assertTrue(sql.contains("\"level\""));
        assertTrue(sql.contains("\"type\""));
        assertTrue(sql.contains("t_auto_hltgq_water_alert"));
        // 字段值：site=站点ID、device=设备表视频设备ID、content="{站点}-视频 信号丢失！"、status=#1#、type=#2#、level=#3#
        List<Object> vals = new ArrayList<>(Arrays.asList(args));
        assertTrue(vals.contains(SITE_ID));
        assertTrue(vals.contains(DEVICE_ID));
        assertTrue(vals.contains(CONTENT));
        assertTrue(vals.contains("#1#"));
        assertTrue(vals.contains("#2#"));
        assertTrue(vals.contains("#3#"));
        assertTrue(vals.contains("hltgq"));
        // code 以 GJ 开头、总长 19 位（GJ + yyyyMMddHHmmss 14位 + 3位序号）
        boolean codeChecked = false;
        for (Object v : vals) {
            if (v instanceof String && ((String) v).startsWith("GJ")) {
                assertEquals(19, ((String) v).length());
                codeChecked = true;
            }
        }
        assertTrue(codeChecked, "告警编号 code 应存在于插入参数中");
        // 兜底查/建设备：type=#5# 视频、code=通道devicecode、status 留空、位置=组织-站点名
        verify(deviceTableService).lookupOrCreateDevice(eq(DEVICE_NAME), eq(SITE_ID),
                eq(DeviceTableService.DEVICE_TYPE_VIDEO), eq(DEVICECODE), nullable(String.class),
                eq(LOCATION));
        // 工单联动：alert=告警ID、site=站点ID、device=设备ID、title=content去"！"、content 与告警一致
        verify(workOrderService).createIfAbsent(anyString(), eq(SITE_ID), eq(DEVICE_ID), eq(TITLE), eq(CONTENT));
    }

    /** 去重：已有未关闭同内容告警 → 不重复插入、不重复建工单 */
    @Test
    void reportFaultSkipsWhenUnclosedExists() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(), any(), any(), any()))
                .thenReturn(1);

        assertFalse(service.reportFault(DEVICECODE, VideoFaultType.SIGNAL_LOSS));
        assertTrue(updateInvocations.isEmpty());
        verify(workOrderService, never()).createIfAbsent(any(), any(), any(), any(), any());
    }

    /** 站点缺失：不插入告警、不建工单 */
    @Test
    void reportFaultSkipsWhenSiteMissing() {
        when(jdbcTemplate.queryForList(anyString(), eq("unknown-code")))
                .thenReturn(new ArrayList<>());
        assertFalse(service.reportFault("unknown-code", VideoFaultType.BLUR));
        assertTrue(updateInvocations.isEmpty());
        verify(workOrderService, never()).createIfAbsent(any(), any(), any(), any(), any());
    }

    /** 恢复关警：按 site+device+content 精确匹配，status 置 #4#，并联动关闭工单 */
    @Test
    void recoverFaultClosesAlertAndWorkOrder() {
        service.recoverFault(DEVICECODE, VideoFaultType.SIGNAL_LOSS);

        assertEquals(1, updateInvocations.size());
        String sql = (String) ((Invocation) updateInvocations.get(0)).getRawArguments()[0];
        Object[] args = (Object[]) ((Invocation) updateInvocations.get(0)).getRawArguments()[1];
        List<Object> vals = Arrays.asList(args);
        assertEquals("#4#", vals.get(0));
        assertTrue(vals.contains(SITE_ID));
        assertTrue(vals.contains(DEVICE_ID));
        assertTrue(vals.contains(CONTENT));
        // 关闭条件排除已关闭行（人工已关闭不重复更新）
        assertTrue(sql.contains("status IS DISTINCT FROM"));
        // 工单联动：恢复 → 关闭对应工单（device=设备ID）
        verify(workOrderService).closeByContent(eq(SITE_ID), eq(DEVICE_ID), eq(CONTENT));
    }

    /** 动态列适配：列元数据不含 content 时，insert SQL 跳过该列 */
    @Test
    void dynamicColumnAdaption() {
        Set<String> columns = new HashSet<>(Arrays.asList(
                "id", "corp_code", "created_at", "created_by", "updated_at", "updated_by",
                "code", "site", "device", "level", "status", "time", "type"));
        // 注意：不含 content 列
        ReflectionTestUtils.setField(service, "alertColumns", columns);
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(), any(), any(), any()))
                .thenReturn(0);

        service.reportFault(DEVICECODE, VideoFaultType.SIGNAL_LOSS);

        assertEquals(1, updateInvocations.size());
        String sql = (String) ((Invocation) updateInvocations.get(0)).getRawArguments()[0];
        assertFalse(sql.toLowerCase().contains("content"));
    }

    /** 级别覆盖配置：video-alert.level-map 覆盖枚举默认级别 */
    @Test
    void levelMapOverride() {
        // init 加载列元数据（mock 空列表）并解析 level-map
        when(jdbcTemplate.queryForList(anyString(), eq(String.class)))
                .thenReturn(new ArrayList<>());
        ReflectionTestUtils.setField(service, "levelMapConfig", "SIGNAL_LOSS:#4#,BAD_NAME:#2#");
        service.init();
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(), any(), any(), any()))
                .thenReturn(0);

        service.reportFault(DEVICECODE, VideoFaultType.SIGNAL_LOSS);

        Object[] args = (Object[]) ((Invocation) updateInvocations.get(0)).getRawArguments()[1];
        // 信号丢失级别被覆盖为 #4# 特别严重
        assertTrue(Arrays.asList(args).contains("#4#"));
        assertFalse(Arrays.asList(args).contains("#3#"));
    }

    /** 设备缺失（设备表不可达/创建失败）：跳过告警，不插入、不建工单 */
    @Test
    void reportFaultSkipsWhenDeviceMissing() {
        when(deviceTableService.lookupOrCreateDevice(anyString(), anyString(), anyString(), anyString(),
                nullable(String.class), anyString())).thenReturn(null);
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(), any(), any(), any()))
                .thenReturn(0);

        assertFalse(service.reportFault(DEVICECODE, VideoFaultType.SIGNAL_LOSS));
        assertTrue(updateInvocations.isEmpty());
        verify(workOrderService, never()).createIfAbsent(any(), any(), any(), any(), any());
    }

    /** 恢复时设备缺失：返回 0，不关告警、不关工单 */
    @Test
    void recoverFaultSkipsWhenDeviceMissing() {
        when(deviceTableService.lookupOrCreateDevice(anyString(), anyString(), anyString(), anyString(),
                nullable(String.class), anyString())).thenReturn(null);

        assertEquals(0, service.recoverFault(DEVICECODE, VideoFaultType.SIGNAL_LOSS));
        assertTrue(updateInvocations.isEmpty());
        verify(workOrderService, never()).closeByContent(any(), any(), any());
    }

    /** 站点缓存 TTL 过期：站点名被人为修改后过期自动重查（防按旧名派生设备名建重复设备） */
    @Test
    void siteCacheExpiredRefreshesStationName() {
        ReflectionTestUtils.setField(service, "siteCacheTtlSeconds", 0L);
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(), any(), any(), any()))
                .thenReturn(1); // 已有未关闭告警，不插入，专注验证缓存刷新

        service.reportFault(DEVICECODE, VideoFaultType.SIGNAL_LOSS);
        service.reportFault(DEVICECODE, VideoFaultType.SIGNAL_LOSS);

        verify(jdbcTemplate, times(2)).queryForList(anyString(), eq(DEVICECODE));
    }

    /** 缓存过期后重查失败：兜底返回旧缓存值，告警不中断 */
    @Test
    void siteCacheRefreshFailureFallsBackToCachedValue() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(), any(), any(), any()))
                .thenReturn(1);

        // 首次解析成功并缓存
        assertFalse(service.reportFault(DEVICECODE, VideoFaultType.SIGNAL_LOSS));
        // TTL 过期 + 查询失败 → 兜底旧值继续工作
        ReflectionTestUtils.setField(service, "siteCacheTtlSeconds", 0L);
        when(jdbcTemplate.queryForList(anyString(), eq(DEVICECODE)))
                .thenThrow(new RuntimeException("db down"));

        assertFalse(service.reportFault(DEVICECODE, VideoFaultType.SIGNAL_LOSS));
        verify(jdbcTemplate, times(2)).queryForList(anyString(), eq(DEVICECODE));
    }
}
