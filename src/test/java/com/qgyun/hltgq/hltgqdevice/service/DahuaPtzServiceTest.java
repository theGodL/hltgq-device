package com.qgyun.hltgq.hltgqdevice.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * 云台 STOP 方向码一致性单测。
 * <p>
 * 官方文档要求 STOP 的 direct 与 START 严格一致，否则设备无法停止。
 * 历史事故：H5 端 STOP 只带 channelId，此前解析失败默认 direct="1"（上方向），
 * 与 START（如 right="4"）不一致 → 设备持续转动停不下来。
 */
class DahuaPtzServiceTest {

    private static final String CHANNEL = "1000230$1$0$0";

    @Mock
    private DahuaAuthService authService;

    private DahuaPtzService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new DahuaPtzService();
        ReflectionTestUtils.setField(service, "authService", authService);
    }

    /** STOP 显式传 direction → 使用该方向码，不依赖缓存 */
    @Test
    void resolveWithExplicitDirection() {
        DahuaPtzService.StopDirectCode r = resolve(CHANNEL, "right");
        assertEquals("4", r.code);
        assertFalse(r.fromCache);
    }

    /** STOP 未传 direction 且无缓存 → 默认 "1"（上方向） */
    @Test
    void resolveWithoutDirectionAndNoCacheDefaultsUp() {
        DahuaPtzService.StopDirectCode r = resolve(CHANNEL, null);
        assertEquals("1", r.code);
        assertFalse(r.fromCache);
    }

    /** STOP 未传 direction 但有 START 缓存 → 复用 START 方向码（历史事故修复点） */
    @Test
    void resolveWithoutDirectionReusesStartCode() {
        lastDirectCode().put(CHANNEL, "4");
        DahuaPtzService.StopDirectCode r = resolve(CHANNEL, null);
        assertEquals("4", r.code);
        assertTrue(r.fromCache);
        // 只读不删：STOP 失败重试时仍可复用缓存
        assertEquals("4", lastDirectCode().get(CHANNEL));
    }

    /** STOP 执行失败（平台异常）→ 方向码缓存保留，供前端重试的 STOP 复用 */
    @Test
    void failedStopKeepsCachedCodeForRetry() {
        lastDirectCode().put(CHANNEL, "4");
        lastDirectStep().put(CHANNEL, "5");
        // authService 返回 null → HttpUtils 内部异常 → stopDirect 抛 RuntimeException
        when(authService.getOauthConfig()).thenReturn(null);
        assertThrows(RuntimeException.class, () -> service.stopDirect(CHANNEL, null));
        assertEquals("4", lastDirectCode().get(CHANNEL));
    }

    @SuppressWarnings("unchecked")
    private ConcurrentHashMap<String, String> lastDirectCode() {
        return (ConcurrentHashMap<String, String>) ReflectionTestUtils.getField(service, "lastDirectCode");
    }

    @SuppressWarnings("unchecked")
    private ConcurrentHashMap<String, String> lastDirectStep() {
        return (ConcurrentHashMap<String, String>) ReflectionTestUtils.getField(service, "lastDirectStep");
    }

    private DahuaPtzService.StopDirectCode resolve(String channelId, String direction) {
        return ReflectionTestUtils.invokeMethod(service, "resolveStopDirectCode", channelId, direction);
    }
}
