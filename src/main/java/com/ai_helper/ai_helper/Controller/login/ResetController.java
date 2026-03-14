package com.ai_helper.ai_helper.Controller.login;

import com.ai_helper.ai_helper.Service.PasswordResetService;
import com.ai_helper.ai_helper.result.Result;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
public class ResetController {

    @Resource
    private PasswordResetService passwordResetService;

    @GetMapping("/reset-password")
    @ResponseBody
    public String resetPasswordPage(@RequestParam("token") String token) {
        return "<!DOCTYPE html>" +
                "<html><head><title>重置密码</title>" +
                "<style>body{font-family:Arial,sans-serif;display:flex;justify-content:center;align-items:center;height:100vh;background:#f5f5f5;}" +
                ".container{background:white;padding:40px;border-radius:8px;box-shadow:0 2px 10px rgba(0,0,0,0.1);}" +
                "input[type='password']{width:100%;padding:12px;margin:20px 0;border:1px solid #ddd;border-radius:4px;box-sizing:border-box;}" +
                "button{width:100%;padding:12px;background:#007bff;color:white;border:none;border-radius:4px;cursor:pointer;font-size:16px;}" +
                "button:hover{background:#0056b3;}</style></head>" +
                "<body><div class='container'><h2>重置密码</h2>" +
                "<form method='POST' action='/api/auth/reset-password'>" +
                "<input type='hidden' name='token' value='" + token + "'>" +
                "<input type='password' name='newPassword' placeholder='请输入新密码（至少 6 位）' required minlength='6'>" +
                "<button type='submit'>提交</button>" +
                "</form></div></body></html>";
    }

    @PostMapping("/api/auth/reset-password")
    @ResponseBody
    public Result resetPassword(
            @RequestParam("token") String token,
            @RequestParam("newPassword") String newPassword) {
        return passwordResetService.resetPassword(token, newPassword);
    }
}
