package com.ai_helper.ai_helper.Config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration // 标记为配置类
@EnableWebSecurity // 启用 Web 安全配置
public class SecurityConfig {

    // 核心：配置安全过滤链，关闭默认认证
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // 1. 关闭 CSRF 防护（小程序/前后端分离场景无需开启）
                .csrf(csrf -> csrf.disable())
                // 2. 允许所有请求通过 Spring Security 过滤器链
                //    真正的登录校验由 AuthInterceptor（见 WebConfig.addInterceptors）承担，
                //    它会把登录学号写入 request attribute 供业务层使用。
                //    这里若再拦一层，会出现两套鉴权逻辑互相打架，故保持放行。
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/**").permitAll()
                )
                // 3. 关闭 HTTP Basic 认证（取消弹窗登录）
                .httpBasic(basic -> basic.disable()); // 明确关闭 Basic 认证

        return http.build();
    }


    // 4. 注入 BCrypt 加密器（供密码加密使用）
    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
