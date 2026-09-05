package com.qgyun.hltgq.hltgqdevice.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.dahuatech.hutool.http.Method;
import com.dahuatech.icc.exception.ClientException;
import com.dahuatech.icc.oauth.model.v202010.GeneralResponse;
import com.dahuatech.icc.oauth.utils.HttpUtils;
import com.qgyun.hltgq.hltgqdevice.config.DahuaConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 大华 ICC 事件订阅服务（IVSS 智能识别事件接入）。
 * <p>调用 ICC 事件订阅接口 {@code /evo-apigw/evo-event/1.0.0/subscribe/mqinfo}，
 * 把本服务回调地址注册为 alarm 事件的监听端（monitorType=url），平台实时 POST 推送事件
 * 到 {@code DahuaEventCallbackController}。订阅接口幂等：同 name/magic 重复订阅即覆盖，
 * 因此每小时整点刷新不会产生重复订阅，且能自愈 ICC 平台侧的订阅丢失（平台重启/人工误删）。
 * <p>订阅范围策略：
 * <ul>
 *   <li>types 不设置 = 订阅全部报警类型（官方文档说明 alarmType 码值随现场平台动态生成，
 *       无静态码表，由处理侧按 alarmTypeName 透传生成告警内容）；</li>
 *   <li>eventType=2 = 只要报警（不要事件）；</li>
 *   <li>orgs 配置化（icc.sdk.event.orgs，逗号分隔）：非空时按组织订阅（灌区根组织覆盖未来
 *       新增视频设备），留空则订阅全部、由处理侧按 nodeCode 匹配视频站点过滤。</li>
 * </ul>
 */
@Slf4j
@Service
public class DahuaEventSubscribeService {

    /** 事件订阅接口路径（ICC 网关） */
    private static final String SUBSCRIBE_PATH = "/evo-apigw/evo-event/1.0.0/subscribe/mqinfo";

    /** 监听类型：url（HTTP 回调） */
    private static final String MONITOR_TYPE_URL = "url";

    @Resource
    private DahuaAuthService authService;

    @Resource
    private DahuaConfig dahuaConfig;

