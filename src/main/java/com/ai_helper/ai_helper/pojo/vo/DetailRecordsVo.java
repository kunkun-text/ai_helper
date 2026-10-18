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

    // ---------- 【2026-10-16 · 报告五维评分】基于**上传的报告文档**的评析 ----------
    // 与上面的 aiReportAnalysis 是两回事：后者是答辩收尾时模型凭空写的，本组是真实文档分析的结果。

    private String reportDocAnalysis;

    /** 【2026-10-18】基于报告全文的 AI 整体评价（约 400~700 字）；为空表示尚未生成 */
    private String reportDocSummary;

    private Double reportDocScore;

    /** 评析来源：report=基于上传报告；为空=未生成 */
    private String analysisSource;

    /** 答辩形式：text=文字 / voice=语音；历史场次为空 */
    private String defenseMode;
}
