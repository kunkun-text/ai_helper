package com.ai_helper.ai_helper.Controller.login;

import com.ai_helper.ai_helper.Service.PasswordResetService;
import com.ai_helper.ai_helper.result.Result;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Controller;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * 重置密码页 + 提交入口。
 *
 * <p>【N35 · 2026-10-08】页面 HTML 从 Java 字符串改为 classpath 模板
 * {@code templates/reset-password.html}（占位符 {@code {{token}}}），Java 里不再堆 HTML。</p>
 *
 * <p>【安全】token 来自 query 参数（用户可控），原实现直接拼进 {@code value='...'}，
 * 构造 {@code /reset-password?token='><script>...} 即为反射型 XSS。
 * 现改为只接受 UUID 形式；非法 token 直接返回提示页，不再回显。</p>
 */
@Slf4j
@Controller
public class ResetController {

    /** 重置 token 由后端生成，格式固定为 UUID；用白名单正则挡掉任意回显 */
    private static final Pattern TOKEN_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private static final String TOKEN_PLACEHOLDER = "{{token}}";

    /** 模板加载失败时为 null，此时按错误页兜底，不影响重置接口本身 */
    private String pageTemplate;

    @Resource
    private PasswordResetService passwordResetService;

    @PostConstruct
    void loadTemplate() {
        ClassPathResource resource = new ClassPathResource("templates/reset-password.html");
        try (InputStream in = resource.getInputStream()) {
            pageTemplate = StreamUtils.copyToString(in, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("重置密码页模板加载失败: {}", resource.getPath(), e);
        }
    }

    @GetMapping("/reset-password")
    @ResponseBody
    public String resetPasswordPage(@RequestParam(value = "token", required = false) String token) {
        if (token == null || !TOKEN_PATTERN.matcher(token).matches()) {
            log.warn("重置密码页收到非法 token，已拒绝回显");
            return errorPage("重置链接无效或已过期", "请在小程序里重新发起「忘记密码」。");
        }
        if (pageTemplate == null) {
            return errorPage("页面暂不可用", "请稍后重试，或联系管理员。");
        }
        return pageTemplate.replace(TOKEN_PLACEHOLDER, token);
    }

    @PostMapping("/api/auth/reset-password")
    @ResponseBody
    public Result resetPassword(
            @RequestParam("token") String token,
            @RequestParam("newPassword") String newPassword) {
        return passwordResetService.resetPassword(token, newPassword);
    }

    /** 极简提示页（内容全部为固定文案，不含任何用户输入，无注入面） */
    private String errorPage(String title, String hint) {
        return "<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<title>" + title + "</title></head>"
                + "<body style=\"font-family:Arial,sans-serif;text-align:center;padding-top:80px;color:#333\">"
                + "<h2>" + title + "</h2><p>" + hint + "</p></body></html>";
    }
}
