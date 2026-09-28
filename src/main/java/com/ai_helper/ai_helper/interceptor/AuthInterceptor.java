package com.ai_helper.ai_helper.interceptor;


import com.ai_helper.ai_helper.result.Result;
import com.ai_helper.ai_helper.util.LoginTokenValue;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Arrays;

/**
 * 登录校验拦截器。
 *
 * <p>由 {@code WebConfig#addInterceptors} 注册在受保护路径上，职责：
 * ① 校验 Authorization 头里的 token 是否有效（Redis 中存在）；
 * ② 把登录学号 / 工号与角色写入 request attribute，供业务层取用；
 * ③ 校验 Controller 上 {@link RequireRole} 声明的角色要求（方法声明优先于类声明）。</p>
 *
 * <p><b>2026-09-28（N19 越权修复）</b>：此前 token 值只存学号、不存角色，
 * 且教师端与 {@code /editUserInfo} 完全不在保护范围内，导致学生登录后可删改课题、改他人资料。
 * 现在 token 值格式改为 {@code userNumber|role}（见 {@link LoginTokenValue}），
 * 「哪个接口要什么角色」由 {@link RequireRole} 注解声明，拦截器统一裁决。</p>
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    /** 登录学号 / 工号写入 request 的属性名，Controller 统一从这里取当前登录人 */
    public static final String ATTR_USER_NUMBER = "userNumber";

    /** 登录角色写入 request 的属性名（历史 token 未存角色时为 null） */
    public static final String ATTR_USER_ROLE = "userRole";

    private static final String BEARER_PREFIX = "Bearer ";

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    public AuthInterceptor(StringRedisTemplate stringRedisTemplate, ObjectMapper objectMapper) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
    }

    /** 从 request 取当前登录学号 / 工号；未登录返回 null（供各 Controller 复用，避免各写一份） */
    public static String currentUserNumber(HttpServletRequest request) {
        return readAttribute(request, ATTR_USER_NUMBER);
    }

    /** 从 request 取当前登录角色；未登录或历史 token 返回 null */
    public static String currentUserRole(HttpServletRequest request) {
        return readAttribute(request, ATTR_USER_ROLE);
    }

    private static String readAttribute(HttpServletRequest request, String name) {
        if (request == null) {
            return null;
        }
        Object value = request.getAttribute(name);
        return value == null ? null : String.valueOf(value);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 获取请求头中的 token
        String token = request.getHeader("Authorization");

        if (token == null || token.isEmpty()) {
            return reject(response, HttpServletResponse.SC_UNAUTHORIZED, "未登录或 token 缺失");
        }

        // 如果 token 以 "Bearer " 开头，去掉前缀
        if (token.startsWith(BEARER_PREFIX)) {
            token = token.substring(BEARER_PREFIX.length());
        }

        // 从 Redis 中验证 token（键前缀与写入端共用 LoginTokenValue）
        String stored = stringRedisTemplate.opsForValue().get(LoginTokenValue.tokenKey(token));

        if (stored == null) {
            return reject(response, HttpServletResponse.SC_UNAUTHORIZED, "token 已过期或无效");
        }

        // 解析 token 值：userNumber|role（历史 token 只有 userNumber，角色为 null）
        String[] parsed = LoginTokenValue.decode(stored);
        request.setAttribute(ATTR_USER_NUMBER, parsed[0]);
        request.setAttribute(ATTR_USER_ROLE, parsed[1]);

        return checkRequiredRole(handler, parsed[1], response);
    }

    /**
     * 校验 {@link RequireRole} 声明的角色要求。
     *
     * <p>角色未知（历史 token）→ 401，提示重新登录；
     * 角色不符 → 403，客户端可据此提示「无权访问」而不是「登录过期」。</p>
     */
    private boolean checkRequiredRole(Object handler, String role, HttpServletResponse response) throws Exception {
        RequireRole required = resolveRequireRole(handler);
        if (required == null) {
            return true;
        }
        if (role == null) {
            return reject(response, HttpServletResponse.SC_UNAUTHORIZED, "登录信息缺少角色，请重新登录");
        }
        if (!Arrays.asList(required.value()).contains(role)) {
            return reject(response, HttpServletResponse.SC_FORBIDDEN, "当前账号无权访问该功能");
        }
        return true;
    }

    /** 取角色要求：方法上的注解优先，其次类上的注解；非 Controller 方法（静态资源等）返回 null */
    private RequireRole resolveRequireRole(Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return null;
        }
        RequireRole onMethod = AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getMethod(), RequireRole.class);
        if (onMethod != null) {
            return onMethod;
        }
        return AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getBeanType(), RequireRole.class);
    }

    private boolean reject(HttpServletResponse response, int status, String message) throws Exception {
        response.setContentType("application/json;charset=UTF-8");
        response.setStatus(status);
        Result<?> result = Result.error(message);
        response.getWriter().write(objectMapper.writeValueAsString(result));
        return false;
    }
}
