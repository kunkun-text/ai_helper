package com.ai_helper.ai_helper.util;

import com.ai_helper.ai_helper.pojo.dto.DocScoreResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 报告评析解析回归测试（2026-10-16 · 报告五维评分第一期）。
 *
 * <p>这里守的是**安全方向**：解析不出来必须返回 {@code null}（上层据此不写库、置 FAILED），
 * 绝不能像逐轮答辩那样回退成全 0 —— 那会把「模型没按格式输出」变成「学生拿了 0 分」。</p>
 */
class DocScoreParserTest {

    private static final String GOOD_OUTPUT = String.join("\n",
            "评分:34/50|7|6|8|7|6",
            "表达:语言流畅，章节标题清晰",
            "逻辑:从需求到方案到验证，论证链条完整",
            "专业:引用了 HDFS 与 MapReduce 的具体机制",
            "应变:讨论了小文件场景的局限与备选方案",
            "创新:自主设计了缓存层");

    @Test
    @DisplayName("正常六行输出：五维解析正确，总分由服务端按五维之和重算")
    void parseNormal() {
        DocScoreResult r = DocScoreParser.parse(GOOD_OUTPUT);
        assertNotNull(r);
        assertEquals(7.0, r.getExpression());
        assertEquals(6.0, r.getLogic());
        assertEquals(8.0, r.getProfessional());
        assertEquals(7.0, r.getAdaptability());
        assertEquals(6.0, r.getInnovation());
        assertEquals(34.0, r.getTotalScore());
    }

    @Test
    @DisplayName("模型自报总分不可信：与五维之和不一致时以五维之和为准")
    void totalScoreIsSumOfDimsNotModelClaim() {
        // 模型自报 48，五维之和其实是 32
        DocScoreResult r = DocScoreParser.parse("评分:48/50|6|6|7|7|6");
        assertNotNull(r);
        assertEquals(32.0, r.getTotalScore());
    }

    @Test
    @DisplayName("越界值夹到 [0,10]：12 与 -3 都不会原样落库")
    void clampOutOfRange() {
        DocScoreResult r = DocScoreParser.parse("评分:48/50|12|-3|7|8|9");
        assertNotNull(r);
        assertEquals(10.0, r.getExpression());
        assertEquals(0.0, r.getLogic());
        assertEquals(34.0, r.getTotalScore());
    }

    @Test
    @DisplayName("容忍「7分」「得分7」这类写法，取其中第一个数字")
    void tolerateNumberSuffix() {
        DocScoreResult r = DocScoreParser.parse("评分:30/50|7分|6.5分|得分8|7|6");
        assertNotNull(r);
        assertEquals(7.0, r.getExpression());
        assertEquals(6.5, r.getLogic());
        assertEquals(8.0, r.getProfessional());
    }

    @Test
    @DisplayName("兼容全角冒号（模型偶尔输出）")
    void tolerateFullWidthColon() {
        DocScoreResult r = DocScoreParser.parse("评分：30/50|7|6|7|6|4");
        assertNotNull(r);
        assertEquals(30.0, r.getTotalScore());
    }

    @Test
    @DisplayName("解析失败一律返回 null：无评分行 / 段数不足 / 维度非数字 / 空输入")
    void unparsableReturnsNull() {
        // 完全没有评分行
        assertNull(DocScoreParser.parse("这份报告写得不错，逻辑清晰。"));
        // 段数不足 6（只有总分和三维）
        assertNull(DocScoreParser.parse("评分:30/50|7|6|7"));
        // 维度位置不是数字
        assertNull(DocScoreParser.parse("评分:30/50|很好|一般|7|6|4"));
        // 空输入
        assertNull(DocScoreParser.parse(null));
        assertNull(DocScoreParser.parse(""));
        assertNull(DocScoreParser.parse("   \n  "));
    }

    @Test
    @DisplayName("逐维点评按维度名+冒号抓取，未给出的维度补占位文案")
    void dimComments() {
        DocScoreResult r = DocScoreParser.parse(String.join("\n",
                "评分:20/50|4|4|4|4|4",
                "表达:结构清晰",
                "专业:术语使用准确"));
        assertNotNull(r);
        String c = r.getDimComments();
        assertTrue(c.contains("表达（4分）：结构清晰"), c);
        assertTrue(c.contains("逻辑（4分）：（模型未给出该维度点评）"), c);
        assertTrue(c.contains("专业（4分）：术语使用准确"), c);
    }

    @Test
    @DisplayName("评分行不会被误当成「表达」维度的点评")
    void scoreLineNotTreatedAsComment() {
        // 评分行里含有「表达」二字，但它以「评分:」开头，不应被 extractLineValue 抓走
        DocScoreResult r = DocScoreParser.parse("评分:32/50|7|6|7|6|6");
        assertNotNull(r);
        assertTrue(r.getDimComments().contains("表达（7分）：（模型未给出该维度点评）"), r.getDimComments());
    }

