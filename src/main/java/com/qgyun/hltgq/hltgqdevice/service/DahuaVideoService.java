package com.qgyun.hltgq.hltgqdevice.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.dahuatech.hutool.http.Method;
import com.dahuatech.icc.exception.ClientException;
import com.dahuatech.icc.oauth.model.v202010.GeneralResponse;
import com.dahuatech.icc.oauth.utils.HttpUtils;
import com.qgyun.hltgq.hltgqdevice.config.DahuaConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.annotation.Resource;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 大华视频流服务
 * <p>
 * 负责获取实时预览视频流地址（HLS/FLV/RTMP等格式）
 *
 * @author hltgq-device
 */
@Slf4j
@Service
public class DahuaVideoService {

    @Resource
    private DahuaAuthService authService;

    @Resource
    private DahuaConfig dahuaConfig;

    /**
     * 智能码流策略缓存：键为channelId，值为最佳码流类型。
     * <ul>
     *   <li>{@code "main"} — 主码流为H.264，返回主码流代理URL（无需转码）</li>
     *   <li>{@code "sub"}  — 子码流H.264 或 主码流也为H.265，返回子码流代理URL</li>
     * </ul>
     * 首次请求时通过前置探测确定，后续请求直接使用缓存。
     */
    private final ConcurrentHashMap<String, String> channelStreamStrategy = new ConcurrentHashMap<>();

    /**
     * 码流探测结果
     */
    private enum ProbeResult {
        /** 流可访问且为HEVC(H.265)编码 */
        HEVC,
        /** 流可访问且为H.264编码（或无法确定编码，保守视为H.264） */
        H264,
        /** 流不可访问（M3U8拉取失败，如404/连接超时） */
        UNAVAILABLE
    }

    /**
     * 获取视频流播放地址
     *
     * @param channelId  通道ID（格式：设备编号$通道号$...）
     * @param streamType 码流类型：1-主码流，2-辅码流
     * @param protocol   协议类型：hls/flv/rtmp
     * @return 流地址URL
     */
    public String getStreamUrl(String channelId, String streamType, String protocol) {
        // 默认值
        if (streamType == null || streamType.trim().isEmpty()) {
            streamType = "1";
        }
        if (protocol == null || protocol.trim().isEmpty()) {
            protocol = "hls";
        }

        // ★ HLS协议：前置智能码流探测，一次性决定用主码流还是子码流
        // 避免代理层中途切换M3U8导致PTS不连续 → bufferStalledError
        if ("hls".equalsIgnoreCase(protocol)) {
            return getSmartHlsUrl(channelId);
        }

        // 非HLS协议（flv/rtmp）：保持原有流程
        try {
            // 方式一：调用ICC视频流转发API获取流地址
            Map<String, Object> body = new HashMap<>();
            Map<String, Object> data = new HashMap<>();
            data.put("channelId", channelId);
            data.put("streamType", streamType);
            data.put("type", protocol.toLowerCase());
            body.put("data", data);

            log.info("获取视频流地址：channelId={}, streamType={}, protocol={}", channelId, streamType, protocol);

            GeneralResponse gr = HttpUtils.executeJson(
                    "/evo-apigw/admin/API/video/stream/realtime",
                    body,
                    null,
                    Method.POST,
                    authService.getOauthConfig(),
                    GeneralResponse.class
            );

            // gr.getResult() 可能已是String，不需要再用 toJSONString 包装
            Object result = gr.getResult();
            String responseJson = result instanceof String ? (String) result : JSON.toJSONString(result);
            log.info("视频流地址响应：{}", responseJson);

            JSONObject response = JSON.parseObject(responseJson);
            if (response != null && "1000".equals(response.getString("code"))) {
                JSONObject responseData = response.getJSONObject("data");
                if (responseData != null) {
                    String url = responseData.getString("url");
                    if (StringUtils.hasText(url)) {
                        log.info("API返回原始流地址：{}", url);

                        // 尝试网关地址转换（toGatewayUrl会跳过内网IP，保留原始地址）
                        String gatewayUrl = toGatewayUrl(url);
                        if (gatewayUrl != null) {
                            log.info("已转换为网关HLS地址：{}", gatewayUrl);
                            return buildProxyUrl(gatewayUrl);
                        }
                        return buildProxyUrl(url);
                    }
                }
            }

            // 方式二：API方式获取失败时，尝试拼接HLS地址
            log.info("API获取流地址失败，尝试拼接HLS地址");
            String jointUrl = getJointHlsUrl(channelId, streamType, true);
            if (jointUrl != null) {
                return buildProxyUrl(jointUrl);
            }
            return null;

        } catch (ClientException e) {
            log.error("获取视频流地址异常：{}", e.getErrMsg(), e);
            // 降级：尝试拼接HLS地址
            String jointUrl = getJointHlsUrl(channelId, streamType, true);
            return jointUrl != null ? buildProxyUrl(jointUrl) : null;
        } catch (Exception e) {
            log.error("获取视频流地址失败：", e);
            String jointUrl = getJointHlsUrl(channelId, streamType, true);
            return jointUrl != null ? buildProxyUrl(jointUrl) : null;
        }
    }

