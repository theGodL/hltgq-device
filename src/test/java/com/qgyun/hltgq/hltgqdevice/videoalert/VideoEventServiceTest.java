package com.qgyun.hltgq.hltgqdevice.videoalert;

import com.alibaba.fastjson.JSONObject;
import com.qgyun.hltgq.hltgqdevice.config.DahuaConfig;
import com.qgyun.hltgq.hltgqdevice.service.DahuaAuthService;
import com.qgyun.hltgq.hltgqdevice.service.DeviceTableService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * IVSS 智能事件处理服务单测：alarm.msg 过滤、alarmStat=1 落告警（级别映射）/2 关警、
 * 非视频 nodeCode 丢弃（站点查询带 epjutj 限定）、重复 uuid 幂等、
 * alarmTypeName 缺失兜底、事件订阅开关关闭时轮询兜底直接跳过。
 */
class VideoEventServiceTest {

    private static final String NODE_CODE = "1000231$1$0$10";
    private static final String SITE_ID = "site-001";
    private static final String SITE_NAME = "海棠洼节制闸";
    private static final String DEVICE_ID = "device-001";
    private static final String DEVICE_NAME = SITE_NAME + "摄像机#";

    private JdbcTemplate jdbcTemplate;
    private DeviceTableService deviceTableService;
    private VideoAlertService alertService;
    private DahuaConfig dahuaConfig;
    private VideoEventService service;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        deviceTableService = mock(DeviceTableService.class);
        alertService = mock(VideoAlertService.class);
        DahuaAuthService authService = mock(DahuaAuthService.class);
        dahuaConfig = new DahuaConfig();
        service = new VideoEventService();
        ReflectionTestUtils.setField(service, "jdbcTemplate", jdbcTemplate);
        ReflectionTestUtils.setField(service, "deviceTableService", deviceTableService);
        ReflectionTestUtils.setField(service, "alertService", alertService);
        ReflectionTestUtils.setField(service, "authService", authService);
        ReflectionTestUtils.setField(service, "dahuaConfig", dahuaConfig);
        // 站点预置：nodeCode 匹配视频站点（mivbcz=站点位置）
        Map<String, Object> row = new HashMap<>();
        row.put("id", SITE_ID);
        row.put("zzkaec", SITE_NAME);
        row.put("mivbcz", "库上防汛办");
        when(jdbcTemplate.queryForList(anyString(), eq(NODE_CODE)))
                .thenReturn(Arrays.asList(row));
        // 设备预置：设备名派生 + 兜底查/建设备返回设备ID
        when(deviceTableService.deviceNameOf(anyString())).thenReturn(DEVICE_NAME);
        when(deviceTableService.lookupOrCreateDevice(anyString(), anyString(), anyString(), anyString(),
                nullable(String.class), anyString())).thenReturn(DEVICE_ID);
    }

    /** 事件JSON构造：通用事件格式（category/method/uuid/info） */
    private String eventJson(String uuid, String nodeCode, String eventName, Object alarmType,
                             Integer grade, String alarmStat, String category, String method) {
        JSONObject info = new JSONObject();
        info.put("nodeCode", nodeCode);
        if (eventName != null) {
            info.put("alarmTypeName", eventName);
        }
        if (alarmType != null) {
            info.put("alarmType", alarmType);
        }
        if (grade != null) {
            info.put("alarmGrade", grade);
        }
        info.put("alarmStat", alarmStat);
        JSONObject event = new JSONObject();
        event.put("category", category);
        event.put("method", method);
        event.put("uuid", uuid);
        event.put("info", info);
        return event.toJSONString();
    }

    /** alarmStat=1（发生）：落智能事件告警，content 前缀与图像故障隔离，级别按 alarmGrade 映射 */
    @Test
    void alarmOnReportsEventAlertWithLevelMapping() {
        assertTrue(service.handleEvent(
                eventJson("uuid-on-1", NODE_CODE, "区域入侵", null, 4, "1", "alarm", "alarm.msg")));

        String content = SITE_NAME + "-视频智能事件 区域入侵！";
        verify(alertService).reportEventAlert(SITE_ID, DEVICE_ID, content, "#4#");
        verify(deviceTableService).lookupOrCreateDevice(eq(DEVICE_NAME), eq(SITE_ID),
                eq(DeviceTableService.DEVICE_TYPE_VIDEO), eq(NODE_CODE), nullable(String.class),
                eq("库上防汛办"));
    }

    /** alarmStat=2（消失）：按 content 关闭告警并联动工单 */
    @Test
    void alarmOffClosesEventAlert() {
        assertTrue(service.handleEvent(
                eventJson("uuid-off-1", NODE_CODE, "区域入侵", null, 4, "2", "alarm", "alarm.msg")));

        String content = SITE_NAME + "-视频智能事件 区域入侵！";
        verify(alertService).closeEventAlert(SITE_ID, DEVICE_ID, content);
        verify(alertService, never()).reportEventAlert(any(), any(), any(), any());
    }

    /** 级别映射：alarmGrade 1~4 → #1#~#4#，缺失默认 #2# */
    @Test
    void alarmGradeMapsToAlertLevel() {
        service.handleEvent(eventJson("uuid-g1", NODE_CODE, "事件一", null, 1, "1", "alarm", "alarm.msg"));
        service.handleEvent(eventJson("uuid-g2", NODE_CODE, "事件二", null, 2, "1", "alarm", "alarm.msg"));
        service.handleEvent(eventJson("uuid-g3", NODE_CODE, "事件三", null, 3, "1", "alarm", "alarm.msg"));
        service.handleEvent(eventJson("uuid-g4", NODE_CODE, "事件四", null, 4, "1", "alarm", "alarm.msg"));
        service.handleEvent(eventJson("uuid-gn", NODE_CODE, "事件五", null, null, "1", "alarm", "alarm.msg"));

        ArgumentCaptor<String> levelCaptor = ArgumentCaptor.forClass(String.class);
        verify(alertService, times(5)).reportEventAlert(eq(SITE_ID), eq(DEVICE_ID),
                anyString(), levelCaptor.capture());
        List<String> levels = levelCaptor.getAllValues();
        assertEquals(Arrays.asList("#1#", "#2#", "#3#", "#4#", "#2#"), levels);
    }

    /** 非视频 nodeCode（站点表无匹配）：丢弃，不落告警 */
    @Test
    void nonVideoNodeCodeDropped() {
        when(jdbcTemplate.queryForList(anyString(), eq("gate-code")))
                .thenReturn(new ArrayList<>());

        assertFalse(service.handleEvent(
                eventJson("uuid-gate", "gate-code", "水位越限", null, 1, "1", "alarm", "alarm.msg")));

        verify(alertService, never()).reportEventAlert(any(), any(), any(), any());
        verify(alertService, never()).closeEventAlert(any(), any(), any());
    }

    /** 站点解析带 epjutj 限定（防同 devicecode 的闸门/水质站点误命中，2026-09-05 线上教训） */
    @Test
    void stationQueryLimitsToVideoStations() {
        service.handleEvent(eventJson("uuid-sql", NODE_CODE, "区域入侵", null, 1, "1", "alarm", "alarm.msg"));

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForList(sqlCaptor.capture(), eq(NODE_CODE));
        assertTrue(sqlCaptor.getValue().contains("epjutj LIKE '%#5#%'"),
                "站点查询 SQL 应带 epjutj 视频类型限定: " + sqlCaptor.getValue());
    }

    /** 重复 uuid（平台重推）：跳过，仅处理一次 */
    @Test
    void duplicateUuidHandledOnce() {
        String json = eventJson("uuid-dup", NODE_CODE, "区域入侵", null, 1, "1", "alarm", "alarm.msg");
        assertTrue(service.handleEvent(json));
        assertFalse(service.handleEvent(json));

        verify(alertService, times(1)).reportEventAlert(eq(SITE_ID), eq(DEVICE_ID), anyString(), any());
    }

    /** 非报警消息（business/state 类）：忽略，不查站点、不落告警 */
    @Test
    void nonAlarmMessageIgnored() {
        assertFalse(service.handleEvent(
                eventJson("uuid-biz", NODE_CODE, "区域入侵", null, 1, "1", "business", "alarm.msg")));
        assertFalse(service.handleEvent(
                eventJson("uuid-sta", NODE_CODE, "区域入侵", null, 1, "1", "alarm", "device.online")));

        verify(jdbcTemplate, never()).queryForList(anyString(), any(Object.class));
        verify(alertService, never()).reportEventAlert(any(), any(), any(), any());
    }

    /** alarmTypeName 缺失：兜底 alarmType 码值生成事件名（两者皆无则丢弃） */
    @Test
    void missingAlarmTypeNameFallsBackToAlarmType() {
        assertTrue(service.handleEvent(
                eventJson("uuid-fb", NODE_CODE, null, 101, 1, "1", "alarm", "alarm.msg")));
        verify(alertService).reportEventAlert(SITE_ID, DEVICE_ID,
                SITE_NAME + "-视频智能事件 智能事件101！", "#1#");

        assertFalse(service.handleEvent(
                eventJson("uuid-none", NODE_CODE, null, null, 1, "1", "alarm", "alarm.msg")));
        verify(alertService, times(1)).reportEventAlert(eq(SITE_ID), eq(DEVICE_ID), anyString(), any());
    }

    /** 未知 alarmStat：跳过（不误报不误关） */
    @Test
    void unknownAlarmStatSkipped() {
        assertFalse(service.handleEvent(
                eventJson("uuid-unk", NODE_CODE, "区域入侵", null, 1, "0", "alarm", "alarm.msg")));

        verify(alertService, never()).reportEventAlert(any(), any(), any(), any());
        verify(alertService, never()).closeEventAlert(any(), any(), any());
    }

    /** 非法 JSON / 空消息：安全返回 false */
    @Test
    void invalidJsonReturnsFalse() {
        assertFalse(service.handleEvent("not-a-json"));
        assertFalse(service.handleEvent(null));
        assertFalse(service.handleEvent(""));
        assertFalse(service.handleEvent("{"));
        verify(alertService, never()).reportEventAlert(any(), any(), any(), any());
    }

    /** 事件订阅开关关闭：轮询兜底直接跳过（不触达 ICC HTTP 接口） */
    @Test
    void pullRecentEventsDisabledReturnsZero() {
        dahuaConfig.setEventEnabled(false);
        assertEquals(0, service.pullRecentEvents());
    }
}
