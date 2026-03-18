package com.ai_helper.ai_helper.pojo.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import lombok.Setter;

import java.time.LocalDateTime;

@Data
@Setter
public class DefenseRecordsVo {

    private Integer defenseRecordId;
    private String userName;
    private String userNumber;
    private String profilePicture;
    private String topicName;
    private String score;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
    private LocalDateTime defenseTime;


}
