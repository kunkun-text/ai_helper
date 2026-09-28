package com.ai_helper.ai_helper.Service;

import com.ai_helper.ai_helper.pojo.entity.DefenseScoreRecord;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 评分持久化服务。
 *
 * <p>每轮答辩结束后把五维评分写入 MySQL。默认异步（不阻塞答辩响应）；
 * 最后一轮由调用方改走同步，保证收尾聚合总分时本轮分数已落库（2026-09-28 N21）。</p>
 */
public interface ScorePersistenceService {

    /**
     * 异步保存单轮评分记录。
     *
     * @return 落库结果：true = 成功，false = 失败（实现内已记 error 日志并返回明确状态，
     *         不再像旧实现那样「只打一行日志、上层完全无感知」）
     */
    CompletableFuture<Boolean> saveRoundScoreAsync(DefenseScoreRecord record);

    /**
     * 同步保存单轮评分记录。
     *
     * <p>用于最后一轮收尾：收尾要立刻聚合总分，异步落库存在末轮分数漏算的窗口。</p>
     */
    void saveRoundScore(DefenseScoreRecord record);

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
