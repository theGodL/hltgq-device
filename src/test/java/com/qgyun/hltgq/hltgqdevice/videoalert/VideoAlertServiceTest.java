package com.qgyun.hltgq.hltgqdevice.videoalert;

import com.qgyun.hltgq.hltgqdevice.service.DeviceTableService;
import com.qgyun.hltgq.hltgqdevice.service.StationStatusSyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.invocation.Invocation;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
    /** 设备表 name = ICC 通道原名（2026-10-20 起不再拼站点前缀/“摄像机#”后缀） */
    private static final String CHANNEL_NAME = "大门外";
    private static final String CONTENT = SITE_NAME + "-视频 信号丢失！";
    private static final String TITLE = SITE_NAME + "-视频 信号丢失";
    private static final String ORG_NAME = "库上防汛办";

    private JdbcTemplate jdbcTemplate;
    private WorkOrderService workOrderService;
    private DeviceTableService deviceTableService;
    private StationStatusSyncService stationStatusSyncService;
    private VideoAlertService service;
    /** doAnswer 捕获的全部 update 调用（原始参数，绕开 Mockito varargs 展开匹配） */
    private final List<InvocationOnMock> updateInvocations = new ArrayList<>();

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        workOrderService = mock(WorkOrderService.class);
        deviceTableService = mock(DeviceTableService.class);
        stationStatusSyncService = mock(StationStatusSyncService.class);
        service = new VideoAlertService();
        ReflectionTestUtils.setField(service, "jdbcTemplate", jdbcTemplate);
        ReflectionTestUtils.setField(service, "workOrderService", workOrderService);
        ReflectionTestUtils.setField(service, "deviceTableService", deviceTableService);
        ReflectionTestUtils.setField(service, "stationStatusSyncService", stationStatusSyncService);
        ReflectionTestUtils.setField(service, "corpCode", "hltgq");
        ReflectionTestUtils.setField(service, "dedupWindowHours", 24L);
        // 站点预置（站点解析走设备表 JOIN，返回 id/zzkaec 两列），避免测试重复模拟查询
        Map<String, Object> row = new HashMap<>();
        row.put("id", SITE_ID);
        row.put("zzkaec", SITE_NAME);
        when(jdbcTemplate.queryForList(anyString(), eq(DEVICECODE)))
                .thenReturn(Arrays.asList(row));
        // 设备预置：设备已存在（resolveSite 即由设备表 JOIN 得到站点）→ 直接取 ID，不触发 ICC 遍历；
        // 仅在设备缺失的用例里覆盖为 null，走"取 ICC 通道名补建"分支
        when(deviceTableService.findVideoDeviceByCode(DEVICECODE))
                .thenReturn(new DeviceTableService.VideoDeviceRef(DEVICE_ID, SITE_ID));
        when(deviceTableService.lookupOrCreateDevice(anyString(), anyString(), anyString(), anyString(),
                nullable(String.class), nullable(String.class))).thenReturn(DEVICE_ID);
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
        // 设备解析：设备已存在（resolveSite JOIN 命中）→ 直接取设备ID，不再走查/建
        verify(deviceTableService).findVideoDeviceByCode(DEVICECODE);
        verify(deviceTableService, never()).lookupOrCreateDevice(anyString(), anyString(), anyString(),
                anyString(), nullable(String.class), nullable(String.class));
        // 工单联动：alert=告警ID、site=站点ID、device=设备ID、title=content去"！"、content 与告警一致、qjulvf=#hxqm#设备故障抢修
        verify(workOrderService).createIfAbsent(anyString(), eq(SITE_ID), eq(DEVICE_ID), eq(TITLE), eq(CONTENT),
                eq("#hxqm#"));
    }

    /** 画质类告警（视频仍能看）：级别 #1# 一般、不生成工单（2026-10-29 业务定级） */
    @Test
    void qualityFaultLowLevelNoWorkOrder() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(), any(), any(), any()))
                .thenReturn(0);

        assertTrue(service.reportFault(DEVICECODE, VideoFaultType.BLUR));

        assertEquals(1, updateInvocations.size());
        Object[] args = (Object[]) ((Invocation) updateInvocations.get(0)).getRawArguments()[1];
        List<Object> vals = new ArrayList<>(Arrays.asList(args));
        // content="{站点}-视频 图像模糊！"，level=#1#（一般）
        assertTrue(vals.contains(SITE_NAME + "-视频 图像模糊！"));
        assertTrue(vals.contains("#1#"));
        // 画质类画面仍可正常观看，不生成工单（无人处理，挂单只积累噪音）
        verify(workOrderService, never()).createIfAbsent(any(), any(), any(), any(), any(), any());
    }

    /** 智能事件告警：type=#3# 智能分析、工单 qjulvf=#pfqj# 安全隐患整改（与故障告警 #2#/#hxqm# 区分） */
    @Test
    void reportEventAlertInsertsWithIntelligentType() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(), any(), any(), any()))
                .thenReturn(0);
        String eventContent = SITE_NAME + "-视频智能事件 区域入侵！";

        assertTrue(service.reportEventAlert(SITE_ID, DEVICE_ID, eventContent, "#2#"));

        assertEquals(1, updateInvocations.size());
        String sql = (String) ((Invocation) updateInvocations.get(0)).getRawArguments()[0];
        Object[] args = (Object[]) ((Invocation) updateInvocations.get(0)).getRawArguments()[1];
        List<Object> vals = new ArrayList<>(Arrays.asList(args));
        // type=#3# 智能分析与 level=#2#（调用参数）同时存在：两个字段独立映射
        assertTrue(sql.contains("\"type\""));
        assertTrue(vals.contains("#3#"));
        assertTrue(vals.contains("#2#"));
        assertTrue(vals.contains(eventContent));
        // 工单联动：qjulvf=#pfqj# 安全隐患整改（title=content 去"！"）
        verify(workOrderService).createIfAbsent(anyString(), eq(SITE_ID), eq(DEVICE_ID),
                eq(SITE_NAME + "-视频智能事件 区域入侵"), eq(eventContent), eq("#pfqj#"));
    }

    /** 去重：已有未关闭同内容告警 → 不重复插入、不重复建工单 */
    @Test
    void reportFaultSkipsWhenUnclosedExists() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(), any(), any(), any()))
                .thenReturn(1);

        assertFalse(service.reportFault(DEVICECODE, VideoFaultType.SIGNAL_LOSS));
        assertTrue(updateInvocations.isEmpty());
        verify(workOrderService, never()).createIfAbsent(any(), any(), any(), any(), any(), any());
    }

    /** 24h 去重：窗口内已有同站同内容告警（即使已关闭）→ 不新增、不建单 */
    @Test
    void reportFaultSkipsWhenRecentSameContentExists() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(), any(), any(), any()))
                .thenReturn(0);
        when(jdbcTemplate.queryForList(anyString(), any(Object.class), any(Object.class), any(Object.class)))
                .thenReturn(Collections.singletonList(Collections.<String, Object>singletonMap("?column?", 1)));

        assertFalse(service.reportFault(DEVICECODE, VideoFaultType.SIGNAL_LOSS));
        assertTrue(updateInvocations.isEmpty());
        verify(workOrderService, never()).createIfAbsent(any(), any(), any(), any(), any(), any());
    }

    /** 24h 去重同样覆盖智能事件入口，避免事件恢复后同日重复建单 */
    @Test
    void reportEventAlertSkipsWhenRecentSameContentExists() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(), any(), any(), any()))
                .thenReturn(0);
        when(jdbcTemplate.queryForList(anyString(), any(Object.class), any(Object.class), any(Object.class)))
                .thenReturn(Collections.singletonList(Collections.<String, Object>singletonMap("?column?", 1)));
        String eventContent = SITE_NAME + "-视频智能事件 区域入侵！";

        assertFalse(service.reportEventAlert(SITE_ID, DEVICE_ID, eventContent, "#2#"));
        assertTrue(updateInvocations.isEmpty());
        verify(workOrderService, never()).createIfAbsent(any(), any(), any(), any(), any(), any());
    }

    /** 24h 查重失败 fail-open：放行插入，不阻断告警 */
    @Test
    void recentDedupFailureFailsOpen() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(), any(), any(), any()))
                .thenReturn(0);
        when(jdbcTemplate.queryForList(anyString(), any(Object.class), any(Object.class), any(Object.class)))
                .thenThrow(new RuntimeException("db down"));

        assertTrue(service.reportFault(DEVICECODE, VideoFaultType.SIGNAL_LOSS));
        assertEquals(1, updateInvocations.size());
    }

    /** 窗口配置为 0：关闭 24h 查重，不再查询告警表 */
    @Test
    void recentDedupDisabledWhenWindowIsZero() {
        ReflectionTestUtils.setField(service, "dedupWindowHours", 0L);
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(), any(), any(), any()))
                .thenReturn(0);

        assertTrue(service.reportFault(DEVICECODE, VideoFaultType.SIGNAL_LOSS));
        verify(jdbcTemplate, never()).queryForList(anyString(), any(Object.class), any(Object.class), any(Object.class));
    }

    /** 站点缺失：不插入告警、不建工单 */
    @Test
    void reportFaultSkipsWhenSiteMissing() {
        when(jdbcTemplate.queryForList(anyString(), eq("unknown-code")))
                .thenReturn(new ArrayList<>());
        assertFalse(service.reportFault("unknown-code", VideoFaultType.BLUR));
        assertTrue(updateInvocations.isEmpty());
        verify(workOrderService, never()).createIfAbsent(any(), any(), any(), any(), any(), any());
    }

    /** 恢复关警：按 device + "%-视频 信号丢失！" 匹配，不依赖站点名；按告警ID关工单 */
    @Test
    void recoverFaultClosesAlertAndWorkOrder() {
        String pattern = "%-视频 信号丢失！";
        when(jdbcTemplate.queryForList(contains("SELECT id FROM"), eq(DEVICE_ID), eq(pattern), eq("#4#")))
                .thenReturn(Collections.singletonList(
                        Collections.<String, Object>singletonMap("id", "alert-1")));

        assertEquals(1, service.recoverFault(DEVICECODE, VideoFaultType.SIGNAL_LOSS));

        assertEquals(1, updateInvocations.size());
        String sql = (String) ((Invocation) updateInvocations.get(0)).getRawArguments()[0];
        Object[] args = (Object[]) ((Invocation) updateInvocations.get(0)).getRawArguments()[1];
        List<Object> vals = Arrays.asList(args);
        assertEquals("#4#", vals.get(0));
        assertTrue(vals.contains("alert-1"));
        // 按告警ID关闭（不再按 site+content 精确匹配）
        assertTrue(sql.contains("id IN (?)"));
        assertTrue(sql.contains("status IS DISTINCT FROM ?"));
        // 查未关闭告警：按设备+故障后缀匹配，不依赖站点名前缀
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForList(sqlCaptor.capture(), eq(DEVICE_ID), eq(pattern), eq("#4#"));
        String selectSql = sqlCaptor.getValue();
        assertTrue(selectSql.contains("device = ?"));
        assertTrue(selectSql.contains("content LIKE ?"));
        assertFalse(selectSql.contains("site = ?"));
        // 工单联动：按被关闭告警ID精确关闭
        verify(workOrderService).closeByAlertIds(Collections.singletonList("alert-1"));
    }

    /**
     * 设备换站/站点改名后恢复：告警 content 仍带旧站点名，按 device + 故障后缀仍能关闭，
     * 不重新拼当前站点名（旧实现会因前缀不匹配漏关）。
     */
    @Test
    void recoverFaultClosesAcrossRenamedSite() {
        String pattern = "%-视频 噪声干扰！";
        when(jdbcTemplate.queryForList(contains("SELECT id FROM"), eq(DEVICE_ID), eq(pattern), eq("#4#")))
                .thenReturn(Collections.singletonList(
                        Collections.<String, Object>singletonMap("id", "alert-old-site")));

        assertEquals(1, service.recoverFault(DEVICECODE, VideoFaultType.NOISE));

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForList(sqlCaptor.capture(), eq(DEVICE_ID), eq(pattern), eq("#4#"));
        assertFalse(sqlCaptor.getValue().contains("site = ?"), "恢复不能依赖站点名前缀");
        verify(workOrderService).closeByAlertIds(Collections.singletonList("alert-old-site"));
    }

    /** 重启后对账：最近 recover-threshold 轮巡检全 ok 的未关闭告警 → 关闭告警并按ID关工单 */
    @Test
    void reconcileRecoveredClosesAlertsAndWorkOrders() {
        when(jdbcTemplate.queryForList(anyString(), eq("#1#"), any(Timestamp.class)))
                .thenReturn(Arrays.asList(
                        Collections.<String, Object>singletonMap("alert_id", "alert-1"),
                        Collections.<String, Object>singletonMap("alert_id", "alert-2")));

        service.reconcileRecovered(2);

        assertEquals(1, updateInvocations.size());
        String sql = (String) ((Invocation) updateInvocations.get(0)).getRawArguments()[0];
        assertTrue(sql.contains("id IN (?, ?)"));
        assertTrue(sql.contains("status IS DISTINCT FROM ?"));
        // 对账取最近 2 行巡检结果：不能只用「本轮起点之后」过滤，否则永远只有本轮 1 行
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForList(sqlCaptor.capture(), eq("#1#"), any(Timestamp.class));
        String selectSql = sqlCaptor.getValue();
        assertTrue(selectSql.contains("video_patrol"));
        assertTrue(selectSql.contains("LIMIT 2"), "应取最近 recover-threshold 轮");
        assertTrue(selectSql.contains("max(x.round_time)"), "最新一轮须不早于本轮起点");
        assertFalse(selectSql.contains("p.round_time >= ?"), "不能用本轮起点过滤掉上一轮");
        verify(workOrderService).closeByAlertIds(Arrays.asList("alert-1", "alert-2"));
    }

    /** 对账无满足条件的告警：不更新、不关工单 */
    @Test
    void reconcileRecoveredSkipsWhenNoAlert() {
        when(jdbcTemplate.queryForList(anyString(), eq("#1#"), any(Timestamp.class)))
                .thenReturn(Collections.emptyList());

        assertEquals(0, service.reconcileRecovered(2));
        assertTrue(updateInvocations.isEmpty());
        verify(workOrderService, never()).closeByAlertIds(any());
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
        when(deviceTableService.findVideoDeviceByCode(DEVICECODE)).thenReturn(null);
        when(deviceTableService.lookupOrCreateDevice(anyString(), anyString(), anyString(), anyString(),
                nullable(String.class), nullable(String.class))).thenReturn(null);
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(), any(), any(), any()))
                .thenReturn(0);

        assertFalse(service.reportFault(DEVICECODE, VideoFaultType.SIGNAL_LOSS));
        assertTrue(updateInvocations.isEmpty());
        verify(workOrderService, never()).createIfAbsent(any(), any(), any(), any(), any(), any());
    }

    /** 恢复时设备缺失：返回 0，不关告警、不关工单 */
    @Test
    void recoverFaultSkipsWhenDeviceMissing() {
        when(deviceTableService.findVideoDeviceByCode(DEVICECODE)).thenReturn(null);
        when(deviceTableService.lookupOrCreateDevice(anyString(), anyString(), anyString(), anyString(),
                nullable(String.class), nullable(String.class))).thenReturn(null);

        assertEquals(0, service.recoverFault(DEVICECODE, VideoFaultType.SIGNAL_LOSS));
        assertTrue(updateInvocations.isEmpty());
        verify(workOrderService, never()).closeByAlertIds(any());
    }

    /**
     * R7 回归：通道未入库时先按 ICC 设备树即时补建（ensureDeviceRegistered），
     * 补建成功后再解析站点并落告警，不再"因设备表缺台账直接丢弃"。
     */
    @Test
    void reportFaultRegistersMissingChannelThenReports() {
        Map<String, Object> row = new HashMap<>();
        row.put("id", SITE_ID);
        row.put("zzkaec", SITE_NAME);
        // 第一次解析（补建前）无结果，补建后第二次解析命中
        when(jdbcTemplate.queryForList(anyString(), eq(DEVICECODE)))
                .thenReturn(new ArrayList<>())
                .thenReturn(Arrays.asList(row));
        when(stationStatusSyncService.ensureDeviceRegistered(DEVICECODE)).thenReturn(true);
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(), any(), any(), any()))
                .thenReturn(0);

        assertTrue(service.reportFault(DEVICECODE, VideoFaultType.SIGNAL_LOSS));

        verify(stationStatusSyncService).ensureDeviceRegistered(DEVICECODE);
        assertEquals(1, updateInvocations.size());
    }

    /** 补建失败：跳过告警并记录，不插入任何数据 */
    @Test
    void reportFaultSkipsWhenRegistrationFails() {
        when(jdbcTemplate.queryForList(anyString(), eq(DEVICECODE)))
                .thenReturn(new ArrayList<>());
        when(stationStatusSyncService.ensureDeviceRegistered(DEVICECODE)).thenReturn(false);

        assertFalse(service.reportFault(DEVICECODE, VideoFaultType.SIGNAL_LOSS));

        verify(stationStatusSyncService).ensureDeviceRegistered(DEVICECODE);
        assertTrue(updateInvocations.isEmpty());
    }

    /**
     * 设备名口径：需要补建设备时，name 取 ICC 通道原名（不是站点名 + “摄像机#”）。
     */
    @Test
    void createDeviceUsesIccChannelName() {
        when(deviceTableService.findVideoDeviceByCode(DEVICECODE)).thenReturn(null);
        StationStatusSyncService.VideoChannel channel = mock(StationStatusSyncService.VideoChannel.class);
        when(channel.getName()).thenReturn(CHANNEL_NAME);
        when(stationStatusSyncService.findChannel(DEVICECODE)).thenReturn(channel);
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(), any(), any(), any()))
                .thenReturn(0);

        assertTrue(service.reportFault(DEVICECODE, VideoFaultType.SIGNAL_LOSS));

        verify(deviceTableService).lookupOrCreateDevice(eq(CHANNEL_NAME), eq(SITE_ID),
                eq(DeviceTableService.DEVICE_TYPE_VIDEO), eq(DEVICECODE), isNull(), isNull());
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

    /** 站点解析走设备表 JOIN（视频资产权威源：d.code → d.site → 站点行；type 含 #5# 限定防串类型） */
    @Test
    void stationQueryJoinsDeviceTableWithVideoType() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(), any(), any(), any()))
                .thenReturn(1);

        service.reportFault(DEVICECODE, VideoFaultType.SIGNAL_LOSS);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForList(sqlCaptor.capture(), eq(DEVICECODE));
        String sql = sqlCaptor.getValue();
        assertTrue(sql.contains("JOIN"), "站点解析应 JOIN 站点表：" + sql);
        assertTrue(sql.contains("t_auto_hltgq_water_device"), "站点解析应走设备表：" + sql);
        assertTrue(sql.contains("type LIKE '%#5#%'"), "必须限定视频设备类型：" + sql);
    }

    /** 未投运通道判定：巡检留痕无 ok 记录的通道才返回（有 ok 记录 = 曾取流成功，不豁免） */
    @Test
    void findNeverOkChannelsReturnsOnlyNeverSucceeded() {
        // ch-a 有 ok 记录（曾取流成功），ch-b 从未成功 → 只返回 ch-b
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), any(), any()))
                .thenReturn(Collections.singletonList("ch-a"));

        Set<String> neverOk = service.findNeverOkChannels(Arrays.asList("ch-a", "ch-b"));

        assertEquals(Collections.singleton("ch-b"), neverOk);
    }

    /** 查询失败 fail-open：返回空集按有记录处理（宁可多报不漏报，不静默丢警） */
    @Test
    void findNeverOkChannelsFailOpenOnQueryError() {
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), any(), any()))
                .thenThrow(new RuntimeException("db down"));

        assertTrue(service.findNeverOkChannels(Arrays.asList("ch-a", "ch-b")).isEmpty());
    }
}
