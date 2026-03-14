package com.ai_helper.ai_helper.Controller.login;

import com.ai_helper.ai_helper.Service.PasswordResetService;
import com.ai_helper.ai_helper.pojo.dto.ForgotPasswordRequestDto;
import com.ai_helper.ai_helper.pojo.dto.PasswordResetDto;
import com.ai_helper.ai_helper.result.Result;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

    /**
     * 重置密码
     */
    @PostMapping("/reset-password")
    public Result resetPassword(@RequestBody PasswordResetDto request) {
        return passwordResetService.resetPassword(request.getToken(), request.getNewPassword());
    }
}




