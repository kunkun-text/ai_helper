package com.ai_helper.ai_helper.util;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 文档纯文本抽取（2026-10-16 · 报告五维评分第一期）。
 *
 * <p><b>为什么零依赖自己写：</b>项目全程 {@code mvn -o} 离线编译，本地仓库里没有任何文档解析库；
 * 而 docx/pptx/xlsx 本质上都是 zip + XML，用 JDK 自带的
 * {@link ZipInputStream} + StAX 足够取出纯文本，不值得为此引入 POI（几十 MB 传递依赖）。</p>
 *
 * <p><b>安全口径（三条都必须在链路上）：</b></p>
 * <ol>
 *   <li><b>压缩炸弹</b>：单条目声明大小、累计解压量、压缩比三重上限；
 *       且按**实际读入字节**计数——{@code ZipEntry.getSize()} 是压缩包自己声明的，可以伪造。</li>
 *   <li><b>XXE</b>：StAX 关掉 DTD 与外部实体，文档里的外部引用一律不解析。</li>
 *   <li><b>提示词注入</b>：文档正文一律当**数据**处理。抽取结果里出现的分隔符字样
 *       （{@code <<<} / {@code >>>}）会被替换成全角，防止正文伪造分隔符把自己"送出"材料区。</li>
 * </ol>
 *
 * <p>全程流式处理，**不把解压出来的条目落到磁盘**；所有方法都是无状态静态方法，
 * 可直接用纯函数单测覆盖（不启 Spring、不连库）。</p>
 */
public final class DocTextExtractor {

    /** 可解析的格式 */
    public enum Format {
        DOCX, PPTX, XLSX, TXT, UNSUPPORTED
    }

    /** 解析失败（格式不支持 / 压缩炸弹 / 文档损坏）——由调用方转成用户可读文案 */
    public static class DocExtractException extends Exception {
        public DocExtractException(String message) {
            super(message);
        }

        public DocExtractException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** 抽取上限。默认值可由 {@code app.report-doc.*} 覆盖，构造后由调用方赋值 */
    public static final class Limits {
        /** 单个 zip 条目解压后的字节上限（声明值与实际值都比对） */
        public long maxEntryBytes = 4L * 1024 * 1024;
        /** 全部条目累计解压字节上限 */
        public long maxTotalBytes = 40L * 1024 * 1024;
        /** 压缩比上限，超过即判定为压缩炸弹 */
        public int maxCompressionRatio = 200;
        /** 抽取出的纯文本字数上限，超出截断（不抛异常，避免整场失败） */
        public int maxChars = 60000;

        public static Limits defaults() {
            return new Limits();
        }
    }

    /** pptx 幻灯片条目：ppt/slides/slideN.xml */
    private static final Pattern PPT_SLIDE = Pattern.compile("ppt/slides/slide(\\d+)\\.xml");

    /** xlsx 工作表条目：xl/worksheets/sheetN.xml */
    private static final Pattern XLSX_SHEET = Pattern.compile("xl/worksheets/sheet(\\d+)\\.xml");

    /** docx 正文条目 */
    private static final String DOCX_DOCUMENT = "word/document.xml";

    /** xlsx 共享字符串表 */
    private static final String XLSX_SHARED_STRINGS = "xl/sharedStrings.xml";

    /** 一次最多解析多少张幻灯片 / 工作表，防超长文档把内存与耗时拉爆 */
    private static final int MAX_PARTS = 100;

    private static final int BUFFER_SIZE = 8192;

    private DocTextExtractor() {
    }

    /** 由扩展名判断格式（大小写不敏感，允许带前导点） */
    public static Format formatOf(String ext) {
        if (ext == null) {
            return Format.UNSUPPORTED;
        }
        String e = ext.trim().toLowerCase(Locale.ROOT);
        if (e.startsWith(".")) {
            e = e.substring(1);
        }
        switch (e) {
            case "docx":
                return Format.DOCX;
            case "pptx":
                return Format.PPTX;
            case "xlsx":
                return Format.XLSX;
            case "txt":
                return Format.TXT;
            default:
                return Format.UNSUPPORTED;
        }
    }

    /** 该扩展名本期是否支持解析（供 Controller 提前给出明确提示） */
    public static boolean supported(String ext) {
        return formatOf(ext) != Format.UNSUPPORTED;
    }

