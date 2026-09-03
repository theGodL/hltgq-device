package com.qgyun.hltgq.hltgqdevice.videoalert;

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
 * 视频告警工单服务：告警新增时自动生成工单（t_auto_hltgq_water_work_order）。
 * <p>规则移植自 hltgq-mq 的 WorkOrderService（同一张工单表、同字段语义）：
 * <ul>
 *   <li>告警新增成功时同步生成工单，status=#1# 待处理，自动闭环时置 #3# 已关闭；</li>
 *   <li>同一 site+device+title 的未关闭工单（status 非 #3#/#4#）不重复生成，与告警去重同构；</li>
 *   <li>alert 字段存告警ID，形成工单↔告警精确关联；</li>
 *   <li>org 存部门 ID：库上站点 → 库上防汛办（org 表 code=00000003），
 *       其余站点（含查不到站码）→ 库下防汛办（code=00000004）；
 *       视频站点 iofhpi 站码多为空 → 默认归库下防汛办；</li>
 *   <li>user/time/result/file 留空（处理人员与完成时限待平台指派）。</li>
 * </ul>
 * <p>与 hltgq-mq 的差异（人工维护保护）：自动闭环仅关闭 status=#1# 待处理的工单——
 * 人工已开始处理（#2#）、已关闭（#3#）、已取消（#4#）的工单均不被恢复联动扭转。
 */
@Slf4j
@Service
public class WorkOrderService {

    /** 人大金仓 schema（带双引号，因为含连字符），与 hltgq-mq 一致 */
    private static final String SCHEMA = "\"qixiao-apaas\".";

    private static final String WORK_ORDER_TABLE = SCHEMA + "t_auto_hltgq_water_work_order";
    private static final String ORG_TABLE = SCHEMA + "t_apaas_uc_org";
    private static final String SITE_TABLE = SCHEMA + "t_auto_hltgq_5nw74_vnqqef";

    /** 部门 code 常量：库上防汛办 / 库下防汛办（org 表按 code 解析 ID，启动时加载） */
    private static final String ORG_UP_CODE = "00000003";
    private static final String ORG_DOWN_CODE = "00000004";

    /** 工单状态字典：#1#待处理 #2#处理中 #3#已关闭 #4#已取消；自动生成默认 #1#，自动闭环置 #3# */
    private static final String STATUS_PENDING = "#1#";
    private static final String STATUS_CLOSED = "#3#";
    private static final String STATUS_CANCELED = "#4#";

    /** 工单编号时间格式（DateTimeFormatter 线程安全） */
    private static final DateTimeFormatter CODE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Value("${app.corp-code:hltgq}")
    private String corpCode;

    /** 库上站点清单（iofhpi 站码逗号分隔，大写归一；其余站点均按库下处理，与 hltgq-mq 同口径） */
    @Value("${work-order.up-stcd:}")
    private String upStcdConfig;

    /** 库上站点 stcd 集合（大写归一） */
    private Set<String> upStcdSet = Collections.emptySet();

    /** 工单表有效列名缓存（动态列适配，列不存在自动跳过，与 hltgq-mq 一致） */
    private Set<String> workOrderColumns = Collections.emptySet();

    /** 部门 ID 解析结果：code → org 表 id（启动时加载一次，部门数据稳定） */
    private volatile String upOrgId;
    private volatile String downOrgId;

    /** siteId → iofhpi(stcd) 缓存（站点站码基本不变，永久缓存，查询失败下次重查） */
    private final ConcurrentMap<String, String> siteStcdCache = new ConcurrentHashMap<>();

    /** 工单编号序号：按秒重置、同秒递增（code=GD+yyyyMMddHHmmss+3位序号） */
    private final AtomicInteger codeSeq = new AtomicInteger();
    private volatile long codeLastSecond = 0;

    @PostConstruct
    public void init() {
        // 1. 工单表列名元数据（列不存在时自动跳过，动态列适配）
        try {
            String sql = "SELECT column_name FROM information_schema.columns " +
                    "WHERE table_schema = 'qixiao-apaas' AND table_name = 't_auto_hltgq_water_work_order'";
            List<String> cols = jdbcTemplate.queryForList(sql, String.class);
            workOrderColumns = new HashSet<>();
            for (String col : cols) {
                workOrderColumns.add(col.toLowerCase());
            }
            log.info("[视频告警] 已加载工单表列名元数据: {} 列", workOrderColumns.size());
        } catch (Exception e) {
            log.error("[视频告警] 加载工单表列名元数据失败", e);
        }
        // 2. 库上站点清单
        Set<String> set = new HashSet<>();
        if (upStcdConfig != null && !upStcdConfig.trim().isEmpty()) {
            for (String s : upStcdConfig.split(",")) {
                if (s.trim().isEmpty()) {
                    continue;
                }
                set.add(s.trim().toUpperCase());
            }
        }
        upStcdSet = set;
        log.info("[视频告警] 工单库上站点清单 {} 个: {}", upStcdSet.size(), upStcdSet);
        // 3. 部门 ID 解析
        loadOrgIds();
    }

