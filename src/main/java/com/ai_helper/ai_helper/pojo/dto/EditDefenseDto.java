
package com.ai_helper.ai_helper.pojo.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class EditDefenseDto {

    private Integer topicId;
    private Integer teacherId;
    private String topicName;


    private LocalDate defenseTime;


    private String topicDescription;

    private List<DefenseQuestionItem> questions;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
    private LocalDateTime updatedAt;

    @Data
    public static class DefenseQuestionItem {
        private Integer questionId;
        private String questionType;
        private String question;
        private String standardAnswer;
    }
}