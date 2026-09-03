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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    /** 新通道：新增站点（INSERT）+ 联动查/建设备（位置=组织-站点名）+ 状态同步 */
    @Test
    void newChannelInsertsStationAndSyncsDevice() {
        when(deviceService.getDeviceTreeWithStatus(anyString()))
                .thenReturn(DahuaDeviceService.DeviceTreeResult.success(
                        Collections.singletonList(onlineChannel())));
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
        assertNotNull(vals.get(0), "站点 id 应生成");
        // 第 2 次：消失站点离线标注（NOT IN 排除本轮见到的通道）
        String offlineSql = (String) ((org.mockito.invocation.Invocation) updateInvocations.get(1))
                .getRawArguments()[0];
        assertTrue(offlineSql.contains("SET zebpsu = ?"));
        assertTrue(offlineSql.contains("devicecode NOT IN"));
        // 设备联动：查/建（type=#5#）+ 状态同步
        verify(deviceTableService).lookupOrCreateDevice(anyString(), anyString(),
                org.mockito.ArgumentMatchers.eq(DeviceTableService.DEVICE_TYPE_VIDEO),
                org.mockito.ArgumentMatchers.eq(DEVICECODE), anyString(), anyString());
        verify(deviceTableService).updateDeviceStatus(org.mockito.ArgumentMatchers.eq(DEVICE_NAME),
                org.mockito.ArgumentMatchers.eq("#1#"));
    }
}
