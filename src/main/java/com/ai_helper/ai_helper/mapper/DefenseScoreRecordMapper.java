package com.ai_helper.ai_helper.mapper;

import com.ai_helper.ai_helper.pojo.entity.DefenseScoreRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

@Mapper
public interface DefenseScoreRecordMapper {

    int insertScoreRecord(DefenseScoreRecord record);

    List<DefenseScoreRecord> getScoreRecordsByDefenseId(@Param("defenseId") Integer defenseId);

    Map<String, Object> aggregateScoresByDefenseId(@Param("defenseId") Integer defenseId);
}
