package com.ai_helper.ai_helper.Config;

import com.ai_helper.ai_helper.exception.BusinessException;
import com.ai_helper.ai_helper.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.apache.catalina.connector.ClientAbortException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

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
        return Result.error("缺少必要参数：" + e.getParameterName());
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
     * 兜底：其余未捕获异常统一返回结构化结果。
     */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception e) {
        log.error("未处理的异常", e);
        return Result.error("服务器处理失败，请稍后重试");
    }
}
