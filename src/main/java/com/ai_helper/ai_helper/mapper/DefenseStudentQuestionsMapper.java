package com.ai_helper.ai_helper.mapper;

import com.ai_helper.ai_helper.pojo.entity.DefenseStudentQuestions;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface DefenseStudentQuestionsMapper {

    int insertStudentQuestion(DefenseStudentQuestions question);
    
    int getNextSortNumber(@Param("defenseId") Integer defenseId);
    
    List<DefenseStudentQuestions> getQuestionsByDefenseId(@Param("defenseId") Integer defenseId);
}
