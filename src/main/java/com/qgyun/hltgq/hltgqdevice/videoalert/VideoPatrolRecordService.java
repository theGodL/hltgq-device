package com.qgyun.hltgq.hltgqdevice.videoalert;

import com.qgyun.hltgq.hltgqdevice.util.IdGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 视频巡检留痕与采集统计服务（统计大屏「视频数据」行数据源，方案见 数据统计.md 7.1）。
 * <p>巡检留痕表 {@code t_auto_hltgq_water_video_patrol}：每轮巡检收尾逐通道写入一轮原始结果
 * （不过防抖——防抖属告警层，统计按每轮原始结果计数）：
 * <ul>
 *   <li>result=ok：检测正常；fail：检出故障（fault_types 存枚举名逗号分隔）；error：检测异常/超时；</li>
 *   <li>整轮跳过（登录失败/设备树遍历不完整）当轮无留痕 → collected 少、successRate 自然下降（同 mq 断网期语义）；</li>
 *   <li>同轮同通道幂等（UNIQUE(round_time, channel_code) + ON CONFLICT DO NOTHING，重跑防重）。</li>
 * </ul>
 * <p>统计口径与 hltgq-mq collect-stats 同构（「已结束完整窗」规则）：只统计当日已结束的完整
 * 巡检窗（进行中窗不计）；参与通道 = 站点表视频站点（epjutj 含 #5#，devicecode 去重），与站点同步口径一致。
 */
@Slf4j
@Service
public class VideoPatrolRecordService {

    /** 人大金仓 schema（带双引号，因为含连字符），与 hltgq-mq 一致 */
    private static final String SCHEMA = "\"qixiao-apaas\".";

    /** 巡检留痕表 / 站点信息表 */
    private static final String PATROL_TABLE = SCHEMA + "t_auto_hltgq_water_video_patrol";
    private static final String STATION_TABLE = SCHEMA + "t_auto_hltgq_5nw74_vnqqef";

    /** 巡检留痕结果三态 */
    private static final String RESULT_OK = "ok";
    private static final String RESULT_FAIL = "fail";
    private static final String RESULT_ERROR = "error";

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Value("${app.corp-code:hltgq}")
    private String corpCode;

    /** 巡检统计窗长（分钟，须与 video-alert.cron 的轮次间隔保持一致，默认 30） */
    @Value("${video-alert.patrol-window-minutes:30}")
    private int windowMinutes;

    // ======================== 巡检留痕落库 ========================

    /**
     * 落库一轮巡检的逐通道原始结果（不过防抖）。检测结果三个来源取并集：
     * <ul>
     *   <li>abnormalByChannel：检出故障的通道（result=fail，fault_types=枚举名逗号分隔）；</li>
     *   <li>inspectedChannels：完成检测的正常通道（result=ok）；</li>
     *   <li>errorChannels：检测异常/超时的通道（result=error）。</li>
     * </ul>
     * 被取消未完成检测的通道不落库。单行落库失败不阻断整轮（仅记日志，下轮重试）。
     *
     * @param roundTime          轮实际开始时间（毫秒）
     * @param abnormalByChannel  通道 → 故障枚举集合
     * @param inspectedChannels  完成检测的通道集合（含检出故障的通道）
     * @param errorChannels      检测异常/超时的通道集合
     */
    public void saveRound(long roundTime, Map<String, Set<VideoFaultType>> abnormalByChannel,
                          Set<String> inspectedChannels, Set<String> errorChannels) {
        Set<String> all = new HashSet<>(abnormalByChannel.keySet());
        all.addAll(inspectedChannels);
        all.addAll(errorChannels);
        if (all.isEmpty()) {
            return;
        }
        Timestamp roundTs = new Timestamp(roundTime);
        Timestamp now = new Timestamp(System.currentTimeMillis());
        int saved = 0;
        for (String code : all) {
            Set<VideoFaultType> faults = abnormalByChannel.get(code);
            String result;
            String faultTypes = null;
            if (faults != null && !faults.isEmpty()) {
                result = RESULT_FAIL;
                faultTypes = joinFaultNames(faults);
            } else if (errorChannels.contains(code)) {
                result = RESULT_ERROR;
            } else if (inspectedChannels.contains(code)) {
                result = RESULT_OK;
            } else {
                continue; // 既无故障又未完成检测（理论不可达），不落库
            }
            try {
                String sql = "INSERT INTO " + PATROL_TABLE
                        + " (id, corp_code, round_time, channel_code, result, fault_types, created_at, created_by)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
                        + " ON CONFLICT (round_time, channel_code) DO NOTHING";
                int rows = jdbcTemplate.update(sql, IdGenerator.generate(), corpCode, roundTs,
                        code, result, faultTypes, now, "SYSTEM");
                if (rows > 0) {
                    saved++;
                }
            } catch (Exception e) {
                log.warn("[视频巡检] 留痕落库失败, code={}, result={}: {}", code, result, e.getMessage());
            }
        }
        log.info("[视频巡检] 留痕落库完成: 轮开始{}ms, 写入{}条/结果{}条", roundTime, saved, all.size());
    }

