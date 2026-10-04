package com.ai_helper.ai_helper.constant;

/**
 * AI 三段式协议与判分标记常量。
 *
 * <p>收敛原先散落在 {@code chatController} / {@code AiTextUtils} /
 * {@code ScorePersistenceServiceImpl} 中重复书写的协议标签字面量，避免
 * 「改一处漏两处」——历史上曾因把追问标签写成"追问:"导致前端解析不到题、
 * 变成「有分无题」。</p>
 *
 * <p>注意：prompt 里给模型看的自然语言示例文本不在此列，保持原文不动，
 * 本类只用于「代码解析 / 拼接」场景。</p>
 */
public final class AiProtocolConstants {

    private AiProtocolConstants() {
    }

    /** 点评行标签（半角冒号） */
    public static final String COMMENT_TAG = "点评:";

    /** 点评行标签（全角冒号，兼容模型偶尔输出） */
    public static final String COMMENT_TAG_FULL = "点评：";

    /** 评分行标签（半角冒号） */
    public static final String SCORE_TAG = "评分:";

    /** 下一题行标签（半角冒号） */
    public static final String NEXT_QUESTION_TAG = "下一题:";

    /** 下一题行标签（全角冒号） */
    public static final String NEXT_QUESTION_TAG_FULL = "下一题：";

    /** 总结行标签（半角冒号） */
    public static final String SUMMARY_TAG = "总结:";

    /** 总结行标签（全角冒号，兼容模型偶尔输出） */
    public static final String SUMMARY_TAG_FULL = "总结：";

    /** 题目标签（全角 / 半角冒号都要认） */
    public static final String[] NEXT_QUESTION_TAGS = {NEXT_QUESTION_TAG, NEXT_QUESTION_TAG_FULL};

    /** 旧格式题目标签（历史数据兼容） */
    public static final String LEGACY_QUESTION_TAG = "【问题】";

    /** 三档判分标记：切题 */
    public static final String MARK_ON_TOPIC = "[切题]";

    /** 三档判分标记：明显回答错误 */
    public static final String MARK_WRONG = "[错误]";

    /** 三档判分标记：跑题 */
    public static final String MARK_OFF_TOPIC = "[跑题]";
}
