package com.ai_helper.ai_helper.pojo.entity;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import lombok.Setter;

import java.time.LocalDateTime;

@Data
@Setter
public class DefenseHistory {

    private Integer historyId;
    private Integer userId;
    private Integer defenseId;
    private double score;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
    private LocalDateTime defenseDate;

}
