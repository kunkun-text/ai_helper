package com.ai_helper.ai_helper.exception;

/**
 * 业务异常：表示「用户输入不合法 / 当前状态不允许」这类可预期错误。
 *
 * <p>由 GlobalExceptionHandler 统一转换为带明确提示的 Result 返回给前端，
 * 避免把 SQL 细节、堆栈等敏感信息透传出去。</p>
 */
public class BusinessException extends RuntimeException {

    public BusinessException(String message) {
        super(message);
    }

    public BusinessException(String message, Throwable cause) {
        super(message, cause);
    }
}
