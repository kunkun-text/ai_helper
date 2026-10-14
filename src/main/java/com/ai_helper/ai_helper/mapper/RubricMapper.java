package com.ai_helper.ai_helper.mapper;

import com.ai_helper.ai_helper.pojo.entity.DefenseRubric;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 要点核验清单（N7 · 2026-10-14）。表 {@code defense_rubric}，
 * 建表语句见 {@code docs/ddl_defense_rubric.sql}（以及最新 {@code docs/schema.sql}）。
 */
@Mapper
public interface RubricMapper {

    /** 取某道预设题的要点清单；不存在返回 null */
    DefenseRubric selectByQuestionId(@Param("questionId") Integer questionId);

    /** 写入/更新要点清单（按 question_id 幂等 upsert）；返回受影响行数 */
    int upsertRubric(DefenseRubric rubric);
}
