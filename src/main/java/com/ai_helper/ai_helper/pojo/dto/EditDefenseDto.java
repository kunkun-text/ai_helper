package com.ai_helper.ai_helper.pojo.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Data
public class EditDefenseDto {

    private Integer topicId;
    private Integer teacherId;
    private String topicName;

    // 使用 String 接收，兼容多种格式
    private String defenseTime;

    private String topicDescription;

    private List<DefenseQuestionItem> questions;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
    private LocalDateTime updatedAt;

    /**
     * 获取 LocalDate 格式的答辩时间
     */
    public LocalDate getDefenseTimeAsLocalDate() {
        if (this.defenseTime == null || this.defenseTime.trim().isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(this.defenseTime, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 获取 LocalDateTime 格式的答辩时间（默认 00:00:00）
     */
    public LocalDateTime getDefenseTimeAsLocalDateTime() {
        LocalDate date = getDefenseTimeAsLocalDate();
        if (date == null) {
            return null;
        }
        return date.atStartOfDay(); // 2026-03-14 00:00:00
    }

    @Data
    public static class DefenseQuestionItem {
        private Integer questionId;
        private String questionType;
        private String question;
        private String standardAnswer;
    }
}
