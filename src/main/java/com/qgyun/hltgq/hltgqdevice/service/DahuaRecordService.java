package com.qgyun.hltgq.hltgqdevice.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.dahuatech.hutool.http.Method;
import com.dahuatech.icc.exception.ClientException;
import com.dahuatech.icc.oauth.model.v202010.GeneralResponse;
import com.dahuatech.icc.oauth.utils.HttpUtils;
import com.qgyun.hltgq.hltgqdevice.model.RecordSegment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.annotation.Resource;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 大华ICC历史录像服务
 * <p>
 * 对接 ICC 录像查询与回放接口（官方文档：录像回放章节）：
 * <ul>
 *   <li>月录像状态：{@code /evo-apigw/admin/API/SS/Record/GetChannelMonthRecordStatus}</li>
 *   <li>录像信息列表：{@code /evo-apigw/admin/API/SS/Record/QueryRecords}（按文件分段）</li>
 *   <li>HLS录像回放流：{@code /evo-apigw/admin/API/video/stream/record}（type=hls，V5.0.12+）</li>
 * </ul>
 * <p>
 * 关键约束（官方文档）：
 * <ul>
 *   <li>录像按文件存储，回放不支持跨文件——须先查录像段，播放时段落在同一条段内；</li>
 *   <li>回放流接口返回的 url 需自行拼接 ?token=AccessToken，且 7086 端口仅内网可达；</li>
 *   <li>HLS/RTMP 回放仅支持 H.264 编码设备（HEVC 录像可由代理层 ffmpeg 转码兜底）。</li>
 * </ul>
 *
 * @author hltgq-device
 */
@Slf4j
@Service
public class DahuaRecordService {

    /** 录像查询/回放相关 API 成功码 */
    private static final String SUCCESS_CODE = "1000";

    /** 无录像错误码（官方文档：The record was null） */
    private static final String NO_RECORD_CODE = "2239";

    @Resource
    private DahuaAuthService authService;

    @Resource
    private DahuaVideoService videoService;

    /**
     * 查询通道指定月份的每日录像存在状态（前端月历标记用）
     *
     * @param channelId    视频通道编码（如 1000230$1$0$0）
     * @param recordSource 录像来源：1=全部，2=设备，3=中心
     * @param month        月份，格式 yyyyMM
     * @return days 字符串（逗号分隔，1=有录像，0=无录像，按日期顺序）；失败返回 null
     */
    public String getMonthRecordStatus(String channelId, String recordSource, String month) {
        try {
            Map<String, Object> body = new HashMap<>();
            Map<String, Object> data = new HashMap<>();
            data.put("channelId", channelId);
            data.put("recordSource", recordSource);
            data.put("month", month);
            body.put("data", data);

            GeneralResponse gr = HttpUtils.executeJson(
                    "/evo-apigw/admin/API/SS/Record/GetChannelMonthRecordStatus",
                    body, null, Method.POST,
                    authService.getOauthConfig(),
                    GeneralResponse.class
            );

            Object result = gr.getResult();
            String responseJson = result instanceof String ? (String) result : JSON.toJSONString(result);
            JSONObject response = JSON.parseObject(responseJson);
            if (response == null || !SUCCESS_CODE.equals(response.getString("code"))) {
                log.warn("月录像状态查询失败：channelId={}, month={}, response={}", channelId, month, responseJson);
                return null;
            }
            JSONObject dataObj = response.getJSONObject("data");
            return dataObj != null ? dataObj.getString("days") : null;
        } catch (ClientException e) {
            log.error("月录像状态查询异常：{}", e.getErrMsg(), e);
            return null;
        } catch (Exception e) {
            log.error("月录像状态查询失败：", e);
            return null;
        }
    }