    /** 启动时订阅（应用启动即尝试，失败由整点定时刷新兜底） */
    @PostConstruct
    public void init() {
        if (!Boolean.TRUE.equals(dahuaConfig.getEventEnabled())) {
            log.info("[事件订阅] 事件订阅未启用（icc.sdk.event.enabled=false），跳过");
            return;
        }
        // 异步订阅：订阅接口超时（默认读超时8s）不应阻塞应用启动
        Thread t = new Thread(this::subscribe, "icc-event-subscribe");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 每小时整点刷新订阅（接口幂等：同 name/magic 覆盖原订阅，不产生重复）。
     * 即使已订阅成功也每次刷新：防 ICC 平台侧订阅丢失（平台重启/人工误删）后本侧不自知
     * 导致事件链路静默中断，整点刷新一次即自愈（成本 1 次 HTTP 请求/小时，可忽略）。
     * 同时兜底启动时网络未就绪/平台短暂故障的订阅失败。
     */
    @Scheduled(cron = "0 0 * * * ?")
    public void scheduledSubscribe() {
        if (!Boolean.TRUE.equals(dahuaConfig.getEventEnabled())) {
            return;
        }
        subscribe();
    }

    /**
     * 执行订阅：构建订阅体（monitor=回调地址、magic=IP_端口、category=alarm、eventType=2）
     * 并调用 ICC 订阅接口，按响应 success/code 判定结果。
     *
     * @return true-订阅成功
     */
    public boolean subscribe() {
        String callbackUrl = dahuaConfig.getEventCallbackUrl();
        if (callbackUrl == null || callbackUrl.trim().isEmpty()) {
            log.warn("[事件订阅] 回调地址未配置（icc.sdk.event.callback-url），无法订阅");
            return false;
        }
        String magic = parseMagic(callbackUrl);
        if (magic == null) {
            log.warn("[事件订阅] 回调地址无法解析IP_端口: {}", callbackUrl);
            return false;
        }
        try {
            Map<String, Object> body = buildSubscribeBody(callbackUrl.trim(),
                    magic, dahuaConfig.getEventOrgs());

            log.info("[事件订阅] 发起订阅: callback={}, magic={}, orgs={}",
                    callbackUrl.trim(), magic, dahuaConfig.getEventOrgs());

            String responseJson = HttpUtils.executeJson(
                    SUBSCRIBE_PATH,
                    body,
                    null,
                    Method.POST,
                    authService.getOauthConfig(),
                    GeneralResponse.class
            ).getResult().toString();

            JSONObject response = JSON.parseObject(responseJson);
            if (response != null && Boolean.TRUE.equals(response.getBoolean("success"))) {
                log.info("[事件订阅] 订阅成功: callback={}", callbackUrl.trim());
                return true;
            }
            log.warn("[事件订阅] 订阅失败: code={}, errMsg={}",
                    response == null ? null : response.getString("code"),
                    response == null ? null : response.getString("errMsg"));
            return false;
        } catch (ClientException e) {
            log.warn("[事件订阅] 订阅请求异常: {}", e.getErrMsg());
            return false;
        } catch (Exception e) {
            log.warn("[事件订阅] 订阅请求异常: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 构建订阅请求体（package-private 供单测验证字段结构，HttpUtils 为静态方法不可 mock）。
     *
     * @param callbackUrl 回调地址（monitor 值，原样透传）
     * @param magic       订阅者标识（IP_端口，monitor/subsystem 共用）
     * @param orgs        订阅组织码（逗号分隔；空=订阅全部，authorities=[{}]）
     * @return 订阅体：{param:{monitors:[{monitor,monitorType,events:[{category,eventType,subscribeAll,domainSubscribe,authorities}]}],subsystem:{subsystemType,name,magic}}}
     */
    Map<String, Object> buildSubscribeBody(String callbackUrl, String magic, String orgs) {
        Map<String, Object> event = new HashMap<>();
        event.put("category", "alarm");
        // 只要报警（2），不要事件（1）；0=报警和事件（默认）
        event.put("eventType", 2);
        // 订阅全部报警类型：alarmType 码值随现场平台动态生成，无静态码表，
        // 处理侧按 alarmTypeName 透传生成告警内容
        event.put("subscribeAll", 1);
        event.put("domainSubscribe", 2);
        // 组织过滤（配置化）：非空时按组织订阅；留空=订阅所有（authorities=[{}]）
        // 官方文档规则：orgs 与 nodeCodes 是并集（A||B），仅按 orgs 过滤时必须显式 nodeCodes=[]，
        // 否则 nodeCodes 缺省=订阅所有 → 并集退化为全部，orgs 过滤失效
        List<Map<String, Object>> authorities = new ArrayList<>();
        Map<String, Object> authority = new HashMap<>();
        if (orgs != null && !orgs.trim().isEmpty()) {
            authority.put("orgs", Arrays.asList(orgs.split(",")));
            authority.put("nodeCodes", new ArrayList<>());
        }
        authorities.add(authority);
        event.put("authorities", authorities);

        List<Map<String, Object>> events = new ArrayList<>();
        events.add(event);

        Map<String, Object> monitor = new HashMap<>();
        monitor.put("monitor", callbackUrl.trim());
        monitor.put("monitorType", MONITOR_TYPE_URL);
        monitor.put("events", events);

        List<Map<String, Object>> monitors = new ArrayList<>();
        monitors.add(monitor);

        Map<String, Object> subsystem = new HashMap<>();
        subsystem.put("subsystemType", 0);
        subsystem.put("name", magic);
        subsystem.put("magic", magic);

        Map<String, Object> param = new HashMap<>();
        param.put("monitors", monitors);
        param.put("subsystem", subsystem);

        Map<String, Object> body = new HashMap<>();
        body.put("param", param);
        return body;
    }

    /**
     * 从回调 URL 解析订阅者 magic（IP_端口），与官方文档规则一致：
     * monitor 为域名时用域名对应的 IP_端口，示例 "10.35.111.10_8010"。
     *
     * @param callbackUrl 回调地址（http://IP:端口/路径）
     * @return "IP_端口"；解析失败返回 null
     */
    String parseMagic(String callbackUrl) {
        try {
            String noScheme = callbackUrl.trim();
            int scheme = noScheme.indexOf("://");
            if (scheme >= 0) {
                noScheme = noScheme.substring(scheme + 3);
            }
            int slash = noScheme.indexOf('/');
            String hostPort = slash >= 0 ? noScheme.substring(0, slash) : noScheme;
            int colon = hostPort.lastIndexOf(':');
            String host = colon >= 0 ? hostPort.substring(0, colon) : hostPort;
            String port = colon >= 0 ? hostPort.substring(colon + 1) : "80";
            return host + "_" + port;
        } catch (Exception e) {
            return null;
        }
    }
}
