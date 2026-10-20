package com.qgyun.hltgq.hltgqdevice.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 站点视频设备查询服务：按站点汇总其下视频设备（设备表 type 含 #5#），
 * 合并 ICC 设备树通道信息（cameraType / 在线状态）与最新一轮巡检结果，
 * 供站点视频监控面板（static/station-video.html）左侧设备列表使用。
 * <p>纯只读：不写任何表，不改变站点同步与巡检落库链路。
 * <p>站点定位优先级：siteId 明确传入 → channelId 反查设备表 site → siteName 精确匹配站点表。
 *
 * @author hltgq-device
 */
@Slf4j
@Service
public class SiteVideoQueryService {

    /** 人大金仓 schema（带双引号，因为含连字符），与站点同步/巡检服务一致 */
    private static final String SCHEMA = "\"qixiao-apaas\".";

    /** 设备表 / 站点表 / 巡检留痕表 */
    private static final String DEVICE_TABLE = SCHEMA + "t_auto_hltgq_water_device";
    private static final String STATION_TABLE = SCHEMA + "t_auto_hltgq_5nw74_vnqqef";
    private static final String PATROL_TABLE = SCHEMA + "t_auto_hltgq_water_video_patrol";

    /** 视频设备类型（与设备表字典一致） */
    private static final String DEVICE_TYPE_VIDEO = "#5#";

    /** 设备运行状态：在线（与 DeviceTableService 一致） */
    private static final String STATUS_ONLINE = "#1#";

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Resource
    private StationStatusSyncService stationSyncService;

    /**
     * 查询站点下的视频设备清单。
     *
     * @param siteId    站点ID（优先）
     * @param siteName  站点名称（siteId 缺失时按名称精确匹配）
     * @param channelId 通道ID（站点仍无法定位时按通道反查归属站点）
     * @return {siteId, siteName, total, channels:[{channelId, name, location, online, cameraType, patrolResult, faultTypes}]}
     */
    public Map<String, Object> query(String siteId, String siteName, String channelId) {
        String resolvedSiteId = resolveSiteId(siteId, siteName, channelId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("siteId", resolvedSiteId);
        data.put("siteName", resolveSiteName(resolvedSiteId, siteName));
        List<Map<String, Object>> channels = new ArrayList<>();
        data.put("total", 0);
        data.put("channels", channels);
        if (resolvedSiteId == null) {
            log.warn("[站点视频] 站点定位失败: siteId={}, siteName={}, channelId={}", siteId, siteName, channelId);
            return data;
        }

        // 1. 设备表取该站点下视频设备（同 code 重复行只保留首条，历史重复数据防重）
        Map<String, Map<String, Object>> deviceByCode = queryDevices(resolvedSiteId);
        if (deviceByCode.isEmpty()) {
            return data;
        }

        // 2. 最新一轮巡检结果（一次查询覆盖全部通道，避免逐条查询）
        Map<String, String[]> latestPatrol = queryLatestPatrol(deviceByCode.keySet());

        // 3. 合并设备树通道信息（cameraType 决定云台面板可操作性，在线状态优先取设备树）
        for (Map.Entry<String, Map<String, Object>> entry : deviceByCode.entrySet()) {
            String code = entry.getKey();
            Map<String, Object> row = entry.getValue();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("channelId", code);

            StationStatusSyncService.VideoChannel channel = findChannelQuietly(code);
            String deviceName = str(row.get("name"));
            item.put("name", deviceName != null ? deviceName : (channel == null ? code : channel.getName()));
            item.put("location", str(row.get("wlcvig")));
            item.put("cameraType", channel != null && channel.getCameraType() != null
                    ? channel.getCameraType() : 1);
            item.put("online", channel != null ? channel.isOnline() : STATUS_ONLINE.equals(str(row.get("status"))));
            String[] patrol = latestPatrol.get(code);
            item.put("patrolResult", patrol == null ? null : patrol[0]);
            item.put("faultTypes", patrol == null ? null : patrol[1]);
            channels.add(item);
        }
        data.put("total", channels.size());
        log.info("[站点视频] 站点设备查询完成: siteId={}, 设备{}台", resolvedSiteId, channels.size());
        return data;
    }

    // ======================== 站点定位 ========================

    /**
     * 站点定位：siteId → channelId 反查 → siteName 精确匹配，均失败返回 null。
     */
    private String resolveSiteId(String siteId, String siteName, String channelId) {
        String direct = str(siteId);
        if (direct != null) {
            return direct;
        }
        String byChannel = str(channelId);
        if (byChannel != null) {
            String site = stationSyncService.findSiteId(byChannel);
            if (str(site) != null) {
                return str(site);
            }
        }
        String name = str(siteName);
        if (name != null) {
            try {
                List<String> ids = jdbcTemplate.queryForList(
                        "SELECT id FROM " + STATION_TABLE + " WHERE zzkaec = ? LIMIT 1", String.class, name);
                if (ids != null && !ids.isEmpty() && str(ids.get(0)) != null) {
                    return str(ids.get(0));
                }
            } catch (Exception e) {
                log.warn("[站点视频] 站点名称反查ID失败: siteName={}, err={}", name, e.getMessage());
            }
        }
        return null;
    }

    /**
     * 站点名称：优先查站点表 zzkaec（站点ID为权威），查不到时回退入参名称。
     */
    private String resolveSiteName(String resolvedSiteId, String siteName) {
        if (resolvedSiteId != null) {
            try {
                List<String> names = jdbcTemplate.queryForList(
                        "SELECT zzkaec FROM " + STATION_TABLE + " WHERE id = ? LIMIT 1",
                        String.class, resolvedSiteId);
                if (names != null && !names.isEmpty() && str(names.get(0)) != null) {
                    return str(names.get(0));
                }
            } catch (Exception e) {
                log.warn("[站点视频] 站点名称查询失败: siteId={}, err={}", resolvedSiteId, e.getMessage());
            }
        }
        return siteName == null ? "" : siteName.trim();
    }

    // ======================== 设备与巡检 ========================

    /**
     * 查站点下视频设备（code 去重，保持设备名排序）。
     * 查询失败返回空表（页面展示空态，不抛错影响页面其它区块）。
     */
    private Map<String, Map<String, Object>> queryDevices(String siteId) {
        Map<String, Map<String, Object>> byCode = new LinkedHashMap<>();
        try {
            String sql = "SELECT code, name, status, wlcvig FROM " + DEVICE_TABLE
                    + " WHERE site = ? AND type LIKE '%" + DEVICE_TYPE_VIDEO + "%'"
                    + " AND COALESCE(code, '') <> '' ORDER BY name";
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, siteId);
            if (rows == null) {
                return byCode;
            }
            for (Map<String, Object> row : rows) {
                String code = str(row.get("code"));
                if (code == null || byCode.containsKey(code)) {
                    continue;
                }
                byCode.put(code, row);
            }
        } catch (Exception e) {
            log.error("[站点视频] 站点设备查询失败: siteId={}, err={}", siteId, e.getMessage());
        }
        return byCode;
    }

