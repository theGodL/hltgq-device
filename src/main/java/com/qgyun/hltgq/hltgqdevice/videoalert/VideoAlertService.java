package com.qgyun.hltgq.hltgqdevice.videoalert;

import com.qgyun.hltgq.hltgqdevice.service.DeviceTableService;
import com.qgyun.hltgq.hltgqdevice.util.IdGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.sql.Timestamp;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 视频故障告警入库服务：检测结果录入平台告警表 {@code t_auto_hltgq_water_alert}。
 * <p>整体模式移植自 hltgq-mq 的 AlertService（同表、同字段语义、同编号规则）：
 * <ul>
 *   <li>code：GJ + yyyyMMddHHmmss + 3位序号（按秒重置、同秒递增）；</li>
 *   <li>content："{站点名}-视频 {故障名}！"，对齐 hltgq-mq "{站点名}-{指标} 设备异常！"风格；</li>
 *   <li>新增默认 status=#1#（未确认），同一未关闭告警（site+device+content 相同）不重复新增；</li>
 *   <li>故障恢复时自动置 status=#4#（已关闭），并联动关闭对应工单；平台侧也可手动维护状态（自动+手动结合）；</li>
 *   <li>type 固定 #2#（异常告警，视频故障不属于阈值超限）；</li>
 *   <li>告警新增成功 → 自动生成工单（{@link WorkOrderService}，移植 hltgq-mq 联动模式）；</li>
 *   <li>动态列适配：启动时加载告警表列元数据，列不存在自动跳过。</li>
 * </ul>
 * <p>device 存设备表 {@code t_auto_hltgq_water_device} 的视频设备 ID（type=#5#）：
 * 设备由站点同步（{@link DeviceTableService}）自动入库，告警侧兜底查/建，
 * 与 hltgq-mq 三字段（site+device+content）去重同构。
 */
@Slf4j
@Service
public class VideoAlertService {

    /** 人大金仓 schema（带双引号，因为含连字符），与 hltgq-mq 一致 */
    private static final String SCHEMA = "\"qixiao-apaas\".";

    /** 告警表 */
    private static final String ALERT_TABLE = SCHEMA + "t_auto_hltgq_water_alert";

    /** 站点信息表 */
    private static final String STATION_TABLE = SCHEMA + "t_auto_hltgq_5nw74_vnqqef";

    /** 处理状态：#1#未确认(新增默认) #4#已关闭(恢复) */
    private static final String STATUS_UNCONFIRMED = "#1#";
    private static final String STATUS_CLOSED = "#4#";

    /** 告警类型：#2# 异常告警（视频故障类） */
    private static final String ALERT_TYPE_ABNORMAL = "#2#";

    /** 告警编号时间格式（DateTimeFormatter 线程安全） */
    private static final DateTimeFormatter CODE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Resource
    private WorkOrderService workOrderService;

    @Resource
    private DeviceTableService deviceTableService;

    @Value("${app.corp-code:hltgq}")
    private String corpCode;

    /** 故障级别覆盖映射（配置项 video-alert.level-map，如 SIGNAL_LOSS:#3#,BLUR:#2#；空=用枚举默认值） */
    @Value("${video-alert.level-map:}")
    private String levelMapConfig;

    /** 告警表有效列名缓存（动态列适配，与 hltgq-mq 一致） */
    private Set<String> alertColumns = new HashSet<>();

    /** 故障级别覆盖表（解析自 levelMapConfig，未配置的项回退枚举默认级别） */
    private final Map<VideoFaultType, String> levelOverrides = new ConcurrentHashMap<>();

    /** 站点缓存：devicecode → 站点信息（TTL 过期刷新——站点名可能被人工编辑，过期后重新查询，
     * 避免按旧名派生设备名造成重复设备；查询失败不缓存可重查） */
    private final ConcurrentMap<String, SiteInfo> siteCache = new ConcurrentHashMap<>();

    /** 站点缓存有效期（秒，配置项 video-alert.site-cache-ttl-seconds，默认 600） */
    @Value("${video-alert.site-cache-ttl-seconds:600}")
    private long siteCacheTtlSeconds;

