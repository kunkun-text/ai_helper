package com.ai_helper.ai_helper.pojo.vo;


import lombok.Data;
import lombok.Setter;

import java.time.LocalDateTime;

@Data
@Setter
public class QuestionDetailVo {

    private Integer answerId;
    private String question;
    private String customQuestion;
    private String questionType;
    private String studentAnswer;
    private Double score;
    private String feedback;

    /**
     * 【N8 · 2026-10-08】该题的标准答案（预设题来自 defense_questions.standard_answer；
     * 追问没有标准答案，为 null）——详情页做「学生作答 vs 标准答案」对照用。
     */
    private String standardAnswer;

    private LocalDateTime createdAt;
}
