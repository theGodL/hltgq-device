package com.qgyun.hltgq.hltgqdevice.videoalert;

import com.qgyun.hltgq.hltgqdevice.model.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.time.LocalDate;
import java.util.Map;

/**
 * 视频采集统计接口（统计大屏「数据采集状态统计」区块「视频数据」行数据源）。
 * <p>返回单行对象与 hltgq-mq /api/report/collect-stats 行同构
 * （dataType/expected/collected/success/failed/successRate/failRate），
 * 由 hltgq-site 网关 /data-statistics/video-collect 转发后与 mq 五行合并渲染。
 * <p>本接口仅内网可达、不对外暴露；鉴权与会话与 device 现有只读接口一致。
 */
@Slf4j
@RestController
@RequestMapping("/api/dahua/video")
public class VideoPatrolStatsController {

    @Resource
    private VideoPatrolRecordService patrolRecordService;

    /**
     * 视频采集统计（当日 0 点起实时聚合；可选 startDate/endDate 查询区间，yyyy-MM-dd 含两端）。
     * <p>无参=今日（与旧版行为完全一致）；区间规则见 数据统计.md 7.1 区间口径定稿：
     * 末日已结束窗截断/未来日 0 窗/上限与 mq 一致 730 天；
     * 参数非法返回 fail（site 网关按失败处理，语义与 mq 超限 502 一致）。
     */
    @GetMapping("/patrol-stats")
    public ApiResponse<Map<String, Object>> patrolStats(@RequestParam(required = false) String startDate,
                                                        @RequestParam(required = false) String endDate) {
        boolean hasParam = (startDate != null && !startDate.trim().isEmpty())
                || (endDate != null && !endDate.trim().isEmpty());
        if (!hasParam) {
            return ApiResponse.success(patrolRecordService.todayStats());
        }
        try {
            LocalDate[] range = patrolRecordService.parseRange(startDate, endDate);
            return ApiResponse.success(patrolRecordService.rangeStats(range[0], range[1]));
        } catch (IllegalArgumentException e) {
            log.warn("[视频巡检] 区间参数非法: startDate={}, endDate={}, err={}",
                    startDate, endDate, e.getMessage());
            return ApiResponse.fail(e.getMessage());
        }
    }
}
