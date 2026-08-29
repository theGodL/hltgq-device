package com.qgyun.hltgq.hltgqdevice.auth;

/**
 * 会话服务不可用异常：Redis 不可达（鉴权场景禁止降级放行，快速失败 503）
 */
public class SessionUnavailableException extends RuntimeException {

    public SessionUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
