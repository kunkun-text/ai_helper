package com.ai_helper.ai_helper.pojo.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class DefenseQuestions {

    private int questionId;
    private Integer topicId;
    private Integer teacherId;
    private String questionType;
    private String question;
    private String standardAnswer;
    private String aiStandardAnswer;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

}
