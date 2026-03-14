package com.ai_helper.ai_helper.Service;

import com.ai_helper.ai_helper.result.Result;

public interface PasswordResetService {
    /**
     * 发送密码重置邮件
     */
    Result sendResetEmail(String email);

    /**
     * 重置密码
     */
    Result resetPassword(String token, String newPassword);
}
