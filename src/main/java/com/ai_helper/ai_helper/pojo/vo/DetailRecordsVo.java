package com.ai_helper.ai_helper.pojo.vo;


import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import lombok.Setter;

import java.time.LocalDateTime;

@Data
@Setter
public class DetailRecordsVo {

    private Integer defenseId;
    private String studentName;
    private String studentNumber;

    private String TopicName;
    private String score;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime defenseTime;

    //答辩视频和报告
    private String defenseVideoUrl;
    private String defenseReportUrl;

    private String aiVideoAnalysis;
    private String aiReportAnalysis;
    private String aiAllAnalysis;


}