    /**
     * 将API返回的媒体服务器URL转换为网关HLS地址
     * <p>
     * ICC API有时返回内网媒体服务器直连地址（如 http://192.168.14.20:7086/...），
     * 该服务器可能需要不同的鉴权方式（非OAuth Token）。
     * 此方法检测到非网关地址时，将其替换为网关HLS代理地址（如 https://60.175.44.252:4091/...?token=xxx），
     * 网关会内部转发到媒体服务器并正确处理鉴权。
     *
     * @param apiUrl API返回的原始流地址
     * @return 网关地址，如果已是网关地址则返回 null（表示无需转换）
     */
    private String toGatewayUrl(String apiUrl) {
        if (apiUrl == null || apiUrl.isEmpty()) {
            return null;
        }
        try {
            URL url = new URL(apiUrl);
            String apiHost = url.getHost();

            // 配置的网关IP
            String gatewayIp = dahuaConfig.getIp();
            if (gatewayIp == null || gatewayIp.isEmpty()) {
                return null;
            }

            // 如果API返回的地址已经是网关地址，无需转换
            if (gatewayIp.equals(apiHost)) {
                log.debug("流地址已是网关地址，无需转换：{}", apiUrl);
                return null;
            }

            // 如果API返回的是内网地址（当前环境可直接访问），无需转换
            if (isPrivateIp(apiHost)) {
                log.info("流地址为内网地址{}，当前环境可直接访问，不转换为网关地址", apiHost);
                return null;
            }

            // 非网关、非内网地址 → 构造HLS地址（路径保持不变，host替换为内网HLS主机，7086端口仅内网可达）
            String hlsPort = dahuaConfig.getHlsPort();
            String hlsHost = dahuaConfig.getEffectiveHlsHost();
            // HLS媒体流走HTTP（NVR内网直连）
            String protocol = "http";
            String token = authService.getAccessToken();

            StringBuilder gwUrl = new StringBuilder();
            gwUrl.append(protocol).append("://")
                    .append(hlsHost).append(":").append(hlsPort)
                    .append(url.getPath());

            // 附加Token参数
            if (StringUtils.hasText(token)) {
                gwUrl.append("?token=").append(token);
            }

            return gwUrl.toString();

        } catch (MalformedURLException e) {
            log.warn("解析流地址失败：{}", apiUrl, e);
            return null;
        }
    }

    /**
     * 确保HLS流URL使用内网IP访问7086端口。
     * <p>
     * 7086端口（NVR媒体服务器）仅内网可达，公网IP（如60.175.44.252）无法连接。
     * 此方法检测到URL的目标端口为HLS端口且host为公网IP时，将host替换为内网HLS主机地址。
     *
     * @param rawUrl 原始流地址
     * @return 修正后的流地址（若无需修正则返回原值）
     */
    private String ensureInternalHlsHost(String rawUrl) {
        if (rawUrl == null || rawUrl.isEmpty()) {
            return rawUrl;
        }
        try {
            URL url = new URL(rawUrl);
            int port = url.getPort();
            if (port <= 0) {
                port = "https".equals(url.getProtocol()) ? 443 : 80;
            }

            // 仅处理HLS端口（默认7086）
            String hlsPortStr = dahuaConfig.getHlsPort();
            int hlsPort = Integer.parseInt(hlsPortStr);
            if (port != hlsPort) {
                return rawUrl; // 非HLS端口，不处理
            }

            String host = url.getHost();
            // 已是内网IP，无需替换
            if (isPrivateIp(host)) {
                return rawUrl;
            }

            // 公网IP → 替换为内网HLS主机地址
            String internalHost = dahuaConfig.getEffectiveHlsHost();
            if (internalHost.equals(host)) {
                return rawUrl; // 相同，无需替换
            }

            // 重建URL（7086端口仅支持HTTP，强制切换协议为http）
            String newUrl = "http://" + internalHost + ":" + hlsPort + url.getPath();
            if (url.getQuery() != null) {
                newUrl += "?" + url.getQuery();
            }
            log.info("HLS URL内网IP修正：{} → {}", rawUrl, newUrl);
            return newUrl;

        } catch (Exception e) {
            log.warn("HLS URL内网IP修正失败，保留原值：{}", rawUrl, e);
            return rawUrl;
        }
    }

