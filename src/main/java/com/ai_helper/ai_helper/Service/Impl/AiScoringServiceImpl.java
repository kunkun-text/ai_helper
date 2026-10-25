package com.ai_helper.ai_helper.Service.Impl;

import com.ai_helper.ai_helper.Config.AppProperties;
import com.ai_helper.ai_helper.Service.AiScoringService;
import com.ai_helper.ai_helper.pojo.dto.AiScoringResult;
import com.ai_helper.ai_helper.util.UploadUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 云端文本模型调用实现（2026-10-25 · N83 · 需求清单 D-P0-1）。
 *
 * <p><b>不新增依赖</b>：用 JDK 17 自带的 {@link HttpClient} + 已在 classpath 的 Jackson。
 * GLM 与 DeepSeek 都提供 OpenAI 兼容的 {@code /chat/completions}，所以一套请求/解析代码
 * 覆盖两家，只有「GLM 需要显式关闭思考」这一处差异。</p>
 *
 * <p><b>回退顺序</b>（每次调用独立回退，失败只降级、不抛给前端）：
 * {@code glm 主模型 → glm 备用模型 → deepseek}。以下情况都算失败并尝试下一档：
 * HTTP 非 2xx、连接/读取超时、正文为空、JSON 解析失败。本地 3B 由调用方兜底，
 * 本类不碰本地模型（避免「云端适配器内部再调 Ollama」这种双向依赖）。</p>
 *
 * <p><b>为什么必须关闭 thinking</b>：2026-10-21 实测 glm-4.7 开启思考时，
 * 20 个 token 全部落在 {@code reasoning_content}、{@code content} 为空——评分拿到空串会
 * 白白触发一次回退。故默认请求体带 {@code "thinking":{"type":"disabled"}}；
 * 且<b>绝不把 {@code reasoning_content} 当正文</b>（那是模型的内部推理，不是答案）。</p>
 */
