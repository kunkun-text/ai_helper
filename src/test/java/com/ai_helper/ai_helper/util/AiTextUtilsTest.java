package com.ai_helper.ai_helper.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * AI 回复文本解析回归测试（N14 · 2026-10-08）。
 *
 * <p>这份解析被答辩主流程、续答回灌、防作弊比对三处共用，协议标签错一个字符就会
 * 「前端解析不到题目」——属于 SKILL 里点名的严重 Bug 类别，必须有回归网。</p>
 */
class AiTextUtilsTest {

    @Test
    @DisplayName("三段式：取『下一题:』之后的整行，且只取到行尾")
    void extractFromSegmentFormat() {
        String text = "点评:思路清晰\n评分:35.5/50|7|7|7|7|7.5\n下一题:请说明HDFS的小文件问题？\n多余内容不应被带上";
        assertEquals("请说明HDFS的小文件问题？", AiTextUtils.extractQuestionFromAiText(text));
    }

    @Test
    @DisplayName("三段式：追问是陈述句（不带问号）也必须能取到")
    void extractStatementFollowUp() {
        String text = "点评:继续\n下一题:请补充说明MapReduce的Shuffle过程";
        assertEquals("请补充说明MapReduce的Shuffle过程", AiTextUtils.extractQuestionFromAiText(text));
    }

    @Test
    @DisplayName("多个『下一题:』时取最后一个（重生成覆盖后的那一句）")
    void takeLastTag() {
        String text = "下一题:第一道问题？\n……\n下一题:最终的问题？";
        assertEquals("最终的问题？", AiTextUtils.extractQuestionFromAiText(text));
    }

    @Test
    @DisplayName("旧格式『【问题】』仍可解析（历史数据兼容）")
    void extractLegacyFormat() {
        String text = "【评价】不错\n【问题】什么是NameNode？";
        assertEquals("什么是NameNode？", AiTextUtils.extractQuestionFromAiText(text));
    }

    @Test
    @DisplayName("兜底：没有标签时取最后一个含问号的行")
    void extractByQuestionMark() {
        String text = "先说一段话\n那这道题怎么答？";
        assertEquals("那这道题怎么答？", AiTextUtils.extractQuestionFromAiText(text));
    }

    @Test
    @DisplayName("空/null 返回 null，不抛异常")
    void nullSafe() {
        assertNull(AiTextUtils.extractQuestionFromAiText(null));
        assertNull(AiTextUtils.extractQuestionFromAiText(""));
        assertNull(AiTextUtils.extractQuestionFromAiText("点评:只有点评没有题目"));
    }

    @Test
    @DisplayName("cleanQuestionText：能提取到题目就返回题目")
    void cleanQuestionTextExtracts() {
        String stored = "点评:回答一般\n评分:20/50|4|4|4|4|4\n下一题:请说明数据清洗流程？";
        assertEquals("请说明数据清洗流程？", AiTextUtils.cleanQuestionText(stored));
    }

    @Test
    @DisplayName("cleanQuestionText：整段含点评标签又提不出题目 → 视为无效（返回 null）")
    void cleanQuestionTextRejectsComment() {
        assertNull(AiTextUtils.cleanQuestionText("点评:答得不错"));
        assertNull(AiTextUtils.cleanQuestionText(null));
        assertNull(AiTextUtils.cleanQuestionText("   "));
    }

    @Test
    @DisplayName("cleanQuestionText：纯题目原样返回")
    void cleanQuestionTextPlain() {
        assertEquals("什么是Hive？", AiTextUtils.cleanQuestionText("什么是Hive？"));
    }
}
