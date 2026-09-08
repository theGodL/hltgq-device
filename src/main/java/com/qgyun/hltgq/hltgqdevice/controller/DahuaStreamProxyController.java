package com.qgyun.hltgq.hltgqdevice.controller;

import com.qgyun.hltgq.hltgqdevice.service.DahuaAuthService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletResponse;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * HLS视频流代理控制器
 * <p>
 * 浏览器无法直接携带OAuth Token访问ICC媒体服务器（会返回401），
 * 此控制器作为反向代理，在后端转发HLS请求时自动附加鉴权Token。
 * <p>
 * 同时负责改写m3u8清单中的TS分片地址，确保所有资源都经过代理。
 *
 * @author hltgq-device
 */
@Slf4j
@Controller
@RequestMapping("/api/dahua")
public class DahuaStreamProxyController {

    @Resource
    private DahuaAuthService authService;

    /** HEVC(H.265)流缓存：键为流基础路径，值为是否HEVC。避免每个TS分片都重复扫描全部包 */
    private final java.util.concurrent.ConcurrentHashMap<String, Boolean> hevcStreamCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * HLS流代理入口（支持两种URL格式）
     * <p>
     * 格式1（m3u8、兼容旧版）：GET /api/dahua/hls-proxy?url={编码后的流地址}
     * 格式2（TS分片，文件名作为路径让hls.js识别类型）：
     *        GET /api/dahua/hls-proxy/1_seg_0.ts?url={编码后的流地址}
     * <p>
     * 后端转发到原始地址，附加Bearer Token，对m3u8文件改写分片地址。
     *
     * @param filename 可选的TS文件名（仅格式2使用，用于hls.js类型识别）
     * @param url      原始流地址（URL编码）
     * @param response HTTP响应
     */
    @GetMapping({"/hls-proxy", "/hls-proxy/{filename:.+}"})
    public void proxyHls(@PathVariable(required = false) String filename,
                         @RequestParam String url,
                         HttpServletResponse response) {
        long reqStartMs = System.currentTimeMillis();
        if (!StringUtils.hasText(url)) {
            sendError(response, HttpServletResponse.SC_BAD_REQUEST, "缺少url参数");
            return;
        }

        String targetUrl;
        try {
            targetUrl = URLDecoder.decode(url, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            sendError(response, HttpServletResponse.SC_BAD_REQUEST, "URL解码失败");
            return;
        }

        // ICC媒体服务器要求路径中$必须编码为%24（通道ID以$分隔如1000230$0）
        // URLDecoder会将%24解码为$，但HTTP请求中必须保持%24格式，否则服务器返回错误内容
        targetUrl = targetUrl.replace("$", "%24");

        log.debug("HLS代理请求：{}", targetUrl);

        HttpURLConnection conn = null;
        try {
            // 获取OAuth Token（ICC媒体服务器和网关均通过?token=鉴权）
            String token = authService.getAccessToken();
            if (!StringUtils.hasText(token)) {
                log.error("HLS代理：无法获取AccessToken");
                sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "鉴权Token获取失败");
                return;
            }

            // ====== 在目标URL上附加Token参数（ICC媒体服务器通过?token=鉴权，而非Bearer头） ======
            // 如果URL已包含token参数则不重复附加（如ICC平台HLS服务URL可能已含token）
            String authenticatedUrl;
            if (targetUrl.contains("token=")) {
                authenticatedUrl = targetUrl;
                log.debug("HLS代理：URL已包含token参数，不重复附加");
            } else {
                String separator = targetUrl.contains("?") ? "&" : "?";
                authenticatedUrl = targetUrl + separator + "token=" + token;
            }
            log.debug("HLS代理请求（已附加token）：{}", authenticatedUrl);

            // 连接目标URL
            URL target = new URL(authenticatedUrl);
            conn = (HttpURLConnection) target.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(30000);
            // 同时尝试 X-Subject-Token 头（部分ICC部署使用此头）
            conn.setRequestProperty("X-Subject-Token", token);
            conn.setRequestProperty("User-Agent", "hltgq-device-hls-proxy");
            conn.setInstanceFollowRedirects(true);

            int status = conn.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                log.warn("HLS代理：目标服务器返回状态码 {}，URL：{}", status, targetUrl);
                response.sendError(status, "上游服务器返回 " + status);
                return;
            }

            String contentType = conn.getContentType();
            if (contentType == null) {
                contentType = "application/octet-stream";
            }

            // 读取响应体
            byte[] body;
            try (InputStream is = conn.getInputStream()) {
                body = readAllBytes(is);
            }
            long fetchElapsed = System.currentTimeMillis() - reqStartMs;
            boolean isTs = !isM3u8(targetUrl, contentType);
            long transcodeElapsed = 0;
            int origLen = 0, transcodedLen = 0;
            boolean wasHevc = false;

            // TS分片处理：HEVC(H.265)检测与ffmpeg转码（hls.js原生TS demuxer不支持HEVC-in-TS）
            // ★ 码流类型已由DahuaVideoService前置探测决定，代理层无需再做切换
            if (isTs) {
                // 诊断日志：TS片段前部hex dump
                int dumpLen = Math.min(body.length, 376);
                log.debug("HLS代理TS响应：ContentType={}, 大小={}bytes, 前{}字节hex={}",
                        contentType, body.length, dumpLen, bytesToHex(body, dumpLen));
                analyzeTsPackets(body);
            
                // HEVC检测与转码
                String streamKey = getStreamBaseKey(targetUrl);
                Boolean isHevcDetected = hevcStreamCache.get(streamKey);
            
                if (isHevcDetected == null) {
                    // 首次遇到此流，扫描全部TS包检测编码格式
                    isHevcDetected = containsHevc(body);
                    // ★ 同时缓存 true 和 false 结果：H.264 段也缓存，避免每个分片都重复扫描188字节包
                    hevcStreamCache.put(streamKey, isHevcDetected);
                    if (Boolean.TRUE.equals(isHevcDetected)) {
                        log.info("HLS代理：首次检测到HEVC(H.265)视频流，stream={}", streamKey);
                    } else {
                        log.debug("HLS代理：检测到H.264流（非HEVC），stream={}", streamKey);
                    }
                }
            
                if (Boolean.TRUE.equals(isHevcDetected)) {
                    // 使用ffmpeg转码
                    origLen = body.length;
                    wasHevc = true;
                    long transcodeStart = System.currentTimeMillis();
                    log.debug("HLS代理：HEVC(H.265)流 → ffmpeg转码H.264，原始{}B", origLen);
                    // ★ 从URL提取分片序号，计算PTS偏移量：seq * 2s（targetDuration）
                    // 每个分片由独立ffmpeg进程处理，不设偏移则PTS都从0开始→hls.js remuxer判为重叠→buffer stall
                    double ptsOffset = extractSegmentSeq(targetUrl) * 2.0;
                    byte[] transcoded = transcodeHevcToH264(body, ptsOffset);
                    transcodeElapsed = System.currentTimeMillis() - transcodeStart;
                    if (transcoded != body) {
                        transcodedLen = transcoded.length;
                        // ★ 仅转码超过500ms时才单独记录（正常情况汇总在下方 HLS代理| 行）
                        if (transcodeElapsed > 500) {
                            log.info("HLS代理：ffmpeg转码较慢 {}B(H.265)→{}B(H.264) 耗时{}ms", origLen, transcoded.length, transcodeElapsed);
                        }
                        body = transcoded;
                    } else {
                        log.warn("HLS代理：ffmpeg转码失败！请确认服务器已安装ffmpeg，stream={}", streamKey);
                    }
                }
            }

            // 对m3u8文件改写分片地址，使TS分片也经过代理
            if (isM3u8(targetUrl, contentType)) {
                String m3u8Content = new String(body, StandardCharsets.UTF_8);
                log.debug("HLS代理：原始m3u8内容（前500字符）：\n{}",
                        m3u8Content.length() > 500 ? m3u8Content.substring(0, 500) + "..." : m3u8Content);
                String rewritten = rewriteM3u8(m3u8Content, targetUrl);
                body = rewritten.getBytes(StandardCharsets.UTF_8);
                log.debug("HLS代理：已改写m3u8分片地址，原始长度={}，改写后长度={}",
                        m3u8Content.length(), rewritten.length());
            }

            // 写入响应
            // ★ 计时总结：NVR取流耗时 + ffmpeg转码耗时 + 总耗时
            long totalElapsed = System.currentTimeMillis() - reqStartMs;
            String typeLabel = isTs ? "TS" : "M3U8";
            if (isTs) {
                String hevcTag = wasHevc ? "[HEVC→H.264]" : "[H.264]";
                log.info("HLS代理|{}|{} 取流{}ms 转码{}ms 总{}ms 大小{}B→{}B | {}",
                        typeLabel, hevcTag, fetchElapsed, transcodeElapsed,
                        totalElapsed, origLen > 0 ? origLen : body.length,
                        transcodedLen > 0 ? transcodedLen : body.length,
                        targetUrl.length() > 100 ? targetUrl.substring(targetUrl.length() - 80) : targetUrl);
            } else {
                log.info("HLS代理|{}| 总{}ms 大小{}B | {}",
                        typeLabel, totalElapsed, body.length,
                        targetUrl.length() > 100 ? targetUrl.substring(targetUrl.length() - 80) : targetUrl);
            }

            // ICC媒体服务器返回非标准Content-Type(video/octet-stream)，修正为标准MIME类型
            if (isTsSegment(targetUrl)) {
                response.setContentType("video/mp2t");
            } else {
                response.setContentType(contentType);
            }
            response.setContentLength(body.length);
            response.setHeader("Access-Control-Allow-Origin", "*");
            response.setHeader("Cache-Control", "no-cache");

            try (OutputStream os = response.getOutputStream()) {
                os.write(body);
                os.flush();
            }

        } catch (java.net.SocketTimeoutException e) {
            log.error("HLS代理：连接超时，URL：{}", targetUrl, e);
            sendError(response, HttpServletResponse.SC_GATEWAY_TIMEOUT, "连接上游服务器超时");
        } catch (java.net.ConnectException e) {
            log.error("HLS代理：连接被拒绝，URL：{}", targetUrl, e);
            sendError(response, HttpServletResponse.SC_BAD_GATEWAY, "无法连接上游服务器");
        } catch (IOException e) {
            // 客户端断开连接（用户切换通道/关闭页面/刷新）→ 正常行为，INFO级别即可
            log.info("HLS代理：客户端断开连接，URL：{}", targetUrl);
        } catch (Exception e) {
            log.error("HLS代理：请求失败，URL：{}", targetUrl, e);
            sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "代理请求失败: " + e.getMessage());
        } finally {
            if (conn != null) {
                // ★ 不主动disconnect，让HttpURLConnection复用TCP连接（keep-alive）
                // 每次请求NVR都重新建立TCP连接会额外消耗~1-2个RTT延迟
                // 对2秒/段的HLS直播流，复用连接可节省~10-20%取流耗时
            }
        }
    }

    /**
     * 判断是否为m3u8文件
     */
    private boolean isM3u8(String url, String contentType) {
        if (url != null && url.toLowerCase().endsWith(".m3u8")) {
            return true;
        }
        if (contentType != null) {
            String ct = contentType.toLowerCase();
            return ct.contains("mpegurl") || ct.contains("vnd.apple.mpegurl")
                    || ct.contains("application/x-mpegurl");
        }
        return false;
    }

    /**
     * 判断是否为TS视频分片
     */
    private boolean isTsSegment(String url) {
        return url != null && url.toLowerCase().endsWith(".ts");
    }

    /**
     * 改写m3u8文件中的分片地址
     * <p>
     * m3u8文件中每行非#开头的为TS分片路径，可能是：
     * - 相对路径：1_0.ts
     * - 绝对路径：/live/cameraid/xxx/1_0.ts
     * - 完整URL：http://192.168.14.20:7086/live/cameraid/xxx/1_0.ts
     * <p>
     * 全部改写为经过代理的相对URL：hls-proxy/{ts文件名}?url={编码后的绝对URL}
     * <p>★ 相对路径以 m3u8 响应URL 为基准解析：兼容 nginx /hltgq-device/ 前缀代理（浏览器基准带前缀）
     * 与直连部署（基准无前缀）两种模式，分片请求始终回到本控制器。
     *
     * @param m3u8Content 原始m3u8内容
     * @param m3u8Url     m3u8文件自身的完整URL
     * @return 改写后的m3u8内容
     */
    private String rewriteM3u8(String m3u8Content, String m3u8Url) {
        // 解析m3u8文件自身的base URL（用于解析相对路径）
        String baseUrl = getBaseUrl(m3u8Url);

        StringBuilder sb = new StringBuilder(m3u8Content.length() + 1024);
        String[] lines = m3u8Content.split("\\r?\\n");

        for (String line : lines) {
            // 空行、注释行、标签行直接保留
            if (line.isEmpty() || line.startsWith("#")) {
                sb.append(line).append("\n");
                continue;
            }

            // TS分片路径——需要改写
            String trimmed = line.trim();
            try {
                String absoluteUrl;
                if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                    // 已经是完整URL
                    absoluteUrl = trimmed;
                } else if (trimmed.startsWith("/")) {
                    // 绝对路径：拼接到base URL的host+port后
                    absoluteUrl = getHostBase(baseUrl) + trimmed;
                } else {
                    // 相对路径：拼接到base URL
                    absoluteUrl = baseUrl;
                    if (!absoluteUrl.endsWith("/")) {
                        absoluteUrl += "/";
                    }
                    absoluteUrl += trimmed;
                }

                String encoded = URLEncoder.encode(absoluteUrl, "UTF-8");
                // 提取原始TS文件名，作为代理URL路径的一部分，让hls.js通过.ts后缀识别分片类型
                String tsFilename = trimmed.substring(trimmed.lastIndexOf('/') + 1);
                // ★ 相对路径（非 /api/dahua/ 绝对路径）：nginx 前缀代理模式（/hltgq-device/）下
                // 浏览器以带前缀的 m3u8 URL 为基准解析，绝对路径会绕过 nginx 前缀导致 404
                sb.append("hls-proxy/").append(tsFilename).append("?url=").append(encoded).append("\n");

            } catch (UnsupportedEncodingException e) {
                // 编码失败时保留原始行
                log.warn("HLS代理：分片URL编码失败，保留原始值：{}", trimmed);
                sb.append(line).append("\n");
            }
        }

        return sb.toString();
    }

    /**
     * 获取m3u8文件URL的基础路径（去掉文件名部分）
     * <p>
     * 例如：http://host:port/live/cameraid/xxx/substream/1.m3u8
     * →    http://host:port/live/cameraid/xxx/substream/
     */
    private String getBaseUrl(String m3u8Url) {
        int lastSlash = m3u8Url.lastIndexOf('/');
        if (lastSlash > "https://".length()) {
            return m3u8Url.substring(0, lastSlash + 1);
        }
        return m3u8Url;
    }

    /**
     * 从TS分片URL中提取分片序号。
     * <p>
     * 例如：{@code .../2_seg_3.ts} → 3，{@code .../1_seg_0.ts} → 0
     *
     * @param url TS分片URL
     * @return 分片序号，解析失败返回0
     */
    private long extractSegmentSeq(String url) {
        if (url == null) return 0;
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("_seg_(\\d+)\\.ts");
        java.util.regex.Matcher m = p.matcher(url);
        if (m.find()) {
            return Long.parseLong(m.group(1));
        }
        return 0;
    }

    /**
     * 获取流基础路径键（用于HEVC检测缓存）。
     * <p>
     * 从TS分片URL中提取流目录路径，同一流的所有TS分片共享此键。
     * 例如：http://host/live/.../substream/2_seg_0.ts → http://host/live/.../substream
     *
     * @param targetUrl TS分片完整URL
     * @return 流基础路径键
     */
    private String getStreamBaseKey(String targetUrl) {
        if (targetUrl == null || targetUrl.isEmpty()) return targetUrl;
        int lastSlash = targetUrl.lastIndexOf('/');
        if (lastSlash > "https://".length()) {
            return targetUrl.substring(0, lastSlash);
        }
        return targetUrl;
    }

    /**
     * 获取URL的协议+主机+端口部分
     * <p>
     * 例如：http://192.168.14.20:7086/live/... → http://192.168.14.20:7086
     */
    private String getHostBase(String url) {
        int schemeEnd = url.indexOf("://");
        if (schemeEnd < 0) {
            return url;
        }
        int pathStart = url.indexOf('/', schemeEnd + 3);
        if (pathStart > 0) {
            return url.substring(0, pathStart);
        }
        return url;
    }

    /**
     * 从InputStream读取全部字节
     */
    private byte[] readAllBytes(InputStream is) throws Exception {
        byte[] buffer = new byte[8192];
        try (java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream()) {
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                baos.write(buffer, 0, bytesRead);
            }
            return baos.toByteArray();
        }
    }

    /**
     * 发送错误响应
     */
    private void sendError(HttpServletResponse response, int status, String message) {
        try {
            if (!response.isCommitted()) {
                response.sendError(status, message);
            } else {
                log.debug("响应已提交，无法发送错误状态 {}: {}", status, message);
            }
        } catch (Exception e) {
            log.error("发送错误响应失败", e);
        }
    }

    /**
     * 字节数组转十六进制字符串（诊断用）
     */
    private String bytesToHex(byte[] bytes, int maxLen) {
        StringBuilder sb = new StringBuilder();
        int len = Math.min(bytes.length, maxLen);
        for (int i = 0; i < len; i++) {
            sb.append(String.format("%02X ", bytes[i] & 0xFF));
        }
        return sb.toString().trim();
    }

    /**
     * 解析TS包结构，识别PAT/PMT及编码格式（诊断fragParsingError用）
     * <p>
     * TS包固定188字节，结构：
     * 1字节同步(0x47) + 2字节PID标识 + 1字节控制位 + 可变长适配域 + 净荷
     *
     * @param data TS原始字节数据
     */
    private void analyzeTsPackets(byte[] data) {
        if (data == null || data.length < 188) {
            log.warn("TS分析：数据不足188字节，无法解析");
            return;
        }

        int pmtPid = -1;  // 从PAT中解析出的PMT PID
        int packetCount = Math.min(data.length / 188, 10); // 最多分析前10个包

        for (int pkt = 0; pkt < packetCount; pkt++) {
            int offset = pkt * 188;
            if (offset + 188 > data.length) break;

            // 检查同步字节
            int syncByte = data[offset] & 0xFF;
            if (syncByte != 0x47) {
                log.debug("TS分析：第{}包同步字节异常 0x{}（期望0x47），可能非标准TS", pkt, String.format("%02X", syncByte));
                continue;
            }

            // 解析PID：字节1的低5位 + 字节2的8位
            int pid = ((data[offset + 1] & 0x1F) << 8) | (data[offset + 2] & 0xFF);

            // 解析控制位：字节3的高2位
            int adaptationFieldControl = (data[offset + 3] & 0x30) >> 4;
            boolean hasAdaptation = (adaptationFieldControl & 0x2) != 0;
            boolean hasPayload = (adaptationFieldControl & 0x1) != 0;

            // 计算净荷起始位置
            int payloadOffset = offset + 4; // 跳过4字节包头
            if (hasAdaptation) {
                int adaptationLen = data[payloadOffset] & 0xFF;
                payloadOffset += 1 + adaptationLen; // 跳过适配域长度字节+适配域
            }

            if (pid == 0 && hasPayload) {
                // PAT包 (Program Association Table)
                parsePat(data, payloadOffset, offset);

                // 从PAT中提取PMT PID（跳过pointer_field和table_id等）
                if (payloadOffset + 8 <= offset + 188) {
                    int pointerField = data[payloadOffset] & 0xFF;
                    int patStart = payloadOffset + 1 + pointerField;
                    if (patStart + 12 <= offset + 188) {
                        // table_id(1) + section_length(2) + transport_stream_id(2) + version(1) + section_number(1) + last_section_number(1)
                        // program_number(2) + PMT_PID(2)
                        int programNum = ((data[patStart + 8] & 0xFF) << 8) | (data[patStart + 9] & 0xFF);
                        pmtPid = ((data[patStart + 10] & 0x1F) << 8) | (data[patStart + 11] & 0xFF);
                        log.debug("TS分析：PAT中 program_number={}, PMT_PID=0x{} ({})",
                                programNum, String.format("%04X", pmtPid), pmtPid);
                    }
                }
            } else if (pmtPid > 0 && pid == pmtPid && hasPayload) {
                // PMT包 (Program Map Table)
                parsePmt(data, payloadOffset, offset);
                break; // 找到PMT后停止分析
            }
        }
    }

    /**
     * 解析PAT包
     */
    private void parsePat(byte[] data, int payloadOffset, int packetOffset) {
        int pointerField = data[payloadOffset] & 0xFF;
        int tableStart = payloadOffset + 1 + pointerField;
        if (tableStart + 3 > data.length) return;
        int tableId = data[tableStart] & 0xFF;
        int sectionLength = ((data[tableStart + 1] & 0x0F) << 8) | (data[tableStart + 2] & 0xFF);
        log.debug("TS分析：PAT tableId=0x{}, sectionLength={}, pointerField={}",
                String.format("%02X", tableId), sectionLength, pointerField);
    }

    /**
     * 解析PMT包，提取编码格式信息
     */
    private void parsePmt(byte[] data, int payloadOffset, int packetOffset) {
        int pointerField = data[payloadOffset] & 0xFF;
        int tableStart = payloadOffset + 1 + pointerField;
        if (tableStart + 12 > data.length) return;

        int tableId = data[tableStart] & 0xFF;
        int sectionLength = ((data[tableStart + 1] & 0x0F) << 8) | (data[tableStart + 2] & 0xFF);

        // PCR PID
        int pcrPid = ((data[tableStart + 8] & 0x1F) << 8) | (data[tableStart + 9] & 0xFF);
        int programInfoLength = ((data[tableStart + 10] & 0x0F) << 8) | (data[tableStart + 11] & 0xFF);

        log.debug("TS分析：PMT tableId=0x{}, sectionLength={}, PCR_PID=0x{}, programInfoLength={}",
                String.format("%02X", tableId), sectionLength, String.format("%04X", pcrPid), programInfoLength);

        // 解析流描述（在program_info之后）
        int streamOffset = tableStart + 12 + programInfoLength;
        int sectionEnd = tableStart + 3 + sectionLength - 4; // 减去CRC32的4字节

        while (streamOffset + 5 <= sectionEnd && streamOffset + 5 <= data.length) {
            int streamType = data[streamOffset] & 0xFF;
            int elementaryPid = ((data[streamOffset + 1] & 0x1F) << 8) | (data[streamOffset + 2] & 0xFF);
            int esInfoLength = ((data[streamOffset + 3] & 0x0F) << 8) | (data[streamOffset + 4] & 0xFF);

            String codecName = getStreamTypeName(streamType);
            log.debug("TS分析：PMT流 streamType=0x{} ({}) elementaryPID=0x{} ES_info_length={}",
                    String.format("%02X", streamType), codecName,
                    String.format("%04X", elementaryPid), esInfoLength);

            streamOffset += 5 + esInfoLength;
        }
    }

    /**
     * 根据MPEG-TS stream_type返回编码格式名称
     */
    private String getStreamTypeName(int streamType) {
        switch (streamType) {
            case 0x01: return "MPEG-1 Video";
            case 0x02: return "MPEG-2 Video";
            case 0x03: return "MPEG-1 Audio";
            case 0x04: return "MPEG-2 Audio";
            case 0x06: return "PES private data";
            case 0x0F: return "AAC Audio (ADTS)";
            case 0x10: return "MPEG-4 Video";
            case 0x11: return "AAC Audio (LATM)";
            case 0x1B: return "H.264 Video (AVC)";
            case 0x1C: return "AAC Audio";
            case 0x24: return "H.265 Video (HEVC)";
            case 0x42: return "CAVS Video";
            default:
                // 大华私有格式常见值
                if (streamType == 0x90) return "大华私有视频";
                if (streamType == 0x91) return "大华私有音频";
                if (streamType == 0x92) return "大华私有数据";
                return "未知编码(" + String.format("0x%02X", streamType) + ")";
        }
    }

    // ==================== HEVC描述符注入（修复fragParsingError根因） ====================

    /**
     * 注入HEVC描述符到TS的PMT中。
     * <p>
     * <b>问题背景：</b>NVR输出的H.265(HEVC)视频流，PMT中streamType=0x24但ES_info_length=0，
     * 缺少HEVC描述符（tag 0x05，含HVCC解码器配置），导致hls.js transmuxer无法识别视频轨，
     * 报"Found no media in msn N"。
     * <p>
     * <b>修复流程：</b>
     * <ol>
     *   <li>解析TS找到PAT→PMT→视频PID</li>
     *   <li>从视频PES流中提取VPS/SPS/PPS NAL单元</li>
     *   <li>构建HEVC Decoder Configuration Record (HVCC)</li>
     *   <li>修改PMT包：减少适配域填充字节→扩展PMT section→插入HEVC描述符→重算CRC32</li>
     * </ol>
     *
     * @param tsData 原始TS数据
     * @return 修改后的TS数据（若无需修改则返回原数组）
     */
    private byte[] injectHevcDescriptorIfNeeded(byte[] tsData) {
        final int TS_PKT = 188;
        if (tsData == null || tsData.length < TS_PKT * 2) return tsData;

        // ---- Step 1：遍历TS包找PAT和PMT ----
        int pmtPid = -1;
        int pmtPktOffset = -1;
        int maxPkts = Math.min(tsData.length / TS_PKT, 10);

        for (int i = 0; i < maxPkts; i++) {
            int off = i * TS_PKT;
            if ((tsData[off] & 0xFF) != 0x47) continue;
            int pid = ((tsData[off + 1] & 0x1F) << 8) | (tsData[off + 2] & 0xFF);

            if (pid == 0) {
                // PAT包
                int afc = (tsData[off + 3] & 0x30) >> 4;
                int payloadOff = off + 4;
                if ((afc & 2) != 0) payloadOff += 1 + (tsData[payloadOff] & 0xFF);
                if ((afc & 1) == 0 || payloadOff + 12 > off + TS_PKT) continue;
                int ptrField = tsData[payloadOff] & 0xFF;
                int tableOff = payloadOff + 1 + ptrField;
                if (tableOff + 12 > off + TS_PKT) continue;
                pmtPid = ((tsData[tableOff + 10] & 0x1F) << 8) | (tsData[tableOff + 11] & 0xFF);
            }

            if (pmtPid > 0 && pid == pmtPid) {
                pmtPktOffset = off;
                break;
            }
        }

        if (pmtPid < 0 || pmtPktOffset < 0) {
            log.debug("HEVC注入：未找到PMT包，跳过");
            return tsData;
        }

        // ---- Step 2：解析PMT，定位HEVC流条目 ----
        int afc = (tsData[pmtPktOffset + 3] & 0x30) >> 4;
        int payloadOff = pmtPktOffset + 4;
        if ((afc & 2) != 0) payloadOff += 1 + (tsData[payloadOff] & 0xFF);
        if ((afc & 1) == 0) { log.debug("HEVC注入：PMT包无净荷"); return tsData; }

        int ptrField = tsData[payloadOff] & 0xFF;
        int tableOff = payloadOff + 1 + ptrField;
        int sectionLength = ((tsData[tableOff + 1] & 0x0F) << 8) | (tsData[tableOff + 2] & 0xFF);
        int crcOffset = tableOff + 3 + sectionLength - 4; // CRC32在section末尾4字节
        int progInfoLen = ((tsData[tableOff + 10] & 0x0F) << 8) | (tsData[tableOff + 11] & 0xFF);
        int streamOff = tableOff + 12 + progInfoLen;

        int videoPid = -1;
        int esInfoLenOff = -1; // HEVC流ES_info_length字段的偏移
        int sectionEnd = crcOffset;

        while (streamOff + 5 <= sectionEnd && streamOff + 5 < pmtPktOffset + TS_PKT) {
            int streamType = tsData[streamOff] & 0xFF;
            int elemPid = ((tsData[streamOff + 1] & 0x1F) << 8) | (tsData[streamOff + 2] & 0xFF);
            int esInfoLen = ((tsData[streamOff + 3] & 0x0F) << 8) | (tsData[streamOff + 4] & 0xFF);

            if (streamType == 0x24) { // H.265/HEVC
                videoPid = elemPid;
                if (esInfoLen == 0) esInfoLenOff = streamOff + 3;
            }
            streamOff += 5 + esInfoLen;
        }

        if (esInfoLenOff < 0 || videoPid < 0) {
            log.debug("HEVC注入：PMT中HEVC流已有描述符或未找到HEVC流，跳过");
            return tsData;
        }

        // ---- Step 3：从视频PES中提取VPS/SPS/PPS NAL单元 ----
        byte[] vps = null, sps = null, pps = null;
        int scanPkts = Math.min(tsData.length / TS_PKT, 30);

        pktLoop:
        for (int i = 1; i < scanPkts; i++) {
            int off = i * TS_PKT;
            if ((tsData[off] & 0xFF) != 0x47) continue;
            int pid = ((tsData[off + 1] & 0x1F) << 8) | (tsData[off + 2] & 0xFF);
            if (pid != videoPid) continue;
            // 需要PUSI=1（PES包起始）
            if ((tsData[off + 1] & 0x40) == 0) continue;

            int afc2 = (tsData[off + 3] & 0x30) >> 4;
            int pos = off + 4;
            if ((afc2 & 2) != 0) pos += 1 + (tsData[pos] & 0xFF);
            if ((afc2 & 1) == 0) continue;

            // 在PES净荷中查找00 00 01 E0（视频PES起始码）
            int pesStart = pos;
            while (pesStart + 4 <= off + TS_PKT) {
                if ((tsData[pesStart] & 0xFF) == 0x00
                        && (tsData[pesStart + 1] & 0xFF) == 0x00
                        && (tsData[pesStart + 2] & 0xFF) == 0x01
                        && (tsData[pesStart + 3] & 0xFF) == 0xE0) {
                    // 视频PES头：起始码(3) + stream_id(1) + PES_length(2) + 可选PES头
                    // 可选PES头长度在偏移9处
                    if (pesStart + 9 <= off + TS_PKT) {
                        int pesHdrLen = tsData[pesStart + 8] & 0xFF;
                        int esStart = pesStart + 9 + pesHdrLen;

                        // 扫描NAL单元（4字节起始码：00 00 00 01）
                        vps = sps = pps = null; // 重置以防跨包
                        int nalOff = esStart;
                        while (nalOff + 5 <= off + TS_PKT) {
                            if ((tsData[nalOff] & 0xFF) == 0x00
                                    && (tsData[nalOff + 1] & 0xFF) == 0x00
                                    && (tsData[nalOff + 2] & 0xFF) == 0x00
                                    && (tsData[nalOff + 3] & 0xFF) == 0x01) {
                                // NAL类型：(header_byte >> 1) & 0x3F
                                int nalType = ((tsData[nalOff + 4] & 0xFF) >> 1) & 0x3F;

                                // 找NAL末尾（下一个起始码或TS包末尾）
                                int nalEnd = nalOff + 4;
                                while (nalEnd + 3 < off + TS_PKT) {
                                    if ((tsData[nalEnd] & 0xFF) == 0x00
                                            && (tsData[nalEnd + 1] & 0xFF) == 0x00) {
                                        int b2 = tsData[nalEnd + 2] & 0xFF;
                                        int b3 = tsData[nalEnd + 3] & 0xFF;
                                        if ((b2 == 0x00 && b3 == 0x01) || (b2 == 0x01)) break;
                                    }
                                    nalEnd++;
                                }

                                int nalLen = nalEnd - (nalOff + 4);
                                if (nalLen > 0 && nalLen < 1024) {
                                    byte[] nal = new byte[nalLen];
                                    System.arraycopy(tsData, nalOff + 4, nal, 0, nalLen);
                                    if (nalType == 32 && vps == null) vps = nal;
                                    else if (nalType == 33 && sps == null) sps = nal;
                                    else if (nalType == 34 && pps == null) pps = nal;
                                }
                                nalOff = nalEnd;
                            } else {
                                nalOff++;
                            }
                        }

                        if (vps != null && sps != null && pps != null) break pktLoop;
                    }
                }
                pesStart++;
            }
        }

        if (vps == null || sps == null || pps == null) {
            log.warn("HEVC注入：未能提取完整NAL单元 vps={} sps={} pps={}",
                    vps != null, sps != null, pps != null);
            return tsData;
        }
        log.debug("HEVC注入：提取NAL成功 VPS={}B SPS={}B PPS={}B", vps.length, sps.length, pps.length);

        // ---- Step 4：构建HVCC ----
        byte[] hvcc = buildHvcc(vps, sps, pps);
        log.debug("HEVC注入：HVCC={}B", hvcc.length);

        // ---- Step 5：修改PMT ----
        return modifyPmtWithHevc(tsData, pmtPktOffset, esInfoLenOff, videoPid, hvcc);
    }

    /**
     * 构建HEVC Decoder Configuration Record（ISO/IEC 14496-15）
     * <p>
     * HVCC包含解码器初始化所需的所有参数，hls.js需要通过它构造MSE codec字符串
     * 并初始化浏览器HEVC解码器。
     * <p>
     * 从SPS中提取profile/level/constraint等信息，连同VPS/SPS/PPS NAL数组一起打包。
     *
     * @param vps VPS NAL单元（不含起始码）
     * @param sps SPS NAL单元（不含起始码）
     * @param pps PPS NAL单元（不含起始码）
     * @return HVCC字节数组
     */
    private byte[] buildHvcc(byte[] vps, byte[] sps, byte[] pps) {
        // HEVC NAL头占2字节：sps[0] forbidden/type, sps[1] layer/temporal
        // SPS数据从sps[2]开始
        // sps[2]: sps_video_parameter_set_id(4) | sps_max_sub_layers_minus1(3) | temporal_id_nesting(1)
        // sps[3]: general_profile_space(2) | general_tier_flag(1) | general_profile_idc(5)
        if (sps.length < 15) {
            log.warn("HEVC注入：SPS太短({}B)，使用默认profile/level", sps.length);
        }
        // 安全的SPS字段提取，长度不足时使用默认值
        int profileSpace = (sps.length > 3) ? ((sps[3] >> 6) & 0x03) : 0;
        int tierFlag = (sps.length > 3) ? ((sps[3] >> 5) & 0x01) : 0;
        int profileIdc = (sps.length > 3) ? (sps[3] & 0x1F) : 1;

        // sps[4..7] = general_profile_compatibility_flags (32 bits)
        int compatFlags = 0;
        if (sps.length > 7) {
            compatFlags = ((sps[4] & 0xFF) << 24) | ((sps[5] & 0xFF) << 16)
                    | ((sps[6] & 0xFF) << 8) | (sps[7] & 0xFF);
        }

        // sps[8..13] = general_constraint_indicator_flags (48 bits)
        // sps[14] = general_level_idc
        int levelIdc = (sps.length > 14) ? (sps[14] & 0xFF) : 0x5D; // default level 5.1

        // 使用默认chroma/bit depth值（绝大多数监控流为8-bit 4:2:0）
        int chromaFormatIdc = 1;    // 4:2:0
        int bitDepthLumaMinus8 = 0; // 8-bit
        int bitDepthChromaMinus8 = 0;
        int minSpatialSeg = 0;

        // 计算HVCC总大小
        int fixedHeader = 23;
        int arrayVps = 5 + vps.length;
        int arraySps = 5 + sps.length;
        int arrayPps = 5 + pps.length;
        byte[] hvcc = new byte[fixedHeader + arrayVps + arraySps + arrayPps];
        int off = 0;

        // configurationVersion = 1
        hvcc[off++] = 0x01;

        // general_profile_space(2) | general_tier_flag(1) | general_profile_idc(5)
        hvcc[off++] = (byte) ((profileSpace << 6) | (tierFlag << 5) | profileIdc);

        // general_profile_compatibility_flags (32 bits)
        hvcc[off++] = (byte) (compatFlags >> 24);
        hvcc[off++] = (byte) (compatFlags >> 16);
        hvcc[off++] = (byte) (compatFlags >> 8);
        hvcc[off++] = (byte) (compatFlags);

        // general_constraint_indicator_flags (48 bits)
        for (int i = 0; i < 6; i++) {
            hvcc[off++] = (sps.length > 8 + i) ? sps[8 + i] : 0x00;
        }

        // general_level_idc
        hvcc[off++] = (byte) levelIdc;

        // reserved(4 bits=0xF) | min_spatial_segmentation_idc(12 bits)
        hvcc[off++] = (byte) (0xF0 | ((minSpatialSeg >> 8) & 0x0F));
        hvcc[off++] = (byte) (minSpatialSeg & 0xFF);

        // reserved(6 bits=0x3F) | parallelismType(2 bits=0)
        hvcc[off++] = (byte) 0xFC;

        // reserved(6 bits=0x3F) | chromaFormat(2 bits)
        hvcc[off++] = (byte) (0xFC | (chromaFormatIdc & 0x03));

        // reserved(5 bits=0x1F) | bitDepthLumaMinus8(3 bits)
        hvcc[off++] = (byte) (0xF8 | (bitDepthLumaMinus8 & 0x07));

        // reserved(5 bits=0x1F) | bitDepthChromaMinus8(3 bits)
        hvcc[off++] = (byte) (0xF8 | (bitDepthChromaMinus8 & 0x07));

        // avgFrameRate (16 bits) = 0 (unknown)
        hvcc[off++] = 0x00;
        hvcc[off++] = 0x00;

        // constantFrameRate(2)|numTemporalLayers(3)|temporalIdNested(1)|lengthSizeMinusOne(2)
        // 00_001_0_11 = 4字节长度前缀
        hvcc[off++] = 0x0B;

        // numOfArrays = 3 (VPS, SPS, PPS)
        hvcc[off++] = 0x03;

        // ---- VPS array ----
        hvcc[off++] = (byte) 0xA0; // completeness=1, reserved=0, NAL_type=32
        hvcc[off++] = 0x00;
        hvcc[off++] = 0x01; // numNalus = 1
        hvcc[off++] = (byte) (vps.length >> 8);
        hvcc[off++] = (byte) (vps.length & 0xFF);
        System.arraycopy(vps, 0, hvcc, off, vps.length);
        off += vps.length;

        // ---- SPS array ----
        hvcc[off++] = (byte) 0xA1; // completeness=1, reserved=0, NAL_type=33
        hvcc[off++] = 0x00;
        hvcc[off++] = 0x01;
        hvcc[off++] = (byte) (sps.length >> 8);
        hvcc[off++] = (byte) (sps.length & 0xFF);
        System.arraycopy(sps, 0, hvcc, off, sps.length);
        off += sps.length;

        // ---- PPS array ----
        hvcc[off++] = (byte) 0xA2; // completeness=1, reserved=0, NAL_type=34
        hvcc[off++] = 0x00;
        hvcc[off++] = 0x01;
        hvcc[off++] = (byte) (pps.length >> 8);
        hvcc[off++] = (byte) (pps.length & 0xFF);
        System.arraycopy(pps, 0, hvcc, off, pps.length);

        return hvcc;
    }

    /**
     * 修改PMT TS包：减少适配域填充→扩展section→插入HEVC描述符→更新CRC32。
     * <p>
     * PMT包结构（修改前后对比）：
     * <pre>
     * [4B头] [适配域长度+N×填充0xFF] [pointer_field] [PMT section] [CRC32]
     *   ↓ 减少适配域填充字节来腾出空间
     * [4B头] [适配域长度+(N-Δ)×填充0xFF] [ptr] [PMT section+HEVC descriptor] [新CRC32]
     * </pre>
     *
     * @param tsData      完整TS数据
     * @param pmtPktOffset PMT包在tsData中的起始偏移
     * @param esInfoOff    HEVC流条目的ES_info_length字段偏移（2字节）
     * @param videoPid     视频PID
     * @param hvcc         HEVC Decoder Configuration Record
     * @return 修改后的TS数据
     */
    private byte[] modifyPmtWithHevc(byte[] tsData, int pmtPktOffset,
                                      int esInfoOff, int videoPid, byte[] hvcc) {
        final int TS_PKT = 188;

        // HEVC descriptor: tag(1) + length(1) + hvcc
        int descLen = 2 + hvcc.length;

        int afc = (tsData[pmtPktOffset + 3] & 0x30) >> 4;
        int payloadOff = pmtPktOffset + 4;
        int adaptLenByte = -1;
        if ((afc & 2) != 0) {
            adaptLenByte = payloadOff;
            payloadOff += 1 + (tsData[payloadOff] & 0xFF);
        }

        int ptrField = tsData[payloadOff] & 0xFF;
        int tableOff = payloadOff + 1 + ptrField;

        // sectionLength（不含table_id和自身2字节，不含CRC32）
        int oldSectionLen = ((tsData[tableOff + 1] & 0x0F) << 8) | (tsData[tableOff + 2] & 0xFF);
        // PMT section结束位置（含CRC32）
        int sectionEnd = tableOff + 3 + oldSectionLen;

        // 新PMT section：旧section + HEVC描述符
        // 旧ES_info_length=0，新ES_info_length = descLen
        int newSectionLen = oldSectionLen + descLen;

        // 检查PMT包是否有足够空间
        // 适配域填充字节数（不含适配域长度字节）
        int stuffingBytes = 0;
        if (adaptLenByte >= 0) {
            int totalAdapt = 1 + (tsData[adaptLenByte] & 0xFF); // 适配域长度字节 + 适配域
            // 适配域基本开销（不含填充）：1(length) + 1(flags) = 2
            // 加上可选字段... 简化处理：填充字节 = 适配域总长 - 实际有用字节
            // 适配域最小开销约2字节，其余为填充（0xFF）
            int minAdaptOverhead = 2; // 至少有length+flags
            if ((tsData[adaptLenByte] & 0xFF) > minAdaptOverhead) {
                stuffingBytes = (tsData[adaptLenByte] & 0xFF) - minAdaptOverhead;
            }
        }

        // 需要增加的空间
        int neededSpace = descLen;
        if (stuffingBytes < neededSpace) {
            log.warn("HEVC注入：PMT适配域填充不足 need={} have={}，无法注入", neededSpace, stuffingBytes);
            return tsData;
        }

        // 复制tsData（因为我们只修改一个包）
        byte[] result = tsData.clone();

        // ---- 1. 缩减适配域填充 ----
        if (adaptLenByte >= 0) {
            int oldAdaptLen = result[adaptLenByte] & 0xFF;
            int newAdaptLen = oldAdaptLen - descLen;
            result[adaptLenByte] = (byte) newAdaptLen;

            // 移位净荷：将payloadOff之后的数据向左移动descLen字节
            // payloadOff指向的是pointer_field（也是适配域后的第一个净荷字节）
            int newPayloadOff = payloadOff - descLen;
            int payloadSize = sectionEnd - payloadOff; // 从ptr_field到section结尾
            System.arraycopy(result, payloadOff, result, newPayloadOff, payloadSize);

            // 更新偏移引用
            payloadOff = newPayloadOff;
            tableOff -= descLen;
            sectionEnd -= descLen;
            esInfoOff -= descLen;
        }

        // ---- 2. 更新ES_info_length ----
        result[esInfoOff] = (byte) ((descLen >> 8) & 0x0F);
        result[esInfoOff + 1] = (byte) (descLen & 0xFF);

        // ---- 3. 在HEVC流条目的描述符区域写入HEVC descriptor ----
        // ES_info_length字段后就是描述符数据，descLen字节空间
        // 即从 esInfoOff+2 到 esInfoOff+2+descLen-1
        int descStart = esInfoOff + 2;
        result[descStart] = 0x05;              // HEVC descriptor tag
        result[descStart + 1] = (byte) hvcc.length; // descriptor length (仅HVCC部分)
        System.arraycopy(hvcc, 0, result, descStart + 2, hvcc.length);

        // ---- 4. 更新section_length ----
        result[tableOff + 1] = (byte) (((newSectionLen >> 8) & 0x0F) | (result[tableOff + 1] & 0xF0));
        result[tableOff + 2] = (byte) (newSectionLen & 0xFF);

        // 新CRC32位置
        int newCrcOff = tableOff + 3 + newSectionLen - 4;

        // ---- 5. 重算CRC32 ----
        // section中需要CRC校验的数据：从table_id到section末尾（不含CRC32本身）
        int sectionDataLen = newCrcOff - tableOff;
        int crc = crc32Mpeg(result, tableOff, sectionDataLen);
        result[newCrcOff] = (byte) (crc >> 24);
        result[newCrcOff + 1] = (byte) (crc >> 16);
        result[newCrcOff + 2] = (byte) (crc >> 8);
        result[newCrcOff + 3] = (byte) (crc);

        // ---- 6. 将新section后的字节清零（旧CRC残留等） ----
        int clearStart = newCrcOff + 4;
        int clearEnd = pmtPktOffset + TS_PKT;
        if (clearStart < clearEnd) {
            // 用0xFF填充（作为适配域填充或废弃数据）
            // 注意：PMT在TS包末尾，原section后的数据已无意义
            for (int i = clearStart; i < clearEnd; i++) {
                result[i] = (byte) 0xFF;
            }
        }

        log.debug("HEVC注入：PMT修改完成 oldSectionLen={} newSectionLen={} descLen={} crc=0x{}",
                oldSectionLen, newSectionLen, descLen, String.format("%08X", crc));
        return result;
    }

    /**
     * CRC32计算（MPEG-2标准，多项式0x04C11DB7）
     */
    private int crc32Mpeg(byte[] data, int offset, int length) {
        int crc = 0xFFFFFFFF;
        for (int i = 0; i < length; i++) {
            crc ^= (data[offset + i] & 0xFF) << 24;
            for (int j = 0; j < 8; j++) {
                if ((crc & 0x80000000) != 0) {
                    crc = (crc << 1) ^ 0x04C11DB7;
                } else {
                    crc <<= 1;
                }
            }
        }
        return crc;
    }

    // ==================== ffmpeg H.265→H.264 转码 ====================

    /**
     * 扫描TS流检测是否包含HEVC（H.265）视频。
     * <p>
     * 遍历全部TS包查找PAT→PMT，检查PMT中是否有streamType=0x24的流条目。
     * 不再限制扫描前N个包，因为HLS分片不一定在开头包含PAT/PMT。
     *
     * @param tsData TS字节数据
     * @return true-包含HEVC视频流
     */
    private boolean containsHevc(byte[] tsData) {
        final int TS_PKT = 188;
        if (tsData == null || tsData.length < TS_PKT * 2) return false;

        int pmtPid = -1;
        int totalPkts = tsData.length / TS_PKT;

        for (int i = 0; i < totalPkts; i++) {
            int off = i * TS_PKT;
            if ((tsData[off] & 0xFF) != 0x47) continue;
            int pid = ((tsData[off + 1] & 0x1F) << 8) | (tsData[off + 2] & 0xFF);

            if (pid == 0) {
                // PAT：提取PMT PID
                int afc = (tsData[off + 3] & 0x30) >> 4;
                int pOff = off + 4;
                if ((afc & 2) != 0) pOff += 1 + (tsData[pOff] & 0xFF);
                if ((afc & 1) == 0 || pOff + 12 > off + TS_PKT) continue;
                int ptr = tsData[pOff] & 0xFF;
                int tOff = pOff + 1 + ptr;
                if (tOff + 12 <= off + TS_PKT) {
                    pmtPid = ((tsData[tOff + 10] & 0x1F) << 8) | (tsData[tOff + 11] & 0xFF);
                }
            }

            if (pmtPid > 0 && pid == pmtPid) {
                // PMT：检查流类型
                int afc = (tsData[off + 3] & 0x30) >> 4;
                int pOff = off + 4;
                if ((afc & 2) != 0) pOff += 1 + (tsData[pOff] & 0xFF);
                // 修复：PMT包无净荷时不立即返回false，继续扫描后续TS包
                if ((afc & 1) == 0) continue;
                int ptr = tsData[pOff] & 0xFF;
                int tOff = pOff + 1 + ptr;
                int secLen = ((tsData[tOff + 1] & 0x0F) << 8) | (tsData[tOff + 2] & 0xFF);
                int progInfo = ((tsData[tOff + 10] & 0x0F) << 8) | (tsData[tOff + 11] & 0xFF);
                int sOff = tOff + 12 + progInfo;
                int sEnd = tOff + 3 + secLen - 4; // minus CRC
                while (sOff + 5 <= sEnd && sOff + 5 < off + TS_PKT) {
                    int streamType = tsData[sOff] & 0xFF;
                    if (streamType == 0x24) return true; // HEVC found
                    int esInfo = ((tsData[sOff + 3] & 0x0F) << 8) | (tsData[sOff + 4] & 0xFF);
                    sOff += 5 + esInfo;
                }
                // PMT已找到并解析完毕，未发现HEVC流类型 → 此流非HEVC
                return false;
            }
        }
        // PAT/PMT未在段中找到（可能此分片不含PSI表）→ 无法判断，返回false
        return false;
    }

    /**
     * 使用ffmpeg将HEVC（H.265）TS流转码为H.264 TS流。
     * <p>
     * 通过管道方式：Java进程将原始TS写入ffmpeg的stdin，
     * 从ffmpeg的stdout读取转码后的TS。每个分片独立启动新进程。
     * <p>
     * ffmpeg参数说明：
     * <ul>
     *   <li>{@code -flush_packets 1} — 确保stdout完整刷新，避免分片末尾帧丢失</li>
     *   <li>{@code -c:v libx264} — 视频编码为H.264</li>
     *   <li>{@code -preset ultrafast} — 极速编码（比veryfast快30-50%），监控画面优先延迟</li>
     *   <li>{@code -tune zerolatency} — 零延迟调优：禁用B帧、降低前瞻、切片优化</li>
     *   <li>{@code -crf 28} — 恒定质量因子，28平衡清晰度与带宽（CRF 35太糊）</li>
     *   <li>{@code -maxrate 2000k -bufsize 4000k} — VBV码率控制，防止突发大流量</li>
     *   <li>{@code -g 60} — GOP=60帧，降低低动态监控画面关键帧频率</li>
     *   <li>{@code -muxdelay 0} — TS复用零延迟，适配直播流实时传输</li>
     *   <li>{@code -c:a copy} — 音频直接复制不重新编码</li>
     * </ul>
     *
     * @param tsData   原始HEVC TS字节数据
     * @param ptsOffset PTS偏移量（秒），用于跨ffmpeg进程的PTS连续性
     * @return 转码后的H.264 TS字节数据（失败时返回原始数据）
     */
    private byte[] transcodeHevcToH264(byte[] tsData, double ptsOffset) {
        long startTime = System.currentTimeMillis();
        Process process = null;
        try {
            // ★ 动态构建命令：按需加入 -output_ts_offset 实现跨进程PTS连续
            java.util.List<String> cmd = new java.util.ArrayList<>(java.util.Arrays.asList(
                    "ffmpeg",
                    "-fflags", "+genpts",
                    "-err_detect", "ignore_err",
                    "-flush_packets", "1",
                    "-f", "mpegts",
                    "-i", "pipe:0",
                    "-c:v", "libx264",
                    "-preset", "ultrafast",
                    "-tune", "zerolatency",
                    "-crf", "28",
                    "-maxrate", "2000k",
                    "-bufsize", "4000k",
                    "-g", "60",
                    "-sc_threshold", "0",
                    "-c:a", "copy",
                    "-f", "mpegts",
                    "-muxdelay", "0"
            ));
            if (ptsOffset > 0) {
                cmd.add("-output_ts_offset");
                cmd.add(String.valueOf(ptsOffset));
            }
            cmd.add("pipe:1");
            ProcessBuilder pb = new ProcessBuilder(cmd);
            // ★ 捕获stderr用于诊断（之前丢弃到NUL，丢失了ffmpeg错误信息）
            process = pb.start();

            // 用独立线程写入stdin（避免管道死锁）
            final Process finalProcess = process;
            Thread writer = new Thread(() -> {
                try (java.io.OutputStream os = finalProcess.getOutputStream()) {
                    os.write(tsData);
                    os.flush();
                } catch (Exception ignored) {
                }
            }, "ffmpeg-stdin-writer");
            writer.start();

            // 静默消费stderr，仅防止ffmpeg管道阻塞
            // 不再保存stderr内容——proxy级计时日志已覆盖诊断需求，省去ByteArrayOutputStream+字符串拼接开销
            Thread stderrReader = new Thread(() -> {
                try {
                    byte[] buf = new byte[1024];
                    java.io.InputStream es = finalProcess.getErrorStream();
                    while (es.read(buf) != -1) { /* discard */ }
                    es.close();
                } catch (Exception ignored) {
                }
            }, "ffmpeg-stderr");
            stderrReader.start();

            // 读取stdout：转码后的TS数据
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream(tsData.length);
            try (java.io.InputStream is = process.getInputStream()) {
                byte[] buf = new byte[8192];
                int len;
                while ((len = is.read(buf)) != -1) {
                    baos.write(buf, 0, len);
                }
            }

            writer.join(5000);
            boolean finished = process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
            stderrReader.join(2000);

            byte[] result = baos.toByteArray();
            if (result.length > 0) {
                if (!finished) {
                    log.warn("ffmpeg转码未正常退出 耗时{}ms 输入{}B→输出{}B",
                            System.currentTimeMillis() - startTime, tsData.length, result.length);
                }
                return result;
            } else {
                log.warn("ffmpeg输出为空 耗时{}ms 输入{}B",
                        System.currentTimeMillis() - startTime, tsData.length);
            }
        } catch (Exception e) {
            log.error("ffmpeg转码异常: {}", e.getMessage());
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
        log.warn("ffmpeg转码失败，回退原始数据");
        return tsData;
    }
}
