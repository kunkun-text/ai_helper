package com.ai_helper.ai_helper.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文档内图片抽取与报告模板的回归测试（2026-10-20）。
 *
 * <p>守三件事：① 图片占位必须落在正文中的**真实位置**（不能堆到文末）；
 * ② 关系表缺失时只降级计数、不抛异常；③ 下发的 docx 模板必须能被
 * {@link DocTextExtractor} 解析（模板类型 = 可分析类型，否则学生填完也白填）。</p>
 */
class ReportDocImageTest {

    private static final String W_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String A_NS = "http://schemas.openxmlformats.org/drawingml/2006/main";
    private static final String R_NS =
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships";

    @Test
    @DisplayName("docx 图片：占位落在正文真实位置，且图片字节可按 rId 取出")
    void extractWithImagesKeepsOrder() throws Exception {
        String document = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<w:document xmlns:w=\"" + W_NS + "\" xmlns:a=\"" + A_NS + "\" xmlns:r=\"" + R_NS + "\">"
                + "<w:body>"
                + "<w:p><w:r><w:t>第一段文字</w:t></w:r></w:p>"
                + "<w:p><w:r><w:drawing><a:blip r:embed=\"rId2\"/></w:drawing></w:r></w:p>"
                + "<w:p><w:r><w:t>第二段文字</w:t></w:r></w:p>"
                + "</w:body></w:document>";
        String rels = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\""
                + " Target=\"media/image1.png\"/>"
                + "</Relationships>";

        Map<String, byte[]> parts = new LinkedHashMap<>();
        parts.put("word/document.xml", document.getBytes(StandardCharsets.UTF_8));
        parts.put("word/_rels/document.xml.rels", rels.getBytes(StandardCharsets.UTF_8));
        parts.put("word/media/image1.png", samplePng());
        byte[] docx = zip(parts);

        DocTextExtractor.ExtractResult result = DocTextExtractor.extractWithImages(
                new ByteArrayInputStream(docx), "docx", new DocTextExtractor.Limits(), 12, 2L * 1024 * 1024);

        assertEquals(1, result.images().size());
        assertEquals("rId2", result.images().get(0).rid());
        assertEquals("png", result.images().get(0).extension());
        assertEquals(0, result.skippedImages());

        String text = result.text();
        int first = text.indexOf("第一段文字");
        int marker = text.indexOf(DocTextExtractor.IMAGE_MARKER_PREFIX + "rId2");
        int second = text.indexOf("第二段文字");
        assertTrue(first >= 0 && marker > first && second > marker,
                "图片占位必须夹在前后两段之间，实际文本：" + text);
    }

    @Test
    @DisplayName("普通 extract 不产生任何图片占位（旧行为不变）")
    void plainExtractHasNoMarker() throws Exception {
        String document = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<w:document xmlns:w=\"" + W_NS + "\" xmlns:a=\"" + A_NS + "\" xmlns:r=\"" + R_NS + "\">"
                + "<w:body><w:p><w:r><w:t>正文</w:t></w:r></w:p>"
                + "<w:p><w:r><w:drawing><a:blip r:embed=\"rId9\"/></w:drawing></w:r></w:p>"
                + "</w:body></w:document>";
        Map<String, byte[]> parts = new LinkedHashMap<>();
        parts.put("word/document.xml", document.getBytes(StandardCharsets.UTF_8));
        byte[] docx = zip(parts);

        String text = DocTextExtractor.extract(new ByteArrayInputStream(docx), "docx",
                new DocTextExtractor.Limits());
        assertFalse(text.contains(DocTextExtractor.IMAGE_MARKER_PREFIX));
        assertEquals("正文", text);
    }

    @Test
    @DisplayName("关系表缺失：只计入 skipped，不抛异常")
    void missingRelationshipIsSkipped() throws Exception {
        String document = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<w:document xmlns:w=\"" + W_NS + "\" xmlns:a=\"" + A_NS + "\" xmlns:r=\"" + R_NS + "\">"
                + "<w:body><w:p><w:r><w:t>正文</w:t></w:r></w:p>"
                + "<w:p><w:r><w:drawing><a:blip r:embed=\"rId7\"/></w:drawing></w:r></w:p>"
                + "</w:body></w:document>";
        Map<String, byte[]> parts = new LinkedHashMap<>();
        parts.put("word/document.xml", document.getBytes(StandardCharsets.UTF_8));
        byte[] docx = zip(parts);

        DocTextExtractor.ExtractResult result = DocTextExtractor.extractWithImages(
                new ByteArrayInputStream(docx), "docx", new DocTextExtractor.Limits(), 12, 1024 * 1024);
        assertEquals(0, result.images().size());
        assertEquals(1, result.skippedImages());
    }

    @Test
    @DisplayName("报告模板：生成的是可被解析的 docx，含封面、评分说明与六章骨架")
    void templateIsParseable() throws Exception {
        byte[] template = ReportTemplateBuilder.build();
        assertTrue(template.length > 1024, "模板体积异常：" + template.length);

        String text = DocTextExtractor.extract(new ByteArrayInputStream(template), "docx",
                new DocTextExtractor.Limits());
        // 封面
        assertTrue(text.contains("答辩报告书"));
        assertTrue(text.contains("报 告 成 绩"));
        assertTrue(text.contains("课题名称"));
        // 第二页
        assertTrue(text.contains("评分标准与写作要点"));
        assertTrue(text.contains("35 ~ 50"));
        // 须知与正文
        assertTrue(text.contains("填写须知"));
        assertTrue(text.contains("只接受 .docx"));
        assertTrue(text.contains("五、难点、风险与局限"));
        assertTrue(text.contains("六、总结与展望"));
        assertTrue(text.contains("参考文献"));
        assertTrue(text.contains("提交前自检清单"));
    }

