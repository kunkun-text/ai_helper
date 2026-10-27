package com.ai_helper.ai_helper.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 【2026-10-27】报告模板的「课题与预设题」章节测试。
 *
 * <p>方向判定要求报告逐题说明预设题，所以模板必须把题印进去——
 * 这里钉住两件事：① 带课题时题目原文出现在 document.xml；② 不带课题时不报错、留空白位。</p>
 */
class ReportTemplateBuilderTest {

    @Test
    @DisplayName("带课题：预设题原文直接印进模板")
    void buildWithTopicQuestions() {
        byte[] docx = ReportTemplateBuilder.build(
                "成绩单验证课题-1016", "验证等级与逐项依据",
                Arrays.asList("本课题如何实现成绩单的总分计算？", "系统如何处理材料与课题不匹配？"));
        String xml = documentXml(docx);
        assertTrue(xml.contains("成绩单验证课题-1016"), "课题名应被写入模板");
        assertTrue(xml.contains("本课题如何实现成绩单的总分计算？"), "预设题 1 应写入模板");
        assertTrue(xml.contains("系统如何处理材料与课题不匹配？"), "预设题 2 应写入模板");
    }

    @Test
    @DisplayName("不带课题：生成空白填写位，不抛异常")
    void buildWithoutTopic() {
        byte[] docx = ReportTemplateBuilder.build(null, null, Collections.emptyList());
        assertTrue(docx.length > 1000, "模板字节应非空");
        String xml = documentXml(docx);
        assertTrue(xml.contains("课题与预设题"), "章节标题始终存在");
        assertFalse(xml.contains("本课题如何实现成绩单的总分计算？"), "空白模板不应包含任何具体题目");
    }

    /** 从 docx（zip）里取出 word/document.xml 文本 */
    private static String documentXml(byte[] docx) {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(docx), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if ("word/document.xml".equals(entry.getName())) {
                    return new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        throw new IllegalStateException("word/document.xml 不存在");
    }
}
