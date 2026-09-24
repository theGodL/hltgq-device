package com.qgyun.hltgq.hltgqdevice.service;

import com.qgyun.hltgq.hltgqdevice.util.IdGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 视频通道 → 设备/站点归属同步定时任务（站点-设备数据架构改造后：设备表为视频资产权威源）
 * <p>
 * 每小时整点执行（如1:00、2:00）：递归遍历大华ICC设备树收集全部视频通道，以设备表
 * {@code t_auto_hltgq_water_device}（code=通道devicecode、type含#5#）为准分四态处理：
 * <ul>
 *   <li>设备不存在（新通道）：先按NVR名/组织名解析业务站——命中则站点 epjutj 追加
 *       {@code |#5#} 后建设备挂靠；未命中则查找/新建兜底站（epjutj=#5#、无devicecode，
 *       名称重名追加"-视频"后缀）并记"待挂靠"日志；</li>
 *   <li>设备已挂兜底站：每轮重试业务站匹配，命中即改挂（兜底站行保留不删，退场由人工处置）；</li>
 *   <li>设备已挂旧迁移行（epjutj=#5# 含 devicecode，待迁移脚本换指）：本轮跳过不动；</li>
 *   <li>设备已挂业务站：站点 epjutj 未含 #5# 时追加，设备状态/位置按设备树同步。</li>
 * </ul>
 * 站点表仅写入三类内容：业务站 epjutj 追加 {@code |#5#}、兜底站新建、系统字段
 * （updated_at/updated_by）——不再写 zebpsu/mivbcz/ahieto/devicecode（D1 决策：状态位置归设备表）。
 * <p>通道消失：仅联动设备表标离线（站点表旧行保留不动，退役行由迁移/对账侧处理）。
 * 仅在整轮遍历无任何失败节点时执行，防止某子树查询失败被误判为通道消失。
 * <p>ID生成、系统字段（corp_code/created_at等）与hltgq-mq站点写入规则一致。
 * <p>应用启动时立即异步同步一次（不等整点），与整点定时任务共用互斥锁，
 * 同一时刻只允许一轮执行（防启动轮与整点轮并发导致重复建站/建设备）。
 *
 * @author hltgq-device
 */
@Slf4j
@Service
public class StationStatusSyncService {

    /** 站点信息表全限定名（人大金仓schema带双引号，因含连字符，与hltgq-mq一致） */
    private static final String STATION_TABLE = "\"qixiao-apaas\".t_auto_hltgq_5nw74_vnqqef";

    /** 视频设备表全限定名（视频资产权威源：code=通道devicecode → site=归属站点） */
    private static final String DEVICE_TABLE = "\"qixiao-apaas\".t_auto_hltgq_water_device";

    /** 视频类型（站点 epjutj 追加后缀 / 设备 type，两端同码值 #5#） */
    private static final String VIDEO_TYPE = "#5#";

    /** 设备在线/离线状态值（设备表 status，{@code #1#}在线/{@code #2#}离线） */
    private static final String STATUS_ONLINE = "#1#";
    private static final String STATUS_OFFLINE = "#2#";

    /** 前端设备树过滤的组织（与monitor.html一致，其子树不纳入站点同步） */
    private static final String[] FILTERED_ORG_NAMES = {"高低点视频", "分析服务器"};

    /**
     * 视频站点上级匹配的人工别名表：设备树 NVR 名（去掉"上游/下游"后缀后）与档案行名
     * 语义同指一物但字面不同的特例（2026-09-22 与业务人工确认）：
     * "荞麦岭泄洪闸"（闸）对应档案行"荞麦岭泄洪河"（河），"二号渡槽"对应档案行"2#渡槽"。
     */
    private static final Map<String, String> PARENT_NAME_ALIASES;

    static {
        Map<String, String> aliases = new HashMap<>();
        aliases.put("荞麦岭泄洪闸", "荞麦岭泄洪河");
        aliases.put("二号渡槽", "2#渡槽");
        PARENT_NAME_ALIASES = Collections.unmodifiableMap(aliases);
    }

    /** 设备树递归最大深度，防止异常数据导致无限递归 */
    private static final int MAX_TREE_DEPTH = 10;

    /** 兜底站点位置：归属组织名称为空时使用 */
    private static final String DEFAULT_LOCATION = "花凉亭灌区";

    @Resource
    private DahuaDeviceService deviceService;

    @Resource
    private DeviceTableService deviceTableService;

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Value("${app.corp-code:hltgq}")
    private String corpCode;

    /** 同步互斥：同一时刻只允许一轮执行（启动轮与整点定时轮共用） */
    private final AtomicBoolean syncRunning = new AtomicBoolean(false);

    /**
     * 通道信息内存缓存：devicecode → VideoChannel。
     * 设备树整轮遍历成功后整体替换（volatile 写不可变 Map，读侧无锁、读到的必为完整快照），
     * 供 /channel-info 接口 O(1) 查询通道名/cameraType/在线状态（弹窗页只传 channelId 的自动补全场景）。
     */
    private volatile Map<String, VideoChannel> channelCache = Collections.emptyMap();

    /**
     * 启动时立即同步一次站点与视频设备（异步执行，不阻塞启动）：
     * 部署后无需等整点即可完成站点入库/状态对齐与设备联动（数据库/ICC 不可达时内部容错跳过）。
     * 依赖注入保证 DeviceTableService 已完成列元数据加载与历史迁移（依赖 bean 初始化先于本 bean）。
     */
    @PostConstruct
    public void init() {
        Thread startupSync = new Thread(() -> {
            try {
                syncVideoStationStatus();
            } catch (Exception e) {
                // syncVideoStationStatus 内部已捕获，此处兜底防线程静默死亡
                log.error("[站点同步] 启动同步线程异常：", e);
            }
        }, "station-startup-sync");
        startupSync.setDaemon(true);
        startupSync.start();
    }

    /**
     * 每小时整点同步一次视频站点状态（服务器时间，如1:00、2:00）；应用启动时由 init() 异步触发首轮。
     * 上一轮未结束时本轮直接跳过（互斥，防并发重复建站/建设备）。
     */
    @Scheduled(cron = "0 0 * * * ?")
    public void syncVideoStationStatus() {
        if (!syncRunning.compareAndSet(false, true)) {
            log.info("[站点同步] 上一轮同步尚未结束，跳过本轮");
            return;
        }
        long start = System.currentTimeMillis();
        try {
            // 1. 递归遍历ICC设备树，收集全部视频通道
            // traversalFailed：任一节点查询失败即置true，本轮跳过"消失站点标离线"，防止误伤
            List<VideoChannel> channels = new ArrayList<>();
            AtomicBoolean traversalFailed = new AtomicBoolean(false);
            collectChannels("001", null, null, channels, 0, traversalFailed);
            log.info("[站点同步] ICC设备树遍历完成，共{}个视频通道，遍历失败={}",
                    channels.size(), traversalFailed.get());

            // 遍历完整时整体刷新通道信息缓存（不完整保留旧快照，防部分通道"消失"）
            if (!traversalFailed.get()) {
                refreshChannelCache(channels);
            }

            // 2. 加载站点表快照：siteById（设备已挂站点判定）+ 全表名称（兜底站去重）+ 业务站名索引（匹配）
            ExistingStations existing = loadExistingStations();
            if (existing == null) {
                log.warn("[站点同步] 站点表加载失败（数据库不可达？），本轮跳过比对与离线标注");
                return;
            }

            // 3. 逐通道同步（设备表为权威源：code → 设备 → 站点，判定新通道/兜底站/迁移行/业务站四态）
            // processedCodes：同一轮遍历中重复出现的通道（多组织共享设备）只处理一次；
            // 同时作为"本轮可见通道"全集，供消失设备离线标注
            RoundStats stats = new RoundStats();
            Set<String> processedCodes = new HashSet<>();
            for (VideoChannel ch : channels) {
                if (!processedCodes.add(ch.devicecode)) continue;
                try {
                    syncChannel(ch, existing, stats);
                } catch (Exception e) {
                    log.error("[站点同步] 通道处理失败: devicecode={}", ch.devicecode, e);
                }
            }

            // 4. 消失通道 → 设备表视频设备标注离线。
            // 仅在整轮遍历无失败时执行：子树查询失败会导致其通道"看起来消失"，
            // 此时标离线会误伤，故失败即跳过（下轮重试）；站点表旧行不动（退役行处理见迁移/对账）。
            if (!traversalFailed.get()) {
                stats.offlineMarked = deviceTableService.markOfflineByMissingCodes(processedCodes);
                log.info("[站点同步] 完成，耗时{}ms：{}", System.currentTimeMillis() - start, stats.describe());
            } else {
                log.warn("[站点同步] 完成但遍历存在失败节点，本轮跳过设备离线标注：{}", stats.describe());
            }
        } catch (Exception e) {
            log.error("[站点同步] 视频站点状态同步失败：", e);
        } finally {
            syncRunning.set(false);
        }
    }

    /**
     * 单通道同步：设备表为权威源的四态判定（新通道 / 兜底站 / 迁移行 / 业务站）。
     * <ul>
     *   <li><b>设备不存在</b>（新通道）：解析业务站，命中先追加站点 #5# 类型再建设备挂靠；
     *       未命中则查找/新建兜底站着落并记"待挂靠"日志（下轮由兜底站分支持续重试匹配）；</li>
     *   <li><b>设备已挂兜底站</b>：每轮重试业务站匹配，命中即改挂（兜底站行保留不删）；</li>
     *   <li><b>设备已挂旧迁移行</b>（纯#5#含devicecode）：跳过不动，待迁移脚本换指后自然转入业务站分支；</li>
     *   <li><b>设备已挂业务站</b>：epjutj 未含 #5# 时追加，设备状态/位置持续同步。</li>
     * </ul>
     * 设备 site 悬空（指向不存在的站点行）时按"业务站优先、兜底站其次"重挂靠。
     * 全部路径均不写站点 zebpsu/mivbcz/ahieto（D1：站点状态位置归设备表）。
     */
    private void syncChannel(VideoChannel ch, ExistingStations existing, RoundStats stats) {
        String target = ch.online ? STATUS_ONLINE : STATUS_OFFLINE;
        String location = channelLocation(ch);
        DeviceTableService.VideoDeviceRef device = deviceTableService.findVideoDeviceByCode(ch.devicecode);
        if (device == null) {
            // 新通道：业务站优先（命中先补 #5# 再建设备，保持"设备所指站点含 #5#"不变量），未命中落兜底站
            String siteId = resolveBusinessSite(ch, existing);
            String stationName;
            if (siteId != null) {
                SiteRow hit = existing.siteById.get(siteId);
                stationName = hit != null ? hit.name : str(ch.name);
                if (appendVideoType(siteId)) {
                    stats.videoTypeAppended++;
                }
                log.info("[站点同步] 新通道挂靠业务站: devicecode={}, 站点={}({})", ch.devicecode, siteId, stationName);
            } else {
                siteId = resolveOrCreateFallbackStation(ch, existing, stats);
                if (siteId == null) {
                    log.warn("[站点同步] 兜底站解析失败，跳过: devicecode={}", ch.devicecode);
                    return;
                }
                SiteRow fallback = existing.siteById.get(siteId);
                stationName = fallback != null ? fallback.name : str(ch.name);
                log.info("[站点同步] 新通道挂靠兜底站（待挂靠）: devicecode={}, 兜底站={}", ch.devicecode, stationName);
            }
            String deviceId = deviceTableService.lookupOrCreateDevice(
                    deviceTableService.deviceNameOf(stationName), siteId,
                    DeviceTableService.DEVICE_TYPE_VIDEO, ch.devicecode, target, location);
            if (deviceId == null) {
                log.warn("[站点同步] 视频设备创建失败: devicecode={}, 站点={}", ch.devicecode, siteId);
                return;
            }
            stats.deviceCreated++;
            syncDeviceState(ch, target, location, stats);
            return;
        }
        SiteRow site = device.getSite() == null ? null : existing.siteById.get(device.getSite());
        if (site == null) {
            // 设备 site 悬空（站点行不存在/为空）：业务站优先、兜底站其次重挂靠
            String targetSiteId = resolveBusinessSite(ch, existing);
            if (targetSiteId != null) {
                if (appendVideoType(targetSiteId)) {
                    stats.videoTypeAppended++;
                }
                log.info("[站点同步] 设备悬空指向修复→业务站: devicecode={}, site={}", ch.devicecode, targetSiteId);
            } else {
                targetSiteId = resolveOrCreateFallbackStation(ch, existing, stats);
                if (targetSiteId != null) {
                    log.info("[站点同步] 设备悬空指向修复→兜底站（待挂靠）: devicecode={}, site={}",
                            ch.devicecode, targetSiteId);
                }
            }
            if (targetSiteId != null && deviceTableService.updateDeviceSite(device.getId(), targetSiteId)) {
                stats.reattached++;
            }
            syncDeviceState(ch, target, location, stats);
            return;
        }
        if (site.isLegacyRow()) {
            // 旧迁移行（纯#5#含devicecode）：迁移脚本换指前跳过不动（设备状态待迁移后恢复同步）
            stats.legacySkipped++;
            return;
        }
        if (site.isFallback()) {
            // 兜底站：每轮重试业务站匹配，命中即改挂（兜底站行保留，退场人工处置）
            String businessSiteId = resolveBusinessSite(ch, existing);
            if (businessSiteId != null) {
                if (appendVideoType(businessSiteId)) {
                    stats.videoTypeAppended++;
                }
                if (deviceTableService.updateDeviceSite(device.getId(), businessSiteId)) {
                    stats.reattached++;
                    log.info("[站点同步] 兜底站改挂业务站: devicecode={}, {} → {}",
                            ch.devicecode, site.id, businessSiteId);
                } else {
                    log.warn("[站点同步] 兜底站改挂失败（下轮重试）: devicecode={}", ch.devicecode);
                }
            }
            syncDeviceState(ch, target, location, stats);
            return;
        }
        // 业务站：epjutj 未含 #5# 时追加（先追加后同步设备，保持不变量）；设备状态/位置持续同步
        if (!site.hasVideoType() && appendVideoType(site.id)) {
            stats.videoTypeAppended++;
        }
        syncDeviceState(ch, target, location, stats);
    }

    /**
     * 收集ICC设备树全部视频通道（视频告警轮巡检测复用，与站点同步共用同一遍历逻辑）。
     *
     * @return 通道列表；遍历存在失败节点时返回null（数据不完整，调用方自行决定本轮是否可用）
     */
    public List<VideoChannel> collectVideoChannels() {
        List<VideoChannel> channels = new ArrayList<>();
        AtomicBoolean traversalFailed = new AtomicBoolean(false);
        collectChannels("001", null, null, channels, 0, traversalFailed);
        if (traversalFailed.get()) {
            log.warn("[视频告警] ICC设备树遍历存在失败节点，通道数据不完整，返回null");
            return null;
        }
        // 遍历完整时整体刷新通道信息缓存（/channel-info 查询用）
        refreshChannelCache(channels);
        return channels;
    }

    /**
     * 整体刷新通道信息缓存（设备树整轮遍历成功后调用；volatile 替换不可变 Map，读侧无锁）。
     * 同轮重复通道（多组织共享设备）先到先得，与站点同步 processedCodes 去重语义一致。
     */
    private void refreshChannelCache(List<VideoChannel> channels) {
        Map<String, VideoChannel> snapshot = new HashMap<>();
        for (VideoChannel ch : channels) {
            snapshot.putIfAbsent(ch.devicecode, ch);
        }
        channelCache = Collections.unmodifiableMap(snapshot);
    }

    /**
     * 按通道编码查询通道信息（名称/摄像头类型/在线状态），供弹窗页仅传 channelId 时自动补全
     * （cameraType 决定云台条显隐预判）。
     * 优先内存缓存（整点同步与告警轮巡遍历后刷新）；未命中（如启动后首轮同步完成前）
     * 实时递归遍历一次兜底；仍找不到返回 null。
     */
    public VideoChannel findChannel(String channelId) {
        if (channelId == null || channelId.trim().isEmpty()) {
            return null;
        }
        String key = channelId.trim();
        VideoChannel cached = channelCache.get(key);
        if (cached != null) {
            return cached;
        }
        log.info("[通道查询] 缓存未命中，实时遍历设备树：channelId={}", key);
        List<VideoChannel> channels = new ArrayList<>();
        AtomicBoolean failed = new AtomicBoolean(false);
        collectChannels("001", null, null, channels, 0, failed);
        if (failed.get()) {
            log.warn("[通道查询] 实时遍历存在失败节点，结果可能不完整：channelId={}", key);
        }
        for (VideoChannel ch : channels) {
            if (key.equals(ch.devicecode)) {
                return ch;
            }
        }
        return null;
    }

    /**
     * 按通道编码查询视频资产的归属站点 ID（设备表 code → site 解析），
     * 供 /channel-info 响应引用（弹窗宿主关联站点用）。单条索引查询实时查库（毫秒级），无需缓存；
     * 设备表 type 含 #5# 限定视频设备（排除同 code 的其他类型设备，参见 loadExistingStations 注释）。
     *
     * @param devicecode 通道编码（= 设备表 code）
     * @return 归属站点 ID（业务站/兜底站）；无对应设备或数据库不可达时返回 null
     */
    public String findSiteId(String devicecode) {
        if (devicecode == null || devicecode.trim().isEmpty()) {
            return null;
        }
        try {
            List<String> ids = jdbcTemplate.queryForList(
                    "SELECT site FROM " + DEVICE_TABLE +
                            " WHERE code = ? AND type LIKE '%" + VIDEO_TYPE + "%' LIMIT 1",
                    String.class, devicecode.trim());
            return (ids == null || ids.isEmpty()) ? null : ids.get(0);
        } catch (Exception e) {
            log.error("[通道查询] 站点ID查询失败: devicecode={}", devicecode, e);
            return null;
        }
    }

    /**
     * 递归遍历ICC设备树，收集视频通道（nodeType=ch）及其管理所归属
     * <p>ICC树层级（前端展示为"花凉亭灌区-管理所-硬盘录像机/位置节点-通道"，但tree API从根001
     * 返回的子列表首层即管理所，根"花凉亭灌区"不在返回中——monitor.html硬编码了根名）：
     * <ul>
     *   <li>orgName=遍历路径上第一个org节点名（管理所）；NVR/位置节点均为非org，不再改变归属组织；</li>
     *   <li>通道位置="orgName-通道名"（如"集岭管理所-大门外"）：NVR（如"集岭管理所硬盘录像机"）
     *       多通道时各通道用自身名，避免全部写成NVR名无法区分
     *       （历史教训：曾产出"集岭管理所硬盘录像机"×16条同位置、"渠首电站上游-渠首电站上游"重复）。</li>
     * </ul>
     *
     * @param parentId 父节点ID（组织/设备）
     * @param orgName  管理所级组织名称（遍历路径上第一个org节点名，null=尚未遇到org）
     * @param nvrName  最近一层非org父节点名（NVR/位置节点名，null=尚未遇到；org不覆盖它），
     *                 供业务站匹配（resolveBusinessSite）使用
     * @param out      收集结果
     * @param depth    当前递归深度
     * @param failed   任一节点查询失败即置true（遍历不完整，禁止离线标注）
     */
    private void collectChannels(String parentId, String orgName, String nvrName, List<VideoChannel> out,
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

            // 管理所级组织：遍历路径上第一个org节点名。
            // 根"花凉亭灌区"org不在API返回中，若未来ICC将其放入返回列表则跳过（它不是管理所）。
            String currentOrg = orgName;
            if (currentOrg == null && "org".equals(node.getNodeType()) && node.getName() != null
                    && !DEFAULT_LOCATION.equals(node.getName())) {
                currentOrg = node.getName();
            }

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
                // ★ 在线判定只认isOnline（0-离线 1-在线）：
                // checkStat是状态检测使能标志（请求参数checkStat=1的回显），离线设备同样为1，
                // 若用 checkStat==1 || isOnline==1 会把所有设备误判为在线（实测离线设备checkStat=1,isOnline=0）
                boolean online = Integer.valueOf(1).equals(node.getIsOnline());
                // 通道位置=管理所-通道名（NVR/位置节点层级不进入位置）；
                // nvrName（最近一层非org父节点名）随通道收集，供上级匹配
                out.add(new VideoChannel(code, name, currentOrg, nvrName, online, node.getCameraType()));
            } else if (Boolean.TRUE.equals(node.getIsParent())
                    && node.getId() != null && !node.getId().trim().isEmpty()) {
                // 有子节点的组织/设备：继续递归（id为空时跳过，防止误查根节点001）。
                // 非org中间节点（NVR/位置节点）名作为最近NVR名下传，org保持上层值
                String childNvrName = "org".equals(node.getNodeType())
                        ? nvrName : firstNonEmpty(node.getName(), nvrName);
                collectChannels(node.getId(), currentOrg, childNvrName, out, depth + 1, failed);
            }
        }
    }

    /**
     * 加载站点表快照（单次全表查询）：
     * <ul>
     *   <li>siteById：站点ID → 行信息（名称/类型/devicecode），供设备已挂站点三态判定
     *       （业务站/兜底站/迁移行，见 {@link SiteRow}）；</li>
     *   <li>names：全表站点名称（zzkaec），兜底站新建重名时追加"-视频"后缀；</li>
     *   <li>idsByName：业务站（非纯#5#行，含迁移后追加#5#的）名称 → 站点ID候选（业务站匹配用）；</li>
     *   <li>videoParentByName：纯#5#行名 → 已挂ahieto（"跟随同名视频行归属"用，重名歧义置null禁用）。</li>
     * </ul>
     *
     * @return 站点数据快照；数据库不可达时返回null
     */
    private ExistingStations loadExistingStations() {
        ExistingStations snapshot = new ExistingStations();
        try {
            // 全表加载：设备 site 指向的行做三态判定；业务站名作匹配候选；
            // 纯#5#行仅参与"同名视频行归属跟随"，不进业务站候选
            // （历史：站点表存在与视频通道同devicecode的其他类型站点——闸门#3#/水质#6#等，
            //   2026-09-05线上发现1000230$1$0$11/1000328$1$0$0重复——按类型分流防挂错站点）
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT id, devicecode, zzkaec, epjutj, ahieto FROM " + STATION_TABLE);
            for (Map<String, Object> row : rows) {
                String idStr = str(row.get("id"));
                String nameStr = str(row.get("zzkaec"));
                String typeStr = str(row.get("epjutj"));
                String codeStr = str(row.get("devicecode"));
                String parentStr = str(row.get("ahieto"));
                if (nameStr != null && !nameStr.isEmpty()) {
                    snapshot.names.add(nameStr);
                }
                if (idStr == null || idStr.isEmpty()) {
                    continue;
                }
                snapshot.siteById.put(idStr, new SiteRow(idStr, nameStr, typeStr, codeStr));
                if (VIDEO_TYPE.equals(typeStr)) {
                    // 纯#5#行（旧迁移行或兜底站）：名称 → 已挂上级；同名行重复出现即歧义（置null禁用），防"跟随"挂错
                    if (nameStr != null && !nameStr.isEmpty()) {
                        if (snapshot.videoParentByName.containsKey(nameStr)) {
                            snapshot.videoParentByName.put(nameStr, null);
                        } else {
                            snapshot.videoParentByName.put(nameStr, parentStr);
                        }
                    }
                } else if (nameStr != null && !nameStr.isEmpty()) {
                    // 业务站：名称 → 站点ID候选（业务站匹配时要求唯一命中）
                    snapshot.idsByName.computeIfAbsent(nameStr, k -> new ArrayList<>()).add(idStr);
                }
            }
        } catch (Exception e) {
            log.error("[站点同步] 加载现有站点记录失败：", e);
            return null;
        }
        return snapshot;
    }

    /**
     * 站点表快照（单次查询加载，供设备已挂站点判定、兜底站去重与业务站匹配）
     */
    private static class ExistingStations {
        /** 站点ID → 行信息（名称/类型/devicecode，三态判定见 SiteRow） */
        final Map<String, SiteRow> siteById = new HashMap<>();
        /** 全表站点名称（zzkaec），兜底站新建重名时追加"-视频"后缀 */
        final Set<String> names = new HashSet<>();
        /** 业务站（非纯#5#）名称 → 站点ID候选列表（业务站匹配用，唯一命中才挂） */
        final Map<String, List<String>> idsByName = new HashMap<>();
        /** 纯#5#行名 → 已挂ahieto（"跟随同名视频行归属"用；重名歧义时值为null，containsKey区分行不存在） */
        final Map<String, String> videoParentByName = new HashMap<>();
    }

    /**
     * 站点行快照（设备 site 指向行的三态判定）：
     * <ul>
     *   <li>业务站：epjutj 非纯 #5#（闸站/管理所/片区，含迁移后追加 #5# 的）——正常挂靠目标；</li>
     *   <li>兜底站：epjutj = #5# 且无 devicecode（同步轮自建，待挂靠）——每轮重试业务站匹配；</li>
     *   <li>迁移行：epjutj = #5# 且有 devicecode（旧同步产出的待迁移行）——迁移换指前跳过不动。</li>
     * </ul>
     */
    private static class SiteRow {
        final String id;
        final String name;
        final String type;
        final String devicecode;

        SiteRow(String id, String name, String type, String devicecode) {
            this.id = id;
            this.name = name;
            this.type = type;
            this.devicecode = devicecode;
        }

        /** 纯 #5# 行（旧迁移行或兜底站） */
        boolean isPureVideo() {
            return VIDEO_TYPE.equals(type);
        }

        /** 兜底站：纯 #5# 且无 devicecode（旧迁移行均有 devicecode，以此区分） */
        boolean isFallback() {
            return isPureVideo() && (devicecode == null || devicecode.isEmpty());
        }

        /** 旧迁移行：纯 #5# 且有 devicecode（待迁移脚本换指） */
        boolean isLegacyRow() {
            return isPureVideo() && devicecode != null && !devicecode.isEmpty();
        }

        /** epjutj 是否已含 #5# 视频类型（业务站追加判定） */
        boolean hasVideoType() {
            return type != null && type.contains(VIDEO_TYPE);
        }
    }

    /**
     * 新增视频站点名称去重：名称与站点表已有站点重复时追加"-视频"后缀
     * （如"夏家湖渡槽" → "夏家湖渡槽-视频"），避免与水位/雨量等非视频站点重名混淆。
     * 若"-视频"后缀名也已被占用（同轮多个同名新通道或历史遗留同名视频站点），
     * 改用数字递增"-视频2"、"-视频3"…，禁止叠词追加（历史教训：
     * 无脑追加"-视频"产生过"长喉槽-视频-视频-视频"脏数据）。
     * 调用方负责传入并维护 usedNames（含本轮已插入的新站点名），
     * 防止同一轮内多个同名新通道互相撞名。
     *
     * @param baseName  通道名称（zzkaec候选值）
     * @param usedNames 已使用名称集合（会被修改：登记最终使用的名称）
     * @return 去重后的站点名称
     */
    private String uniqueStationName(String baseName, Set<String> usedNames) {
        if (baseName == null || baseName.trim().isEmpty()) {
            return baseName;
        }
        String name = baseName.trim();
        if (!usedNames.contains(name)) {
            usedNames.add(name);
            return name;
        }
        // 重名 → 追加"-视频"后缀；若"-视频"也已被占用则数字递增"-视频2"、"-视频3"…
        String candidate = name + "-视频";
        int seq = 2;
        while (usedNames.contains(candidate)) {
            candidate = name + "-视频" + seq;
            seq++;
        }
        log.info("[站点同步] 站点名称{}已被占用，新增视频站点改名为{}", name, candidate);
        usedNames.add(candidate);
        return candidate;
    }

    /**
     * 业务站 epjutj 追加视频类型（"|#5#"）：设备挂靠业务站前先追加，保证
     * "视频设备所指站点含 #5#"不变量（站侧过滤/统计按类型判定）。
     * COALESCE 守卫 + NOT LIKE 条件幂等：已含 #5# 的行不更新，重复执行不叠加。
     *
     * @return true-实际追加（行有变化）；false-已含#5#/站点不存在/数据库不可达
     */
    private boolean appendVideoType(String siteId) {
        if (siteId == null || siteId.trim().isEmpty()) {
            return false;
        }
        try {
            String sql = "UPDATE " + STATION_TABLE +
                    " SET epjutj = CASE WHEN COALESCE(epjutj, '') = '' THEN '" + VIDEO_TYPE + "'" +
                    " ELSE concat(epjutj, '|" + VIDEO_TYPE + "') END, " +
                    " updated_at = ?, updated_by = 'SYSTEM'" +
                    " WHERE id = ? AND COALESCE(epjutj, '') NOT LIKE '%" + VIDEO_TYPE + "%'";
            int rows = jdbcTemplate.update(sql, new Timestamp(System.currentTimeMillis()), siteId.trim());
            if (rows > 0) {
                log.info("[站点同步] 业务站追加视频类型: site={}", siteId);
            }
            return rows > 0;
        } catch (Exception e) {
            log.error("[站点同步] 业务站追加视频类型失败: site={}", siteId, e);
            return false;
        }
    }

    /**
     * 新建兜底站：新通道匹配不到业务站时的落位站（epjutj=#5#、不写 devicecode，
     * 以此与旧迁移行（纯#5#有devicecode）区分）。仅写 id/corp_code/系统字段/名称/类型：
     * 状态/位置归设备表（D1 决策，站点表不再维护 zebpsu/mivbcz/ahieto/devicecode）。
     *
     * @return 新站点ID；失败返回null
     */
    private String insertFallbackStation(String name) {
        try {
            String stationId = IdGenerator.generate();
            Timestamp now = new Timestamp(System.currentTimeMillis());
            String sql = "INSERT INTO " + STATION_TABLE +
                    " (id, corp_code, created_at, created_by, updated_at, updated_by, zzkaec, epjutj) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
            jdbcTemplate.update(sql, stationId, corpCode, now, "SYSTEM", now, "SYSTEM",
                    name, VIDEO_TYPE);
            log.info("[站点同步] 新建兜底站: 名称={}, id={}", name, stationId);
            return stationId;
        } catch (Exception e) {
            log.error("[站点同步] 新建兜底站失败: 名称={}", name, e);
            return null;
        }
    }

    /**
     * 兜底站复用查找：按通道名候选（原名/原名+"-视频"）在兜底站中查已建行——
     * 设备创建失败重试/同通道名多设备场景复用同一兜底站，防重复建站。
     * 仅匹配纯#5#且无 devicecode 的行，业务站与旧迁移行天然排除。
     *
     * @return 兜底站ID；无匹配/查询失败返回null
     */
    private String findFallbackStationByName(String baseName) {
        if (baseName == null || baseName.trim().isEmpty()) {
            return null;
        }
        String name = baseName.trim();
        try {
            String sql = "SELECT id FROM " + STATION_TABLE +
                    " WHERE epjutj = '" + VIDEO_TYPE + "' AND COALESCE(devicecode, '') = ''" +
                    " AND (zzkaec = ? OR zzkaec = ?) LIMIT 1";
            List<String> ids = jdbcTemplate.queryForList(sql, String.class, name, name + "-视频");
            return (ids == null || ids.isEmpty()) ? null : ids.get(0);
        } catch (Exception e) {
            log.warn("[站点同步] 兜底站查找失败: 名称={}: {}", baseName, e.getMessage());
            return null;
        }
    }

    /**
     * 兜底站解析（复用或新建）：新通道匹配不到业务站时调用。
     * 新站名称经 uniqueStationName 与全表名称去重（重名追加"-视频"后缀），
     * 并把新行登记进本轮快照（siteById），防同轮多通道重复建站、供设备命名取值。
     *
     * @return 兜底站ID；查找与新建均失败返回null
     */
    private String resolveOrCreateFallbackStation(VideoChannel ch, ExistingStations existing, RoundStats stats) {
        String baseName = str(ch.name);
        if (baseName == null || baseName.isEmpty()) {
            baseName = ch.devicecode;
        }
        String reused = findFallbackStationByName(baseName);
        if (reused != null) {
            return reused;
        }
        String fallbackName = uniqueStationName(baseName, existing.names);
        String siteId = insertFallbackStation(fallbackName);
        if (siteId != null) {
            existing.siteById.put(siteId, new SiteRow(siteId, fallbackName, VIDEO_TYPE, null));
            stats.fallbackCreated++;
        }
        return siteId;
    }

    /**
     * 通道站点位置：{管理所级组织}-{通道名}（如"集岭管理所-大门外"），
     * 管理所缺失时仅通道名，均缺失兜底 DEFAULT_LOCATION（位置列非空）。
     */
    private String channelLocation(VideoChannel ch) {
        String location = DeviceTableService.buildLocation(ch.orgName, ch.name);
        return location == null || location.trim().isEmpty() ? DEFAULT_LOCATION : location.trim();
    }

    /**
     * 解析通道的业务站ID（设备挂靠目标），供新通道挂靠与兜底站改挂。
     * <p>匹配依据来自设备树收集线索与档案表（2026-09-22 人工裁决的归档规则），候选按精确度递减，
     * 逐级尝试、每级唯一命中才采用（"找不到就往上找"）：
     * <ol>
     *   <li>NVR名（最近一层非org父节点名，如"郝大屋泄洪闸上游"）精确匹配业务站
     *       （闸站#3#/#1#|#4#、管理所#2#、片区#7#、组织行）；</li>
     *   <li>去掉"上游/下游"后缀再匹配（如"郝大屋泄洪闸上游" → 档案"郝大屋泄洪闸"）；</li>
     *   <li>人工别名（PARENT_NAME_ALIASES，如"二号渡槽" → 档案"2#渡槽"）；</li>
     *   <li>同名纯#5#行（旧视频行）已挂上级时跟随（如"段垅节制闸上游" → 同名行"段垅节制闸" → "毕岭管理所"）；</li>
     *   <li>所属组织名兜底（orgName，如"毕岭管理所"；县org对应档案#7#行"望江"等）。</li>
     * </ol>
     * 全程唯一命中才返回，未命中返回null（落兜底站，后续轮次自动重试）。
     *
     * @return 业务站ID；无可靠匹配返回null
     */
    private String resolveBusinessSite(VideoChannel ch, ExistingStations index) {
        // 候选名：NVR名 → 去"上游/下游"后缀 → 人工别名 → 所属组织名（兜底"往上找"）
        List<String> candidates = new ArrayList<>();
        String nvrName = str(ch.nvrName);
        if (nvrName != null && !nvrName.isEmpty()) {
            candidates.add(nvrName);
            String base = stripUpDownSuffix(nvrName);
            if (!base.equals(nvrName)) {
                candidates.add(base);
            }
            String alias = PARENT_NAME_ALIASES.get(base);
            if (alias != null && !candidates.contains(alias)) {
                candidates.add(alias);
            }
        }
        String orgName = str(ch.orgName);
        if (orgName != null && !orgName.isEmpty() && !candidates.contains(orgName)) {
            candidates.add(orgName);
        }
        for (String candidate : candidates) {
            List<String> ids = index.idsByName.get(candidate);
            if (ids != null) {
                if (ids.size() == 1) {
                    return ids.get(0);
                }
                log.warn("[站点同步] 上级匹配重名歧义（{}行同名），跳过候选: 名称={}", ids.size(), candidate);
                continue;
            }
            // 同名视频行（#5#）已挂上级 → 跟随其归属
            String followed = index.videoParentByName.get(candidate);
            if (followed != null) {
                return followed;
            }
        }
        return null;
    }

    /**
     * 去掉名称末尾的"上游/下游"方位后缀（如"郝大屋泄洪闸上游" → "郝大屋泄洪闸"）：
     * 设备树NVR/位置节点名常带方位后缀，档案行名不带（上级匹配用）
     */
    private static String stripUpDownSuffix(String name) {
        if (name != null && name.length() > 2
                && (name.endsWith("上游") || name.endsWith("下游"))) {
            return name.substring(0, name.length() - 2);
        }
        return name;
    }

    /** Object值转字符串（trim后；null保持null） */
    private static String str(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    /**
     * 视频设备状态/位置同步（仅设备表写入，站点表不再联动）：
     * 状态随通道在线/离线（updateDeviceStatus 内 IS DISTINCT FROM 变化才写），
     * 安装位置随设备树层级（updateDeviceLocation 同源守卫）；均按通道 devicecode 精确匹配——
     * 历史教训：按 name 匹配会波及同名僵尸节点设备，故一律以 code 为键。
     */
    private void syncDeviceState(VideoChannel ch, String status, String location, RoundStats stats) {
        if (deviceTableService.updateDeviceStatus(ch.devicecode, status) > 0) {
            stats.statusUpdated++;
        } else {
            stats.unchanged++;
        }
        if (deviceTableService.updateDeviceLocation(ch.devicecode, location) > 0) {
            stats.locationUpdated++;
        }
    }

    /**
     * 单轮同步计数器（仅用于收尾日志，便于运维核对）
     */
    private static class RoundStats {
        /** 新设备挂靠台数（新建或按名兜底挂接，含业务站/兜底站） */
        int deviceCreated;
        /** 设备状态更新台数 */
        int statusUpdated;
        /** 设备状态无变化台数 */
        int unchanged;
        /** 设备安装位置更新台数 */
        int locationUpdated;
        /** 兜底站/悬空指向改挂台数 */
        int reattached;
        /** 新建兜底站数 */
        int fallbackCreated;
        /** 旧迁移行跳过台数（待迁移脚本换指） */
        int legacySkipped;
        /** 业务站追加 #5# 站数 */
        int videoTypeAppended;
        /** 消失通道联动标离线设备台数 */
        int offlineMarked;

        String describe() {
            return "新设备" + deviceCreated + "台，状态更新" + statusUpdated + "台，状态无变化" + unchanged
                    + "台，位置更新" + locationUpdated + "台，兜底改挂" + reattached + "台，新建兜底站"
                    + fallbackCreated + "个，类型追加" + videoTypeAppended + "站，迁移行跳过"
                    + legacySkipped + "台，消失标离线" + offlineMarked + "台";
        }
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
     * ICC设备树中的视频通道信息（站点同步与视频告警轮巡检测共用）
     */
    public static class VideoChannel {
        /** 设备编码（如1000231$1$0$10），匹配站点表devicecode */
        private final String devicecode;
        /** 通道名称 → 站点名称zzkaec 与 站点位置mivbcz后半（如"大门外"） */
        private final String name;
        /** 管理所级组织名称 → 站点位置mivbcz前半（如"集岭管理所"），未遍历到org时为null */
        private final String orgName;
        /** 最近一层非org父节点名（NVR/位置节点名，如"郝大屋泄洪闸上游"）→ 业务站匹配线索（resolveBusinessSite），直挂org时为null */
        private final String nvrName;
        /** 是否在线 → 设备表status */
        private final boolean online;
        /** 摄像头类型：1=枪机 2=球机 3=半球 4=云台（弹窗页云台条显隐预判） */
        private final Integer cameraType;

        VideoChannel(String devicecode, String name, String orgName, String nvrName,
                     boolean online, Integer cameraType) {
            this.devicecode = devicecode;
            this.name = name;
            this.orgName = orgName;
            this.nvrName = nvrName;
            this.online = online;
            this.cameraType = cameraType;
        }

        public String getDevicecode() {
            return devicecode;
        }

        public String getName() {
            return name;
        }

        public String getOrgName() {
            return orgName;
        }

        public String getNvrName() {
            return nvrName;
        }

        public boolean isOnline() {
            return online;
        }

        public Integer getCameraType() {
            return cameraType;
        }
    }
}