    /**
     * 批量查各通道最新一轮巡检结果：round_time 最大的一行，返回 code → [result, fault_types]。
     * 巡检表不可达时返回空表（设备列表照常展示，只是无巡检结果）。
     */
    private Map<String, String[]> queryLatestPatrol(Collection<String> codes) {
        Map<String, String[]> result = new LinkedHashMap<>();
        if (codes == null || codes.isEmpty()) {
            return result;
        }
        List<String> params = new ArrayList<>(codes);
        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < params.size(); i++) {
            placeholders.append(i == 0 ? "?" : ", ?");
        }
        try {
            String sql = "SELECT channel_code, result, fault_types FROM ("
                    + "SELECT channel_code, result, fault_types,"
                    + " ROW_NUMBER() OVER (PARTITION BY channel_code ORDER BY round_time DESC) AS rn"
                    + " FROM " + PATROL_TABLE + " WHERE channel_code IN (" + placeholders + ")"
                    + ") t WHERE rn = 1";
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, params.toArray());
            if (rows == null) {
                return result;
            }
            for (Map<String, Object> row : rows) {
                String code = str(row.get("channel_code"));
                if (code == null) {
                    continue;
                }
                result.put(code, new String[]{str(row.get("result")), str(row.get("fault_types"))});
            }
        } catch (Exception e) {
            log.warn("[站点视频] 巡检结果查询失败（设备列表照常返回）: {}", e.getMessage());
        }
        return result;
    }

    /**
     * 通道信息查询：设备树缓存未命中时 findChannel 会实时遍历一次设备树，
     * 单个通道失败不影响整份设备列表（按枪机、离线兜底）。
     */
    private StationStatusSyncService.VideoChannel findChannelQuietly(String code) {
        try {
            return stationSyncService.findChannel(code);
        } catch (Exception e) {
            log.warn("[站点视频] 通道信息查询失败: channelId={}, err={}", code, e.getMessage());
            return null;
        }
    }

    /** 字符串取值：去空白，空串按 null 处理 */
    private static String str(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }
}
