package com.ai_helper.ai_helper.Controller.login;

import com.ai_helper.ai_helper.Service.editUserInfoService;
import com.ai_helper.ai_helper.interceptor.AuthInterceptor;
import com.ai_helper.ai_helper.pojo.dto.EditUserInfoDto;
import com.ai_helper.ai_helper.result.Result;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 个人资料修改入口。
 *
 * <p>【N19 · 2026-09-28】该接口已纳入登录保护（见 {@code WebConfig.PROTECTED_PATHS}），
 * 身份一律取自登录态、不信任请求体里的 id；越权校验在 Service 层。</p>
 */
@RestController
public class editUserInfoController {

    @Autowired
    private editUserInfoService editUserInfoService;

    @PostMapping("/editUserInfo")
    public Result editUserInfo(@RequestBody EditUserInfoDto editUserInfo, HttpServletRequest request) {
        return editUserInfoService.editUserInfo(editUserInfo, AuthInterceptor.currentUserNumber(request));
    }

}
