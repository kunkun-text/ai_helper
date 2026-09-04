package com.ai_helper.ai_helper.Config;

import lombok.extern.slf4j.Slf4j;
import org.apache.catalina.connector.ClientAbortException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.io.IOException;

/**
 * 全局异常处理：静默处理客户端断连等非服务端错误
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
}
