package com.ai_helper.ai_helper.Service;

import com.ai_helper.ai_helper.result.Result;

import java.util.Map;

/**
 * 教师端数据统计服务。
 */
public interface StatsService {

    /**
     * 数据总览：概览指标 + 课题分布 + 近期趋势。
     *
     * @return data 为 {@code {overview, topicDistribution, dailyTrend}} 三段结构
     */
    Result<Map<String, Object>> overview();
}
