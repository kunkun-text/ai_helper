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

    /** 答辩状态：pending=进行中/未完成，completed=已结束 */
    private String status;

    // ---------- 【2026-10-16 · 报告五维评分】列表着色用 ----------

    /** 基于上传报告文档的 AI 评析总分（0~50）；为空表示该场次还没做过报告评析 */
    private Double reportDocScore;

    /**
     * 评析来源：{@code report}=基于上传报告文档；为空=无材料评析。
     *
     * <p>列表用它（而不是 {@code reportDocScore != null}）判断是否着色——
     * 分数可能是 0 分（报告质量极差），用分数判真假会把 0 分场次漏掉。</p>
     */
    private String analysisSource;

    /** 答辩形式：text=文字 / voice=语音；历史场次为空 */
    private String defenseMode;

    // ---------- 【VID-4 · 2026-10-20】视频 AI 评分的列表入口 ----------

    /**
     * 该场次是否已上传有效答辩视频（{@code video_url} 非空且不是历史脏值 {@code 'abc'}）。
     *
     * <p>教师端列表据此打「视频已上传」红标并按浅红底色标识——视频评分按钮在
     * 「答辩详情」里，且只在该场次有视频时才渲染；没有这个标记，教师无法在列表里
     * 定位到"可以评分的那一行"，会误以为评分功能不可用。</p>
     */
    private Boolean hasVideo;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
    private LocalDateTime defenseTime;


}
