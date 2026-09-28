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

    /**
     * 该轮是否已落库（幂等键：defense_id + round_num）。
     * 前端重试会重发同一轮请求，落库前用它挡掉重复插入（2026-09-28 N4）。
     */
    int countByDefenseIdAndRound(@Param("defenseId") Integer defenseId, @Param("roundNum") Integer roundNum);

    /**
     * 该场答辩最后一次评分落库时间。
     * 用于判断未完成场次是否仍在「可续答」的时间窗口内（2026-09-28 回归修复）。
     */
    java.time.LocalDateTime getLastScoreTime(@Param("defenseId") Integer defenseId);

    List<DefenseScoreRecord> getScoreRecordsByDefenseId(@Param("defenseId") Integer defenseId);

    Map<String, Object> aggregateScoresByDefenseId(@Param("defenseId") Integer defenseId);
}
