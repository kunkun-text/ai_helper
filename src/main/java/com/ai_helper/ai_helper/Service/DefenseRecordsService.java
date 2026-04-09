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
}
