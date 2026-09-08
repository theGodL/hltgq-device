package com.qgyun.hltgq.hltgqdevice.controller;

import com.qgyun.hltgq.hltgqdevice.auth.RequireAdmin;
import com.qgyun.hltgq.hltgqdevice.auth.RolePermissionService;
import com.qgyun.hltgq.hltgqdevice.auth.SessionContextService;
import com.qgyun.hltgq.hltgqdevice.auth.SessionUnavailableException;
import com.qgyun.hltgq.hltgqdevice.auth.UnauthorizedException;
import com.qgyun.hltgq.hltgqdevice.auth.UserContext;
import com.qgyun.hltgq.hltgqdevice.model.ApiResponse;
import com.qgyun.hltgq.hltgqdevice.model.RecordSegment;
import com.qgyun.hltgq.hltgqdevice.service.DahuaDeviceService;
import com.qgyun.hltgq.hltgqdevice.service.DahuaPtzService;
import com.qgyun.hltgq.hltgqdevice.service.DahuaRecordService;
import com.qgyun.hltgq.hltgqdevice.service.DahuaVideoService;
import com.qgyun.hltgq.hltgqdevice.service.PtzCommandDispatcher;
import com.qgyun.hltgq.hltgqdevice.videoalert.AlertQueryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
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
    private DahuaRecordService recordService;

    @Resource
    private PtzCommandDispatcher ptzDispatcher;

    @Resource
    private SessionContextService sessionContextService;

    @Resource
    private RolePermissionService rolePermissionService;

    @Resource
    private AlertQueryService alertQueryService;

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
     * 云台方向控制（仅系统管理员）
     * <p>
     * 前端调用：POST /api/dahua/ptz/direct
     * Body: { "channelId": "xxx", "direction": "up", "speed": 5 }
     *
     * @param params 请求参数
     * @return 操作结果
     */
    @RequireAdmin
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
     * 停止云台移动（仅系统管理员）
     * <p>
     * 前端调用：POST /api/dahua/ptz/stop
     * Body: { "channelId": "xxx" }
     *
     * @param params 请求参数
     * @return 操作结果
     */
    @RequireAdmin
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
     * 镜头控制（变焦/聚焦/光圈，仅系统管理员）
     * <p>
     * 前端调用：POST /api/dahua/ptz/lens
     * Body: { "channelId": "xxx", "action": "zoomIn", "speed": 5 }
     *
     * @param params 请求参数
     * @return 操作结果
     */
    @RequireAdmin
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

    // ==================== 历史录像 ====================

    /**
     * 查询通道指定月份的每日录像存在状态（前端月历标记用）
     * <p>
     * 前端调用：GET /api/dahua/record/month-status?channelId=xxx&month=202609&recordSource=1
     *
     * @param channelId    通道ID（必填）
     * @param month        月份，格式 yyyyMM（必填）
     * @param recordSource 录像来源：1=全部，2=设备，3=中心（默认1）
     * @return days 字符串（逗号分隔的0/1序列，1=当日有录像）
     */
    @GetMapping("/record/month-status")
    public ApiResponse<Map<String, Object>> getMonthRecordStatus(
            @RequestParam String channelId,
            @RequestParam String month,
            @RequestParam(required = false, defaultValue = "1") String recordSource) {
        Map<String, Object> result = new HashMap<>();
        try {
            if (channelId == null || channelId.trim().isEmpty()) {
                return ApiResponse.fail("通道ID不能为空");
            }
            if (month == null || !month.matches("\\d{6}")) {
                return ApiResponse.fail("月份格式错误，应为yyyyMM");
            }
            String days = recordService.getMonthRecordStatus(channelId.trim(), recordSource, month);
            result.put("channelId", channelId.trim());
            result.put("month", month);
            result.put("days", days);
            return ApiResponse.success(result);
        } catch (Exception e) {
            log.error("月录像状态查询接口异常：", e);
            return ApiResponse.fail("月录像状态查询失败：" + e.getMessage());
        }
    }

    /**
     * 查询时间段内录像信息列表（按文件分段，回放不能跨文件）
     * <p>
     * 前端调用：GET /api/dahua/record/query?channelId=xxx&startTime=1725000000&endTime=1725003600
     *
     * @param channelId    通道ID（必填）
     * @param startTime    开始时间（时间戳：单位秒，必填）
     * @param endTime      结束时间（时间戳：单位秒，必填）
     * @param recordSource 录像来源：1=全部，2=设备，3=中心（默认1）
     * @param streamType   码流类型：0=所有，1=主码流，2=辅码流（默认0）
     * @param recordType   录像类型：0=全部录像（默认0）
     * @return 录像段列表（无录像返回空数组）
     */
    @GetMapping("/record/query")
    public ApiResponse<List<RecordSegment>> queryRecords(
            @RequestParam String channelId,
            @RequestParam long startTime,
            @RequestParam long endTime,
            @RequestParam(required = false, defaultValue = "1") String recordSource,
            @RequestParam(required = false, defaultValue = "0") String streamType,
            @RequestParam(required = false, defaultValue = "0") String recordType) {
        try {
            if (channelId == null || channelId.trim().isEmpty()) {
                return ApiResponse.fail("通道ID不能为空");
            }
            if (startTime <= 0 || endTime <= startTime) {
                return ApiResponse.fail("时间范围不合法");
            }
            List<RecordSegment> records = recordService.queryRecords(
                    channelId.trim(), recordSource, startTime, endTime, streamType, recordType);
            return ApiResponse.success(records);
        } catch (Exception e) {
            log.error("录像信息查询接口异常：", e);
            return ApiResponse.fail("录像信息查询失败：" + e.getMessage());
        }
    }

    /**
     * 获取HLS录像回放流代理地址
     * <p>
     * 前端调用：GET /api/dahua/record/stream?channelId=xxx&beginTime=1725000000&endTime=1725000300
     * <p>
     * 注意：beginTime/endTime 不能跨录像文件（先调 /record/query 拿到段再申请流），
     * 返回的代理地址经 /hls-proxy 播放（后端自动附加token、内网IP修正、HEVC转码兜底）。
     *
     * @param channelId    通道ID（必填）
     * @param beginTime    开始时间（时间戳：单位秒，必填）
     * @param endTime      结束时间（时间戳：单位秒，必填）
     * @param streamType   码流类型：1=主码流，2=辅码流（默认1）
     * @param recordType   录像类型：1=普通录像，2=报警录像（默认1，与查询到的段类型保持一致）
     * @param recordSource 录像来源：2=设备，3=中心（默认3）
     * @return { url } 代理播放地址
     */
    @GetMapping("/record/stream")
    public ApiResponse<Map<String, String>> getRecordStream(
            @RequestParam String channelId,
            @RequestParam long beginTime,
            @RequestParam long endTime,
            @RequestParam(required = false, defaultValue = "1") String streamType,
            @RequestParam(required = false, defaultValue = "1") String recordType,
            @RequestParam(required = false, defaultValue = "3") String recordSource) {
        try {
            if (channelId == null || channelId.trim().isEmpty()) {
                return ApiResponse.fail("通道ID不能为空");
            }
            if (beginTime <= 0 || endTime <= beginTime) {
                return ApiResponse.fail("时间范围不合法");
            }
            String url = recordService.getRecordStreamUrl(
                    channelId.trim(), streamType, recordType, beginTime, endTime, recordSource);
            if (url == null || url.trim().isEmpty()) {
                return ApiResponse.fail("获取录像回放流失败，可能该时间段无录像或录像类型不支持");
            }
            Map<String, String> result = new HashMap<>();
            result.put("url", url);
            return ApiResponse.success(result);
        } catch (Exception e) {
            log.error("录像回放流接口异常：", e);
            return ApiResponse.fail("获取录像回放流失败：" + e.getMessage());
        }
    }

    // ==================== 告警查询（H5 移动端列表统计 / 详情 AI 状态栏） ====================

    /**
     * 未关闭告警查询（H5 移动端视频页面，仅登录，无管理员限制）。
     * <p>不带 channelId → 汇总：{@code {total: 告警总数, byChannel: {通道devicecode: 告警数}}}；
     * 带 channelId → 该通道未关闭告警列表（content/time/type/level/status，按发生时间倒序）。
     * <p>前端调用：
     * <ul>
     *   <li>列表页统计行/卡片告警态：GET /api/dahua/alert/active</li>
     *   <li>详情页 AI 检测状态栏：GET /api/dahua/alert/active?channelId=xxx</li>
     * </ul>
     */
    @GetMapping("/alert/active")
    public ApiResponse<Map<String, Object>> activeAlerts(
            @RequestParam(required = false) String channelId) {
        try {
            if (channelId == null || channelId.trim().isEmpty()) {
                return ApiResponse.success(alertQueryService.activeSummary());
            }
            List<Map<String, Object>> list = alertQueryService.activeAlertsOfChannel(channelId.trim());
            Map<String, Object> result = new HashMap<>();
            result.put("channelId", channelId.trim());
            result.put("count", list.size());
            result.put("list", list);
            return ApiResponse.success(result);
        } catch (Exception e) {
            log.error("未关闭告警查询接口异常：", e);
            return ApiResponse.fail("告警查询失败：" + e.getMessage());
        }
    }

    // ==================== 鉴权 ====================

    /**
     * 当前登录人云台权限接口（前端用于控制云台面板显隐/禁用）
     * <p>
     * 前端调用：GET /api/dahua/auth/current-user
     * 返回 data = { userId, isAdmin }；未登录或会话服务异常时 isAdmin=false（安全侧：隐藏云台面板）
     */
    @GetMapping("/auth/current-user")
    public ApiResponse<Map<String, Object>> currentUser(HttpServletRequest request) {
        Map<String, Object> data = new HashMap<>();
        try {
            UserContext user = sessionContextService.resolveCurrentUser(request);
            // 平台超管（superAdmin）或绑定 hltgq_default_admin 角色的用户均视为系统管理员
            boolean isAdmin = rolePermissionService.isAdmin(user);
            data.put("userId", user.getUserId());
            data.put("isAdmin", isAdmin);
        } catch (UnauthorizedException e) {
            // 未登录/会话过期：非错误场景，前端按无权限处理
            data.put("userId", null);
            data.put("isAdmin", false);
        } catch (SessionUnavailableException e) {
            // Redis 不可达：安全侧处理（隐藏云台面板，禁止放行操作）
            log.warn("current-user 会话服务不可用，按无权限返回：{}", e.getMessage());
            data.put("userId", null);
            data.put("isAdmin", false);
        }
        return ApiResponse.success(data);
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
