package com.qgyun.hltgq.hltgqdevice.controller;

import com.qgyun.hltgq.hltgqdevice.videoalert.VideoEventService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 大华 ICC 事件订阅回调入口（IVSS 智能事件接入）。
 * <p>平台按订阅的 monitor 地址 POST 通用事件格式 JSON（无鉴权，路径已在
 * {@code AuthInterceptor} 固定白名单放行），本接口快速受理后异步处理，
 * 避免阻塞平台推送（官方要求 ContentType=application/json，返回 code=0 即视为成功）。
 * <p>业务幂等由 {@link VideoEventService} 保证（uuid 内存去重 + 告警 content 三字段去重），
 * 异步处理失败不影响对平台的响应。
 */
@Slf4j
@RestController
public class DahuaEventCallbackController {

    /** 事件处理线程池：受理与处理解耦，回调接口永不阻塞平台推送 */
    private static final ExecutorService EVENT_POOL = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "icc-event-handler");
        t.setDaemon(true);
        return t;
    });

    @Resource
    private VideoEventService videoEventService;

    /**
     * 接收事件推送：受理成功即返回 code=0（官方回调契约），处理在后台线程执行。
     *
     * @param body 平台推送的事件消息体（通用事件格式 JSON 原文）
     * @return {"code":"0","message":"成功"}
     */
    @PostMapping("/api/dahua/event/receive")
    public Map<String, Object> receive(@RequestBody String body) {
        EVENT_POOL.submit(() -> {
            try {
                videoEventService.handleEvent(body);
            } catch (Exception e) {
                log.warn("[智能事件] 事件处理异常: {}", e.getMessage());
            }
        });
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("code", "0");
        resp.put("message", "成功");
        return resp;
    }
}
