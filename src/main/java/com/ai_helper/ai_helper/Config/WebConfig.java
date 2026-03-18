package com.ai_helper.ai_helper.Config;

import com.ai_helper.ai_helper.interceptor.AuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;

    public WebConfig(AuthInterceptor authInterceptor) {
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 对 teacher 端的所有接口进行 token 验证
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/teacher/**")  // 拦截 teacher 下的所有接口
                .excludePathPatterns(
                        "/teacher/login",        // 排除登录接口
                        "/teacher/register",   // 排除注册接口
                        "/api"
                );
    }
}