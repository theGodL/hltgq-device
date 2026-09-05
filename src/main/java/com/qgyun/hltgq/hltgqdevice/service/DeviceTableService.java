package com.qgyun.hltgq.hltgqdevice.service;

import com.qgyun.hltgq.hltgqdevice.util.IdGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 视频设备表服务：视频设备入库 {@code t_auto_hltgq_water_device}（type=#5# 视频），
 * 模式移植自 hltgq-mq MonitorDataService.lookupOrCreateDeviceByName（同表、同字段语义）：
 * <ul>
 *   <li>设备名 = 站点名 + 后缀（默认"摄像机"）+ "#"，如"南山寺节制闸摄像机#"
 *       （站点名含"-视频"后缀时如"夏家湖渡槽-视频摄像机#"），
 *       对齐 hltgq-mq "{站点名}{设备类型}#"（如"集岭管理所雨量计#"）命名风格；</li>
 *   <li>每个视频站点（通道）对应 1 台设备，site 存站点ID，code 存通道 devicecode；</li>
 *   <li>设备匹配键 = code（通道 devicecode，唯一）；name 仅展示（创建时写入）。历史教训：
 *       按 name 匹配时僵尸站点与树内站点重名（如两个"大门外"），树内同步会波及同名僵尸设备、
 *       树内站点自身设备反而错配（2026-09-05 线上核对发现 21 条 wlcvig 不一致）；</li>
 *   <li>code → deviceId 永久缓存（code 稳定，查询/创建失败不缓存可重试）；</li>
 *   <li>动态列适配：启动时加载设备表列元数据，列不存在自动跳过；</li>
 *   <li>设备运行状态 status（#1#在线/#2#离线）由站点同步维护；</li>
 *   <li>启动迁移（幂等）：为历史视频站点补建设备，并把历史告警/工单中 device=站点ID 的
 *       旧数据更新为设备ID，保证恢复闭环按 site+device+content 精确匹配不断裂。</li>
 * </ul>
 *
 * @author hltgq-device
 */
@Slf4j
@Service
public class DeviceTableService {

    /** 人大金仓 schema（带双引号，因为含连字符），与 hltgq-mq 一致 */
    private static final String SCHEMA = "\"qixiao-apaas\".";

    /** 设备表 / 站点表 / 告警表 / 工单表 */
    private static final String DEVICE_TABLE = SCHEMA + "t_auto_hltgq_water_device";
    private static final String STATION_TABLE = SCHEMA + "t_auto_hltgq_5nw74_vnqqef";
    private static final String ALERT_TABLE = SCHEMA + "t_auto_hltgq_water_alert";
    private static final String WORK_ORDER_TABLE = SCHEMA + "t_auto_hltgq_water_work_order";

    /** 视频设备类型（type=#5# 视频，与设备表字典一致） */
    public static final String DEVICE_TYPE_VIDEO = "#5#";

    /** 设备运行状态（status）：#1#在线 #2#离线 */
    public static final String DEVICE_STATUS_ONLINE = "#1#";
    public static final String DEVICE_STATUS_OFFLINE = "#2#";

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Value("${app.corp-code:hltgq}")
    private String corpCode;

    /** 视频设备名称后缀（默认"摄像机"，与 hltgq-mq "雨量计#" 命名风格一致） */
    @Value("${video-alert.device-suffix:摄像机}")
    private String deviceSuffix;

    /** 设备表有效列名缓存（动态列适配，与 hltgq-mq 一致） */
    private Set<String> deviceColumns = Collections.emptySet();

    /** 工单表是否含 device 列（启动迁移用，动态列适配） */
    private volatile boolean workOrderHasDevice = true;

