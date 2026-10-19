package com.ai_helper.ai_helper.Controller;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 【2026-10-19 · 方案A】错误标注一致性守卫的纯函数回归。
 *
 * <p>背景：3B 会给「[切题] + 正面点评」自行附加「（有明显回答错误）」，
 * 旧 N64 只在总分 ≥32 时撤标，导致 defenseId=359 第 2/4 轮（24/30 分）出现
 * 「正面点评 + 错误后缀」的矛盾。新规则：正文无错误措辞且未触发任何错误确认/扣分时，
 * 不论分数都撤标；真错误/已确认扣分则保留。</p>
 */
class ChatControllerGuardTest {

    private static final String NOTE = "（有明显回答错误）";

    @Test
    void positiveCommentWithAutoSuffixBelowThresholdIsStripped() {
        // 实测 359 第 4 轮：点评正面，30 分，模型自行附加后缀
        assertTrue(chatController.shouldStripWrongAnswerNote(
                "性能差异表述准确，小文件问题成因分析到位。" + NOTE,
                30.0, false, false, 32.0));
        // 实测 359 第 2 轮：点评正面，24 分
        assertTrue(chatController.shouldStripWrongAnswerNote(
                "思路清晰，逻辑严谨，建议在 Map 阶段增加对异常值的处理。" + NOTE,
                24.0, false, false, 32.0));
    }

    @Test
    void realErrorCueKeepsSuffix() {
        // 已确认错误 + 正文有具体错误措辞：保留（半分档 12.5）
        assertFalse(chatController.shouldStripWrongAnswerNote(
                "把 STL 分解说反了，应为趋势项、季节项和残差项。" + NOTE,
                12.5, true, true, 32.0));
        // 未走错误分支，但正文明确写了"说反了"：保留
        assertFalse(chatController.shouldStripWrongAnswerNote(
                "把数据量小和大说反了。" + NOTE,
                15.0, false, true, 32.0));
    }

    @Test
    void verifiedErrorKeepsSuffixEvenWithoutCue() {
        // 复核/清单已确认错误、但正文只写了笼统结论：保留，避免"撤标但分被压低"的二次矛盾
        assertFalse(chatController.shouldStripWrongAnswerNote(
                "回答针对本题，但存在明显错误。" + NOTE,
                20.0, true, false, 32.0));
    }

    @Test
    void normalScoreStillStrips() {
        // 原 N64 口径：总分已达正常档（≥32）→ 撤标
        assertTrue(chatController.shouldStripWrongAnswerNote(
                "回答正确。" + NOTE,
                35.0, true, false, 32.0));
    }

    @Test
    void noSuffixNeverStrips() {
        assertFalse(chatController.shouldStripWrongAnswerNote("回答正确。", 38.0, false, false, 32.0));
        assertFalse(chatController.shouldStripWrongAnswerNote(null, 0.0, false, false, 32.0));
    }

    // ==================== N79 · 追问串题闸门（2026-10-19） ====================

    @Test
    void followUpRejectsTopicMetaQuestions() {
        // 实测 362 第 10 轮：模型反过来向学生索要课题背景
        assertFalse(chatController.isAcceptableFollowUp("请提供课题名称、核心方案的技术栈与架构信息"));
        // 实测 362 第 7~9 轮：空指代，放在任何课题下都成立
        assertFalse(chatController.isAcceptableFollowUp("请说明该课题核心方案的关键实现细节？"));
        assertFalse(chatController.isAcceptableFollowUp("请分析该课题核心方案的性能瓶颈？"));
    }

    @Test
    void followUpAcceptsTopicSpecificQuestions() {
        assertTrue(chatController.isAcceptableFollowUp("如何具体实现CombineTextInputFormat合并小文件？"));
        assertTrue(chatController.isAcceptableFollowUp("HDFS 中 NameNode 与 DataNode 如何协作？"));
    }

    @Test
    void followUpRejectsEmptyAndTooShort() {
        assertFalse(chatController.isAcceptableFollowUp(null));
        assertFalse(chatController.isAcceptableFollowUp("   "));
        assertFalse(chatController.isAcceptableFollowUp("继续？"));
    }

    // ==================== 逻辑隔离 · 会话/课题一致性（2026-10-19） ====================

    @Test
    void sessionTopicMustMatchRequest() {
        assertNull(chatController.sessionIdTopicMismatch("user_12408060102_topic_28", "12408060102", 28));
        // 会话属于课题 27，却拿它来答课题 28 → 必须拦
        assertNotNull(chatController.sessionIdTopicMismatch("user_12408060102_topic_27", "12408060102", 28));
        // 会话属于别人 → 也拦
        assertNotNull(chatController.sessionIdTopicMismatch("user_20230001_topic_28", "12408060102", 28));
    }

    @Test
    void sessionTopicCheckSkipsUnknownFormat() {
        // 自定义会话名 / 缺 topicId：不拦截，交给后续逻辑
        assertNull(chatController.sessionIdTopicMismatch("custom_session", "12408060102", 28));
        assertNull(chatController.sessionIdTopicMismatch("user_12408060102_topic_28", "12408060102", null));
        assertNull(chatController.sessionIdTopicMismatch(null, "12408060102", 28));
    }
}