    /**
     * 将原始流地址转换为后端代理地址
     * <p>
     * 浏览器无法直接携带OAuth Token访问ICC媒体服务器，
     * 需要通过后端代理转发HLS请求并自动附加鉴权信息。
     * 同时强制将7086端口的公网IP替换为内网HLS主机（7086仅内网可达）。
     * <p>
     * 公共方法：实时流与录像回放流（/vod/...路径，同为7086端口）共用此包装逻辑。
     *
     * @param rawUrl ICC返回的原始流地址（如 http://192.168.14.20:7086/live/.../1.m3u8）
     * @return 代理地址（如 /api/dahua/hls-proxy?url=...）
     */
    public String buildProxyUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.trim().isEmpty()) {
            return null;
        }
        try {
            // ★ 7086端口仅内网可达，强制替换公网IP为内网HLS主机地址
            String safeUrl = ensureInternalHlsHost(rawUrl);
            String encoded = URLEncoder.encode(safeUrl, "UTF-8");
            return "/api/dahua/hls-proxy?url=" + encoded;
        } catch (UnsupportedEncodingException e) {
            log.error("URL编码失败：{}", rawUrl, e);
            return rawUrl;
        }
    }

    /**
     * 判断是否为内网私有IP地址
     * <p>
     * 内网地址范围：10.x.x.x、172.16-31.x.x、192.168.x.x
     *
     * @param ip IP地址字符串
     * @return true-内网地址，false-公网地址
     */
    private boolean isPrivateIp(String ip) {
        if (ip == null || ip.isEmpty()) {
            return false;
        }
        try {
            String[] parts = ip.split("\\.");
            if (parts.length != 4) {
                return false;
            }
            int first = Integer.parseInt(parts[0]);
            int second = Integer.parseInt(parts[1]);
            // 10.0.0.0/8
            if (first == 10) {
                return true;
            }
            // 172.16.0.0/12
            if (first == 172 && second >= 16 && second <= 31) {
                return true;
            }
            // 192.168.0.0/16
            if (first == 192 && second == 168) {
                return true;
            }
            return false;
        } catch (NumberFormatException e) {
            log.warn("IP格式解析失败：{}", ip);
            return false;
        }
    }

    // 【已删除 extractPublicUrl 方法】
    // 7086端口仅内网可达，绝不能用urlList中的公网IP替换内网地址。
    // URL的内网IP修正由 ensureInternalHlsHost() 统一处理。
    // 参见 toGatewayUrl() 和 buildProxyUrl() 中的调用链。

    /**
     * 拼接HLS流地址（ICC平台HLS服务，端口4091）
     * <p>
     * 格式：http(s)://ip:port/live/cameraid/{deviceCode}%24{channelSeq}/substream/{streamType}.m3u8?token={token}
     * 与官方demo中 getJointHlsUrl 方法完全一致
     *
     * @param channelId    通道ID
     * @param streamType   码流类型
     * @param includeToken 是否在URL中直接附加token（代理模式不需要，直连模式需要）
     * @return 拼接的HLS流地址
     */
    private String getJointHlsUrl(String channelId, String streamType, boolean includeToken) {
        try {
            // ★ 7086端口仅内网可达，使用内网HLS主机IP，不走公网
            String ip = authService.getHlsHost();
            String port = authService.getHlsPort();
            String token = authService.getAccessToken();
            // HLS媒体流通常走HTTP（NVR内网直连），不使用HTTPS
            String protocol = "http";

            // 解析channelId获取设备编码和通道序号
            String deviceCode;
            String channelSeq;
            int firstDollar = channelId.indexOf("$");
            int lastDollar = channelId.lastIndexOf("$");

            if (firstDollar >= 0 && lastDollar >= 0 && firstDollar != lastDollar) {
                deviceCode = channelId.substring(0, firstDollar);
                channelSeq = channelId.substring(lastDollar + 1);
            } else if (firstDollar >= 0) {
                deviceCode = channelId.substring(0, firstDollar);
                channelSeq = channelId.substring(firstDollar + 1);
            } else {
                deviceCode = channelId;
                channelSeq = "0";
            }

            StringBuilder hlsUrl = new StringBuilder();
            hlsUrl.append(protocol).append("://").append(ip).append(":").append(port)
                    .append("/live/cameraid/")
                    .append(deviceCode).append("%24").append(channelSeq)
                    .append("/substream/").append(streamType).append(".m3u8");

            if (includeToken && StringUtils.hasText(token)) {
                hlsUrl.append("?token=").append(token);
            }

            log.info("拼接HLS流地址：{}", hlsUrl);
            return hlsUrl.toString();

        } catch (Exception e) {
            log.error("拼接HLS流地址失败：", e);
            return null;
        }
    }

    // ==================== 智能HLS码流探测（前置探测，避免代理层M3U8切换） ====================

    /**
     * 智能HLS码流选择：前置探测子码流/主码流的编码格式，一次性返回最佳流地址。
     * <p>
     * 策略：
     * <ol>
     *   <li>探测子码流：若非HEVC → 直接返回子码流（带宽低，无需转码）</li>
     *   <li>子码流HEVC → 探测主码流</li>
     *   <li>主码流H.264 → 返回主码流（无需转码，画质更高）</li>
     *   <li>主码流也为HEVC → 返回子码流（带宽低，由代理层ffmpeg转码）</li>
     * </ol>
     * 结果缓存在 {@link #channelStreamStrategy}，后续请求直接命中。
     *
     * @param channelId 通道ID
     * @return 代理URL
     */
    private String getSmartHlsUrl(String channelId) {
        // ★ 先查缓存，避免每次都调ICC API（减少不必要的外部调用）
        String cached = channelStreamStrategy.get(channelId);

        if ("main".equals(cached)) {
            // 已缓存：主码流为H.264，直接获取主码流URL
            String mainRawUrl = resolveRawStreamUrl(channelId, "1");
            if (mainRawUrl != null) {
                return buildProxyUrl(mainRawUrl);
            }
            // 主码流不可用 → 失效缓存，降级重探测
            log.warn("智能码流探测：缓存为main但主码流不可用，失效缓存重新探测，channel={}", channelId);
            channelStreamStrategy.remove(channelId);
            // fall through to probe
        } else if (cached != null) {
            // 已缓存：子码流（H.264直出 或 HEVC需转码），直接获取子码流URL
            String subRawUrl = resolveRawStreamUrl(channelId, "2");
            return subRawUrl != null ? buildProxyUrl(subRawUrl) : null;
        }

        // ====== 首次探测（缓存未命中） ======
        // 先获取子码流URL
        String subRawUrl = resolveRawStreamUrl(channelId, "2");
        if (subRawUrl == null) {
            log.warn("智能码流探测：无法获取子码流地址，channel={}", channelId);
            return null;
        }

        log.info("智能码流探测：首次探测子码流编码格式，channel={}", channelId);
        ProbeResult subProbe = probeStream(subRawUrl);

        // ★ 子码流不可用（M3U8拉取失败，如404）→ 降级探测主码流
        // 避免把无效URL返回前端导致hls.js manifestLoadError
        if (subProbe == ProbeResult.UNAVAILABLE) {
            log.warn("智能码流探测：子码流不可用，降级探测主码流，channel={}", channelId);
            String mainRawUrl = resolveRawStreamUrl(channelId, "1");
            if (mainRawUrl != null) {
                ProbeResult mainProbe = probeStream(mainRawUrl);
                if (mainProbe != ProbeResult.UNAVAILABLE) {
                    // 主码流可用：H.264直出，HEVC交由代理层ffmpeg转码
                    channelStreamStrategy.put(channelId, "main");
                    log.info("智能码流探测：子码流不可用，主码流{} → 返回主码流，channel={}",
                            mainProbe == ProbeResult.HEVC ? "HEVC(代理转码)" : "H.264", channelId);
                    return buildProxyUrl(mainRawUrl);
                }
                log.warn("智能码流探测：子码流和主码流均不可用，channel={}", channelId);
            }
            // 两条码流都取不到 → 返回null，前端提示获取流地址失败（而非404播放错误）
            return null;
        }

        if (subProbe == ProbeResult.H264) {
            // 子码流原生H.264 → 最佳方案，无需转码
            channelStreamStrategy.put(channelId, "sub");
            log.info("智能码流探测：子码流为H.264 → 直出，channel={}", channelId);
            return buildProxyUrl(subRawUrl);
        }

        // 子码流HEVC → 探测主码流
        log.info("智能码流探测：子码流为HEVC → 探测主码流，channel={}", channelId);
        String mainRawUrl = resolveRawStreamUrl(channelId, "1");
        if (mainRawUrl != null) {
            ProbeResult mainProbe = probeStream(mainRawUrl);
            if (mainProbe == ProbeResult.H264) {
                channelStreamStrategy.put(channelId, "main");
                log.info("智能码流探测：主码流为H.264 → 返回主码流，无需转码，channel={}", channelId);
                return buildProxyUrl(mainRawUrl);
            }
            log.info("智能码流探测：主码流{} → 返回子码流+代理转码，channel={}",
                    mainProbe == ProbeResult.HEVC ? "也为HEVC" : "不可用", channelId);
        } else {
            log.info("智能码流探测：主码流不可用 → 返回子码流+代理转码，channel={}", channelId);
        }

        channelStreamStrategy.put(channelId, "sub");
        return buildProxyUrl(subRawUrl);
    }

    /**
     * 获取NVR原始流地址（不构建代理URL，直接返回可访问的NVR URL）。
     * <p>
     * 与 {@link #getStreamUrl} 前半段逻辑相同：调用ICC API → 网关转换 → 内网IP修正，
     * 但不包装为代理URL，直接返回裸URL供探测使用。
     *
     * @param channelId  通道ID
     * @param streamType 码流类型（1或2）
     * @return NVR原始流地址（含token），失败返回null
     */
    private String resolveRawStreamUrl(String channelId, String streamType) {
        try {
            Map<String, Object> body = new HashMap<>();
            Map<String, Object> data = new HashMap<>();
            data.put("channelId", channelId);
            data.put("streamType", streamType);
            data.put("type", "hls");
            body.put("data", data);

            GeneralResponse gr = HttpUtils.executeJson(
                    "/evo-apigw/admin/API/video/stream/realtime",
                    body, null, Method.POST,
                    authService.getOauthConfig(),
                    GeneralResponse.class
            );

            Object result = gr.getResult();
            String responseJson = result instanceof String ? (String) result : JSON.toJSONString(result);
            JSONObject response = JSON.parseObject(responseJson);
            if (response != null && "1000".equals(response.getString("code"))) {
                JSONObject responseData = response.getJSONObject("data");
                if (responseData != null) {
                    String url = responseData.getString("url");
                    if (StringUtils.hasText(url)) {
                        // 网关转换
                        String gwUrl = toGatewayUrl(url);
                        String rawUrl = gwUrl != null ? gwUrl : url;
                        // 内网IP修正
                        rawUrl = ensureInternalHlsHost(rawUrl);
                        // ★ 确保URL包含鉴权token：ICC API返回的内网NVR地址不含token，
                        // 探测时必须附带token否则NVR拒绝访问（返回401/403）
                        if (!rawUrl.contains("token=")) {
                            String token = authService.getAccessToken();
                            if (StringUtils.hasText(token)) {
                                rawUrl += (rawUrl.contains("?") ? "&" : "?") + "token=" + token;
                            }
                        }
                        return rawUrl;
                    }
                }
            }

            // API失败 → 降级拼接
            return getJointHlsUrl(channelId, streamType, true);

        } catch (ClientException e) {
            log.warn("resolveRawStreamUrl：ICC API异常，降级拼接，channel={}，err={}", channelId, e.getErrMsg());
            return getJointHlsUrl(channelId, streamType, true);
        } catch (Exception e) {
            log.warn("resolveRawStreamUrl：获取失败，channel={}", channelId, e);
            return getJointHlsUrl(channelId, streamType, true);
        }
    }

    /**
     * 探测HLS流的编码格式及可用性。
     * <p>
     * 流程：获取M3U8 → 提取首条TS分片URL → 拉取TS分片 → 扫描PMT中的streamType。
     * 与旧版 probeHevc 的关键区别：M3U8拉取失败（如404）时返回 {@link ProbeResult#UNAVAILABLE}，
     * 避免调用方把"流不可用"误判为"H.264直出"后把无效URL返回前端（hls.js manifestLoadError）。
     * 超时保护：单次HTTP请求连接3s、读取5s。
     *
     * @param m3u8Url 流的M3U8地址（含token）
     * @return 探测结果
     */
    private ProbeResult probeStream(String m3u8Url) {
        long start = System.currentTimeMillis();
        try {
            // 从M3U8 URL中提取token（TS分片URL可能不含鉴权参数，需手动附加）
            String token = extractUrlParam(m3u8Url, "token");

            // Step 1：拉取M3U8
            String m3u8Content = httpGetString(m3u8Url);
            if (m3u8Content == null || m3u8Content.isEmpty()) {
                log.warn("probeStream：M3U8拉取失败/空（流不可用），url={}", m3u8Url);
                return ProbeResult.UNAVAILABLE;
            }
            log.info("probeStream：M3U8拉取成功，len={}，前200字符={}",
                    m3u8Content.length(),
                    m3u8Content.length() > 200 ? m3u8Content.substring(0, 200) : m3u8Content);

            // Step 2：提取首条TS分片URL
            String tsUrl = extractFirstTsUrl(m3u8Content, m3u8Url);
            if (tsUrl == null) {
                log.info("probeStream：M3U8中无TS分片");
                return ProbeResult.H264; // 保守处理：无法判断编码，视为H.264直出
            }
            log.info("probeStream：首条TS分片URL={}", tsUrl);

            // ★ 附加token到TS URL（NVR要求每条TS请求都鉴权）
            if (token != null && !tsUrl.contains("token=")) {
                tsUrl += (tsUrl.contains("?") ? "&" : "?") + "token=" + token;
                log.info("probeStream：已附加token到TS URL");
            }

            // Step 3：拉取TS分片
            byte[] tsData = httpGetBytes(tsUrl);
            if (tsData == null || tsData.length < 188 * 2) {
                log.info("probeStream：TS分片拉取失败/过短，len={}", tsData != null ? tsData.length : 0);
                return ProbeResult.H264; // 保守处理：M3U8可用但TS暂不可取，视为H.264直出
            }
            log.info("probeStream：TS分片拉取成功，len={}", tsData.length);

            // Step 4：扫描HEVC
            boolean isHevc = containsHevcSimple(tsData);
            long elapsed = System.currentTimeMillis() - start;
            log.info("probeStream：结果={}，耗时{}ms", isHevc ? "HEVC" : "H.264", elapsed);
            return isHevc ? ProbeResult.HEVC : ProbeResult.H264;

        } catch (Exception e) {
            log.info("probeStream：异常，url={}，err={}", m3u8Url, e.getMessage());
            return ProbeResult.H264; // 保守处理
        }
    }

    /**
     * 从URL中提取指定查询参数的值。
     */
    private String extractUrlParam(String url, String paramName) {
        if (url == null || url.isEmpty()) return null;
        String key = paramName + "=";
        int idx = url.indexOf(key);
        if (idx < 0) return null;
        idx += key.length();
        int end = url.indexOf('&', idx);
        return end > 0 ? url.substring(idx, end) : url.substring(idx);
    }

    /**
     * 简易TS包HEVC检测：扫描TS包(188B)的PAT→PMT，检查streamType==0x24。
     * <p>
     * 与 {@code DahuaStreamProxyController.containsHevc()} 逻辑完全一致——
     * 正确处理MPEG-TS的pointer_field，确保PAT/PMT解析准确。
     *
     * @param tsData TS原始字节
     * @return true-含HEVC流
     */
    private boolean containsHevcSimple(byte[] tsData) {
        final int TS_PKT = 188;
        if (tsData == null || tsData.length < TS_PKT * 2) return false;

        int pmtPid = -1;
        int totalPkts = tsData.length / TS_PKT;

        for (int i = 0; i < totalPkts; i++) {
            int off = i * TS_PKT;
            if ((tsData[off] & 0xFF) != 0x47) continue;
            int pid = ((tsData[off + 1] & 0x1F) << 8) | (tsData[off + 2] & 0xFF);

            if (pid == 0) {
                // PAT：提取PMT PID（与DahuaStreamProxyController.containsHevc完全一致）
                int afc = (tsData[off + 3] & 0x30) >> 4;
                int pOff = off + 4;
                if ((afc & 2) != 0) pOff += 1 + (tsData[pOff] & 0xFF);
                if ((afc & 1) == 0 || pOff + 12 > off + TS_PKT) continue;
                int ptr = tsData[pOff] & 0xFF;                    // pointer_field
                int tOff = pOff + 1 + ptr;                         // section header
                if (tOff + 12 <= off + TS_PKT) {
                    pmtPid = ((tsData[tOff + 10] & 0x1F) << 8) | (tsData[tOff + 11] & 0xFF);
                }
            }

            if (pmtPid > 0 && pid == pmtPid) {
                // PMT：检查流类型（与DahuaStreamProxyController.containsHevc完全一致）
                int afc = (tsData[off + 3] & 0x30) >> 4;
                int pOff = off + 4;
                if ((afc & 2) != 0) pOff += 1 + (tsData[pOff] & 0xFF);
                if ((afc & 1) == 0) continue;
                int ptr = tsData[pOff] & 0xFF;                    // pointer_field
                int tOff = pOff + 1 + ptr;                         // section header
                int secLen = ((tsData[tOff + 1] & 0x0F) << 8) | (tsData[tOff + 2] & 0xFF);
                int progInfo = ((tsData[tOff + 10] & 0x0F) << 8) | (tsData[tOff + 11] & 0xFF);
                int sOff = tOff + 12 + progInfo;
                int sEnd = tOff + 3 + secLen - 4;                 // minus CRC
                while (sOff + 5 <= sEnd && sOff + 5 < off + TS_PKT) {
                    int streamType = tsData[sOff] & 0xFF;
                    if (streamType == 0x24) return true;           // HEVC found
                    int esInfo = ((tsData[sOff + 3] & 0x0F) << 8) | (tsData[sOff + 4] & 0xFF);
                    sOff += 5 + esInfo;
                }
                // PMT已找到并解析完毕，未发现HEVC流类型 → 此流非HEVC
                return false;
            }
        }
        // PAT/PMT未在段中找到 → 无法判断，返回false
        return false;
    }

    /**
     * 从M3U8内容中提取首条TS分片的完整URL。
     */
    private String extractFirstTsUrl(String m3u8Content, String m3u8BaseUrl) {
        String basePath = m3u8BaseUrl.substring(0, m3u8BaseUrl.lastIndexOf('/') + 1);
        String[] lines = m3u8Content.split("\\r?\\n");
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (!line.endsWith(".ts")) continue;
            if (line.startsWith("http://") || line.startsWith("https://")) {
                return line;
            } else if (line.startsWith("/")) {
                int schemeEnd = m3u8BaseUrl.indexOf("://");
                int pathStart = m3u8BaseUrl.indexOf('/', schemeEnd + 3);
                String host = pathStart > 0 ? m3u8BaseUrl.substring(0, pathStart) : m3u8BaseUrl;
                return host + line;
            } else {
                return basePath + line;
            }
        }
        return null;
    }

    /**
     * HTTP GET请求返回字符串（用于拉取M3U8）。
     */
    private String httpGetString(String urlStr) {
        byte[] data = httpGetBytes(urlStr);
        if (data == null) return null;
        try {
            return new String(data, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * HTTP GET请求返回字节数组（用于拉取TS分片）。
     */
    private byte[] httpGetBytes(String urlStr) {
        if (urlStr == null || urlStr.isEmpty()) return null;
        HttpURLConnection conn = null;
        try {
            String url = urlStr.replace("$", "%24");
            URL target = new URL(url);
            conn = (HttpURLConnection) target.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(5000);
            conn.setRequestProperty("User-Agent", "hltgq-device-probe");
            conn.setInstanceFollowRedirects(true);
            int status = conn.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                log.info("httpGetBytes：HTTP状态码 {}，url前80字符={}", status,
                        url.length() > 80 ? url.substring(0, 80) : url);
                return null;
            }
            try (InputStream is = conn.getInputStream();
                 ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int len;
                while ((len = is.read(buf)) != -1) {
                    baos.write(buf, 0, len);
                }
                return baos.toByteArray();
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
}
