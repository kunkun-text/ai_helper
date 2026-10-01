package com.ai_helper.ai_helper.util;

import com.ai_helper.ai_helper.constant.AiProtocolConstants;

/**
 * AI 回复文本的解析工具（纯函数，便于回归测试）。
 *
 * <p>从模型返回的三段式文本（{@code 点评:/评分:/下一题:}）里提取题目。
 * 目前有三处需要同一份解析：答辩主流程提取「上一轮 AI 提出的题」、
 * 重进答辩时回灌会话记忆、防作弊比对已问题目——统一收敛到本类，
 * 避免三处各写一份、改一处漏两处。</p>
 *
 * <p>协议标签统一取自 {@link AiProtocolConstants}。</p>
 */
public final class AiTextUtils {

    private AiTextUtils() {
    }

    /**
     * 从单条 AI 回复文本中提取题目。
     *
     * <p>依次尝试：① 三段式「下一题:」行（追问常是陈述句、不带问号，故不能只认问号行）；
     * ② 旧格式「【问题】」；③ 最后一个含问号的行。</p>
     *
     * @return 题目文本；提取不到返回 {@code null}
     */
    public static String extractQuestionFromAiText(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }

        // ① 当前三段式：取「下一题:」之后的整行
        for (String tag : AiProtocolConstants.NEXT_QUESTION_TAGS) {
            int idx = text.lastIndexOf(tag);
            if (idx >= 0) {
                String q = text.substring(idx + tag.length()).trim();
                int newline = q.indexOf('\n');
                if (newline >= 0) {
                    q = q.substring(0, newline).trim();
                }
                if (!q.isEmpty()) {
                    return q;
                }
            }
        }

        // ② 旧格式
        String legacyTag = AiProtocolConstants.LEGACY_QUESTION_TAG;
        if (text.contains(legacyTag)) {
            String q = text.substring(text.indexOf(legacyTag) + legacyTag.length()).trim();
            if (!q.isEmpty()) {
                return q;
            }
        }

        // ③ 兜底：最后一个含问号的行
        String[] lines = text.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (!line.isEmpty() && (line.contains("?") || line.contains("？"))) {
                return line;
            }
        }
        return null;
    }

    /**
     * 从「可能是整段 AI 回复、也可能只是题目」的文本里取纯题目。
     *
     * <p>用于历史数据：追问题入库时有的入库点存的是整段回复（含点评/评分行），
     * 有的存的是纯题目，读取时统一收敛成题目本身。</p>
     */
    public static String cleanQuestionText(String storedText) {
        if (storedText == null || storedText.trim().isEmpty()) {
            return null;
        }
        String extracted = extractQuestionFromAiText(storedText);
        if (extracted != null) {
            return extracted;
        }
        // 整段都不是题目（含点评等标签）→ 视为无效
        return storedText.contains(AiProtocolConstants.COMMENT_TAG)
                || storedText.contains(AiProtocolConstants.COMMENT_TAG_FULL)
                ? null : storedText.trim();
    }
}
