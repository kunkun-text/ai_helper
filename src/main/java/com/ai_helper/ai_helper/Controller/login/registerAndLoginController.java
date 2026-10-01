package com.ai_helper.ai_helper.Controller.login;

import com.ai_helper.ai_helper.Service.RegisterService;
import com.ai_helper.ai_helper.pojo.dto.UserDto;
import com.ai_helper.ai_helper.result.Result;
import jakarta.validation.Valid;
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
        return registerService.register(userDto);
    }

    @PostMapping("/register/teacher")
    public Result Tregister(@Valid @RequestBody UserDto userDto) {
        return registerService.register(userDto);
    }

    @PostMapping("/login/student")
    public Result login(@Valid @RequestBody UserDto userDto) {
        return registerService.login(userDto);
    }

    @PostMapping("/login/teacher")
    public Result Tlogin(@Valid @RequestBody UserDto userDto) {
        return registerService.login(userDto);
    }


}
