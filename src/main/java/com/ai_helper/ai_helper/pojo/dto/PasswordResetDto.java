package com.ai_helper.ai_helper.pojo.dto;

import lombok.Data;
import lombok.Setter;

@Data
@Setter
public class PasswordResetDto {
    private String token;
    private String newPassword;

}