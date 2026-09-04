package com.ai_helper.ai_helper.pojo.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class DefenseScoreRecord {

    private Integer id;
    private Integer defenseId;
    private Integer questionId;
    private Integer roundNum;
    private BigDecimal expressionScore;
    private BigDecimal logicScore;
    private BigDecimal professionalScore;
    private BigDecimal adaptabilityScore;
    private BigDecimal innovationScore;
    private String comment;
    private LocalDateTime createdAt;
}
