package com.ai_helper.ai_helper.mapper;

import com.ai_helper.ai_helper.pojo.dto.DefenseRecordsDto;
import com.ai_helper.ai_helper.pojo.entity.DefenseTopics;
import com.ai_helper.ai_helper.pojo.query.TextQuery;
import com.ai_helper.ai_helper.pojo.vo.DefenseRecordsVo;
import com.ai_helper.ai_helper.pojo.vo.DetailRecordsVo;
import com.ai_helper.ai_helper.pojo.vo.QuestionDetailVo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface DefenseRecordsMapper {


    List<DefenseRecordsVo> getDefenseRecords(DefenseRecordsDto defenseRecordsDto);

    DetailRecordsVo getDetailRecords(Integer defenseId);

    List<QuestionDetailVo> getDefenseQuestionsAnswers(Integer defenseId);


    List<DefenseRecordsVo> getStudentDefenseRecords(String userNumber);

    void insertVideoUrl(@Param("userId") String userId,@Param("topicId") Long topicId, @Param("videoUrl")  String videoUrl);

    /** 【F3】分页查询课题（keyword 可选，按课题名模糊匹配） */
    List<DefenseTopics> getDefenseTopic(@Param("keyword") String keyword,
                                        @Param("offset") int offset,
                                        @Param("limit") int limit);

    /** 【F3】课题总数（与分页查询同条件） */
    long countDefenseTopic(@Param("keyword") String keyword);

    int selectByUserIdAndTopicId(@Param("userId") String userId,@Param("topicId") Long topicId);

    void updateVideoUrlByUserIdAndTopicId(@Param("userId") String userId,@Param("topicId") Long topicId,@Param("videoUrl") String videoUrl);

    void updateVideoUrlById(@Param("recordId") Long recordId,@Param("videoUrl") String videoUrl);

    void updateReportUrlById(@Param("recordId") Long recordId, @Param("reportUrl") String reportUrl);

    void clearVideoUrlByDefenseId(@Param("defenseId") Long defenseId);

    void clearReportUrlByDefenseId(@Param("defenseId") Long defenseId);

    String getUserIdByUserNumber(String userNumber);

    /** 【N15 · 2026-10-08】取某场答辩的审计上下文（user_id / topic_id），用于写审计日志 */
    java.util.Map<String, Object> selectAuditContext(@Param("defenseId") Integer defenseId);

    String getVideoUrlByUserIdAndTopicId(@Param("userId") String userId,@Param("topicId") Long topicId);

    /** 【N71 · 2026-10-15】全量 video_url（孤儿预分配文件清理用：被引用的视频文件绝不能当孤儿删） */
    List<String> selectAllVideoUrls();

    /**
     * 取该用户在该题目下的最新一条答辩记录（不区分状态）。
     *
     * <p>【2026-10-25】附件归属已不再用它——它会返回已结束的场次，
     * 导致"答辩后上传的报告挂到上一场答辩"上。附件请用
     * {@link #getLatestEmptyShellDefenseId} / {@link #getLatestPendingDefenseIdByUserAndTopic}。</p>
     */
    Integer getLatestDefenseIdByUserAndTopic(@Param("userId") String userId, @Param("topicId") Integer topicId);

    /**
     * 【2026-10-25】「待答辩空壳」场次：pending + 一题未答 + 无追问 + 无评分。
     *
     * <p>附件应挂在这一条上——它也是开始答辩时会被复用的那一条，
     * 两处口径一致，材料与作答才不会分家。</p>
     */
    Integer getLatestEmptyShellDefenseId(@Param("userId") String userId,
                                         @Param("topicId") Integer topicId);

    /**
     * 【2026-10-25】该课题下最新的 pending 场次（不辨是否已作答）。
     * 调用方须再判断最后一次作答是否在续答窗口内，否则会挂到历史弃答场次。
     */
    Integer getLatestPendingDefenseIdByUserAndTopic(@Param("userId") String userId,
                                                    @Param("topicId") Integer topicId);

    /**
     * 【2026-10-25】读取「当前该展示的」视频地址（优先进行中场次，其次最近已结束场次）。
     * 旧实现 {@code LIMIT 1} 无排序，多记录时会随机取到空值，前端表现为"传了却显示未上传"。
     */
    String getLatestVideoUrlByUserAndTopic(@Param("userId") String userId,
                                           @Param("topicId") Integer topicId);

    /**
     * 【2026-10-28】按【本场次】精确读取旧视频地址（换视频时只删本场自己的旧文件）。
     *
     * <p>不能用 {@link #getLatestVideoUrlByUserAndTopic}：它是跨场次「最新一条」，
     * 新建场次后旧 URL 属于上一场，会把上一场的视频文件误删。</p>
     */
    String getVideoUrlByDefenseId(@Param("defenseId") Integer defenseId);

    /** 【2026-10-25】读取「当前该展示的」报告地址（口径同视频） */
    String getLatestReportUrlByUserAndTopic(@Param("userId") String userId,
                                            @Param("topicId") Integer topicId);

    /**
     * 【2026-10-25】「删除附件」要操作的那一条：拥有当前展示材料的场次。
     * 删除与读取必须指向同一行，否则会出现"列表显示有报告、点删除却删了另一场"。
     *
     * @param type {@code video} / {@code report}
     */
    Integer selectLatestMaterialDefenseId(@Param("userId") String userId,
                                          @Param("topicId") Integer topicId,
                                          @Param("type") String type);

    /**
     * 校验某条答辩记录是否属于指定用户，防止学生通过改 defenseId 读取他人记录。
     *
     * @return 匹配条数（1 表示属于该用户）
     */
    int countOwnedDefenseRecord(@Param("defenseId") Integer defenseId, @Param("userId") String userId);

    TextQuery getDetailWordsRecords(Integer topicId);


    TextQuery selectVideoAndPptWords(@Param("topicId") Integer topicId, @Param("userId") String userId);

    Integer getExistingDefenseRecord(@Param("userId") String userId, @Param("topicId") Integer topicId);

    int createDefenseRecord(@Param("userId") String userId, @Param("topicId") Integer topicId);

    /**
     * 【2026-10-25】文档答辩收尾：报告评析出分后把该场次判为已完成并写入分数。
     *
     * <p>只在「一条作答都没有」时生效——先答文字/语音再传报告的场次，
     * 总分必须保留逐轮答辩结果，不能被报告分覆盖。</p>
     *
     * @return 实际更新行数（0 = 有作答记录，未覆盖）
     */
    int completeReportDefense(@Param("defenseId") Integer defenseId, @Param("score") double score);

    /**
     * 开始新答辩：清理该学生该课题「一题未答、没有任何附件、也没有任何分析产物」的空壳记录。
     *
     * <p>【2026-10-25 · 事故修复】附件条件不能省：只传了视频/报告、还没开始答题的场次
     * 同样「pending + 0 作答」，被当成空壳整行删除后，材料与记录一起消失
     * （{@code defenseId=358} 事故，255MB 视频不可恢复）。
     * 带材料的场次由 {@link #selectReusableMaterialDefenseId} 复用，不会命中本方法。</p>
     *
     * @return 删除的空壳记录条数
     */
    int deleteEmptyShellRecords(@Param("userId") String userId, @Param("topicId") Integer topicId);

    /**
     * 【2026-10-25】开始答辩时优先复用的场次：
     * pending + 一题未答 + 已上传视频或报告的最新一条。
     *
     * <p>不复用就会出现"材料在旧场次、作答在新场次"的分家现象
     * （学生端看到两条记录，教师点开的那条没有材料）。</p>
     */
    Integer selectReusableMaterialDefenseId(@Param("userId") String userId,
                                            @Param("topicId") Integer topicId);

    int saveSummary(@Param("userId") String userId,@Param("topicId") Integer topicId,@Param("summary") String summary);

    void updateReportUrl(@Param("userId") String userId, @Param("topicId") Integer topicId, @Param("reportUrl") String reportUrl);

    void insertReportUrl(@Param("userId") String userId, @Param("topicId") Integer topicId, @Param("reportUrl") String reportUrl);

    String getReportUrlByUserIdAndTopicId(@Param("userId") String userId, @Param("topicId") Integer topicId);

    // 原 upsertVideoUrl / upsertReportUrl / upsertDefenseRecord 已删除（2026-09-28 · 清单「新-2」）：
    // 三者依赖的 (user_id, topic_id) 唯一索引已改为普通索引，语句退化成纯 INSERT，
    // 每次调用会凭空新建空壳记录，且早已无调用点。附件链路按 defense_id 显式 UPDATE；
    // 需要「取或建记录」时用 getOrCreateDefenseRecord / getOrCreateLatestDefenseRecord。

    Integer getDefenseIdByUserAndTopic(@Param("userId") String userId, @Param("topicId") Integer topicId);

    /**
     * 答辩结束收尾：写入聚合总分（0-50制）、总结评语，并将状态置为已完成
     */
    int updateFinalResult(@Param("defenseId") Integer defenseId,
                          @Param("score") java.math.BigDecimal score,
                          @Param("summary") String summary);

    /**
     * 【2026-10-16 · 报告五维评分】取某场答辩的「上传报告评析」上下文。
     *
     * <p>返回 report_url / report_doc_analysis / report_doc_score / analysis_source / topic_name，
     * 供触发分析前做存在性、格式与幂等判定，并给评分 prompt 提供课题名。</p>
     */
    java.util.Map<String, Object> selectReportDocTarget(@Param("defenseId") Integer defenseId);

    /**
     * 【2026-10-16 · 报告全文落库】写入抽取出的报告全文，供评分依据事后复核。
     *
     * <p><b>为什么单独一个方法、且在评分之前调用：</b>分析失败（解析不出五维 / 超时）时，
     * 恰恰最需要知道"模型当时到底看到了什么"，而那时 {@link #updateReportDocAnalysis} 不会被调用。
     * 只 set {@code report_text} + {@code updated_at}，不碰任何其它列。</p>
     *
     * @param text 抽取并归一化后的全文（上限由 {@code app.report-doc.max-chars} 截断）
     */
    int updateReportText(@Param("defenseId") Integer defenseId, @Param("text") String text);

    /**
     * 【2026-10-16 · 报告五维评分】写入基于上传报告文档的 AI 评析结果。
     *
     * <p><b>只 set 这三个新列 + updated_at</b>：feedback / video_analysis / report_analysis /
     * score（答辩总分）与本功能无关，一行都不能碰——否则会覆盖既有链路的数据。</p>
     */
    int updateReportDocAnalysis(@Param("defenseId") Integer defenseId,
                                @Param("analysis") String analysis,
                                @Param("score") java.math.BigDecimal score,
                                @Param("source") String source);

    /**
     * 【2026-10-18 · 报告综合评价】写入基于报告全文的整体评价（约 400~700 字）。
     *
     * <p>与 {@link #updateReportDocAnalysis} 分开：综合评价是**附加项**，
     * 生成或落库失败都不能影响五维成绩单——成绩单才是评分的主口径。</p>
     */
    int updateReportDocSummary(@Param("defenseId") Integer defenseId,
                               @Param("summary") String summary);

    /**
     * 【2026-10-25】材料裁定不合格：写入裁定结论 + 说明，**不碰任何分数列**。
     *
     * <p>与 {@link #updateReportDocAnalysis} 分开的原因就是"不写分"：0 分是"评过但很差"，
     * 传错文件是"根本没评"。这里只落 {@code report_doc_analysis}（裁定说明）与
     * {@code material_verdict*} 三列，{@code report_doc_score} / {@code score} / {@code status}
     * 保持原值，列表继续显示「未完成」并带「待复审」标签。</p>
     *
     * @param verdict       裁定码：suspect_offtopic / suspect_nonmaterial / insufficient
     * @param verdictType   模型判定的材料类型（答辩报告/个人简历/合同…），可为空
     * @param verdictReason 裁定理由（约 40 字）
     */
    int updateReportDocRejected(@Param("defenseId") Integer defenseId,
                                @Param("analysis") String analysis,
                                @Param("verdict") String verdict,
                                @Param("verdictType") String verdictType,
                                @Param("verdictReason") String verdictReason);

    /**
     * 【2026-10-25】改判裁定（教师云端复审通过 → {@code ok}，列表「待复审」标签消失）。
     * 只动裁定三列，不碰成绩单与分数。
     */
    int updateMaterialVerdict(@Param("defenseId") Integer defenseId,
                              @Param("verdict") String verdict,
                              @Param("verdictType") String verdictType,
                              @Param("verdictReason") String verdictReason);

    // ==================== 答辩视频的 AI 评分（2026-10-20 · VID 系列） ====================

    /**
     * 取某场答辩的「视频评分」上下文。
     *
     * <p>返回 video_url / video_words / video_analysis / analysis_source / topic_name，
     * 供触发前做存在性、幂等判定，并给评分 prompt 提供课题名。</p>
     */
    java.util.Map<String, Object> selectVideoAnalysisTarget(@Param("defenseId") Integer defenseId);

    /**
     * 写入视频语音转写文本（评分前落库，供事后复核模型看到的输入）。
     *
     * <p>只 set {@code video_words} + {@code updated_at}。</p>
     */
    int updateVideoWords(@Param("defenseId") Integer defenseId, @Param("words") String words);

    /**
     * 写入基于答辩视频的 AI 评析（{@code video_analysis} + {@code analysis_source='video'}）。
     *
     * <p>只 set 这两列 + {@code updated_at}：视频地址、逐轮反馈、报告评析、总分都不能碰。</p>
     */
    int updateVideoAnalysis(@Param("defenseId") Integer defenseId,
                            @Param("analysis") String analysis,
                            @Param("source") String source);

    /**
     * 【2026-10-28】写入视频答辩综合评价（{@code video_summary}，约 400~700 字）。
     *
     * <p>附加写：与五维成绩单分开落库，失败只记日志、不影响成绩单。</p>
     */
    int updateVideoSummary(@Param("defenseId") Integer defenseId,
                           @Param("summary") String summary);

    /**
     * 【2026-10-28】视频场次收尾：写入总分并把该场置为 {@code completed}。
     *
     * <p>SQL 自带「一条作答都没有」的条件——先答文字/语音再传视频的场次，
     * 总分仍以逐轮答辩为准，不会被视频分覆盖。</p>
     */
    int completeVideoDefense(@Param("defenseId") Integer defenseId,
                             @Param("score") java.math.BigDecimal score);

    /**
     * 【2026-10-28】写入答辩形式（{@code text} / {@code voice}）。
     *
     * <p>该列此前建了但从未写入（CLAUDE.md 挂账项），导致列表看不出「语音答辩」。</p>
     */
    int updateDefenseMode(@Param("defenseId") Integer defenseId, @Param("mode") String mode);
}
