package com.ai_helper.ai_helper.mapper;

import com.ai_helper.ai_helper.pojo.vo.DefenseRecordsVo;
import com.ai_helper.ai_helper.pojo.vo.DetailRecordsVo;
import com.ai_helper.ai_helper.pojo.vo.QuestionDetailVo;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface DefenseRecordsMapper {


    List<DefenseRecordsVo> getDefenseRecords();

    DetailRecordsVo getDetailRecords(Integer defenseId);

    List<QuestionDetailVo> getDefenseQuestionsAnswers(Integer defenseId);
}
