package com.qgyun.hltgq.hltgqdevice.model;

/**
 * ICC历史录像段信息（对应 /evo-apigw/admin/API/SS/Record/QueryRecords 返回的 records 元素）
 * <p>
 * 录像按文件存储，每个文件有独立起止时间；录像回放不支持跨文件，
 * 前端须按段切片展示时间轴，播放时段必须落在同一条录像段内。
 */
public class RecordSegment {

    /** 通道编码（如 1000230$1$0$0） */
    private String channelId;

    /** 录像来源：1=全部，2=设备，3=中心 */
    private String recordSource;

    /** 录像类型：0=全部，1=远程/手动录像，2=报警录像，6=定时录像（普通录像） */
    private String recordType;

    /** 开始时间（时间戳：单位秒） */
    private String startTime;

    /** 结束时间（时间戳：单位秒） */
    private String endTime;

    /** 录像文件名（不同厂家标识不同） */
    private String recordName;

    /** 文件长度，单位KB */
    private String fileLength;

    /** 录像计划ID */
    private String planId;

    /** 存储服务ID */
    private String ssId;

    /** 磁盘ID */
    private String diskId;

    /** 码流处理(StreamId) */
    private String streamId;

    /** 是否淡忘 */
    private String forgotten;

    /** 码流类型：0=全部，1=主码流，2=辅码流 */
    private String streamType;

    /** 中心录像类型：1=普通录像 2=报警录像 81=补录录像 82=预录录像（按时间播放时可用此值替换recordType） */
    private String videoRecordType;

    // ========== Getters & Setters ==========

    public String getChannelId() {
        return channelId;
    }

    public void setChannelId(String channelId) {
        this.channelId = channelId;
    }

    public String getRecordSource() {
        return recordSource;
    }

    public void setRecordSource(String recordSource) {
        this.recordSource = recordSource;
    }

    public String getRecordType() {
        return recordType;
    }

    public void setRecordType(String recordType) {
        this.recordType = recordType;
    }

    public String getStartTime() {
        return startTime;
    }

    public void setStartTime(String startTime) {
        this.startTime = startTime;
    }

    public String getEndTime() {
        return endTime;
    }

    public void setEndTime(String endTime) {
        this.endTime = endTime;
    }

    public String getRecordName() {
        return recordName;
    }

    public void setRecordName(String recordName) {
        this.recordName = recordName;
    }

    public String getFileLength() {
        return fileLength;
    }

    public void setFileLength(String fileLength) {
        this.fileLength = fileLength;
    }

    public String getPlanId() {
        return planId;
    }

    public void setPlanId(String planId) {
        this.planId = planId;
    }

    public String getSsId() {
        return ssId;
    }

    public void setSsId(String ssId) {
        this.ssId = ssId;
    }

    public String getDiskId() {
        return diskId;
    }

    public void setDiskId(String diskId) {
        this.diskId = diskId;
    }

    public String getStreamId() {
        return streamId;
    }

    public void setStreamId(String streamId) {
        this.streamId = streamId;
    }

    public String getForgotten() {
        return forgotten;
    }

    public void setForgotten(String forgotten) {
        this.forgotten = forgotten;
    }

    public String getStreamType() {
        return streamType;
    }

    public void setStreamType(String streamType) {
        this.streamType = streamType;
    }

    public String getVideoRecordType() {
        return videoRecordType;
    }

    public void setVideoRecordType(String videoRecordType) {
        this.videoRecordType = videoRecordType;
    }
}
