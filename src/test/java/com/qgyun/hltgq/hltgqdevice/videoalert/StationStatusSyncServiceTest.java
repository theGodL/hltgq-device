package com.qgyun.hltgq.hltgqdevice.videoalert;

import com.qgyun.hltgq.hltgqdevice.service.DahuaDeviceService;
import com.qgyun.hltgq.hltgqdevice.service.DeviceTableService;
import com.qgyun.hltgq.hltgqdevice.service.StationStatusSyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.invocation.Invocation;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 视频站点同步单测（站点-设备架构改造后：设备表为视频资产权威源的四态同步）。
 * 覆盖：启动异步首轮、轮巡互斥、遍历失败跳过离线标注、新通道挂靠业务站/兜底站
 * （名称去重/复用）、旧迁移行跳过、兜底站改挂、悬空指向修复、业务站类型追加、
 * 位置口径（管理所-通道名）、消失通道离线标注、通道缓存与查询、站点ID查询。
 */
class StationStatusSyncServiceTest {

    private static final String DEVICECODE = "1000231$1$0$10";
    private static final String CHANNEL_NAME = "渠首电站上游";
    private static final String DEVICE_NAME = CHANNEL_NAME + "摄像机#";
    /** 设备树层级：001→管理所org→位置节点org→通道，位置应取"管理所-通道名" */
    private static final String ORG_ID = "001001";
    private static final String ORG_NAME = "渠首管理所";
    private static final String LOC_NODE_ID = "1000230";
    private static final String NVR_NAME = "渠首硬盘录像机";
    private static final String LOCATION = ORG_NAME + "-" + CHANNEL_NAME;

    private DahuaDeviceService deviceService;
    private DeviceTableService deviceTableService;
    private JdbcTemplate jdbcTemplate;
    private StationStatusSyncService service;

