package com.ai_helper.ai_helper.pojo.dto;

import lombok.Data;
import lombok.Setter;

@Data
@Setter
public class AiAnalysis {

    Integer defenseId;
    String userId;
    String feedback;
    String videoAnalysis;
    String reportAnalysis;
    Double score;

}
