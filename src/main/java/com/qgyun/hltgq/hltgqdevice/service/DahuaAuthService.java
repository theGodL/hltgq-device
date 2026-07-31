package com.qgyun.hltgq.hltgqdevice.service;

import com.dahuatech.icc.exception.ClientException;
import com.dahuatech.icc.oauth.model.v202010.OauthConfigUserPwdInfo;
import com.dahuatech.icc.oauth.utils.HttpUtils;
import com.qgyun.hltgq.hltgqdevice.config.DahuaConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;

/**
 * 大华ICC鉴权认证服务
 * <p>
 * 负责维护OauthConfig配置及AccessToken，SDK内部自动处理Token有效期和保活
 *
 * @author hltgq-device
 */
@Slf4j
@Service
public class DahuaAuthService {

    @Resource
    private DahuaConfig dahuaConfig;

    private OauthConfigUserPwdInfo oauthConfig;

    @PostConstruct
    public void init() {
        // 构建Oauth配置（SDK内部会自动管理Token）
        // 注意：SDK的host参数只需要IP地址，端口通过后面参数单独传入
        // getHost()返回"IP:端口"，getIp()只返回IP部分
        boolean isHttp = !Boolean.TRUE.equals(dahuaConfig.getEnableHttps());
        String ip = dahuaConfig.getIp();
        String port = dahuaConfig.getPort();

        this.oauthConfig = new OauthConfigUserPwdInfo(
                ip,                         // host: 只传IP，不包含端口
                dahuaConfig.getClientId(),
                dahuaConfig.getClientSecret(),
                dahuaConfig.getUsername(),
                dahuaConfig.getPassword(),
                isHttp,
                port,                       // HTTPS端口
                isHttp ? null : port        // HTTP端口（HTTPS模式时端口值与HTTPS一致）
        );

        // 设置超时时间
        this.oauthConfig.getHttpConfigInfo().setReadTimeout(dahuaConfig.getReadTimeout());
        this.oauthConfig.getHttpConfigInfo().setConnectionTimeout(dahuaConfig.getConnectionTimeout());

        log.info("大华ICC SDK鉴权配置初始化完成，平台IP：{}，端口：{}，HTTPS：{}，凭证ID：{}",
                ip, port, !isHttp, dahuaConfig.getClientId());

        // ★ Token 预热：后台异步获取 AccessToken，避免首次云台操作多花 1~2s 鉴权时间
        Thread preWarm = new Thread(() -> {
            try {
                String token = getAccessToken();
                if (token != null) {
                    log.info("ICC Token 预热成功（length={}）", token.length());
                }
            } catch (Exception e) {
                log.warn("ICC Token 预热失败（不影响业务，首次调用时将重试）：{}", e.getMessage());
            }
        }, "icc-token-preWarm");
        preWarm.setDaemon(true);
        preWarm.start();
    }

    /**
     * 获取Oauth配置信息
     */
    public OauthConfigUserPwdInfo getOauthConfig() {
        return this.oauthConfig;
    }

    /**
     * 获取AccessToken（用于拼接HLS流地址等场景）
     */
    public String getAccessToken() {
        try {
            return HttpUtils.getToken(oauthConfig).getAccess_token();
        } catch (ClientException e) {
            log.error("获取AccessToken失败：{}", e.getErrMsg(), e);
            return null;
        }
    }

    /**
     * 获取平台IP（不含端口）
     */
    public String getPlatformIp() {
        return dahuaConfig.getIp();
    }

    /**
     * 获取HLS流端口
     */
    public String getHlsPort() {
        return dahuaConfig.getHlsPort();
    }

    /**
     * 获取HLS/NVR内网IP（7086端口仅内网可达）
     */
    public String getHlsHost() {
        return dahuaConfig.getEffectiveHlsHost();
    }
}
