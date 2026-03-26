package com.ai_helper.ai_helper.mapper;

import com.ai_helper.ai_helper.pojo.dto.DefenseRecordsDto;
import com.ai_helper.ai_helper.pojo.entity.DefenseTopics;
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

    void insertVideoUrl(@Param("userId") String userId, @Param("url")  String url);

    List<DefenseTopics> getDefenseTopic();
}
