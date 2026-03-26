package com.ai_helper.ai_helper.Service;

import com.ai_helper.ai_helper.pojo.dto.DefenseRecordsDto;
import com.ai_helper.ai_helper.pojo.entity.DefenseTopics;
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
}
