package com.qgyun.hltgq.hltgqdevice.videoalert;

/**
 * 视频设备故障类型（15 类常见摄像机故障）。
 * <p>告警级别字典（与 hltgq-mq 一致）：{@code #1#} 一般、{@code #2#} 较重、{@code #3#} 严重、{@code #4#} 特别严重。
 * <p>{@code phaseOne} 标记一期实施范围（A 层流协议 + B 层基础图像指标，共 10 类），
 * C 层时序/高级分析类（抖动/条纹/畸变/场景变更/云台失控）二期评估后启用。
 * <p>智能类告警（画面冻结/视频遮挡等）不在此枚举：由大华 IVSS 智能分析事件推送接入
 * （{@code VideoEventService}），本项目不代理大华做出智能告警。
 */
public enum VideoFaultType {

    // ============ A 层：流协议层（无需解码） ============
    /** 信号丢失：设备树 isOnline=0（摄像机掉电/断网） */
    SIGNAL_LOSS("信号丢失", "#3#", true),

    /** 取流异常：设备在线但流不可取（M3U8/TS 拉取失败、抽帧失败） */
    STREAM_ERROR("取流异常", "#3#", true),

    /** 登录失败：ICC 平台鉴权失败（token 不可获取，轮级全局判定，不落通道告警） */
    LOGIN_FAIL("登录失败", "#3#", true),

    // ============ B 层：图像层基础指标（ffmpeg 抽帧 + 纯 Java 像素统计） ============
    /** 图像过亮：亮度均值/亮像素占比超阈值 */
    TOO_BRIGHT("图像过亮", "#2#", true),

    /** 图像过暗：亮度均值/暗像素占比超阈值（夜间无补光场景易误报，现场调优） */
    TOO_DARK("图像过暗", "#2#", true),

    /** 图像偏色：RGB 通道均值偏差过大 */
    COLOR_CAST("图像偏色", "#2#", true),

    /** 黑白图像：饱和度均值过低（彩色相机输出灰度画面） */
    GRAYSCALE("黑白图像", "#2#", true),

    /** 图像模糊：Laplacian 方差过低（失焦/镜头脏污） */
    BLUR("图像模糊", "#2#", true),

    /** 对比度异常：灰度标准差过低（画面灰蒙） */
    LOW_CONTRAST("对比度", "#2#", true),

    /** 噪声干扰：高频噪声残差过大（信号弱/电磁干扰） */
    NOISE("噪声干扰", "#2#", true),

    // ============ C 层：时序/高级分析（二期实施） ============
    /** 视频抖动：帧间全局位移波动过大 */
    JITTER("视频抖动", "#2#", false),

    /** 条纹干扰：画面周期性条纹（行方差周期分析） */
    STRIPE("条纹干扰", "#2#", false),

    /** 视频畸变：边缘几何异常 */
    DISTORTION("视频畸变", "#2#", false),

    /** 场景变更：场景指纹突变并持续（人工挪机也可能触发，误报风险高） */
    SCENE_CHANGE("场景变更", "#1#", false),

    /** 云台失控：PTZ 指令发出后画面无变化 */
    PTZ_OUT_OF_CONTROL("云台失控", "#3#", false);

    /** 故障名称（告警内容："{站点名} 视频{label}！"） */
    private final String label;

    /** 默认告警级别（#1#~#4#） */
    private final String defaultLevel;

    /** 一期是否启用检测 */
    private final boolean phaseOne;

    VideoFaultType(String label, String defaultLevel, boolean phaseOne) {
        this.label = label;
        this.defaultLevel = defaultLevel;
        this.phaseOne = phaseOne;
    }

    public String getLabel() {
        return label;
    }

    public String getDefaultLevel() {
        return defaultLevel;
    }

    public boolean isPhaseOne() {
        return phaseOne;
    }
}