    /**
     * 抽取纯文本。返回的是已经归一化并截断的文本，可能为空字符串（例如空文档），
     * 但**不返回 null**。
     *
     * @throws DocExtractException 格式不支持 / 文档损坏 / 判定为压缩炸弹
     */
    public static String extract(InputStream in, String ext, Limits limits) throws DocExtractException {
        if (in == null) {
            throw new DocExtractException("文档内容为空");
        }
        Limits lim = limits == null ? Limits.defaults() : limits;
        Format format = formatOf(ext);
        if (format == Format.UNSUPPORTED) {
            throw new DocExtractException("本期暂不支持解析该格式：" + ext);
        }

        String raw;
        if (format == Format.TXT) {
            raw = decodeText(readBounded(in, lim.maxTotalBytes));
        } else {
            raw = extractZip(in, format, lim);
        }
        return normalize(raw, lim.maxChars);
    }

    // ------------------------------------------------------------------
    // zip 与各格式
    // ------------------------------------------------------------------

    private static String extractZip(InputStream in, Format format, Limits limits) throws DocExtractException {
        Map<String, byte[]> wanted = collectWantedEntries(in, format, limits);
        if (wanted.isEmpty()) {
            throw new DocExtractException("文档内容为空或结构异常，未能取到正文");
        }
        try {
            switch (format) {
                case DOCX:
                    return extractParagraphText(wanted.get(DOCX_DOCUMENT));
                case PPTX:
                    return extractPptx(wanted);
                case XLSX:
                    return extractXlsx(wanted);
                default:
                    throw new DocExtractException("本期暂不支持解析该格式");
            }
        } catch (XMLStreamException e) {
            throw new DocExtractException("文档内容解析失败（文件可能已损坏）", e);
        }
    }

    /**
     * 单遍扫描 zip，只把我们关心的条目读进内存（受三重上限约束）。
     *
     * <p>用单遍而不是 {@code ZipFile} 随机访问，是因为上传流没有落盘路径；
     * 关心条目总共不超过几十个、且总量有硬上限，全部放内存是安全的。</p>
     */
    private static Map<String, byte[]> collectWantedEntries(InputStream in, Format format, Limits limits)
            throws DocExtractException {
        Map<String, byte[]> result = new LinkedHashMap<>();
        long totalRead = 0;
        try (ZipInputStream zin = new ZipInputStream(in, StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                if (!isWanted(name, format)) {
                    continue;
                }

                long declared = entry.getSize();
                if (declared > limits.maxEntryBytes) {
                    throw new DocExtractException("文档内部条目过大，已拒绝解析");
                }
                long compressed = entry.getCompressedSize();
                if (declared > 0 && compressed > 0
                        && declared / Math.max(1L, compressed) > limits.maxCompressionRatio) {
                    throw new DocExtractException("文档压缩比异常（疑似压缩炸弹），已拒绝解析");
                }

                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[BUFFER_SIZE];
                long entryRead = 0;
                int n;
                while ((n = zin.read(buf)) > 0) {
                    entryRead += n;
                    totalRead += n;
                    // 声明值可以伪造，这里按实际读入量再兜一次
                    if (entryRead > limits.maxEntryBytes || totalRead > limits.maxTotalBytes) {
                        throw new DocExtractException("文档解压后体积过大，已拒绝解析");
                    }
                    bos.write(buf, 0, n);
                }
                result.put(name, bos.toByteArray());
            }
        } catch (IOException e) {
            throw new DocExtractException("文档读取失败（文件可能已损坏）", e);
        }
        return result;
    }

    private static boolean isWanted(String name, Format format) {
        switch (format) {
            case DOCX:
                return DOCX_DOCUMENT.equals(name);
            case PPTX:
                return PPT_SLIDE.matcher(name).matches();
            case XLSX:
                return XLSX_SHARED_STRINGS.equals(name) || XLSX_SHEET.matcher(name).matches();
            default:
                return false;
        }
    }

    /**
     * docx 与 pptx 的正文结构同形（{@code <w:p>/<a:p>} 段落、{@code <w:t>/<a:t>} 文本），
     * 本地名去掉命名空间前缀后都是 {@code p} / {@code t}，因此共用一套抽取。
     */
    private static String extractParagraphText(byte[] xml) throws XMLStreamException {
        if (xml == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        StringBuilder paragraph = new StringBuilder();
        boolean inParagraph = false;
        XMLStreamReader reader = openXml(xml);
        try {
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String local = reader.getLocalName();
                    if ("p".equals(local)) {
                        inParagraph = true;
                        paragraph.setLength(0);
                    } else if ("t".equals(local)) {
                        String text = reader.getElementText();
                        if (inParagraph && text != null) {
                            paragraph.append(text);
                        }
                    } else if ("tab".equals(local)) {
                        if (inParagraph) {
                            paragraph.append('\t');
                        }
                    } else if ("br".equals(local) || "cr".equals(local)) {
                        if (inParagraph) {
                            paragraph.append('\n');
                        }
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT
                        && "p".equals(reader.getLocalName()) && inParagraph) {
                    inParagraph = false;
                    appendParagraph(out, paragraph);
                }
            }
        } finally {
            closeQuietly(reader);
        }
        return out.toString();
    }

