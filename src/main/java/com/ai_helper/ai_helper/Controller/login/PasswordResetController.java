package com.ai_helper.ai_helper.Controller.login;

import com.ai_helper.ai_helper.Service.PasswordResetService;
import com.ai_helper.ai_helper.pojo.dto.ForgotPasswordRequestDto;
import com.ai_helper.ai_helper.result.Result;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 忘记密码入口（发送重置邮件）。
 *
 * <p>【N59 · 2026-10-14】删除了重复的 {@code POST /api/reset-password}：它与
 * {@code ResetController} 的 {@code POST /api/auth/reset-password}（重置页表单真正提交的地址）
 * 功能完全重复，且全仓无任何调用方；对应的 DTO {@code PasswordResetDto} 一并删除。</p>
 */
@RestController
@RequestMapping("/api")
public class PasswordResetController {

    @Resource
    private PasswordResetService passwordResetService;

    /**
     * 请求发送密码重置邮件
     */
    @PostMapping("/forgot-password")
    public Result forgotPassword(@RequestBody ForgotPasswordRequestDto request) {
        return passwordResetService.sendResetEmail(request.getEmail());
    }
}