    /** 告警编号序号：按秒重置、同秒递增 */
    private final AtomicInteger codeSeq = new AtomicInteger();
    private volatile long codeLastSecond = 0;

    @PostConstruct
    public void init() {
        try {
            String sql = "SELECT column_name FROM information_schema.columns " +
                    "WHERE table_schema = 'qixiao-apaas' AND table_name = 't_auto_hltgq_water_alert'";
            List<String> cols = jdbcTemplate.queryForList(sql, String.class);
            alertColumns = new HashSet<>();
            for (String col : cols) {
                alertColumns.add(col.toLowerCase());
            }
            log.info("[视频告警] 已加载告警表列名元数据: {} 列", alertColumns.size());
        } catch (Exception e) {
            log.error("[视频告警] 加载告警表列名元数据失败", e);
        }
        // 故障级别覆盖解析（如 SIGNAL_LOSS:#3#,BLUR:#2#），非法项忽略并告警
        if (levelMapConfig != null && !levelMapConfig.trim().isEmpty()) {
            for (String pair : levelMapConfig.split(",")) {
                String[] kv = pair.trim().split(":");
                if (kv.length != 2 || kv[0].trim().isEmpty() || kv[1].trim().isEmpty()) {
                    log.warn("[视频告警] level-map 配置项非法(忽略): {}", pair);
                    continue;
                }
                try {
                    VideoFaultType ft = VideoFaultType.valueOf(kv[0].trim());
                    levelOverrides.put(ft, kv[1].trim());
                } catch (IllegalArgumentException e) {
                    log.warn("[视频告警] level-map 故障名不存在(忽略): {}", pair);
                }
            }
            log.info("[视频告警] 故障级别覆盖: {}", levelOverrides);
        }
    }

    /** 故障级别：配置覆盖优先，未配置回退枚举默认值 */
    private String levelOf(VideoFaultType fault) {
        return levelOverrides.getOrDefault(fault, fault.getDefaultLevel());
    }

    // ======================== 告警新增 / 恢复关闭 ========================

    /**
     * 新增故障告警：站点名 + 故障名拼装 content（"{站点}-视频 {故障}！"，对齐 hltgq-mq 风格），
     * device 存设备表视频设备 ID（type=#5#，查不到自动兜底创建），
     * 同一未关闭告警（site+device+content）不重复新增，新增成功自动联动生成工单。
     *
     * @param devicecode 通道设备编码（匹配站点表 devicecode）
     * @param fault      故障类型
     * @return true-新增成功，false-站点/设备缺失、已有未关闭同内容告警、入库失败
     */
    public boolean reportFault(String devicecode, VideoFaultType fault) {
        SiteInfo site = resolveSite(devicecode);
        if (site == null) {
            log.warn("[视频告警] 通道 {} 未匹配到站点，跳过告警: {}", devicecode, fault.getLabel());
            return false;
        }
        String deviceId = resolveOrCreateDevice(devicecode, site);
        if (deviceId == null) {
            log.warn("[视频告警] 通道 {} 未匹配到视频设备，跳过告警: {}", devicecode, fault.getLabel());
            return false;
        }
        String content = site.name + "-视频 " + fault.getLabel() + "！";
        // device=设备表视频设备ID（与 hltgq-mq 三字段去重同构）
        if (existsUnclosed(site.id, deviceId, content)) {
            return false;
        }
        insertAlert(site.id, deviceId, content, levelOf(fault));
        log.warn("[视频告警] 新增告警: site={}({}), device={}, fault={}, content={}",
                site.id, site.name, deviceId, fault, content);
        return true;
    }

    /**
     * 故障恢复：按站点+设备+内容精确匹配，关闭未关闭告警（status → #4#），
     * 并联动关闭对应工单（status → #3#，已取消工单除外）。
     *
     * @return 实际关闭的告警条数（人工已关闭的行不重复更新，返回0）
     */
    public int recoverFault(String devicecode, VideoFaultType fault) {
        SiteInfo site = resolveSite(devicecode);
        if (site == null) {
            return 0;
        }
        String deviceId = resolveOrCreateDevice(devicecode, site);
        if (deviceId == null) {
            return 0;
        }
        String content = site.name + "-视频 " + fault.getLabel() + "！";
        int rows = closeByContent(site.id, deviceId, content);
        if (rows > 0) {
            log.info("[视频告警] 恢复关警 {} 条: site={}({}), device={}, fault={}",
                    rows, site.id, site.name, deviceId, fault);
        }
        // 告警恢复 → 同步自动关闭对应工单（与 hltgq-mq closeDeviceError 联动模式一致）
        workOrderService.closeByContent(site.id, deviceId, content);
        return rows;
    }

