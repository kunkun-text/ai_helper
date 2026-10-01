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
}
