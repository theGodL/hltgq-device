package com.qgyun.hltgq.hltgqdevice.videoalert;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.dahuatech.hutool.http.Method;
import com.dahuatech.icc.exception.ClientException;
import com.dahuatech.icc.oauth.model.v202010.GeneralResponse;
import com.dahuatech.icc.oauth.utils.HttpUtils;
import com.qgyun.hltgq.hltgqdevice.config.DahuaConfig;
import com.qgyun.hltgq.hltgqdevice.service.DahuaAuthService;
import com.qgyun.hltgq.hltgqdevice.service.DeviceTableService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * IVSS 智能事件处理服务：大华 ICC 事件订阅推送的报警事件
 * （category=alarm, method=alarm.msg）解析后映射到视频站点/设备，
 * 复用告警/工单闭环（{@link VideoAlertService}）。
 * <p>处理规则：
 * <ul>
 *   <li>仅处理 alarm.msg：业务/状态/感知类事件直接忽略；</li>
 *   <li>nodeCode（通道编码）→ 站点表 devicecode + epjutj LIKE '%#5#%' 限定
 *       （防同 devicecode 的闸门/水质站点误命中，2026-09-05 线上教训），未匹配=非视频子系统事件，丢弃；</li>
 *   <li>alarmStat=1（发生）→ 新增告警（content="{站点}-视频智能事件 {事件名}！"，前缀与图像故障隔离防撞）；
 *       alarmStat=2（消失）→ 按 content 关闭告警并联动工单；</li>
 *   <li>事件名透传大华 alarmTypeName（官方文档无静态 alarmType 码表，名称由平台动态下发），
 *       缺失时兜底 alarmType 码值，两者皆无则丢弃（宁缺毋滥）；</li>
 *   <li>alarmGrade 1~4 → 告警级别 #1#~#4#（缺失默认 #2#）；</li>
 *   <li>幂等：uuid 内存去重（防平台重推）+ content 三字段去重（跨重启幂等，复用告警去重机制）。</li>
 * </ul>
 * <p>智能事件自带 alarmStat 发生/恢复配对，不做防抖，收到即落库。
 */
@Slf4j
@Service
public class VideoEventService {

    /** 人大金仓 schema（带双引号，因为含连字符），与 hltgq-mq 一致 */
    private static final String SCHEMA = "\"qixiao-apaas\".";

    /** 站点信息表 */
    private static final String STATION_TABLE = SCHEMA + "t_auto_hltgq_5nw74_vnqqef";

    /** 事件大类/方法（仅处理报警消息） */
    private static final String CATEGORY_ALARM = "alarm";
    private static final String METHOD_ALARM_MSG = "alarm.msg";

    /** 事件发生/恢复（alarmStat 语义：1=报警产生 2=报警消失，官方文档口径） */
    private static final String ALARM_STAT_ON = "1";
    private static final String ALARM_STAT_OFF = "2";

    /** 智能事件告警 content 前缀（与图像故障 "{站点}-视频 {故障}！" 隔离，防同名互串去重） */
    private static final String CONTENT_PREFIX = "-视频智能事件 ";

    /** uuid 幂等窗口（毫秒）：平台重推间隔远小于此值；超窗自动清理防止内存膨胀 */
    private static final long UUID_DEDUP_WINDOW_MS = 60 * 60 * 1000L;

    /** 事件分页查询接口路径（轮询兜底，ICC 网关 1.2.0） */
    private static final String EVENT_PAGE_PATH = "/evo-apigw/evo-event/1.2.0/alarm-record/page";

    /** 单轮拉取上限（30分钟事件量远小于此值；多页场景忽略后续页，下轮时间窗已推进） */
    private static final int EVENT_PAGE_SIZE = 500;

    /** 事件查询时间格式（与 ICC 平台时间入参约定一致） */
    private static final DateTimeFormatter EVENT_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Resource
    private DeviceTableService deviceTableService;

    @Resource
    private VideoAlertService alertService;

    @Resource
    private DahuaAuthService authService;

    @Resource
    private DahuaConfig dahuaConfig;

    /** uuid 幂等去重（防平台重推）：uuid → 处理时间戳 */
    private final ConcurrentMap<String, Long> handledUuids = new ConcurrentHashMap<>();

    /** 轮询兜底游标：上次成功拉取的结束时间（毫秒），首次=服务启动前30分钟 */
    private volatile long lastPullTime = System.currentTimeMillis() - 30 * 60 * 1000L;

