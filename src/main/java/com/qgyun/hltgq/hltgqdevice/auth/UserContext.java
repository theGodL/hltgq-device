package com.qgyun.hltgq.hltgqdevice.auth;

import lombok.Data;

/**
 * 当前登录人上下文
 * <p>数据来源：APaaS 平台 Redis 会话 Hash（HGETALL {sessionId}）。
 * 仅保留会话 Hash 实际存在的字段（实测仅 userId/corpCode/superAdmin 有值，
 * 且值带 JSON 双引号包裹，解析时统一剥离）。
 */
@Data
public class UserContext {

    /** 用户主键（t_apaas_uc_user.id） */
    private String userId;

    /** 企业编码 */
    private String corpCode;

    /** 是否超级管理员（平台会话字段，仅供参考；权限判定以角色为准） */
    private String superAdmin;
}
