package com.qgyun.hltgq.hltgqdevice.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.dahuatech.hutool.http.Method;
import com.dahuatech.icc.exception.ClientException;
import com.dahuatech.icc.oauth.model.v202010.GeneralResponse;
import com.dahuatech.icc.oauth.utils.HttpUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


/**
 * 大华云台控制（PTZ）服务
 * <p>
 * 负责控制摄像头的方向移动、镜头变焦、光圈等操作
 *
 * @author hltgq-device
 */
@Slf4j
@Service
public class DahuaPtzService {

    @Resource
    private DahuaAuthService authService;

    // ==================== 云台方向控制 ====================

    /**
     * 方向控制命令常量（依据大华ICC官方文档：0=停止, 1=开启）
     *
     * @see <a href="https://open-icc.dahuatech.com/iccdoc/enterprisebase/5.0.17/wiki/admin/cameraPtz.html">官方文档</a>
     */
    private static final String COMMAND_START = "1";
    private static final String COMMAND_STOP = "0";

    /** 默认移动速度（stepX/stepY），STOP 需与 START 保持一致（官方文档要求） */
    private static final String DEFAULT_SPEED = "5";

    /**
     * START 与 STOP 到达平台的最小间隔（毫秒）
     * <p>
     * 官方文档："异步发送操作指令到设备端，需开启动作与停止动作间隔一定时间，保证顺序执行"
     */
    private static final long MIN_START_STOP_INTERVAL_MS = 150;

    /** 每通道最后一次方向 START 调用完成时间（毫秒时间戳），用于 STOP 自适应延迟 */
    private final ConcurrentHashMap<String, Long> lastDirectStartTime = new ConcurrentHashMap<>();

    /** 每通道最后一次镜头 START 调用完成时间（毫秒时间戳），用于 STOP 自适应延迟 */
    private final ConcurrentHashMap<String, Long> lastLensStartTime = new ConcurrentHashMap<>();

    /** 每通道最后一次方向 START 使用的速度（step），STOP 需与 START 保持一致（官方文档要求） */
    private final ConcurrentHashMap<String, String> lastDirectStep = new ConcurrentHashMap<>();

    /** 镜头控制最后状态缓存（key=channelId），STOP 时需要使用与 START 一致的参数 */
    private final ConcurrentHashMap<String, LensParams> lastLensParams = new ConcurrentHashMap<>();

    private static class LensParams {
        final String operateType;
        final String direct;
        final String step;
        LensParams(String operateType, String direct, String step) {
            this.operateType = operateType;
            this.direct = direct;
            this.step = step;
        }
    }

