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
     * 附件上传/删除用它定位目标记录，避免每次上传都新建一条空壳记录。
     */
    Integer getLatestDefenseIdByUserAndTopic(@Param("userId") String userId, @Param("topicId") Integer topicId);

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
     * 开始新答辩：清理该学生该课题一题未答的空壳记录
     *
     * @return 删除的空壳记录条数
     */
    int deleteEmptyShellRecords(@Param("userId") String userId, @Param("topicId") Integer topicId);

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
}
