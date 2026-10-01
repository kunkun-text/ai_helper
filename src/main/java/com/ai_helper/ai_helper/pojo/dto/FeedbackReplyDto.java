package com.ai_helper.ai_helper.pojo.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 教师回复反馈入参。
 */
@Data
public class FeedbackReplyDto {

    @NotNull(message = "反馈ID不能为空")
    private Integer feedbackId;

    @NotBlank(message = "回复内容不能为空")
    private String reply;
}
