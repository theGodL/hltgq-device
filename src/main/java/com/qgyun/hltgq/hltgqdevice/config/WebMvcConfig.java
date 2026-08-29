package com.qgyun.hltgq.hltgqdevice.config;

import com.qgyun.hltgq.hltgqdevice.auth.AuthInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;

/**
 * Web MVC 配置：注册云台权限拦截器（仅拦截标注 @RequireAdmin 的接口）。
 * <p>总开关 auth.enabled（默认 true），关闭时云台接口不做权限校验（调试用，生产必须开启）。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Resource
    private AuthInterceptor authInterceptor;

    /** 云台权限校验总开关 */
    @Value("${auth.enabled:true}")
    private boolean authEnabled;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        if (!authEnabled) {
            return;
        }
        registry.addInterceptor(authInterceptor).addPathPatterns("/api/**");
    }
}
