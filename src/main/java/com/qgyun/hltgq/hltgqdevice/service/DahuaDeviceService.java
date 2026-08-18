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
import java.util.*;

/**
 * 大华设备树查询服务
 * <p>
 * 负责查询ICC平台的组织/设备/通道树形结构
 *
 * @author hltgq-device
 */
@Slf4j
@Service
public class DahuaDeviceService {

    @Resource
    private DahuaAuthService authService;

    /**
     * 查询设备树节点
     * <p>
     * 根据父节点ID查询其直接子节点列表（懒加载模式）
     *
     * @param orgId 父节点组织ID，为空或"001"时查询根节点
     * @return 子节点列表
     */
    public List<DeviceTreeNode> getDeviceTree(String orgId) {
        return getDeviceTreeWithStatus(orgId).getNodes();
    }

    /**
     * 查询设备树节点（带成功标识）
     * <p>
     * 供站点同步定时任务区分"API调用失败"与"无子节点"：二者返回的节点列表
     * 都为空，但前者意味着本轮遍历不完整，不能据此把缺失通道标注为离线
     * （会误伤整个子树），必须通过success标识区分。
     *
     * @param orgId 父节点组织ID，为空或"001"时查询根节点
     * @return 查询结果（success=false表示API调用失败）
     */
    public DeviceTreeResult getDeviceTreeWithStatus(String orgId) {
        try {
            // 处理空orgId，默认查询根组织001
            String parentId = (orgId == null || orgId.trim().isEmpty()) ? "001" : orgId.trim();

            // 构建请求参数
            Map<String, Object> body = new HashMap<>();
            body.put("type", "001;;1");
            body.put("id", parentId);
            body.put("checkStat", 1);

            log.info("查询设备树，parentId={}", parentId);

            // 调用ICC设备树API
            GeneralResponse gr = HttpUtils.executeJson(
                    "/evo-apigw/evo-brm/1.0.0/tree",
                    body,
                    null,
                    Method.POST,
                    authService.getOauthConfig(),
                    GeneralResponse.class
            );

            // gr.getResult() 可能已是String，不需要再用 toJSONString 包装
            Object result = gr.getResult();
            String responseJson = result instanceof String ? (String) result : JSON.toJSONString(result);
            // 调试级日志：站点同步定时任务会递归调用本方法数百次，INFO级整段响应会洪泛日志
            log.debug("设备树查询响应：{}", responseJson);

            JSONObject response = JSON.parseObject(responseJson);
            // ICC API使用code="0"表示成功，success=true也表示成功
            if (response == null || !"0".equals(response.getString("code"))) {
                log.warn("设备树查询失败：{}", response);
                return DeviceTreeResult.fail();
            }

            // data.value 才是节点数组（data是{"value":[...]}结构）
            JSONObject dataObj = response.getJSONObject("data");
            if (dataObj == null) {
                log.warn("设备树响应data为空");
                return DeviceTreeResult.fail();
            }
            List<DeviceTreeNode> nodes = JSON.parseArray(
                    dataObj.getString("value"),
                    DeviceTreeNode.class
            );

            return DeviceTreeResult.success(nodes != null ? nodes : Collections.emptyList());

        } catch (ClientException e) {
            log.error("设备树查询异常：{}", e.getErrMsg(), e);
            return DeviceTreeResult.fail();
        } catch (Exception e) {
            log.error("设备树查询失败：", e);
            return DeviceTreeResult.fail();
        }
    }

    /**
     * 设备树查询结果：success=false表示API调用失败（与"无子节点"区分）
     */
    public static class DeviceTreeResult {
        private final boolean success;
        private final List<DeviceTreeNode> nodes;

        private DeviceTreeResult(boolean success, List<DeviceTreeNode> nodes) {
            this.success = success;
            this.nodes = nodes;
        }

        public static DeviceTreeResult success(List<DeviceTreeNode> nodes) {
            return new DeviceTreeResult(true, nodes);
        }

        public static DeviceTreeResult fail() {
            return new DeviceTreeResult(false, Collections.emptyList());
        }

        public boolean isSuccess() {
            return success;
        }

        public List<DeviceTreeNode> getNodes() {
            return nodes;
        }
    }

    /**
     * 设备树节点DTO（映射前端需要的数据结构）
     */
    public static class DeviceTreeNode {
        /** 节点ID */
        private String id;
        /** 节点名称 */
        private String name;
        /** 父节点ID */
        private String pId;
        /** 节点类型：org-组织, dev-设备, ch-通道 */
        private String nodeType;
        /** 是否为父节点（有子节点） */
        private Boolean isParent;
        /** 在线状态 0-离线 1-在线 */
        private Integer checkStat;
        /** 在线标识 0-离线 1-在线 */
        private Integer isOnline;
        /** 排序号 */
        private Integer sort;
        /** 设备编码 */
        private String deviceCode;
        /** 摄像头类型：1=枪机(固定) 2=球机(PTZ) 3=半球 4=云台 */
        private Integer cameraType;

        // ========== Getters & Setters ==========

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getpId() {
            return pId;
        }

        public void setpId(String pId) {
            this.pId = pId;
        }

        public String getNodeType() {
            return nodeType;
        }

        public void setNodeType(String nodeType) {
            this.nodeType = nodeType;
        }

        public Boolean getIsParent() {
            return isParent;
        }

        public void setIsParent(Boolean isParent) {
            this.isParent = isParent;
        }

        public Integer getCheckStat() {
            return checkStat;
        }

        public void setCheckStat(Integer checkStat) {
            this.checkStat = checkStat;
        }

        public Integer getIsOnline() {
            return isOnline;
        }

        public void setIsOnline(Integer isOnline) {
            this.isOnline = isOnline;
        }

        public Integer getSort() {
            return sort;
        }

        public void setSort(Integer sort) {
            this.sort = sort;
        }

        public String getDeviceCode() {
            return deviceCode;
        }

        public void setDeviceCode(String deviceCode) {
            this.deviceCode = deviceCode;
        }

        public Integer getCameraType() {
            return cameraType;
        }

        public void setCameraType(Integer cameraType) {
            this.cameraType = cameraType;
        }
    }
}
