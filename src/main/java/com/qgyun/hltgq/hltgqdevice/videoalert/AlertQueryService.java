package com.qgyun.hltgq.hltgqdevice.videoalert;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 告警查询服务（H5 移动端视频页面使用）：
 * <ul>
 *   <li>列表页「告警 N」统计与卡片告警态：按视频通道聚合未关闭告警数；</li>
 *   <li>详情页 AI 检测状态栏：单通道未关闭告警列表（type=#3# 即 IVSS 智能事件）。</li>
 * </ul>
 * <p>告警与通道的关联走 device 字段：告警表 device = 设备表 id，设备表 code = 通道 devicecode
 * （告警入库时已按设备表视频设备 ID 落 device，与 PC 端告警口径一致）。
 */
@Slf4j
@Service
public class AlertQueryService {

    /** 人大金仓 schema（带双引号，因为含连字符），与 hltgq-mq 一致 */
    private static final String SCHEMA = "\"qixiao-apaas\".";

    /** 告警表 / 设备表（告警 device 关联设备表 id；设备表 code 即通道 devicecode） */
    private static final String ALERT_TABLE = SCHEMA + "t_auto_hltgq_water_alert";
    private static final String DEVICE_TABLE = SCHEMA + "t_auto_hltgq_water_device";

    /** 已关闭状态（未关闭判定用 IS DISTINCT FROM，兼容 NULL） */
    private static final String STATUS_CLOSED = "#4#";

    @Resource
    private JdbcTemplate jdbcTemplate;

    /**
     * 未关闭告警汇总（H5 列表页统计行 + 卡片告警态）。
     *
     * @return {@code {total: 告警总数, byChannel: {通道devicecode: 未关闭告警数}}}
     */
    public Map<String, Object> activeSummary() {
        Map<String, Object> result = new LinkedHashMap<>();
        String sql = "SELECT d.code AS channel, COUNT(*) AS cnt FROM " + ALERT_TABLE + " a"
                + " JOIN " + DEVICE_TABLE + " d ON a.device = d.id"
                + " WHERE a.status IS DISTINCT FROM ?"
                + " GROUP BY d.code";
        Map<String, Integer> byChannel = new LinkedHashMap<>();
        int total = 0;
        for (Map<String, Object> row : jdbcTemplate.queryForList(sql, STATUS_CLOSED)) {
            Object code = row.get("channel");
            Object cnt = row.get("cnt");
            if (code == null || String.valueOf(code).trim().isEmpty()) {
                continue;
            }
            int n = cnt == null ? 0 : Integer.parseInt(String.valueOf(cnt));
            byChannel.put(String.valueOf(code).trim(), n);
            total += n;
        }
        result.put("total", total);
        result.put("byChannel", byChannel);
        return result;
    }

    /**
     * 单通道未关闭告警列表（H5 详情页 AI 检测状态栏）。
     *
     * @param channelId 通道 devicecode
     * @return 告警列表（content/time/type/level/status），按发生时间倒序
     */
    public List<Map<String, Object>> activeAlertsOfChannel(String channelId) {
        String sql = "SELECT a.content, a.\"time\", a.\"type\", a.\"level\", a.status FROM " + ALERT_TABLE + " a"
                + " JOIN " + DEVICE_TABLE + " d ON a.device = d.id"
                + " WHERE d.code = ? AND a.status IS DISTINCT FROM ?"
                + " ORDER BY a.\"time\" DESC";
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList(sql, channelId, STATUS_CLOSED)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("content", row.get("content"));
            item.put("time", row.get("time"));
            item.put("type", row.get("type"));
            item.put("level", row.get("level"));
            item.put("status", row.get("status"));
            out.add(item);
        }
        return out;
    }
}
