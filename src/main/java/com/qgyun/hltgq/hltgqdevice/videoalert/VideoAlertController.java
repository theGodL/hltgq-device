package com.qgyun.hltgq.hltgqdevice.videoalert;

import com.qgyun.hltgq.hltgqdevice.auth.RequireAdmin;
import com.qgyun.hltgq.hltgqdevice.model.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

/**
 * 视频故障检测管理接口（运维用）。
 * <p>不提供告警查询接口：平台侧 hltgq-site {@code /alert/page} 已支持
 * {@code type=other}（异常告警）与 {@code siteType=5}（视频站点）筛选，
 * 视频告警入库后平台告警页面直接展示，避免双写查询逻辑。
 */
@Slf4j
@RestController
@RequestMapping("/api/dahua/video-alert")
public class VideoAlertController {

    @Resource
    private VideoAlertScheduler scheduler;

    /**
     * 手动触发一轮视频故障检测（系统管理员，异步执行，立即返回）。
     */
    @RequireAdmin
    @PostMapping("/trigger")
    public ApiResponse<String> trigger() {
        boolean accepted = scheduler.triggerRound();
        if (accepted) {
            log.info("[视频告警] 手动触发一轮检测（异步执行中）");
            return ApiResponse.success("已触发，检测在后台异步执行", null);
        }
        return ApiResponse.fail("上一轮轮巡尚未结束，请稍后再试");
    }

    /**
     * 当前轮巡进度与最近一轮检测结果快照（只读）。
     */
    @GetMapping("/status")
    public ApiResponse<VideoAlertScheduler.RoundSnapshot> status() {
        return ApiResponse.success(scheduler.getSnapshot());
    }
}
