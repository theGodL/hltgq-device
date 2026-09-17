package com.qgyun.hltgq.hltgqdevice.auth;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.annotation.Resource;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 角色权限服务：判定当前用户是否为系统管理员（云台操作等敏感权限）。
 * <p>判定链路（与 hltgq-site 的 RolePermissionService 一致）：
 * <ol>
 *   <li>本地缓存 5 分钟（避免每请求访问 Redis/库）；</li>
 *   <li>Redis 角色缓存（平台维护，TTL 30m）：
 *       LRANGE qx.auth.hltgq.user.{userId} 取 roleId 列表，
 *       逐个 HGET qx.auth.hltgq.role.{roleId} 的 code 字段比对管理员角色编码（hltgq_default_admin / administra）；</li>
 *   <li>Redis 未明确命中管理员角色/异常 → 直连库查角色指派关系兜底（JdbcTemplate），
 *       保证判定与库数据一致（角色刚指派缓存未刷新、缓存值带 JSON 引号等场景不误判）。</li>
 * </ol>
 * <p>角色缓存 key 前缀可配置（{@code auth.role-cache-key-prefix}），
 * 因有查库兜底，前缀不匹配不影响判定正确性，仅影响性能。
 */
@Slf4j
@Service
public class RolePermissionService {

    /** 系统管理员角色编码集合：{corpCode}_default_admin（hltgq 场景）与平台 administra 角色，命中其一即为管理员 */
    private static final List<String> ADMIN_ROLE_CODES = Arrays.asList("hltgq_default_admin", "administra");

    /** 查库 SQL 的管理员角色 IN 列表（'a','b' 形式；编码为代码内常量，无注入风险） */
    private static final String ADMIN_ROLE_SQL_IN = ADMIN_ROLE_CODES.stream()
            .map(code -> "'" + code + "'")
            .collect(Collectors.joining(","));

    /** 角色缓存中"无角色"占位值 */
    private static final String NO_ROLE_PLACEHOLDER = "0";

    /** 判定结果本地缓存 TTL（毫秒） */
    private static final long LOCAL_CACHE_TTL_MS = 5 * 60 * 1000L;

    /** 角色缓存 key 前缀（如 qx.auth.hltgq.），可配置 */
    @Value("${auth.role-cache-key-prefix:qx.auth.hltgq.}")
    private String roleCacheKeyPrefix;

    /** StringRedisTemplate：字段名与自动配置 bean 名一致（stringRedisTemplate），避免 @Resource 按名称注入到 Object 类型 redisTemplate */
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private JdbcTemplate jdbcTemplate;

    /** userId → (判定结果, 过期时间戳) */
    private final ConcurrentHashMap<String, CachedEntry> localCache = new ConcurrentHashMap<>();

    /**
     * 判定用户是否为系统管理员：平台超管（会话 Hash 的 superAdmin=true/1）或拥有管理员角色（hltgq_default_admin / administra）。
     * <p>与 hltgq-site 前端判定（superAdmin）行为对齐：平台超管即使未绑定角色也可操作；
     * 非超管账号仍走角色判定链路（Redis 角色缓存 + 查库兜底）。
     */
    public boolean isAdmin(UserContext user) {
        if (user == null) {
            return false;
        }
        String superAdmin = user.getSuperAdmin();
        if ("true".equalsIgnoreCase(superAdmin) || "1".equals(superAdmin)) {
            log.info("角色判定 userId={}, isAdmin=true（平台超管 superAdmin={}）", user.getUserId(), superAdmin);
            return true;
        }
        return isSystemAdmin(user.getUserId());
    }

    /**
     * 判定用户是否为系统管理员（拥有管理员角色：hltgq_default_admin / administra）
     *
     * @param userId 用户主键（t_apaas_uc_user.id）
     * @return true = 系统管理员
     */
    public boolean isSystemAdmin(String userId) {
        if (!StringUtils.hasText(userId)) {
            return false;
        }
        CachedEntry cached = localCache.get(userId);
        if (cached != null && cached.expireAt > System.currentTimeMillis()) {
            return cached.admin;
        }
        boolean admin = resolveAdmin(userId);
        localCache.put(userId, new CachedEntry(admin, System.currentTimeMillis() + LOCAL_CACHE_TTL_MS));
        log.info("角色判定 userId={}, isAdmin={}", userId, admin);
        return admin;
    }

