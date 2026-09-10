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

    /** 删除指定答辩记录下所有历史 AI 追问（用于开始新答辩时重置追问额度） */
    int deleteAiQuestionsByDefenseId(@Param("defenseId") Integer defenseId);
}
