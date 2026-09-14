package com.ai_helper.ai_helper.mapper;

import com.ai_helper.ai_helper.pojo.entity.DefenseScoreRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

@Mapper
public interface DefenseScoreRecordMapper {

    int insertScoreRecord(DefenseScoreRecord record);

    /** 统计该答辩已落库的评分行数（每次作答含放弃0分恰落一行），用作权威轮次计数（2026-09-15 修复） */
    int countByDefenseId(@Param("defenseId") Integer defenseId);

    List<DefenseScoreRecord> getScoreRecordsByDefenseId(@Param("defenseId") Integer defenseId);

    Map<String, Object> aggregateScoresByDefenseId(@Param("defenseId") Integer defenseId);
}
