package com.ai_helper.ai_helper.Controller;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 【2026-11-17】要点核验「命中率分母对齐 + 失败可见」的纯函数回归。
 *
 * <p>背景：旧实现在 {@code verifyChecklist} 里用「模型实际返回的 verdict 条数」作分母。
 * v2 微调模型丢失 JSON 指令遵循，给 4 条要点只回 1 条 {@code {"verdict":"命中"}}，
 * 命中率恒为 1/1 = 100%，闸门实质失效（defenseId=407：全错但切题 44/50）。
 * 现改为分母 = 清单条数；模型少回按未命中计；返回条数不足清单一半时判「核验不可用」（-1）。</p>
 */
class ChecklistHitRateTest {

    @Test
    void denominatorIsRubricSizeNotReturnedCount() {
        // 清单 4 条，模型只回 3 条（1 命中 + 2 未命中）→ 1/4 = 0.25，而不是 1/3
        assertEquals(0.25, chatController.computeChecklistHitRate(1, 3, 4), 1e-9);
        // 清单 4 条，模型只回 2 条（1 命中）→ 仍按 4 条算 = 0.25（旧口径会算成 1/2 = 0.5）
        assertEquals(0.25, chatController.computeChecklistHitRate(1, 2, 4), 1e-9);
        // 全部命中 → 1.0
        assertEquals(1.0, chatController.computeChecklistHitRate(4, 4, 4), 1e-9);
    }

    @Test
    void singleVerdictAgainstFourItemRubricIsUnavailable() {
        // 健康检查：返回 1 条 < 清单 4 条的一半 → 不可用（-1），不允许拿 1 条判定全中
        assertEquals(-1.0, chatController.computeChecklistHitRate(1, 1, 4), 1e-9);
        // 恰好一半（2 条 / 4 条）不算不足，分母仍按清单 4 条 → 1/4
        assertEquals(0.25, chatController.computeChecklistHitRate(1, 2, 4), 1e-9);
    }

    @Test
    void missingItemsCountAsMisses() {
        // 清单 5 条，模型回 3 条且全命中 → 3/5 = 0.6
        assertEquals(0.6, chatController.computeChecklistHitRate(3, 3, 5), 1e-9);
        // 模型多回条数时命中数不得超过分母
        assertEquals(1.0, chatController.computeChecklistHitRate(5, 5, 4), 1e-9);
    }

    @Test
    void nonJsonRubricFallsBackToModelDenominator() {
        // 历史纯文本清单：rubricSize = -1 → 回退模型返回条数口径
        assertEquals(0.5, chatController.computeChecklistHitRate(1, 2, -1), 1e-9);
        assertEquals(-1.0, chatController.computeChecklistHitRate(0, 0, -1), 1e-9);
    }

    @Test
    void parseRubricSizeReadsJsonArray() {
        assertEquals(4, chatController.parseRubricSize("[\"要点1\",\"要点2\",\"要点3\",\"要点4\"]"));
        // 容忍 markdown 代码块与前后缀文字
        assertEquals(3, chatController.parseRubricSize("```json\n[\"A\",\"B\",\"C\"]\n```"));
        assertEquals(2, chatController.parseRubricSize("输出如下：[ \"A\", \"B\" ]"));
        // 模型偶发输出多个相邻数组（实测 defenseId=419 追问轮）：必须按 3 条算，而不是首段 1 条
        assertEquals(3, chatController.parseRubricSize("[\"A\"] [\"B\"] [\"C\"]"));
    }

    @Test
    void parseRubricSizeRejectsNonJson() {
        assertEquals(-1, chatController.parseRubricSize(null));
        assertEquals(-1, chatController.parseRubricSize(""));
        assertEquals(-1, chatController.parseRubricSize("要点1\n要点2\n要点3"));
        assertEquals(-1, chatController.parseRubricSize("{\"items\":[]}"));
    }
}