@Slf4j
@Service
public class AiScoringServiceImpl implements AiScoringService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String GLM = "glm";
    private static final String DEEPSEEK = "deepseek";

    private final AppProperties appProperties;

    /**
     * 真正发请求的函数。抽成接口只为单测能注入桩（不连真实网络），
     * 生产环境固定为 {@link #sendViaHttp}。
     */
    private ChatSender sender;

    @Autowired
    public AiScoringServiceImpl(AppProperties appProperties) {
        this.appProperties = appProperties;
        this.sender = this::sendViaHttp;
    }

    /** 包内可见：单测注入桩，避免真实网络调用 */
    AiScoringServiceImpl(AppProperties appProperties, ChatSender sender) {
        this.appProperties = appProperties;
        this.sender = sender == null ? this::sendViaHttp : sender;
    }

    @Override
    public boolean cloudAvailable() {
        return !candidates().isEmpty();
    }

    @Override
    public AiScoringResult complete(String systemPrompt, String userPrompt) {
        if (UploadUtils.isBlank(userPrompt)) {
            return null;
        }
        List<Candidate> chain = candidates();
        if (chain.isEmpty()) {
            log.debug("云端评分未启用或未配置 key，跳过");
            return null;
        }
        for (Candidate candidate : chain) {
            long start = System.currentTimeMillis();
            try {
                String body = buildRequestBody(candidate, systemPrompt, userPrompt);
                String response = sender.send(candidate, body);
                String content = extractContent(response);
                if (UploadUtils.isBlank(content)) {
                    // 实测：GLM 开着思考时 content 为空、内容全在 reasoning_content。
                    // 这里按失败处理并尝试下一档，绝不拿推理过程当正文。
                    log.warn("云端评分返回空正文，尝试下一档 - 引擎: {}", candidate.describe());
                    continue;
                }
                long elapsed = System.currentTimeMillis() - start;
                log.info("云端评分调用成功 - 引擎: {}, 耗时: {}ms", candidate.describe(), elapsed);
                return new AiScoringResult(content.trim(), candidate.provider, candidate.model, elapsed);
            } catch (Exception e) {
                // 只记引擎与原因：异常文本里不含 key（key 只出现在请求头，且不打请求头）
                log.warn("云端评分调用失败，尝试下一档 - 引擎: {}, 原因: {}",
                        candidate.describe(), e.getMessage());
            }
        }
        log.warn("云端评分全部渠道失败，交由调用方回退本地模型");
        return null;
    }

    // ==================================================================
    // 渠道装配
    // ==================================================================

    /**
     * 按配置装配回退链。key 为空视为该档不可用（这样换机器没配 key 时不会反复超时）。
     * {@code provider=local} 时整条链为空，直接走本地。
     */
    List<Candidate> candidates() {
        List<Candidate> list = new ArrayList<>();
        AppProperties.Ai.Scoring cfg = appProperties.getAi().getScoring();
        if (cfg == null || !cfg.isEnabled()) {
            return list;
        }
        String provider = cfg.getProvider() == null ? GLM : cfg.getProvider().trim().toLowerCase();

        AppProperties.Ai.Scoring.Glm glm = cfg.getGlm();
        if ((GLM.equals(provider) || "auto".equals(provider)) && glm != null) {
            if (!UploadUtils.isBlank(glm.getApiKey())) {
                addIfPresent(list, GLM, glm.getBaseUrl(), glm.getApiKey(), glm.getModel());
                // 备用模型仅在配置了且与主模型不同的时候加入，避免同一请求重试两次
                if (!UploadUtils.isBlank(glm.getFallbackModel())
                        && !glm.getFallbackModel().equals(glm.getModel())) {
                    addIfPresent(list, GLM, glm.getBaseUrl(), glm.getApiKey(), glm.getFallbackModel());
                }
            }
        }
        AppProperties.Ai.Scoring.DeepSeek ds = cfg.getDeepseek();
        if ((DEEPSEEK.equals(provider) || GLM.equals(provider) || "auto".equals(provider)) && ds != null) {
            if (!UploadUtils.isBlank(ds.getApiKey())) {
                addIfPresent(list, DEEPSEEK, ds.getBaseUrl(), ds.getApiKey(), ds.getModel());
            }
        }
        return list;
    }

    private static void addIfPresent(List<Candidate> list, String provider, String baseUrl,
                                     String apiKey, String model) {
        if (UploadUtils.isBlank(model)) {
            return;
        }
        list.add(new Candidate(provider, trimTrailingSlash(baseUrl), apiKey, model));
    }

    private static String trimTrailingSlash(String url) {
        if (url == null) {
            return "";
        }
        String u = url.trim();
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }

    // ==================================================================
    // 请求 / 响应（纯函数，便于单测）
    // ==================================================================

    /**
     * 构造 OpenAI 兼容请求体。
     *
     * <p>GLM 额外带 {@code thinking}：实测不关思考会返回空 {@code content}。
     * DeepSeek 不支持该字段，不能盲加（会 400）。</p>
     */
    String buildRequestBody(Candidate candidate, String systemPrompt, String userPrompt) throws Exception {
        AppProperties.Ai.Scoring cfg = appProperties.getAi().getScoring();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", candidate.model);
        List<Map<String, Object>> messages = new ArrayList<>();
        if (!UploadUtils.isBlank(systemPrompt)) {
            messages.add(Map.of("role", "system", "content", systemPrompt));
        }
        messages.add(Map.of("role", "user", "content", userPrompt));
        body.put("messages", messages);
        body.put("temperature", cfg.getTemperature());
        body.put("max_tokens", cfg.getMaxTokens());
        body.put("stream", false);
        if (GLM.equals(candidate.provider)) {
            body.put("thinking", Map.of("type", cfg.isThinkingEnabled() ? "enabled" : "disabled"));
        }
        return MAPPER.writeValueAsString(body);
    }

    /**
     * 从 OpenAI 兼容响应里取 {@code choices[0].message.content}。
     *
     * @return 正文；结构不符或为空返回 null
     */
    static String extractContent(String responseBody) {
        if (UploadUtils.isBlank(responseBody)) {
            return null;
        }
        try {
            JsonNode root = MAPPER.readTree(responseBody);
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                return null;
            }
            JsonNode message = choices.get(0).path("message");
            JsonNode content = message.path("content");
            if (content.isMissingNode() || content.isNull()) {
                return null;
            }
            String text = content.asText();
            return UploadUtils.isBlank(text) ? null : text;
        } catch (Exception e) {
            return null;
        }
    }

    /** 真正发 HTTP：非 2xx 直接抛，交给上层切下一档 */
    private String sendViaHttp(Candidate candidate, String body) throws Exception {
        AppProperties.Ai.Scoring cfg = appProperties.getAi().getScoring();
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, cfg.getConnectTimeoutSeconds())))
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(candidate.baseUrl + "/chat/completions"))
                .timeout(Duration.ofSeconds(Math.max(1, cfg.getReadTimeoutSeconds())))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + candidate.apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = client.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            // 只带状态码与截断后的响应体，绝不回显请求头（含 key）
            String snippet = response.body() == null ? "" : response.body();
            if (snippet.length() > 300) {
                snippet = snippet.substring(0, 300);
            }
            throw new IllegalStateException("HTTP " + status + " " + snippet);
        }
        return response.body();
    }

    /** 一个可调用渠道 */
    static final class Candidate {
        final String provider;
        final String baseUrl;
        final String apiKey;
        final String model;

        Candidate(String provider, String baseUrl, String apiKey, String model) {
            this.provider = provider;
            this.baseUrl = baseUrl;
            this.apiKey = apiKey;
            this.model = model;
        }

        String describe() {
            return provider + "/" + model;
        }
    }

    /** 发送函数（单测桩点） */
    @FunctionalInterface
    interface ChatSender {
        String send(Candidate candidate, String body) throws Exception;
    }
}