    @BeforeEach
    void setUp() {
        deviceService = mock(DahuaDeviceService.class);
        deviceTableService = mock(DeviceTableService.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        service = new StationStatusSyncService();
        ReflectionTestUtils.setField(service, "deviceService", deviceService);
        ReflectionTestUtils.setField(service, "deviceTableService", deviceTableService);
        ReflectionTestUtils.setField(service, "jdbcTemplate", jdbcTemplate);
        ReflectionTestUtils.setField(service, "corpCode", "hltgq");
        when(deviceTableService.deviceNameOf(anyString())).thenReturn(DEVICE_NAME);
    }

    /** 在线通道节点（cameraType=球机，供通道信息缓存用例断言） */
    private DahuaDeviceService.DeviceTreeNode onlineChannel() {
        DahuaDeviceService.DeviceTreeNode node = new DahuaDeviceService.DeviceTreeNode();
        node.setId(DEVICECODE);
        node.setName(CHANNEL_NAME);
        node.setNodeType("ch");
        node.setIsOnline(1);
        node.setCameraType(2);
        return node;
    }

    /**
     * 三层设备树 mock（001→管理所org→位置节点org→通道）：
     * 位置取"管理所（遍历路径第一个org）-通道名"，最内层org（位置节点）不进入位置、不覆盖管理所。
     */
    private void mockThreeLevelTree() {
        when(deviceService.getDeviceTreeWithStatus(anyString())).thenAnswer(inv -> {
            String pid = inv.getArgument(0);
            if ("001".equals(pid)) {
                return DahuaDeviceService.DeviceTreeResult.success(
                        Collections.singletonList(orgNode(ORG_ID, ORG_NAME)));
            }
            if (ORG_ID.equals(pid)) {
                return DahuaDeviceService.DeviceTreeResult.success(
                        Collections.singletonList(orgNode(LOC_NODE_ID, CHANNEL_NAME)));
            }
            return DahuaDeviceService.DeviceTreeResult.success(
                    Collections.singletonList(onlineChannel()));
        });
    }

    /**
     * 管理所下的硬盘录像机（NVR）mock（001→管理所org→NVR非org→通道）：
     * NVR 名（如"渠首硬盘录像机"）不进入位置，通道位置取"管理所-通道名"。
     */
    private void mockNvrTree() {
        when(deviceService.getDeviceTreeWithStatus(anyString())).thenAnswer(inv -> {
            String pid = inv.getArgument(0);
            if ("001".equals(pid)) {
                return DahuaDeviceService.DeviceTreeResult.success(
                        Collections.singletonList(orgNode(ORG_ID, ORG_NAME)));
            }
            if (ORG_ID.equals(pid)) {
                return DahuaDeviceService.DeviceTreeResult.success(
                        Collections.singletonList(devNode(LOC_NODE_ID, NVR_NAME)));
            }
            return DahuaDeviceService.DeviceTreeResult.success(
                    Collections.singletonList(onlineChannel()));
        });
    }

    /** 有子节点的org节点（nodeType=org且isParent=true，触发递归） */
    private DahuaDeviceService.DeviceTreeNode orgNode(String id, String name) {
        DahuaDeviceService.DeviceTreeNode node = new DahuaDeviceService.DeviceTreeNode();
        node.setId(id);
        node.setName(name);
        node.setNodeType("org");
        node.setIsParent(true);
        return node;
    }

    /** 有子节点的非org节点（nodeType=dev，如硬盘录像机NVR，触发递归） */
    private DahuaDeviceService.DeviceTreeNode devNode(String id, String name) {
        DahuaDeviceService.DeviceTreeNode node = new DahuaDeviceService.DeviceTreeNode();
        node.setId(id);
        node.setName(name);
        node.setNodeType("dev");
        node.setIsParent(true);
        return node;
    }

    /**
     * 参数化三层设备树 mock（001→org→dev(NVR名)→通道）：业务站匹配用例用，
     * NVR名即 dev 节点名（如"孟垅直灌涵"），org 名进入 orgName（组织兜底用）。
     */
    private void mockNvrTreeWithChannel(String orgName, String nvrName,
                                        String channelName, String devicecode) {
        when(deviceService.getDeviceTreeWithStatus(anyString())).thenAnswer(inv -> {
            String pid = inv.getArgument(0);
            if ("001".equals(pid)) {
                return DahuaDeviceService.DeviceTreeResult.success(
                        Collections.singletonList(orgNode(ORG_ID, orgName)));
            }
            if (ORG_ID.equals(pid)) {
                return DahuaDeviceService.DeviceTreeResult.success(
                        Collections.singletonList(devNode(LOC_NODE_ID, nvrName)));
            }
            DahuaDeviceService.DeviceTreeNode node = new DahuaDeviceService.DeviceTreeNode();
            node.setId(devicecode);
            node.setName(channelName);
            node.setNodeType("ch");
            node.setIsOnline(1);
            node.setCameraType(1);
            return DahuaDeviceService.DeviceTreeResult.success(Collections.singletonList(node));
        });
    }

    /** 捕获站点表全部写库调用（appendVideoType UPDATE / 兜底站 INSERT + varargs），并返回 1 行（追加成功语义） */
    private List<InvocationOnMock> captureAllUpdates() {
        List<InvocationOnMock> updateInvocations = new ArrayList<>();
        doAnswer(inv -> {
            updateInvocations.add(inv);
            return 1;
        }).when(jdbcTemplate).update(anyString(), ArgumentMatchers.<Object>any());
        return updateInvocations;
    }

    private static String sqlOf(InvocationOnMock inv) {
        return String.valueOf(((Invocation) inv).getRawArguments()[0]);
    }

    private static Object[] argsOf(InvocationOnMock inv) {
        return (Object[]) ((Invocation) inv).getRawArguments()[1];
    }

    /** 在捕获的写库调用中查找 SQL 同时包含全部子串的首条（找不到返回 null） */
    private static InvocationOnMock findUpdate(List<InvocationOnMock> updates, String... subs) {
        for (InvocationOnMock inv : updates) {
            String sql = sqlOf(inv);
            boolean all = true;
            for (String sub : subs) {
                if (!sql.contains(sub)) {
                    all = false;
                    break;
                }
            }
            if (all) {
                return inv;
            }
        }
        return null;
    }

    /** 站点表行（加载快照用：id/zzkaec/epjutj/devicecode/ahieto 五键） */
    private Map<String, Object> stationRow(String id, String name, String type, String devicecode, String ahieto) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", id);
        row.put("zzkaec", name);
        row.put("epjutj", type);
        row.put("devicecode", devicecode);
        row.put("ahieto", ahieto);
        return row;
    }

    /** 应用启动：init() 异步触发一轮站点同步（不等整点） */
    @Test
    void startupSyncRunsOnceAsync() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(deviceService.getDeviceTreeWithStatus(anyString())).thenAnswer(inv -> {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            return DahuaDeviceService.DeviceTreeResult.success(Collections.emptyList());
        });
        // 站点表加载：空（无现有站点）
        when(jdbcTemplate.queryForList(anyString())).thenReturn(new ArrayList<>());

