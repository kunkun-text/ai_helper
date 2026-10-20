package com.ai_helper.ai_helper.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 生成「答辩报告模板」.docx（2026-10-20）。
 *
 * <p><b>为什么是 docx</b>：模板类型必须等于系统唯一能分析的类型（学生上传只收 docx）；
 * 若模板给 PDF/旧 doc，学生填完还得转换，等于自相矛盾。</p>
 *
 * <p><b>为什么动态生成而不是仓库放二进制</b>：模板内容会随评分口径调整（字数门槛、
 * 章节要求、须知条目），放在代码里可以随版本 review 和演进。</p>
 *
 * <p><b>封面</b>：参照学校实验报告封面版式——校徽 + 校名书法字（随包图片）
 * → 报告标题 → 成绩框 → 课题名称/班级/姓名/学号/指导教师/日期（下划线填写）。
 * 图片放在 {@code resources/templates/}，以 OOXML {@code word/media} 内嵌，
 * 不依赖学生电脑上的任何字体或外部文件。</p>
 *
 * <p>只写最小 OOXML 包（内容类型 + 关系 + 正文 + 样式 + 一张封面头图），
 * 不引入任何第三方文档库，保持 {@code mvn -o} 离线编译可用。</p>
 */
public final class ReportTemplateBuilder {

    private static final String DECLARATION = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>";

    private static final String W_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String R_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String WP_NS = "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing";
    private static final String A_NS = "http://schemas.openxmlformats.org/drawingml/2006/main";
    private static final String PIC_NS = "http://schemas.openxmlformats.org/drawingml/2006/picture";

    /**
     * 封面头部图片（校徽 + 校名的一整张干净图）。
     *
     * <p>为什么用整图而不是从参照 docx 里拆两张：参照文档的校名图右侧**自带题字落款小字**，
     * 拆出来会多出几个字（2026-10-20 用户实测反馈）。整图与学校官方版式一致，
     * 也避免中间多一个空格字符。</p>
     */
    private static final String HEADER_RESOURCE = "/templates/school-header.png";

    private static final String HEADER_PART = "word/media/school-header.png";

    /** 封面头图显示尺寸（EMU，1 英寸 = 914400）；645×207 等比放大到页宽约 4.36 英寸 */
    private static final long HEADER_CX = 3990975L;
    private static final long HEADER_CY = 1280824L;

    private ReportTemplateBuilder() {
    }

