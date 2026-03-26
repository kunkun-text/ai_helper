package com.ai_helper.ai_helper.pojo.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import lombok.Setter;

import java.time.LocalDateTime;

@Data
@Setter
public class DefenseHistoryDto {

    private Integer DefenseId;
    private Integer userId;
    private Integer defenseId;
    private double score;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
    private LocalDateTime defenseDate;

}
