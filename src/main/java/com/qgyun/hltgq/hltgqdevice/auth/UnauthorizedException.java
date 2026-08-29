package com.qgyun.hltgq.hltgqdevice.auth;

/**
 * 未登录/会话失效异常：请求未携带会话ID或会话Hash为空
 */
public class UnauthorizedException extends RuntimeException {

    public UnauthorizedException(String message) {
        super(message);
    }
}