        service.init();

        // 启动线程确实进入了同步流程（遍历了设备树）
        assertTrue(entered.await(5, TimeUnit.SECONDS), "启动线程应异步执行站点同步");
        release.countDown();
        Thread.sleep(200); // 等线程走完收尾
        verify(deviceService, times(1)).getDeviceTreeWithStatus(anyString());
    }

    /** 轮巡互斥：上一轮未结束时本轮直接跳过（防启动轮与整点轮并发重复同步） */
    @Test
    void syncSkipsWhenPreviousRoundRunning() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(deviceService.getDeviceTreeWithStatus(anyString())).thenAnswer(inv -> {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            return DahuaDeviceService.DeviceTreeResult.success(Collections.emptyList());
        });
        when(jdbcTemplate.queryForList(anyString())).thenReturn(new ArrayList<>());

        Thread first = new Thread(() -> service.syncVideoStationStatus());
        first.start();
        assertTrue(entered.await(5, TimeUnit.SECONDS), "第一轮应进入同步流程");

        // 第二轮：上一轮未结束，应被互斥跳过（设备树不再被查询）
        service.syncVideoStationStatus();

        release.countDown();
        first.join(5000);
        verify(deviceService, times(1)).getDeviceTreeWithStatus(anyString());
    }

    /**
     * 遍历存在失败节点：本轮跳过"消失通道标离线"（防子树查询失败被误判为通道消失），
     * 站点/设备同步仍照常收尾（本用例树为空仅验证离线标注被跳过）。
     */
    @Test
    void traversalFailureSkipsOfflineMarking() {
        when(deviceService.getDeviceTreeWithStatus(anyString()))
                .thenReturn(DahuaDeviceService.DeviceTreeResult.fail());
        when(jdbcTemplate.queryForList(anyString())).thenReturn(new ArrayList<>());

        service.syncVideoStationStatus();

        verify(deviceTableService, never()).markOfflineByMissingCodes(any());
    }

    /**
     * 新通道挂靠业务站（组织名兜底匹配）：设备表中无该 code → 解析业务站（唯一命中）→
     * 站点 epjutj 追加 #5#（追加先于建设备，保持"设备所指站点含 #5#"不变量）→
     * 建设备挂靠该站 → 设备状态/位置同步 → 本轮通道参与消失标注。
     */
    @Test
    void newChannelLinksBusinessStationByOrgName() {
        mockThreeLevelTree();
        when(jdbcTemplate.queryForList(anyString())).thenReturn(Collections.singletonList(
                stationRow("biz-001", ORG_NAME, "#2#", null, null)));
        List<InvocationOnMock> updates = captureAllUpdates();
        when(deviceTableService.lookupOrCreateDevice(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString())).thenReturn("device-001");
        when(deviceTableService.updateDeviceStatus(anyString(), anyString())).thenReturn(0);
        when(deviceTableService.updateDeviceLocation(anyString(), anyString())).thenReturn(0);

        service.syncVideoStationStatus();

        // 仅 1 次站点表写库：业务站追加 #5#（幂等 SQL：CASE WHEN + concat + NOT LIKE）
        assertEquals(1, updates.size());
        String appendSql = sqlOf(updates.get(0));
        assertTrue(appendSql.contains("SET epjutj = CASE WHEN"), "应追加视频类型：" + appendSql);
        assertTrue(appendSql.contains("concat(epjutj, '|#5#')"), "追加须用 concat（金仓 || 返回布尔）：" + appendSql);
        assertTrue(appendSql.contains("NOT LIKE '%#5#%'"), "追加须幂等（NOT LIKE 条件）：" + appendSql);
        assertEquals("biz-001", argsOf(updates.get(0))[1], "追加目标为匹配到的业务站");
        // 设备挂靠业务站 + 状态/位置按设备树同步
        verify(deviceTableService).lookupOrCreateDevice(eq(DEVICE_NAME), eq("biz-001"),
                eq(DeviceTableService.DEVICE_TYPE_VIDEO), eq(DEVICECODE), eq("#1#"), eq(LOCATION));
        verify(deviceTableService).updateDeviceStatus(eq(DEVICECODE), eq("#1#"));
        verify(deviceTableService).updateDeviceLocation(eq(DEVICECODE), eq(LOCATION));
        // 本轮见到的通道参与消失标注
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> seenCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(deviceTableService).markOfflineByMissingCodes(seenCaptor.capture());
        assertTrue(seenCaptor.getValue().contains(DEVICECODE), "本轮通道应传给离线标注");
    }

    /**
     * 新通道挂靠业务站（NVR名直达）：NVR名与业务站同名 → 设备挂靠该站。
     * 场景取自 2026-09 实测：通道"孟垅直灌涵_1"挂 NVR"孟垅直灌涵"下，档案存在同名#3#行。
     */
    @Test
    void newChannelLinksBusinessStationByNvrName() {
        mockNvrTreeWithChannel("宿松", "孟垅直灌涵", "孟垅直灌涵_1", "1000329$1$0$0");
        when(jdbcTemplate.queryForList(anyString())).thenReturn(Collections.singletonList(
                stationRow("parent-001", "孟垅直灌涵", "#3#", null, null)));
        List<InvocationOnMock> updates = captureAllUpdates();
        when(deviceTableService.lookupOrCreateDevice(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString())).thenReturn("device-001");

        service.syncVideoStationStatus();

        assertEquals("parent-001", argsOf(updates.get(0))[1], "NVR名应命中同名业务站");
        verify(deviceTableService).lookupOrCreateDevice(eq(DEVICE_NAME), eq("parent-001"),
                eq(DeviceTableService.DEVICE_TYPE_VIDEO), eq("1000329$1$0$0"), eq("#1#"), eq("宿松-孟垅直灌涵_1"));
    }

    /**
     * 新通道挂靠业务站（去方位后缀+人工别名）：NVR"荞麦岭泄洪闸上游"去"上游"后缀后档案仍无同名行，
     * 经人工别名映射到"荞麦岭泄洪河"（#1#|#4#，2026-09-22业务确认"河/闸"同指一物）。
     */
    @Test
    void newChannelLinksBusinessStationViaSuffixStripAndAlias() {
        mockNvrTreeWithChannel("望江", "荞麦岭泄洪闸上游", "荞麦岭泄洪闸上游_1", "1000332$1$0$0");
        when(jdbcTemplate.queryForList(anyString())).thenReturn(Collections.singletonList(
                stationRow("parent-river", "荞麦岭泄洪河", "#1#|#4#", null, null)));
        List<InvocationOnMock> updates = captureAllUpdates();
        when(deviceTableService.lookupOrCreateDevice(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString())).thenReturn("device-001");

        service.syncVideoStationStatus();

        assertEquals("parent-river", argsOf(updates.get(0))[1], "别名应映射到荞麦岭泄洪河");
        verify(deviceTableService).lookupOrCreateDevice(eq(DEVICE_NAME), eq("parent-river"),
                eq(DeviceTableService.DEVICE_TYPE_VIDEO), eq("1000332$1$0$0"), eq("#1#"), anyString());
    }

    /**
     * 新通道挂靠业务站（跟随同名纯#5#行的已挂上级）：NVR"段垅节制闸上游"在档案无闸站行，
     * 但同名纯#5#行"段垅节制闸"已挂"毕岭管理所" → 跟随其上级（2026-09-22业务确认规则）。
     */
    @Test
    void newChannelFollowsExistingVideoRowParent() {
        mockNvrTreeWithChannel("太湖", "段垅节制闸上游", "段垅节制闸上游_1", "1000347$1$0$0");
        when(jdbcTemplate.queryForList(anyString())).thenReturn(Collections.singletonList(
                stationRow("video-001", "段垅节制闸", "#5#", "1000218$1$0$6", "parent-biling")));
        List<InvocationOnMock> updates = captureAllUpdates();
        when(deviceTableService.lookupOrCreateDevice(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString())).thenReturn("device-001");

        service.syncVideoStationStatus();

        assertEquals("parent-biling", argsOf(updates.get(0))[1], "应跟随同名视频行已挂的上级");
        verify(deviceTableService).lookupOrCreateDevice(eq(DEVICE_NAME), eq("parent-biling"),
                eq(DeviceTableService.DEVICE_TYPE_VIDEO), eq("1000347$1$0$0"), eq("#1#"), anyString());
    }

    /** 上级匹配歧义（同名多行）：不挂任何业务站，落兜底站（防误挂） */
    @Test
    void ambiguousBusinessNameFallsToFallback() {
        mockThreeLevelTree();
        when(jdbcTemplate.queryForList(anyString())).thenReturn(Arrays.asList(
                stationRow("dup-1", ORG_NAME, "#2#", null, null),
                stationRow("dup-2", ORG_NAME, "#3#", null, null)));
        List<InvocationOnMock> updates = captureAllUpdates();
        when(deviceTableService.lookupOrCreateDevice(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString())).thenReturn("device-001");

        service.syncVideoStationStatus();

        // 歧义候选跳过 → 新建兜底站（INSERT），名称取通道名
        InvocationOnMock insert = findUpdate(updates, "INSERT INTO");
        assertNotNull(insert, "歧义时应落兜底站");
        assertEquals(CHANNEL_NAME, argsOf(insert)[6], "兜底站名取通道名");
        assertEquals("#5#", argsOf(insert)[7], "兜底站 epjutj=#5#");
    }

    /**
     * 新通道无业务站匹配 → 新建兜底站（INSERT 仅 8 列：id/系统字段/名称/类型，
     * 不写 devicecode/zebpsu/mivbcz——状态位置归设备表）→ 设备挂靠兜底站（待挂靠）。
     */
    @Test
    void newChannelWithoutMatchCreatesFallbackStation() {
        mockNvrTreeWithChannel("望江县", "野站闸", "野站闸_1", "1000900$1$0$0");
        when(jdbcTemplate.queryForList(anyString())).thenReturn(new ArrayList<>());
        List<InvocationOnMock> updates = captureAllUpdates();
        when(deviceTableService.lookupOrCreateDevice(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString())).thenReturn("device-001");

        service.syncVideoStationStatus();

        assertEquals(1, updates.size());
        InvocationOnMock insert = updates.get(0);
        String insertSql = sqlOf(insert);
        assertTrue(insertSql.contains("INSERT INTO"));
        assertTrue(insertSql.contains("t_auto_hltgq_5nw74_vnqqef"));
        assertTrue(insertSql.contains("(id, corp_code, created_at, created_by, updated_at, updated_by, zzkaec, epjutj)"),
                "兜底站 INSERT 仅 8 列：" + insertSql);
        Object[] args = argsOf(insert);
        assertEquals(8, args.length);
        String fallbackSiteId = String.valueOf(args[0]);
        assertEquals("野站闸_1", args[6], "兜底站名=通道名（无重名不加后缀）");
        assertEquals("#5#", args[7], "兜底站 epjutj=#5#");
        assertFalse(Arrays.asList(args).contains("1000900$1$0$0"), "兜底站不写 devicecode");
        assertFalse(insertSql.contains("devicecode"), "兜底站 SQL 不含 devicecode 列");
        assertFalse(insertSql.contains("zebpsu"), "兜底站 SQL 不含 zebpsu（状态归设备表）");
        assertFalse(insertSql.contains("mivbcz"), "兜底站 SQL 不含 mivbcz（位置归设备表）");
        // 设备挂靠兜底站
        verify(deviceTableService).lookupOrCreateDevice(eq(DEVICE_NAME), eq(fallbackSiteId),
                eq(DeviceTableService.DEVICE_TYPE_VIDEO), eq("1000900$1$0$0"), eq("#1#"), eq("望江县-野站闸_1"));
    }

    /** 兜底站重名去重：通道名与已有站点重名 → 追加"-视频"后缀（与 uniqueStationName 规则一致） */
    @Test
    void fallbackStationNameDeduplicatedWithSuffix() {
        mockNvrTreeWithChannel("望江县", "野站闸", "野站闸_1", "1000900$1$0$0");
        when(jdbcTemplate.queryForList(anyString())).thenReturn(Collections.singletonList(
                stationRow("existing-1", "野站闸_1", "#2#", null, null)));
        List<InvocationOnMock> updates = captureAllUpdates();
        when(deviceTableService.lookupOrCreateDevice(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString())).thenReturn("device-001");

        service.syncVideoStationStatus();

        InvocationOnMock insert = findUpdate(updates, "INSERT INTO");
        assertNotNull(insert);
        assertEquals("野站闸_1-视频", argsOf(insert)[6], "重名应追加-视频后缀");
    }

    /** 兜底站复用：同通道名兜底站已存在（设备创建失败重试/同名多设备场景）→ 复用不重复建站 */
    @Test
    void fallbackStationReusedWhenExists() {
        mockNvrTreeWithChannel("望江县", "野站闸", "野站闸_1", "1000900$1$0$0");
        when(jdbcTemplate.queryForList(anyString())).thenReturn(new ArrayList<>());
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), anyString(), anyString()))
                .thenReturn(Collections.singletonList("fallback-001"));
        List<InvocationOnMock> updates = captureAllUpdates();
        when(deviceTableService.lookupOrCreateDevice(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString())).thenReturn("device-001");

        service.syncVideoStationStatus();

        assertNull(findUpdate(updates, "INSERT INTO"), "兜底站已存在不应重复建站");
        verify(deviceTableService).lookupOrCreateDevice(eq(DEVICE_NAME), eq("fallback-001"),
                eq(DeviceTableService.DEVICE_TYPE_VIDEO), eq("1000900$1$0$0"), eq("#1#"), anyString());
    }

    /**
     * 旧迁移行（纯#5#含devicecode）：迁移脚本换指前本轮跳过不动——
     * 不追加类型、不改挂、不建/查设备、不同步状态（站点表与设备表均无写库）。
     */
    @Test
    void legacyRowDeviceSkipped() {
        mockNvrTreeWithChannel("望江县", "野站闸", "野站闸_1", "1000900$1$0$0");
        when(jdbcTemplate.queryForList(anyString())).thenReturn(Collections.singletonList(
                stationRow("legacy-001", "野站闸_1", "#5#", "1000218$1$0$6", null)));
        when(deviceTableService.findVideoDeviceByCode("1000900$1$0$0"))
                .thenReturn(new DeviceTableService.VideoDeviceRef("dev-legacy", "legacy-001"));
        List<InvocationOnMock> updates = captureAllUpdates();

        service.syncVideoStationStatus();

        assertEquals(0, updates.size(), "迁移行不应触发站点表写库");
        verify(deviceTableService, never()).lookupOrCreateDevice(anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString());
        verify(deviceTableService, never()).updateDeviceSite(anyString(), anyString());
        verify(deviceTableService, never()).updateDeviceStatus(anyString(), anyString());
        verify(deviceTableService, never()).updateDeviceLocation(anyString(), anyString());
    }

    /**
     * 兜底站设备改挂业务站（每轮重试）：设备已挂兜底站 → 本轮业务站匹配命中（去后缀）→
     * 业务站追加 #5# + 设备改挂 + 状态/位置照常同步。
     */
    @Test
    void fallbackDeviceReattachesToBusinessOnRetry() {
        mockNvrTreeWithChannel("望江县", "段垅节制闸上游", "段垅节制闸上游_1", "1000347$1$0$0");
        when(jdbcTemplate.queryForList(anyString())).thenReturn(Arrays.asList(
                stationRow("fb-001", "段垅节制闸上游_1", "#5#", null, null),
                stationRow("biz-002", "段垅节制闸", "#3#", null, null)));
        when(deviceTableService.findVideoDeviceByCode("1000347$1$0$0"))
                .thenReturn(new DeviceTableService.VideoDeviceRef("dev-1", "fb-001"));
        when(deviceTableService.updateDeviceSite(anyString(), anyString())).thenReturn(true);
        List<InvocationOnMock> updates = captureAllUpdates();

        service.syncVideoStationStatus();

        // 业务站追加 #5#
        InvocationOnMock append = findUpdate(updates, "SET epjutj = CASE WHEN");
        assertNotNull(append, "改挂前应先追加业务站 #5#");
        assertEquals("biz-002", argsOf(append)[1]);
        // 设备从兜底站改挂业务站
        verify(deviceTableService).updateDeviceSite(eq("dev-1"), eq("biz-002"));
        // 状态/位置照常同步
        verify(deviceTableService).updateDeviceStatus(eq("1000347$1$0$0"), eq("#1#"));
        verify(deviceTableService).updateDeviceLocation(eq("1000347$1$0$0"), eq("望江县-段垅节制闸上游_1"));
    }

    /** 兜底站设备重试未命中：保持挂靠（不改挂、不追加），状态/位置照常同步（下轮继续重试） */
    @Test
    void fallbackDeviceStaysWhenNoBusinessMatch() {
        mockNvrTreeWithChannel("望江县", "野站闸", "野站闸_1", "1000900$1$0$0");
        when(jdbcTemplate.queryForList(anyString())).thenReturn(Collections.singletonList(
                stationRow("fb-001", "野站闸_1", "#5#", null, null)));
        when(deviceTableService.findVideoDeviceByCode("1000900$1$0$0"))
                .thenReturn(new DeviceTableService.VideoDeviceRef("dev-1", "fb-001"));
        List<InvocationOnMock> updates = captureAllUpdates();

        service.syncVideoStationStatus();

        assertEquals(0, updates.size(), "未命中业务站不应有站点表写库");
        verify(deviceTableService, never()).updateDeviceSite(anyString(), anyString());
        verify(deviceTableService).updateDeviceStatus(eq("1000900$1$0$0"), eq("#1#"));
    }

    /** 设备 site 悬空（指向不存在的站点行）：按业务站优先重挂靠 + 追加类型 */
    @Test
    void danglingDeviceSiteRepaired() {
        mockThreeLevelTree();
        when(jdbcTemplate.queryForList(anyString())).thenReturn(Collections.singletonList(
                stationRow("biz-009", ORG_NAME, "#2#", null, null)));
        when(deviceTableService.findVideoDeviceByCode(DEVICECODE))
                .thenReturn(new DeviceTableService.VideoDeviceRef("dev-1", "missing-site"));
        when(deviceTableService.updateDeviceSite(anyString(), anyString())).thenReturn(true);
        List<InvocationOnMock> updates = captureAllUpdates();

        service.syncVideoStationStatus();

        InvocationOnMock append = findUpdate(updates, "SET epjutj = CASE WHEN");
        assertNotNull(append, "重挂业务站前应先追加 #5#");
        assertEquals("biz-009", argsOf(append)[1]);
        verify(deviceTableService).updateDeviceSite(eq("dev-1"), eq("biz-009"));
    }

    /** 业务站设备：站点 epjutj 未含 #5# 时追加（先追加后同步状态），状态/位置同步 */
    @Test
    void businessStationAppendsVideoTypeWhenMissing() {
        mockThreeLevelTree();
        when(jdbcTemplate.queryForList(anyString())).thenReturn(Collections.singletonList(
                stationRow("biz-004", ORG_NAME, "#2#", null, null)));
        when(deviceTableService.findVideoDeviceByCode(DEVICECODE))
                .thenReturn(new DeviceTableService.VideoDeviceRef("dev-1", "biz-004"));
        List<InvocationOnMock> updates = captureAllUpdates();

        service.syncVideoStationStatus();

        InvocationOnMock append = findUpdate(updates, "SET epjutj = CASE WHEN");
        assertNotNull(append, "未含 #5# 的业务站应追加");
        assertEquals("biz-004", argsOf(append)[1]);
        verify(deviceTableService).updateDeviceStatus(eq(DEVICECODE), eq("#1#"));
        verify(deviceTableService).updateDeviceLocation(eq(DEVICECODE), eq(LOCATION));
    }

    /** 业务站设备：站点 epjutj 已含 #5#（如"#2#|#5#"）→ 不重复追加，仅同步状态/位置 */
    @Test
    void businessStationWithVideoTypeSkipsAppend() {
        mockThreeLevelTree();
        when(jdbcTemplate.queryForList(anyString())).thenReturn(Collections.singletonList(
                stationRow("biz-005", ORG_NAME, "#2#|#5#", null, null)));
        when(deviceTableService.findVideoDeviceByCode(DEVICECODE))
                .thenReturn(new DeviceTableService.VideoDeviceRef("dev-1", "biz-005"));
        List<InvocationOnMock> updates = captureAllUpdates();

        service.syncVideoStationStatus();

        assertEquals(0, updates.size(), "已含 #5# 不应重复追加");
        verify(deviceTableService).updateDeviceStatus(eq(DEVICECODE), eq("#1#"));
        verify(deviceTableService).updateDeviceLocation(eq(DEVICECODE), eq(LOCATION));
    }

    /** NVR 下的新通道：位置取"管理所-通道名"，NVR 名不进入位置（历史教训：16个通道全写成NVR名无法区分） */
    @Test
    void nvrChannelLocationUsesChannelNameNotNvrName() {
        mockNvrTree();
        when(jdbcTemplate.queryForList(anyString())).thenReturn(new ArrayList<>());
        List<InvocationOnMock> updates = captureAllUpdates();
        when(deviceTableService.lookupOrCreateDevice(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString())).thenReturn("device-001");
        when(deviceTableService.updateDeviceLocation(anyString(), anyString())).thenReturn(0);

        service.syncVideoStationStatus();

        // 兜底站名=通道名；NVR 名不进入名称/位置
        InvocationOnMock insert = findUpdate(updates, "INSERT INTO");
        assertNotNull(insert);
        Object[] args = argsOf(insert);
        assertTrue(Arrays.asList(args).contains(CHANNEL_NAME), "兜底站名应为通道名");
        assertFalse(Arrays.asList(args).contains(NVR_NAME), "NVR名不应进入站点名称");
        assertFalse(Arrays.asList(args).contains(ORG_NAME + "-" + NVR_NAME), "位置不应为管理所-NVR名");
        // 设备安装位置=管理所-通道名（设备表 wlcvig，站点同步轮维护）
        verify(deviceTableService).updateDeviceLocation(eq(DEVICECODE), eq(LOCATION));
        verify(deviceTableService).lookupOrCreateDevice(eq(DEVICE_NAME), anyString(),
                eq(DeviceTableService.DEVICE_TYPE_VIDEO), eq(DEVICECODE), eq("#1#"), eq(LOCATION));
    }

    // ==================== 通道信息缓存与查询（/channel-info 支撑） ====================

    /** 设备树遍历成功 → 通道信息进入缓存；findChannel 命中缓存直接返回（不再触达设备树） */
    @Test
    void collectChannelsRefreshesCacheAndFindChannelHits() {
        mockThreeLevelTree();

        List<StationStatusSyncService.VideoChannel> channels = service.collectVideoChannels();

        assertNotNull(channels, "遍历成功应返回通道列表");
        assertEquals(1, channels.size());
        // 缓存命中：返回设备树中的通道信息（含 cameraType）
        StationStatusSyncService.VideoChannel found = service.findChannel(DEVICECODE);
        assertNotNull(found, "findChannel 应命中缓存");
        assertEquals(CHANNEL_NAME, found.getName());
        assertEquals(2, found.getCameraType(), "cameraType 应从设备树收集");
        assertTrue(found.isOnline());
        // 命中缓存不触发设备树遍历：全部调用次数 = collect 遍历的 3 层（001→管理所→位置节点）
        verify(deviceService, times(3)).getDeviceTreeWithStatus(anyString());
    }

    /** 缓存未命中（启动后首轮同步完成前）→ findChannel 实时遍历设备树兜底 */
    @Test
    void findChannelRealtimeTraversalOnCacheMiss() {
        mockThreeLevelTree();

        StationStatusSyncService.VideoChannel found = service.findChannel(DEVICECODE);

        assertNotNull(found, "缓存未命中应实时遍历查得通道");
        assertEquals(DEVICECODE, found.getDevicecode());
        assertEquals(2, found.getCameraType());
        assertEquals(CHANNEL_NAME, found.getName());
        // 实时遍历 3 层
        verify(deviceService, times(3)).getDeviceTreeWithStatus(anyString());
    }

    /** 设备树中不存在该通道 → findChannel 返回 null（前端提示"未找到该通道信息"）；空白入参直接返回 null */
    @Test
    void findChannelReturnsNullWhenAbsent() {
        when(deviceService.getDeviceTreeWithStatus(anyString())).thenReturn(
                DahuaDeviceService.DeviceTreeResult.success(Collections.emptyList()));

        assertNull(service.findChannel("no-such-channel"), "不存在的通道应返回 null");
        assertNull(service.findChannel("  "), "空白 channelId 应直接返回 null");
    }

    // ==================== 站点ID查询（/channel-info 响应含 siteId） ====================

    /** findSiteId：按通道 devicecode 查设备表 site，SQL 必须限定视频设备类型（防命中同 code 的其他类型设备） */
    @Test
    void findSiteIdQueriesVideoDeviceOnly() {
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), anyString()))
                .thenReturn(Collections.singletonList("site-001"));

        assertEquals("site-001", service.findSiteId(DEVICECODE), "应返回设备表 site 列");

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForList(sqlCaptor.capture(), eq(String.class), eq(DEVICECODE));
        assertTrue(sqlCaptor.getValue().contains("t_auto_hltgq_water_device"),
                "站点ID查询应走设备表（视频资产权威源），实际 SQL：" + sqlCaptor.getValue());
        assertTrue(sqlCaptor.getValue().contains("type LIKE '%#5#%'"),
                "站点ID查询必须限定视频设备类型，实际 SQL：" + sqlCaptor.getValue());
    }

    /** findSiteId：无对应设备返回 null（不阻断接口，响应中 siteId 可为空）；空白入参直接返回 null（不查库） */
    @Test
    void findSiteIdReturnsNullWhenAbsent() {
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), anyString()))
                .thenReturn(new ArrayList<>());

        assertNull(service.findSiteId(DEVICECODE), "无对应设备应返回 null");
        assertNull(service.findSiteId("  "), "空白 devicecode 应直接返回 null");
    }
}
