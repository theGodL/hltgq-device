package com.qgyun.hltgq.hltgqdevice.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 未登录跳转登录页的回归测试：登录页按请求来源区分——
 * H5 请求（/mobile/ 路径的页面导航、Referer 含 /mobile/ 的接口请求）→ 平台 H5 登录页；
 * 其余（PC 页面导航、PC 页面发起的接口请求）→ 平台 PC 登录页。
 */
class AuthInterceptorTest {

    private static final String PC_LOGIN = "http://220.179.1.110:8081/login/user/login";
    private static final String H5_LOGIN = "http://220.179.1.110:8081/hlt/#/login/user/login";

    private AuthInterceptor interceptor;
    private SessionContextService sessionContextService;
    private HttpServletRequest request;
    private HttpServletResponse response;
    private StringWriter writer;

    @BeforeEach
    void setUp() throws Exception {
        interceptor = new AuthInterceptor();
        sessionContextService = mock(SessionContextService.class);
        ReflectionTestUtils.setField(interceptor, "sessionContextService", sessionContextService);
        ReflectionTestUtils.setField(interceptor, "rolePermissionService",
                mock(RolePermissionService.class));
        ReflectionTestUtils.setField(interceptor, "loginPageUrl", PC_LOGIN);
        ReflectionTestUtils.setField(interceptor, "loginPageUrlH5", H5_LOGIN);
        ReflectionTestUtils.setField(interceptor, "whiteListConfig", "");

        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
        writer = new StringWriter();
        when(request.getContextPath()).thenReturn("");
        when(request.getMethod()).thenReturn("GET");
        when(response.getWriter()).thenReturn(new PrintWriter(writer));
    }

    /** 未登录的请求走 handleUnauthorized 分支 */
    private void givenNoSession() {
        when(sessionContextService.extractSessionId(request)).thenReturn(null);
    }

    /** H5 页面直接导航（无会话）→ 302 跳 H5 登录页 */
    @Test
    void h5PageNavigationRedirectsToH5Login() throws Exception {
        when(request.getRequestURI()).thenReturn("/mobile/monitor.html");
        when(request.getHeader("Accept")).thenReturn("text/html,application/xhtml+xml");
        givenNoSession();

        interceptor.preHandle(request, response, null);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(response).sendRedirect(captor.capture());
        assertEquals(H5_LOGIN, captor.getValue());
    }

    /** PC 页面直接导航（无会话）→ 302 跳 PC 登录页 */
    @Test
    void pcPageNavigationRedirectsToPcLogin() throws Exception {
        when(request.getRequestURI()).thenReturn("/monitor.html");
        when(request.getHeader("Accept")).thenReturn("text/html,application/xhtml+xml");
        givenNoSession();

        interceptor.preHandle(request, response, null);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(response).sendRedirect(captor.capture());
        assertEquals(PC_LOGIN, captor.getValue());
    }

    /** H5 页面发起的接口请求（Referer 含 /mobile/）→ 401 JSON 携带 H5 登录页 */
    @Test
    void h5ApiRequestReturns401WithH5RedirectUrl() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/dahua/device-tree");
        when(request.getHeader("Accept")).thenReturn("application/json");
        when(request.getHeader("Referer"))
                .thenReturn("http://220.179.1.110:8081/hltgq-device/mobile/monitor.html");
        givenNoSession();

        interceptor.preHandle(request, response, null);

        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        assertTrue(writer.toString().contains("\"redirectUrl\":\"" + H5_LOGIN + "\""));
    }

    /** PC 页面发起的接口请求（Referer 为 PC 页面）→ 401 JSON 携带 PC 登录页 */
    @Test
    void pcApiRequestReturns401WithPcRedirectUrl() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/dahua/device-tree");
        when(request.getHeader("Accept")).thenReturn("application/json");
        when(request.getHeader("Referer"))
                .thenReturn("http://220.179.1.110:8081/hltgq-device/monitor.html");
        givenNoSession();

        interceptor.preHandle(request, response, null);

        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        assertTrue(writer.toString().contains("\"redirectUrl\":\"" + PC_LOGIN + "\""));
    }

    /** 无 Referer 的第三方直调接口 → 401 JSON 携带 PC 登录页（默认 PC） */
    @Test
    void apiRequestWithoutRefererReturns401WithPcRedirectUrl() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/dahua/device-tree");
        when(request.getHeader("Accept")).thenReturn("application/json");
        givenNoSession();

        interceptor.preHandle(request, response, null);

        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        assertTrue(writer.toString().contains("\"redirectUrl\":\"" + PC_LOGIN + "\""));
    }
}
