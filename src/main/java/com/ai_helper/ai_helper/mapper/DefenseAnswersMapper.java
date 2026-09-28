
package com.ai_helper.ai_helper.mapper;

import com.ai_helper.ai_helper.pojo.dto.AiAnalysis;
import com.ai_helper.ai_helper.pojo.entity.DefenseAnswers;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface DefenseAnswersMapper {

    int insertAnswer(DefenseAnswers answer);

    void insertAiFeedback(AiAnalysis aiAnalysis);

    /** 删除指定答辩记录下 AI 追问的回答（sq_id 非空即为追问回答，预设题回答的 sq_id 为 NULL） */
    int deleteAiAnswersByDefenseId(@Param("defenseId") Integer defenseId);

    /**
     * 按作答顺序取某场答辩的全部答案行。
     * 用于重进答辩时回灌会话记忆（2026-09-28 N1：中途退出后继续作答）。
     */
    List<DefenseAnswers> getAnswersByDefenseId(@Param("defenseId") Integer defenseId);
}
