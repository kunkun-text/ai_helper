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

    /** 【N35】错误提示页模板占位符 */
    private static final String ERROR_TITLE_PLACEHOLDER = "{{title}}";
    private static final String ERROR_HINT_PLACEHOLDER = "{{hint}}";

    /** 模板加载失败时为 null，此时按错误页兜底，不影响重置接口本身 */
    private String pageTemplate;

    /** 【N35 · 2026-10-14】错误提示页模板；此前是 Java 内联 HTML 字符串拼接 */
    private String errorPageTemplate;

    @Resource
    private PasswordResetService passwordResetService;

    @PostConstruct
    void loadTemplate() {
        pageTemplate = loadTemplateFromClasspath("templates/reset-password.html");
        errorPageTemplate = loadTemplateFromClasspath("templates/error-page.html");
    }

    /** 从 classpath 读模板；读不到返回 null，由各调用方自行兜底 */
    private String loadTemplateFromClasspath(String path) {
        ClassPathResource resource = new ClassPathResource(path);
        try (InputStream in = resource.getInputStream()) {
            return StreamUtils.copyToString(in, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("模板加载失败: {}", path, e);
            return null;
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

    /**
     * 提示页。【N35 · 2026-10-14】改由 classpath 模板 {@code templates/error-page.html} 渲染，
     * Java 里不再拼 HTML 字符串。
     *
     * <p>调用方传入的都是固定文案；这里仍对占位符值做 HTML 转义，
     * 避免将来传入动态内容时出现注入。</p>
     */
    private String errorPage(String title, String hint) {
        if (errorPageTemplate == null) {
            // 模板缺失时的最简兜底：纯文本，不做标签拼接，天然无注入面
            return "重置密码页 · " + title + "：" + hint;
        }
        return errorPageTemplate
                .replace(ERROR_TITLE_PLACEHOLDER, escapeHtml(title))
                .replace(ERROR_HINT_PLACEHOLDER, escapeHtml(hint));
    }

    /** 占位符替换值做 HTML 转义（N35） */
    private static String escapeHtml(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
