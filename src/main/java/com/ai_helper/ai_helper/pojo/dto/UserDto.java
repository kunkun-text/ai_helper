package com.ai_helper.ai_helper.pojo.dto;

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
    private String userNumber;
    private String role;
    private String phoneNumber;
    private String email;
    private String password;
    private String createTime;


}
