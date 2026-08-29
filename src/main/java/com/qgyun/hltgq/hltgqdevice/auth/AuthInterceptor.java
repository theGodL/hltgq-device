package com.qgyun.hltgq.hltgqdevice.auth;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;

/**
 * 系统管理员权限拦截器：仅校验标注 @RequireAdmin 的敏感接口（当前为云台控制接口），
 * 其他接口（设备树/视频流等）不受影响。
 * <p>判定链路：提取会话ID（Header X-Session-Id / Cookie sessionId）
 * → HGETALL 平台 Redis 会话 Hash → userId → 角色判定（Redis 角色缓存，查库兜底）。
 * <p>响应语义（与 hltgq-site 的 AuthInterceptor 一致）：
 * <ul>
 *   <li>未登录/会话过期 → 401；</li>
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

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!(handler instanceof HandlerMethod)) {
            return true;
        }
        HandlerMethod handlerMethod = (HandlerMethod) handler;
        RequireAdmin requireAdmin = handlerMethod.getMethodAnnotation(RequireAdmin.class);
        if (requireAdmin == null) {
            requireAdmin = handlerMethod.getBeanType().getAnnotation(RequireAdmin.class);
        }
        // 非敏感接口：不做登录/权限校验（保持设备树、视频流等功能开放）
        if (requireAdmin == null) {
            return true;
        }

        String path = request.getRequestURI();
        try {
            String sessionId = sessionContextService.extractSessionId(request);
            if (sessionId == null) {
                log.warn("云台权限拦截：{} {} 未携带会话ID", request.getMethod(), path);
                writeJson(response, HttpServletResponse.SC_UNAUTHORIZED, "{\"code\":401,\"message\":\"未登录，云台操作需登录后使用\"}");
                return false;
            }
            UserContext user = sessionContextService.resolveUser(sessionId);
            UserContextHolder.set(user);
            // 平台超管（superAdmin）或绑定 hltgq_default_admin 角色的用户均可操作云台
            if (rolePermissionService.isAdmin(user)) {
                return true;
            }
            log.warn("云台权限拦截：{} {} userId={} 无系统管理员角色", request.getMethod(), path, user.getUserId());
            writeJson(response, HttpServletResponse.SC_FORBIDDEN, "{\"code\":403,\"message\":\"无操作权限，仅系统管理员可操作云台\"}");
            return false;
        } catch (UnauthorizedException e) {
            log.warn("云台权限拦截：{} {} - {}", request.getMethod(), path, e.getMessage());
            writeJson(response, HttpServletResponse.SC_UNAUTHORIZED, "{\"code\":401,\"message\":\"未登录或会话已过期\"}");
            return false;
        } catch (SessionUnavailableException e) {
            log.error("会话服务不可用：{} {} - {}", request.getMethod(), path, e.getMessage());
            writeJson(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "{\"code\":503,\"message\":\"会话服务不可用，请稍后重试\"}");
            return false;
        }
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        UserContextHolder.clear();
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
