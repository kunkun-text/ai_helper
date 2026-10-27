package com.ai_helper.ai_helper.Service.Impl;

import com.ai_helper.ai_helper.Config.AppProperties;
import com.ai_helper.ai_helper.Service.AiScoringService;
import com.ai_helper.ai_helper.Service.MaterialCoverageService;
import com.ai_helper.ai_helper.pojo.dto.AiScoringResult;
import com.ai_helper.ai_helper.pojo.dto.MaterialCoverage;
import com.ai_helper.ai_helper.util.MaterialScorePrompts;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 【2026-10-27】预设题覆盖判定（本地 3B + 云端复核）。
 *
 * <p><b>一次调用判完所有题：</b>按用户口径，逐题判定放在同一次模型调用里，
 * 每题输出一行「题号:是|依据」。服务端只数命中数，不解析自然语言长文。</p>
 *
 * <p><b>为什么不用词表/相似度：</b>词表只能覆盖一种文件、一种学科；预设题来自数据库，
 * 换课题只换数据。也不需要引入新依赖。</p>
 *
 * <p><b>失败方向：</b>调用异常或输出不可解析 → 返回 {@link MaterialCoverage#unknown}，
 * 由调用方按"待复审、不出分"处理（用户 2026-10-27 拍板：宁可漏评，不给错分）。</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MaterialCoverageServiceImpl implements MaterialCoverageService {

    private final ChatClient chatClient;
    private final AiScoringService aiScoringService;
    private final AppProperties appProperties;

    @Value("${spring.ai.openai.chat.options.model:unknown}")
    private String ollamaModelName;

    @Override
    public MaterialCoverage judge(String topicName, String topicDescription,
                                  List<String> questions, String material) {
        AppProperties.MaterialCoverage cfg = appProperties.getMaterialCoverage();
        if (!cfg.isEnabled() || questions == null || questions.isEmpty()) {
            return MaterialCoverage.notApplicable();
        }
        if (material == null || material.isBlank()) {
            return MaterialCoverage.unknown("材料为空", questions);
        }
        try {
            String user = buildUser(topicName, topicDescription, questions, material, cfg);
            String out = chatClient.prompt()
                    .system(MaterialScorePrompts.COVERAGE_SYSTEM)
                    .user(user)
                    .options(OpenAiChatOptions.builder()
                            .model(ollamaModelName)
                            .temperature(0.0)
                            .maxTokens(tokensFor(cfg, questions.size()))
                            .build())
                    .call().content();

            MaterialCoverage coverage = MaterialCoverage.parse(out, questions);
            log.info("材料方向判定 - 命中 {}/{}，漏行 {}",
                    coverage.getHitCount(), coverage.getTotal(), coverage.hasUnparsed());
            if (coverage.hasUnparsed()) {
                log.warn("材料方向判定有漏行，原文: {}",
                        out == null ? "（空）" : out.replaceAll("\\s+", " "));
            }
            return coverage;
        } catch (Exception e) {
            log.warn("材料方向判定调用失败（按待复审处理）- 原因: {}", e.getMessage());
            return MaterialCoverage.unknown(e.getMessage(), questions);
        }
    }

    @Override
    public MaterialCoverage reviewWithCloud(String topicName, String topicDescription,
                                            List<String> questions, String material) {
        AppProperties.MaterialCoverage cfg = appProperties.getMaterialCoverage();
        if (!cfg.isCloudReviewEnabled() || questions == null || questions.isEmpty()) {
            return null;
        }
        if (aiScoringService == null || !aiScoringService.cloudAvailable()) {
            log.info("未配置云端渠道，跳过材料方向复核");
            return null;
        }
        try {
            String user = buildUser(topicName, topicDescription, questions, material, cfg);
            AiScoringResult resp = aiScoringService.complete(MaterialScorePrompts.COVERAGE_CLOUD_SYSTEM, user);
            if (resp == null || resp.getContent() == null || resp.getContent().isBlank()) {
                log.warn("云端方向复核返回空");
                return null;
            }
            MaterialCoverage coverage = MaterialCoverage.parse(resp.getContent(), questions);
            log.info("云端方向复核 - 引擎 {}/{}，命中 {}/{}，漏行 {}",
                    resp.getProvider(), resp.getModel(),
                    coverage.getHitCount(), coverage.getTotal(), coverage.hasUnparsed());
            return coverage;
        } catch (Exception e) {
            log.warn("云端方向复核异常 - 原因: {}", e.getMessage());
            return null;
        }
    }

    /** 一次调用判完所有预设题；题号从 1 开始，与解析端一一对应 */
    private String buildUser(String topicName, String topicDescription,
                             List<String> questions, String material, AppProperties.MaterialCoverage cfg) {
        StringBuilder b = new StringBuilder();
        b.append("【课题】").append(blankTo(topicName, "（未提供）")).append("\n");
        if (topicDescription != null && !topicDescription.isBlank()) {
            b.append("【课题说明】").append(topicDescription.trim()).append("\n");
        }
        b.append("【预设题】\n");
        for (int i = 0; i < questions.size(); i++) {
            b.append(i + 1).append(". ").append(questions.get(i)).append("\n");
        }
        b.append("【材料·开始】\n").append(headTail(material, cfg.getMaxChars())).append("\n【材料·结束】\n");
        b.append("请按上面的题号逐题输出「题号:是|依据」或「题号:否|依据」，共 ").append(questions.size()).append(" 行。");
        return b.toString();
    }

    /** 输出预算：每题约 40 token，封顶取配置值 */
    private int tokensFor(AppProperties.MaterialCoverage cfg, int questionCount) {
        return Math.min(cfg.getMaxTokens(), 60 + 40 * questionCount);
    }

    private static String blankTo(String s, String fallback) {
        return s == null || s.isBlank() ? fallback : s;
    }

    /** 保头保尾截断：结论与方案常分布两头，中间省略比只留开头更安全 */
    private static String headTail(String text, int max) {
        if (text == null || max <= 0 || text.length() <= max) {
            return text;
        }
        int head = (int) (max * 0.6);
        int tail = max - head;
        return text.substring(0, head) + "\n……（中间省略）……\n" + text.substring(text.length() - tail);
    }
}