    /** 设备缓存：code → deviceId（code 缺失回退 name；永久缓存，创建失败不缓存可重试） */
    private final ConcurrentMap<String, String> deviceCache = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        // 1. 设备表列名元数据（列不存在时自动跳过，动态列适配）
        try {
            String sql = "SELECT column_name FROM information_schema.columns " +
                    "WHERE table_schema = 'qixiao-apaas' AND table_name = 't_auto_hltgq_water_device'";
            List<String> cols = jdbcTemplate.queryForList(sql, String.class);
            Set<String> set = new HashSet<>();
            for (String col : cols) {
                set.add(col.toLowerCase());
            }
            deviceColumns = set;
            log.info("[视频设备] 已加载设备表列名元数据: {} 列", deviceColumns.size());
        } catch (Exception e) {
            log.error("[视频设备] 加载设备表列名元数据失败", e);
        }
        // 2. 工单表 device 列探测（历史迁移用）
        try {
            String sql = "SELECT column_name FROM information_schema.columns " +
                    "WHERE table_schema = 'qixiao-apaas' AND table_name = 't_auto_hltgq_water_work_order'";
            List<String> cols = jdbcTemplate.queryForList(sql, String.class);
            workOrderHasDevice = false;
            for (String col : cols) {
                if ("device".equalsIgnoreCase(col)) {
                    workOrderHasDevice = true;
                    break;
                }
            }
        } catch (Exception e) {
            log.warn("[视频设备] 工单表列名元数据加载失败，迁移按含device列处理", e);
        }
        // 3. 历史数据迁移（幂等，异常不阻塞启动）
        migrateLegacyDeviceRefs();
    }

    /** 设备名派生：站点名 + 后缀 + "#"（如"渠首电站上游摄像机#"） */
    public String deviceNameOf(String siteName) {
        if (siteName == null || siteName.trim().isEmpty()) {
            return null;
        }
        return siteName.trim() + deviceSuffix + "#";
    }

    /**
     * 设备安装位置派生：{所属组织}-{站点名}（如"库上防汛办-渠首电站上游"），
     * 组织名或站点名缺失时仅保留另一侧，均缺失返回 null。
     */
    public static String buildLocation(String orgName, String stationName) {
        String org = orgName == null ? null : orgName.trim();
        String station = stationName == null ? null : stationName.trim();
        if (org == null || org.isEmpty()) {
            return station == null || station.isEmpty() ? null : station;
        }
        if (station == null || station.isEmpty()) {
            return org;
        }
        return org + "-" + station;
    }

    // ======================== 查找 / 创建 ========================

    /**
     * 按通道 devicecode（code）查找或创建设备（带缓存），可选写入 type/code/status/location。
     * <p>code 为设备唯一匹配键；name 仅作展示字段（创建时写入）。
     * 历史设备可能缺失 code（早期版本按 name 创建），code 查不到时按 name 兜底并回填 code
     * （按设备ID精确回填，避免 name 重名波及多行）。
     * <p>查询失败/创建失败返回 null 且不缓存，下次调用重试。
     *
     * @param location 安装位置（wlcvig，可为 null；创建时写入，已存在设备不更新）
     * @return 设备ID；设备名缺失或数据库不可达时返回 null
     */
    public String lookupOrCreateDevice(String name, String siteId, String type, String code, String status,
                                       String location) {
        if (name == null || name.trim().isEmpty()) {
            return null;
        }
        // 匹配键：通道 devicecode 唯一；历史设备缺失 code 时回退 name（旧行为）
        String matchKey = (code != null && !code.trim().isEmpty()) ? code.trim() : name.trim();
        String cached = deviceCache.get(matchKey);
        if (cached != null) {
            return cached;
        }
        // computeIfAbsent 内返回 null 不缓存（ConcurrentHashMap 语义），创建失败下次重试
        return deviceCache.computeIfAbsent(matchKey, key -> {
            String id = findDeviceByCodeOrName(key, name.trim());
            return id != null ? id : createDevice(name.trim(), siteId, type, code, status, location);
        });
    }

    /**
     * 按 code 查设备ID；code 查不到时按 name 兜底（历史设备缺失 code 场景），
     * 命中后回填 code（按设备ID精确更新），后续轮次即可按 code 匹配。
     */
    private String findDeviceByCodeOrName(String key, String name) {
        String id = findDeviceByKey("code", key);
        if (id != null) {
            return id;
        }
        String byName = findDeviceByKey("name", name);
        if (byName != null && !key.equals(name)) {
            backfillCode(byName, key);
        }
        return byName;
    }

    /** 按设备表列（code/name，内部常量）查视频设备ID（不创建），不存在或查询失败返回 null */
    private String findDeviceByKey(String column, String value) {
        try {
            // type 条件限定视频设备：站点表存在与视频通道同 devicecode 的其他类型站点
            // （如闸门），其设备 code 相同，必须排除（2026-09-05 线上发现 1000230$1$0$11/1000328$1$0$0 重复）
            String sql = "SELECT id FROM " + DEVICE_TABLE + " WHERE " + column
                    + " = ? AND type = '" + DEVICE_TYPE_VIDEO + "'";
            List<String> results = jdbcTemplate.queryForList(sql, String.class, value);
            if (results != null && !results.isEmpty()
                    && results.get(0) != null && !results.get(0).trim().isEmpty()) {
                return results.get(0).trim();
            }
        } catch (Exception e) {
            log.warn("[视频设备] 查找设备失败, {}={}: {}", column, value, e.getMessage());
        }
        return null;
    }

    /**
     * 历史设备 code 缺失回填：按设备ID精确更新（防 name 重名时波及多行），
     * 仅 code 为空时写入，不覆盖已有 code。
     */
    private void backfillCode(String deviceId, String code) {
        try {
            String sql = "UPDATE " + DEVICE_TABLE +
                    " SET code = ?, updated_at = ?, updated_by = 'SYSTEM' " +
                    " WHERE id = ? AND (code IS NULL OR code = '')";
            jdbcTemplate.update(sql, code, new Timestamp(System.currentTimeMillis()), deviceId);
            log.info("[视频设备] 历史设备code回填: id={}, code={}", deviceId, code);
        } catch (Exception e) {
            log.warn("[视频设备] 设备code回填失败, id={}: {}", deviceId, e.getMessage());
        }
    }

    /**
     * 创建设备（动态列适配：列不存在自动跳过），失败返回 null。
     * <p>wlcvig（安装位置）写入 location；入库日期 tm / 启用日期 ptlink 无真实数据源，
     * 不自动写入（宁缺毋滥，留空由业务人工维护）。
     */
    private String createDevice(String name, String siteId, String type, String code, String status,
                                String location) {
        try {
            String deviceId = IdGenerator.generate();
            Timestamp now = new Timestamp(System.currentTimeMillis());
            Map<String, Object> fm = new LinkedHashMap<>();
            fm.put("id", deviceId);
            fm.put("corp_code", corpCode);
            fm.put("created_at", now);
            fm.put("created_by", "SYSTEM");
            fm.put("updated_at", now);
            fm.put("updated_by", "SYSTEM");
            fm.put("name", name);
            if (siteId != null) {
                fm.put("site", siteId);
            }
            if (type != null) {
                fm.put("type", type);
            }
            if (code != null && !code.trim().isEmpty()) {
                fm.put("code", code.trim());
            }
            if (status != null) {
                fm.put("status", status);
            }
            if (location != null && !location.trim().isEmpty()) {
                fm.put("wlcvig", location.trim());
            }
            // 入库日期（tm）/启用日期（ptlink）：无真实数据源，不写入（留空人工维护，宁缺毋滥）

            StringBuilder cols = new StringBuilder();
            StringBuilder phs = new StringBuilder();
            List<Object> vals = new ArrayList<>();
            for (Map.Entry<String, Object> e : fm.entrySet()) {
                if (!deviceColumns.isEmpty() && !deviceColumns.contains(e.getKey().toLowerCase())) {
                    continue; // 列不存在则跳过（动态列适配）
                }
                if (cols.length() > 0) {
                    cols.append(", ");
                    phs.append(", ");
                }
                // type 为 SQL 方言关键字，加双引号保险（与告警表写入一致）
                String col = "type".equalsIgnoreCase(e.getKey()) ? "\"type\"" : e.getKey();
                cols.append(col);
                phs.append("?");
                vals.add(e.getValue());
            }
            String sql = String.format("INSERT INTO %s (%s) VALUES (%s)", DEVICE_TABLE, cols, phs);
            jdbcTemplate.update(sql, vals.toArray());
            log.info("[视频设备] 已自动创建视频设备: name={}, id={}, site={}, type={}, code={}, status={}",
                    name, deviceId, siteId, type, code, status);
            return deviceId;
        } catch (Exception e) {
            log.error("[视频设备] 创建设备失败, name={}: {}", name, e.getMessage());
            return null;
        }
    }

    // ======================== 状态同步 ========================

    /**
     * 更新设备运行状态（#1#在线/#2#离线）：仅状态变化时更新（status IS DISTINCT FROM 条件）。
     * 按通道 devicecode（code）精确匹配——历史教训：按 name 匹配会波及同名设备（僵尸/重名站点）。
     *
     * @param code 通道 devicecode（唯一匹配键，为空跳过）
     * @return 实际更新的行数（无变化为 0）
     */
    public int updateDeviceStatus(String code, String status) {
        if (code == null || code.trim().isEmpty() || status == null) {
            return 0;
        }
        try {
            String sql = "UPDATE " + DEVICE_TABLE +
                    " SET status = ?, updated_at = ?, updated_by = 'SYSTEM' " +
                    " WHERE code = ? AND type = '" + DEVICE_TYPE_VIDEO +
                    "' AND status IS DISTINCT FROM ?";
            return jdbcTemplate.update(sql, status,
                    new Timestamp(System.currentTimeMillis()), code.trim(), status);
        } catch (Exception e) {
            log.warn("[视频设备] 更新设备状态失败, code={}: {}", code, e.getMessage());
            return 0;
        }
    }

    /**
     * 更新设备安装位置（wlcvig变化时）：与站点位置同源（ICC设备树层级），
     * 随设备树层级调整持续同步（IS DISTINCT FROM 变化才写库）。
     * 按通道 devicecode（code）精确匹配（防 name 重名波及同名设备）。
     * 设备表无 wlcvig 列时自动跳过（动态列适配）。
     *
     * @param code 通道 devicecode（唯一匹配键，为空跳过）
     * @return 实际更新的行数（无变化为 0）
     */
    public int updateDeviceLocation(String code, String location) {
        if (code == null || code.trim().isEmpty() || location == null || location.trim().isEmpty()) {
            return 0;
        }
        if (!deviceColumns.isEmpty() && !deviceColumns.contains("wlcvig")) {
            return 0;
        }
        try {
            String sql = "UPDATE " + DEVICE_TABLE +
                    " SET wlcvig = ?, updated_at = ?, updated_by = 'SYSTEM' " +
                    " WHERE code = ? AND type = '" + DEVICE_TYPE_VIDEO +
                    "' AND wlcvig IS DISTINCT FROM ?";
            return jdbcTemplate.update(sql, location.trim(),
                    new Timestamp(System.currentTimeMillis()), code.trim(), location.trim());
        } catch (Exception e) {
            log.warn("[视频设备] 更新设备安装位置失败, code={}: {}", code, e.getMessage());
            return 0;
        }
    }

    /**
     * 站点消失联动：按站点ID批量将设备标注离线（#2#），分批500防止参数超限。
     * 设备表无 status 列时自动跳过（动态列适配）。
     *
     * @return 标注离线的设备行数
     */
    public int markOfflineBySiteIds(List<String> siteIds) {
        if (siteIds == null || siteIds.isEmpty()) {
            return 0;
        }
        if (!deviceColumns.isEmpty() && !deviceColumns.contains("status")) {
            log.warn("[视频设备] 设备表无 status 列，跳过离线标注");
            return 0;
        }
        int total = 0;
        Timestamp now = new Timestamp(System.currentTimeMillis());
        try {
            for (int i = 0; i < siteIds.size(); i += 500) {
                List<String> chunk = siteIds.subList(i, Math.min(i + 500, siteIds.size()));
                StringBuilder sql = new StringBuilder("UPDATE " + DEVICE_TABLE +
                        " SET status = ?, updated_at = ?, updated_by = 'SYSTEM' WHERE site IN (");
                for (int j = 0; j < chunk.size(); j++) {
                    sql.append(j == 0 ? "?" : ", ?");
                }
                sql.append(") AND status IS DISTINCT FROM ?");
                List<Object> params = new ArrayList<>();
                params.add(DEVICE_STATUS_OFFLINE);
                params.add(now);
                params.addAll(chunk);
                params.add(DEVICE_STATUS_OFFLINE);
                total += jdbcTemplate.update(sql.toString(), params.toArray());
            }
        } catch (Exception e) {
            log.warn("[视频设备] 设备批量标离线失败: {}", e.getMessage());
        }
        return total;
    }

    // ======================== 启动迁移（历史数据，幂等） ========================

    /**
     * 历史数据迁移：视频告警早期版本 device 塞的是站点ID（当时视频设备未入库），
     * 现按"每视频站点 1 台设备"补齐设备记录，并把历史告警/工单的 device 更新为设备ID。
     * <p>幂等：迁移后 device=设备ID，再跑时 UPDATE 条件（device=站点ID）不再命中；
     * 仅处理 device=站点ID 的历史行，未来新数据（device=设备ID）不受影响。
     * <p>历史设备字段回填（仅空值写入，不覆盖人工编辑）：
     * 运行状态按站点 zebpsu 对齐（变化才写库）、安装位置 wlcvig 空值回填（值取站点 mivbcz，
     * 在树站点由同步轮持续修正为"管理所级组织-位置节点"格式）。
     * 入库日期 tm / 启用日期 ptlink 无真实数据源，不回填（留空人工维护，宁缺毋滥）。
     */
    private void migrateLegacyDeviceRefs() {
        int stations = 0, alertMigrated = 0, orderMigrated = 0, statusAligned = 0,
                locationBackfilled = 0;
        Timestamp now = new Timestamp(System.currentTimeMillis());
        try {
            String sql = "SELECT id, devicecode, zzkaec, mivbcz, zebpsu FROM " + STATION_TABLE +
                    " WHERE epjutj LIKE '%#5#%'";
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
            if (rows == null || rows.isEmpty()) {
                return;
            }
            for (Map<String, Object> row : rows) {
                Object sid = row.get("id");
                Object sname = row.get("zzkaec");
                if (sid == null || sname == null || String.valueOf(sname).trim().isEmpty()) {
                    continue;
                }
                String siteId = String.valueOf(sid).trim();
                String deviceName = deviceNameOf(String.valueOf(sname).trim());
                if (deviceName == null) {
                    continue;
                }
                // 匹配键 code（通道 devicecode，唯一）；历史设备缺失 code 时由 lookupOrCreateDevice 兜底回填
                String deviceCode = row.get("devicecode") == null ? null
                        : String.valueOf(row.get("devicecode")).trim();
                String location = row.get("mivbcz") == null ? null
                        : String.valueOf(row.get("mivbcz")).trim();
                String stationStatus = row.get("zebpsu") == null ? null
                        : String.valueOf(row.get("zebpsu")).trim();
                String deviceId = lookupOrCreateDevice(deviceName, siteId, DEVICE_TYPE_VIDEO,
                        deviceCode, stationStatus, location);
                if (deviceId == null) {
                    log.warn("[视频设备] 迁移: 设备创建失败, site={}, name={}", siteId, deviceName);
                    continue;
                }
                // 历史设备字段回填：运行状态按站点 zebpsu 对齐（IS DISTINCT FROM 变化才写），
                // 安装位置空值回填（不覆盖人工编辑值）——
                // 同步轮覆盖不到的消失站点设备也能对齐
                if (stationStatus != null && !stationStatus.isEmpty()) {
                    statusAligned += updateDeviceStatus(deviceCode, stationStatus);
                }
                locationBackfilled += backfillLocation(deviceCode, location);
                stations++;
                // 告警表：device=站点ID 的历史行 → 设备ID（幂等）
                try {
                    int n = jdbcTemplate.update("UPDATE " + ALERT_TABLE +
                                    " SET device = ?, updated_at = ?, updated_by = 'SYSTEM' WHERE site = ? AND device = ?",
                            deviceId, now, siteId, siteId);
                    if (n > 0) {
                        alertMigrated += n;
                        log.info("[视频设备] 迁移: 告警device更新{}行, site={} → device={}", n, siteId, deviceId);
                    }
                } catch (Exception e) {
                    log.warn("[视频设备] 迁移: 告警device更新失败, site={}: {}", siteId, e.getMessage());
                }
                // 工单表：含 device 列才执行（动态列适配）
                if (workOrderHasDevice) {
                    try {
                        int n = jdbcTemplate.update("UPDATE " + WORK_ORDER_TABLE +
                                        " SET device = ?, updated_at = ?, updated_by = 'SYSTEM' WHERE site = ? AND device = ?",
                                deviceId, now, siteId, siteId);
                        if (n > 0) {
                            orderMigrated += n;
                            log.info("[视频设备] 迁移: 工单device更新{}行, site={} → device={}", n, siteId, deviceId);
                        }
                    } catch (Exception e) {
                        log.warn("[视频设备] 迁移: 工单device更新失败, site={}: {}", siteId, e.getMessage());
                    }
                }
            }
            log.info("[视频设备] 历史数据迁移完成: 视频站点{}个, 设备状态对齐{}台, 安装位置回填{}台, " +
                            "告警device更新{}行, 工单device更新{}行",
                    stations, statusAligned, locationBackfilled, alertMigrated, orderMigrated);
        } catch (Exception e) {
            log.error("[视频设备] 历史数据迁移失败", e);
        }
    }

    /**
     * 安装位置空值回填（历史设备创建时未写 wlcvig，启动迁移补填）：
     * 回填值取站点位置 mivbcz（设备安装位置与站点位置同源；在树站点由同步轮持续修正为设备树层级值），
     * 按通道 devicecode（code）精确匹配，仅 wlcvig 为空时写入，不覆盖人工编辑值；
     * 设备表无 wlcvig 列自动跳过（动态列适配）。
     *
     * @param code 通道 devicecode（唯一匹配键，为空跳过）
     * @return 实际回填的行数
     */
    private int backfillLocation(String code, String location) {
        if (code == null || code.trim().isEmpty() || location == null || location.trim().isEmpty()) {
            return 0;
        }
        if (!deviceColumns.isEmpty() && !deviceColumns.contains("wlcvig")) {
            return 0;
        }
        try {
            String sql = "UPDATE " + DEVICE_TABLE +
                    " SET wlcvig = ?, updated_at = ?, updated_by = 'SYSTEM' " +
                    " WHERE code = ? AND type = '" + DEVICE_TYPE_VIDEO +
                    "' AND (wlcvig IS NULL OR wlcvig = '')";
            return jdbcTemplate.update(sql, location.trim(),
                    new Timestamp(System.currentTimeMillis()), code.trim());
        } catch (Exception e) {
            log.warn("[视频设备] 安装位置回填失败, code={}: {}", code, e.getMessage());
            return 0;
        }
    }
}