    /** pptx：按幻灯片序号**数值**排序（字典序会把 slide10 排到 slide2 前面） */
    private static String extractPptx(Map<String, byte[]> entries) throws XMLStreamException {
        List<Map.Entry<Integer, byte[]>> slides = new ArrayList<>();
        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
            Matcher m = PPT_SLIDE.matcher(e.getKey());
            if (!m.matches()) {
                continue;
            }
            if (slides.size() >= MAX_PARTS) {
                break;
            }
            slides.add(Map.entry(Integer.parseInt(m.group(1)), e.getValue()));
        }
        slides.sort((a, b) -> Integer.compare(a.getKey(), b.getKey()));

        StringBuilder out = new StringBuilder();
        for (Map.Entry<Integer, byte[]> slide : slides) {
            String page = extractParagraphText(slide.getValue());
            if (!page.isEmpty()) {
                if (out.length() > 0) {
                    out.append("\n\n");
                }
                out.append(page);
            }
        }
        return out.toString();
    }

    /** xlsx：先读共享字符串表，再按工作表逐个还原单元格文本 */
    private static String extractXlsx(Map<String, byte[]> entries) throws XMLStreamException {
        List<String> shared = entries.containsKey(XLSX_SHARED_STRINGS)
                ? extractSharedStrings(entries.get(XLSX_SHARED_STRINGS))
                : List.of();

        List<Map.Entry<Integer, byte[]>> sheets = new ArrayList<>();
        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
            Matcher m = XLSX_SHEET.matcher(e.getKey());
            if (!m.matches()) {
                continue;
            }
            if (sheets.size() >= MAX_PARTS) {
                break;
            }
            sheets.add(Map.entry(Integer.parseInt(m.group(1)), e.getValue()));
        }
        sheets.sort((a, b) -> Integer.compare(a.getKey(), b.getKey()));

