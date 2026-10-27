package com.qgyun.hltgq.hltgqdevice.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatcher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 站点视频设备查询服务单测：站点定位（siteId / channelId / siteName）、
 * 设备去重、通道信息合并（cameraType/在线）、巡检结果合并与查询失败降级。
 */
class SiteVideoQueryServiceTest {

    private static final String SITE_ID = "site-001";
    private static final String SITE_NAME = "虎形渡槽";
    private static final String CODE_A = "1000328$1$0$0";
    private static final String CODE_B = "1000328$1$0$1";

    private JdbcTemplate jdbcTemplate;
    private StationStatusSyncService stationSyncService;
    private SiteVideoQueryService service;

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
        stationSyncService = mock(StationStatusSyncService.class);
        service = new SiteVideoQueryService();
        ReflectionTestUtils.setField(service, "jdbcTemplate", jdbcTemplate);
        ReflectionTestUtils.setField(service, "stationSyncService", stationSyncService);
    }

    /** 设备表行 */
    private static Map<String, Object> deviceRow(String code, String name, String status, String location) {
        Map<String, Object> row = new HashMap<>();
        row.put("code", code);
        row.put("name", name);
        row.put("status", status);
        row.put("wlcvig", location);
        return row;
    }

    /** 巡检表行 */
    private static Map<String, Object> patrolRow(String code, String result, String faults) {
        Map<String, Object> row = new HashMap<>();
        row.put("channel_code", code);
        row.put("result", result);
        row.put("fault_types", faults);
        return row;
    }

    private void stubSiteName() {
        when(jdbcTemplate.queryForList(argThat(sqlContains("SELECT zzkaec")),
                eq(String.class), eq(SITE_ID)))
                .thenReturn(Collections.singletonList(SITE_NAME));
    }

    private void stubDevices(List<Map<String, Object>> rows) {
        when(jdbcTemplate.queryForList(
                argThat(sqlContains("SELECT code, name, status, wlcvig", "site = ?")),
                org.mockito.ArgumentMatchers.<Object>any()))
                .thenReturn(rows);
    }

    private void stubPatrol(List<Map<String, Object>> rows) {
        when(jdbcTemplate.queryForList(argThat(sqlContains("ROW_NUMBER() OVER")),
                org.mockito.ArgumentMatchers.<Object>any()))
                .thenReturn(rows);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> channelsOf(Map<String, Object> data) {
        return (List<Map<String, Object>>) data.get("channels");
    }

    /** siteId 明确传入：设备去重 + 合并设备树 cameraType/在线 + 合并最新巡检结果 */
    @Test
    void queryBySiteIdMergesDeviceChannelAndPatrol() {
        stubSiteName();
        stubDevices(Arrays.asList(
                deviceRow(CODE_A, "虎形渡槽-视频摄像机#", "#1#", "集岭管理所-大门外"),
                deviceRow(CODE_B, "虎形渡槽-视频摄像机#2", "#2#", "集岭管理所-闸室"),
                deviceRow(CODE_A, "虎形渡槽-视频摄像机#", "#1#", "集岭管理所-大门外")));
        stubPatrol(Collections.singletonList(patrolRow(CODE_A, "fail", "BLACKWHITE")));
        when(stationSyncService.findChannel(CODE_A))
                .thenReturn(new StationStatusSyncService.VideoChannel(
                        CODE_A, "大门外", "集岭管理所", "虎形渡槽", true, 2));
        when(stationSyncService.findChannel(CODE_B)).thenReturn(null);

        Map<String, Object> data = service.query(SITE_ID, null, null, null);

        assertEquals(SITE_ID, data.get("siteId"));
        assertEquals(SITE_NAME, data.get("siteName"));
        assertEquals(2, data.get("total"));
        List<Map<String, Object>> channels = channelsOf(data);
        assertEquals(2, channels.size());
        // code 重复行去重，保留首条
        Map<String, Object> first = channels.get(0);
        assertEquals(CODE_A, first.get("channelId"));
        assertEquals("虎形渡槽-视频摄像机#", first.get("name"));
        assertEquals("集岭管理所-大门外", first.get("location"));
        assertEquals(2, first.get("cameraType"));
        assertEquals(true, first.get("online"));
        assertEquals("fail", first.get("patrolResult"));
        assertEquals("BLACKWHITE", first.get("faultTypes"));
        // 设备树查不到时：cameraType 兜底枪机、在线状态取设备表 status（#2#=离线）、无巡检结果
        Map<String, Object> second = channels.get(1);
        assertEquals(CODE_B, second.get("channelId"));
        assertEquals(1, second.get("cameraType"));
        assertEquals(false, second.get("online"));
        assertNull(second.get("patrolResult"));
        // 设备树查不到时不得触发站点反查（避免多余查库）
        verify(stationSyncService, never()).findSiteId(CODE_B);
    }

    /** siteId 缺失：按 channelId 反查归属站点 */
    @Test
    void queryResolvesSiteByChannelIdWhenSiteIdMissing() {
        when(stationSyncService.findSiteId(CODE_A)).thenReturn(SITE_ID);
        stubSiteName();
        stubDevices(new ArrayList<>());

        Map<String, Object> data = service.query(null, null, CODE_A, null);

        assertEquals(SITE_ID, data.get("siteId"));
        assertEquals(0, data.get("total"));
        verify(stationSyncService).findSiteId(CODE_A);
    }

    /** siteId 与 channelId 均缺失：按站点名称精确匹配站点ID */
    @Test
    void queryResolvesSiteBySiteNameWhenOthersMissing() {
        when(jdbcTemplate.queryForList(argThat(sqlContains("SELECT id", "WHERE zzkaec = ?")),
                eq(String.class), eq(SITE_NAME)))
                .thenReturn(Collections.singletonList(SITE_ID));
        stubSiteName();
        stubDevices(new ArrayList<>());

        Map<String, Object> data = service.query(null, SITE_NAME, null, null);

        assertEquals(SITE_ID, data.get("siteId"));
        assertEquals(SITE_NAME, data.get("siteName"));
        assertEquals(0, data.get("total"));
    }

    /** 巡检表不可达：设备列表照常返回，仅巡检字段为空（不影响视频与云台） */
    @Test
    void patrolQueryFailureKeepsDeviceList() {
        stubSiteName();
        stubDevices(Collections.singletonList(
                deviceRow(CODE_A, "虎形渡槽-视频摄像机#", "#1#", "集岭管理所-大门外")));
        when(jdbcTemplate.queryForList(argThat(sqlContains("ROW_NUMBER() OVER")),
                org.mockito.ArgumentMatchers.<Object>any()))
                .thenThrow(new RuntimeException("patrol table missing"));
        when(stationSyncService.findChannel(CODE_A)).thenReturn(null);

        Map<String, Object> data = service.query(SITE_ID, null, null, null);

        assertEquals(1, data.get("total"));
        assertNull(channelsOf(data).get(0).get("patrolResult"));
    }

    /** 设备表查询异常：返回空清单而不抛错（页面展示空态，视频区不受影响） */
    @Test
    void deviceQueryFailureReturnsEmptyList() {
        stubSiteName();
        when(jdbcTemplate.queryForList(
                argThat(sqlContains("SELECT code, name, status, wlcvig")),
                org.mockito.ArgumentMatchers.<Object>any()))
                .thenThrow(new RuntimeException("device table missing"));

        Map<String, Object> data = service.query(SITE_ID, null, null, null);

        assertNotNull(data.get("channels"));
        assertEquals(0, data.get("total"));
        assertEquals(Collections.emptyList(), data.get("channels"));
    }
}