    /**
     * 云台方向控制
     * <p>
     * 依据大华ICC官方文档 ({@code OperateDirect} API)：
     * <ul>
     *   <li>{@code command="1"} = 开启动作（START）</li>
     *   <li>{@code command="0"} = 停止动作（STOP）</li>
     *   <li>STOP 需与 START 的 stepX/stepY/direct 保持一致，否则无法停止</li>
     *   <li>异步发送到设备端，需 START/STOP 之间间隔一定时间，保证顺序执行</li>
     * </ul>
     * <p>
     * 历史教训：此前 COMMAND_START/STOP 值写反（0/1 互换），
     * 且 STOP 用 stepX=0 与 START 不匹配，导致设备不可控。
     *
     * @param channelId 通道ID
     * @param direction 方向：up/down/left/right/upleft/upright/downleft/downright
     * @param speed     移动速度（1-8，官方文档限制）
     */
    public void operateDirect(String channelId, String direction, int speed) {
        channelId = normalizeChannelId(channelId);
        validateChannelId(channelId);
        String directCode = mapDirectionToCode(direction);
        if (directCode == null) {
            log.warn("不支持的云台方向：{}", direction);
            return;
        }

        String step = String.valueOf(Math.max(1, Math.min(8, speed)));

        // ★ 缓存本次 START 的 step，供 STOP 使用（官方文档要求 STOP 参数与 START 一致）
        lastDirectStep.put(channelId, step);

        try {
            Map<String, Object> body = new HashMap<>();
            Map<String, Object> data = new HashMap<>();
            data.put("channelId", channelId);
            data.put("direct", directCode);
            data.put("stepX", step);
            data.put("stepY", step);
            data.put("command", COMMAND_START);
            data.put("extend", "");
            body.put("data", data);

            log.info("云台方向控制（START）：channelId={}, direction={}, directCode={}, step={}",
                    channelId, direction, directCode, step);

            String responseJson = HttpUtils.executeJson(
                    "/evo-apigw/admin/API/DMS/Ptz/OperateDirect",
                    body,
                    null,
                    Method.POST,
                    authService.getOauthConfig(),
                    GeneralResponse.class
            ).getResult().toString();

            // ★ 记录 START 完成时刻（平台已接收），供 STOP 自适应延迟计算
            lastDirectStartTime.put(channelId, System.currentTimeMillis());

            JSONObject response = JSON.parseObject(responseJson);
            if (response != null && !"1000".equals(response.getString("code"))) {
                String errDesc = response.getString("desc");
                if (errDesc != null && errDesc.contains("no need")) {
                    log.info("云台方向控制（设备无需响应）：channelId={}, desc={}", channelId, errDesc);
                    return;
                }
                log.warn("云台方向控制失败：{}", errDesc);
                throw new RuntimeException("云台方向控制失败：" + errDesc);
            }

        } catch (ClientException e) {
            log.error("云台方向控制异常：{}", e.getErrMsg(), e);
            throw new RuntimeException("云台方向控制异常：" + e.getErrMsg(), e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("云台方向控制失败：", e);
            throw new RuntimeException("云台方向控制失败：" + e.getMessage(), e);
        }
    }

    /**
     * 停止云台移动
     * <p>
     * 依据官方文档要求：STOP 的 stepX/stepY/direct 需与 START 保持一致，
     * 否则设备无法正常停止。采用<b>自适应延迟</b>：距最后一次 START 完成不足
     * 150ms 时补足差值（保证平台顺序执行），超过 150ms 时零延迟直发，
     * 消除固定延迟带来的操作卡顿感。
     *
     * @param channelId 通道ID
     * @param direction 要停止的方向（up/down/left/right/...），为null时默认"up"
     */
    public void stopDirect(String channelId, String direction) {
        channelId = normalizeChannelId(channelId);
        String directCode = mapDirectionToCode(direction);
        if (directCode == null) {
            directCode = "1"; // 默认上方向，兼容旧版不传direction的调用
        }

        try {
            Map<String, Object> body = new HashMap<>();
            Map<String, Object> data = new HashMap<>();
            data.put("channelId", channelId);
            data.put("direct", directCode);
            // ★ STOP 使用与 START 一致的速度（官方文档要求："停止动作需与开启动作时的其他参数需保持一致，否则无法停止"）
            String step = lastDirectStep.remove(channelId);
            if (step == null) step = DEFAULT_SPEED;
            data.put("stepX", step);
            data.put("stepY", step);
            data.put("command", COMMAND_STOP);
            data.put("extend", "");
            body.put("data", data);

            log.info("停止云台移动（STOP）：channelId={}, direction={}, directCode={}",
                    channelId, direction, directCode);

            // ★ 自适应延迟：仅当距最后一次 START 完成不足 150ms 时补足差值
            // （官方文档：异步发送需开启/停止动作间隔一定时间，保证顺序执行）
            if (!adaptiveDelay(lastDirectStartTime.get(channelId))) {
                return; // 线程被中断
            }

            String responseJson = HttpUtils.executeJson(
                    "/evo-apigw/admin/API/DMS/Ptz/OperateDirect",
                    body,
                    null,
                    Method.POST,
                    authService.getOauthConfig(),
                    GeneralResponse.class
            ).getResult().toString();

            JSONObject response = JSON.parseObject(responseJson);
            if (response != null && !"1000".equals(response.getString("code"))) {
                String errDesc = response.getString("desc");
                if (errDesc != null && errDesc.contains("no need")) {
                    log.info("停止云台移动（设备已停止）：channelId={}, desc={}", channelId, errDesc);
                    return;
                }
                log.warn("停止云台移动失败：{}", errDesc);
                throw new RuntimeException("停止云台移动失败：" + errDesc);
            }

        } catch (ClientException e) {
            log.error("停止云台移动异常：{}", e.getErrMsg(), e);
            throw new RuntimeException("停止云台移动异常：" + e.getErrMsg(), e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("停止云台移动失败：", e);
            throw new RuntimeException("停止云台移动失败：" + e.getMessage(), e);
        }
    }

    // ==================== 镜头控制 ====================

    /**
     * 镜头控制（变焦/聚焦/光圈）
     * <p>
     * 依据大华ICC官方文档 ({@code OperateCamera} API)：
     * <ul>
     *   <li>{@code command="1"} = 开启动作，{@code command="0"} = 停止动作</li>
     *   <li>{@code direct="1"} = 增加（放大），{@code direct="2"} = 减小（缩小）</li>
     *   <li>STOP 需与 START 的 operateType/direct/step 保持一致，否则无法停止</li>
     * </ul>
     *
     * @param channelId 通道ID
     * @param action    动作：zoomIn/zoomOut/focusIn/focusOut/irisIn/irisOut/stop
     * @param speed     速度（1-8，官方文档限制）
     */
    public void operateLens(String channelId, String action, int speed) {
        channelId = normalizeChannelId(channelId);
        validateChannelId(channelId);
        if ("stop".equals(action)) {
            // ★ STOP：使用与 START 一致的参数（官方文档要求）
            LensParams params = lastLensParams.remove(channelId);
            if (params == null) {
                log.info("镜头停止（无历史参数，跳过API）：channelId={}", channelId);
                return;
            }
            executeLensApi(channelId, params.operateType, params.direct, params.step, COMMAND_STOP);
            return;
        }

        String operateType = mapLensActionToOperateType(action);
        if (operateType == null) {
            log.warn("不支持的镜头动作：{}", action);
            return;
        }

        // ★ 正确映射 direct：zoomIn/focusIn/irisIn → "1"(增加)，zoomOut/focusOut/irisOut → "2"(减小)
        String direct = mapLensActionToDirect(action);
        String step = String.valueOf(Math.max(1, Math.min(8, speed)));

        // 保存参数供后续 STOP 使用
        lastLensParams.put(channelId, new LensParams(operateType, direct, step));

        executeLensApi(channelId, operateType, direct, step, COMMAND_START);
    }

    /**
     * 执行镜头控制 API 调用
     *
     * @param channelId   通道ID
     * @param operateType 操作类型：1=变倍, 2=变焦, 3=光圈
     * @param direct      方向：1=增加, 2=减小
     * @param step        速度（1-8）
     * @param command     {@link #COMMAND_START} 或 {@link #COMMAND_STOP}
     */
    private void executeLensApi(String channelId, String operateType, String direct,
                                String step, String command) {
        try {
            Map<String, Object> body = new HashMap<>();
            Map<String, Object> data = new HashMap<>();
            data.put("channelId", channelId);
            data.put("operateType", operateType);
            data.put("direct", direct);
            data.put("step", step);
            data.put("command", command);
            data.put("extend", "");
            body.put("data", data);

            String cmdLabel = COMMAND_START.equals(command) ? "START" : "STOP";
            log.info("镜头控制（{}）：channelId={}, operateType={}, direct={}, step={}",
                    cmdLabel, channelId, operateType, direct, step);

            // ★ STOP 前自适应延迟：保证与最后一次 START 到达平台的间隔 ≥150ms
            if (COMMAND_STOP.equals(command) && !adaptiveDelay(lastLensStartTime.get(channelId))) {
                return; // 线程被中断
            }

            String responseJson = HttpUtils.executeJson(
                    "/evo-apigw/admin/API/DMS/Ptz/OperateCamera",
                    body,
                    null,
                    Method.POST,
                    authService.getOauthConfig(),
                    GeneralResponse.class
            ).getResult().toString();

            // ★ 记录 START 完成时刻，供后续 STOP 自适应延迟计算
            if (COMMAND_START.equals(command)) {
                lastLensStartTime.put(channelId, System.currentTimeMillis());
            }

            JSONObject response = JSON.parseObject(responseJson);
            if (response != null && !"1000".equals(response.getString("code"))) {
                String errDesc = response.getString("desc");
                if (errDesc != null && errDesc.contains("no need")) {
                    log.info("镜头控制（设备无需响应）：channelId={}, desc={}", channelId, errDesc);
                    return;
                }
                log.warn("镜头控制失败：{}", errDesc);
                throw new RuntimeException("镜头控制失败：" + errDesc);
            }

        } catch (ClientException e) {
            log.error("镜头控制异常：{}", e.getErrMsg(), e);
            throw new RuntimeException("镜头控制异常：" + e.getErrMsg(), e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("镜头控制失败：", e);
            throw new RuntimeException("镜头控制失败：" + e.getMessage(), e);
        }
    }

    // ==================== 映射工具方法 ====================

    /**
     * 将前端方向映射为ICC方向控制码
     * <p>
     * 依据大华ICC官方文档：1=上, 2=下, 3=左, 4=右,
     * 5=左上, 6=左下, 7=右上, 8=右下
     */
    private String mapDirectionToCode(String direction) {
        if (direction == null) return null;
        switch (direction.toLowerCase()) {
            case "up":
                return "1";
            case "down":
                return "2";
            case "left":
                return "3";
            case "right":
                return "4";
            case "upleft":
                return "5";
            case "upright":
                return "7";  // 文档：7=右上
            case "downleft":
                return "6";  // 文档：6=左下
            case "downright":
                return "8";
            default:
                return null;
        }
    }

    /**
     * 将前端镜头动作映射为ICC操作类型码
     * <p>
     * 操作类型：1-变倍, 2-聚焦, 3-光圈
     */
    private String mapLensActionToOperateType(String action) {
        if (action == null) return null;
        switch (action.toLowerCase()) {
            case "zoomin":
            case "zoomout":
                return "1";  // 变倍
            case "focusin":
            case "focusout":
                return "2";  // 聚焦
            case "irisin":
            case "irisout":
                return "3";  // 光圈
            default:
                return null;
        }
    }

    /**
     * 将前端镜头动作映射为ICC方向码
     * <p>
     * 依据官方文档：1=增加（放大/聚焦近/光圈大），2=减小（缩小/聚焦远/光圈小）
     */
    private String mapLensActionToDirect(String action) {
        if (action == null) return "1";
        String lower = action.toLowerCase();
        if (lower.endsWith("in")) return "1";   // zoomIn/focusIn/irisIn → 增加
        if (lower.endsWith("out")) return "2";  // zoomOut/focusOut/irisOut → 减小
        return "1";
    }

    /**
     * 自适应延迟：保证 STOP 与最后一次 START 到达平台的间隔 ≥ {@link #MIN_START_STOP_INTERVAL_MS}
     * <p>
     * 距最后一次 START 完成已超过间隔时零延迟直发；不足时仅补足差值。
     * 无 START 记录（如服务重启后）时不延迟。
     *
     * @param lastStartMillis 最后一次 START 完成时间戳，可为null
     * @return true=可继续执行，false=线程被中断应终止
     */
    private boolean adaptiveDelay(Long lastStartMillis) {
        if (lastStartMillis == null) {
            return true; // 无 START 记录，无需排序，直发
        }
        long elapsed = System.currentTimeMillis() - lastStartMillis;
        long remain = MIN_START_STOP_INTERVAL_MS - elapsed;
        if (remain <= 0) {
            return true; // 间隔已足够，零延迟
        }
        try {
            Thread.sleep(remain);
            return true;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 归一化通道ID：将短格式（纯设备编码，如1000230）转换为ICC标准格式
     * <p>
     * 前端可能传入仅含设备编码的短格式（如URL参数 channelId=1000230），
     * 但ICC PTZ API要求完整格式 {@code deviceCode$channelType$...$channelSeq}（如1000230$1$0$0）。
     * 此方法与 {@link DahuaVideoService#getJointHlsUrl} 的默认补齐逻辑保持一致：
     * channelSeq默认为"0"（首通道），channelType默认为"1"（视频通道）。
     * <p>
     * 若channelId已包含'$'分隔符，说明已是标准ICC格式，直接返回原值。
     *
     * @param channelId 原始通道ID（可能是短格式或完整格式）
     * @return 归一化后的ICC标准格式通道ID
     */
    private String normalizeChannelId(String channelId) {
        if (channelId == null || channelId.isEmpty()) {
            return channelId;
        }
        // 已包含'$'分隔符 → 已是ICC标准格式，直接使用
        if (channelId.contains("$")) {
            return channelId;
        }
        // 短格式（纯设备编码）→ 补齐为ICC标准格式：deviceCode$1$0$0
        // $1 = channelType=1（视频通道），$0 = streamType，$0 = channelSeq=0（首通道）
        String normalized = channelId + "$1$0$0";
        log.info("PTZ channelId归一化：{} → {}", channelId, normalized);
        return normalized;
    }

    /**
     * 校验通道ID格式合法性（纵深防御）
     * <p>
     * 依据官方文档：channelId 第一个'$'后的数字代表通道类型，必须为"1"
     * 且摄像头类型需是球机(cameraType=2)。前端已做cameraType校验，
     * 此处仅校验通道格式，不合规时记录告警。
     */
    private void validateChannelId(String channelId) {
        if (channelId == null || channelId.isEmpty()) {
            log.warn("PTZ操作：channelId为空");
            return;
        }
        int firstDollar = channelId.indexOf('$');
        if (firstDollar < 0) {
            log.warn("PTZ操作：channelId格式异常（无$分隔符）: {}", channelId);
            return;
        }
        int secondDollar = channelId.indexOf('$', firstDollar + 1);
        if (secondDollar < 0) {
            log.warn("PTZ操作：channelId格式异常（仅一个$分隔符）: {}", channelId);
            return;
        }
        String channelType = channelId.substring(firstDollar + 1, secondDollar);
        if (!"1".equals(channelType)) {
            log.warn("PTZ操作：通道类型非1（疑似非视频通道），channelId={}, channelType={}", channelId, channelType);
        }
    }
}
