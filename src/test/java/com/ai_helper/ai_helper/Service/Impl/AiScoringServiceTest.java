package com.ai_helper.ai_helper.Service.Impl;

import com.ai_helper.ai_helper.Config.AppProperties;
import com.ai_helper.ai_helper.pojo.dto.AiScoringResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 云端评分适配器（N83）的单测：**不连真实网络**，全部用桩。
 *
 * <p>守住四类真实踩过的坑：<br>
 * ① GLM 必须带 {@code thinking:disabled}（不关思考时 content 为空，实测 20 token 全在
 * {@code reasoning_content}）；DeepSeek 不能带该字段（不支持，会 400）；<br>
 * ② 空正文必须切下一档，且<b>绝不拿 reasoning_content 当正文</b>；<br>
 * ③ 非 2xx / 超时必须切下一档，不能把异常抛给前端；<br>
 * ④ 没配 key 的渠道不能进回退链（否则每次白等超时）。</p>
 */
class AiScoringServiceTest {

    private static AppProperties props(String provider, String glmKey, String dsKey) {
        AppProperties p = new AppProperties();
        AppProperties.Ai.Scoring s = p.getAi().getScoring();
        s.setEnabled(true);
        s.setProvider(provider);
        s.setTemperature(0.0);
        s.setThinkingEnabled(false);
        s.setMaxTokens(1500);
        s.setGlm(new AppProperties.Ai.Scoring.Glm());
        s.getGlm().setApiKey(glmKey);
        s.getGlm().setModel("glm-4.7");
        s.getGlm().setFallbackModel("glm-4.5-air");
        s.setDeepseek(new AppProperties.Ai.Scoring.DeepSeek());
        s.getDeepseek().setApiKey(dsKey);
        s.getDeepseek().setModel("deepseek-v4-flash");
        return p;
    }

    private static String okResponse(String content) {
        return "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"" + content + "\"}}]}";
    }

    // ==================== ① 请求体 ====================

    @Test
    @DisplayName("GLM 请求体必须带 thinking=disabled，否则返回空正文")
    void glmRequestBodyDisablesThinking() throws Exception {
        AiScoringServiceImpl svc = new AiScoringServiceImpl(props("glm", "k1", ""), (c, b) -> okResponse("x"));
        AiScoringServiceImpl.Candidate glm = svc.candidates().get(0);

        String body = svc.buildRequestBody(glm, "sys", "user");

        assertTrue(body.contains("\"thinking\":{\"type\":\"disabled\"}"), "GLM 必须关闭思考: " + body);
        assertTrue(body.contains("\"temperature\":0.0"), "评分固定 temperature=0");
        assertTrue(body.contains("\"stream\":false"));
    }

    @Test
    @DisplayName("DeepSeek 请求体不得带 thinking 字段（不支持，会 400）")
    void deepseekRequestBodyHasNoThinking() throws Exception {
        AiScoringServiceImpl svc = new AiScoringServiceImpl(props("deepseek", "", "k2"), (c, b) -> okResponse("x"));
        AiScoringServiceImpl.Candidate ds = svc.candidates().get(0);

        String body = svc.buildRequestBody(ds, "sys", "user");

        assertFalse(body.contains("thinking"), "DeepSeek 不能带 thinking: " + body);
        assertEquals("deepseek-v4-flash", ds.model);
    }

    // ==================== ② 响应解析 ====================

    @Test
    @DisplayName("正常响应解析出正文")
    void extractContentParsesChoices() {
        assertEquals("你好", AiScoringServiceImpl.extractContent(okResponse("你好")));
    }

    @Test
    @DisplayName("空正文/结构不符一律返回 null（交给下一档，不许当成功）")
    void extractContentRejectsEmptyAndBroken() {
        assertNull(AiScoringServiceImpl.extractContent(okResponse("")));
        assertNull(AiScoringServiceImpl.extractContent("{\"choices\":[]}"));
        assertNull(AiScoringServiceImpl.extractContent("not json"));
        assertNull(AiScoringServiceImpl.extractContent(""));
        // 只有 reasoning_content、没有 content：必须判空，不能拿推理过程当答案
        assertNull(AiScoringServiceImpl.extractContent(
                "{\"choices\":[{\"message\":{\"reasoning_content\":\"想了很多\"}}]}"));
    }