    // ======================== 数据库操作 ========================

    /**
     * 新增告警行（动态列适配，status 默认 #1#，type=#2# 异常告警，time=当前时间）。
     * 插入成功后自动联动生成工单（与 hltgq-mq insertAlert 联动模式一致）。
     *
     * @param deviceId 设备表视频设备ID（device 字段）
     */
    private void insertAlert(String siteId, String deviceId, String content, String level) {
        try {
            Timestamp now = new Timestamp(System.currentTimeMillis());
            String alertId = IdGenerator.generate();
            Map<String, Object> fm = new LinkedHashMap<>();
            fm.put("id", alertId);
            fm.put("corp_code", corpCode);
            fm.put("created_at", now);
            fm.put("created_by", "SYSTEM");
            fm.put("updated_at", now);
            fm.put("updated_by", "SYSTEM");
            fm.put("code", genAlertCode(now));
            fm.put("site", siteId);
            fm.put("device", deviceId);
            fm.put("content", content);
            fm.put("type", ALERT_TYPE_ABNORMAL);
            fm.put("level", level);
            fm.put("status", STATUS_UNCONFIRMED);
            fm.put("time", now);

            StringBuilder cols = new StringBuilder();
            StringBuilder phs = new StringBuilder();
            List<Object> vals = new ArrayList<>();
            for (Map.Entry<String, Object> e : fm.entrySet()) {
                if (!alertColumns.isEmpty() && !alertColumns.contains(e.getKey().toLowerCase())) {
                    continue; // 列不存在则跳过（动态列适配）
                }
                if (cols.length() > 0) {
                    cols.append(", ");
                    phs.append(", ");
                }
                // level/type 为 SQL 保留字或方言关键字，需加双引号（与 hltgq-mq 一致）
                String col = ("level".equalsIgnoreCase(e.getKey()) || "type".equalsIgnoreCase(e.getKey()))
                        ? "\"" + e.getKey() + "\"" : e.getKey();
                cols.append(col);
                phs.append("?");
                vals.add(e.getValue());
            }
            String sql = String.format("INSERT INTO %s (%s) VALUES (%s)", ALERT_TABLE, cols, phs);
            jdbcTemplate.update(sql, vals.toArray());
            // 告警新增成功 → 自动生成工单（alert 存告警ID精确关联；device 存设备表视频设备ID）
            workOrderService.createIfAbsent(alertId, siteId, deviceId, deriveTitle(content), content);
        } catch (Exception e) {
            log.error("[视频告警] 告警入库失败, site={}, content={}: {}", siteId, content, e.getMessage());
        }
    }

    /** 工单标题派生：告警内容去掉结尾"！"（与 hltgq-mq deriveWorkOrderTitle 一致） */
    private static String deriveTitle(String content) {
        return (content != null && content.endsWith("！"))
                ? content.substring(0, content.length() - 1) : content;
    }

    /** 同站点-设备-内容且未关闭的告警是否存在（新增去重，与 hltgq-mq 三字段同构） */
    private boolean existsUnclosed(String siteId, String deviceId, String content) {
        try {
            String sql = "SELECT COUNT(*) FROM " + ALERT_TABLE +
                    " WHERE site = ? AND device = ? AND content = ? AND status IS DISTINCT FROM ?";
            Integer count = jdbcTemplate.queryForObject(sql, Integer.class, siteId, deviceId, content, STATUS_CLOSED);
            return count != null && count > 0;
        } catch (Exception e) {
            log.debug("[视频告警] 告警去重检查失败, 放行: {}", e.getMessage());
            return false;
        }
    }

