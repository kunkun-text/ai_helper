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

    List<DefenseTopics> getDefenseTopic();

    int selectByUserIdAndTopicId(@Param("userId") String userId,@Param("topicId") Long topicId);

    void updateVideoUrlByUserIdAndTopicId(@Param("userId") String userId,@Param("topicId") Long topicId,@Param("videoUrl") String videoUrl);

    void updateVideoUrlById(@Param("recordId") Long recordId,@Param("videoUrl") String videoUrl);

    String getUserIdByUserNumber(String userNumber);

    String getVideoUrlByUserIdAndTopicId(@Param("userId") String userId,@Param("topicId") Long topicId);

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

    // ========== 原子化 UPSERT（线程安全） ==========

    void upsertVideoUrl(@Param("userId") String userId, @Param("topicId") Long topicId, @Param("videoUrl") String videoUrl);

    void upsertReportUrl(@Param("userId") String userId, @Param("topicId") Integer topicId, @Param("reportUrl") String reportUrl);

    void upsertDefenseRecord(@Param("userId") String userId, @Param("topicId") Integer topicId);

    Integer getDefenseIdByUserAndTopic(@Param("userId") String userId, @Param("topicId") Integer topicId);

    /**
     * 答辩结束收尾：写入聚合总分（0-50制）、总结评语，并将状态置为已完成
     */
    int updateFinalResult(@Param("defenseId") Integer defenseId,
                          @Param("score") java.math.BigDecimal score,
                          @Param("summary") String summary);
}
