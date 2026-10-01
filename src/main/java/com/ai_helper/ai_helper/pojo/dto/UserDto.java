package com.ai_helper.ai_helper.pojo.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserDto {

    private String id;
    private Integer userId;
    private String name;

    /** 登录 / 注册都必填；校验提示与 Service 层手工校验保持一致 */
    @NotBlank(message = "学号/工号不能为空")
    private String userNumber;

    private String role;
    private String phoneNumber;
    private String email;

    @NotBlank(message = "密码不能为空")
    private String password;

    private String createTime;


}
