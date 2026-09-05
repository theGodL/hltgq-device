package com.qgyun.hltgq.hltgqdevice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 大华ICC SDK配置属性
 *
 * @author hltgq-device
 */
@Component
@ConfigurationProperties(prefix = "icc.sdk")
public class DahuaConfig {

    /** 平台地址（IP:端口，如：60.175.44.252:9443） */
    private String host;

    /** 是否启用HTTPS */
    private Boolean enableHttps = true;

    /** 认证授权类型 */
    private String grantType = "password";

    /** 凭证ID */
    private String clientId;

    /** 凭证密钥 */
    private String clientSecret;

    /** 平台登录用户名 */
    private String username;

    /** 平台登录密码 */
    private String password;

    /** HLS流端口（默认7086，实际端口以ICC API返回的urlList为准） */
    private String hlsPort = "7086";

    /** HLS/NVR 内网IP（7086端口仅内网可达，不能走公网IP，默认取host中的IP） */
    private String hlsHost;

    /** 连接超时时间（毫秒，-1表示不设置） */
    private Long connectionTimeout = -1L;

    /** 读取超时时间（毫秒，-1表示不设置） */
    private Long readTimeout = -1L;

    // ========== ICC 事件订阅配置（IVSS 智能事件接入） ==========

    /** 事件订阅总开关（默认false，联调通过后打开） */
    private Boolean eventEnabled = false;

    /** 事件回调地址（ICC服务器可访问的本服务地址，如 http://192.168.x.x:18686/api/dahua/event/receive） */
    private String eventCallbackUrl;

    /** 订阅组织码（逗号分隔；留空=订阅全部，靠处理侧过滤非视频站点） */
    private String eventOrgs;

    // ========== Getters & Setters ==========

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public Boolean getEnableHttps() {
        return enableHttps;
    }

    public void setEnableHttps(Boolean enableHttps) {
        this.enableHttps = enableHttps;
    }

    public String getGrantType() {
        return grantType;
    }

    public void setGrantType(String grantType) {
        this.grantType = grantType;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getClientSecret() {
        return clientSecret;
    }

    public void setClientSecret(String clientSecret) {
        this.clientSecret = clientSecret;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getHlsPort() {
        return hlsPort;
    }

    public void setHlsPort(String hlsPort) {
        this.hlsPort = hlsPort;
    }

    public String getHlsHost() {
        return hlsHost;
    }

    public void setHlsHost(String hlsHost) {
        this.hlsHost = hlsHost;
    }

    /**
     * 获取HLS流使用的内网IP。
     * 若未配置 {@code icc.sdk.hls-host}，则回退到平台IP（{@link #getIp()}），
     * 但此时7086端口可能不可达（走公网IP）。
     */
    public String getEffectiveHlsHost() {
        if (hlsHost != null && !hlsHost.trim().isEmpty()) {
            return hlsHost.trim();
        }
        return getIp();
    }

    public Long getConnectionTimeout() {
        return connectionTimeout;
    }

    public void setConnectionTimeout(Long connectionTimeout) {
        this.connectionTimeout = connectionTimeout;
    }

    public Long getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Long readTimeout) {
        this.readTimeout = readTimeout;
    }

    public Boolean getEventEnabled() {
        return eventEnabled;
    }

    public void setEventEnabled(Boolean eventEnabled) {
        this.eventEnabled = eventEnabled;
    }

    public String getEventCallbackUrl() {
        return eventCallbackUrl;
    }

    public void setEventCallbackUrl(String eventCallbackUrl) {
        this.eventCallbackUrl = eventCallbackUrl;
    }

    public String getEventOrgs() {
        return eventOrgs;
    }

    public void setEventOrgs(String eventOrgs) {
        this.eventOrgs = eventOrgs;
    }

    /**
     * 从host中解析IP地址
     */
    public String getIp() {
        if (host != null && host.contains(":")) {
            return host.substring(0, host.lastIndexOf(":"));
        }
        return host;
    }

    /**
     * 从host中解析端口号
     */
    public String getPort() {
        if (host != null && host.contains(":")) {
            return host.substring(host.lastIndexOf(":") + 1);
        }
        return "443";
    }
}
