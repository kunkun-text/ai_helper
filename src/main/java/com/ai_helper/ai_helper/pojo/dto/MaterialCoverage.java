package com.ai_helper.ai_helper.pojo.dto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 【2026-10-27】「材料是否覆盖本课题预设题」的判定结果。
 *
 * <p><b>为什么用预设题而不是又一个词表/又一个"像不像报告"的判断：</b>
 * 预设题是课题本身要求阐释的内容，**任何课题都自带这组题**（A 课题、B 课题、C 课题通用，
 * 代码里不出现任何具体学科词）。一份材料如果一道预设题都没讲，就不是本课题的材料——
 * 与它"看起来像不像报告"无关。2026-10-27 的 {@code defenseId=377} 就是反例：
 * 一份思政调研作业对「成绩单验证课题」的预设题 0 命中，却因为"像报告"拿了 39/50。</p>
 *
 * <p><b>判定口径（用户 2026-10-27 拍板）：只有 0 命中才拦</b>；命中 1 道及以上即视为
 * 方向相关，交正常五维评分（写得差是质量问题，不是资格问题）。</p>
 *
 * <p><b>解析容错：</b>模型按 {@code 题号:是|依据} 逐行输出，但 3B 会写成
 * {@code 1. 是（依据）}、{@code 题1：否}、甚至漏行。这里只认"题号 + 是/否"，
 * 依据可有可无；漏行的题记为未解析，由调用方决定是否上云端复核。</p>
 */
public final class MaterialCoverage {

    /** 题号 + 是/否 + 可选依据（| 或 ，分隔） */
    private static final Pattern LINE = Pattern.compile("^\\s*(?:题|Q|q)?\\s*(\\d+)\\s*[:：.、)）]\\s*(.+)$");

    /** 单题判定 */
    public static final class Item {
        private final String question;
        private final boolean covered;
        private final String evidence;
        private final boolean parsed;

        Item(String question, boolean covered, String evidence, boolean parsed) {
            this.question = question == null ? "" : question;
            this.covered = covered;
            this.evidence = evidence == null ? "" : evidence;
            this.parsed = parsed;
        }

        public String getQuestion() {
            return question;
        }

        public boolean isCovered() {
            return covered;
        }

        public String getEvidence() {
            return evidence;
        }

        /** 模型是否给出了这道题的可识别结论（否/是）；漏行 = false */
        public boolean isParsed() {
            return parsed;
        }
    }

    private final List<Item> items;
    private final boolean applicable;
    private final boolean unknown;
    private final String raw;

    private MaterialCoverage(List<Item> items, boolean applicable, boolean unknown, String raw) {
        this.items = items == null ? Collections.emptyList() : items;
        this.applicable = applicable;
        this.unknown = unknown;
        this.raw = raw == null ? "" : raw;
    }

    /** 该课题没有预设题：本判定不适用（调用方回落到"课题名+描述"的资格裁定） */
    public static MaterialCoverage notApplicable() {
        return new MaterialCoverage(Collections.emptyList(), false, false, "");
    }

    /** 无法判定（模型漏行 + 云端复核也不可用/失败）：按用户口径 → 待复审、不出分 */
    public static MaterialCoverage unknown(String raw, List<String> questions) {
        List<Item> items = new ArrayList<>();
        if (questions != null) {
            for (String q : questions) {
                items.add(new Item(q, false, "", false));
            }
        }
        return new MaterialCoverage(items, true, true, raw);
    }

    /**
     * 解析模型输出。
     *
     * @param modelOutput 模型原文
     * @param questions   该课题的预设题（顺序即题号 1..n）
     * @return 命中统计；题号为 1..n，缺行记为未解析
     */
    public static MaterialCoverage parse(String modelOutput, List<String> questions) {
        if (questions == null || questions.isEmpty()) {
            return notApplicable();
        }
        List<Item> items = new ArrayList<>();
        String text = modelOutput == null ? "" : modelOutput;

        for (int i = 0; i < questions.size(); i++) {
            String q = questions.get(i);
            Item hit = matchLine(text, i + 1);
            items.add(hit == null ? new Item(q, false, "", false) : new Item(q, hit.covered, hit.evidence, true));
        }
        return new MaterialCoverage(items, true, false, text);
    }