    /** 精确 content 关闭（故障恢复用）：仅更新未关闭（status≠#4#）的告警，不覆盖人工已关闭记录 */
    private int closeByContent(String siteId, String deviceId, String content) {
        try {
            String sql = "UPDATE " + ALERT_TABLE +
                    " SET status = ?, updated_at = ?, updated_by = 'SYSTEM' " +
                    " WHERE site = ? AND device = ? AND content = ? AND status IS DISTINCT FROM ?";
            return jdbcTemplate.update(sql, STATUS_CLOSED,
                    new Timestamp(System.currentTimeMillis()), siteId, deviceId, content, STATUS_CLOSED);
        } catch (Exception e) {
            log.debug("[视频告警] 关闭告警失败: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * 告警编号：GJ + yyyyMMddHHmmss + 3位序号（按秒重置、同秒递增），
     * 与 hltgq-mq 完全一致，保证跨服务告警编号格式统一。
     */
    private String genAlertCode(Timestamp tm) {
        long sec = tm.getTime() / 1000;
        int seq;
        synchronized (codeSeq) {
            if (sec != codeLastSecond) {
                codeLastSecond = sec;
                codeSeq.set(0);
            }
            seq = codeSeq.incrementAndGet();
        }
        return "GJ" + tm.toLocalDateTime().format(CODE_TIME_FORMATTER) + String.format("%03d", seq);
    }

    // ======================== 站点 / 设备解析 ========================

    /**
     * devicecode → 站点信息（id + 名称 + 位置），本地缓存 TTL 过期刷新
     * （站点名可能被人工编辑，过期后重查；查询失败兜底返回旧值可重查）。
     */
    private SiteInfo resolveSite(String devicecode) {
        if (devicecode == null || devicecode.trim().isEmpty()) {
            return null;
        }
        String code = devicecode.trim();
        SiteInfo cached = siteCache.get(code);
        if (cached != null && !isSiteCacheExpired(cached)) {
            return cached;
        }
        try {
            String sql = "SELECT id, zzkaec, mivbcz FROM " + STATION_TABLE + " WHERE devicecode = ?";
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, code);
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
            SiteInfo info = new SiteInfo(String.valueOf(id).trim(),
                    name == null ? code : String.valueOf(name).trim(),
                    location == null ? null : String.valueOf(location).trim());
            siteCache.put(code, info);
            return info;
        } catch (Exception e) {
            // 查询失败：兜底返回旧缓存值（可能过期），保证告警不中断；无缓存则返回 null 下轮重试
            log.warn("[视频告警] 站点解析失败, devicecode={}: {}", code, e.getMessage());
            return cached;
        }
    }

    /** 站点缓存是否过期（TTL 由配置项 video-alert.site-cache-ttl-seconds 控制） */
    private boolean isSiteCacheExpired(SiteInfo info) {
        return siteCacheTtlSeconds <= 0
                || System.currentTimeMillis() - info.cachedAt >= siteCacheTtlSeconds * 1000L;
    }

    /** 站点信息快照（不可变，含缓存时间戳用于 TTL 过期判定） */
    private static class SiteInfo {
        final String id;
        final String name;
        /** 站点位置（mivbcz，站点表组织名），可为 null */
        final String location;
        final long cachedAt = System.currentTimeMillis();

        SiteInfo(String id, String name, String location) {
            this.id = id;
            this.name = name;
            this.location = location;
        }
    }

    /**
     * devicecode → 设备表视频设备ID（type=#5#）：设备名 = 站点名 + 后缀 + "#"，
     * 查不到自动兜底创建（设备状态留空，由站点同步维护）；设备表不可达返回 null。
     */
    private String resolveOrCreateDevice(String devicecode, SiteInfo site) {
        try {
            String deviceName = deviceTableService.deviceNameOf(site.name);
            // 安装位置：站点位置（组织名）-站点名，org 缺失时仅站点名
            String location = DeviceTableService.buildLocation(site.location, site.name);
            return deviceTableService.lookupOrCreateDevice(deviceName, site.id,
                    DeviceTableService.DEVICE_TYPE_VIDEO, devicecode, null, location);
        } catch (Exception e) {
            log.warn("[视频告警] 设备解析失败, devicecode={}: {}", devicecode, e.getMessage());
            return null;
        }
    }
}