        StringBuilder out = new StringBuilder();
        for (Map.Entry<Integer, byte[]> sheet : sheets) {
            String text = extractSheet(sheet.getValue(), shared);
            if (!text.isEmpty()) {
                if (out.length() > 0) {
                    out.append("\n\n");
                }
                out.append(text);
            }
        }
        return out.toString();
    }

    /** 共享字符串表：每个 {@code <si>} 是一个字符串，内部可能被拆成多个 {@code <t>}（富文本 run） */
    private static List<String> extractSharedStrings(byte[] xml) throws XMLStreamException {
        List<String> list = new ArrayList<>();
        XMLStreamReader reader = openXml(xml);
        StringBuilder current = null;
        try {
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String local = reader.getLocalName();
                    if ("si".equals(local)) {
                        current = new StringBuilder();
                    } else if ("t".equals(local) && current != null) {
                        String text = reader.getElementText();
                        if (text != null) {
                            current.append(text);
                        }
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT
                        && "si".equals(reader.getLocalName()) && current != null) {
                    list.add(current.toString());
                    current = null;
                }
            }
        } finally {
            closeQuietly(reader);
        }
        return list;
    }

    /** 单元格：{@code t="s"} 是共享字符串索引，{@code t="inlineStr"} 是内联字符串，其余取 {@code <v>} 字面量 */
    private static String extractSheet(byte[] xml, List<String> shared) throws XMLStreamException {
        StringBuilder out = new StringBuilder();
        StringBuilder row = new StringBuilder();
        StringBuilder inline = new StringBuilder();
        boolean inRow = false;
        String cellType = null;
        String cellValue = null;

        XMLStreamReader reader = openXml(xml);
        try {
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String local = reader.getLocalName();
                    if ("row".equals(local)) {
                        inRow = true;
                        row.setLength(0);
                    } else if ("c".equals(local)) {
                        cellType = reader.getAttributeValue(null, "t");
                        cellValue = null;
                        inline.setLength(0);
                    } else if ("v".equals(local)) {
                        cellValue = reader.getElementText();
                    } else if ("t".equals(local)) {
                        String text = reader.getElementText();
                        if (text != null) {
                            inline.append(text);
                        }
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    String local = reader.getLocalName();
                    if ("c".equals(local)) {
                        String cell = resolveCell(cellType, cellValue, inline.toString(), shared);
                        if (cell != null && !cell.isEmpty()) {
                            if (row.length() > 0) {
                                row.append('\t');
                            }
                            row.append(cell);
                        }
                    } else if ("row".equals(local)) {
                        if (inRow && row.length() > 0) {
                            if (out.length() > 0) {
                                out.append('\n');
                            }
                            out.append(row);
                        }
                        inRow = false;
                    }
                }
            }
        } finally {
            closeQuietly(reader);
        }
        return out.toString();
    }

    private static String resolveCell(String type, String value, String inline, List<String> shared) {
        if ("s".equals(type)) {
            int idx = parseIntSafe(value);
            if (idx >= 0 && idx < shared.size()) {
                return shared.get(idx);
            }
            return null;
        }
        if (inline != null && !inline.isEmpty()) {
            return inline;
        }
        return value;
    }

    private static int parseIntSafe(String s) {
        if (s == null) {
            return -1;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ------------------------------------------------------------------
    // 文本解码、归一化
    // ------------------------------------------------------------------

    /** 有界读取：txt 没有 zip 结构，直接用累计上限兜住 */
    private static byte[] readBounded(InputStream in, long cap) throws DocExtractException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[BUFFER_SIZE];
        long total = 0;
        try {
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > cap) {
                    throw new DocExtractException("文档体积过大，已拒绝解析");
                }
                bos.write(buf, 0, n);
            }
        } catch (IOException e) {
            throw new DocExtractException("文档读取失败", e);
        }
        return bos.toByteArray();
    }

    /**
     * 文本编码探测：先按严格 UTF-8 解，解不了再回退 GBK。
     *
     * <p>为什么不是直接 {@code new String(bytes, UTF_8)}：那会用替换字符把乱码"洗白"，
     * 解码不会失败，于是永远走不到 GBK 分支——国内 Office「另存为 txt」默认就是 GBK。</p>
     */
    static String decodeText(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        int offset = 0;
        // 去掉 UTF-8 BOM，否则会被当成正文的第一个字符
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF
                && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            offset = 3;
        }
        byte[] body = offset == 0 ? bytes : java.util.Arrays.copyOfRange(bytes, offset, bytes.length);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(body))
                    .toString();
        } catch (CharacterCodingException notUtf8) {
            // 回退 GBK：国内 Windows 上「另存为 txt」的默认编码
        }
        try {
            return Charset.forName("GBK").decode(ByteBuffer.wrap(body)).toString();
        } catch (Exception notGbk) {
            return new String(body, Charset.defaultCharset());
        }
    }

    /**
     * 归一化：统一换行、折叠连续空行、中和分隔符字样、按字数上限截断。
     *
     * <p>分隔符中和是**安全措施**：评分 prompt 用 {@code <<<DOC START>>>} 标记材料边界，
     * 若正文里出现同样字样，就可能在模型眼里"提前结束"材料区、把后面的内容当指令读。
     * 替换成全角后字面不再匹配，正文内容本身不受影响。</p>
     */
    static String normalize(String raw, int maxChars) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String s = raw.replace("\r\n", "\n").replace('\r', '\n');
        s = s.replace("<<<", "＜＜＜").replace(">>>", "＞＞＞");
        // 折叠三个以上的连续换行为两个（保留段落感，去掉大段空白）
        s = s.replaceAll("\n{3,}", "\n\n");
        s = s.trim();
        if (maxChars > 0 && s.length() > maxChars) {
            s = s.substring(0, maxChars);
        }
        return s;
    }

    /** 每个段落之间用空行分隔，为后续「按段落切块」保留天然断点 */
    private static void appendParagraph(StringBuilder out, StringBuilder paragraph) {
        String p = paragraph.toString().trim();
        paragraph.setLength(0);
        if (p.isEmpty()) {
            return;
        }
        if (out.length() > 0) {
            out.append("\n\n");
        }
        out.append(p);
    }

    // ------------------------------------------------------------------
    // XML 读取
    // ------------------------------------------------------------------

    /**
     * 建 StAX 读取器并**关掉 DTD 与外部实体**（防 XXE）。
     * {@code XMLInputFactory} 非线程安全，故每次新建。
     */
    private static XMLStreamReader openXml(byte[] xml) throws XMLStreamException {
        XMLInputFactory factory = XMLInputFactory.newInstance();
        setIfSupported(factory, XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
        setIfSupported(factory, XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
        setIfSupported(factory, XMLInputFactory.IS_COALESCING, Boolean.TRUE);
        return factory.createXMLStreamReader(new ByteArrayInputStream(xml));
    }

    /** 不同 StAX 实现对属性的支持程度不一，不认的属性忽略即可，不阻断解析 */
    private static void setIfSupported(XMLInputFactory factory, String property, Object value) {
        try {
            factory.setProperty(property, value);
        } catch (IllegalArgumentException ignored) {
            // 该实现不支持此属性，跳过
        }
    }

    private static void closeQuietly(XMLStreamReader reader) {
        if (reader == null) {
            return;
        }
        try {
            reader.close();
        } catch (XMLStreamException ignored) {
            // 关闭失败无需处理
        }
    }
}