    /** 找第 n 题的判定行；找不到或判不出是/否 → null */
    private static Item matchLine(String text, int no) {
        for (String line : text.split("\r?\n")) {
            Matcher m = LINE.matcher(line.trim());
            if (!m.matches()) {
                continue;
            }
            if (Integer.parseInt(m.group(1)) != no) {
                continue;
            }
            String rest = m.group(2).trim();
            String[] parts = rest.split("[|｜，,；;]", 2);
            String head = parts[0].trim();
            String evidence = parts.length > 1 ? parts[1].trim() : "";

            Boolean covered = decide(head);
            if (covered == null) {
                return null;
            }
            return new Item("", covered, evidence, true);
        }
        return null;
    }

    /**
     * 判断"是/否"：先判否定（"未提及""没有""未覆盖"里也含"提及/覆盖"，
     * 顺序反了会把否定读成肯定）。
     */
    private static Boolean decide(String head) {
        if (head.contains("未") || head.contains("没有") || head.contains("不含") || head.contains("不涉及")
                || head.startsWith("否") || head.startsWith("无") || head.startsWith("不")) {
            return Boolean.FALSE;
        }
        if (head.contains("是") || head.contains("有") || head.contains("覆盖")
                || head.contains("提及") || head.contains("包含") || head.contains("阐述")) {
            return Boolean.TRUE;
        }
        return null;
    }

    public List<Item> getItems() {
        return items;
    }

    /** 该课题有没有预设题；false 时本判定整体不参与 */
    public boolean isApplicable() {
        return applicable;
    }

    /** 是否"判不准"（漏行且需要人/云端再看） */
    public boolean isUnknown() {
        return unknown;
    }

    public String getRaw() {
        return raw;
    }

    public int getTotal() {
        return items.size();
    }

    public int getHitCount() {
        int n = 0;
        for (Item it : items) {
            if (it.parsed && it.covered) {
                n++;
            }
        }
        return n;
    }

    /** 有未解析的题（模型漏行）：0 命中时需要云端复核才能下结论 */
    public boolean hasUnparsed() {
        for (Item it : items) {
            if (!it.parsed) {
                return true;
            }
        }
        return false;
    }

    /**
     * 是否应拦截（不出分、标待复审）。
     *
     * <p>口径：**0 命中才拦**，且必须是"所有题都解析出来、明确都是否"；
     * 漏行时不算命中也不直接拦，交给云端复核 / 待复审。</p>
     */
    public boolean isRejected() {
        return applicable && !unknown && !hasUnparsed() && getHitCount() == 0;
    }

    /** 逐题明细（写进成绩单说明，教师可核） */
    public String summary() {
        if (!applicable) {
            return "";
        }
        if (getTotal() == 0) {
            // 课题没有预设题：方向判定无从下手，按用户口径判异常（不出分、待复审）
            return "课题未配置预设题：无法核对材料是否讲本课题内容。"
                    + "请在教师端给该课题补充 3~5 道预设题后重新生成评析。";
        }
        StringBuilder b = new StringBuilder();
        b.append("预设题覆盖：").append(getHitCount()).append("/").append(getTotal()).append(" 道有对应内容");
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            String mark = it.parsed ? (it.covered ? "有" : "未提及") : "判不出";
            b.append("\n  ").append(i + 1).append(". ").append(clip(it.question, 40)).append(" → ").append(mark);
            if (!it.evidence.isEmpty()) {
                b.append("（").append(clip(it.evidence, 30)).append("）");
            }
        }
        return b.toString();
    }

    private static String clip(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
