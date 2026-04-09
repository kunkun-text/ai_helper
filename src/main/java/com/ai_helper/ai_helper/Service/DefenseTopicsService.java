package com.ai_helper.ai_helper.Service;

import com.ai_helper.ai_helper.pojo.dto.EditDefenseDto;
import com.ai_helper.ai_helper.pojo.entity.DefenseQuestions;
import com.ai_helper.ai_helper.pojo.entity.DefenseTopics;
import com.ai_helper.ai_helper.result.PageResult;
import com.ai_helper.ai_helper.result.Result;

import java.util.List;


public interface DefenseTopicsService {

    Result<Object> addDefense(EditDefenseDto editDefenseDto);

    Result<PageResult<DefenseTopics>> getAllDefense(int pageNum, int pageSize);

    Result<Object> editDefense(EditDefenseDto editDefenseDto);

    Result<List<DefenseQuestions>> getDefenseQuestionById(Integer topicId);

    Result<Object> deleteDefenseTopics(Integer topicId);

    Result<Object> getTopicById(Integer topicId);
}
