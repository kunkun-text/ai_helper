package com.ai_helper.ai_helper.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * CSV 转义回归测试（N14 · 2026-10-08）。
 *
 * <p>这几条是**审计修过的安全行为**（2026-10-01 P1-1 公式注入），一旦有人改坏转义，
 * 单元测试会先于线上暴露问题。</p>
 */
class CsvUtilsTest {

    @Test
    @DisplayName("公式注入中和：= + - @ Tab CR 开头的单元格前置单引号（CWE-1236）")
    void neutralizeFormulaInjection() {
        assertEquals("'=1+1", CsvUtils.escapeCell("=1+1"));
        assertEquals("'+SUM(A1)", CsvUtils.escapeCell("+SUM(A1)"));
        assertEquals("'-2", CsvUtils.escapeCell("-2"));
        assertEquals("'@cmd", CsvUtils.escapeCell("@cmd"));
        assertEquals("'\ttab", CsvUtils.escapeCell("\ttab"));
        // 正常文本不加前缀
        assertEquals("张三", CsvUtils.escapeCell("张三"));
    }

    @Test
    @DisplayName("含逗号/引号/换行时用双引号包裹，内部引号翻倍")
    void quoteWhenNeeded() {
        assertEquals("\"张,三\"", CsvUtils.escapeCell("张,三"));
        assertEquals("\"说\"\"你好\"\"\"", CsvUtils.escapeCell("说\"你好\""));
        assertEquals("\"第一行\n第二行\"", CsvUtils.escapeCell("第一行\n第二行"));
    }

    @Test
    @DisplayName("null 与数值单元格")
    void nullAndNumeric() {
        assertEquals("", CsvUtils.escapeCell(null));
        assertEquals("35.5", CsvUtils.escapeCell(35.5));
        assertEquals("7", CsvUtils.escapeCell(7));
    }

    @Test
    @DisplayName("整行拼装：逗号连接且每个单元格各自转义")
    void joinRow() {
        assertEquals("题目,标准答案", CsvUtils.joinRow("题目", "标准答案"));
        assertEquals("a,\"b,c\",", CsvUtils.joinRow("a", "b,c", null));
    }

    @Test
    @DisplayName("常量：BOM 与 CRLF")
    void constants() {
        assertEquals('\ufeff', CsvUtils.BOM.charAt(0));
        assertEquals("\r\n", CsvUtils.CRLF);
    }
}
