package com.qgyun.hltgq.hltgqdevice.service;

import com.qgyun.hltgq.hltgqdevice.config.DahuaConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/**
 * 大华 ICC 事件订阅服务单测：订阅请求体字段正确性（monitor/magic/category/eventType=2/
 * subscribeAll/orgs）、magic 解析规则、回调地址缺失前置失败、已订阅跳过重试/未订阅重试。
 * <p>HttpUtils.executeJson 为静态方法（Mockito 3.x 不可 mock），订阅体构建提取
 * package-private buildSubscribeBody 直接验证，subscribe 的 HTTP 链路由联调覆盖。
 */
class DahuaEventSubscribeServiceTest {

    private static final String CALLBACK_URL = "http://10.35.111.10:8010/api/dahua/event/receive";

    private DahuaConfig dahuaConfig;
    private DahuaEventSubscribeService service;

    @BeforeEach
    void setUp() {
        dahuaConfig = new DahuaConfig();
        service = new DahuaEventSubscribeService();
        ReflectionTestUtils.setField(service, "authService", mock(DahuaAuthService.class));
        ReflectionTestUtils.setField(service, "dahuaConfig", dahuaConfig);
    }

    /** 订阅体结构：monitor=回调地址、monitorType=url、category=alarm、eventType=2（只要报警）、
     * subscribeAll=1（全部报警类型）、orgs 按配置填充、subsystem.name/magic 同值 */
    @Test
    @SuppressWarnings("unchecked")
    void buildSubscribeBodyFieldsCorrect() {
        Map<String, Object> body = service.buildSubscribeBody(CALLBACK_URL, "10.35.111.10_8010", "org1,org2");

        Map<String, Object> param = (Map<String, Object>) body.get("param");
        List<Map<String, Object>> monitors = (List<Map<String, Object>>) param.get("monitors");
        assertEquals(1, monitors.size());
        Map<String, Object> monitor = monitors.get(0);
        assertEquals(CALLBACK_URL, monitor.get("monitor"));
        assertEquals("url", monitor.get("monitorType"));

        List<Map<String, Object>> events = (List<Map<String, Object>>) monitor.get("events");
        assertEquals(1, events.size());
        Map<String, Object> event = events.get(0);
        assertEquals("alarm", event.get("category"));
        assertEquals(2, event.get("eventType"));
        assertEquals(1, event.get("subscribeAll"));
        assertEquals(2, event.get("domainSubscribe"));
        List<Map<String, Object>> authorities = (List<Map<String, Object>>) event.get("authorities");
        assertEquals(1, authorities.size());
        assertEquals(Arrays.asList("org1", "org2"), authorities.get(0).get("orgs"));
        // 官方文档：orgs 与 nodeCodes 是并集，仅按 orgs 过滤必须显式 nodeCodes=[]，否则退化为订阅所有
        assertEquals(0, ((List<?>) authorities.get(0).get("nodeCodes")).size());

        Map<String, Object> subsystem = (Map<String, Object>) param.get("subsystem");
        assertEquals(0, subsystem.get("subsystemType"));
        assertEquals("10.35.111.10_8010", subsystem.get("name"));
        assertEquals("10.35.111.10_8010", subsystem.get("magic"));
    }

    /** orgs 留空：authorities=[{}]（订阅全部，靠处理侧过滤非视频站点） */
    @Test
    @SuppressWarnings("unchecked")
    void buildSubscribeBodyWithoutOrgsSubscribesAll() {
        Map<String, Object> body = service.buildSubscribeBody(CALLBACK_URL, "10.35.111.10_8010", null);

        Map<String, Object> param = (Map<String, Object>) body.get("param");
        List<Map<String, Object>> monitors = (List<Map<String, Object>>) param.get("monitors");
        List<Map<String, Object>> events = (List<Map<String, Object>>) monitors.get(0).get("events");
        List<Map<String, Object>> authorities = (List<Map<String, Object>>) events.get(0).get("authorities");
        assertEquals(1, authorities.size());
        assertNull(authorities.get(0).get("orgs"), "留空=订阅全部，authority 不应含 orgs 字段");
    }

    /** magic 解析：URL → IP_端口（无端口默认 80） */
    @Test
    void parseMagicFromCallbackUrl() {
        assertEquals("10.35.111.10_8010", service.parseMagic(CALLBACK_URL));
        assertEquals("10.35.111.10_80", service.parseMagic("http://10.35.111.10/api/dahua/event/receive"));
        assertEquals("10.35.111.10_8010", service.parseMagic("http://10.35.111.10:8010"));
    }

    /** 回调地址未配置：subscribe 前置失败，不发起 HTTP 请求 */
    @Test
    void subscribeWithoutCallbackUrlReturnsFalse() {
        dahuaConfig.setEventCallbackUrl(null);
        assertFalse(service.subscribe());
        dahuaConfig.setEventCallbackUrl("  ");
        assertFalse(service.subscribe());
    }

    /** 整点刷新：已订阅成功也每次重新订阅（幂等覆盖，防 ICC 平台侧订阅丢失后自愈） */
    @Test
    void scheduledSubscribeRefreshesEveryHour() {
        dahuaConfig.setEventEnabled(true);
        DahuaEventSubscribeService spy = spy(service);
        doReturn(true).when(spy).subscribe();

        spy.scheduledSubscribe();

        verify(spy).subscribe();
    }

    /** 订阅未成功（启动失败）：整点刷新重新发起订阅 */
    @Test
    void scheduledSubscribeRetriesWhenSubscribeFailed() {
        dahuaConfig.setEventEnabled(true);
        DahuaEventSubscribeService spy = spy(service);
        doReturn(false).when(spy).subscribe();

        spy.scheduledSubscribe();

        verify(spy).subscribe();
    }

    /** 事件订阅开关关闭：整点任务直接返回（不订阅） */
    @Test
    void scheduledSubscribeDisabledReturns() {
        dahuaConfig.setEventEnabled(false);
        DahuaEventSubscribeService spy = spy(service);

        spy.scheduledSubscribe();

        verify(spy, never()).subscribe();
        assertTrue(true, "开关关闭时静默跳过");
    }
}
