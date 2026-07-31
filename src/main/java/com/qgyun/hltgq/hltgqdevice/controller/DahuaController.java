package com.qgyun.hltgq.hltgqdevice.controller;

import com.qgyun.hltgq.hltgqdevice.model.ApiResponse;
import com.qgyun.hltgq.hltgqdevice.service.DahuaDeviceService;
import com.qgyun.hltgq.hltgqdevice.service.DahuaPtzService;
import com.qgyun.hltgq.hltgqdevice.service.DahuaVideoService;
import com.qgyun.hltgq.hltgqdevice.service.PtzCommandDispatcher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 大华视频监控统一API控制器
 * <p>
 * 提供设备树查询、视频流地址获取、云台控制等REST接口
 *
 * @author hltgq-device
 */
@Slf4j
@RestController
@RequestMapping("/api/dahua")
public class DahuaController {

    @Resource
    private DahuaDeviceService deviceService;

    @Resource
    private DahuaVideoService videoService;

    @Resource
    private DahuaPtzService ptzService;

    @Resource
    private PtzCommandDispatcher ptzDispatcher;

    // ==================== 设备树 ====================

    /**
     * 查询设备树节点（懒加载）
     * <p>
     * 前端调用：GET /api/dahua/device-tree?orgId=
     *
     * @param orgId 父组织ID，为空时默认查询根节点"001"
     * @return 子节点列表
     */
    @GetMapping("/device-tree")
    public ApiResponse<List<DahuaDeviceService.DeviceTreeNode>> getDeviceTree(
            @RequestParam(required = false) String orgId) {
        try {
            List<DahuaDeviceService.DeviceTreeNode> nodes = deviceService.getDeviceTree(orgId);
            return ApiResponse.success(nodes);
        } catch (Exception e) {
            log.error("设备树查询接口异常：", e);
            return ApiResponse.fail("设备树查询失败：" + e.getMessage());
        }
    }

    // ==================== 视频流 ====================

    /**
     * 获取视频流播放地址
     * <p>
     * 前端调用：GET /api/dahua/stream-url?channelId=xxx&streamType=1&protocol=hls
     *
     * @param channelId  通道ID（必填）
     * @param streamType 码流类型：1-主码流，2-辅码流（默认1）
     * @param protocol   协议类型：hls/flv/rtmp（默认hls）
     * @return 流地址URL
     */
    @GetMapping("/stream-url")
    public ApiResponse<Map<String, String>> getStreamUrl(
            @RequestParam String channelId,
            @RequestParam(required = false, defaultValue = "1") String streamType,
            @RequestParam(required = false, defaultValue = "hls") String protocol) {
        try {
            if (channelId == null || channelId.trim().isEmpty()) {
                return ApiResponse.fail("通道ID不能为空");
            }

            String url = videoService.getStreamUrl(channelId, streamType, protocol);
            if (url == null || url.trim().isEmpty()) {
                return ApiResponse.fail("获取视频流地址失败");
            }

            Map<String, String> result = new HashMap<>();
            result.put("url", url);
            return ApiResponse.success(result);
        } catch (Exception e) {
            log.error("获取视频流地址接口异常：", e);
            return ApiResponse.fail("获取视频流地址失败：" + e.getMessage());
        }
    }

    // ==================== 云台方向控制 ====================

    /**
     * 云台方向控制
     * <p>
     * 前端调用：POST /api/dahua/ptz/direct
     * Body: { "channelId": "xxx", "direction": "up", "speed": 5 }
     *
     * @param params 请求参数
     * @return 操作结果
     */
    @PostMapping("/ptz/direct")
    public ApiResponse<Void> ptzDirect(@RequestBody Map<String, Object> params) {
        try {
            String channelId = (String) params.get("channelId");
            String direction = (String) params.get("direction");
            int speed = getIntParam(params, "speed", 5);

            if (channelId == null || channelId.trim().isEmpty()) {
                return ApiResponse.fail("通道ID不能为空");
            }
            if (direction == null || direction.trim().isEmpty()) {
                return ApiResponse.fail("方向不能为空");
            }

            ptzDispatcher.submitDirectStart(channelId, direction, speed);
            return ApiResponse.success(null);
        } catch (Exception e) {
            log.error("云台方向控制接口异常：", e);
            return ApiResponse.fail("云台控制失败：" + e.getMessage());
        }
    }

    /**
     * 停止云台移动
     * <p>
     * 前端调用：POST /api/dahua/ptz/stop
     * Body: { "channelId": "xxx" }
     *
     * @param params 请求参数
     * @return 操作结果
     */
    @PostMapping("/ptz/stop")
    public ApiResponse<Void> ptzStop(@RequestBody Map<String, Object> params) {
        try {
            String channelId = (String) params.get("channelId");
            String direction = (String) params.get("direction");
            if (channelId == null || channelId.trim().isEmpty()) {
                return ApiResponse.fail("通道ID不能为空");
            }

            ptzDispatcher.submitDirectStop(channelId, direction);
            return ApiResponse.success(null);
        } catch (Exception e) {
            log.error("停止云台接口异常：", e);
            return ApiResponse.fail("停止云台失败：" + e.getMessage());
        }
    }

    // ==================== 镜头控制 ====================

    /**
     * 镜头控制（变焦/聚焦/光圈）
     * <p>
     * 前端调用：POST /api/dahua/ptz/lens
     * Body: { "channelId": "xxx", "action": "zoomIn", "speed": 5 }
     *
     * @param params 请求参数
     * @return 操作结果
     */
    @PostMapping("/ptz/lens")
    public ApiResponse<Void> ptzLens(@RequestBody Map<String, Object> params) {
        try {
            String channelId = (String) params.get("channelId");
            String action = (String) params.get("action");
            int speed = getIntParam(params, "speed", 5);

            if (channelId == null || channelId.trim().isEmpty()) {
                return ApiResponse.fail("通道ID不能为空");
            }
            if (action == null || action.trim().isEmpty()) {
                return ApiResponse.fail("镜头动作不能为空");
            }

            ptzDispatcher.submitLensStart(channelId, action, speed);
            return ApiResponse.success(null);
        } catch (Exception e) {
            log.error("镜头控制接口异常：", e);
            return ApiResponse.fail("镜头控制失败：" + e.getMessage());
        }
    }

    // ==================== 工具方法 ====================

    /**
     * 安全地从参数Map中获取整数值
     */
    private int getIntParam(Map<String, Object> params, String key, int defaultValue) {
        Object value = params.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String) {
            try {
                return Integer.parseInt((String) value);
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
        return defaultValue;
    }
}
