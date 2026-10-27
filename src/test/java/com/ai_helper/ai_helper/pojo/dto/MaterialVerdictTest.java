package com.ai_helper.ai_helper.pojo.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 【2026-10-25】材料裁定解析测试。
 *
 * <p>重点不是"某个词能不能被识别"，而是：**只有模型明确给出四类资格之一才拦截**，
 * 任何解析不出的情况都必须放行（UNKNOWN）。这条约束保证换模型、换措辞时
 * 不会把正常答辩报告误拦。</p>
 */
class MaterialVerdictTest {

    @Test
    @DisplayName("合格：资格=通过 → OK，不拦截，落库 ok")
    void pass() {
        MaterialVerdict v = MaterialVerdict.parse(
                "类型:答辩报告\n资格:通过\n理由:讲的是仓储出入库与库存预警");
        assertEquals(MaterialVerdict.Kind.OK, v.kind());
        assertFalse(v.isRejected());
        assertEquals("ok", v.code());
        assertEquals("答辩报告", v.materialType());
        assertEquals("讲的是仓储出入库与库存预警", v.reason());
    }

    @Test
    @DisplayName("跑题：是答辩材料但讲别的课题 → OFF_TOPIC，拦截")
    void offTopic() {
        MaterialVerdict v = MaterialVerdict.parse(
                "类型:课程论文\n资格:跑题\n理由:写的是校园二手交易平台");
        assertEquals(MaterialVerdict.Kind.OFF_TOPIC, v.kind());
        assertTrue(v.isRejected());
        assertEquals("suspect_offtopic", v.code());
        assertEquals("疑似跑题", v.label());
    }

    @Test
    @DisplayName("非答辩材料：简历/合同/说明书一律归此类，与具体文件类型无关")
    void nonMaterial() {
        MaterialVerdict resume = MaterialVerdict.parse(
                "类型:个人简历\n资格:非答辩材料\n理由:讲的是求职者本人");
        assertEquals(MaterialVerdict.Kind.NON_MATERIAL, resume.kind());
        assertEquals("suspect_nonmaterial", resume.code());

        // 换一种文件：同一个分类，不需要任何新词表
        MaterialVerdict contract = MaterialVerdict.parse(
                "类型:劳动合同\n资格:不是答辩材料\n理由:内容是甲乙双方用工约定");
        assertEquals(MaterialVerdict.Kind.NON_MATERIAL, contract.kind());
        assertEquals("劳动合同", contract.materialType());
    }

    @Test
    @DisplayName("内容不足：空白/只有封面目录 → INSUFFICIENT")
    void insufficient() {
        MaterialVerdict v = MaterialVerdict.parse(
                "类型:空白文档\n资格:内容不足\n理由:只有封面与目录，没有正文");
        assertEquals(MaterialVerdict.Kind.INSUFFICIENT, v.kind());
        assertTrue(v.isRejected());
        assertEquals("insufficient", v.code());
        assertEquals("内容不足", v.label());
    }

    @Test
    @DisplayName("方向安全：没有资格行 / 取值不认识 / 空输出 → UNKNOWN，必须放行")
    void unknownAlwaysPasses() {
        MaterialVerdict noLine = MaterialVerdict.parse("这份材料是一份答辩报告，质量一般。");
        assertEquals(MaterialVerdict.Kind.UNKNOWN, noLine.kind());
        assertFalse(noLine.isRejected());
        assertNull(noLine.code());

        assertEquals(MaterialVerdict.Kind.UNKNOWN,
                MaterialVerdict.parse("类型:报告\n资格:说不好\n理由:看不出来").kind());
        assertEquals(MaterialVerdict.Kind.UNKNOWN, MaterialVerdict.parse("").kind());
        assertEquals(MaterialVerdict.Kind.UNKNOWN, MaterialVerdict.parse(null).kind());
    }

    @Test
    @DisplayName("解析容忍：全角冒号、前置说明文字、资格值带修饰")
    void tolerantParsing() {
        MaterialVerdict v = MaterialVerdict.parse(
                "好的，我的判断如下：\n类型：课程实验报告\n资格：属于非答辩材料\n理由：内容是一门课的实验步骤");
        assertEquals(MaterialVerdict.Kind.NON_MATERIAL, v.kind());
        assertEquals("课程实验报告", v.materialType());

        assertEquals(MaterialVerdict.Kind.OK,
                MaterialVerdict.parse("类型:答辩报告\n资格:通过（主题一致）\n理由:同一系统").kind());
    }

    @Test
    @DisplayName("【2026-10-27 defenseId=377 原文】单行 + 斜杠分隔 + 无标签 → 必须判跑题")
    void singleLineWithSlash() {
        MaterialVerdict v = MaterialVerdict.parse(
                "答辩报告 / 内容不足 / 通篇内容与课题无关，讲的是山区基层医疗卫生保障调查，而非成绩单验证");
        // 模型写的是"内容不足"，但理由明确"与课题无关" → 升级为跑题（真正的失败原因是方向不符）
        assertEquals(MaterialVerdict.Kind.OFF_TOPIC, v.kind());
        assertTrue(v.isRejected());
        assertEquals("suspect_offtopic", v.code());
        assertEquals("答辩报告", v.materialType());
        assertTrue(v.reason().contains("山区"), "理由应保留原文，便于教师核对：" + v.reason());
    }

    @Test
    @DisplayName("单行但保留标签 → 仍按标签解析")
    void singleLineWithLabels() {
        MaterialVerdict v = MaterialVerdict.parse(
                "类型:答辩报告 / 资格:通过 / 理由:讲的就是本课题的仓储系统");
        assertEquals(MaterialVerdict.Kind.OK, v.kind());
        assertEquals("答辩报告", v.materialType());
        assertTrue(v.reason().contains("仓储"));
    }

    @Test
    @DisplayName("资格写成内容不足、理由却点明与课题无关 → 升级为跑题")
    void upgradeInsufficientToOffTopic() {
        MaterialVerdict v = MaterialVerdict.parse(
                "类型:课程作业\n资格:内容不足\n理由:通篇讲基层医疗调研，与本课题无关");
        assertEquals(MaterialVerdict.Kind.OFF_TOPIC, v.kind());
    }

    @Test
    @DisplayName("判不准：UNSURE 不出分，落库 unclear（前端映射「待复审」）")
    void unsureIsRejected() {
        MaterialVerdict v = MaterialVerdict.unsure("未知类型", "模型输出格式漂移");
        assertEquals(MaterialVerdict.Kind.UNSURE, v.kind());
        assertTrue(v.isRejected());
        assertEquals("unclear", v.code());
        assertEquals("无法判定", v.label());
    }
}
