package com.ai_helper.ai_helper.util;

import com.ai_helper.ai_helper.constant.AiProtocolConstants;
import com.ai_helper.ai_helper.pojo.dto.DocScoreResult;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 上传报告评析结果的**严格解析器**（2026-10-16 · 报告五维评分第一期）。
 *
 * <p><b>为什么不能复用 {@code ScorePersistenceServiceImpl.parseScoresFromResponse}：</b>
 * 那个解析器服务于逐轮答辩——解析失败时会走 {@code fillDefaults()} 把五维全部填成 0.0，
 * 因为答辩必须每轮都有分数、不能中断。而本功能的口径正相反：**解析不出来就不写库**
 * （状态置 FAILED，教师可重试），绝不能把「模型没按格式输出」变成「学生拿了 0 分」。
 * 复用它会直接违背这条安全方向。</p>
 *
 * <p>本类只读 {@link AiProtocolConstants#SCORE_TAG} 常量定位评分行，
 * 不新增协议标签、不改动任何既有解析链路。</p>
 */
public final class DocScoreParser {

    /** 五维名称，顺序与评分行、展示顺序强绑定，不可调整 */
    public static final String[] DIMENSIONS = {"表达", "逻辑", "专业", "应变", "创新"};

    /** 评分行标签兼容半角/全角冒号（模型偶尔输出全角） */
    private static final String[] SCORE_TAGS = {AiProtocolConstants.SCORE_TAG, "评分："};

    /** 从「7」「7.5」「7分」「得分7」这类写法里抓第一个数字；抓不到返回 null */
    private static final Pattern NUMBER = Pattern.compile("-?\\d+(?:\\.\\d+)?");

    /** 单维满分 */
    private static final double DIM_MAX = 10.0;

    private DocScoreParser() {
    }

    /**
     * 解析模型输出。**任何不满足格式要求的情况一律返回 {@code null}**，绝不返回"全 0 的结果"。
     *
     * <p>评分行接受两种形态（2026-10-16 实测后放宽）：</p>
     * <ul>
     *   <li>推荐形态（评分由点评单独推导，不带总分，3B 数槽位更稳）：
     *       {@code 评分:表达|逻辑|专业|应变|创新} —— 恰好 5 段；</li>
     *   <li>旧形态：{@code 评分:总分/50|表达|逻辑|专业|应变|创新} —— 6 段，总分被忽略（服务端重算）。</li>
     * </ul>
     *
     * <p>带「总分/50」前缀却只有 5 段（模型漏写了一维）的情况**判定为失败**返回 null：
     * 无法确定缺的是哪一维，硬解析会把总分错当维度。上层会重试一次，仍失败才置 FAILED。</p>
     *
     * <p>点评不再从评分调用的输出里抓取（评分调用只回一行），而是由上层把「点评调用」的
     * 输出经 {@link #formatDimComments} 与分数合成——见 {@code ReportDocAnalysisServiceImpl}。</p>
     */
    public static DocScoreResult parse(String modelOutput) {
        if (modelOutput == null || modelOutput.isBlank()) {
            return null;
        }

        String scoreLine = null;
        for (String line : modelOutput.split("\r?\n")) {
            String t = line.trim();
            for (String tag : SCORE_TAGS) {
                if (t.startsWith(tag)) {
                    scoreLine = t.substring(tag.length()).trim();
                    break;
                }
            }
            if (scoreLine != null) {
                break;
            }
        }
        if (scoreLine == null) {
            return null;
        }

        // 全角竖线归一（中文语境下模型偶尔输出｜）
        scoreLine = scoreLine.replace('｜', '|');

        String[] parts = scoreLine.split("\\|");
        int offset;
        if (parts.length == 6) {
            offset = 1; // 旧形态：总分|五维
        } else if (parts.length == 5) {
            if (parts[0].contains("/")) {
                // 「总分/50」+只有 4 维：缺了一维，判定失败（上层重试）
                return null;
            }
            offset = 0; // 推荐形态：五维|无总分
        } else {
            return null;
        }

        DocScoreResult result = new DocScoreResult();
        double[] dims = new double[DIMENSIONS.length];
        for (int i = 0; i < DIMENSIONS.length; i++) {
            Double v = parseNumber(parts[offset + i]);
            if (v == null) {
                // 任一维解析不出 → 整体判定为不可用，交给上层置 FAILED
                return null;
            }
            dims[i] = clamp(v);
        }
        result.setExpression(dims[0]);
        result.setLogic(dims[1]);
        result.setProfessional(dims[2]);
        result.setAdaptability(dims[3]);
        result.setInnovation(dims[4]);

        // 总分一律由服务端按五维之和重算，忽略模型自报的总分
        double total = 0;
        for (double d : dims) {
            total += d;
        }
        result.setTotalScore(total);

        result.setDimComments(buildDimComments(modelOutput, dims));
        result.setRawOutput(modelOutput);
        return result;
    }

    /**
     * 把「点评调用」输出的逐维点评与已解析的分数合成为落库展示文本：
     * {@code 表达（7分）：……}，一行一维；缺失的维度补占位文案。
     *
     * <p>2026-10-16 实测后点评与评分拆成两次模型调用（3B 撑不住"六行点评+评分行"混合格式），
     * 评分行不再携带点评文字，本方法承接「点评原始输出 × 分数」的合成职责，
     * 供 {@code ReportDocAnalysisServiceImpl} 在解析出分数后调用。</p>
     *
     * @param commentsRaw 点评调用的原始输出（期望五行，以「维度名:」开头）
     * @param dims        五维分数，顺序与 {@link #DIMENSIONS} 一致，长度不足时按缺失补占位
     */
    public static String formatDimComments(String commentsRaw, double[] dims) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < DIMENSIONS.length; i++) {
            String dim = DIMENSIONS[i];
            String comment = extractLineValue(commentsRaw == null ? "" : commentsRaw, dim);
            if (comment == null || comment.isBlank()) {
                comment = "（模型未给出该维度点评）";
            } else {
                // 摘掉可能粘在行尾的「切题:是/否」（模型偶尔不换行，实测抓到过）
                comment = stripTopicJudgment(comment);
            }
            String scoreText = (dims != null && dims.length > i) ? formatScore(dims[i]) : "-";
            if (sb.length() > 0) {
                sb.append('\n');
            }
            // 该维被判为"缺失"时挂 ⚠️，让教师一眼看出这一分没有依据（成绩单要能自证）
            if (isMissingDimension(commentsRaw, dim)) {
                sb.append("⚠️ ");
            }
            sb.append(dim).append('（').append(scoreText).append("分）：").append(comment);
        }
        return sb.toString();
    }

    /**
     * 兼容入口：从同一段输出里既抓分数又抓点评（评分与点评未拆分的旧形态）。
     * 拆分链路下点评由 {@link #formatDimComments} 单独合成。
     */
    private static String buildDimComments(String modelOutput, double[] dims) {
        return formatDimComments(modelOutput, dims);
    }

    /** 抓「维度名:」/「维度名：」开头的行，返回冒号后的内容；没有则 null */
    private static String extractLineValue(String text, String label) {
        for (String line : text.split("\r?\n")) {
            String t = line.trim();
            if (t.startsWith(label + ":") || t.startsWith(label + "：")) {
                return t.substring(label.length() + 1).trim();
            }
        }
        return null;
    }

    /** 抓第一个数字；抓不到返回 null（注意 "-" 单独出现时视为无数字） */
    private static Double parseNumber(String s) {
        if (s == null) {
            return null;
        }
        Matcher m = NUMBER.matcher(s);
        if (!m.find()) {
            return null;
        }
        try {
            return Double.parseDouble(m.group());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 夹到 [0, 10]，防模型给出 12 / -3 这类越界值 */
    private static double clamp(double v) {
        if (v < 0) {
            return 0;
        }
        return Math.min(v, DIM_MAX);
    }

    /** 分数展示：整数不带小数点，小数保留一位（与既有展示习惯一致） */
    public static String formatScore(double v) {
        if (v == Math.floor(v) && !Double.isInfinite(v)) {
            return String.valueOf((long) v);
        }
        return String.format("%.1f", v);
    }

    // ==================================================================
    // 分数守护与等级（2026-10-16 · 成绩单化）
    // ==================================================================

    /**
     * 「该维度缺失」的信号词。点评里出现任意一个，就认为模型自己承认了这一维没有依据。
     *
     * <p><b>为什么要服务端强制压分：</b>实测抓到模型自相矛盾——点评写「（模型未给出该维度点评）」
     * 或「材料未涉及」，分数却照样给 7 分。成绩单上每一分都要说得出依据，
     * 这种「无依据的分」必须由服务端兜住，不能指望 3B 自觉。</p>
     */
    private static final String[] MISSING_MARKERS = {
            "未涉及", "未提及", "未给出", "未展开", "未说明", "未讨论", "未见",
            "缺少", "缺失", "缺乏", "只有笼统", "笼统描述", "无创新", "无明显",
            "没有给出", "没有具体", "材料未",
            // 【2026-10-20】补「没有 X」这一族：3B 在视频点评里高频使用
            // （实测原话「应变: 没有提到应对技术问题或风险的措施」），
            // 原词表只认「未 X」，导致该维既没被压低、也没被口语下限兜住。
            "未提到", "没有提到", "没提到", "未体现", "没有体现", "未交代", "没有交代"
    };

    /** 被判定为"该维缺失"时，分数上限（对齐文字答辩第四档「可怜分」的上界 4 分） */
    private static final double MISSING_DIM_MAX = 4.0;

    /**
     * 切题判断的**全文匹配**模式。
     *
     * <p>不要求行首——模型偶尔不换行、把 {@code 切题:是} 粘在上一行末尾（已实测）。</p>
     */
    private static final Pattern TOPIC_JUDGMENT =
            Pattern.compile("切题性?\\s*[:：]\\s*([是否])");

    /**
     * 材料被判为"与课题无关"时，五维分数上限 = **0**。
     *
     * <p>严格对齐文字答辩五档表的**第五档「完全不相关 → 零分」**：那边是
     * {@code 评分固定 0/50|0|0|0|0|0}，报告侧取同一口径。</p>
     *
     * <p><b>为什么不用"给个低分"这种折中：</b>档位边界是「无效」&lt;5，只要不是 0，
     * 五维各给 2 分加起来就是 6，落到「不合格」档——那等于**把跑题降级成了内容问题**，
     * 与文字答辩的口径不一致。要么判跑题（0），要么不判（保留原分），没有中间态。</p>
     *
     * <p><b>误判风险与兜底</b>：切题判断是模型的一行输出，判错会把合格报告压成 0。
     * 兜底有三层——① 落库文本首行是醒目的「⚠️ 材料内容与课题不符（判为跑题）」，
     * 教师一眼可见；② 教师可重新触发（改报告后指纹变化即允许重跑）；
     * ③ 实测中在课题一致的材料上从未误判（t1/t3/t4 均稳定判「是」）。</p>
     */
    private static final double OFF_TOPIC_DIM_MAX = 0.0;

    /**
     * 按点评内容守护分数：**点评里承认该维缺失的，分数强制压到 0~4**。
     *
     * <p>纯函数，不修改入参。返回新数组，顺序与 {@link #DIMENSIONS} 一致。</p>
     *
     * @param dims        模型给出的五维分数
     * @param commentsRaw 点评调用的原始输出（逐维一行）
     * @return 守护后的五维分数
     */
    public static double[] applyMissingGuards(double[] dims, String commentsRaw) {
        if (dims == null) {
            return new double[0];
        }
        double[] out = new double[dims.length];
        for (int i = 0; i < dims.length; i++) {
            out[i] = dims[i];
            if (i >= DIMENSIONS.length) {
                continue;
            }
            String comment = extractLineValue(commentsRaw == null ? "" : commentsRaw, DIMENSIONS[i]);
            if (comment == null) {
                // 该维点评整行缺失 → 同样视为无依据（模型跳过了这一维）
                out[i] = Math.min(out[i], MISSING_DIM_MAX);
                continue;
            }
            for (String marker : MISSING_MARKERS) {
                if (comment.contains(marker)) {
                    out[i] = Math.min(out[i], MISSING_DIM_MAX);
                    break;
                }
            }
        }
        return out;
    }

    /**
     * 【2026-10-20 · 视频答辩专用】给"缺失维"抬下限。
     *
     * <p><b>为什么只有视频需要：</b>报告是书面材料，一维确实可以完全没写；
     * 而答辩视频是**口语**——学生大概率讲了，只是没讲到点上，或者 whisper 把术语听错了。
     * 按报告口径把这种维度压到 0~4，再用 3B 打分（它往往直接给 0~2），
     * 实测一场 2 分钟视频直接掉到 4/50「无效」，明显过严。</p>
     *
     * <p><b>只抬下限、不抬上限：</b>分数仍然由模型给，这里只把"自认缺失"的维度兜到
     * {@code floor}（默认 3），不会把 0 分硬拉成及格。跑题闸门在调用侧**之后**执行，
     * 所以"讲的是另一个系统"依然归零，不会被这条规则救回来。</p>
     *
     * <p>纯函数，不修改入参。</p>
     *
     * @param dims        已经过 {@link #applyMissingGuards} 的五维分数
     * @param commentsRaw 点评原始输出
     * @param floor       口语场景下缺失维的最低分（建议 3）
     * @return 抬过下限的新数组
     */
    public static double[] applyOralMissingFloor(double[] dims, String commentsRaw, double floor) {
        if (dims == null || dims.length == 0) {
            return new double[0];
        }
        double[] out = dims.clone();
        for (int i = 0; i < out.length && i < DIMENSIONS.length; i++) {
            if (isMissingDimension(commentsRaw, DIMENSIONS[i]) && out[i] < floor) {
                out[i] = floor;
            }
        }
        return out;
    }

    /**
     * 材料是否被判为"与课题无关"（点评调用的第 6 行 {@code 切题:是/否}）。
     *
     * <p><b>为什么报告也要切题判断：</b>2026-10-16 标定实测抓到最严重的失败——
     * 一份通篇讲「校园二手交易平台」的材料，课题是「智能仓储管理系统」，
     * 却拿了 30 分（良好档）。报告侧原本完全没有切题概念，而对齐文字答辩就必须补上
     * 五档表的第五档「完全不相关 → 零分」。</p>
     *
     * <p>方向安全：**没有该行时不判跑题**（老结果、模型漏输出时不会误杀）。</p>
     */
    public static boolean isOffTopic(String commentsRaw) {
        if (commentsRaw == null) {
            return false;
        }
        Matcher m = TOPIC_JUDGMENT.matcher(commentsRaw);
        if (!m.find()) {
            return false;
        }
        return "否".equals(m.group(1));
    }

    /**
     * 摘掉文本里可能粘连的「切题:是/否」片段。
     *
     * <p><b>为什么需要：</b>2026-10-16 端到端实测抓到模型**不换行**——输出成
     * {@code 创新（8分）：集成本地大语言模型…。切题:是}，把切题判断粘在了上一行末尾。
     * 行首匹配的 {@link #extractLineValue} 会因此**静默忽略**该判断，
     * 使跑题闸门失效（只是恰好没误判而已）。故改为正则全文匹配 + 摘除。</p>
     */
    public static String stripTopicJudgment(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String s = TOPIC_JUDGMENT.matcher(text).replaceAll("");
        return s.replaceAll("[。，,；;、\\s]+$", "").trim();
    }

    /**
     * 跑题守护：材料被判定与课题无关时，五维一律压到 0~2。
     *
     * <p>与 {@link #applyMissingGuards} 串联使用：先压缺失维，再压跑题。
     * 纯函数，不修改入参。</p>
     */
    public static double[] applyOffTopicGuard(double[] dims, String commentsRaw) {
        if (dims == null) {
            return new double[0];
        }
        if (!isOffTopic(commentsRaw)) {
            return dims.clone();
        }
        double[] out = new double[dims.length];
        for (int i = 0; i < dims.length; i++) {
            out[i] = Math.min(dims[i], OFF_TOPIC_DIM_MAX);
        }
        return out;
    }

    /** 某维点评是否被判为"缺失"（供展示时加 ⚠️ 标记） */
    public static boolean isMissingDimension(String commentsRaw, String dimension) {
        String comment = extractLineValue(commentsRaw == null ? "" : commentsRaw, dimension);
        if (comment == null) {
            return true;
        }
        for (String marker : MISSING_MARKERS) {
            if (comment.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 等级映射：**与文字答辩共用同一把尺子**（{@code chatController} 的「五档分数表」）。
     *
     * <p>对齐口径（报告语境下的措辞，分数区间与答辩完全一致）：</p>
     * <ul>
     *   <li>{@code ≥35} 优秀 —— 对应第一档「正确回答 → 高分」</li>
     *   <li>{@code 26~34} 良好 —— 对应第二档「小错误 → 正常分」</li>
     *   <li>{@code 15~25} 合格 —— 对应第三档「大错误 → 半分」的下半段</li>
     *   <li>{@code 5~14} 不合格 —— 对应第四档「可怜分」</li>
     *   <li>{@code <5} 无效 —— 对应第五档「完全不相关 → 零分」</li>
     * </ul>
     *
     * <p>注意第二、三档在答辩表里区间重叠（26~34 与 15~30），报告侧取不重叠划分，
     * 边界 26 / 15 与答辩口径一致，保证「同一分数在两边含义相同」。</p>
     */
    public static String gradeLevel(double totalScore) {
        if (totalScore >= 35) {
            return "优秀";
        }
        if (totalScore >= 26) {
            return "良好";
        }
        if (totalScore >= 15) {
            return "合格";
        }
        if (totalScore >= 5) {
            return "不合格";
        }
        return "无效";
    }
}