    /**
     * 处理一条事件 JSON（事件回调与轮询兜底共用入口）。
     *
     * @param json 平台推送的事件消息体（通用事件格式）
     * @return true-已处理，false-已忽略（重复/非视频/字段缺失）
     */
    public boolean handleEvent(String json) {
        if (json == null || json.trim().isEmpty()) {
            return false;
        }
        JSONObject event;
        try {
            event = JSON.parseObject(json);
        } catch (Exception e) {
            log.warn("[智能事件] 事件JSON解析失败: {}", e.getMessage());
            return false;
        }
        if (event == null) {
            return false;
        }
        String category = event.getString("category");
        String method = event.getString("method");
        if (!CATEGORY_ALARM.equals(category) || !METHOD_ALARM_MSG.equals(method)) {
            log.debug("[智能事件] 忽略非报警消息: category={}, method={}", category, method);
            return false;
        }

        // uuid 幂等：平台重推同一事件时直接跳过
        String uuid = event.getString("uuid");
        if (uuid != null && !uuid.trim().isEmpty() && !markHandled(uuid)) {
            log.debug("[智能事件] 重复事件已处理，跳过: uuid={}", uuid);
            return false;
        }

        JSONObject info = event.getJSONObject("info");
        if (info == null) {
            return false;
        }
        String nodeCode = trimToNull(info.getString("nodeCode"));
        if (nodeCode == null) {
            log.debug("[智能事件] 事件缺少nodeCode，跳过");
            return false;
        }

        // 事件名：alarmTypeName 透传（平台动态下发），缺失兜底 alarmType 码值
        String eventName = trimToNull(info.getString("alarmTypeName"));
        if (eventName == null) {
            Object alarmType = info.get("alarmType");
            if (alarmType == null || String.valueOf(alarmType).trim().isEmpty()) {
                log.warn("[智能事件] 事件缺少alarmTypeName/alarmType，丢弃: nodeCode={}", nodeCode);
                return false;
            }
            eventName = "智能事件" + String.valueOf(alarmType).trim();
        }

        // nodeCode → 视频站点（epjutj 限定，非视频子系统事件自然丢弃）
        StationRef station = resolveStation(nodeCode);
        if (station == null) {
            log.debug("[智能事件] nodeCode={} 未匹配到视频站点，丢弃", nodeCode);
            return false;
        }

        // 站点 → 视频设备（code 匹配，查不到自动兜底创建，设备状态由站点同步维护）
        String deviceId = deviceTableService.lookupOrCreateDevice(
                deviceTableService.deviceNameOf(station.name), station.id,
                DeviceTableService.DEVICE_TYPE_VIDEO, nodeCode, null, station.location);
        if (deviceId == null) {
            log.warn("[智能事件] 设备解析失败，丢弃: nodeCode={}", nodeCode);
            return false;
        }

        String content = station.name + CONTENT_PREFIX + eventName + "！";
        String alarmStat = String.valueOf(info.get("alarmStat"));
        if (ALARM_STAT_ON.equals(alarmStat) || ALARM_STAT_OFF.equals(alarmStat)) {
            // 联调日志：打印事件原始字段与映射结果，部署后从服务器日志即可核对
            // alarmGrade 语义（1~4 与一般~特别严重对应关系）与事件名透传情况，无需人工事前确认
            Integer alarmGrade = info.getInteger("alarmGrade");
            String level = gradeToLevel(alarmGrade);
            log.info("[智能事件] 事件映射: nodeCode={}, 事件名={}, alarmType={}, alarmGrade={} → 级别{}, alarmStat={} → {}, content={}",
                    nodeCode, eventName, info.get("alarmType"), alarmGrade,
                    level, alarmStat, ALARM_STAT_ON.equals(alarmStat) ? "新增告警" : "关闭告警", content);
            if (ALARM_STAT_ON.equals(alarmStat)) {
                alertService.reportEventAlert(station.id, deviceId, content, level);
                return true;
            }
            alertService.closeEventAlert(station.id, deviceId, content);
            return true;
        }
        log.warn("[智能事件] 未知alarmStat={}，跳过: nodeCode={}", alarmStat, nodeCode);
        return false;
    }

    // ======================== 轮询兜底（补偿回调丢失的事件） ========================

