package com.qgyun.hltgq.hltgqdevice.videoalert;

import com.qgyun.hltgq.hltgqdevice.service.DahuaVideoService;
import com.qgyun.hltgq.hltgqdevice.service.StationStatusSyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.awt.image.BufferedImage;
import java.lang.reflect.Constructor;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 单路通道检测器单测：A 层流可用性判定以"流能否实际取到"为准（2026-10-29 业务定级），
 * 设备树 isOnline 仅作参考——离线但流可取不报信号丢失，离线且流不可取才判信号丢失。
 */
class ChannelInspectorTest {

    private DahuaVideoService videoService;
    private FfmpegFrameGrabber frameGrabber;
    private ImageQualityAnalyzer imageAnalyzer;
    private ChannelInspector inspector;

    @BeforeEach
    void setUp() {
        videoService = mock(DahuaVideoService.class);
        frameGrabber = mock(FfmpegFrameGrabber.class);
        imageAnalyzer = mock(ImageQualityAnalyzer.class);
        inspector = new ChannelInspector();
        ReflectionTestUtils.setField(inspector, "videoService", videoService);
        ReflectionTestUtils.setField(inspector, "frameGrabber", frameGrabber);
        ReflectionTestUtils.setField(inspector, "imageAnalyzer", imageAnalyzer);
        ReflectionTestUtils.setField(inspector, "frames", 3);
        ReflectionTestUtils.setField(inspector, "grabTimeoutSeconds", 15);
        // 默认不重试：现有用例按单次探测语义断言；重试用例单独覆盖
        ReflectionTestUtils.setField(inspector, "retryDelaySeconds", 0);
    }

    /** 构造 VideoChannel（构造器包私有，反射创建） */
    private StationStatusSyncService.VideoChannel channel(String code, boolean online) {
        try {
            Constructor<StationStatusSyncService.VideoChannel> c = StationStatusSyncService.VideoChannel.class
                    .getDeclaredConstructor(String.class, String.class, String.class, String.class,
                            boolean.class, Integer.class);
            c.setAccessible(true);
            return c.newInstance(code, "通道", "org", "nvr", online, 1);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** 设备树离线但流可取（isOnline 失真）：不报信号丢失，按正常通道继续（画质无异常 → 空集） */
    @Test
    void treeOfflineButStreamAvailableTreatedAsNormal() {
        when(videoService.resolveInspectionStreamUrl("ch1")).thenReturn("http://nvr/ch1.m3u8");
        when(videoService.isStreamAvailable("http://nvr/ch1.m3u8")).thenReturn(true);
        when(frameGrabber.grabFrames(eq("http://nvr/ch1.m3u8"), eq(3), eq(15)))
                .thenReturn(Collections.singletonList(new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB)));
        when(imageAnalyzer.analyze(anyList())).thenReturn(EnumSet.noneOf(VideoFaultType.class));

        Set<VideoFaultType> faults = inspector.inspect(channel("ch1", false));

        assertTrue(faults.isEmpty(), "设备树离线但流可取：isOnline 失真，不应报信号丢失");
    }

    /** 设备树离线且流探测不可取：真信号丢失（视频直接看不了） */
    @Test
    void treeOfflineAndStreamUnavailableIsSignalLoss() {
        when(videoService.resolveInspectionStreamUrl("ch2")).thenReturn("http://nvr/ch2.m3u8");
        when(videoService.isStreamAvailable("http://nvr/ch2.m3u8")).thenReturn(false);

        Set<VideoFaultType> faults = inspector.inspect(channel("ch2", false));

        assertEquals(EnumSet.of(VideoFaultType.SIGNAL_LOSS), faults);
    }

    /** 设备树离线且流地址获取失败：真信号丢失 */
    @Test
    void treeOfflineAndNoStreamUrlIsSignalLoss() {
        when(videoService.resolveInspectionStreamUrl("ch3")).thenReturn(null);

        Set<VideoFaultType> faults = inspector.inspect(channel("ch3", false));

        assertEquals(EnumSet.of(VideoFaultType.SIGNAL_LOSS), faults);
    }

    /** 设备树在线但流不可取：取流异常（语义不变） */
    @Test
    void onlineButStreamUnavailableIsStreamError() {
        when(videoService.resolveInspectionStreamUrl("ch4")).thenReturn("http://nvr/ch4.m3u8");
        when(videoService.isStreamAvailable("http://nvr/ch4.m3u8")).thenReturn(false);

        Set<VideoFaultType> faults = inspector.inspect(channel("ch4", true));

        assertEquals(EnumSet.of(VideoFaultType.STREAM_ERROR), faults);
    }

    /** 在线且流可取但抽帧为空：取流异常 */
    @Test
    void onlineFrameGrabEmptyIsStreamError() {
        when(videoService.resolveInspectionStreamUrl("ch5")).thenReturn("http://nvr/ch5.m3u8");
        when(videoService.isStreamAvailable("http://nvr/ch5.m3u8")).thenReturn(true);
        when(frameGrabber.grabFrames(eq("http://nvr/ch5.m3u8"), eq(3), eq(15)))
                .thenReturn(Collections.emptyList());

        Set<VideoFaultType> faults = inspector.inspect(channel("ch5", true));

        assertEquals(EnumSet.of(VideoFaultType.STREAM_ERROR), faults);
    }

    /** 首测失败、重试恢复（间歇断流瞬断自愈）：不计故障，且抽帧使用重试返回的新地址 */
    @Test
    void retryAfterFirstFailureRecoversIsNotFault() {
        ReflectionTestUtils.setField(inspector, "retryDelaySeconds", 1);
        when(videoService.resolveInspectionStreamUrl("ch6"))
                .thenReturn("http://nvr/ch6.m3u8", "http://nvr/ch6-retry.m3u8");
        when(videoService.isStreamAvailable("http://nvr/ch6.m3u8")).thenReturn(false);
        when(videoService.isStreamAvailable("http://nvr/ch6-retry.m3u8")).thenReturn(true);
        when(frameGrabber.grabFrames(eq("http://nvr/ch6-retry.m3u8"), eq(3), eq(15)))
                .thenReturn(Collections.singletonList(new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB)));
        when(imageAnalyzer.analyze(anyList())).thenReturn(EnumSet.noneOf(VideoFaultType.class));

        Set<VideoFaultType> faults = inspector.inspect(channel("ch6", true));

        assertTrue(faults.isEmpty(), "首次探测失败但重试恢复：瞬时断流，不应计故障");
        verify(frameGrabber).grabFrames(eq("http://nvr/ch6-retry.m3u8"), eq(3), eq(15));
    }

