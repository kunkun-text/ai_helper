package com.ai_helper.ai_helper.util;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 【N61 · 2026-10-14】违规话术过滤器（输入侧 + 输出侧护栏）。
 *
 * <p><b>为什么做成外置词表</b>：词库会持续增长，写死在 Java 常量里每次都要改代码、重新编译。
 * 参考开源中文敏感词库 {@code houbb/sensitive-word} 的做法——<b>词库与主项目解耦</b>，
 * 按「词|标签」组织、支持分类分级与白名单，本类只负责加载与匹配。</p>
 *
 * <p><b>匹配口径</b>：</p>
 * <ol>
 *   <li>归一化后匹配（去空白/标点/符号 + 转小写），因此「忽 略 以 上 指 令！！」同样命中；</li>
 *   <li><b>白名单优先于黑名单</b>：命中 {@code allow} 标签的文本整条放行；</li>
 *   <li>只做整短语 {@code contains} 匹配，不做分词与模糊匹配——答辩场景下宁可漏判不可误伤，
 *       误判违规会把正常作答打成 0 分。</li>
 * </ol>
 *
 * <p><b>容错</b>：词表文件缺失或读取失败时按「空词表」处理（{@link #check} 恒返回空集合），
 * 护栏整体失效但答辩流程完全不受影响。</p>
 *
 * @see com.ai_helper.ai_helper.Controller.chatController 输入侧闸门与输出侧点评校验的调用方
 */
@Component
public class ForbiddenWordFilter {

    private static final Logger log = LoggerFactory.getLogger(ForbiddenWordFilter.class);

    /** 词表文件（classpath 根目录） */
    private static final String WORDS_FILE = "forbidden-words.txt";

    /** 白名单标签（优先级最高） */
    public static final String TAG_ALLOW = "allow";

    /** 提示注入 / 越狱 / 角色劫持 / 改分指令 */
    public static final String TAG_JAILBREAK = "jailbreak";

    /** 索要模型自身规则与提示词 */
    public static final String TAG_META = "meta";

    /**
     * 短语最短长度：过短的词（如 2 字）几乎必然误伤，直接忽略。
     * 一个中文短语 4 字起才具备"无歧义"的特征度。
     */
    private static final int MIN_PHRASE_LENGTH = 4;

    /**
     * 词表里允许出现的标签集合。
     * 用途：解析时据此判断一行里「哪一侧是标签」，从而同时兼容 `词|标签` 与 `标签|词` 两种书写顺序
     * （首版词表文件正是把顺序写反了，靠单元测试才发现）。
     */
    private static final Set<String> KNOWN_TAGS = Set.of(TAG_ALLOW, TAG_JAILBREAK, TAG_META);

    /** 标签 → 归一化后的词列表（保持文件顺序，便于排查） */
    private final Map<String, List<String>> wordsByTag = new LinkedHashMap<>();

    /** 全部黑名单词（用于输出侧整体检查） */
    private final List<String> blacklist = new ArrayList<>();

    /** 白名单词 */
    private final List<String> allowlist = new ArrayList<>();

    @PostConstruct
    void load() {
        ClassPathResource resource = new ClassPathResource(WORDS_FILE);
        try (InputStream in = resource.getInputStream()) {
            String content = StreamUtils.copyToString(in, StandardCharsets.UTF_8);
            parse(content);
            log.info("违规话术词表加载完成 - 黑名单 {} 条（{} 个标签），白名单 {} 条",
                    blacklist.size(), wordsByTag.size(), allowlist.size());
        } catch (Exception e) {
            log.warn("违规话术词表加载失败（{}），输入/输出护栏降级为不拦截: {}", WORDS_FILE, e.getMessage());
        }
    }

    /** 解析词表：忽略注释与空行，按 `词|标签` 拆分；非法行只记 warn，不中断加载 */
    private void parse(String content) {
        if (content == null || content.isEmpty()) {
            return;
        }
        for (String rawLine : content.split("\\R")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int sep = line.indexOf('|');
            if (sep <= 0 || sep == line.length() - 1) {
                log.warn("违规话术词表存在非法行（缺少 `词|标签` 格式），已忽略: {}", line);
                continue;
            }
            String left = normalize(line.substring(0, sep));
            String right = line.substring(sep + 1).trim().toLowerCase();
            // 兼容 `词|标签`（推荐）与 `标签|词` 两种顺序：以「哪一侧是已知标签」判定；
            // 两侧都不是已知标签时按 `词|标签` 解析并告警，便于尽快发现写错的词表。
            String phrase;
            String tag;
            if (KNOWN_TAGS.contains(right)) {
                phrase = left;
                tag = right;
            } else if (KNOWN_TAGS.contains(left)) {
                phrase = normalize(right);
                tag = left;
            } else {
                phrase = left;
                tag = right;
                log.warn("违规话术词表存在未知标签「{}」，该行仍按 `词|标签` 解析: {}", tag, line);
            }
            if (phrase.length() < MIN_PHRASE_LENGTH || tag.isEmpty()) {
                // 过短的词不收（误伤风险高），静默跳过
                continue;
            }
            if (TAG_ALLOW.equals(tag)) {
                allowlist.add(phrase);
            } else {
                wordsByTag.computeIfAbsent(tag, k -> new ArrayList<>()).add(phrase);
                blacklist.add(phrase);
            }
        }
    }

    /**
     * 检查文本命中的全部标签。
     *
     * @return 命中的标签集合；未命中返回空集合。命中白名单时**恒返回空集合**（白名单优先）。
     */
    public Set<String> check(String text) {
        if (text == null || text.isEmpty() || blacklist.isEmpty()) {
            return Collections.emptySet();
        }
        String normalized = normalize(text);
        if (normalized.isEmpty()) {
            return Collections.emptySet();
        }
        for (String allow : allowlist) {
            if (normalized.contains(allow)) {
                return Collections.emptySet();
            }
        }
        Set<String> hitTags = new LinkedHashSet<>();
        for (Map.Entry<String, List<String>> entry : wordsByTag.entrySet()) {
            for (String phrase : entry.getValue()) {
                if (normalized.contains(phrase)) {
                    hitTags.add(entry.getKey());
                    break;
                }
            }
        }
        return hitTags;
    }

    /** 是否命中指定标签（白名单优先，命中白名单恒返回 false） */
    public boolean hit(String text, String tag) {
        return check(text).contains(tag);
    }

    /** 词表是否已成功加载（可用于自检/诊断） */
    public boolean ready() {
        return !blacklist.isEmpty();
    }

    /** 归一化：去空白、标点与符号，转小写（与词表入库时的口径保持一致） */
    private static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.toLowerCase().replaceAll("[\\s\\p{P}\\p{S}]+", "");
    }
}
