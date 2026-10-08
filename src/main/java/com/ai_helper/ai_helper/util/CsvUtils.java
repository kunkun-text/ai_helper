package com.ai_helper.ai_helper.util;

/**
 * CSV 工具（2026-10-08 · N10 从 {@code ExportController} 抽出，供「成绩导出」与「题库导入模板」共用同一套转义口径）。
 *
 * <p>抽出的原因：CSV 单元格转义与**公式注入中和**（CWE-1236）是审计修过的安全点
 * （见 CHANGES 2026-10-01 P1-1），如果导入模板另写一份，两处迟早会漂移。</p>
 */
public final class CsvUtils {

    /** UTF-8 BOM：Excel 打开中文 CSV 不乱码 */
    public static final String BOM = "\ufeff";

    /** 行分隔符固定 CRLF（{@code PrintWriter.println} 随服务器平台变化，Linux 上会变成 LF） */
    public static final String CRLF = "\r\n";

    private CsvUtils() {
    }

    /**
     * CSV 字段转义：① 含逗号 / 引号 / 换行时用双引号包裹并把内部引号翻倍；
     * ② 防公式注入（CWE-1236）：以 {@code = + - @ Tab} 或 CR 开头的单元格会被 Excel 当公式执行，
     * 用户可控字段（姓名/课题名等）可能携带此类前缀，统一前置单引号中和
     * （前置单引号只对 Excel 公式解析生效，单元格文本不受影响）。
     */
    public static String escapeCell(Object value) {
        if (value == null) {
            return "";
        }
        String s = String.valueOf(value);
        if (s.startsWith("=") || s.startsWith("+") || s.startsWith("-")
                || s.startsWith("@") || s.startsWith("\t") || s.startsWith("\r")) {
            s = "'" + s;
        }
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    /** 按单元格顺序拼一整行 CSV（不含行尾分隔符） */
    public static String joinRow(Object... cells) {
        if (cells == null || cells.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(escapeCell(cells[i]));
        }
        return sb.toString();
    }
}