    // ==================== ③ 回退链 ====================

    @Test
    @DisplayName("主模型空正文 → 自动切到备用模型")
    void fallsBackToFallbackModelWhenContentEmpty() {
        List<String> tried = new ArrayList<>();
        AiScoringServiceImpl svc = new AiScoringServiceImpl(props("glm", "k1", ""), (c, b) -> {
            tried.add(c.describe());
            return tried.size() == 1 ? okResponse("") : okResponse("备用模型输出");
        });

        AiScoringResult result = svc.complete("sys", "user");

        assertNotNull(result);
        assertEquals("备用模型输出", result.getContent());
        assertEquals("glm-4.5-air", result.getModel(), "应回退到备用模型");
        assertEquals(List.of("glm/glm-4.7", "glm/glm-4.5-air"), tried);
    }

    @Test
    @DisplayName("主模型抛异常（超时/非2xx）→ 不抛给调用方，继续下一档")
    void fallsBackWhenSenderThrows() {
        List<String> tried = new ArrayList<>();
        AiScoringServiceImpl svc = new AiScoringServiceImpl(props("glm", "k1", "k2"), (c, b) -> {
            tried.add(c.describe());
            if (tried.size() <= 2) {
                throw new IllegalStateException("HTTP 429 rate limited");
            }
            return okResponse("deepseek 兜底");
        });

        AiScoringResult result = svc.complete("sys", "user");

        assertNotNull(result);
        assertEquals("deepseek", result.getProvider());
        assertEquals(3, tried.size(), "应依次尝试 glm-4.7 / glm-4.5-air / deepseek: " + tried);
    }

    @Test
    @DisplayName("全部渠道失败 → 返回 null（由调用方回退本地 3B），不抛异常")
    void returnsNullWhenAllFail() {
        AiScoringServiceImpl svc = new AiScoringServiceImpl(props("glm", "k1", ""), (c, b) -> {
            throw new IllegalStateException("timeout");
        });

        assertNull(svc.complete("sys", "user"));
    }

    // ==================== ④ 渠道装配 ====================

    @Test
    @DisplayName("没配 key 的渠道不进回退链；全都为空时 cloudAvailable=false")
    void skipsChannelsWithoutKey() {
        AiScoringServiceImpl none = new AiScoringServiceImpl(props("glm", "", ""), (c, b) -> okResponse("x"));
        assertTrue(none.candidates().isEmpty());
        assertFalse(none.cloudAvailable());
        assertNull(none.complete("sys", "user"), "未配置时直接返回 null，不白等超时");

        AiScoringServiceImpl onlyGlm = new AiScoringServiceImpl(props("glm", "k1", ""), (c, b) -> okResponse("x"));
        assertEquals(2, onlyGlm.candidates().size(), "glm 主 + 备用两个模型");

        AiScoringServiceImpl dsOnly = new AiScoringServiceImpl(props("deepseek", "", "k2"), (c, b) -> okResponse("x"));
        assertEquals(1, dsOnly.candidates().size());
        assertEquals("deepseek", dsOnly.candidates().get(0).provider);
    }

    @Test
    @DisplayName("provider=local 或 enabled=false 时不调用云端")
    void respectsLocalProviderAndDisabledFlag() {
        AppProperties local = props("local", "k1", "k2");
        assertTrue(new AiScoringServiceImpl(local, (c, b) -> okResponse("x")).candidates().isEmpty());

        AppProperties disabled = props("glm", "k1", "k2");
        disabled.getAi().getScoring().setEnabled(false);
        assertTrue(new AiScoringServiceImpl(disabled, (c, b) -> okResponse("x")).candidates().isEmpty());
    }

    @Test
    @DisplayName("空 userPrompt 不发起调用")
    void blankUserPromptSkipsCall() {
        boolean[] called = {false};
        AiScoringServiceImpl svc = new AiScoringServiceImpl(props("glm", "k1", ""), (c, b) -> {
            called[0] = true;
            return okResponse("x");
        });
        assertNull(svc.complete("sys", "   "));
        assertFalse(called[0]);
    }
}
