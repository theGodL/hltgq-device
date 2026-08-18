package com.qgyun.hltgq.hltgqdevice.service;

import com.qgyun.hltgq.hltgqdevice.util.IdGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 视频站点状态同步定时任务
 * <p>
 * 每小时整点执行（如1:00、2:00）：递归遍历大华ICC设备树，收集全部视频通道的
 * 在线/离线状态，按 devicecode 匹配站点信息表 {@code t_auto_hltgq_5nw74_vnqqef}：
 * <ul>
 *   <li>已存在站点：状态（zebpsu，{@code #1#}在线/{@code #2#}离线）变化时更新；</li>
 *   <li>新视频站点：自动新增，必填 id/devicecode/zebpsu/zzkaec/mivbcz/epjutj，
 *       类型默认 {@code #5#} 视频站点，位置取所属组织名称，坐标暂无来源留空；</li>
 *   <li>ICC设备树中已消失的视频站点：标注离线（{@code #2#}）。
 *       仅在整轮遍历无任何失败节点时执行，防止某子树查询失败被误判为通道消失。</li>
 * </ul>
 * ID生成、系统字段（corp_code/created_at等）与hltgq-mq站点写入规则一致。
 *
 * @author hltgq-device
 */
@Slf4j
@Service
public class StationStatusSyncService {

    /** 站点信息表全限定名（人大金仓schema带双引号，因含连字符，与hltgq-mq一致） */
    private static final String STATION_TABLE = "\"qixiao-apaas\".t_auto_hltgq_5nw74_vnqqef";

    /** 视频站点类型（epjutj） */
    private static final String VIDEO_TYPE = "#5#";

    /** 站点在线/离线状态值（zebpsu） */
    private static final String STATUS_ONLINE = "#1#";
    private static final String STATUS_OFFLINE = "#2#";

    /** 前端设备树过滤的组织（与monitor.html一致，其子树不纳入站点同步） */
    private static final String[] FILTERED_ORG_NAMES = {"高低点视频", "分析服务器"};

    /** 设备树递归最大深度，防止异常数据导致无限递归 */
    private static final int MAX_TREE_DEPTH = 10;

    /** 兜底站点位置：归属组织名称为空时使用 */
    private static final String DEFAULT_LOCATION = "花凉亭灌区";

    @Resource
    private DahuaDeviceService deviceService;

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Value("${app.corp-code:hltgq}")
    private String corpCode;

    /**
     * 每小时整点同步一次视频站点状态（服务器时间，如1:00、2:00）
     */
    @Scheduled(cron = "0 0 * * * ?")
    public void syncVideoStationStatus() {
        long start = System.currentTimeMillis();
        try {
            // 1. 递归遍历ICC设备树，收集全部视频通道
            // traversalFailed：任一节点查询失败即置true，本轮跳过"消失站点标离线"，防止误伤
            List<VideoChannel> channels = new ArrayList<>();
            AtomicBoolean traversalFailed = new AtomicBoolean(false);
            collectChannels("001", null, channels, 0, traversalFailed);
            log.info("[站点同步] ICC设备树遍历完成，共{}个视频通道，遍历失败={}",
                    channels.size(), traversalFailed.get());

            // 2. 加载站点表现有记录：devicecode → zebpsu（失败返回null，代表数据库不可达）
            Map<String, String> existingStatus = loadExistingStations();
            if (existingStatus == null) {
                log.warn("[站点同步] 站点表加载失败（数据库不可达？），本轮跳过状态比对与离线标注");
                return;
            }

            // 3. 逐通道比对：状态变化则更新，新通道则新增
            // processedCodes：同一轮遍历中重复出现的通道（多组织共享设备）只处理一次，防止重复插入
            int inserted = 0, updated = 0, unchanged = 0;
            Set<String> processedCodes = new HashSet<>();
            for (VideoChannel ch : channels) {
                if (!processedCodes.add(ch.devicecode)) continue;
                String target = ch.online ? STATUS_ONLINE : STATUS_OFFLINE;
                // 用containsKey区分"站点不存在(新增)"与"存在但zebpsu为NULL(更新)"，
                // 否则NULL状态站点会被误判为新增导致重复插入
                boolean exists = existingStatus.containsKey(ch.devicecode);
                if (!exists) {
                    if (insertStation(ch, target)) inserted++;
                } else if (!target.equals(existingStatus.get(ch.devicecode))) {
                    if (updateStationStatus(ch.devicecode, target)) updated++;
                } else {
                    unchanged++;
                }
            }

            // 4. ICC设备树中已消失的视频站点 → 标注离线。
            // 仅在整轮遍历无失败时执行：子树查询失败会导致其通道"看起来消失"，
            // 此时标离线会误伤，故失败即跳过（下轮重试）。
            if (!traversalFailed.get()) {
                int offlineMarked = markMissingStationsOffline(processedCodes);
                log.info("[站点同步] 完成，耗时{}ms：新增{}个，状态更新{}个，无变化{}个，消失标离线{}个",
                        System.currentTimeMillis() - start, inserted, updated, unchanged, offlineMarked);
            } else {
                log.warn("[站点同步] 完成但遍历存在失败节点（新增{}个，状态更新{}个，无变化{}个），本轮跳过消失站点离线标注",
                        inserted, updated, unchanged);
            }
        } catch (Exception e) {
            log.error("[站点同步] 视频站点状态同步失败：", e);
        }
    }

    /**
     * 递归遍历ICC设备树，收集视频通道（nodeType=ch）及其归属组织名称
     *
     * @param parentId 父节点ID（组织/设备）
     * @param orgName  最近一级归属组织名称（通道的站点位置来源）
     * @param out      收集结果
     * @param depth    当前递归深度
     * @param failed   任一节点查询失败即置true（遍历不完整，禁止离线标注）
     */
    private void collectChannels(String parentId, String orgName, List<VideoChannel> out,
                                 int depth, AtomicBoolean failed) {
        if (depth > MAX_TREE_DEPTH) {
            // 深度超限意味着子树未遍历完整，同样视为失败，防止离线标注误伤
            log.warn("[站点同步] 设备树递归超过最大深度{}，跳过parentId={}", MAX_TREE_DEPTH, parentId);
            failed.set(true);
            return;
        }
        DahuaDeviceService.DeviceTreeResult result = deviceService.getDeviceTreeWithStatus(parentId);
        if (!result.isSuccess()) {
            failed.set(true);
            return;
        }
        for (DahuaDeviceService.DeviceTreeNode node : result.getNodes()) {
            if (node == null) continue;
            // 过滤指定组织（与前端设备树一致）
            if (isFilteredOrg(node.getName())) continue;

            // 组织节点更新归属组织名称，非组织节点沿用父级
            String currentOrg = "org".equals(node.getNodeType()) && node.getName() != null
                    ? node.getName() : orgName;

            if ("ch".equals(node.getNodeType())) {
                // 通道节点：devicecode优先取id（如1000231$1$0$10），兜底取deviceCode字段
                String code = firstNonEmpty(node.getId(), node.getDeviceCode());
                if (code == null || code.trim().isEmpty()) {
                    log.warn("[站点同步] 通道{}缺少设备编码，跳过", node.getName());
                    continue;
                }
                code = code.trim();
                // 站点名称兜底取设备编码，避免zzkaec入库为NULL违反非空约束
                String name = firstNonEmpty(node.getName(), code);
                boolean online = Integer.valueOf(1).equals(node.getCheckStat())
                        || Integer.valueOf(1).equals(node.getIsOnline());
                out.add(new VideoChannel(code, name, currentOrg, online));
            } else if (Boolean.TRUE.equals(node.getIsParent())
                    && node.getId() != null && !node.getId().trim().isEmpty()) {
                // 有子节点的组织/设备：继续递归（id为空时跳过，防止误查根节点001）
                collectChannels(node.getId(), currentOrg, out, depth + 1, failed);
            }
        }
    }

    /**
     * 加载站点表现有记录：devicecode → zebpsu
     *
     * @return devicecode→zebpsu映射（zebpsu可能为NULL）；数据库不可达时返回null
     */
    private Map<String, String> loadExistingStations() {
        Map<String, String> map = new HashMap<>();
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT devicecode, zebpsu FROM " + STATION_TABLE + " WHERE devicecode IS NOT NULL");
            for (Map<String, Object> row : rows) {
                Object code = row.get("devicecode");
                Object status = row.get("zebpsu");
                if (code != null && !String.valueOf(code).trim().isEmpty()) {
                    map.put(String.valueOf(code).trim(), status == null ? null : String.valueOf(status));
                }
            }
        } catch (Exception e) {
            log.error("[站点同步] 加载现有站点记录失败：", e);
            return null;
        }
        return map;
    }

    /**
     * 新增视频站点（此前设备树中不存在的devicecode）
     * <p>
     * 必填：id、devicecode、zebpsu、zzkaec（站点名称）、mivbcz（站点位置，取所属组织名称）、
     * epjutj（默认#5#视频站点）；坐标bviiio_x/bviiio_y暂无来源留空；系统字段与hltgq-mq一致。
     */
    private boolean insertStation(VideoChannel ch, String status) {
        try {
            Timestamp now = new Timestamp(System.currentTimeMillis());
            String location = ch.orgName != null && !ch.orgName.trim().isEmpty()
                    ? ch.orgName.trim() : DEFAULT_LOCATION;
            String sql = "INSERT INTO " + STATION_TABLE +
                    " (id, corp_code, created_at, created_by, updated_at, updated_by, " +
                    "  devicecode, zebpsu, zzkaec, mivbcz, epjutj) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
            jdbcTemplate.update(sql, IdGenerator.generate(), corpCode, now, "SYSTEM", now, "SYSTEM",
                    ch.devicecode, status, ch.name, location, VIDEO_TYPE);
            log.info("[站点同步] 新增视频站点: devicecode={}, 名称={}, 位置={}, 状态={}",
                    ch.devicecode, ch.name, location, status);
            return true;
        } catch (Exception e) {
            log.error("[站点同步] 新增视频站点失败: devicecode={}", ch.devicecode, e);
            return false;
        }
    }

    /**
     * 更新已有站点状态（zebpsu变化时）
     */
    private boolean updateStationStatus(String devicecode, String status) {
        try {
            String sql = "UPDATE " + STATION_TABLE +
                    " SET zebpsu = ?, updated_at = ?, updated_by = 'SYSTEM' WHERE devicecode = ?";
            int rows = jdbcTemplate.update(sql, status,
                    new Timestamp(System.currentTimeMillis()), devicecode);
            if (rows > 0) {
                log.info("[站点同步] 站点状态更新: devicecode={}, zebpsu={}", devicecode, status);
            }
            return rows > 0;
        } catch (Exception e) {
            log.error("[站点同步] 站点状态更新失败: devicecode={}", devicecode, e);
            return false;
        }
    }

    /**
     * 将ICC设备树中已消失的视频站点标注离线。
     * <p>
     * 目标行：epjutj含#5#（视频类型）且devicecode不在本轮遍历结果中、当前状态非离线的站点。
     * 仅在整轮遍历无失败节点时由调用方执行（失败会误伤缺失子树，见syncVideoStationStatus）。
     * devicecode数量较大时分批NOT IN，避免超出数据库参数上限。
     *
     * @param seenCodes 本轮遍历见到的全部devicecode（去重）
     * @return 标注离线的行数
     */
    private int markMissingStationsOffline(Set<String> seenCodes) {
        int total = 0;
        Timestamp now = new Timestamp(System.currentTimeMillis());
        try {
            // 公共条件：视频类型站点、有devicecode、当前状态非离线
            String baseWhere = " WHERE epjutj LIKE '%#5#%' AND devicecode IS NOT NULL AND devicecode <> ''" +
                    " AND zebpsu IS DISTINCT FROM ?";
            if (seenCodes.isEmpty()) {
                // ICC已无任何视频通道（遍历成功但结果为空）：全部视频站点标离线
                String sql = "UPDATE " + STATION_TABLE + " SET zebpsu = ?, updated_at = ?, updated_by = 'SYSTEM'"
                        + baseWhere;
                int rows = jdbcTemplate.update(sql, STATUS_OFFLINE, now, STATUS_OFFLINE);
                if (rows > 0) {
                    log.info("[站点同步] ICC无视频通道，全部视频站点标注离线: {} 个", rows);
                }
                return rows;
            }
            // 分批NOT IN（每批500个），超出部分分多轮UPDATE
            List<String> codeList = new ArrayList<>(seenCodes);
            for (int i = 0; i < codeList.size(); i += 500) {
                List<String> chunk = codeList.subList(i, Math.min(i + 500, codeList.size()));
                StringBuilder sql = new StringBuilder("UPDATE " + STATION_TABLE +
                        " SET zebpsu = ?, updated_at = ?, updated_by = 'SYSTEM'");
                sql.append(baseWhere);
                sql.append(" AND devicecode NOT IN (");
                for (int j = 0; j < chunk.size(); j++) {
                    sql.append(j == 0 ? "?" : ", ?");
                }
                sql.append(")");
                List<Object> params = new ArrayList<>();
                params.add(STATUS_OFFLINE);
                params.add(now);
                params.add(STATUS_OFFLINE);
                params.addAll(chunk);
                int rows = jdbcTemplate.update(sql.toString(), params.toArray());
                if (rows > 0) {
                    log.info("[站点同步] ICC中已消失的视频站点标注离线: {} 个", rows);
                }
                total += rows;
            }
        } catch (Exception e) {
            log.error("[站点同步] 消失站点离线标注失败：", e);
        }
        return total;
    }

    /**
     * 是否前端过滤的组织（过滤后其整个子树不纳入同步）
     */
    private boolean isFilteredOrg(String name) {
        if (name == null) return false;
        for (String keyword : FILTERED_ORG_NAMES) {
            if (name.contains(keyword)) return true;
        }
        return false;
    }

    /**
     * 取第一个非空字符串
     */
    private String firstNonEmpty(String... values) {
        for (String v : values) {
            if (v != null && !v.trim().isEmpty()) return v;
        }
        return null;
    }

    /**
     * ICC设备树中的视频通道信息
     */
    private static class VideoChannel {
        /** 设备编码（如1000231$1$0$10），匹配站点表devicecode */
        final String devicecode;
        /** 通道名称 → 站点名称zzkaec */
        final String name;
        /** 归属组织名称 → 站点位置mivbcz */
        final String orgName;
        /** 是否在线 → zebpsu */
        final boolean online;

        VideoChannel(String devicecode, String name, String orgName, boolean online) {
            this.devicecode = devicecode;
            this.name = name;
            this.orgName = orgName;
            this.online = online;
        }
    }
}
