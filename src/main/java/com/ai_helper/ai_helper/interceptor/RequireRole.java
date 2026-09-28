package com.ai_helper.ai_helper.interceptor;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明接口所需的登录角色，由 {@link AuthInterceptor} 在进入 Controller 前统一校验。
 *
 * <p><b>用法</b>：标在 Controller 类上（整类生效）或某个方法上（方法上的声明优先），
 * 值为允许访问的角色，如 {@code @RequireRole(UserRole.TEACHER)}；不标即只要求「已登录」。</p>
 *
 * <p><b>为什么用注解</b>：角色要求跟着接口走——新增接口时加一行注解即可，
 * 不必改拦截器里的路径判断，也不会出现「新增接口忘了加保护」的漏配。</p>
 */
@Documented
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireRole {

    /** 允许访问的角色列表（取值见 {@code UserRole} 常量类） */
    String[] value();
}
