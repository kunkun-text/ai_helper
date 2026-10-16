package com.ai_helper.ai_helper.pojo.dto;

import lombok.Data;

/**
 * 上传报告文档的 AI 五维评析结果（2026-10-16 · 报告五维评分第一期）。
 *
 * <p>与逐轮答辩的评分不同，这里的一次评析对应**一整份上传材料**，
 * 五维语义沿用文字答辩（表达/逻辑/专业/应变/创新，各 10 分、总分 50），
 * 但在「报告」语境下的含义另作操作化定义（见评分 prompt）。</p>
 *
 * <p>{@code totalScore} 恒等于五维之和——**由服务端计算，不采信模型自报的总分**，
 * 与 {@code ScorePersistenceServiceImpl} 的管道格式口径保持一致，
 * 避免出现「模型自报 48、五维和 32」这类分叉。</p>
 */
@Data
public class DocScoreResult {

    /** 总分（= 五维之和，0~50） */
    private double totalScore;

    /** 表达 */
    private double expression;

    /** 逻辑 */
    private double logic;

    /** 专业 */
    private double professional;

    /** 应变 */
    private double adaptability;

    /** 创新 */
    private double innovation;

    /** 逐维点评（已拼成「表达（7分）：…」的多行文本，供落库与展示） */
    private String dimComments;

    /** 模型原始输出（解析失败时用于排查，不落库） */
    private String rawOutput;
}
