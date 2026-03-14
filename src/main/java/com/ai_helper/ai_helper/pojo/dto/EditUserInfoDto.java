package com.ai_helper.ai_helper.pojo.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EditUserInfoDto {

    private String id;
    private String name;
    private String userNumber;
    private String phoneNumber;
    private String email;

}
