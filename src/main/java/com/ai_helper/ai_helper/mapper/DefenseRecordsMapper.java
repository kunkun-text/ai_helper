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

    String getVideoUrlByUserIdAndTopicId(@Param("userId") String userId,@Param("topicId") Long topicId);

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
}
