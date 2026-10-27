package com.ai_helper.ai_helper.pojo.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 【2026-10-27】预设题覆盖判定的解析与阈值测试。
 *
 * <p>这条判定的核心口径是「**0 命中才拦**」：命中 1 道即视为本课题材料，
 * 写得差交给五维评分；漏行（模型没判全）不算命中也不算结论，必须走云端/待复审。
 * 这里把三个分支都钉死，避免以后调 prompt 时把阈值改坏。</p>
 */
class MaterialCoverageTest {

    private static final List<String> QUESTIONS = Arrays.asList(
            "系统如何解决库存超卖问题",
            "如何保证并发下的数据一致性",
            "系统的技术选型与理由");

    @Test
    @DisplayName("命中 1 道 → 不拦截（质量交给五维），命中数/总数正确")
    void oneHitPasses() {
        MaterialCoverage c = MaterialCoverage.parse(
                "1:是|第2章写了超卖与锁\n2:否|未涉及并发\n3:否|只列了框架名", QUESTIONS);
        assertTrue(c.isApplicable());
        assertEquals(3, c.getTotal());
        assertEquals(1, c.getHitCount());
        assertFalse(c.isRejected());
        assertFalse(c.hasUnparsed());
    }

    @Test
    @DisplayName("0 命中且全部判出 → 拦截（这是 377 那类无关材料的处置）")
    void zeroHitRejects() {
        MaterialCoverage c = MaterialCoverage.parse(
                "1:否|调研的是基层医疗\n2:否|没有并发内容\n3:否|与仓储无关", QUESTIONS);
        assertEquals(0, c.getHitCount());
        assertTrue(c.isRejected());
        assertTrue(c.summary().contains("0/3"));
        assertTrue(c.summary().contains("未提及"));
    }

    @Test
    @DisplayName("漏行 → 不算拦截也不能当结论：hasUnparsed=true")
    void missingLineIsUnknown() {
        MaterialCoverage c = MaterialCoverage.parse("1:否|没有相关内容", QUESTIONS);
        assertEquals(3, c.getTotal());
        assertEquals(0, c.getHitCount());
        assertTrue(c.hasUnparsed());
        assertFalse(c.isRejected(), "漏行时不能直接判无关，要云端复核/待复审");
    }

    @Test
    @DisplayName("解析容错：题1 / 全角冒号 / 顿号 / 括号都能认")
    void tolerantFormats() {
        MaterialCoverage c = MaterialCoverage.parse(
                "题1：是（见 2.3 节）\n"
                        + "Q2、否、未涉及\n"
                        + "3. 是 —— 3.1 有选型表", QUESTIONS);
        assertEquals(2, c.getHitCount());
        assertFalse(c.hasUnparsed());
    }

    @Test
    @DisplayName("否定优先：未提及/没有/未覆盖 不能被读成肯定")
    void negativeFirst() {
        MaterialCoverage c = MaterialCoverage.parse(
                "1:未提及超卖\n2:没有并发相关描述\n3:未覆盖选型", QUESTIONS);
        assertEquals(0, c.getHitCount());
        assertTrue(c.isRejected());
    }

    @Test
    @DisplayName("课题没有预设题 → 本判定不适用（回落到资格裁定）")
    void notApplicableWithoutQuestions() {
        MaterialCoverage c = MaterialCoverage.parse("1:是|随便", Collections.emptyList());
        assertFalse(c.isApplicable());
        assertFalse(c.isRejected());
        assertEquals(0, c.getTotal());
    }

    @Test
    @DisplayName("判不准：unknown 不拦截但标记为待复审")
    void unknownNeedsReview() {
        MaterialCoverage c = MaterialCoverage.unknown("无法解析", QUESTIONS);
        assertTrue(c.isApplicable());
        assertTrue(c.isUnknown());
        assertFalse(c.isRejected());
        assertEquals(3, c.getTotal());
    }

    @Test
    @DisplayName("课题没有预设题（必填口径）→ 判不准，明细写清「未配置预设题」")
    void unknownWhenTopicHasNoQuestions() {
        MaterialCoverage c = MaterialCoverage.unknown("课题未配置预设题", Collections.emptyList());
        assertTrue(c.isUnknown());
        assertFalse(c.isRejected());
        assertEquals(0, c.getTotal());
        assertTrue(c.summary().contains("未配置预设题"), "教师要看懂为什么不出分：" + c.summary());
    }
}