    /**
     * 轮询兜底：按时间窗拉取 alarm-record/page 遗漏事件（补偿订阅回调网络抖动丢失），
     * 与回调共用 {@link #handleEvent} 解析/幂等链路。由 {@code VideoAlertScheduler}
     * 每轮检测后调用（30分钟节拍）。拉取失败游标不推进，下轮重拉同窗口（重复由幂等兜底）。
     *
     * @return 拉取并处理的事件条数（-1=拉取失败）
     */
    public int pullRecentEvents() {
        if (!Boolean.TRUE.equals(dahuaConfig.getEventEnabled())) {
            return 0;
        }
        long windowStart = lastPullTime;
        long windowEnd = System.currentTimeMillis();
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("pageNum", 1);
            body.put("pageSize", EVENT_PAGE_SIZE);
            body.put("alarmStartDateString", formatTime(windowStart));
            body.put("alarmEndDateString", formatTime(windowEnd));

            String responseJson = HttpUtils.executeJson(
                    EVENT_PAGE_PATH,
                    body,
                    null,
                    Method.POST,
                    authService.getOauthConfig(),
                    GeneralResponse.class
            ).getResult().toString();

            JSONObject response = JSON.parseObject(responseJson);
            if (response == null || !"0".equals(response.getString("code"))) {
                log.warn("[智能事件] 事件分页查询失败: code={}, errMsg={}",
                        response == null ? null : response.getString("code"),
                        response == null ? null : response.getString("errMsg"));
                return -1;
            }
            JSONObject data = response.getJSONObject("data");
            JSONArray pageData = data == null ? null : data.getJSONArray("pageData");
            if (pageData == null) {
                lastPullTime = windowEnd;
                return 0;
            }
            int handled = 0;
            for (Object item : pageData) {
                if (item instanceof JSONObject && handlePolledRecord((JSONObject) item)) {
                    handled++;
                }
            }
            lastPullTime = windowEnd;
            if (handled > 0) {
                log.info("[智能事件] 轮询兜底处理事件 {} 条（窗口 {} ~ {}）",
                        handled, formatTime(windowStart), formatTime(windowEnd));
            }
            return handled;
        } catch (ClientException e) {
            log.warn("[智能事件] 事件分页查询异常: {}", e.getErrMsg());
            return -1;
        } catch (Exception e) {
            log.warn("[智能事件] 事件分页查询异常: {}", e.getMessage());
            return -1;
        }
    }

    /**
     * 查询记录 → 通用事件格式 JSON → {@link #handleEvent}。
     * uuid 构造为 "poll_alarmCode_alarmStat"（查询记录无平台 uuid）；
     * 与回调路径的跨路径重复由告警 content 三字段去重兜底。
     */
    private boolean handlePolledRecord(JSONObject rec) {
        String nodeCode = trimToNull(rec.getString("nodeCode"));
        if (nodeCode == null) {
            return false;
        }
        Integer alarmStat = rec.getInteger("alarmStat");
        if (alarmStat == null) {
            return false;
        }
        String recordKey = rec.getString("alarmCode") != null
                ? rec.getString("alarmCode") : String.valueOf(rec.get("id"));
        JSONObject info = new JSONObject();
        info.put("nodeCode", nodeCode);
        info.put("alarmTypeName", rec.getString("alarmTypeName"));
        info.put("alarmType", rec.getInteger("alarmType"));
        info.put("alarmGrade", rec.getInteger("alarmGrade"));
        info.put("alarmStat", alarmStat);
        JSONObject event = new JSONObject();
        event.put("category", CATEGORY_ALARM);
        event.put("method", METHOD_ALARM_MSG);
        event.put("uuid", "poll_" + recordKey + "_" + alarmStat);
        event.put("info", info);
        return handleEvent(event.toJSONString());
    }

    /** 毫秒时间戳 → ICC 时间入参格式（yyyy-MM-dd HH:mm:ss，系统默认时区） */
    private String formatTime(long millis) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault())
                .format(EVENT_TIME_FORMATTER);
    }

    /**
     * nodeCode → 视频站点（id + 名称 + 位置）。
     * epjutj LIKE '%#5#%' 限定：站点表存在与视频通道同 devicecode 的闸门/水质站点
     * （2026-09-05 线上发现 1000230$1$0$11/1000328$1$0$0 重复），不限定会误命中非视频站点。
     */
    private StationRef resolveStation(String nodeCode) {
        try {
            String sql = "SELECT id, zzkaec, mivbcz FROM " + STATION_TABLE +
                    " WHERE devicecode = ? AND epjutj LIKE '%#5#%'";
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, nodeCode);
            if (rows.isEmpty()) {
                return null;
            }
            Map<String, Object> row = rows.get(0);
            Object id = row.get("id");
            Object name = row.get("zzkaec");
            if (id == null || String.valueOf(id).trim().isEmpty()) {
                return null;
            }
            Object location = row.get("mivbcz");
            return new StationRef(String.valueOf(id).trim(),
                    name == null || String.valueOf(name).trim().isEmpty()
                            ? nodeCode : String.valueOf(name).trim(),
                    location == null ? null : String.valueOf(location).trim());
        } catch (Exception e) {
            log.warn("[智能事件] 站点解析失败, nodeCode={}: {}", nodeCode, e.getMessage());
            return null;
        }
    }

    /** alarmGrade 1~4 → 告警级别 #1#~#4#（缺失默认 #2# 较重） */
    private String gradeToLevel(Integer grade) {
        if (grade == null) {
            return "#2#";
        }
        switch (grade) {
            case 1: return "#1#";
            case 3: return "#3#";
            case 4: return "#4#";
            default: return "#2#";
        }
    }

    /** 标记 uuid 已处理并清理过期项；返回 true=首次处理，false=重复 */
    private boolean markHandled(String uuid) {
        long now = System.currentTimeMillis();
        // 惰性清理：窗口外的 uuid 直接移除（防内存膨胀）
        handledUuids.entrySet().removeIf(e -> now - e.getValue() > UUID_DEDUP_WINDOW_MS);
        Long prev = handledUuids.putIfAbsent(uuid, now);
        return prev == null;
    }

    private String trimToNull(String s) {
        return (s == null || s.trim().isEmpty()) ? null : s.trim();
    }

    /** 视频站点快照（不可变） */
    private static class StationRef {
        final String id;
        final String name;
        final String location;

        StationRef(String id, String name, String location) {
            this.id = id;
            this.name = name;
            this.location = location;
        }
    }
}
