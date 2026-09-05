package com.qgyun.hltgq.hltgqdevice.videoalert;

import com.qgyun.hltgq.hltgqdevice.service.DahuaDeviceService;
import com.qgyun.hltgq.hltgqdevice.service.DeviceTableService;
import com.qgyun.hltgq.hltgqdevice.service.StationStatusSyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 视频站点状态同步单测：启动异步首轮同步、轮巡互斥（上一轮未结束跳过）、
 * 新通道新增站点并联动视频设备。
 */
class StationStatusSyncServiceTest {

    private static final String DEVICECODE = "1000231$1$0$10";
    private static final String CHANNEL_NAME = "渠首电站上游";
    private static final String DEVICE_NAME = CHANNEL_NAME + "摄像机#";
    /** 设备树层级：001→管理所org→位置节点/NVR→通道，位置应取"管理所-通道名" */
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
    }

    /** 在线通道节点 */
    private DahuaDeviceService.DeviceTreeNode onlineChannel() {
        DahuaDeviceService.DeviceTreeNode node = new DahuaDeviceService.DeviceTreeNode();
        node.setId(DEVICECODE);
        node.setName(CHANNEL_NAME);
        node.setNodeType("ch");
        node.setIsOnline(1);
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
     * NVR 名（如"集岭管理所硬盘录像机"）不进入位置，通道位置取"管理所-通道名"。
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

    /** 轮巡互斥：上一轮未结束时本轮直接跳过（防启动轮与整点轮并发重复建站） */
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

    /** 新通道：新增站点（INSERT，位置=管理所-位置节点）+ 联动查/建设备（位置同源）+ 状态/位置同步 */
    @Test
    void newChannelInsertsStationAndSyncsDevice() {
        mockThreeLevelTree();
        // 站点表加载：空（新通道 → 新增站点）
        when(jdbcTemplate.queryForList(anyString())).thenReturn(new ArrayList<>());
        // 捕获站点 INSERT 调用
        List<InvocationOnMock> updateInvocations = new ArrayList<>();
        doAnswer(inv -> {
            updateInvocations.add(inv);
            return 1;
        }).when(jdbcTemplate).update(anyString(), any(Object.class));
        // 设备联动预置
        when(deviceTableService.deviceNameOf(anyString())).thenReturn(DEVICE_NAME);
        when(deviceTableService.lookupOrCreateDevice(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString())).thenReturn("device-001");
        when(deviceTableService.updateDeviceStatus(anyString(), anyString())).thenReturn(0);
        when(deviceTableService.updateDeviceLocation(anyString(), anyString())).thenReturn(0);

        service.syncVideoStationStatus();

        // 站点写库共 2 次：INSERT（新站点）+ 消失标注 UPDATE（无消失站点也执行，返回0行）
        assertEquals(2, updateInvocations.size());
        String insertSql = (String) ((org.mockito.invocation.Invocation) updateInvocations.get(0))
                .getRawArguments()[0];
        Object[] args = (Object[]) ((org.mockito.invocation.Invocation) updateInvocations.get(0))
                .getRawArguments()[1];
        assertTrue(insertSql.contains("INSERT INTO"));
        assertTrue(insertSql.contains("t_auto_hltgq_5nw74_vnqqef"));
        List<Object> vals = Arrays.asList(args);
        assertTrue(vals.contains(DEVICECODE), "站点 devicecode 应入库");
        assertTrue(vals.contains("#1#"), "在线站点 zebpsu=#1#");
        assertTrue(vals.contains("#5#"), "视频站点 epjutj=#5#");
        assertTrue(vals.contains(LOCATION), "站点位置 mivbcz=管理所-通道名（最内层org/命名后缀不进入位置）");
        assertNotNull(vals.get(0), "站点 id 应生成");
        // 第 2 次：消失站点离线标注（NOT IN 排除本轮见到的通道）
        String offlineSql = (String) ((org.mockito.invocation.Invocation) updateInvocations.get(1))
                .getRawArguments()[0];
        assertTrue(offlineSql.contains("SET zebpsu = ?"));
        assertTrue(offlineSql.contains("devicecode NOT IN"));
        // 设备联动：查/建（type=#5#）+ 状态同步 + 安装位置同步（与站点位置同源，按 devicecode 精确匹配）
        verify(deviceTableService).lookupOrCreateDevice(anyString(), anyString(),
                org.mockito.ArgumentMatchers.eq(DeviceTableService.DEVICE_TYPE_VIDEO),
                org.mockito.ArgumentMatchers.eq(DEVICECODE), anyString(), anyString());
        verify(deviceTableService).updateDeviceStatus(org.mockito.ArgumentMatchers.eq(DEVICECODE),
                org.mockito.ArgumentMatchers.eq("#1#"));
        verify(deviceTableService).updateDeviceLocation(org.mockito.ArgumentMatchers.eq(DEVICECODE),
                org.mockito.ArgumentMatchers.eq(LOCATION));
    }

    /** 已存在站点：状态无变化时不写库，位置与设备树层级不一致时更新 mivbcz 并联动设备安装位置 */
    @Test
    void existingStationLocationSyncsFromTree() {
        mockThreeLevelTree();
        // 站点表加载：已存在站点（devicecode 命中），zebpsu 已在线、mivbcz 为旧值"渠首电站上游"（历史错值）
        Map<String, Object> row = new java.util.HashMap<>();
        row.put("id", "site-001");
        row.put("devicecode", DEVICECODE);
        row.put("zzkaec", CHANNEL_NAME);
        row.put("zebpsu", "#1#");
        row.put("mivbcz", CHANNEL_NAME);
        when(jdbcTemplate.queryForList(anyString()))
                .thenReturn(Collections.singletonList(row));
        // 捕获写库调用（位置UPDATE为5参varargs调用，消失标注UPDATE为2参调用，分别stub）
        List<InvocationOnMock> updateInvocations = new ArrayList<>();
        doAnswer(inv -> {
            updateInvocations.add(inv);
            return 1;
        }).when(jdbcTemplate).update(anyString(), any(Object.class), any(Object.class),
                any(Object.class), any(Object.class));
        doAnswer(inv -> {
            updateInvocations.add(inv);
            return 1;
        }).when(jdbcTemplate).update(anyString(), any(Object.class));
        // 设备联动预置
        when(deviceTableService.deviceNameOf(anyString())).thenReturn(DEVICE_NAME);
        when(deviceTableService.lookupOrCreateDevice(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString())).thenReturn("device-001");
        when(deviceTableService.updateDeviceStatus(anyString(), anyString())).thenReturn(0);
        when(deviceTableService.updateDeviceLocation(anyString(), anyString())).thenReturn(0);

        service.syncVideoStationStatus();

        // 状态无变化 → 状态UPDATE不执行；位置变化 → mivbcz UPDATE + 消失标注 UPDATE 共 2 次
        assertEquals(2, updateInvocations.size());
        String locSql = (String) ((org.mockito.invocation.Invocation) updateInvocations.get(0))
                .getRawArguments()[0];
        Object[] args = (Object[]) ((org.mockito.invocation.Invocation) updateInvocations.get(0))
                .getRawArguments()[1];
        assertTrue(locSql.contains("SET mivbcz = ?"), "应执行站点位置 UPDATE");
        assertTrue(locSql.contains("epjutj"), "位置 UPDATE 必须限定视频站点，防波及同devicecode的闸门/水质站点");
        assertTrue(Arrays.asList(args).contains(LOCATION), "位置应更新为管理所-通道名");
        // 状态同步调用但无变化（updateDeviceStatus 由 SQL 条件保证，此处仅验证设备位置联动参数，按 devicecode 精确匹配）
        verify(deviceTableService).updateDeviceLocation(org.mockito.ArgumentMatchers.eq(DEVICECODE),
                org.mockito.ArgumentMatchers.eq(LOCATION));
    }

    /**
     * 已有站点状态变化：状态 UPDATE 必须限定视频站点（epjutj含#5#）——
     * 历史教训：站点表存在与视频通道同devicecode的闸门/水质站点，不限定类型会把它们的
     * zebpsu/mivbcz 一并改写（2026-09-05 线上发现 1000328$1$0$0 共 5 个不同类型站点重复）。
     */
    @Test
    void statusUpdateLimitsToVideoStations() {
        mockThreeLevelTree();
        // 站点表加载：已存在站点，zebpsu=#2#（与树上#1#不一致 → 触发状态更新），mivbcz 与树上一致（不触发位置更新）
        Map<String, Object> row = new java.util.HashMap<>();
        row.put("id", "site-001");
        row.put("devicecode", DEVICECODE);
        row.put("zzkaec", CHANNEL_NAME);
        row.put("zebpsu", "#2#");
        row.put("mivbcz", LOCATION);
        when(jdbcTemplate.queryForList(anyString())).thenReturn(Collections.singletonList(row));
        // 捕获全部 UPDATE（状态UPDATE为4参varargs、消失标注UPDATE为2参，any(Object.class)均可匹配）
        List<InvocationOnMock> updateInvocations = new ArrayList<>();
        doAnswer(inv -> {
            updateInvocations.add(inv);
            return 1;
        }).when(jdbcTemplate).update(anyString(), any(Object.class));
        // 设备联动预置
        when(deviceTableService.deviceNameOf(anyString())).thenReturn(DEVICE_NAME);
        when(deviceTableService.lookupOrCreateDevice(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString())).thenReturn("device-001");
        when(deviceTableService.updateDeviceStatus(anyString(), anyString())).thenReturn(0);
        when(deviceTableService.updateDeviceLocation(anyString(), anyString())).thenReturn(0);

        service.syncVideoStationStatus();

        // 找到状态 UPDATE（SET zebpsu）并断言限定视频站点
        boolean found = false;
        for (InvocationOnMock inv : updateInvocations) {
            String sql = (String) ((org.mockito.invocation.Invocation) inv).getRawArguments()[0];
            if (sql.contains("SET zebpsu = ?")) {
                found = true;
                assertTrue(sql.contains("epjutj"), "状态 UPDATE 必须限定视频站点：" + sql);
            }
        }
        assertTrue(found, "应执行站点状态 UPDATE");
    }

    /** NVR 下的新通道：位置取"管理所-通道名"，NVR 名不进入位置（历史教训：16个通道全写成NVR名无法区分） */
    @Test
    void nvrChannelLocationUsesChannelNameNotNvrName() {
        mockNvrTree();
        // 站点表加载：空（新通道 → 新增站点）
        when(jdbcTemplate.queryForList(anyString())).thenReturn(new ArrayList<>());
        // 捕获站点 INSERT 调用
        List<InvocationOnMock> updateInvocations = new ArrayList<>();
        doAnswer(inv -> {
            updateInvocations.add(inv);
            return 1;
        }).when(jdbcTemplate).update(anyString(), any(Object.class));
        // 设备联动预置
        when(deviceTableService.deviceNameOf(anyString())).thenReturn(DEVICE_NAME);
        when(deviceTableService.lookupOrCreateDevice(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString())).thenReturn("device-001");
        when(deviceTableService.updateDeviceStatus(anyString(), anyString())).thenReturn(0);
        when(deviceTableService.updateDeviceLocation(anyString(), anyString())).thenReturn(0);

        service.syncVideoStationStatus();

        // 第 1 次写库：站点 INSERT
        Object[] args = (Object[]) ((org.mockito.invocation.Invocation) updateInvocations.get(0))
                .getRawArguments()[1];
        List<Object> vals = Arrays.asList(args);
        assertTrue(vals.contains(LOCATION), "NVR通道位置应为管理所-通道名：" + LOCATION);
        assertFalse(vals.contains(NVR_NAME), "NVR名不应进入站点位置");
        assertFalse(vals.contains(ORG_NAME + "-" + NVR_NAME), "位置不应为管理所-NVR名");
        // 设备安装位置同源：管理所-通道名，按 devicecode 精确匹配
        verify(deviceTableService).updateDeviceLocation(org.mockito.ArgumentMatchers.eq(DEVICECODE),
                org.mockito.ArgumentMatchers.eq(LOCATION));
    }
}