    /** 按 code 解析库上/库下防汛办的部门 ID（org 固定存 ID，平台架构约定，与 hltgq-mq 一致） */
    private void loadOrgIds() {
        try {
            String sql = "SELECT id, code FROM " + ORG_TABLE +
                    " WHERE corp_code = ? AND code IN (?, ?)";
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql,
                    corpCode, ORG_UP_CODE, ORG_DOWN_CODE);
            for (Map<String, Object> row : rows) {
                String code = String.valueOf(row.get("code")).trim();
                String id = String.valueOf(row.get("id"));
                if (ORG_UP_CODE.equals(code)) {
                    upOrgId = id;
                }
                if (ORG_DOWN_CODE.equals(code)) {
                    downOrgId = id;
                }
            }
            if (upOrgId == null) {
                log.error("[视频告警] !!!!! 未解析到库上防汛办部门ID(code=00000003)，库上站点工单org将留空 !!!!!");
            }
            if (downOrgId == null) {
                log.error("[视频告警] !!!!! 未解析到库下防汛办部门ID(code=00000004)，库下站点工单org将留空 !!!!!");
            }
            log.info("[视频告警] 工单负责部门ID: 库上防汛办={}, 库下防汛办={}", upOrgId, downOrgId);
        } catch (Exception e) {
            log.error("[视频告警] 解析防汛办部门ID失败", e);
        }
    }

    // ======================== 工单生成 ========================

    /**
     * 告警新增成功后同步生成工单（告警去重已通过，此处再做工单侧去重双保险）。
     * alert 字段存告警ID精确关联；title 由告警 content 派生（去结尾"！"），
     * content 与告警 content 一致，关闭时按 content 匹配（与告警关闭条件同构）。
     *
     * @param alertId  告警ID（精确关联，动态列适配：工单表无 alert 列时自动跳过）
     * @param siteId   所属站点
     * @param deviceId 关联设备（设备表视频设备ID，type=#5#）
     * @param title    工单标题
     * @param content  内容描述（与告警 content 一致）
     */
    public void createIfAbsent(String alertId, String siteId, String deviceId, String title, String content) {
        if (siteId == null || title == null) {
            return;
        }
        if (existsUnclosed(siteId, deviceId, title)) {
            return;
        }
        String orgId = resolveOrg(siteId);
        if (orgId == null) {
            log.warn("[视频告警] 工单负责部门未解析, org留空: site={}, title={}", siteId, title);
        }
        insertWorkOrder(alertId, siteId, deviceId, title, content, orgId);
    }

    /** 同一 site+device+title 的未关闭工单（非 #3#已关闭/#4#已取消）是否存在 */
    private boolean existsUnclosed(String siteId, String deviceId, String title) {
        try {
            String sql = "SELECT COUNT(*) FROM " + WORK_ORDER_TABLE +
                    " WHERE site = ? AND device IS NOT DISTINCT FROM ? AND title = ? " +
                    " AND status IS DISTINCT FROM ? AND status IS DISTINCT FROM ?";
            Integer count = jdbcTemplate.queryForObject(sql, Integer.class,
                    siteId, deviceId, title, STATUS_CLOSED, STATUS_CANCELED);
            return count != null && count > 0;
        } catch (Exception e) {
            log.debug("[视频告警] 工单去重检查失败, 放行: {}", e.getMessage());
            return false;
        }
    }

    /** 站点归属部门：库上清单内 → 库上防汛办；其余（含查不到站码）→ 库下防汛办 */
    private String resolveOrg(String siteId) {
        String stcd = siteStcdCache.computeIfAbsent(siteId, id -> {
            try {
                String sql = "SELECT iofhpi FROM " + SITE_TABLE + " WHERE id = ?";
                List<String> rows = jdbcTemplate.queryForList(sql, String.class, id);
                return rows.isEmpty() || rows.get(0) == null ? "" : rows.get(0).trim();
            } catch (Exception e) {
                log.debug("[视频告警] 查询站点站码失败, site={}: {}", id, e.getMessage());
                return "";
            }
        });
        if (!stcd.isEmpty() && upStcdSet.contains(stcd.toUpperCase())) {
            return upOrgId;
        }
        return downOrgId;
    }

    /** 工单入库：alert 存告警ID，status=#1# 待处理，user/time/result/file 留空 */
    private void insertWorkOrder(String alertId, String siteId, String deviceId, String title, String content, String orgId) {
        try {
            Timestamp now = new Timestamp(System.currentTimeMillis());
            Map<String, Object> fm = new LinkedHashMap<>();
            fm.put("id", IdGenerator.generate());
            fm.put("corp_code", corpCode);
            fm.put("created_at", now);
            fm.put("created_by", "SYSTEM");
            fm.put("updated_at", now);
            fm.put("updated_by", "SYSTEM");
            fm.put("code", genWorkOrderCode(now));
            fm.put("title", title);
            fm.put("content", content);
            fm.put("site", siteId);
            if (deviceId != null) {
                fm.put("device", deviceId);
            }
            if (alertId != null) {
                fm.put("alert", alertId);
            }
            if (orgId != null) {
                fm.put("org", orgId);
            }
            fm.put("status", STATUS_PENDING);

            StringBuilder cols = new StringBuilder();
            StringBuilder phs = new StringBuilder();
            List<Object> vals = new ArrayList<>();
            for (Map.Entry<String, Object> e : fm.entrySet()) {
                if (!workOrderColumns.isEmpty() && !workOrderColumns.contains(e.getKey().toLowerCase())) {
                    continue; // 列不存在则跳过（动态列适配）
                }
                if (cols.length() > 0) {
                    cols.append(", ");
                    phs.append(", ");
                }
                // org 可能为平台方言保留字，加双引号保险（其余列名与库内小写列名一致，与 hltgq-mq 一致）
                String col = "org".equalsIgnoreCase(e.getKey()) ? "\"org\"" : e.getKey();
                cols.append(col);
                phs.append("?");
                vals.add(e.getValue());
            }
            String sql = String.format("INSERT INTO %s (%s) VALUES (%s)", WORK_ORDER_TABLE, cols, phs);
            jdbcTemplate.update(sql, vals.toArray());
            log.info("[视频告警] 告警触发自动生成工单: code={}, site={}, device={}, title={}, org={}",
                    fm.get("code"), siteId, deviceId, title, orgId);
        } catch (Exception e) {
            log.error("[视频告警] 工单入库失败, site={}, title={}: {}", siteId, title, e.getMessage());
        }
    }

    /**
     * 工单编号：GD + yyyyMMddHHmmss + 3位序号（按秒重置、同秒递增），与 hltgq-mq 完全一致。
     * 应用重启后同秒序号可能撞车，概率极低且 code 无唯一约束时无害。
     */
    private String genWorkOrderCode(Timestamp tm) {
        long sec = tm.getTime() / 1000;
        int seq;
        synchronized (codeSeq) {
            if (sec != codeLastSecond) {
                codeLastSecond = sec;
                codeSeq.set(0);
            }
            seq = codeSeq.incrementAndGet();
        }
        return "GD" + tm.toLocalDateTime().format(CODE_TIME_FORMATTER) + String.format("%03d", seq);
    }

    // ======================== 告警恢复联动关闭 ========================

    /**
     * 故障恢复：按 content 精确匹配关闭工单（与告警 closeByContent 同条件）。
     * <p>人工维护保护：仅关闭 status=#1# 待处理的工单——人工已开始处理（#2#）、
     * 已关闭（#3#）、已取消（#4#）的工单均不自动扭转，保留人工操作结果
     * （对 hltgq-mq 的加强：人工介入后系统不再改变工单状态）。
     */
    public void closeByContent(String siteId, String deviceId, String content) {
        try {
            Timestamp now = new Timestamp(System.currentTimeMillis());
            String sql = "UPDATE " + WORK_ORDER_TABLE +
                    " SET status = ?, updated_at = ?, updated_by = 'SYSTEM' " +
                    " WHERE site = ? AND device IS NOT DISTINCT FROM ? AND content = ? AND status = ?";
            int rows = jdbcTemplate.update(sql, STATUS_CLOSED, now,
                    siteId, deviceId, content, STATUS_PENDING);
            if (rows > 0) {
                log.info("[视频告警] 故障恢复, 自动关闭工单 {} 条: site={}, content={}", rows, siteId, content);
            }
        } catch (Exception e) {
            log.debug("[视频告警] 关闭工单失败: {}", e.getMessage());
        }
    }
}
