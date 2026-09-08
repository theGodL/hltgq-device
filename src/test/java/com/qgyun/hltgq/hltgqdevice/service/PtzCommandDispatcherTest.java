package com.qgyun.hltgq.hltgqdevice.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * PTZ 指令调度器单测：
 * 同通道指令严格串行（START 完成前 STOP 不得执行）、
 * 新 START 入队丢弃队列中的旧 STOP（换方向跟手，不排队回放）、
 * STOP 执行完之后才到达的旧 START 被时间戳保险丢弃（防停而复转）。
 */
class PtzCommandDispatcherTest {

    private static final String CH = "1000230$1$0$0";

    @Mock
    private DahuaPtzService ptzService;

    private PtzCommandDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        dispatcher = new PtzCommandDispatcher();
        ReflectionTestUtils.setField(dispatcher, "ptzService", ptzService);
    }

    @AfterEach
    void tearDown() {
        dispatcher.shutdown();
    }

    /** 同通道指令严格串行：START 执行完成前，STOP 不得被调用 */
    @Test
    void startAndStopExecuteSerially() throws Exception {
        CountDownLatch startEntered = new CountDownLatch(1);
        CountDownLatch startRelease = new CountDownLatch(1);
        doAnswer(inv -> {
            startEntered.countDown();
            startRelease.await(5, TimeUnit.SECONDS);
            return null;
        }).when(ptzService).operateDirect(CH, "right", 5);

        dispatcher.submitDirectStart(CH, "right", 5);
        assertTrue(startEntered.await(2, TimeUnit.SECONDS), "START 应已被执行");
        dispatcher.submitDirectStop(CH, "right");
        Thread.sleep(200);
        verify(ptzService, never()).stopDirect(anyString(), anyString());
        startRelease.countDown();
        verify(ptzService, timeout(3000)).stopDirect(CH, "right");
        verify(ptzService, times(1)).operateDirect(CH, "right", 5);
    }

    /** 新 START 入队丢弃同通道未执行的旧 STOP（换方向时跟手，不排队回放） */
    @Test
    void newStartDropsPendingStop() throws Exception {
        CountDownLatch startEntered = new CountDownLatch(1);
        CountDownLatch startRelease = new CountDownLatch(1);
        doAnswer(inv -> {
            startEntered.countDown();
            startRelease.await(5, TimeUnit.SECONDS);
            return null;
        }).when(ptzService).operateDirect(anyString(), anyString(), anyInt());

        dispatcher.submitDirectStart(CH, "right", 5);
        assertTrue(startEntered.await(2, TimeUnit.SECONDS));
        // 队列里先后入队 STOP 与一条新 START：新 START 应丢弃未执行的旧 STOP
        dispatcher.submitDirectStop(CH, "right");
        dispatcher.submitDirectStart(CH, "left", 5);
        startRelease.countDown();
        verify(ptzService, timeout(3000).times(2)).operateDirect(anyString(), anyString(), anyInt());
        Thread.sleep(200);
        verify(ptzService, never()).stopDirect(anyString(), anyString());
    }

    /** STOP 执行完之后才到达的旧 START（时间戳早于 STOP）→ 时间戳保险直接丢弃 */
    @Test
    void staleStartAfterStopIsDropped() throws Exception {
        dispatcher.submitDirectStop(CH, "right");
        verify(ptzService, timeout(3000)).stopDirect(CH, "right");
        // 构造一条“旧 START”（入队时间戳早于已执行的 STOP），直接送入队列执行
        Object staleCmd = newCmd("START", CH, "right", System.currentTimeMillis() - 60_000);
        executeCmd(staleCmd);
        verify(ptzService, never()).operateDirect(anyString(), anyString(), anyInt());
    }

    /** STOP 之后的新 START（时间戳晚于 STOP）→ 正常执行，不被误丢弃 */
    @Test
    void freshStartAfterStopExecutes() {
        dispatcher.submitDirectStop(CH, "right");
        verify(ptzService, timeout(3000)).stopDirect(CH, "right");
        dispatcher.submitDirectStart(CH, "left", 5);
        verify(ptzService, timeout(3000)).operateDirect(CH, "left", 5);
    }

    // ==================== 反射辅助（Cmd/ChannelQueue 为私有内部类） ====================

    private Object newCmd(String op, String channelId, String direction, long ts) throws Exception {
        Class<?> cmdClass = Class.forName("com.qgyun.hltgq.hltgqdevice.service.PtzCommandDispatcher$Cmd");
        Class<?> categoryClass = Class.forName("com.qgyun.hltgq.hltgqdevice.service.PtzCommandDispatcher$Category");
        Class<?> opClass = Class.forName("com.qgyun.hltgq.hltgqdevice.service.PtzCommandDispatcher$Op");
        java.lang.reflect.Constructor<?> ctor = cmdClass.getDeclaredConstructor(
                categoryClass, opClass, String.class, String.class, int.class, String.class);
        ctor.setAccessible(true);
        @SuppressWarnings({"unchecked", "rawtypes"})
        Object cmd = ctor.newInstance(
                Enum.valueOf((Class) categoryClass, "DIRECTION"),
                Enum.valueOf((Class) opClass, op),
                channelId, direction, 5, null);
        ReflectionTestUtils.setField(cmd, "ts", ts);
        return cmd;
    }

    private void executeCmd(Object cmd) {
        Object queue = channels().get(CH);
        ReflectionTestUtils.invokeMethod(queue, "execute", cmd);
    }

    @SuppressWarnings("unchecked")
    private ConcurrentHashMap<String, Object> channels() {
        return (ConcurrentHashMap<String, Object>) ReflectionTestUtils.getField(dispatcher, "channels");
    }
}
