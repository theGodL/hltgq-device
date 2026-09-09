package com.qgyun.hltgq.hltgqdevice.videoalert;

import com.qgyun.hltgq.hltgqdevice.model.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
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
     * 当日视频采集统计（实时聚合巡检留痕表，日量级 720 行毫秒级完成，无需缓存）。
     */
    @GetMapping("/patrol-stats")
    public ApiResponse<Map<String, Object>> patrolStats() {
        return ApiResponse.success(patrolRecordService.todayStats());
    }
}
