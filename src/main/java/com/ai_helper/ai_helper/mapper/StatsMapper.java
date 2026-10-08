package com.ai_helper.ai_helper.mapper;

import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 教师端数据统计（借鉴 smart-medicine 的「系统信息统计」思路）。
 *
 * <p>全部为只读聚合查询，与答辩主链路完全隔离；SQL 见 {@code resources/Mapper/StatsMapper.xml}。</p>
 */
public interface StatsMapper {

    /**
     * 总体概览：答辩总场次 / 已完成 / 进行中 / 课题数 / 学生数 / 平均分。
     *
     * @return 单行 Map（列名即 key）
     */
    Map<String, Object> selectOverview();

    /**
     * 各课题的答辩场次分布（含 0 场课题），按场次倒序。
     *
     * @param limit 最多返回多少条
     */
    List<Map<String, Object>> selectTopicDistribution(@Param("limit") int limit);

    /**
     * 最近 N 天每日答辩场次（用于趋势）。
     *
     * @param days 天数
     */
    List<Map<String, Object>> selectDailyTrend(@Param("days") int days);

    /**
     * 【N9 · 2026-10-08】按课题统计：题目数 / 答辩场次数 / 已完成场次 / 平均分（含 0 场课题）。
     *
     * @param limit 最多返回多少条
     */
    List<Map<String, Object>> selectTopicStats(@Param("limit") int limit);

    /**
     * 【N9 · 2026-10-08】取某个课题下的答辩记录（供「按课题导出成绩」使用，列与教师端列表同口径）。
     *
     * @param topicId 课题 ID
     * @param limit   最多返回条数（导出上限）
     */
    List<com.ai_helper.ai_helper.pojo.vo.DefenseRecordsVo> selectRecordsByTopic(
            @Param("topicId") Integer topicId, @Param("limit") int limit);
}
