package com.ai_helper.ai_helper.mapper;

import com.ai_helper.ai_helper.pojo.entity.DefenseQuestions;
import com.ai_helper.ai_helper.pojo.entity.DefenseTopics;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface DefenseTopicsMapper {

    int addDefense(DefenseTopics defenseTopics);

    List<DefenseTopics> selectAllDefense(@Param("offset") int offset, @Param("limit") int limit);

    long countAllDefense();

    void editDefense(DefenseTopics editDefenseTopics);

    /**
     * 删除指定主题下的所有问题
     */
    int deleteQuestionsByTopicId(@Param("topicId") Integer topicId);


    int addDefenseQuestion(DefenseQuestions question);

    /**
     * 根据问题ID查询问题
     */
    List<DefenseQuestions> getDefenseQuestionById(Integer topicId);

    int deleteDefenseTopics(Integer topicId);
}
