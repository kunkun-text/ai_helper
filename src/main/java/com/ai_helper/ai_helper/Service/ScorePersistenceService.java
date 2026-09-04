package com.ai_helper.ai_helper.Service;

import com.ai_helper.ai_helper.pojo.entity.DefenseScoreRecord;
import org.springframework.scheduling.annotation.Async;

import java.util.Map;

/**
 * 异步评分持久化服务
 * 每轮答辩结束后，将5维评分异步存入MySQL，不阻塞主响应
 */
public interface ScorePersistenceService {

    /**
     * 异步保存单轮评分记录
     */
    @Async("taskExecutor")
    void saveRoundScoreAsync(DefenseScoreRecord record);

    /**
     * 从AI回复中解析5维评分和评语
     * @return Map包含: expression, logic, professional, adaptability, innovation, comment
     */
    Map<String, Object> parseScoresFromResponse(String aiResponse);

    /**
     * 聚合某次答辩的所有轮次评分
     */
    Map<String, Object> aggregateScores(Integer defenseId);

    /**
     * 构建总体评价的轻量prompt（~300 token）
     */
    String buildFinalEvaluatePrompt(Map<String, Object> aggregatedScores,
                                    java.util.List<DefenseScoreRecord> records);
}
