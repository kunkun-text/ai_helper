package com.ai_helper.ai_helper.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 转写纠错词表回归测试（2026-10-20）。
 *
 * <p>守两件事：① 长词优先，避免「文字打变」被「打变」提前替换成「文字答辩」；
 * ② 无规则/空文本原样返回，绝不因纠错本身抛异常或清空内容。</p>
 */
class AsrTextCorrectorTest {

    @Test
    @DisplayName("长词优先：文字打变 → 文字答辩，而不是文字答辩答辩")
    void longestFirst() {
        Map<String, String> rules = new LinkedHashMap<>();
        rules.put("打变", "答辩");
        rules.put("文字打变", "文字答辩");

        assertEquals("这是文字答辩的记录", AsrTextCorrector.correct("这是文字打变的记录", rules));
    }

    @Test
    @DisplayName("多个高置信度同音词一次替换")
    void replaceMultipleTerms() {
        Map<String, String> rules = new LinkedHashMap<>();
        rules.put("剥锁", "部署");
        rules.put("系统平方", "系统评分");

        assertEquals("先把系统部署好，再看系统评分",
                AsrTextCorrector.correct("先把系统剥锁好，再看系统平方", rules));
    }

    @Test
    @DisplayName("turbo 的「打遍 / 系统平分」变体也能纠正")
    void turboVariants() {
        Map<String, String> rules = new LinkedHashMap<>();
        rules.put("打遍", "答辩");
        rules.put("文字打遍", "文字答辩");
        rules.put("系统平分", "系统评分");

        assertEquals("这是文字答辩，系统评分在这里",
                AsrTextCorrector.correct("这是文字打遍，系统平分在这里", rules));
    }

    @Test
    @DisplayName("空文本 / 无规则 / null 原样返回")
    void noopCases() {
        assertNull(AsrTextCorrector.correct(null, Map.of("打变", "答辩")));
        assertEquals("", AsrTextCorrector.correct("", Map.of("打变", "答辩")));
        assertEquals("原文", AsrTextCorrector.correct("原文", Map.of()));
        assertEquals("原文", AsrTextCorrector.correct("原文", null));
    }
}
