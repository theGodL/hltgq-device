package com.qgyun.hltgq.hltgqdevice.videoalert;

import com.qgyun.hltgq.hltgqdevice.service.DeviceTableService;
import com.qgyun.hltgq.hltgqdevice.service.StationStatusSyncService;
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
import java.util.Collections;
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
 *   <li>同一站点同一内容 24h 内只入库一次（对齐 hltgq-mq 一日去重；恢复关警后同日再触发同样抑制）；</li>
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

    /** 视频设备表（视频资产权威源：站点解析改走设备表 code → site） */
    private static final String DEVICE_TABLE = SCHEMA + "t_auto_hltgq_water_device";

    /** 巡检留痕表（恢复对账用：最近 N 轮结果全 ok 视为已恢复） */
    private static final String PATROL_TABLE = SCHEMA + "t_auto_hltgq_water_video_patrol";

    /** 恢复对账巡检回看窗口下限（分钟）：覆盖 recover-threshold 轮 + 轮次延迟/跳过容错 */
    private static final int RECONCILE_MIN_LOOKBACK_MINUTES = 120;

    /** 处理状态：#1#未确认(新增默认) #4#已关闭(恢复) */
    private static final String STATUS_UNCONFIRMED = "#1#";
    private static final String STATUS_CLOSED = "#4#";

    /** 告警类型：#1#阈值超限 #2#异常告警（图像质量/链路故障） #3#智能分析（IVSS 智能事件） */
    private static final String ALERT_TYPE_ABNORMAL = "#2#";
    private static final String ALERT_TYPE_INTELLIGENT = "#3#";

    /** 工单类型（qjulvf）：图像质量/链路故障 → 设备故障抢修工单 */
    private static final String WORK_ORDER_TYPE_FAULT_REPAIR = "#hxqm#";

    /** 工单类型（qjulvf）：IVSS 智能事件 → 安全隐患整改工单 */
    private static final String WORK_ORDER_TYPE_SAFETY_RECTIFY = "#pfqj#";

    /** 告警编号时间格式（DateTimeFormatter 线程安全） */
    private static final DateTimeFormatter CODE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Resource
    private WorkOrderService workOrderService;

    @Resource
    private DeviceTableService deviceTableService;

    @Resource
    private StationStatusSyncService stationStatusSyncService;

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

    /** 同站同内容去重窗口（小时，0=关闭；默认 24h，对齐 hltgq-mq 一日去重） */
    @Value("${video-alert.dedup-window-hours:24}")
    private long dedupWindowHours;

    /** 告警入库分段锁数（按 site+content 哈希分段，巡检线程与 IVSS 事件线程并发时互斥） */
    private static final int ALERT_LOCK_STRIPES = 64;

    /** 告警入库分段锁：保证「24h 查重 → 未关闭查重 → 插入 → 建单」原子，防止并发重复入库 */
    private final Object[] alertLocks = createAlertLocks();

    private static Object[] createAlertLocks() {
        Object[] locks = new Object[ALERT_LOCK_STRIPES];
        for (int i = 0; i < locks.length; i++) {
            locks[i] = new Object();
        }
        return locks;
    }

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
        log.info("[视频告警] 同站同内容去重窗口: {} 小时（0=关闭）", dedupWindowHours);
    }

    /** 故障级别：配置覆盖优先，未配置回退枚举默认值 */
    private String levelOf(VideoFaultType fault) {
        return levelOverrides.getOrDefault(fault, fault.getDefaultLevel());
    }

    // ======================== 告警新增 / 恢复关闭 ========================

    /**
     * 新增故障告警：站点名 + 故障名拼装 content（"{站点}-视频 {故障}！"，对齐 hltgq-mq 风格），
     * device 存设备表视频设备 ID（type=#5#），
     * 同一未关闭告警（site+device+content）不重复新增；同站同内容 24h 内只入库一次，
     * 新增成功自动联动生成工单。
     * <p>通道尚未入库（ICC 新增通道/历史缺口）时，先按 ICC 设备树即时补建再重试解析，
     * 避免"巡检发现了故障却因设备表没有台账而丢告警"（2026-10-20 R7 修复）。
     *
     * @param devicecode 通道设备编码（设备表 code 匹配）
     * @param fault      故障类型
     * @return true-新增成功，false-站点/设备缺失、已有未关闭同内容告警、24h 内已有同站同内容告警、入库失败
     */
    public boolean reportFault(String devicecode, VideoFaultType fault) {
        SiteInfo site = resolveSite(devicecode);
        if (site == null && stationStatusSyncService.ensureDeviceRegistered(devicecode)) {
            // 补建后设备已挂站点：再解析一次（siteCache 只缓存成功结果，不会命中空值）
            site = resolveSite(devicecode);
        }
        if (site == null) {
            log.warn("[视频告警] 通道 {} 未匹配到站点且即时补建失败，跳过告警: {}", devicecode, fault.getLabel());
            return false;
        }
        String deviceId = resolveOrCreateDevice(devicecode, site);
        if (deviceId == null) {
            log.warn("[视频告警] 通道 {} 未匹配到视频设备，跳过告警: {}", devicecode, fault.getLabel());
            return false;
        }
        String content = site.name + "-视频 " + fault.getLabel() + "！";
        // 分段锁：同一站点+内容并发进入时，只允许一个线程完成「查重→插入→建单」
        synchronized (lockFor(site.id, content)) {
            // device=设备表视频设备ID（与 hltgq-mq 三字段去重同构）
            if (existsUnclosed(site.id, deviceId, content)) {
                return false;
            }
            // 同站同内容 24h 只入库一次（对齐 hltgq-mq 一日去重）：恢复关警后同日再触发同样抑制，
            // 不新增告警/工单（平台播报读告警表，不插入即不播报）
            if (existsRecentSameContent(site.id, content)) {
                log.info("[视频告警] 24h 内已有同站同内容告警，抑制新增: site={}({}), content={}",
                        site.id, site.name, content);
                return false;
            }
            // ★ 工单口径（2026-10-29 业务定级）：只有 A 层流故障（视频看不了需人处理）建单；
            //   画质类画面仍能看，不生成工单（无人处理，挂单只积累噪音），级别亦为 #1# 一般
            String workOrderType = fault.isNeedWorkOrder() ? WORK_ORDER_TYPE_FAULT_REPAIR : null;
            insertAlert(site.id, deviceId, content, levelOf(fault), workOrderType, ALERT_TYPE_ABNORMAL);
        }
        log.warn("[视频告警] 新增告警: site={}({}), device={}, fault={}, content={}",
                site.id, site.name, deviceId, fault, content);
        return true;
    }

    /**
     * 故障恢复：按设备 + 故障后缀（"%-视频 {故障}！"）关闭未关闭告警（status → #4#），
     * 不再依赖站点名前缀——设备换站/站点改名后告警 content 仍是旧站点名，按 device 匹配才能关掉。
     * 并按实际关闭的告警 id 联动关闭对应工单（status → #3#，仅待处理）。
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
        String suffix = "-视频 " + fault.getLabel() + "！";
        List<String> alertIds = findUnclosedAlertIds(deviceId, "%" + suffix);
        if (alertIds.isEmpty()) {
            return 0;
        }
        // 先关工单再关告警：任一步失败时告警仍为未关闭，下轮对账可重试收敛
        workOrderService.closeByAlertIds(alertIds);
        int rows = closeByIds(alertIds);
        if (rows > 0) {
            log.info("[视频告警] 恢复关警 {} 条: device={}({}), fault={}",
                    rows, deviceId, devicecode, fault);
        }
        return rows;
    }

    // ======================== 未投运通道判定 ========================

    /**
     * 从给定通道中筛出"从未取流成功过"的通道（巡检留痕无 ok 记录）：
     * 业务口径 2026-10-30——设备本身没接入/未投运就是离线，谈不上"视频看不了"的告警，
     * 信号丢失按离线状态处理（不报严重不建单）；通道首次取流成功后自动进入正常判定。
     * 查询失败 fail-open 返回空集（照常告警，宁可多报不漏报）。
     *
     * @param devicecodes 候选通道设备编码（本轮信号丢失候选）
     * @return 从未成功过的通道集合（可能为空）
     */
    public Set<String> findNeverOkChannels(List<String> devicecodes) {
        Set<String> neverOk = new HashSet<>();
        if (devicecodes == null || devicecodes.isEmpty()) {
            return neverOk;
        }
        try {
            StringBuilder phs = new StringBuilder();
            for (int i = 0; i < devicecodes.size(); i++) {
                if (i > 0) {
                    phs.append(", ");
                }
                phs.append("?");
            }
            String sql = "SELECT DISTINCT channel_code FROM " + PATROL_TABLE +
                    " WHERE channel_code IN (" + phs + ") AND result = 'ok'";
            List<String> hasOk = jdbcTemplate.queryForList(sql, String.class, devicecodes.toArray());
            Set<String> hasOkSet = new HashSet<>(hasOk);
            for (String code : devicecodes) {
                if (!hasOkSet.contains(code)) {
                    neverOk.add(code);
                }
            }
            return neverOk;
        } catch (Exception e) {
            log.warn("[视频告警] 查询通道历史成功记录失败(按有记录处理，照常告警): {}", e.getMessage());
            return Collections.emptySet();
        }
    }

    // ======================== 智能事件告警（IVSS 事件接入复用） ========================

    /**
     * 新增智能事件告警（IVSS 事件接入复用入口）：content 由调用方拼装
     * （"{站点}-视频智能事件 {事件名}！"，与图像故障 "{站点}-视频 {故障名}！" 前缀隔离防撞），
     * device 存设备表视频设备 ID，去重（site+device+content + 24h 同站同内容）与工单联动同故障告警完全一致。
     *
     * @param siteId   站点ID（事件 nodeCode 匹配到的视频站点）
     * @param deviceId 设备表视频设备ID
     * @param content  告警内容
     * @param level    告警级别（#1#~#4#，由事件 alarmGrade 映射）
     * @return true-新增成功，false-已有未关闭同内容告警、24h 内已有同站同内容告警或入库失败
     */
    public boolean reportEventAlert(String siteId, String deviceId, String content, String level) {
        if (siteId == null || deviceId == null || content == null || content.trim().isEmpty()) {
            return false;
        }
        synchronized (lockFor(siteId, content)) {
            if (existsUnclosed(siteId, deviceId, content)) {
                return false;
            }
            if (existsRecentSameContent(siteId, content)) {
                log.info("[视频告警] 24h 内已有同站同内容智能事件告警，抑制新增: site={}, content={}",
                        siteId, content);
                return false;
            }
            insertAlert(siteId, deviceId, content, level, WORK_ORDER_TYPE_SAFETY_RECTIFY, ALERT_TYPE_INTELLIGENT);
        }
        log.warn("[视频告警] 新增智能事件告警: site={}, device={}, content={}", siteId, deviceId, content);
        return true;
    }

    /**
     * 关闭智能事件告警（事件恢复复用入口）：按 site+device+content 精确匹配，
     * 关闭未关闭告警（status → #4#）并联动关闭对应工单（人工已关闭的行不重复更新）。
     *
     * @return 实际关闭的告警条数
     */
    public int closeEventAlert(String siteId, String deviceId, String content) {
        if (siteId == null || deviceId == null || content == null || content.trim().isEmpty()) {
            return 0;
        }
        int rows = closeByContent(siteId, deviceId, content);
        if (rows > 0) {
            log.info("[视频告警] 智能事件恢复关警 {} 条: site={}, device={}, content={}",
                    rows, siteId, deviceId, content);
        }
        workOrderService.closeByContent(siteId, deviceId, content);
        return rows;
    }

    // ======================== 数据库操作 ========================

    /**
     * 新增告警行（动态列适配，status 默认 #1#，type=告警类型，time=当前时间）。
     * 插入成功后自动联动生成工单（与 hltgq-mq insertAlert 联动模式一致）。
     *
     * @param deviceId      设备表视频设备ID（device 字段）
     * @param workOrderType 联动工单类型（qjulvf）：故障=#hxqm#设备故障抢修，智能事件=#pfqj#安全隐患整改；
     *                      null=不生成工单（画质类告警：视频仍能看，无人处理）
     * @param alertType     告警类型：故障=#2#异常告警，智能事件=#3#智能分析
     */
    private void insertAlert(String siteId, String deviceId, String content, String level, String workOrderType,
                             String alertType) {
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
            fm.put("type", alertType);
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
            // 告警新增成功 → 自动生成工单（alert 存告警ID精确关联；device 存设备表视频设备ID；qjulvf 存工单类型）
            // workOrderType=null 表示该故障类型不建单（画质类：视频仍能看，无人处理）
            if (workOrderType != null) {
                workOrderService.createIfAbsent(alertId, siteId, deviceId, deriveTitle(content), content, workOrderType);
            }
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

    /**
     * 同站点-内容且 24h 内已入库的告警是否存在（status 不限，对齐 hltgq-mq 一日去重）。
     * <p>覆盖恢复关警后再次触发、服务重启防抖状态清零、抑制窗口结束补发等场景：
     * 同一天内同站同内容只保留一条告警，避免告警/工单/平台播报循环放大；查询失败 fail-open 放行。
     */
    private boolean existsRecentSameContent(String siteId, String content) {
        if (dedupWindowHours <= 0) {
            return false;
        }
        try {
            Timestamp cutoff = new Timestamp(System.currentTimeMillis() - dedupWindowHours * 3600_000L);
            String sql = "SELECT 1 FROM " + ALERT_TABLE +
                    " WHERE site = ? AND content = ? AND created_at >= ? LIMIT 1";
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, siteId, content, cutoff);
            return rows != null && !rows.isEmpty();
        } catch (Exception e) {
            log.warn("[视频告警] 24h 同内容去重检查失败, 放行: {}", e.getMessage());
            return false;
        }
    }

    /** 按站点+内容取分段锁（同一键的「查重→插入→建单」串行，不同键互不阻塞） */
    private Object lockFor(String siteId, String content) {
        return alertLocks[Math.floorMod((siteId + '\0' + content).hashCode(), alertLocks.length)];
    }

    /** 查设备下未关闭的故障告警 id（按故障后缀匹配，兼容换站/改名后的旧站点名前缀） */
    private List<String> findUnclosedAlertIds(String deviceId, String contentLike) {
        try {
            String sql = "SELECT id FROM " + ALERT_TABLE +
                    " WHERE device = ? AND content LIKE ? AND status IS DISTINCT FROM ?";
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql,
                    deviceId, contentLike, STATUS_CLOSED);
            List<String> ids = new ArrayList<>();
            if (rows != null) {
                for (Map<String, Object> row : rows) {
                    Object id = row.get("id");
                    if (id != null && !String.valueOf(id).trim().isEmpty()) {
                        ids.add(String.valueOf(id));
                    }
                }
            }
            return ids;
        } catch (Exception e) {
            log.warn("[视频告警] 查询未关闭告警失败, device={}: {}", deviceId, e.getMessage());
            return Collections.emptyList();
        }
    }

    /** 按告警 id 批量关闭（恢复/对账共用）：仅更新未关闭行，不覆盖人工已关闭记录 */
    private int closeByIds(List<String> alertIds) {
        if (alertIds == null || alertIds.isEmpty()) {
            return 0;
        }
        try {
            StringBuilder phs = new StringBuilder();
            List<Object> args = new ArrayList<>();
            args.add(STATUS_CLOSED);
            args.add(new Timestamp(System.currentTimeMillis()));
            for (String id : alertIds) {
                if (phs.length() > 0) {
                    phs.append(", ");
                }
                phs.append("?");
                args.add(id);
            }
            args.add(STATUS_CLOSED);
            String sql = "UPDATE " + ALERT_TABLE +
                    " SET status = ?, updated_at = ?, updated_by = 'SYSTEM'" +
                    " WHERE id IN (" + phs + ") AND status IS DISTINCT FROM ?";
            return jdbcTemplate.update(sql, args.toArray());
        } catch (Exception e) {
            log.debug("[视频告警] 按ID关闭告警失败: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * 恢复对账（每轮巡检后调用）：不依赖防抖内存状态，按巡检表兜底关闭已恢复告警。
     * <p>服务重启后 {@link AlertDebouncer} 计数清零，历史告警不会产生 RECOVERED 事件；
     * 这里直接查未关闭视频故障告警对应设备最近 okRounds 轮巡检是否全 ok，
     * 全 ok 即视为恢复：关闭告警并按告警 id 关闭对应工单（仅待处理）。
     * 要求最新一轮落在回看时间窗内，长期无巡检数据的通道不参与对账，避免陈旧数据误关。
     *
     * @param okRounds 连续正常轮数阈值（video-alert.recover-threshold）
     * @return 实际关闭的告警条数
     */
    public int reconcileRecovered(int okRounds) {
        if (okRounds <= 0) {
            return 0;
        }
        long lookbackMinutes = Math.max(RECONCILE_MIN_LOOKBACK_MINUTES, okRounds * 60L);
        return reconcileRecovered(okRounds,
                new Timestamp(System.currentTimeMillis() - lookbackMinutes * 60_000L));
    }

    /**
     * 恢复对账（指定巡检轮次起点）：每轮巡检收尾调用。
     * <p>取该通道最近 okRounds 行巡检结果（本轮留痕 round_time 即轮次起点），要求全部为 ok、
     * 且最新一轮不早于本轮起点——本轮留痕未写入（数据库异常/通道被取消）时最新一轮是历史数据，
     * 条件不成立，不会用陈旧巡检结果误关。
     *
     * @param okRounds   连续正常轮数阈值（video-alert.recover-threshold）
     * @param roundStart 本轮巡检开始时间（巡检表 round_time 同源）
     * @return 实际关闭的告警条数
     */
    public int reconcileRecovered(int okRounds, Timestamp roundStart) {
        if (okRounds <= 0 || roundStart == null) {
            return 0;
        }
        List<String> alertIds = new ArrayList<>();
        try {
            // 最近 okRounds 轮全 ok，且最新一轮（max round_time）不早于本轮起点
            String latestRounds = "(SELECT p.result, p.round_time FROM " + PATROL_TABLE + " p" +
                    " WHERE p.channel_code = d.code" +
                    " ORDER BY p.round_time DESC LIMIT " + okRounds + ") x";
            String sql = "SELECT a.id AS alert_id FROM " + ALERT_TABLE + " a" +
                    " JOIN " + DEVICE_TABLE + " d ON d.id = a.device" +
                    " WHERE a.status = ? AND a.content LIKE '%-视频 %'" +
                    " AND d.code IS NOT NULL AND d.code <> ''" +
                    " AND (SELECT count(*) FROM " + latestRounds + ") = " + okRounds +
                    " AND (SELECT count(*) FILTER (WHERE x.result = 'ok') FROM " + latestRounds + ") = "
                    + okRounds +
                    " AND (SELECT max(x.round_time) FROM " + latestRounds + ") >= ?";
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql,
                    STATUS_UNCONFIRMED, roundStart);
            if (rows != null) {
                for (Map<String, Object> row : rows) {
                    Object id = row.get("alert_id");
                    if (id != null && !String.valueOf(id).trim().isEmpty()) {
                        alertIds.add(String.valueOf(id));
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[视频告警] 恢复对账查询失败: {}", e.getMessage());
            return 0;
        }
        if (alertIds.isEmpty()) {
            return 0;
        }
        // 先关工单再关告警：任一步失败时告警仍为未关闭，下轮对账可重试收敛
        workOrderService.closeByAlertIds(alertIds);
        int rows = closeByIds(alertIds);
        if (rows > 0) {
            log.info("[视频告警] 恢复对账关闭告警 {} 条（{}轮巡检全正常）", rows, okRounds);
        }
        return rows;
    }

    /** 精确 content 关闭（智能事件恢复用）：仅更新未关闭（status≠#4#）的告警，不覆盖人工已关闭记录 */
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
     * devicecode → 站点信息（id + 名称）：设备表解析（视频资产权威源）——
     * 设备 code → site → 站点行（type 含 #5# 限定，防同 code 其他类型设备串行）。
     * 本地缓存 TTL 过期刷新（站点名可能被人工编辑，过期后重查；查询失败兜底返回旧值可重查）。
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
            String sql = "SELECT s.id, s.zzkaec FROM " + DEVICE_TABLE + " d" +
                    " JOIN " + STATION_TABLE + " s ON s.id = d.site" +
                    " WHERE d.code = ? AND d.type LIKE '%" + DeviceTableService.DEVICE_TYPE_VIDEO + "%'";
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, code);
            if (rows.isEmpty()) {
                return null;
            }
            Map<String, Object> row = rows.get(0);
            Object id = row.get("id");
            if (id == null || String.valueOf(id).trim().isEmpty()) {
                return null;
            }
            Object name = row.get("zzkaec");
            SiteInfo info = new SiteInfo(String.valueOf(id).trim(),
                    name == null ? code : String.valueOf(name).trim());
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
        final long cachedAt = System.currentTimeMillis();

        SiteInfo(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    /**
     * devicecode → 设备表视频设备ID（type=#5#）：设备名优先取 ICC 通道原名（与站点同步同一口径，
     * 设备已挂在站点下，不再拼站点名前缀/后缀），取不到时退回站点名；
     * 查不到自动兜底创建（设备状态/位置留空，由站点同步轮按设备树维护——架构改造后
     * 站点 mivbcz 不再维护，安装位置以设备表 wlcvig 为准）；设备表不可达返回 null。
     */
    private String resolveOrCreateDevice(String devicecode, SiteInfo site) {
        try {
            // 正常路径：设备已存在（resolveSite 即由设备表 JOIN 得到站点），直接取 ID，不触发 ICC 遍历
            DeviceTableService.VideoDeviceRef existing = deviceTableService.findVideoDeviceByCode(devicecode);
            if (existing != null) {
                return existing.getId();
            }
            String deviceName = site.name;
            StationStatusSyncService.VideoChannel ch = stationStatusSyncService.findChannel(devicecode);
            if (ch != null && ch.getName() != null && !ch.getName().trim().isEmpty()) {
                deviceName = ch.getName().trim();
            }
            return deviceTableService.lookupOrCreateDevice(deviceName, site.id,
                    DeviceTableService.DEVICE_TYPE_VIDEO, devicecode, null, null);
        } catch (Exception e) {
            log.warn("[视频告警] 设备解析失败, devicecode={}: {}", devicecode, e.getMessage());
            return null;
        }
    }
}
