package com.qgyun.hltgq.hltgqdevice.auth;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.annotation.Resource;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import java.util.Map;

/**
 * 会话上下文服务：从 APaaS 平台 Redis 会话解析当前登录人。
 * <p>会话来源：平台登录后 cookie 中的 sessionId（形如 dev_hltgq_session:xxxx），
 * 该值即 Redis Hash key，HGETALL 取用户上下文（userId/corpCode/superAdmin）。
 * <p>平台对 Hash 值做过 JSON 序列化（字符串值带双引号包裹），取值必须剥离首尾引号。
 * <p>注意：鉴权场景 Redis 异常禁止降级放行，抛 SessionUnavailableException 快速失败。
 * 实现与 hltgq-site 的 SessionContextService 保持一致（同平台、同会话体系）。
 */
@Slf4j
@Service
public class SessionContextService {

    /** Header 传递会话 ID（平台服务端代理场景） */
    public static final String HEADER_SESSION_ID = "X-Session-Id";

    /** Cookie 传递会话 ID（浏览器直连场景） */
    public static final String COOKIE_SESSION_ID = "sessionId";

    /** StringRedisTemplate：字段名与自动配置 bean 名一致（stringRedisTemplate），避免 @Resource 按名称注入到 Object 类型 redisTemplate */
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 从请求提取会话 ID：优先 Header（X-Session-Id），其次 Cookie（sessionId）
     *
     * @return 会话 ID，未携带返回 null
     */
    public String extractSessionId(HttpServletRequest request) {
        String headerValue = request.getHeader(HEADER_SESSION_ID);
        if (StringUtils.hasText(headerValue)) {
            return headerValue.trim();
        }
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (COOKIE_SESSION_ID.equals(cookie.getName()) && StringUtils.hasText(cookie.getValue())) {
                    return cookie.getValue().trim();
                }
            }
        }
        return null;
    }

    /**
     * 解析当前请求登录人（提取会话 ID → 解析用户上下文），供接口直接调用。
     */
    public UserContext resolveCurrentUser(HttpServletRequest request) {
        String sessionId = extractSessionId(request);
        if (sessionId == null) {
            log.warn("未登录：请求未携带会话 ID（Header/Cookie 均缺失）");
            throw new UnauthorizedException("未登录：缺少会话 ID");
        }
        return resolveUser(sessionId);
    }

    /**
     * 按会话 ID 解析用户上下文：HGETALL {sessionId}，空 Hash 视为未登录/已过期。
     */
    public UserContext resolveUser(String sessionId) {
        Map<Object, Object> entries;
        try {
            entries = stringRedisTemplate.opsForHash().entries(sessionId);
        } catch (Exception e) {
            // 鉴权场景禁止降级放行：Redis 不可达时快速失败
            log.error("会话服务不可用：HGETALL {} 异常", sessionId, e);
            throw new SessionUnavailableException("会话服务不可用，请稍后重试", e);
        }
        if (entries == null || entries.isEmpty()) {
            log.warn("未登录或会话过期：session {} 无用户上下文", sessionId);
            throw new UnauthorizedException("未登录或会话已过期");
        }

        UserContext user = new UserContext();
        // 仅解析会话 Hash 实际存在的字段（实测仅有 userId/corpCode/superAdmin 有值）
        user.setUserId(firstOf(entries, "userId", "user_id", "id"));
        user.setCorpCode(firstOf(entries, "corpCode", "corp_code"));
        user.setSuperAdmin(firstOf(entries, "superAdmin", "super_admin"));

        log.debug("会话解析完成 sessionId={}, userId={}, corpCode={}, superAdmin={}",
                sessionId, user.getUserId(), user.getCorpCode(), user.getSuperAdmin());
        return user;
    }

    /**
     * 按候选 key 顺序取会话 Hash 字段值（兼容字段命名变体），全部缺失返回 null。
     * <p>平台存储时对值做过 JSON 序列化（字符串值带双引号包裹），统一剥离首尾引号，
     * 避免查库条件携带引号字符导致不匹配。
     */
    private String firstOf(Map<Object, Object> entries, String... keys) {
        for (String key : keys) {
            Object value = entries.get(key);
            if (value != null && StringUtils.hasText(String.valueOf(value))) {
                return normalizeValue(String.valueOf(value));
            }
        }
        return null;
    }

    /**
     * 规范化 Hash 字段值：剥离 JSON 序列化遗留的首尾双引号。
     */
    private String normalizeValue(String value) {
        String trimmed = value.trim();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }
}
