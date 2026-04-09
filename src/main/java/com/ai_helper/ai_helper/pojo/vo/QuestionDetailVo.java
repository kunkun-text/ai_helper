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
    private LocalDateTime createdAt;
}