    /**
     * 查询时间段内录像信息列表（按文件分段）
     *
     * @param channelId    视频通道编码
     * @param recordSource 录像来源：1=全部，2=设备，3=中心
     * @param startTime    开始时间（时间戳：单位秒）
     * @param endTime      结束时间（时间戳：单位秒）
     * @param streamType   码流类型：0=所有码流，1=主码流，2=辅码流
     * @param recordType   录像类型：0=全部录像
     * @return 录像段列表（无录像/失败返回空列表）
     */
    public List<RecordSegment> queryRecords(String channelId, String recordSource,
                                            long startTime, long endTime,
                                            String streamType, String recordType) {
        try {
            Map<String, Object> body = new HashMap<>();
            Map<String, Object> data = new HashMap<>();
            data.put("channelId", channelId);
            data.put("recordSource", recordSource);
            data.put("startTime", String.valueOf(startTime));
            data.put("endTime", String.valueOf(endTime));
            data.put("streamType", streamType);
            data.put("recordType", recordType);
            body.put("data", data);

            GeneralResponse gr = HttpUtils.executeJson(
                    "/evo-apigw/admin/API/SS/Record/QueryRecords",
                    body, null, Method.POST,
                    authService.getOauthConfig(),
                    GeneralResponse.class
            );

            Object result = gr.getResult();
            String responseJson = result instanceof String ? (String) result : JSON.toJSONString(result);
            JSONObject response = JSON.parseObject(responseJson);
            if (response == null || !SUCCESS_CODE.equals(response.getString("code"))) {
                log.warn("录像信息查询失败：channelId={}, start={}, end={}, response={}",
                        channelId, startTime, endTime, responseJson);
                return Collections.emptyList();
            }
            JSONObject dataObj = response.getJSONObject("data");
            if (dataObj == null) {
                return Collections.emptyList();
            }
            List<RecordSegment> records = JSON.parseArray(dataObj.getString("records"), RecordSegment.class);
            return records != null ? records : Collections.emptyList();
        } catch (ClientException e) {
            log.error("录像信息查询异常：{}", e.getErrMsg(), e);
            return Collections.emptyList();
        } catch (Exception e) {
            log.error("录像信息查询失败：", e);
            return Collections.emptyList();
        }
    }

    /**
     * 获取HLS录像回放流代理地址
     * <p>
     * 流程：调 ICC 回放流接口 → 拼接 token → 内网IP修正（7086仅内网）→ 包装为 hls-proxy 代理地址。
     * 回放流与实时流同为 7086 端口，代理层（hls-proxy）可直接复用。
     *
     * @param channelId    视频通道编码
     * @param streamType   码流类型：1=主码流，2=辅码流
     * @param recordType   录像类型：1=普通录像，2=报警录像（与查询到的段类型保持一致）
     * @param beginTime    开始时间（时间戳：单位秒，须≥录像文件开始时间，不跨文件）
     * @param endTime      结束时间（时间戳：单位秒，须≤录像文件结束时间）
     * @param recordSource 录像来源：2=设备，3=中心
     * @return 代理播放地址（/api/dahua/hls-proxy?url=...）；无录像/失败返回 null
     */
    public String getRecordStreamUrl(String channelId, String streamType, String recordType,
                                     long beginTime, long endTime, String recordSource) {
        try {
            Map<String, Object> body = new HashMap<>();
            Map<String, Object> data = new HashMap<>();
            data.put("channelId", channelId);
            data.put("streamType", streamType);
            data.put("type", "hls");
            data.put("recordType", recordType);
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            data.put("beginTime", sdf.format(new Date(beginTime * 1000L)));
            data.put("endTime", sdf.format(new Date(endTime * 1000L)));
            data.put("recordSource", recordSource);
            body.put("data", data);

            GeneralResponse gr = HttpUtils.executeJson(
                    "/evo-apigw/admin/API/video/stream/record",
                    body, null, Method.POST,
                    authService.getOauthConfig(),
                    GeneralResponse.class
            );

            Object result = gr.getResult();
            String responseJson = result instanceof String ? (String) result : JSON.toJSONString(result);
            JSONObject response = JSON.parseObject(responseJson);
            if (response == null || !SUCCESS_CODE.equals(response.getString("code"))) {
                // 2239=The record was null：时间范围内无录像
                log.warn("录像回放流获取失败：channelId={}, begin={}, end={}, code={}, desc={}",
                        channelId, beginTime, endTime,
                        response != null ? response.getString("code") : "null",
                        response != null ? response.getString("desc") : "");
                return null;
            }

            JSONObject dataObj = response.getJSONObject("data");
            if (dataObj == null) {
                return null;
            }
            String rawUrl = dataObj.getString("url");
            if (!StringUtils.hasText(rawUrl)) {
                return null;
            }

            // ★ 官方文档：接口返回的 url 需自行拼接 ?token=AccessToken
            String token = authService.getAccessToken();
            if (StringUtils.hasText(token) && !rawUrl.contains("token=")) {
                rawUrl += (rawUrl.contains("?") ? "&" : "?") + "token=" + token;
            }

            // 内网IP修正（7086仅内网可达）+ 包装为代理地址（与实时流共用 hls-proxy）
            return videoService.buildProxyUrl(rawUrl);
        } catch (ClientException e) {
            log.error("录像回放流获取异常：{}", e.getErrMsg(), e);
            return null;
        } catch (Exception e) {
            log.error("录像回放流获取失败：", e);
            return null;
        }
    }
}
