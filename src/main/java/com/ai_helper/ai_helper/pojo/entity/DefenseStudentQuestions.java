package com.ai_helper.ai_helper.pojo.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class DefenseStudentQuestions {

    private int sqId;
    private Integer defenseId;
    private Integer questionId;
    private String customQuestion;
    private String customStandardAnswer;
    private String questionType;
    private Integer sort;
    private LocalDateTime createdAt;
}
