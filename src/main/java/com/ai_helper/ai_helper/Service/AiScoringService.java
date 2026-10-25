package com.ai_helper.ai_helper.Service;

import com.ai_helper.ai_helper.pojo.dto.AiScoringResult;

/**
 * 云端文本模型调用（2026-10-25 · N83）。
 *
 * <p><b>为什么单独抽一个服务</b>：报告评析、视频评分、切题复核三处都要「把一段 prompt
 * 交给大模型写一段文字」，如果每处各写一套 HTTP，密钥读取、GLM 的 {@code thinking}
 * 开关、超时与回退顺序就会各说各话。收敛到这里之后：
 * 换模型、调超时、加兜底只改一处。</p>
 *
 * <p><b>与本地 3B 的关系</b>：本服务只负责「云端」。全部渠道失败时返回 {@code null}，
 * 由调用方决定是否退回本地 {@code chatClient}（{@code fallback-to-local} 在调用方生效）。
 * 这样「云端点评成功、云端打分失败」可以只回退后半段，不必整场重来。</p>
 *
 * <p><b>安全</b>：API key 只从 {@code app.ai.scoring.*.api-key} 读取（本地
 * {@code application.yml} 或环境变量），日志只打 provider/model/耗时/错误原因，
 * <b>任何路径都不打印 key 与请求体</b>。</p>
 */
public interface AiScoringService {

    /**
     * 是否至少配置了一个可用的云端渠道（未配置时调用方直接走本地，不必白等一次超时）。
     */
    boolean cloudAvailable();

    /**
     * 以「system + user」两段式调用云端模型。按
     * {@code glm 主模型 → glm 备用模型 → deepseek} 顺序逐级回退。
     *
     * @return 成功返回正文与引擎信息；全部失败（含未配置、超时、非 2xx、空正文）返回 {@code null}
     */
    AiScoringResult complete(String systemPrompt, String userPrompt);
}
