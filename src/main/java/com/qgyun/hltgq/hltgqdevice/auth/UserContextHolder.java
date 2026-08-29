package com.qgyun.hltgq.hltgqdevice.auth;

/**
 * 当前请求用户上下文持有器（ThreadLocal）
 * <p>由 AuthInterceptor 在 preHandle 写入、afterCompletion 清理；
 * 业务代码通过 {@link #currentUser()} 获取，非敏感接口（未标注@RequireAdmin）时为 null。
 */
public final class UserContextHolder {

    private static final ThreadLocal<UserContext> HOLDER = new ThreadLocal<>();

    private UserContextHolder() {
    }

    public static void set(UserContext user) {
        HOLDER.set(user);
    }

    /**
     * 当前请求登录人，未经过权限校验（非敏感接口）时为 null
     */
    public static UserContext currentUser() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
