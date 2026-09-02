package com.qgyun.hltgq.hltgqdevice.auth;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 登录验证拦截器：所有页面与接口均需登录校验（白名单除外）。
 * <p>判定链路：提取会话 ID（Header X-Session-Id / Authorization / Cookie sessionId，
 * 任意来源携带形如 dev_hltgq_session:xxxx 的会话键即视为携带登录凭证）
 * → HGETALL 平台 Redis 会话 Hash → 会话存在即登录。
 * <p>登录通过后，标注 @RequireAdmin 的敏感接口（当前为云台控制）额外校验系统管理员角色。
 * <p>响应语义（与 hltgq-site 的 AuthInterceptor 一致）：
 * <ul>
 *   <li>未登录：浏览器导航（Accept 含 text/html）302 跳转平台登录页；AJAX/API 返回 401 JSON；</li>
 *   <li>非系统管理员 → 403；</li>
 *   <li>会话服务不可用（Redis 故障）→ 503，禁止降级放行。</li>
 * </ul>
 */
@Slf4j
@Component
public class AuthInterceptor implements HandlerInterceptor {

    @Resource
    private SessionContextService sessionContextService;

    @Resource
    private RolePermissionService rolePermissionService;

    /** 平台登录页地址（未登录页面导航跳转），与 hltgq-site 同平台同地址 */
    @Value("${auth.login-page-url:http://220.179.1.110:8081/login/user/login}")
    private String loginPageUrl;

    /** 可配置白名单（逗号分隔，前缀匹配），与代码固定白名单合并 */
    @Value("${auth.white-list:}")
    private String whiteListConfig;

    /** 代码固定白名单（不受配置影响） */
    private static final Set<String> FIXED_WHITE_LIST = new HashSet<>(Arrays.asList(
            "/error"
    ));

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        String relativePath = path.substring(contextPath.length());
        if (isWhiteListed(relativePath)) {
            return true;
        }

        try {
            String sessionId = sessionContextService.extractSessionId(request);
            if (sessionId == null) {
                log.warn("未登录：{} {} 未携带会话 ID", request.getMethod(), relativePath);
                handleUnauthorized(request, response);
                return false;
            }
            UserContext user = sessionContextService.resolveUser(sessionId);
            UserContextHolder.set(user);
            if (!checkAdminPermission(request, response, handler, relativePath, user)) {
                return false;
            }
            return true;
        } catch (UnauthorizedException e) {
            log.warn("未登录：{} {} - {}", request.getMethod(), relativePath, e.getMessage());
            handleUnauthorized(request, response);
            return false;
        } catch (SessionUnavailableException e) {
            log.error("会话服务不可用：{} {} - {}", request.getMethod(), relativePath, e.getMessage());
            writeJson(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "{\"code\":503,\"message\":\"会话服务不可用，请稍后重试\"}");
            return false;
        }
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        UserContextHolder.clear();
    }

    /**
     * 系统管理员权限校验：方法/类标注 @RequireAdmin 的敏感写接口（当前为云台控制），
     * 校验当前登录人是否拥有系统管理员角色，非管理员返回 403。
     * <p>静态资源等非 HandlerMethod 请求不涉及角色校验，直接放行（登录校验仍生效）。
     * <p>权限判定服务异常（Redis/库均不可用）时不降级放行，返回 503（与登录鉴权同策略）。
     */
    private boolean checkAdminPermission(HttpServletRequest request, HttpServletResponse response,
                                         Object handler, String relativePath, UserContext user) throws Exception {
        if (!(handler instanceof HandlerMethod)) {
            return true;
        }
        HandlerMethod handlerMethod = (HandlerMethod) handler;
        RequireAdmin requireAdmin = handlerMethod.getMethodAnnotation(RequireAdmin.class);
        if (requireAdmin == null) {
            requireAdmin = handlerMethod.getBeanType().getAnnotation(RequireAdmin.class);
        }
        if (requireAdmin == null) {
            return true;
        }
        String userId = user == null ? null : user.getUserId();
        try {
            // 平台超管（superAdmin）或绑定 hltgq_default_admin 角色的用户均可操作
            if (rolePermissionService.isAdmin(user)) {
                return true;
            }
            log.warn("无操作权限：{} {} userId={}", request.getMethod(), relativePath, userId);
            writeJson(response, HttpServletResponse.SC_FORBIDDEN,
                    "{\"code\":403,\"message\":\"无操作权限，仅系统管理员可操作\"}");
            return false;
        } catch (Exception e) {
            log.error("权限判定服务不可用：{} {} - {}", request.getMethod(), relativePath, e.getMessage());
            writeJson(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "{\"code\":503,\"message\":\"权限服务不可用，请稍后重试\"}");
            return false;
        }
    }

    /**
     * 白名单判定：配置白名单 + 代码固定白名单，均按前缀匹配。
     */
    private boolean isWhiteListed(String path) {
        for (String item : FIXED_WHITE_LIST) {
            if (path.startsWith(item)) {
                return true;
            }
        }
        if (StringUtils.hasText(whiteListConfig)) {
            for (String item : whiteListConfig.split(",")) {
                String trimmed = item.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                if (path.startsWith(trimmed)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 未登录处理：页面导航 302 跳转登录页；AJAX/API 返回 401 JSON（携带 redirectUrl 供前端跳转）
     */
    private void handleUnauthorized(HttpServletRequest request, HttpServletResponse response) throws Exception {
        String accept = request.getHeader("Accept");
        if (accept != null && accept.contains("text/html")) {
            response.sendRedirect(loginPageUrl);
            return;
        }
        writeJson(response, HttpServletResponse.SC_UNAUTHORIZED,
                "{\"code\":401,\"message\":\"未登录\",\"redirectUrl\":\"" + loginPageUrl + "\"}");
    }

    /**
     * 输出 JSON 响应
     */
    private void writeJson(HttpServletResponse response, int status, String body) throws Exception {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(body);
    }
}
