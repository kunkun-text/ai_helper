package com.ai_helper.ai_helper.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 语音转写后处理：按外置词表修正高置信度的同音/断句错误。
 *
 * <p><b>为什么需要</b>：whisper-small 在中文答辩场景会把「文字答辩」听成「文字打变」、
 * 「部署」听成「剥锁」。这些不是模型幻觉，而是同音/近音错误，靠 prompt 治不好
 * （2026-10-20 实测：加长术语表和 --carry-initial-prompt 反而更差）。</p>
 *
 * <p><b>安全边界</b>：只做**字面替换**，不做拼音/编辑距离模糊匹配；词表由
 * {@code asr-corrections.txt} 提供，保守起见只收录高置信度短语。长词优先替换，
 * 避免「文字答辩」先被「打变」拆坏。</p>
 */
public final class AsrTextCorrector {

    private AsrTextCorrector() {
    }

    /**
     * 按词表替换（字面匹配，长词优先）。
     *
     * @param text  原始转写文本，可为 null
     * @param rules 错误写法 → 正确写法；可为 null/空
     * @return 替换后的文本；无规则或空文本时原样返回
     */
    public static String correct(String text, Map<String, String> rules) {
        if (text == null || text.isEmpty() || rules == null || rules.isEmpty()) {
            return text;
        }
        List<Map.Entry<String, String>> entries = new ArrayList<>(rules.entrySet());
        // 长词优先：先替换「文字打变」，再替换「打变」
        entries.sort((a, b) -> Integer.compare(b.getKey().length(), a.getKey().length()));

        String result = text;
        for (Map.Entry<String, String> entry : entries) {
            String from = entry.getKey();
            String to = entry.getValue();
            if (from == null || from.isEmpty() || to == null || from.equals(to)) {
                continue;
            }
            result = result.replace(from, to);
        }
        return result;
    }
}
