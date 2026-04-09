package com.ai_helper.ai_helper.pojo.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class DefenseAnswers {

    private int answerId;
    private Integer defenseId;
    private Integer questionId;
    private Integer sqId;
    private String studentAnswer;
    private BigDecimal score;
    private String feedback;
    private LocalDateTime createdAt;
}
