package com.ai_helper.ai_helper.Controller.login;

import com.ai_helper.ai_helper.Service.RegisterService;
import com.ai_helper.ai_helper.pojo.dto.UserDto;
import com.ai_helper.ai_helper.result.Result;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 注册 / 登录入口。
 *
 * <p>【2026-10-01】4 个接口补 {@code @Valid}：学号/工号、密码为空时在校验层直接拦截，
 * 由 GlobalExceptionHandler 统一返回友好提示（原来只能靠 Service 内手写 if 兜底）。</p>
 */
@RestController
public class registerAndLoginController {

    @Autowired
    RegisterService registerService;

    @PostMapping("/register/student")
    public Result register(@Valid @RequestBody UserDto userDto) {
        // 【2026-10-28】按接口路径兜底角色：此前完全依赖请求体里的 role，
        // 缺字段时直接抛 DataIntegrityViolation（users.role 非空），前端只看到"注册失败，请稍后重试"。
        fillRoleIfBlank(userDto, "student");
        return registerService.register(userDto);
    }

    @PostMapping("/register/teacher")
    public Result registerTeacher(@Valid @RequestBody UserDto userDto) {
        fillRoleIfBlank(userDto, "teacher");
        return registerService.register(userDto);
    }

    /** 请求体没带 role（或空串）时按接口路径补默认值，避免裸 500 */
    private static void fillRoleIfBlank(UserDto dto, String role) {
        if (dto != null && (dto.getRole() == null || dto.getRole().trim().isEmpty())) {
            dto.setRole(role);
        }
    }

    @PostMapping("/login/student")
    public Result login(@Valid @RequestBody UserDto userDto, HttpServletRequest request) {
        // 【N77 · 2026-10-15】传客户端 IP，供「失败次数过多短期锁定」计数
        return registerService.login(userDto, request.getRemoteAddr());
    }

    @PostMapping("/login/teacher")
    public Result loginTeacher(@Valid @RequestBody UserDto userDto, HttpServletRequest request) {
        // 【N77 · 2026-10-15】传客户端 IP，供「失败次数过多短期锁定」计数
        return registerService.login(userDto, request.getRemoteAddr());
    }


}
