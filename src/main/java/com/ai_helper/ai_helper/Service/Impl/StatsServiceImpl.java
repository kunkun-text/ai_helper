package com.ai_helper.ai_helper.Service.Impl;

import com.ai_helper.ai_helper.Service.StatsService;
import com.ai_helper.ai_helper.mapper.StatsMapper;
import com.ai_helper.ai_helper.result.Result;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 教师端数据统计实现。
 */
@Slf4j
@Service
public class StatsServiceImpl implements StatsService {

    /** 课题分布最多展示条数 */
    private static final int TOPIC_LIMIT = 10;

    /** 趋势统计天数 */
    private static final int TREND_DAYS = 14;

    /** 【N9】按课题统计最多返回条数 */
    private static final int TOPIC_STATS_LIMIT = 500;

    @Resource
    private StatsMapper statsMapper;

    @Override
    public Result<Map<String, Object>> overview() {
        Map<String, Object> data = new LinkedHashMap<>();
        Map<String, Object> overview = statsMapper.selectOverview();
        data.put("overview", overview == null ? new LinkedHashMap<String, Object>() : overview);
        data.put("topicDistribution", statsMapper.selectTopicDistribution(TOPIC_LIMIT));
        data.put("dailyTrend", statsMapper.selectDailyTrend(TREND_DAYS));
        return Result.success(data);
    }

    @Override
    public Result<List<Map<String, Object>>> topicStats() {
        // 【N9】按课题统计：总览页「课题分布」只有场次数，这里补上题目数/已完成/平均分，供教师按课题横向比较
        return Result.success(statsMapper.selectTopicStats(TOPIC_STATS_LIMIT));
    }
}
