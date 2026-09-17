package com.qgyun.hltgq.hltgqdevice.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 角色权限判定回归测试：管理员角色编码集合（hltgq_default_admin / administra）。
 * <p>覆盖：平台超管直接放行（不查 Redis/库）；Redis 角色缓存链路命中任一管理员编码即通过；
 * 未命中/异常 → 查库兜底（SQL 覆盖两个编码且保留黑名单剔除）；本地缓存 TTL 内不重复访问 Redis。
 */
@SuppressWarnings("unchecked")
class RolePermissionServiceTest {

    private static final String PREFIX = "qx.auth.hltgq.";
    private static final String USER_ID = "user-001";

    private RolePermissionService service;
    private StringRedisTemplate stringRedisTemplate;
    private ListOperations<String, String> listOps;
    private HashOperations<String, Object, Object> hashOps;
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        service = new RolePermissionService();
        stringRedisTemplate = mock(StringRedisTemplate.class);
        listOps = mock(ListOperations.class);
        hashOps = mock(HashOperations.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", stringRedisTemplate);
        ReflectionTestUtils.setField(service, "jdbcTemplate", jdbcTemplate);
        ReflectionTestUtils.setField(service, "roleCacheKeyPrefix", PREFIX);
        when(stringRedisTemplate.opsForList()).thenReturn(listOps);
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOps);
    }

    /** 平台超管（superAdmin=true/1）直接判定通过，不查 Redis/库 */
    @Test
    void superAdminFlagIsAdminWithoutLookup() {
        UserContext user = new UserContext();
        user.setUserId(USER_ID);
        user.setSuperAdmin("true");
        assertTrue(service.isAdmin(user), "superAdmin=true 应为管理员");

        UserContext flagOne = new UserContext();
        flagOne.setUserId(USER_ID);
        flagOne.setSuperAdmin("1");
        assertTrue(service.isAdmin(flagOne), "superAdmin=1 应为管理员");

        verifyNoInteractions(jdbcTemplate);

        assertFalse(service.isAdmin(null), "空用户上下文应返回 false");
    }

    /** Redis 命中 hltgq_default_admin（平台值带 JSON 引号包裹）→ 管理员，不查库 */
    @Test
    void redisHitDefaultAdminCode() {
        givenRoles("role-1");
        when(hashOps.get(PREFIX + "role.role-1", "code")).thenReturn("\"hltgq_default_admin\"");

        assertTrue(service.isSystemAdmin(USER_ID), "hltgq_default_admin 角色应为管理员");
        verifyNoInteractions(jdbcTemplate);
    }

    /** Redis 命中 administra → 管理员（平台 administra 角色），不查库 */
    @Test
    void redisHitAdministraCode() {
        givenRoles("\"role-2\"");
        when(hashOps.get(PREFIX + "role.role-2", "code")).thenReturn("\"administra\"");

        assertTrue(service.isSystemAdmin(USER_ID), "administra 角色应为管理员");
        verifyNoInteractions(jdbcTemplate);
    }

    /** Redis 命中非管理员角色 → 查库兜底（角色刚指派缓存未刷新场景） */
    @Test
    void redisMissFallsBackToDb() {
        givenRoles("role-3");
        when(hashOps.get(PREFIX + "role.role-3", "code")).thenReturn("user");
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), anyString(), anyString()))
                .thenReturn(Collections.singletonList("administra"));

        assertTrue(service.isSystemAdmin(USER_ID), "Redis 未命中应查库兜底");
    }

    /** Redis 异常 → 降级查库兜底 */
    @Test
    void redisExceptionFallsBackToDb() {
        when(listOps.range(anyString(), anyLong(), anyLong()))
                .thenThrow(new RuntimeException("redis down"));
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), anyString(), anyString()))
                .thenReturn(Collections.singletonList("hltgq_default_admin"));

        assertTrue(service.isSystemAdmin(USER_ID), "Redis 异常应降级查库判定");
    }

    /** 查库 SQL 必须覆盖两个管理员编码，且保留黑名单剔除逻辑 */
    @Test
    void dbSqlCoversBothAdminCodes() {
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), anyString(), anyString()))
                .thenReturn(Collections.emptyList());

        assertFalse(service.isSystemAdmin(USER_ID), "库中无管理员角色应返回 false");

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForList(sqlCaptor.capture(), eq(String.class), eq(USER_ID), eq(USER_ID));
        String sql = sqlCaptor.getValue();
        assertTrue(sql.contains("'hltgq_default_admin'"), "SQL 应包含 hltgq_default_admin，实际：" + sql);
        assertTrue(sql.contains("'administra'"), "SQL 应包含 administra，实际：" + sql);
        assertTrue(sql.contains("IN (") && sql.contains("BLACK_LIST"), "SQL 应保留 IN 列表与黑名单剔除");
    }

    /** 本地缓存：TTL 内二次判定直接返回，不再访问 Redis */
    @Test
    void secondCallUsesLocalCache() {
        givenRoles("role-4");
        when(hashOps.get(PREFIX + "role.role-4", "code")).thenReturn("administra");

        assertTrue(service.isSystemAdmin(USER_ID), "首次判定应为管理员");
        assertTrue(service.isSystemAdmin(USER_ID), "二次判定命中本地缓存");

        verify(listOps, times(1)).range(eq(PREFIX + "user." + USER_ID), eq(0L), eq(-1L));
    }

    /** 空白 userId 直接返回 false，不访问任何依赖 */
    @Test
    void blankUserIdReturnsFalse() {
        assertFalse(service.isSystemAdmin("  "), "空白 userId 应返回 false");
        assertFalse(service.isSystemAdmin(null), "null userId 应返回 false");
        verifyNoInteractions(jdbcTemplate);
    }

    /** stub 用户角色列表（LRANGE user.{userId}），入参可带 JSON 引号模拟平台实际值 */
    private void givenRoles(String... rawRoleIds) {
        when(listOps.range(PREFIX + "user." + USER_ID, 0L, -1L))
                .thenReturn(Arrays.asList(rawRoleIds));
    }
}
