package com.ai_helper.ai_helper.Service.Impl;

import com.ai_helper.ai_helper.Service.PasswordResetService;
import com.ai_helper.ai_helper.mapper.PasswordResetMapper;
import com.ai_helper.ai_helper.pojo.dto.UserDto;
import com.ai_helper.ai_helper.result.Result;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class PasswordResetServiceImpl implements PasswordResetService {

    @Resource
    private PasswordResetMapper passwordResetMapper;

    @Resource
    private JavaMailSender mailSender;

    @Resource
    private RedisTemplate<String, String> redisTemplate;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Override
    public Result sendResetEmail(String email) {
        // 1. 验证邮箱是否存在
        UserDto user = passwordResetMapper.selectByEmail(email);
        if (user == null) {
            return Result.error("该邮箱未注册");
        }

        // 2. 生成 token
        String token = UUID.randomUUID().toString();

        // 3. 存入 Redis，30 分钟过期
        redisTemplate.opsForValue().set(
                "password:reset:" + token,
                email,
                30, TimeUnit.MINUTES
        );

        // 4. 发送重置邮件
        String resetLink = "http://localhost:8080/reset-password?token=" + token;
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom("1685975918@qq.com");
        message.setTo(email);
        message.setSubject("密码重置");
        message.setText("请点击以下链接重置密码（30 分钟内有效）：\n" + resetLink);

        try {
            mailSender.send(message);
            log.info("密码重置邮件已发送至：" + email);
            return Result.success("重置邮件已发送，请查收");
        } catch (Exception e) {
            log.error("邮件发送失败：" + e.getMessage());
            return Result.error("邮件发送失败：" + e.getMessage());
        }
    }

    @Override
    public Result resetPassword(String token, String newPassword) {
        // 1. 验证新密码
        if (newPassword == null || newPassword.length() < 6) {
            return Result.error("密码不能少于 6 位");
        }

        // 2. 从 Redis 获取 token 对应的邮箱
        String email = redisTemplate.opsForValue().get("password:reset:" + token);
        if (email == null) {
            return Result.error("重置链接已过期或无效");
        }

        // 3. 查询用户
        UserDto user = passwordResetMapper.selectByEmail(email);
        if (user == null) {
            return Result.error("用户不存在");
        }

        // 4. 加密并更新密码
        String encryptedPassword = passwordEncoder.encode(newPassword);
        int updateCount = passwordResetMapper.updatePassword(user.getUserNumber(), encryptedPassword);
        if (updateCount <= 0) {
            return Result.error("密码重置失败");
        }
        log.info("密码重置成功");


        // 5. 删除 token
        redisTemplate.delete("password:reset:" + token);

        return Result.success("密码重置成功");
    }
}