    @Test
    @DisplayName("报告模板：封面学校头图必须真的打进 zip（否则 Word 显示红叉）")
    void templateEmbedsSchoolImages() throws Exception {
        byte[] template = ReportTemplateBuilder.build();
        Map<String, Integer> entries = new LinkedHashMap<>();
        try (java.util.zip.ZipInputStream zip = new java.util.zip.ZipInputStream(
                new ByteArrayInputStream(template), StandardCharsets.UTF_8)) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries.put(entry.getName(), (int) zip.readAllBytes().length);
            }
        }
        assertTrue(entries.containsKey("word/media/school-header.png"),
                "缺少封面头图：" + entries.keySet());
        assertTrue(entries.get("word/media/school-header.png") > 1024);
    }

    @Test
    @DisplayName("报告模板：zip 首条目必须是 [Content_Types].xml（WPS 等严格解析器的硬要求）")
    void contentTypesIsFirstEntry() throws Exception {
        byte[] template = ReportTemplateBuilder.build();
        try (java.util.zip.ZipInputStream zip = new java.util.zip.ZipInputStream(
                new ByteArrayInputStream(template), StandardCharsets.UTF_8)) {
            java.util.zip.ZipEntry first = zip.getNextEntry();
            assertTrue(first != null && "[Content_Types].xml".equals(first.getName()),
                    "首条目应为 [Content_Types].xml，实际：" + (first == null ? "空包" : first.getName()));
        }
    }

    @Test
    @DisplayName("报告模板：正文良构、段落不嵌套、图片引用都能在关系表里找到")
    void templateStructureIsSane() throws Exception {
        byte[] template = ReportTemplateBuilder.build();
        Map<String, byte[]> parts = new LinkedHashMap<>();
        try (java.util.zip.ZipInputStream zip = new java.util.zip.ZipInputStream(
                new ByteArrayInputStream(template), StandardCharsets.UTF_8)) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                parts.put(entry.getName(), zip.readAllBytes());
            }
        }

        javax.xml.parsers.DocumentBuilderFactory factory =
                javax.xml.parsers.DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        org.w3c.dom.Document document = factory.newDocumentBuilder()
                .parse(new ByteArrayInputStream(parts.get("word/document.xml")));

        // w:p 里再嵌 w:p：Word 直接弹「在试图打开文件时遇到错误」
        org.w3c.dom.NodeList paragraphs = document.getElementsByTagNameNS(W_NS, "p");
        for (int i = 0; i < paragraphs.getLength(); i++) {
            assertNull(nearestAncestor(paragraphs.item(i), W_NS, "p"),
                    "存在嵌套段落，Word 会报文档损坏");
        }

        // 每个图片引用都必须在关系表里有对应 Id，否则 Word 显示红叉
        String rels = new String(parts.get("word/_rels/document.xml.rels"), StandardCharsets.UTF_8);
        org.w3c.dom.NodeList blips = document.getElementsByTagNameNS(A_NS, "blip");
        assertTrue(blips.getLength() >= 1, "封面头图应当存在");
        for (int i = 0; i < blips.getLength(); i++) {
            org.w3c.dom.Node embed = blips.item(i).getAttributes().getNamedItemNS(R_NS, "embed");
            assertTrue(embed != null, "a:blip 缺少 r:embed");
            assertTrue(rels.contains("Id=\"" + embed.getNodeValue() + "\""),
                    "图片引用缺少关系：" + embed.getNodeValue());
        }
    }

    @Test
    @DisplayName("OCR 引擎缺失：返回 null 而不抛异常（图片内容缺席，报告照常评）")
    void ocrMissingEngineDegrades() {
        DocImageOcr ocr = new DocImageOcr("Z:\\不存在\\tesseract.exe", 5);
        assertFalse(ocr.available());
        assertNull(ocr.recognize(new byte[]{1, 2, 3}));
    }

    // ------------------------------------------------------------------

    /** 向上找最近的同名祖先元素（找嵌套用） */
    private static org.w3c.dom.Node nearestAncestor(org.w3c.dom.Node node, String ns, String localName) {
        org.w3c.dom.Node parent = node.getParentNode();
        while (parent != null) {
            if (ns.equals(parent.getNamespaceURI()) && localName.equals(parent.getLocalName())) {
                return parent;
            }
            parent = parent.getParentNode();
        }
        return null;
    }

    private static byte[] samplePng() throws IOException {
        BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bos);
        return bos.toByteArray();
    }

    private static byte[] zip(Map<String, byte[]> parts) throws IOException {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(bos, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, byte[]> part : parts.entrySet()) {
                zip.putNextEntry(new ZipEntry(part.getKey()));
                zip.write(part.getValue());
                zip.closeEntry();
            }
            zip.finish();
            return bos.toByteArray();
        }
    }
}
