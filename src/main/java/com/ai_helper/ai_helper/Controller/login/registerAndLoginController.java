package com.ai_helper.ai_helper.Controller.login;

import com.ai_helper.ai_helper.Service.RegisterService;
import com.ai_helper.ai_helper.pojo.dto.UserDto;
import com.ai_helper.ai_helper.result.Result;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class registerAndLoginController {

    @Autowired
    RegisterService registerService;

    @PostMapping("/register/student")
    public Result register(@RequestBody UserDto userDto) {
        return registerService.register(userDto);
    }

    @PostMapping("/register/teacher")
    public Result Tregister(@RequestBody UserDto userDto) {
        return registerService.register(userDto);
    }

    @PostMapping("/login/student")
    public Result login(@RequestBody UserDto userDto) {
        return registerService.login(userDto);
    }

    @PostMapping("/login/teacher")
    public Result Tlogin(@RequestBody UserDto userDto) {
        return registerService.login(userDto);
    }


}
