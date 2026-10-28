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

    /**
     * 【2026-10-28】基于视频转写全文的 AI 整体综合评价（约 400~700 字）。
     * 与 {@link #aiVideoAnalysis} 不同：后者是五维成绩单，这里是一段叙述性总结，
     * 供「回答详情」在视频场次（0 轮文字作答）展示，避免弹层空白。
     */
    private String aiVideoSummary;

    private String aiReportAnalysis;
    private String aiAllAnalysis;

    // ---------- 【2026-10-16 · 报告五维评分】基于**上传的报告文档**的评析 ----------
    // 与上面的 aiReportAnalysis 是两回事：后者是答辩收尾时模型凭空写的，本组是真实文档分析的结果。

    private String reportDocAnalysis;

    /** 【2026-10-18】基于报告全文的 AI 整体评价（约 400~700 字）；为空表示尚未生成 */
    private String reportDocSummary;

    private Double reportDocScore;

    // ---------- 【2026-10-25】通用材料裁定 ----------

    /** ok / suspect_offtopic / suspect_nonmaterial / insufficient；null=未裁定 */
    private String materialVerdict;

    /** 模型判定的材料类型（答辩报告 / 个人简历 / 合同 …） */
    private String materialVerdictType;

    /** 裁定理由（约 40 字） */
    private String materialVerdictReason;

    /** 评析来源：report=基于上传报告；为空=未生成 */
    private String analysisSource;

    /** 答辩形式：text=文字 / voice=语音；历史场次为空 */
    private String defenseMode;
}