    /**
     * 真实判定：Redis 角色缓存仅作为快捷判定层，
     * 缓存明确命中管理员角色才直接放行；未命中/无 admin/异常一律查库兜底（保证与库数据一致，
     * 如角色刚指派缓存未刷新、缓存值带 JSON 引号导致 key 拼错等场景）。
     */
    private boolean resolveAdmin(String userId) {
        try {
            List<String> roleIds = stringRedisTemplate.opsForList().range(roleCacheKeyPrefix + "user." + userId, 0, -1);
            if (roleIds != null && !roleIds.isEmpty()) {
                // Redis 命中（空角色列表存 "0" 占位）：遍历角色详情比对 code
                // ★ 平台 Redis 值带 JSON 引号包裹，roleId 必须先剥离引号再拼接 HGET key
                for (String rawRoleId : roleIds) {
                    String roleId = stripQuotes(rawRoleId);
                    if (roleId == null || roleId.isEmpty() || NO_ROLE_PLACEHOLDER.equals(roleId)) {
                        continue;
                    }
                    Object codeValue = stringRedisTemplate.opsForHash().get(roleCacheKeyPrefix + "role." + roleId, "code");
                    if (codeValue != null) {
                        String code = stripQuotes(String.valueOf(codeValue));
                        if (ADMIN_ROLE_CODES.contains(code)) {
                            log.info("角色判定 userId={} Redis 命中管理员角色 code={}", userId, code);
                            return true;
                        }
                    }
                }
            }
            // Redis 未命中或命中但无管理员角色：查库兜底（缓存可能与库不一致，正确性优先）
            return existsAdminRoleInDb(userId);
        } catch (Exception e) {
            log.warn("Redis 角色缓存不可用，降级查库判定 userId={}：{}", userId, e.getMessage());
            return existsAdminRoleInDb(userId);
        }
    }

    /**
     * 查库兜底：直接指派（field_id='USER'）的角色中含管理员角色编码（hltgq_default_admin / administra）即管理员，
     * 黑名单（field_id='BLACK_LIST'）指向该角色时剔除（与 hltgq-site RoleMapper SQL 一致）。
     * <p>查具体 code 而非计数：命中时可输出实际角色编码，供联调核对。
     */
    private boolean existsAdminRoleInDb(String userId) {
        String sql = "SELECT r.code FROM \"qixiao-apaas\".\"t_apaas_auth_role_assign_rel\" rel " +
                "JOIN \"qixiao-apaas\".\"t_apaas_auth_role\" r " +
                "  ON rel.biz_id = r.id AND r.corp_code = 'hltgq' " +
                "WHERE rel.rel_id = ? " +
                "  AND rel.corp_code = 'hltgq' " +
                "  AND rel.field_id = 'USER' " +
                "  AND r.code IN (" + ADMIN_ROLE_SQL_IN + ") " +
                "  AND NOT EXISTS ( " +
                "    SELECT 1 FROM \"qixiao-apaas\".\"t_apaas_auth_role_assign_rel\" bl " +
                "    WHERE bl.rel_id = ? AND bl.field_id = 'BLACK_LIST' AND bl.biz_id = r.id " +
                "  )";
        List<String> codes = jdbcTemplate.queryForList(sql, String.class, userId, userId);
        if (codes != null && !codes.isEmpty()) {
            log.info("角色判定 userId={} 查库兜底命中管理员角色 code={}", userId, codes);
            return true;
        }
        return false;
    }

    /**
     * 剥离 JSON 序列化遗留的首尾双引号（平台 Redis 值可能带引号包裹）。
     */
    private String stripQuotes(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    /**
     * 本地缓存条目
     */
    private static class CachedEntry {
        final boolean admin;
        final long expireAt;

        CachedEntry(boolean admin, long expireAt) {
            this.admin = admin;
            this.expireAt = expireAt;
        }
    }
}
