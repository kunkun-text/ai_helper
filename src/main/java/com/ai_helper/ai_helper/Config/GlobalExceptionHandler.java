package com.ai_helper.ai_helper.Config;

import com.ai_helper.ai_helper.exception.BusinessException;
import com.ai_helper.ai_helper.result.Result;
import com.ai_helper.ai_helper.result.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.apache.catalina.connector.ClientAbortException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import jakarta.validation.ConstraintViolationException;
import java.io.IOException;

/**
 * 全局异常处理。
 *
 * <p>目标：让前端总能拿到统一的 {@link Result} 结构 + 一句能看懂的提示，
 * 同时不把堆栈、SQL 细节透传出去。</p>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler({ClientAbortException.class, IOException.class})
    public void handleClientAbort(Exception e) {
        String msg = e.getMessage();
        if (msg != null && (msg.contains("中止了一个已建立的连接")
                || msg.contains("abort")
                || msg.contains("broken pipe")
                || msg.contains("connection was aborted"))) {
            log.debug("客户端已断开连接，无需处理: {}", msg);
        } else {
            log.warn("IO异常: {}", msg);
        }
    }

    /**
     * 业务异常：消息本身就是给用户看的，直接返回。
     */
    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusinessException(BusinessException e) {
        log.warn("业务异常: {}", e.getMessage());
        return Result.error(e.getMessage());
    }

    /**
     * 缺少必填参数（例如上传时没带 topicId）。以前会直接 500，前端只能显示"网络请求失败"。
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public Result<Void> handleMissingParameter(MissingServletRequestParameterException e) {
        log.warn("缺少必要参数: {}", e.getParameterName());
        return Result.error(ResultCode.PARAM_ERROR, "缺少必要参数：" + e.getParameterName());
    }

    /**
     * 请求体参数校验失败（DTO 上的 {@code @NotBlank} 等）。返回第一条提示即可，
     * 前端只需给用户一句能看懂的话。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(FieldError::getDefaultMessage)
                .orElse("参数校验失败");
        log.warn("参数校验失败: {}", msg);
        return Result.error(ResultCode.PARAM_ERROR, msg);
    }

    /**
     * 请求体不是合法 JSON / 类型对不上（前端传错格式时此前会落到兜底 Exception，
     * 提示成"服务器处理失败"，误导排查方向）。
     */
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public Result<Void> handleUnreadableBody(org.springframework.http.converter.HttpMessageNotReadableException e) {
        log.warn("请求体解析失败: {}", e.getMessage());
        return Result.error(ResultCode.PARAM_ERROR, "请求数据格式不正确");
    }

    /**
     * 方法参数（@RequestParam / @PathVariable）上的约束校验失败。
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public Result<Void> handleConstraintViolation(ConstraintViolationException e) {
        String msg = e.getConstraintViolations().stream()
                .findFirst()
                .map(v -> v.getMessage())
                .orElse("参数校验失败");
        log.warn("参数约束校验失败: {}", msg);
        return Result.error(ResultCode.PARAM_ERROR, msg);
    }

    /**
     * 上传文件超过 Spring multipart 上限。给出明确提示，避免前端只看到 500。
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public Result<Void> handleMaxUploadSize(MaxUploadSizeExceededException e) {
        log.warn("上传文件超过服务器限制: {}", e.getMessage());
        return Result.error("文件超过服务器允许的大小上限，请压缩后再上传");
    }

    /**
     * 【F2】数字参数格式错误（如 ?topicId=abc）。此前落到兜底 Exception → 500 + "服务器处理失败"。
     */
    @ExceptionHandler(NumberFormatException.class)
    public Result<Void> handleNumberFormat(NumberFormatException e) {
        log.warn("数字参数格式错误: {}", e.getMessage());
        return Result.error(ResultCode.PARAM_ERROR, "参数格式不正确，请检查后重试");
    }

    /**
     * 【F2】路径/查询参数类型不匹配（如把字符串传给 Integer 参数）。
     */
    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    public Result<Void> handleTypeMismatch(
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException e) {
        log.warn("参数类型不匹配 - name: {}, value: {}", e.getName(), e.getValue());
        return Result.error(ResultCode.PARAM_ERROR, "参数格式不正确，请检查后重试");
    }

    /**
     * 兜底：其余未捕获异常统一返回结构化结果。
     */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception e) {
        log.error("未处理的异常", e);
        return Result.error("服务器处理失败，请稍后重试");
    }
}
