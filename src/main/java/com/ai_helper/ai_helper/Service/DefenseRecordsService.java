package com.ai_helper.ai_helper.Service;

import com.ai_helper.ai_helper.pojo.dto.DefenseRecordsDto;
import com.ai_helper.ai_helper.pojo.entity.DefenseTopics;
import com.ai_helper.ai_helper.pojo.query.TextQuery;
import com.ai_helper.ai_helper.pojo.vo.DefenseRecordsVo;
import com.ai_helper.ai_helper.pojo.vo.DetailRecordsVo;
import com.ai_helper.ai_helper.pojo.vo.QuestionDetailVo;
import com.ai_helper.ai_helper.result.Result;
import com.github.pagehelper.PageInfo;

import java.util.List;

public interface DefenseRecordsService {

    /**
     * 获取 defense_history 表中的数据
     *
     * @return defense_history 表中的数据
     */
    Result<PageInfo<DefenseRecordsVo>> getDefenseRecords(Integer pageNum, Integer pageSize);

    Result<DetailRecordsVo> getDefenseDetailRecords(Integer defenseId);

    Result<List<QuestionDetailVo>> getDefenseQuestionsAnswers(Integer defenseId);

    Result<PageInfo<DefenseRecordsVo>> selectDefenseRecords(DefenseRecordsDto defenseRecordsDto);

    Result<PageInfo<DefenseRecordsVo>> getStudentDefenseRecords(int pageNum, int pageSize, String userNumber);

    Result<List<DefenseTopics>> getDefenseTopic();

    TextQuery getDefenseWordsRecords(Integer topicId);

    TextQuery selectVideoAndPptWords(Integer topicId, String userId);

    Integer saveAiQuestion(List<Integer> existingQuestionIds, Integer topicId, String userId, String userInput, String aiResponse, Double score, String feedback, String summary);

    void savePresetQuestionAnswer(Integer topicId, String userId, Integer questionId, String studentAnswer, String aiFeedback, Double score);

    Integer getOrCreateDefenseRecord(Integer topicId, String userId);

    /**
     * 取该用户在该题目下的最新答辩记录（不区分状态），不存在时按需创建。
     *
     * <p>用于附件（视频/报告）的读写：附件应挂到已有记录上，
     * 而不是像 {@link #getOrCreateDefenseRecord} 那样、记录已完成时再新建一条空壳。</p>
     *
     * @param createIfMissing 不存在时是否新建；删除类操传 false，避免为了删文件而凭空建记录
     * @return 答辩记录 ID，定位不到且不创建时返回 null
     */
    Integer getOrCreateLatestDefenseRecord(Integer topicId, String userNumber, boolean createIfMissing);

    /**
     * 开始新答辩：清理该学生该课题遗留的空壳记录（一题未答），并创建本次答辩的独立记录
     */
    void startNewDefenseRecord(Integer topicId, String userId);

    /**
     * 答辩结束收尾：聚合总分（0-50制）与总结评语写回 defense_records
     */
    void finishDefenseRecord(Integer defenseId, java.math.BigDecimal totalScore, String summary);
}