    /** 故障枚举名逗号拼接（枚举声明序，输出稳定） */
    private static String joinFaultNames(Set<VideoFaultType> faults) {
        List<String> names = new ArrayList<>();
        for (VideoFaultType f : faults) {
            names.add(f.name());
        }
        return String.join(",", names);
    }

    // ======================== 采集统计（当日） ========================

    /**
     * 当日视频采集统计（统计大屏「视频数据」行，与 mq collect-stats 行同构）：
     * <ul>
     *   <li>expected 应采 = 参与通道数（站点表视频站点）× 当日已结束完整窗数（进行中窗不计）；</li>
     *   <li>collected 实采 = 已结束窗内留痕行数（ok/fail/error 均算）；</li>
     *   <li>success 成功 = result=ok 行数；failed 失败 = fail+error 行数；</li>
     *   <li>successRate/failRate = success/failed ÷ expected × 100（保留 1 位小数，expected=0 时 0.0）。</li>
     * </ul>
     */
    public Map<String, Object> todayStats() {
        return todayStats(System.currentTimeMillis());
    }

    /** 当日统计（nowMs 可注入，便于测试固定「当前时刻」） */
    Map<String, Object> todayStats(long nowMs) {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("dataType", "视频数据");
        long dayStartMs = dayStartMillis();
        int finishedWindows = finishedWindows(nowMs, dayStartMs, windowMinutes);
        long expected = 0;
        long collected = 0;
        long success = 0;
        long failed = 0;
        try {
            int channelCount = countPatrolChannels();
            expected = channelCount * (long) finishedWindows;
            Timestamp dayStart = new Timestamp(dayStartMs);
            // 已结束窗上界 = 当前进行中窗起点（round_time 不早于该上界的轮次所在窗未结束，不计入）
            Timestamp cutoff = new Timestamp(dayStartMs + finishedWindows * (windowMinutes * 60000L));
            String sql = "SELECT result, COUNT(*) AS cnt FROM " + PATROL_TABLE
                    + " WHERE round_time >= ? AND round_time < ? AND channel_code IN ("
                    + "SELECT devicecode FROM " + STATION_TABLE
                    + " WHERE epjutj LIKE '%#5#%' AND devicecode IS NOT NULL AND devicecode <> '')"
                    + " GROUP BY result";
            for (Map<String, Object> row : jdbcTemplate.queryForList(sql, dayStart, cutoff)) {
                Object r = row.get("result");
                Object c = row.get("cnt");
                long n = c == null ? 0 : Long.parseLong(String.valueOf(c));
                collected += n;
                if (RESULT_OK.equals(r)) {
                    success = n;
                } else {
                    failed += n;
                }
            }
        } catch (Exception e) {
            log.error("[视频巡检] 采集统计查询失败: {}", e.getMessage());
        }
        stats.put("expected", expected);
        stats.put("collected", collected);
        stats.put("success", success);
        stats.put("failed", failed);
        stats.put("successRate", rate(success, expected));
        stats.put("failRate", rate(failed, expected));
        return stats;
    }

    /** 参与统计的视频通道数 = 站点表视频站点数（epjutj 含 #5#，按 devicecode 去重），与站点同步口径一致 */
    private int countPatrolChannels() {
        String sql = "SELECT COUNT(*) FROM (SELECT devicecode FROM " + STATION_TABLE
                + " WHERE epjutj LIKE '%#5#%' AND devicecode IS NOT NULL AND devicecode <> ''"
                + " GROUP BY devicecode) t";
        Long n = jdbcTemplate.queryForObject(sql, Long.class);
        return n == null ? 0 : n.intValue();
    }

    /** 当日 0 点毫秒时间戳（服务器本地时区，与站点/告警统计口径一致） */
    static long dayStartMillis() {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0);
        cal.set(java.util.Calendar.MINUTE, 0);
        cal.set(java.util.Calendar.SECOND, 0);
        cal.set(java.util.Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }

    /** 当日已结束完整窗数（进行中窗不计，同 mq「已结束完整窗」规则；windowMinutes≤0 防除零） */
    static int finishedWindows(long nowMs, long dayStartMs, int windowMinutes) {
        if (windowMinutes <= 0) {
            return 0;
        }
        return (int) ((nowMs - dayStartMs) / (windowMinutes * 60000L));
    }

    /** 百分比（保留 1 位小数；分母 0 → 0.0，避免除零） */
    static double rate(long part, long total) {
        return total > 0 ? Math.round(part * 1000.0 / total) / 10.0 : 0.0;
    }
}
