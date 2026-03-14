package com.ai_helper.ai_helper.Controller.login;

import com.ai_helper.ai_helper.Service.editUserInfoService;
import com.ai_helper.ai_helper.pojo.dto.EditUserInfoDto;
import com.ai_helper.ai_helper.result.Result;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController

public class editUserInfoController {

    @Autowired
    private editUserInfoService editUserInfoService;

    @PostMapping("/editUserInfo")
    public Result editUserInfo(@RequestBody EditUserInfoDto editUserInfo) {
        return editUserInfoService.editUserInfo(editUserInfo);
    }

}
