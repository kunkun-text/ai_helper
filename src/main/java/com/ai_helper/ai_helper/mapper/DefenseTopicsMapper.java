package com.ai_helper.ai_helper.mapper;

import com.ai_helper.ai_helper.pojo.dto.TopicDto;
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

    TopicDto getTopicById(Integer topicId);

    /** 【A3】查询课题归属教师 user_id（编辑/删除前的归属校验用） */
    Integer selectTeacherIdByTopicId(@Param("topicId") Integer topicId);

    // ==================== 【2026-10-10 · 差量更新】editDefense 防级联删作答 ====================

    /**
     * 【2026-10-10】课题下 teacher 类题目全量行（editDefense 差量比对用，含标准答案）。
     * 只取 teacher 类：前端编辑弹层加载与提交的都是这一类，ai 类题目不在编辑范围内、不动。
     */
    List<DefenseQuestions> selectTeacherQuestionsByTopicId(@Param("topicId") Integer topicId);

    /** 【2026-10-10】题目被学生作答引用的次数（删除守卫：&gt;0 拒绝删除该题） */
    int countAnswersByQuestionId(@Param("questionId") Integer questionId);

    /** 【2026-10-10】课题下全部题目被学生作答引用的总数（题库覆盖导入守卫用） */
    int countAnswersByTopicId(@Param("topicId") Integer topicId);

    /** 【2026-10-10】保留 question_id 原位更新题干/标准答案（历史作答关联不断链） */
    int updateDefenseQuestion(DefenseQuestions question);

    /** 【2026-10-10】按主键删除单道题（仅确认无作答引用时由 Service 调用） */
    int deleteDefenseQuestionById(@Param("questionId") Integer questionId);
}