    /** 两次探测均失败：确认故障（离线通道仍判信号丢失） */
    @Test
    void retryBothFailIsStillSignalLoss() {
        ReflectionTestUtils.setField(inspector, "retryDelaySeconds", 1);
        when(videoService.resolveInspectionStreamUrl("ch7")).thenReturn("http://nvr/ch7.m3u8");
        when(videoService.isStreamAvailable("http://nvr/ch7.m3u8")).thenReturn(false);

        Set<VideoFaultType> faults = inspector.inspect(channel("ch7", false));

        assertEquals(EnumSet.of(VideoFaultType.SIGNAL_LOSS), faults, "两次探测均失败才确认信号丢失");
    }

    /** 树离线但流地址可拉、抽帧为空：取不到画面 → 信号丢失（未投运豁免可覆盖此路径） */
    @Test
    void treeOfflineAndFrameGrabEmptyIsSignalLoss() {
        when(videoService.resolveInspectionStreamUrl("ch8")).thenReturn("http://nvr/ch8.m3u8");
        when(videoService.isStreamAvailable("http://nvr/ch8.m3u8")).thenReturn(true);
        when(frameGrabber.grabFrames(eq("http://nvr/ch8.m3u8"), eq(3), eq(15)))
                .thenReturn(Collections.emptyList());

        Set<VideoFaultType> faults = inspector.inspect(channel("ch8", false));

        assertEquals(EnumSet.of(VideoFaultType.SIGNAL_LOSS), faults, "树离线且取不到画面应判信号丢失");
    }

    /** 重试等待被调度器取消中断：抛 IllegalStateException 由任务层记 error，不得伪装成故障候选 */
    @Test
    void interruptedDuringRetryThrowsInsteadOfFault() {
        ReflectionTestUtils.setField(inspector, "retryDelaySeconds", 10);
        when(videoService.resolveInspectionStreamUrl("ch9")).thenReturn("http://nvr/ch9.m3u8");
        when(videoService.isStreamAvailable("http://nvr/ch9.m3u8")).thenReturn(false);

        Thread.currentThread().interrupt();
        try {
            assertThrows(IllegalStateException.class, () -> inspector.inspect(channel("ch9", false)),
                    "被取消的中断不得伪装成故障候选");
        } finally {
            Thread.interrupted(); // 清除中断标志，避免污染后续用例
        }
    }
}
