package com.ai_helper.ai_helper.Service.Impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 评分解析回归测试（N14 · 2026-10-08）。
 *
 * <p>只测**纯解析**（{@link ScorePersistenceServiceImpl#parseScoresFromResponse}），
 * 不启动 Spring 容器、不连数据库：方法与已注入的 Mapper 无关，直接 new 即可。</p>
 *
 * <p>重点守住两条历史修复：① 总分必须是**五维之和**（N6，不能用模型自报的总分，
 * 否则「气泡显示的分 ≠ 库里五维和」）；② 单维必须夹在 0~10（模型会给 15 或负数）。</p>
 */
class ScorePersistenceServiceImplTest {

    private final ScorePersistenceServiceImpl service = new ScorePersistenceServiceImpl();

    private double num(Map<String, Object> map, String key) {
        Object v = map.get(key);
        assertTrue(v instanceof Number, "字段 " + key + " 应为数值，实际: " + v);
        return ((Number) v).doubleValue();
    }

    @Test
    @DisplayName("三段式管道格式：总分强制等于五维之和（无视模型自报的 48）")
    void parseSegmentPipeFormat() {
        String ai = "点评:回答思路清晰\n"
                + "评分:48/50|7|7|7|7|7.5|优点|建议|\n"
                + "下一题:请说明关键实现细节？";

        Map<String, Object> r = service.parseScoresFromResponse(ai);

        assertEquals(7.0, num(r, "expression"), 0.0001);
        assertEquals(7.0, num(r, "logic"), 0.0001);
        assertEquals(7.0, num(r, "professional"), 0.0001);
        assertEquals(7.0, num(r, "adaptability"), 0.0001);
        assertEquals(7.5, num(r, "innovation"), 0.0001);
        // N6 回归点：模型自报 48，但落库总分必须是 7+7+7+7+7.5 = 35.5
        assertEquals(35.5, num(r, "totalScore"), 0.0001);
        assertEquals("回答思路清晰", r.get("comment"));
    }

    @Test
    @DisplayName("单维越界被夹到 0~10，总分随之重算")
    void clampOutOfRangeDimensions() {
        String ai = "评分:80/50|15|-3|7|7|7|";

        Map<String, Object> r = service.parseScoresFromResponse(ai);

        assertEquals(10.0, num(r, "expression"), 0.0001);
        assertEquals(0.0, num(r, "logic"), 0.0001);
        assertEquals(7.0, num(r, "professional"), 0.0001);
        assertEquals(7.0, num(r, "adaptability"), 0.0001);
        assertEquals(7.0, num(r, "innovation"), 0.0001);
        assertEquals(31.0, num(r, "totalScore"), 0.0001);
    }

    @Test
    @DisplayName("旧格式【表达】等标记：五维可解析，总分同为五维之和")
    void parseLegacyFormat() {
        String ai = "【评价】概念阐述准确\n【表达】8\n【逻辑】7\n【专业】6\n【应变】5\n【创新】4";

        Map<String, Object> r = service.parseScoresFromResponse(ai);

        assertEquals(8.0, num(r, "expression"), 0.0001);
        assertEquals(7.0, num(r, "logic"), 0.0001);
        assertEquals(6.0, num(r, "professional"), 0.0001);
        assertEquals(5.0, num(r, "adaptability"), 0.0001);
        assertEquals(4.0, num(r, "innovation"), 0.0001);
        assertEquals(30.0, num(r, "totalScore"), 0.0001);
        assertEquals("概念阐述准确", r.get("comment"));
    }

    @Test
    @DisplayName("空输入返回空 Map，不抛异常")
    void emptyInput() {
        assertTrue(service.parseScoresFromResponse(null).isEmpty());
        assertTrue(service.parseScoresFromResponse("").isEmpty());
    }
}
