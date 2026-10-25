package com.ai_helper.ai_helper.Service.Impl;

import com.ai_helper.ai_helper.Service.MaterialVerdictService;
import com.ai_helper.ai_helper.pojo.dto.MaterialVerdict;
import com.ai_helper.ai_helper.util.MaterialScorePrompts;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 材料裁定的本地实现（2026-10-25）。
 *
 * <p>一次独立的短调用：输入「课题名 + 材料节选」，输出「类型 / 资格 / 理由」三行，
 * 解析交给 {@link MaterialVerdict#parse}。刻意不并入五维点评——点评提示词一旦变大，
 * 3B 就会用技术名词代替"这份文件在讲什么"的判断。</p>
 *
 * <p><b>与报告/视频链路的关系</b>：本类不依赖任何一条业务链路，也不写库；
 * 是否落库、命中后怎么处置由调用方决定（报告链路：不出分 + 标记待复审）。</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MaterialVerdictServiceImpl implements MaterialVerdictService {

    private final ChatClient chatClient;

    @Value("${spring.ai.openai.chat.options.model:unknown}")
    private String ollamaModelName;

    /** 送裁定的材料上限：只判断"在讲什么"，保留头尾已经足够（报告结论常在尾部） */
    @Value("${app.material-verdict.max-chars:6000}")
    private int maxChars;

    /** 总开关。关掉后一律按合格放行，等于回到"没有裁定"的行为 */
    @Value("${app.material-verdict.enabled:true}")
    private boolean enabled;

    @Override
    public MaterialVerdict judge(String topicName, String material) {
        if (!enabled) {
            log.info("材料裁定开关已关闭（app.material-verdict.enabled=false），按合格放行");
            return MaterialVerdict.ok("", "裁定开关关闭");
        }
        if (material == null || material.isBlank()) {
            // 空材料在调用方就该被拦（报告抽取为空会直接失败），这里返回 UNKNOWN 更安全
            return MaterialVerdict.unknown("材料为空");
        }

        try {
            String user = "【课题】" + (topicName == null || topicName.isBlank() ? "（未提供）" : topicName)
                    + "\n【文件·开始】\n" + headTail(material, maxChars) + "\n【文件·结束】\n"
                    + "按三行输出：类型 / 资格 / 理由。";
            String out = chatClient.prompt()
                    .system(MaterialScorePrompts.MATERIAL_VERDICT_SYSTEM)
                    .user(user)
                    .options(OpenAiChatOptions.builder()
                            .model(ollamaModelName)
                            .temperature(0.0)
                            .maxTokens(200)
                            .build())
                    .call().content();

            MaterialVerdict verdict = MaterialVerdict.parse(out);
            if (verdict.kind() == MaterialVerdict.Kind.UNKNOWN) {
                // 记下原文片段，便于排查是模型没按格式输出还是 prompt 需要调整
                log.warn("材料裁定输出不可解析，按合格放行 - 原文: {}",
                        out == null ? "（空）" : out.replaceAll("\\s+", " "));
            } else {
                log.info("材料裁定 - 类型: {}, 资格: {}, 理由: {}",
                        verdict.materialType(), verdict.label(), verdict.reason());
            }
            return verdict;
        } catch (Exception e) {
            log.warn("材料裁定调用失败（按合格放行）- 原因: {}", e.getMessage());
            return MaterialVerdict.unknown(e.getMessage());
        }
    }

    /** 保头保尾截断：中间省略，避免把长报告的结论章节整段切掉 */
    private static String headTail(String text, int max) {
        if (text == null || text.length() <= max || max <= 0) {
            return text;
        }
        int head = (int) (max * 0.6);
        int tail = max - head;
        return text.substring(0, head) + "\n……（中间省略）……\n"
                + text.substring(text.length() - tail);
    }
}
