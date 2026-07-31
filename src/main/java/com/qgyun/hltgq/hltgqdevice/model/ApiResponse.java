package com.qgyun.hltgq.hltgqdevice.model;

/**
 * 统一API响应模型
 *
 * @author hltgq-device
 */
public class ApiResponse<T> {

    /** 是否成功 */
    private boolean success;

    /** 响应码 */
    private String code;

    /** 描述信息 */
    private String desc;

    /** 数据体 */
    private T data;

    public ApiResponse() {
    }

    public ApiResponse(boolean success, String code, String desc, T data) {
        this.success = success;
        this.code = code;
        this.desc = desc;
        this.data = data;
    }

    /**
     * 成功响应
     */
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, "0", "success", data);
    }

    /**
     * 成功响应（带自定义消息）
     */
    public static <T> ApiResponse<T> success(String desc, T data) {
        return new ApiResponse<>(true, "0", desc, data);
    }

    /**
     * 失败响应
     */
    public static <T> ApiResponse<T> fail(String code, String desc) {
        return new ApiResponse<>(false, code, desc, null);
    }

    /**
     * 失败响应
     */
    public static <T> ApiResponse<T> fail(String desc) {
        return new ApiResponse<>(false, "-1", desc, null);
    }

    // ========== Getters & Setters ==========

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getDesc() {
        return desc;
    }

    public void setDesc(String desc) {
        this.desc = desc;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }
}
