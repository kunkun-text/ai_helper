package com.ai_helper.ai_helper.pojo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 提交反馈入参。
 */
@Data
public class FeedbackDto {

    @NotBlank(message = "反馈内容不能为空")
    @Size(max = 1000, message = "反馈内容最多 1000 字")
    private String content;

    @Size(max = 100, message = "联系方式最多 100 字")
    private String contact;
}