    /** 生成模板文件字节（可直接作为下载流返回） */
    public static byte[] build() {
        byte[] header = readResource(HEADER_RESOURCE);
        boolean withImages = header != null;

        Map<String, byte[]> parts = new LinkedHashMap<>();
        parts.put("[Content_Types].xml", bytes(contentTypes(withImages)));
        parts.put("_rels/.rels", bytes(rootRelationships()));
        parts.put("word/_rels/document.xml.rels", bytes(documentRelationships(withImages)));
        parts.put("word/styles.xml", bytes(styles()));
        parts.put("word/document.xml", bytes(document(withImages)));
        if (withImages) {
            parts.put(HEADER_PART, header);
        }

        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(bos, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, byte[]> part : parts.entrySet()) {
                zip.putNextEntry(new ZipEntry(part.getKey()));
                zip.write(part.getValue());
                zip.closeEntry();
            }
            zip.finish();
            return bos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("报告模板生成失败", e);
        }
    }

    // ==================================================================
    // 正文内容
    // ==================================================================

    private static String document(boolean withImages) {
        StringBuilder b = new StringBuilder();
        b.append(DECLARATION);
        b.append("<w:document xmlns:w=\"").append(W_NS)
                .append("\" xmlns:r=\"").append(R_NS)
                .append("\" xmlns:wp=\"").append(WP_NS)
                .append("\" xmlns:a=\"").append(A_NS)
                .append("\" xmlns:pic=\"").append(PIC_NS)
                .append("\"><w:body>");

        // ---------- 第 1 页：封面 ----------
        b.append(cover(withImages));
        b.append(pageBreak());

        // ---------- 第 2 页：评分标准与写作要点 ----------
        b.append(scoringGuide());
        b.append(pageBreak());

        // ---------- 第 3 页：填写须知 ----------
        b.append(notice());
        b.append(pageBreak());

        // ---------- 正文骨架 ----------
        b.append(p("Heading1", "一、选题背景与目标"));
        b.append(p(null, "【写作提示】说明课题来源、要解决的实际问题、目标与范围。建议 400~600 字。"));
        b.append(p(null, "1.1 课题背景"));
        b.append(p(null, "【在此填写】"));
        b.append(p(null, "1.2 研究目标与范围"));
        b.append(p(null, "【在此填写。写清楚「做什么」「不做什么」，避免后续被追问范围不清】"));

        b.append(p("Heading1", "二、需求分析与总体方案"));
        b.append(p(null, "【写作提示】先写要满足的需求，再写总体方案。架构图可以放，但必须配文字说明。"));
        b.append(p(null, "2.1 功能需求"));
        b.append(p(null, "【在此填写，建议用编号列表】"));
        b.append(p(null, "2.2 非功能需求"));
        b.append(p(null, "【在此填写：性能、并发、安全、部署环境等】"));
        b.append(p(null, "2.3 总体架构（架构图需配文字说明）"));
        b.append(p(null, "【在此填写：系统由哪些部分组成，各部分如何交互。图上有的关键要素请用文字复述】"));

        b.append(p("Heading1", "三、技术方案与实现"));
        b.append(p(null, "【写作提示】这是评分的主要依据，建议 800~1200 字。技术选型要写「为什么选它」。"));
        b.append(p(null, "3.1 技术选型与理由"));
        b.append(p(null, "【在此填写：用了什么框架/数据库/模型，各自解决了什么问题，替代方案为何没选】"));
        b.append(p(null, "3.2 关键模块与流程"));
        b.append(p(null, "【在此填写：核心模块职责、调用流程、关键数据结构】"));
        b.append(p(null, "3.3 关键实现细节"));
        b.append(p(null, "【在此填写：难点代码思路、并发/事务处理、异常与降级策略。不要大段贴代码，写思路】"));

        b.append(p("Heading1", "四、数据与实验"));
        b.append(p(null, "【写作提示】必须给出可核对的数据，结论要写成句子，不能只贴表。"));
        b.append(p(null, "4.1 数据集或测试环境"));
        b.append(p(null, "【在此填写：数据规模、来源、硬件环境】"));
        b.append(p(null, "4.2 测试方法与指标"));
        b.append(p(null, "【在此填写：怎么测的、看哪些指标】"));
        b.append(p(null, "4.3 结果与分析"));
        b.append(p(null, "【在此填写：具体数值 + 结论。例如「平均响应时间 320ms，较基线下降 42%」】"));

        b.append(p("Heading1", "五、难点、风险与局限"));
        b.append(p(null, "【写作提示】这一章最能体现真实工作量，缺失会被直接压分。"));
        b.append(p(null, "5.1 遇到的主要难点及解决过程"));
        b.append(p(null, "【在此填写：问题现象、排查过程、最终方案】"));
        b.append(p(null, "5.2 方案权衡"));
        b.append(p(null, "【在此填写：在哪些方案之间做了取舍，为什么】"));
        b.append(p(null, "5.3 现有局限与未解决问题"));
        b.append(p(null, "【在此填写：诚实写清楚，不要回避。例如模型精度上限、硬件限制、未覆盖的场景】"));

        b.append(p("Heading1", "六、总结与展望"));
        b.append(p(null, "6.1 完成情况"));
        b.append(p(null, "【在此填写：对照第一章目标逐条说明完成度】"));
        b.append(p(null, "6.2 工作量说明"));
        b.append(p(null, "【在此填写：投入时间、代码规模、参与的模块】"));
        b.append(p(null, "6.3 后续改进方向"));
        b.append(p(null, "【在此填写】"));

        b.append(p("Heading1", "参考文献"));
        b.append(p(null, "【按学校格式列出，可省略】"));

        b.append(pageBreak());

        // ---------- 自检 ----------
        b.append(p("Heading1", "提交前自检清单"));
        b.append(p(null, "□ 文件是 .docx 格式，且不超过 50MB"));
        b.append(p(null, "□ 正文不少于 3000 字（Word / WPS 左下角可查看字数）"));
        b.append(p(null, "□ 六个章节齐全，没有留下「【在此填写】」占位文字"));
        b.append(p(null, "□ 图片里的关键信息已经用文字复述"));
        b.append(p(null, "□ 表格里的结论已经写成完整句子"));
        b.append(p(null, "□ 没有写与内容无关的指令性文字"));

        b.append("<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/>"
                + "<w:pgMar w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\""
                + " w:header=\"851\" w:footer=\"992\" w:gutter=\"0\"/></w:sectPr>");
        b.append("</w:body></w:document>");
        return b.toString();
    }

    /** 封面：校徽 + 校名 → 标题 → 成绩框 → 信息填写栏 */
    private static String cover(boolean withImages) {
        StringBuilder b = new StringBuilder();

        // 校徽与校名同一行居中（整图内已排好版，中间不再插空格字符）
        if (withImages) {
            b.append("<w:p><w:pPr><w:jc w:val=\"center\"/><w:spacing w:before=\"600\" w:after=\"200\"/></w:pPr>");
            b.append(imageRun("rId10", 101, "school-header", HEADER_CX, HEADER_CY));
            b.append("</w:p>");
        } else {
            // 图片资源缺失时的降级：段落必须独立闭合，`w:p` 不能嵌套（否则 Word 判定文档损坏）
            b.append("<w:p><w:pPr><w:jc w:val=\"center\"/><w:spacing w:before=\"600\" w:after=\"200\"/></w:pPr></w:p>");
            b.append(p("Title", "重庆理工大学"));
        }

        // 报告标题
        b.append("<w:p><w:pPr><w:jc w:val=\"center\"/><w:spacing w:before=\"900\" w:after=\"400\"/></w:pPr>"
                + "<w:r><w:rPr><w:rFonts w:ascii=\"黑体\" w:eastAsia=\"黑体\" w:hAnsi=\"黑体\"/>"
                + "<w:b/><w:sz w:val=\"72\"/><w:szCs w:val=\"72\"/></w:rPr>"
                + "<w:t>答辩报告书</w:t></w:r></w:p>");

        // 成绩框（对齐原版「实验项目成绩」的两行单列表格）
        b.append("<w:p><w:pPr><w:jc w:val=\"center\"/><w:spacing w:before=\"600\"/></w:pPr></w:p>");
        b.append("<w:tbl><w:tblPr><w:tblW w:w=\"2600\" w:type=\"dxa\"/><w:jc w:val=\"center\"/>"
                + "<w:tblBorders>"
                + border("top") + border("left") + border("bottom") + border("right")
                + "<w:insideH w:val=\"single\" w:sz=\"8\" w:space=\"0\" w:color=\"000000\"/>"
                + "<w:insideV w:val=\"none\" w:sz=\"0\" w:space=\"0\" w:color=\"auto\"/>"
                + "</w:tblBorders></w:tblPr>"
                + "<w:tblGrid><w:gridCol w:w=\"2600\"/></w:tblGrid>"
                + "<w:tr><w:tc><w:tcPr><w:tcW w:w=\"2600\" w:type=\"dxa\"/></w:tcPr>"
                + "<w:p><w:pPr><w:jc w:val=\"center\"/><w:ind w:firstLine=\"0\"/></w:pPr>"
                + "<w:r><w:t>报 告 成 绩</w:t></w:r></w:p></w:tc></w:tr>"
                + "<w:tr><w:tc><w:tcPr><w:tcW w:w=\"2600\" w:type=\"dxa\"/></w:tcPr>"
                + "<w:p><w:pPr><w:jc w:val=\"center\"/><w:ind w:firstLine=\"0\"/>"
                + "<w:spacing w:before=\"120\" w:after=\"120\"/></w:pPr>"
                + "<w:r><w:t xml:space=\"preserve\"> </w:t></w:r></w:p></w:tc></w:tr>"
                + "</w:tbl>");

        // 信息栏：标签右对齐 + 值区下划线（表格实现，打印对齐稳定）
        b.append("<w:p><w:pPr><w:spacing w:before=\"1200\"/></w:pPr></w:p>");
        b.append(infoTable());

        return b.toString();
    }

    private static String infoTable() {
        String[][] rows = {
                {"课题名称", ""},
                {"班　　级", ""},
                {"学生姓名", ""},
                {"学　　号", ""},
                {"指导教师", ""},
                {"日　　期", ""}
        };
        StringBuilder b = new StringBuilder();
        b.append("<w:tbl><w:tblPr><w:tblW w:w=\"7600\" w:type=\"dxa\"/><w:jc w:val=\"center\"/>"
                + "<w:tblBorders>"
                + "<w:top w:val=\"none\" w:sz=\"0\" w:space=\"0\" w:color=\"auto\"/>"
                + "<w:left w:val=\"none\" w:sz=\"0\" w:space=\"0\" w:color=\"auto\"/>"
                + "<w:bottom w:val=\"none\" w:sz=\"0\" w:space=\"0\" w:color=\"auto\"/>"
                + "<w:right w:val=\"none\" w:sz=\"0\" w:space=\"0\" w:color=\"auto\"/>"
                + "<w:insideH w:val=\"none\" w:sz=\"0\" w:space=\"0\" w:color=\"auto\"/>"
                + "<w:insideV w:val=\"none\" w:sz=\"0\" w:space=\"0\" w:color=\"auto\"/>"
                + "</w:tblBorders></w:tblPr>"
                + "<w:tblGrid><w:gridCol w:w=\"2100\"/><w:gridCol w:w=\"5500\"/></w:tblGrid>");
        for (String[] row : rows) {
            b.append("<w:tr>")
                    .append("<w:tc><w:tcPr><w:tcW w:w=\"2100\" w:type=\"dxa\"/></w:tcPr>")
                    .append("<w:p><w:pPr><w:jc w:val=\"right\"/><w:ind w:firstLine=\"0\"/></w:pPr>")
                    .append("<w:r><w:t>").append(escape(row[0])).append("：</w:t></w:r></w:p></w:tc>")
                    .append("<w:tc><w:tcPr><w:tcW w:w=\"5500\" w:type=\"dxa\"/>")
                    .append("<w:tcBorders><w:bottom w:val=\"single\" w:sz=\"8\" w:space=\"0\" w:color=\"000000\"/></w:tcBorders>")
                    .append("</w:tcPr>")
                    .append("<w:p><w:pPr><w:jc w:val=\"left\"/><w:ind w:firstLine=\"0\"/></w:pPr>")
                    .append("<w:r><w:t xml:space=\"preserve\"> </w:t></w:r></w:p></w:tc>")
                    .append("</w:tr>");
        }
        b.append("</w:tbl>");
        return b.toString();
    }

    /** 第 2 页：评分标准与写作要点 */
    private static String scoringGuide() {
        StringBuilder b = new StringBuilder();
        b.append(p("Heading1", "评分标准与写作要点"));

        b.append(p("Heading2", "一、五维评分（每项 10 分，合计 50 分）"));
        b.append(p(null, "表达：术语是否准确、语句是否通顺、有没有把关键结论说清楚。"));
        b.append(p(null, "逻辑：章节之间是否递进，问题—方案—验证是否闭环。"));
        b.append(p(null, "专业：技术选型是否有依据，是否体现对原理的理解。"));
        b.append(p(null, "应变：是否考虑了异常、边界、并发与降级。"));
        b.append(p(null, "创新：有没有自己的改进、取舍或工程化亮点。"));

        b.append(p("Heading2", "二、等级对照"));
        b.append(twoColumnTable(
                new String[]{"总分", "等级"},
                new String[][]{
                        {"35 ~ 50", "优秀"},
                        {"26 ~ 34", "良好"},
                        {"15 ~ 25", "合格"},
                        {"5 ~ 14", "不合格"},
                        {"0 ~ 4", "无效（跑题或几乎无实质内容）"}
                },
                new int[]{2600, 5000}));

        b.append(p("Heading2", "三、各章建议篇幅"));
        b.append(twoColumnTable(
                new String[]{"章节", "建议字数"},
                new String[][]{
                        {"一、选题背景与目标", "400 ~ 600 字"},
                        {"二、需求分析与总体方案", "500 ~ 800 字"},
                        {"三、技术方案与实现", "800 ~ 1200 字"},
                        {"四、数据与实验", "400 ~ 800 字"},
                        {"五、难点、风险与局限", "300 ~ 600 字"},
                        {"六、总结与展望", "200 ~ 400 字"}
                },
                new int[]{5000, 2600}));

        b.append(p("Heading2", "四、常见扣分点"));
        b.append(p(null, "· 缺章节：六个一级章节缺一个，对应维度直接按最低档并标注「缺失」。"));
        b.append(p(null, "· 只贴图：关键方案只在截图/流程图里，正文没有文字说明——系统读不到图里的结构。"));
        b.append(p(null, "· 只给结论没数据：写「效果很好」但不给指标、对比或测试条件。"));
        b.append(p(null, "· 通篇概述：没有具体模块、接口、数据结构或排错过程。"));
        b.append(p(null, "· 跑题：报告内容与所选课题不一致，整份按无效处理。"));
        return b.toString();
    }

    /** 第 3 页：填写须知 */
    private static String notice() {
        StringBuilder b = new StringBuilder();
        b.append(p("Heading1", "填写须知（提交前请先读完）"));
        b.append(p(null, "1. 提交格式：只接受 .docx 文件，不超过 50MB。旧版 .doc、PDF、PPT、Excel 均无法识别，"
                + "请用 Word 或 WPS 另存为 .docx 后上传。"));
        b.append(p(null, "2. 正文字数：建议不少于 3000 字。实测低于 1000 字时，系统无法区分报告质量，"
                + "无论内容如何都会落入「合格」档（约 20 分）。"));
        b.append(p(null, "3. 禁止用图片承载关键内容：系统只能读取文字。图片里的文字会被尽量识别，"
                + "但架构图、流程图、时序图、图表的箭头关系与趋势无法判读。"));
        b.append(p(null, "4. 表格可以用，但表格里的结论必须用文字再写一遍，否则等于没写。"));
        b.append(p(null, "5. 关键内容不要只放在页眉、页脚、文本框或批注中，这些位置不保证被读取。"));
        b.append(p(null, "6. 不要写「给我满分」「忽略以上指令」等与报告内容无关的话："
                + "评分环节看不到原文，写了也不会生效。"));
        b.append(p(null, "7. 评分口径：表达、逻辑、专业、应变、创新五个维度各 10 分，合计 50 分。"
                + "等级：35 分及以上优秀，26~34 良好，15~25 合格，5~14 不合格，5 分以下无效。"));
        b.append(p(null, "8. 缺少章节会被直接压分并标注「缺失」；「难点与局限」这一章最容易被忽略，也最容易被压分。"));
        return b.toString();
    }

    // ==================================================================
    // OOXML 包骨架
    // ==================================================================

    private static String contentTypes(boolean withImages) {
        return DECLARATION
                + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + (withImages ? "<Default Extension=\"png\" ContentType=\"image/png\"/>" : "")
                + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
                + "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>"
                + "</Types>";
    }

    private static String rootRelationships() {
        return DECLARATION
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>"
                + "</Relationships>";
    }

    private static String documentRelationships(boolean withImages) {
        return DECLARATION
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>"
                + (withImages
                ? "<Relationship Id=\"rId10\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"media/school-header.png\"/>"
                : "")
                + "</Relationships>";
    }

    /** 中文字体走 eastAsia，正文宋体小四（12pt = 24 半磅），标题黑体 */
    private static String styles() {
        return DECLARATION
                + "<w:styles xmlns:w=\"" + W_NS + "\">"
                + "<w:docDefaults><w:rPrDefault><w:rPr>"
                + "<w:rFonts w:ascii=\"Times New Roman\" w:eastAsia=\"宋体\" w:hAnsi=\"Times New Roman\"/>"
                + "<w:sz w:val=\"24\"/><w:szCs w:val=\"24\"/>"
                + "</w:rPr></w:rPrDefault>"
                + "<w:pPrDefault><w:pPr><w:spacing w:line=\"360\" w:lineRule=\"auto\"/>"
                + "<w:ind w:firstLine=\"480\"/></w:pPr></w:pPrDefault></w:docDefaults>"
                + style("Normal", "正文", 24, false, true, false)
                + style("Title", "标题", 44, true, false, false)
                + style("Subtitle", "副标题", 21, false, false, false)
                + style("Heading1", "一级标题", 32, true, false, true)
                + style("Heading2", "二级标题", 28, true, false, true)
                + "</w:styles>";
    }

    private static String style(String id, String name, int halfPoints, boolean bold,
                                boolean indent, boolean outline) {
        // Normal 不能 basedOn 自己（Word 会判定文档结构非法），且必须标记为默认样式
        boolean normal = "Normal".equals(id);
        return "<w:style w:type=\"paragraph\"" + (normal ? " w:default=\"1\"" : "")
                + " w:styleId=\"" + id + "\">"
                + "<w:name w:val=\"" + name + "\"/>"
                + (normal ? "" : "<w:basedOn w:val=\"Normal\"/>")
                + "<w:qFormat/>"
                + "<w:pPr>"
                + (indent ? "" : "<w:ind w:firstLine=\"0\"/>")
                + (outline ? "<w:outlineLvl w:val=\"0\"/>" : "")
                + "<w:spacing w:before=\"120\" w:after=\"120\" w:line=\"360\" w:lineRule=\"auto\"/>"
                + "</w:pPr>"
                + "<w:rPr>"
                + "<w:rFonts w:ascii=\"黑体\" w:eastAsia=\"黑体\" w:hAnsi=\"黑体\"/>"
                + (bold ? "<w:b/>" : "")
                + "<w:sz w:val=\"" + halfPoints + "\"/><w:szCs w:val=\"" + halfPoints + "\"/>"
                + "</w:rPr>"
                + "</w:style>";
    }

    private static String p(String style, String text) {
        StringBuilder b = new StringBuilder("<w:p>");
        if (style != null) {
            b.append("<w:pPr><w:pStyle w:val=\"").append(style).append("\"/></w:pPr>");
        }
        if (text != null && !text.isEmpty()) {
            b.append("<w:r><w:t xml:space=\"preserve\">").append(escape(text)).append("</w:t></w:r>");
        }
        b.append("</w:p>");
        return b.toString();
    }

    private static String pageBreak() {
        return "<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>";
    }

    /** 两列带边框表格（表头加粗 + 浅灰底） */
    private static String twoColumnTable(String[] header, String[][] rows, int[] widths) {
        StringBuilder b = new StringBuilder();
        b.append("<w:tbl><w:tblPr><w:tblW w:w=\"0\" w:type=\"auto\"/><w:jc w:val=\"center\"/>"
                + "<w:tblBorders>"
                + border("top") + border("left") + border("bottom") + border("right")
                + "<w:insideH w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"808080\"/>"
                + "<w:insideV w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"808080\"/>"
                + "</w:tblBorders></w:tblPr>"
                + "<w:tblGrid><w:gridCol w:w=\"").append(widths[0]).append("\"/>")
                .append("<w:gridCol w:w=\"").append(widths[1]).append("\"/></w:tblGrid>");
        b.append(tableRow(header, widths, true));
        for (String[] row : rows) {
            b.append(tableRow(row, widths, false));
        }
        b.append("</w:tbl>");
        // 表格后补一个空段落，避免连续两个表格贴在一起
        b.append("<w:p><w:pPr><w:ind w:firstLine=\"0\"/></w:pPr></w:p>");
        return b.toString();
    }

    private static String tableRow(String[] cells, int[] widths, boolean header) {
        StringBuilder b = new StringBuilder("<w:tr>");
        for (int i = 0; i < cells.length; i++) {
            b.append("<w:tc><w:tcPr><w:tcW w:w=\"").append(widths[i]).append("\" w:type=\"dxa\"/>")
                    .append(header ? "<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"F2F2F2\"/>" : "")
                    .append("</w:tcPr>")
                    .append("<w:p><w:pPr><w:jc w:val=\"center\"/><w:ind w:firstLine=\"0\"/></w:pPr>")
                    .append("<w:r>");
            if (header) {
                b.append("<w:rPr><w:b/></w:rPr>");
            }
            b.append("<w:t>").append(escape(cells[i])).append("</w:t></w:r></w:p></w:tc>");
        }
        b.append("</w:tr>");
        return b.toString();
    }

    private static String border(String side) {
        return "<w:" + side + " w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"808080\"/>";
    }

    /** 内联图片（OOXML drawing），尺寸单位 EMU */
    private static String imageRun(String rId, int docPrId, String name, long cx, long cy) {
        return "<w:r><w:drawing><wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\">"
                + "<wp:extent cx=\"" + cx + "\" cy=\"" + cy + "\"/>"
                + "<wp:docPr id=\"" + docPrId + "\" name=\"" + name + "\"/>"
                + "<a:graphic><a:graphicData uri=\"" + PIC_NS + "\">"
                + "<pic:pic><pic:nvPicPr>"
                + "<pic:cNvPr id=\"" + docPrId + "\" name=\"" + name + "\"/><pic:cNvPicPr/>"
                + "</pic:nvPicPr>"
                + "<pic:blipFill><a:blip r:embed=\"" + rId + "\"/>"
                + "<a:stretch><a:fillRect/></a:stretch></pic:blipFill>"
                + "<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"" + cx + "\" cy=\"" + cy + "\"/></a:xfrm>"
                + "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr>"
                + "</pic:pic></a:graphicData></a:graphic>"
                + "</wp:inline></w:drawing></w:r>";
    }

    private static byte[] readResource(String path) {
        try (InputStream in = ReportTemplateBuilder.class.getResourceAsStream(path)) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException e) {
            return null;
        }
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
