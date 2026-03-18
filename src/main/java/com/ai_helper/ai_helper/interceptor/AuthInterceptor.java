package com.ai_helper.ai_helper.interceptor;


import com.ai_helper.ai_helper.result.Result;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class AuthInterceptor implements HandlerInterceptor {

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    public AuthInterceptor(RedisTemplate<String, String> redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 获取请求头中的 token
        String token = request.getHeader("Authorization");

        if (token == null || token.isEmpty()) {
            response.setContentType("application/json;charset=UTF-8");
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            Result<?> result = Result.error("未登录或 token 缺失");
            response.getWriter().write(objectMapper.writeValueAsString(result));
            return false;
        }

        // 如果 token 以 "Bearer " 开头，去掉前缀
        if (token.startsWith("Bearer ")) {
            token = token.substring(7);
        }

        // 从 Redis 中验证 token
        String userNumber = redisTemplate.opsForValue().get("login:token:" + token);

        if (userNumber == null) {
            response.setContentType("application/json;charset=UTF-8");
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            Result<?> result = Result.error("token 已过期或无效");
            response.getWriter().write(objectMapper.writeValueAsString(result));
            return false;
        }

        // 将用户信息存入 request 属性，供后续使用
        request.setAttribute("userNumber", userNumber);

        return true;
    }
}