    @Test
    @DisplayName("原始输出被保留，便于排查模型实际给了什么")
    void keepsRawOutput() {
        DocScoreResult r = DocScoreParser.parse(GOOD_OUTPUT);
        assertNotNull(r);
        assertEquals(GOOD_OUTPUT, r.getRawOutput());
    }

    @Test
    @DisplayName("分数展示格式：整数不带小数点，小数保留一位")
    void formatScore() {
        assertEquals("7", DocScoreParser.formatScore(7.0));
        assertEquals("6.5", DocScoreParser.formatScore(6.5));
        assertEquals("0", DocScoreParser.formatScore(0.0));
    }

    // ---------- 2026-10-16 实测后新增：评分与点评拆两次调用，评分行不带总分 ----------

    @Test
    @DisplayName("推荐形态（无总分，恰好 5 段）：直接解析五维，总分由服务端求和")
    void parseFiveSegmentNoTotal() {
        // 2026-10-16 实测：优质含注入报告的评分调用输出原样
        DocScoreResult r = DocScoreParser.parse("评分:7|7|9|7|8");
        assertNotNull(r);
        assertEquals(7.0, r.getExpression());
        assertEquals(7.0, r.getLogic());
        assertEquals(9.0, r.getProfessional());
        assertEquals(7.0, r.getAdaptability());
        assertEquals(8.0, r.getInnovation());
        assertEquals(38.0, r.getTotalScore());
    }

    @Test
    @DisplayName("带「总分/50」前缀却只有 5 段（漏了一维）→ 判定失败，硬解析会把总分错当维度")
    void fiveSegmentWithTotalPrefixFails() {
        // 30/50 是总分不是表达分，无法确定缺的是哪一维
        assertNull(DocScoreParser.parse("评分:30/50|7|6|7|6"));
    }

    @Test
    @DisplayName("全角竖线归一：模型偶尔输出｜也能解析")
    void tolerateFullWidthPipe() {
        DocScoreResult r = DocScoreParser.parse("评分：7｜7｜9｜7｜8");
        assertNotNull(r);
        assertEquals(38.0, r.getTotalScore());
    }

    @Test
    @DisplayName("formatDimComments：点评原始输出 × 分数合成展示文本，缺失维度补占位")
    void formatDimCommentsMergesScoresWithRawComments() {
        String raw = String.join("\n",
                "表达:章节划分为背景、方案、实现、结论，语句通顺",
                "专业:准确使用了MapReduce、HDFS等术语并给出推理耗时数据");
        String merged = DocScoreParser.formatDimComments(raw, new double[]{7, 8, 9, 4, 4});
        assertTrue(merged.contains("表达（7分）：章节划分为背景、方案、实现、结论，语句通顺"), merged);
        assertTrue(merged.contains("逻辑（8分）：（模型未给出该维度点评）"), merged);
        assertTrue(merged.contains("专业（9分）：准确使用了MapReduce、HDFS等术语并给出推理耗时数据"), merged);
        assertTrue(merged.contains("应变（4分）：（模型未给出该维度点评）"), merged);
    }

    // ---------- 2026-10-16 成绩单化：分数守护 + 等级 + 缺失标记 ----------

    @Test
    @DisplayName("分数守护：点评自认缺失的维度被强制压到 0~4（实测抓到过「写未给出却给7分」）")
    void missingGuardClampsScore() {
        // 取自 2026-10-16 实测 defenseId=348（劣质报告）的真实点评
        String comments = String.join("\n",
                "表达:只有笼统描述，缺少具体细节（如技术栈）",
                "逻辑:项目完成度高，达到了预期目标",
                "专业:使用了主流技术栈，但未提及具体技术名称",
                "应变:开发过程中遇到困难并解决，体现了问题处理能力",
                "创新:无创新点，未能提出新的系统功能或改进措施");
        // 模型给的是 0,5,7,6,1 —— 表达/专业/创新三处点评自认缺失
        double[] guarded = DocScoreParser.applyMissingGuards(
                new double[]{0, 5, 7, 6, 1}, comments);
        assertEquals(0.0, guarded[0], "已低于上限，维持");
        assertEquals(5.0, guarded[1], "逻辑点评无缺失信号，不动");
        assertEquals(4.0, guarded[2], "「未提及具体技术名称」→ 压到 4");
        assertEquals(6.0, guarded[3], "应变点评无缺失信号，不动");
        assertEquals(1.0, guarded[4], "「无创新点」→ 已低于上限，维持");
    }

    @Test
    @DisplayName("分数守护：点评整行缺失同样视为无依据")
    void missingCommentRowIsGuarded() {
        String comments = "表达:章节划分清晰";
        double[] guarded = DocScoreParser.applyMissingGuards(new double[]{7, 9, 9, 9, 9}, comments);
        assertEquals(7.0, guarded[0]);
        for (int i = 1; i < 5; i++) {
            assertEquals(4.0, guarded[i], "缺点评行的维度应被压到 4 分");
        }
    }

