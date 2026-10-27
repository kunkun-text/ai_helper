package com.ai_helper.ai_helper.pojo.dto;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 【2026-10-25】通用「材料资格裁定」结果。
 *
 * <p><b>为什么要有这个类，而不是又一个词表：</b>报告评析原先靠五维点评里的
 * {@code 切题:是/否} 判断材料对不对，3B 看到技术名词就放行；随后补过一版
 * 「简历特征词表」，但那只是对**一种文件**的特殊对待——换成合同、说明书、小说、
 * 课程作业、实验报告，同样会漏。材料类型是开放集合，不可能穷举。</p>
 *
 * <p>所以改成：一次独立的模型裁定，输出「类型 / 资格 / 理由」三行，
 * 服务端只认这几个**语义分类**，不认任何具体文件名或行业词：
 * <ul>
 *   <li>{@link Kind#OK} —— 本课题的答辩材料（体裁不限：论文、报告、PPT 大纲、口述稿都行）</li>
 *   <li>{@link Kind#OFF_TOPIC} —— 是答辩/论文类材料，但讲的是别的课题</li>
 *   <li>{@link Kind#NON_MATERIAL} —— 与答辩无关的文件（简历/合同/说明书/小说/笔记…）</li>
 *   <li>{@link Kind#INSUFFICIENT} —— 几乎没有可评内容（空白/只有封面目录/通篇图片）</li>
 *   <li>{@link Kind#UNKNOWN} —— 模型没给出可识别结论，<b>调用方必须按 OK 放行</b></li>
 * </ul>
 *
 * <p><b>复用性：</b>本类与 {@code MaterialVerdictService} 不依赖报告链路——
 * 视频转写文本、未来任何「学生提交的东西」都可以先过这一道裁定，
 * 只是材料来源不同，判定口径完全一致。</p>
 */
public final class MaterialVerdict {

    /** 裁定类别。数值顺序不代表优先级，分类见 {@link #parse}。 */
    public enum Kind {
        OK,
        OFF_TOPIC,
        NON_MATERIAL,
        INSUFFICIENT,
        /** 【2026-10-27】判不准（模型漏行/调用失败）：按用户口径 → 待复审、不出分 */
        UNSURE,
        UNKNOWN
    }

    private static final String PREFIX_TYPE = "类型";
    private static final String PREFIX_VERDICT = "资格";
    private static final String PREFIX_REASON = "理由";

    /**
     * 「与课题无关」类信号的通用写法。
     *
     * <p>不是学科词表——只描述"方向不符"这一语义。模型会写「与课题无关」「与本课题不符」
     * 「跟所研究课题不同」，固定字面量列不全；同时必须排除「与答辩无关」（那是 NON_MATERIAL，
     * 调用前已提前返回）。</p>
     */
    private static final Pattern OFF_TOPIC_HINT = Pattern.compile(
            "(与|跟)[^，。；;\\n]{0,8}(课题|本题)[^，。；;\\n]{0,4}(无关|不符|不同)"
                    + "|并非本课题|不是本课题|另一个课题|与要求不符|文不对题|离题");

    private final Kind kind;
    private final String materialType;
    private final String reason;
    private final String raw;

    private MaterialVerdict(Kind kind, String materialType, String reason, String raw) {
        this.kind = kind;
        this.materialType = materialType == null ? "" : materialType;
        this.reason = reason == null ? "" : reason;
        this.raw = raw == null ? "" : raw;
    }

    /** 裁定为合格（内部/外部都需要一个明确的"放行"值） */
    public static MaterialVerdict ok(String materialType, String reason) {
        return new MaterialVerdict(Kind.OK, materialType, reason, "");
    }

    /**
     * 没有可用结论（未启用 / 调用失败 / 输出不可解析）。
     *
     * <p><b>方向安全：</b>返回 UNKNOWN 而不是"不合格"——预检没跑成
     * 不能把合格报告压成不合格，调用方看到 UNKNOWN 必须继续正常评分。</p>
     */
    public static MaterialVerdict unknown(String raw) {
        return new MaterialVerdict(Kind.UNKNOWN, "", "", raw);
    }

    /**
     * 【2026-10-27】方向不符（预设题 0 命中）：与 `跑题` 同一处置（不出分、待复审），
     * 但理由由调用方给（带上"几道题 0 命中"），便于教师看懂依据。
     */
    public static MaterialVerdict offTopic(String materialType, String reason) {
        return new MaterialVerdict(Kind.OFF_TOPIC, materialType, reason, "");
    }

    /**
     * 【2026-10-27】判不准（模型漏行/调用失败）：**不出分**、标待复审。
     *
     * <p>与 {@link #unknown} 的区别：UNKNOWN 表示"本环节没有结论，调用方自己决定"，
     * UNSURE 表示"服务端已决定按待复审处理"。用户 2026-10-27 口径：宁可漏评，不给错分。</p>
     */
    public static MaterialVerdict unsure(String materialType, String reason) {
        return new MaterialVerdict(Kind.UNSURE, materialType, reason, "");
    }

    /**
     * 解析模型输出，**容错优先**。
     *
     * <p>3B 实测会写成一行、用斜杠分隔、还丢掉「类型/资格/理由」标签
     * （2026-10-27，{@code defenseId=377} 原文）：
     * <pre>答辩报告 / 内容不足 / 通篇内容与课题无关，讲的是山区基层医疗卫生保障调查，而非成绩单验证</pre>
     * 旧实现只按换行找「资格:」前缀 → 找不到就返回 UNKNOWN → 调用方按合格放行，
     * 于是这份与课题无关的材料拿了 39/50「优秀」。现在按以下顺序解析：
     * <ol>
     *   <li>先按行切；只有一行时再按 {@code / | ｜ 、} 切段（逗号不切，理由里常有）；</li>
     *   <li>段内有「类型/资格/理由」标签就按标签取值（同一行多个标签也能取）；</li>
     *   <li>没有标签时逐段做关键字分类，任一段命中即采用；第一段当类型、最后一段当理由；</li>
     *   <li>正文出现「与课题无关/而非本课题/另一个课题」等强信号时，把「通过 / 内容不足」
     *       升级为跑题——模型其实判对了方向，只是把资格写成了"内容不足"。</li>
     * </ol>
     *
     * <p>仍然解析不出结论 → {@link Kind#UNKNOWN}，由调用方按既定口径处理（待复审、不出分）。</p>
     */
    public static MaterialVerdict parse(String modelOutput) {
        if (modelOutput == null || modelOutput.isBlank()) {
            return unknown(modelOutput);
        }
        List<String> segments = segments(modelOutput);

        String type = pick(segments, PREFIX_TYPE);
        String verdictValue = pick(segments, PREFIX_VERDICT);
        String reason = pick(segments, PREFIX_REASON);

        Kind kind = verdictValue == null ? Kind.UNKNOWN : classify(verdictValue);
        if (kind == Kind.UNKNOWN) {
            // 标签缺失 / 标签值不可识别：按段内容兜底（命中段 = 资格，首段 = 类型，末段 = 理由）
            int verdictIdx = -1;
            for (int i = 0; i < segments.size(); i++) {
                Kind guess = classify(segments.get(i));
                if (guess != Kind.UNKNOWN) {
                    kind = guess;
                    verdictIdx = i;
                    break;
                }
            }
            if (kind != Kind.UNKNOWN) {
                int last = segments.size() - 1;
                if (type == null && verdictIdx > 0) {
                    type = segments.get(0);
                }
                if (reason == null && last != verdictIdx) {
                    reason = segments.get(last);
                }
            }
        }
        kind = upgradeOffTopic(kind, modelOutput);
        return new MaterialVerdict(kind, nz(type), nz(reason), modelOutput);
    }

    /**
     * 把模型给的资格短语映射成枚举。
     *
     * <p>只做**包含匹配**，容忍模型补字（"属于非答辩材料"）。
     * 判断顺序刻意固定：先「内容不足」，再「非答辩」，最后「跑题」——
     * 因为"是答辩材料但课题不符"里既有"答辩"也有"不符"，反过来匹配会误判。</p>
     */
    private static Kind classify(String verdict) {
        if (verdict == null || verdict.isBlank()) {
            return Kind.UNKNOWN;
        }
        String s = verdict.replaceAll("\\s", "");
        // "不通过"必须排在"通过"前面，否则会被 contains("通过") 吃掉
        if (s.contains("不通过")) {
            return Kind.NON_MATERIAL;
        }
        if (s.contains("内容不足") || s.contains("无实质") || s.contains("空白")) {
            return Kind.INSUFFICIENT;
        }
        if (s.contains("非答辩") || s.contains("不是答辩") || s.contains("与答辩无关")) {
            return Kind.NON_MATERIAL;
        }
        if (s.contains("跑题") || s.contains("不符") || s.contains("不相关") || s.contains("无关")) {
            return Kind.OFF_TOPIC;
        }
        if (s.contains("通过")) {
            return Kind.OK;
        }
        return Kind.UNKNOWN;
    }

    /**
     * 「资格写成内容不足、理由却写着与课题无关」的升级规则。
     *
     * <p>只升级「通过 / 内容不足 / 未识别」，不碰「非答辩材料」——
     * 后者已经是更准确的结论，没必要改成跑题。</p>
     */
    private static Kind upgradeOffTopic(Kind kind, String text) {
        if (kind == Kind.NON_MATERIAL || kind == Kind.OFF_TOPIC) {
            return kind;
        }
        String t = text.replaceAll("\\s", "");
        // 用正则而不是固定词表：模型会写「与本课题无关」「跟所研究课题不符」，
        // 但不会写「与答辩无关」（那是 NON_MATERIAL，前面已提前返回）。
        if (OFF_TOPIC_HINT.matcher(t).find()) {
            return Kind.OFF_TOPIC;
        }
        return kind;
    }

    /** 切段：优先按行；只有一行（或全空）时按 / | ｜ 、 切 */
    private static List<String> segments(String text) {
        List<String> out = new ArrayList<>();
        for (String line : text.split("\r?\n")) {
            if (!line.isBlank()) {
                out.add(line.trim());
            }
        }
        if (out.size() <= 1) {
            out.clear();
            for (String seg : text.split("[/|｜、]")) {
                String t = seg.trim();
                if (!t.isEmpty()) {
                    out.add(t);
                }
            }
        }
        return out;
    }

    /** 在段里找「前缀:值」（兼容全角冒号与同一行多个标签） */
    private static String pick(List<String> segments, String prefix) {
        for (String seg : segments) {
            int idx = seg.indexOf(prefix);
            if (idx < 0) {
                continue;
            }
            int colon = Math.max(seg.indexOf('：', idx + prefix.length()),
                    seg.indexOf(':', idx + prefix.length()));
            if (colon < 0) {
                continue;
            }
            String value = seg.substring(colon + 1).trim();
            value = cutAtNextLabel(value);
            if (!value.isEmpty()) {
                return value;
            }
        }
        return null;
    }

    /** 同一行里还有下一个标签时截断，例如「类型:答辩报告 资格:内容不足」 */
    private static String cutAtNextLabel(String value) {
        int cut = value.length();
        for (String label : new String[]{PREFIX_TYPE, PREFIX_VERDICT, PREFIX_REASON}) {
            int i = value.indexOf(label);
            if (i > 0) {
                int colon = Math.max(value.indexOf('：', i + label.length()),
                        value.indexOf(':', i + label.length()));
                if (colon == i + label.length()) {
                    cut = Math.min(cut, i);
                }
            }
        }
        return value.substring(0, cut).trim();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    public Kind kind() {
        return kind;
    }

    /** 模型判定的材料类型（答辩报告 / 个人简历 / 合同 …）；可能为空 */
    public String materialType() {
        return materialType;
    }

    /** 裁定理由（约 40 字）；可能为空 */
    public String reason() {
        return reason;
    }

    public String raw() {
        return raw;
    }

    /** 是否应当拦截（不进五维评分、不出分）。UNKNOWN 永远不拦截。 */
    public boolean isRejected() {
        return kind != Kind.OK && kind != Kind.UNKNOWN;
    }

    /**
     * 落库值（{@code defense_records.material_verdict}）。
     * UNKNOWN 返回 {@code null}：没有结论就不能断言"未通过"，历史行为保持"未裁定"。
     */
    public String code() {
        switch (kind) {
            case OK:
                return "ok";
            case OFF_TOPIC:
                return "suspect_offtopic";
            case NON_MATERIAL:
                return "suspect_nonmaterial";
            case INSUFFICIENT:
                return "insufficient";
            case UNSURE:
                return "unclear";
            default:
                return null;
        }
    }

    /** 中文短标签（成绩单/日志/界面用） */
    public String label() {
        switch (kind) {
            case OK:
                return "合格";
            case OFF_TOPIC:
                return "疑似跑题";
            case NON_MATERIAL:
                return "非答辩材料";
            case INSUFFICIENT:
                return "内容不足";
            case UNSURE:
                return "无法判定";
            default:
                return "未裁定";
        }
    }
}
