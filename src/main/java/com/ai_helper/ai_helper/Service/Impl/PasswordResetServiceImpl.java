package com.ai_helper.ai_helper.Service.Impl;

import com.ai_helper.ai_helper.Service.PasswordResetService;
import com.ai_helper.ai_helper.mapper.PasswordResetMapper;
import com.ai_helper.ai_helper.pojo.dto.UserDto;
import com.ai_helper.ai_helper.result.Result;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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

    /**
     * 重置链接的基础地址（学生点开邮件里的链接时访问的后端地址）。
     * 【N35 · 2026-10-08】原为硬编码 {@code http://localhost:8080}，换机器/上线必须改代码；
     * 现走配置：本机调试用默认值，正式部署在 application.yml 里改成对外可访问的地址。
     */
    @Value("${app.mail.reset-link-base:http://localhost:8080}")
    private String resetLinkBase;

    /**
     * 邮件发件人。【N35】原为硬编码 {@code 1685975918@qq.com}；
     * 现取自 {@code spring.mail.username}，留空则不显式设置（交给 JavaMailSender 默认值）。
     */
    @Value("${spring.mail.username:}")
    private String mailFrom;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    /** 【N76】同一邮箱重置邮件发送冷却：60 秒一封，防邮件轰炸 */
    private static final long RESET_EMAIL_COOLDOWN_SECONDS = 60;

    @Override
    public Result sendResetEmail(String email) {
        // 【N76 · 2026-10-15】发送冷却（先于注册校验）：同一邮箱 60 秒内只发一封
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(
                "password:reset:cooldown:" + email, "1", RESET_EMAIL_COOLDOWN_SECONDS, TimeUnit.SECONDS);
        if (!Boolean.TRUE.equals(acquired)) {
            return Result.error("发送过于频繁，请 1 分钟后再试");
        }

        // 1. 验证邮箱是否存在。【N76】未注册不再回显「该邮箱未注册」——防枚举，统一按已发送口径回复
        UserDto user = passwordResetMapper.selectByEmail(email);
        if (user == null) {
            log.info("密码重置请求：邮箱未注册（统一文案回复，不向调用方回显） - {}", email);
            return Result.success("若该邮箱已注册，重置邮件将发送至该邮箱，请查收");
        }

        // 2. 生成 token
        String token = UUID.randomUUID().toString();

        // 3. 存入 Redis，30 分钟过期
        redisTemplate.opsForValue().set(
                "password:reset:" + token,
                email,
                30, TimeUnit.MINUTES
        );

        // 4. 发送重置邮件（【N35】链接前缀与发件人均改为配置项，不再硬编码）
        String base = resetLinkBase == null ? "" : resetLinkBase.trim().replaceAll("/+$", "");
        String resetLink = base + "/reset-password?token=" + token;
        SimpleMailMessage message = new SimpleMailMessage();
        if (mailFrom != null && !mailFrom.isBlank()) {
            message.setFrom(mailFrom.trim());
        }
        message.setTo(email);
        message.setSubject("密码重置");
        message.setText("请点击以下链接重置密码（30 分钟内有效）：\n" + resetLink);

        try {
            mailSender.send(message);
            log.info("密码重置邮件已发送至：" + email);
            return Result.success("重置邮件已发送，请查收");
        } catch (Exception e) {
            // 【F2】SMTP 细节（主机/端口/账号）只记日志，不回传前端
            log.error("密码重置邮件发送失败 - 收件人: {}", email, e);
            return Result.error("邮件发送失败，请稍后重试或联系管理员");
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
