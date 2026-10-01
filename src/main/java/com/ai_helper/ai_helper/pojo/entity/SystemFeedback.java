package com.ai_helper.ai_helper.pojo.entity;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 系统反馈实体（对应表 {@code system_feedback}）。
 *
 * <p>2026-10-01 新增，借鉴 smart-medicine 的反馈模块。</p>
 */
@Data
public class SystemFeedback implements Serializable {

    private Integer feedbackId;
    private Integer userId;
    private String userNumber;
    private String userName;
    private String role;
    private String content;
    private String contact;
    /** pending 待处理 / resolved 已回复 */
    private String status;
    private String reply;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
    private LocalDateTime updatedAt;
}