    @Test
    @DisplayName("缺失标记：自认缺失的维度展示时挂 ⚠️，有依据的不挂")
    void missingDimensionsAreMarked() {
        String comments = String.join("\n",
                "表达:第一章绪论结构清晰，数据详实",
                "应变:材料未涉及局限性与备选方案");
        String text = DocScoreParser.formatDimComments(comments, new double[]{7, 5, 5, 4, 5});
        assertTrue(text.contains("表达（7分）：第一章绪论结构清晰，数据详实"), text);
        assertFalse(text.contains("⚠️ 表达"), text);
        assertTrue(text.contains("⚠️ 应变（4分）：材料未涉及局限性与备选方案"), text);
    }

    @Test
    @DisplayName("跑题守护：判为切题否时五维压到 0~2（对齐文字答辩第五档）")
    void offTopicGuardClampsEverything() {
        String comments = String.join("\n",
                "表达:第二章功能需求与第四章实现模块一一对应",
                "逻辑:从需求分析到功能实现链条完整",
                "专业:正确使用了JWT鉴权与事务回滚",
                "应变:只列举了风险没有给出应对措施",
                "创新:新增的按学分估值定价算法有公式支撑",
                "切题:否");
        assertTrue(DocScoreParser.isOffTopic(comments));
        double[] guarded = DocScoreParser.applyOffTopicGuard(new double[]{8, 8, 9, 7, 8}, comments);
        for (double v : guarded) {
            assertEquals(0.0, v, "跑题时五维一律归零（对齐文字答辩第五档固定 0 分）");
        }
    }

    @Test
    @DisplayName("跑题守护：没有「切题:」行时不判跑题（老结果/模型漏输出不会误杀）")
    void offTopicGuardIsSafeWhenTagMissing() {
        String noTag = "表达:章节清晰\n逻辑:链条完整";
        assertFalse(DocScoreParser.isOffTopic(noTag));
        double[] dims = {7, 7, 8, 7, 8};
        double[] out = DocScoreParser.applyOffTopicGuard(dims, noTag);
        assertEquals(7.0, out[0], "无切题行时原样保留");
        // 切题:是 同样不压分
        assertFalse(DocScoreParser.isOffTopic("表达:x\n切题:是"));
        assertEquals(7.0, DocScoreParser.applyOffTopicGuard(dims, "切题:是")[0]);
    }

    @Test
    @DisplayName("切题行不换行粘连时仍能识别（实测抓到「…预警。切题:是」）")
    void topicJudgmentOnSameLineIsStillDetected() {
        // 2026-10-16 端到端实测 defenseId=350 的真实输出
        String glued = String.join("\n",
                "表达:系统设计围绕仓储管理展开，功能模块详尽。",
                "逻辑:从需求分析到实现各环节，逻辑清晰连贯。",
                "专业:使用了Spring Boot、MyBatis等成熟技术栈。",
                "应变:针对并发控制提出双重保护方案，应对挑战。",
                "创新:集成本地大语言模型进行自然语言查询与预警。切题:是");
        assertFalse(DocScoreParser.isOffTopic(glued), "粘连的「切题:是」应被判为切题");
        // 同样粘连的「切题:否」必须能被抓住，否则跑题闸门静默失效
        String gluedNo = glued.replace("切题:是", "切题：否");
        assertTrue(DocScoreParser.isOffTopic(gluedNo), "粘连的「切题:否」必须能识别");

        // 展示时应摘掉粘连片段，不污染「创新」点评
        String text = DocScoreParser.formatDimComments(glued, new double[]{7, 8, 9, 7, 8});
        assertTrue(text.contains("创新（8分）：集成本地大语言模型进行自然语言查询与预警"), text);
        assertFalse(text.contains("切题:是"), text);
    }

    @Test
    @DisplayName("等级映射：与文字答辩五档表共用同一把尺子（35/26/15/5 边界）")
    void gradeLevelAlignsWithTextDefenseTiers() {
        assertEquals("优秀", DocScoreParser.gradeLevel(42));
        assertEquals("优秀", DocScoreParser.gradeLevel(35));
        assertEquals("良好", DocScoreParser.gradeLevel(34));
        assertEquals("良好", DocScoreParser.gradeLevel(26));
        assertEquals("合格", DocScoreParser.gradeLevel(25));
        assertEquals("合格", DocScoreParser.gradeLevel(15));
        assertEquals("不合格", DocScoreParser.gradeLevel(14));
        assertEquals("不合格", DocScoreParser.gradeLevel(5));
        assertEquals("无效", DocScoreParser.gradeLevel(4));
        assertEquals("无效", DocScoreParser.gradeLevel(0));
    }
}
