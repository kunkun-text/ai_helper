package com.ai_helper.ai_helper.pojo.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 云端文本模型（GLM / DeepSeek）的一次调用结果。
 *
 * <p>【2026-10-25 · N83】只承载「模型输出了什么 + 哪个引擎出的 + 花了多久」，
 * 不含评分语义——五维解析仍由 {@code DocScoreParser} 负责。
 * 带上 provider/model 是为了让日志与成绩单能写清「这一分是谁给的」，
 * 便于事后核对与额度排查。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AiScoringResult {

    /** 模型输出的正文（已 trim）。空内容不会构造本对象，调用方按失败处理 */
    private String content;

    /** 引擎标识：glm / deepseek */
    private String provider;

    /** 具体型号，如 glm-4.7、glm-4.5-air、deepseek-v4-flash */
    private String model;

    /** 本次调用耗时（毫秒），用于排查慢请求与额度问题 */
    private long elapsedMs;

    /** 「引擎:型号」的短标识，写日志/成绩单用（不含任何密钥） */
    public String describe() {
        return (provider == null ? "" : provider) + (model == null ? "" : ":" + model);
    }
}
