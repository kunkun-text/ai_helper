package com.ai_helper.ai_helper.pojo.dto;

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
        UNKNOWN
    }

    private static final String PREFIX_TYPE = "类型";
    private static final String PREFIX_VERDICT = "资格";
    private static final String PREFIX_REASON = "理由";

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
     * 解析模型输出：
     * <pre>
     * 类型:个人简历
     * 资格:非答辩材料
     * 理由:讲的是求职者本人，与仓储系统无关
     * </pre>
     *
     * <p>缺「资格」行或取值不在四类之内 → {@link Kind#UNKNOWN}（放行）。</p>
     */
    public static MaterialVerdict parse(String modelOutput) {
        if (modelOutput == null || modelOutput.isBlank()) {
            return unknown(modelOutput);
        }
        String verdictLine = lineValue(modelOutput, PREFIX_VERDICT);
        if (verdictLine == null) {
            return unknown(modelOutput);
        }
        return new MaterialVerdict(classify(verdictLine),
                lineValue(modelOutput, PREFIX_TYPE),
                lineValue(modelOutput, PREFIX_REASON),
                modelOutput);
    }

    /**
     * 把模型给的资格短语映射成枚举。
     *
     * <p>只做**包含匹配**，容忍模型补字（"属于非答辩材料"）。
     * 判断顺序刻意固定：先「内容不足」，再「非答辩」，最后「跑题」——
     * 因为"是答辩材料但课题不符"里既有"答辩"也有"不符"，反过来匹配会误判。</p>
     */
    private static Kind classify(String verdict) {
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
        if (s.contains("跑题") || s.contains("不符") || s.contains("不相关")) {
            return Kind.OFF_TOPIC;
        }
        if (s.contains("通过")) {
            return Kind.OK;
        }
        return Kind.UNKNOWN;
    }

    /** 取「前缀:值」行（兼容半角/全角冒号），没有返回 null */
    private static String lineValue(String text, String prefix) {
        for (String line : text.split("\r?\n")) {
            String t = line.trim();
            if (t.startsWith(prefix)) {
                int idx = Math.max(t.indexOf('：'), t.indexOf(':'));
                if (idx >= 0 && idx + 1 <= t.length()) {
                    return t.substring(idx + 1).trim();
                }
            }
        }
        return null;
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
            default:
                return "未裁定";
        }
    }
}
