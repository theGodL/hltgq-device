package com.qgyun.hltgq.hltgqdevice.videoalert;

import com.qgyun.hltgq.hltgqdevice.util.IdGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
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

    /** 统计查询区间上限（天，与 hltgq-mq 730 天上限保持一致，见 数据统计.md 4.4） */
    @Value("${video-alert.stats-max-range-days:730}")
    private int maxRangeDays;

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
            long[] sums = sumByResult(jdbcTemplate.queryForList(sql, dayStart, cutoff));
            collected = sums[0];
            success = sums[1];
            failed = sums[2];
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

    // ======================== 采集统计（日期区间） ========================

    /**
     * 区间视频采集统计（startDate/endDate 含两端，规则见 数据统计.md 7.1 区间口径定稿）：
     * <ul>
     *   <li>expected = 参与通道数（当前快照）× 区间已结束完整窗总数（末日截断，未来日 0 窗）；</li>
     *   <li>collected/success/failed 按留痕表 round_time ∈ [start日0点, cutoff) 聚合（与 expected 同窗界）；</li>
     *   <li>留痕起始日（2026-09-09 部署）之前的日期无数据 → collected=0，属真实语义不补偿。</li>
     * </ul>
     */
    public Map<String, Object> rangeStats(LocalDate start, LocalDate end) {
        LocalDate today = LocalDate.now();
        long nowMs = System.currentTimeMillis();
        return rangeStats(start, end, today, dayStartMillis(today), nowMs);
    }

    /** 区间统计（时间参数可注入，便于测试固定“当前时刻”） */
    Map<String, Object> rangeStats(LocalDate start, LocalDate end, LocalDate today,
                                   long todayStartMs, long nowMs) {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("dataType", "视频数据");
        long startMs = dayStartMillis(start);
        long cutoffMs = rangeCutoff(end, today, todayStartMs, nowMs, windowMinutes);
        long expected = 0;
        long collected = 0;
        long success = 0;
        long failed = 0;
        try {
            int channelCount = countPatrolChannels();
            long windows = totalFinishedWindows(start, end, today, todayStartMs, nowMs, windowMinutes);
            expected = channelCount * windows;
            String sql = "SELECT result, COUNT(*) AS cnt FROM " + PATROL_TABLE
                    + " WHERE round_time >= ? AND round_time < ? AND channel_code IN ("
                    + "SELECT devicecode FROM " + STATION_TABLE
                    + " WHERE epjutj LIKE '%#5#%' AND devicecode IS NOT NULL AND devicecode <> '')"
                    + " GROUP BY result";
            long[] sums = sumByResult(
                    jdbcTemplate.queryForList(sql, new Timestamp(startMs), new Timestamp(cutoffMs)));
            collected = sums[0];
            success = sums[1];
            failed = sums[2];
        } catch (Exception e) {
            log.error("[视频巡检] 采集统计区间查询失败: {}", e.getMessage());
        }
        stats.put("expected", expected);
        stats.put("collected", collected);
        stats.put("success", success);
        stats.put("failed", failed);
        stats.put("successRate", rate(success, expected));
        stats.put("failRate", rate(failed, expected));
        return stats;
    }

    /**
     * 解析并校验区间参数（规则见 数据统计.md 7.1）：
     * 都空=今日；只传 startDate=该日至今日；只传 endDate=该单日；
     * 格式非法/startDate>endDate/超出 stats-max-range-days 上限抛 IllegalArgumentException（controller 转 fail）。
     */
    public LocalDate[] parseRange(String startDate, String endDate) {
        return parseRange(startDate, endDate, LocalDate.now(), maxRangeDays);
    }

    /** 解析校验（today/maxRangeDays 可注入，便于测试） */
    static LocalDate[] parseRange(String startDate, String endDate, LocalDate today, int maxRangeDays) {
        boolean hasStart = startDate != null && !startDate.trim().isEmpty();
        boolean hasEnd = endDate != null && !endDate.trim().isEmpty();
        LocalDate start;
        LocalDate end;
        try {
            if (!hasStart && !hasEnd) {
                start = today;
                end = today;
            } else {
                start = hasStart ? LocalDate.parse(startDate.trim()) : today;
                end = hasEnd ? LocalDate.parse(endDate.trim()) : today;
                if (!hasStart) {
                    start = end;   // 只传 endDate → 单日
                }
                if (!hasEnd) {
                    end = today;   // 只传 startDate → 至今日
                }
            }
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("日期格式非法，应为 yyyy-MM-dd");
        }
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("startDate 不能晚于 endDate");
        }
        if (ChronoUnit.DAYS.between(start, end) >= maxRangeDays) {
            throw new IllegalArgumentException("查询区间超出上限 " + maxRangeDays + " 天");
        }
        return new LocalDate[]{start, end};
    }

    /** 按 result 三态汇总聚合行，返回 long[3]{collected, success, failed} */
    private long[] sumByResult(List<Map<String, Object>> rows) {
        long collected = 0;
        long success = 0;
        long failed = 0;
        for (Map<String, Object> row : rows) {
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
        return new long[]{collected, success, failed};
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

    /** 某自然日 0 点毫秒时间戳（服务器本地时区） */
    static long dayStartMillis(LocalDate day) {
        return Timestamp.valueOf(day.atStartOfDay()).getTime();
    }

    /**
     * 区间内已结束完整窗总数（O(1) 公式，不逐日循环，任意长区间安全）：
     * <ul>
     *   <li>start 在未来 → 0；</li>
     *   <li>末日≤今天 → 完整历史日数 × 全天窗数 + 末日窗数（末日&lt;今天→全天窗；=今天→当前已结束窗）；</li>
     *   <li>末日&gt;今天 → 末日按今天截断（未来日贡献 0 窗）。</li>
     * </ul>
     */
    static long totalFinishedWindows(LocalDate start, LocalDate end, LocalDate today,
                                     long todayStartMs, long nowMs, int windowMinutes) {
        if (windowMinutes <= 0) {
            return 0;
        }
        if (start.isAfter(today)) {
            return 0;
        }
        long windowsPerDay = 1440L / windowMinutes;
        LocalDate last = end.isAfter(today) ? today : end;
        long fullDays = ChronoUnit.DAYS.between(start, last);
        long total = fullDays * windowsPerDay;
        if (!end.isBefore(today)) {
            total += (nowMs - todayStartMs) / (windowMinutes * 60000L);
        } else {
            total += windowsPerDay;
        }
        return total;
    }

    /**
     * 区间聚合 SQL 上界（与 expected 同窗界，避免 collected>expected）：
     * 末日&lt;今天 → 末日次日 0 点；末日≥今天 → 今日已结束窗上界（=当前进行中窗起点）。
     */
    static long rangeCutoff(LocalDate end, LocalDate today, long todayStartMs, long nowMs, int windowMinutes) {
        if (windowMinutes <= 0) {
            return todayStartMs;
        }
        if (end.isBefore(today)) {
            return dayStartMillis(end.plusDays(1));
        }
        long finished = (nowMs - todayStartMs) / (windowMinutes * 60000L);
        return todayStartMs + finished * windowMinutes * 60000L;
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
