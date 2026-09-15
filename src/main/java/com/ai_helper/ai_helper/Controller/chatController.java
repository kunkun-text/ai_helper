package com.ai_helper.ai_helper.Controller;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ai_helper.ai_helper.Service.DefenseRecordsService;
import com.ai_helper.ai_helper.Service.DefenseTopicsService;
import com.ai_helper.ai_helper.Service.ScorePersistenceService;
import com.ai_helper.ai_helper.mapper.DefenseAnswersMapper;
import com.ai_helper.ai_helper.mapper.DefenseScoreRecordMapper;
import com.ai_helper.ai_helper.mapper.DefenseStudentQuestionsMapper;
import com.ai_helper.ai_helper.pojo.dto.AiAnalysis;
import com.ai_helper.ai_helper.pojo.dto.TopicDto;
import com.ai_helper.ai_helper.pojo.entity.DefenseAnswers;
import com.ai_helper.ai_helper.pojo.entity.DefenseQuestions;
import com.ai_helper.ai_helper.pojo.entity.DefenseScoreRecord;
import com.ai_helper.ai_helper.pojo.entity.DefenseStudentQuestions;
import com.ai_helper.ai_helper.result.Result;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api")
@Slf4j
public class chatController {

    @Autowired
    private ChatClient chatClient;

    @Autowired
    private DefenseTopicsService defenseTopicsService;

    @Autowired
    private DefenseRecordsService defenseRecordsService;

    @Autowired
    private ChatMemory chatMemory;

    @Autowired
    private DefenseAnswersMapper defenseAnswersMapper;

    @Autowired
    private DefenseStudentQuestionsMapper defenseStudentQuestionsMapper;

    @Autowired
    private ScorePersistenceService scorePersistenceService;

    @Autowired
    private DefenseScoreRecordMapper scoreRecordMapper;

    @Value("${spring.ai.openai.chat.options.model:unknown}")
    private String ollamaModelName;

    /** 每轮对话保留的最大消息数（6轮 × 2条 = 12条） */
    private static final int MAX_HISTORY_MESSAGES = 12;

    /** 标准答案截断长度 */
    private static final int ANSWER_TRUNCATE_LENGTH = 50;

    /** 额外问题上限（AI 追问次数，5 预设题 + 5 追问 = 共 10 轮） */
    private static final int EXTRA_QUESTION_LIMIT = 5;

    // ==================== /api/chat 核心接口 ====================

    @RequestMapping(value = "/chat", produces = "text/html;charset=utf-8")
    public Flux<String> chat(@RequestParam(required = false, defaultValue = "") String prompt,
                            @RequestParam(required = false) Integer topicId,
                            @RequestParam(required = false) String sessionId,
                            @RequestParam(required = false) String userId) {

        log.info("收到聊天请求 - prompt长度: {}, topicId: {}, sessionId: {}, userId: {}",
                prompt != null ? prompt.length() : 0, topicId, sessionId, userId);

        if (sessionId == null || sessionId.trim().isEmpty()) {
            if (userId != null && !userId.isEmpty()) {
                sessionId = "user_" + userId + "_topic_" + topicId;
            } else {
                sessionId = "topic_" + topicId + "_" + System.currentTimeMillis();
            }
        }

        final String finalSessionId = sessionId;
        final String finalUserInput = prompt != null ? prompt : "";

        if (topicId != null) {
            try {
                Result<Object> topicResult = defenseTopicsService.getTopicById(topicId);

                if (topicResult.getCode() != 1 || topicResult.getData() == null) {
                    log.warn("查询主题信息失败，topicId: {}, code: {}, msg: {}",
                            topicId, topicResult.getCode(), topicResult.getMsg());
                    return handleFallback(userId, topicId, finalUserInput, "未找到相关主题信息", finalSessionId, finalUserInput);
                }

                Result<List<DefenseQuestions>> questionResult = defenseTopicsService.getDefenseQuestionById(topicId);

                List<Integer> existingQuestionIds = new ArrayList<>();
                int existingQuestionCount = 0;

                if (questionResult.getCode() == 1 && questionResult.getData() != null) {
                    List<DefenseQuestions> questions = questionResult.getData();
                    existingQuestionCount = questions.size();

                    for (DefenseQuestions question : questions) {
                        existingQuestionIds.add(question.getQuestionId());
                    }

                    log.info("数据库中已有的问题ID列表: {}, 问题数量: {}", existingQuestionIds, existingQuestionCount);

                    if (!questions.isEmpty()) {
                        log.info("找到 {} 个答辩题目，进入提问模式", questions.size());

                        int extraQuestionLimit = EXTRA_QUESTION_LIMIT;

                        // --- 权威轮次计数（2026-09-15 修复）---
                        // Redis 会话记忆被 trimChatMemory 物理截断到 12 条，旧实现按"历史中的 assistant 消息数"
                        // 计轮次会封顶在 6（实测轮次号 1,2,1,4,5,6,6,6…，收尾判断 followUpDone/hardCapReached 永远无法触发）。
                        // 现改按 defense_score_record 落库行数计数：每次作答（含放弃0分）恰落一行，天然免疫记忆裁剪；
                        // 异步落库极端延迟只会让计数少 1（答辩多走一轮才收尾），方向安全。
                        Integer defenseIdEarly = null;
                        int answeredCount = 0;
                        try {
                            defenseIdEarly = defenseRecordsService.getOrCreateDefenseRecord(topicId, userId);
                            if (defenseIdEarly != null) {
                                answeredCount = scoreRecordMapper.countByDefenseId(defenseIdEarly);
                            }
                        } catch (Exception countEx) {
                            log.warn("权威轮次计数失败，回退历史消息计数: {}", countEx.getMessage());
                            answeredCount = Math.max(countAssistantMessages(chatMemory.get(finalSessionId)) - 1, 0);
                        }
                        // extraAskedCount 旧值来自 countExtraQuestions（按 DB 追问题条数统计，追问题在两个入库点
                        // 各登记一次而虚高≈2倍），一并纠正为"本轮之前已完成的追问次数"
                        int extraAskedCount = Math.max(answeredCount - existingQuestionCount, 0);
                        // currentRound 保留旧口径 = 本次作答序号（首轮后端出题的 greeting 占 1 条 assistant，故 = 已答次数 + 1）
                        int currentRound = answeredCount + 1;

                        StringBuilder contextPrompt = buildQuestionModePrompt(
                                topicResult.getData(), questions, topicId, finalUserInput, userId,
                                extraAskedCount, extraQuestionLimit, currentRound, existingQuestionIds);

                        return toFlux(sendMessageWithMemory(existingQuestionIds, existingQuestionCount, extraAskedCount, userId, topicId,
                                contextPrompt.toString(), finalSessionId, finalUserInput, answeredCount));
                    } else {
                        log.info("该题目下暂无问题，进入视频内容辅助回答模式");
                        StringBuilder contextPrompt = buildVideoAssistModePrompt(
                                topicResult.getData(), topicId, finalUserInput);

                        return toFlux(sendMessageWithMemory(existingQuestionIds, existingQuestionCount, 0, userId, topicId,
                                contextPrompt.toString(), finalSessionId, finalUserInput, 0));
                    }
                } else {
                    log.warn("查询题目信息失败，topicId: {}, code: {}, msg: {}",
                            topicId, questionResult.getCode(), questionResult.getMsg());
                    return handleFallback(userId, topicId, finalUserInput, "未找到相关题目信息", finalSessionId, finalUserInput);
                }

            } catch (Exception e) {
                log.error("查询题目信息时发生异常，topicId: {}", topicId, e);
                return handleFallback(userId, topicId, finalUserInput, "查询题目信息时发生错误", finalSessionId, finalUserInput);
            }
        } else {
            log.info("无 topicId，使用通用模式回答");
            return toFlux(sendMessageWithMemory(new ArrayList<>(), 0, 0, userId, null, finalUserInput, finalSessionId, finalUserInput, 0));
        }
    }

    @PostMapping(value = "/chat", produces = "text/html;charset=utf-8")
    public Flux<String> chatPost(@RequestBody Map<String, Object> requestBody) {
        String prompt = requestBody.getOrDefault("prompt", "").toString();
        Integer topicId = requestBody.get("topicId") != null ?
                Integer.parseInt(requestBody.get("topicId").toString()) : null;
        String sessionId = requestBody.getOrDefault("sessionId", "").toString();
        String userId = requestBody.getOrDefault("userId", "").toString();

        return chat(prompt, topicId, sessionId, userId);
    }

    // ==================== /api/final-evaluate 总体评价接口 ====================

    @PostMapping("/final-evaluate")
    public Flux<String> finalEvaluate(@RequestBody Map<String, Object> requestBody) {
        Integer topicId = requestBody.get("topicId") != null ?
                Integer.parseInt(requestBody.get("topicId").toString()) : null;
        String userId = requestBody.getOrDefault("userId", "").toString();

        if (topicId == null || userId == null || userId.isEmpty()) {
            return Flux.just("参数错误：topicId 和 userId 不能为空");
        }

        log.info("生成总体评价 - topicId: {}, userId: {}", topicId, userId);

        Integer defenseId = defenseRecordsService.getOrCreateDefenseRecord(topicId, userId);
        if (defenseId == null) {
            return Flux.just("未找到对应的答辩记录");
        }

        Map<String, Object> aggregated = scorePersistenceService.aggregateScores(defenseId);
        @SuppressWarnings("unchecked")
        List<DefenseScoreRecord> records = (List<DefenseScoreRecord>) aggregated.get("records");

        String evalPrompt = scorePersistenceService.buildFinalEvaluatePrompt(aggregated, records);
        log.info("总体评价 prompt 长度: {} 字符", evalPrompt.length());

        return chatClient.prompt()
                .user(evalPrompt)
                .stream()
                .content()
                .doOnComplete(() -> {
                    log.info("总体评价生成完成 - defenseId: {}", defenseId);
                });
    }

    // ==================== 会话清理 ====================

    /** 清理指定 sessionId 的对话历史，用于开始新答辩时清除旧记录 */
    @PostMapping("/chat/clear")
    public Map<String, Object> clearChatMemory(@RequestBody Map<String, Object> body) {
        String sessionId = (String) body.getOrDefault("sessionId", "");
        if (sessionId.isEmpty()) {
            return Map.of("success", false, "message", "sessionId 为空");
        }
        chatMemory.clear(sessionId);

        // 开始新答辩：清理上次遗留的空壳记录（一题未答），并为本轮创建独立的答辩记录
        // 追问额度按答辩记录隔离统计，新记录下天然从零开始，无需再删除历史追问
        try {
            Integer topicId = body.get("topicId") != null ? Integer.parseInt(body.get("topicId").toString()) : null;
            String userId = body.getOrDefault("userId", "").toString();
            if (topicId != null && !userId.isEmpty()) {
                defenseRecordsService.startNewDefenseRecord(topicId, userId);
            }
        } catch (Exception e) {
            log.warn("开启新答辩记录失败（不影响会话清理）: {}", e.getMessage());
        }

        log.info("已清理 Redis 会话记忆并开启新答辩记录 - sessionId: {}", sessionId);
        return Map.of("success", true, "message", "已清理");
    }

    // ==================== Prompt 构建（极简管道格式） ====================

    @SuppressWarnings("unchecked")
    private StringBuilder buildQuestionModePrompt(Object topicData, List<DefenseQuestions> questions,
                                                   Integer topicId, String prompt, String userId,
                                                   int extraAskedCount, int extraQuestionLimit,
                                                   int currentRound, List<Integer> existingQuestionIds) {
        boolean isFirstRound = (currentRound == 0);
        int remainingExtra = extraQuestionLimit - extraAskedCount;
        StringBuilder p = new StringBuilder();

        p.append("你是答辩评委，正在对学生进行一对一答辩考核。本场答辩共约10轮：5道预设题 + 至多5次AI追问。除首轮外，你必须严格按照以下三行格式输出，顺序不可颠倒，每行以固定标签开头，不要任何多余内容：\n");
        p.append("点评:（40字以内，必须基于学生回答的真实内容：回答有实质内容才先一句话肯定优点，再指出不足和一条改进建议；回答是敷衍/语气词/未实际作答时，必须直接写明回答无实质内容，严禁虚构“思路清晰”“回答完整”等与实际不符的肯定，禁止空话套话）\n");
        p.append("评分:总分/50|表达分|逻辑分|专业分|应变分|创新分（各0-10整数，五维分数相加必须等于总分）\n");
        p.append("下一题:（30字以内，提问下一道题目）\n");
        p.append("示例：\n点评:概念阐述准确、逻辑清晰，但缺少实际案例支撑，建议结合具体业务场景补充说明。\n评分:38/50|8|7|8|7|8\n下一题:请解释HDFS中NameNode的作用。\n\n");
        p.append("【轮次铁律】5道预设题未全部答完前，必须逐题输出『下一题:』提问下一道预设题；预设题答完后，最多允许5次AI追问，追问阶段每轮仍输出『下一题:』（30字以内）由你自拟追问。只有『预设题全部答完且追问已达5次』时，最后一行才允许输出『总结:』。任何情况下严禁提前输出『总结:』或提前结束答辩；只要还剩预设题或追问额度，最后一行必须输出『下一题:』，严禁输出『总结:』。每轮末尾会附带 [进度: 第X题/共Y题, 已追问Z/5次]，请据此判断当前进度并输出正确标签。\n\n");
        p.append("打分必须客观公正：学生回答正确、完整、条理清晰才给高分；回答错误、答非所问、含糊其辞或直接说“不知道”必须给低分（对应维度只给0-4分，总分不超过25/50），严禁凭印象乱给高分。\n\n");
        p.append("特别注意：学生回答“不知道/不会/不清楚”类短语，或回答无实质内容（如“额”“嗯”“开始”“一般吧”“还好吧”“差不多”“1”“666”“对对对”“你是对的”“我是对的”“感觉不太行”“我会这道题”等语气词、敷衍输入、只声称会/不会但未实际回答、纯数字、纯标点、与问题完全无关的跑题内容）时，本题五维必须全部给0分，点评中明确说明回答无实质内容，点评后必须照常输出『下一题:』继续提问；严禁因此输出『总结:』或提前结束答辩，除非本轮提示明确说明这是最后一轮。\n\n");

        if (isFirstRound) {
            p.append("共").append(questions.size()).append("题:\n");
            for (int i = 0; i < questions.size(); i++) {
                DefenseQuestions q = questions.get(i);
                p.append(i + 1).append(". ").append(q.getQuestion());
                if (q.getStandardAnswer() != null && !q.getStandardAnswer().isEmpty()) {
                    p.append(" (要点:").append(truncateSummary(q.getStandardAnswer(), ANSWER_TRUNCATE_LENGTH)).append(")");
                }
                p.append("\n");
            }
            p.append("\n首轮：用自然语言向学生问好，然后完整提出第1题，不要使用评分格式。\n");
        } else {
            int qi = currentRound - 1;
            if (qi >= 0 && qi < questions.size()) {
                DefenseQuestions cq = questions.get(qi);
                p.append("当前题:").append(cq.getQuestion());
                if (cq.getStandardAnswer() != null && !cq.getStandardAnswer().isEmpty()) {
                    p.append(" (参考:").append(truncateSummary(cq.getStandardAnswer(), 100)).append(")");
                }
                p.append("\n");
                int ni = qi + 1;
                if (ni < questions.size()) {
                    p.append("下题:").append(questions.get(ni).getQuestion()).append("\n");
                } else if (remainingExtra > 0) {
                    p.append("已无预设题,可追问").append(remainingExtra).append("个后总结\n");
                } else {
                    p.append("本题为整场答辩的最后一轮。学生回答完后请给出总结。注意：本轮不要输出『下一题』，最后一行改为：总结:（100字以内，对整场答辩的总体评价，先肯定优点，再指出整体不足和建议）\n");
                }
            } else {
                // 预设题已问完的追问阶段：明确剩余额度，额度用完则要求本轮总结
                if (remainingExtra > 0) {
                    p.append("预设题已全部问完，还可追问").append(remainingExtra).append("个。请按格式输出点评/评分/下一题，“下一题”由你提出。\n");
                } else {
                    p.append("预设题与追问已全部问完，这是最后一轮。请按格式输出点评/评分，最后一行改为：总结:（100字以内，对整场答辩的总体评价，先肯定优点，再指出整体不足和建议）。不要输出『下一题』。\n");
                }
            }
        }

        if (prompt != null && !prompt.trim().isEmpty()) {
            p.append("\n学生答:").append(prompt).append("\n");
        }

        // --- 动态段末尾进度说明（结构化标签由 sendMessageWithMemory 统一附加） ---
        int progressX = currentRound + 1;
        p.append("\n当前进度：第").append(progressX)
                .append("题/共").append(questions.size() + EXTRA_QUESTION_LIMIT)
                .append("题，已追问").append(extraAskedCount)
                .append("/").append(EXTRA_QUESTION_LIMIT)
                .append("次，请严格据此决定本轮最后一行输出『下一题:』还是『总结:』，追问阶段同样用『下一题:』标签。\n");

        return p;
    }

    // ==================== 消息发送与记忆管理 ====================

    private String sendMessageWithMemory(List<Integer> existingQuestionIds, int existingQuestionCount,
                                                int extraAskedCount, String userId, Integer topicId, String fullPrompt,
                                                String sessionId, String userInput, int answeredCount) {
        List<Message> history = chatMemory.get(sessionId);

        // --- 首轮出题：直接由题库提供，不调用模型（零延迟、无答案泄露、题目完整） ---
        if ((userInput == null || userInput.trim().isEmpty()) && topicId != null) {
            try {
                Result<List<DefenseQuestions>> qr = defenseTopicsService.getDefenseQuestionById(topicId);
                if (qr.getCode() == 1 && qr.getData() != null && !qr.getData().isEmpty()) {
                    String firstQuestion = qr.getData().get(0).getQuestion();
                    String greeting = "你好，我是本次答辩的AI考官。现在我们开始第1题：" + firstQuestion;
                    chatMemory.add(sessionId, List.of(new AssistantMessage(greeting)));
                    trimChatMemory(sessionId);
                    log.info("首轮由后端直接出题: {}", firstQuestion);
                    return greeting;
                }
            } catch (Exception e) {
                log.warn("首轮后端出题失败，回退模型出题", e);
            }
        }

        // --- 学生放弃作答（"不知道/不会"类短语）或敷衍作答（"开始/1/一般"等无实质内容，2026-09-15 新增）：不调用评分模型，本题零分并直接进入下一题 ---
        if (topicId != null && userId != null && (isGiveUpAnswer(userInput) || isJunkAnswer(userInput))) {
            // 点评文案按判定来源区分：放弃作答=明确表示不会；敷衍作答=回答无实质内容
            String zeroComment = isGiveUpAnswer(userInput)
                    ? "学生表示不知道该题，本题计0分，建议课后补强该知识点。"
                    : "学生未给出实质回答，本题计0分，建议结合问题认真作答。";
            String fixedResponse = handleGiveUpAnswer(existingQuestionIds, existingQuestionCount,
                    userId, topicId, userInput, sessionId, history, answeredCount, zeroComment);
            if (fixedResponse != null) {
                return fixedResponse;
            }
            // 返回 null 表示轮次/题库异常，回退正常模型流程
        }

        log.info("=== 当前使用的 Ollama 模型: {} ===", ollamaModelName);

        // --- 历史消息裁剪：只保留最近 MAX_HISTORY_MESSAGES 条 ---
        List<Message> trimmedHistory;
        if (history.size() > MAX_HISTORY_MESSAGES) {
            trimmedHistory = new ArrayList<>(history.subList(history.size() - MAX_HISTORY_MESSAGES, history.size()));
            log.info("历史消息裁剪 - 原始: {} 条, 保留最近: {} 条", history.size(), trimmedHistory.size());
        } else {
            trimmedHistory = new ArrayList<>(history);
        }

        // --- 精简日志 ---
        log.debug("sessionId: {}, 历史消息数: {}", sessionId, trimmedHistory.size());

        // --- 构建完整 prompt（只拼接最近6轮历史摘要） ---
        StringBuilder completePrompt = new StringBuilder();

        if (!trimmedHistory.isEmpty()) {
            completePrompt.append("【对话摘要】\n");
            for (Message msg : trimmedHistory) {
                if (msg instanceof UserMessage userMsg) {
                    String brief = userMsg.getText().length() > 100
                            ? userMsg.getText().substring(0, 100) + "..."
                            : userMsg.getText();
                    completePrompt.append("学生：").append(brief).append("\n");
                } else if (msg instanceof AssistantMessage asstMsg) {
                    // 只提取AI回复中的核心判断，不携带完整评分内容
                    String brief = extractBriefFromAiMessage(asstMsg.getText());
                    completePrompt.append("考官：").append(brief).append("\n");
                }
            }
            completePrompt.append("\n");
        }

        completePrompt.append(fullPrompt);

        // --- 进度标签：附加到用户消息末尾，让模型无需回看历史也知道当前轮次（10 轮改造） ---
        // 权威口径（2026-09-15 修复）：进度行序号与 buildQuestionModePrompt 保持一致 = 下一题序号（已答次数 + 2）
        if (topicId != null) {
            int progressTotal = existingQuestionCount + EXTRA_QUESTION_LIMIT;
            int progressX = Math.min(Math.max(answeredCount + 2, 1), progressTotal);
            completePrompt.append("\n[进度: 第").append(progressX)
                    .append("题/共").append(progressTotal)
                    .append("题, 已追问").append(extraAskedCount)
                    .append("/").append(EXTRA_QUESTION_LIMIT).append("次]");
        }

        // --- 阻塞调用模型（强制 maxTokens） ---
        String aiResponse = chatClient.prompt()
                .user(completePrompt.toString())
                .options(OpenAiChatOptions.builder()
                        .model("qwen2.5:3b-16k")
                        .maxTokens(320)
                        .build())
                .call()
                .content();
        log.info("=== AI 原始返回内容: {}", aiResponse);

        // 还未到最后一轮时，剥离模型提前输出的"总结"行，防止前端误判答辩提前结束
        aiResponse = stripPrematureSummary(aiResponse, existingQuestionCount, extraAskedCount);

        try {
            if (topicId != null && userId != null) {
                // 轮次口径（2026-09-15 修复）：权威计数 = 评分落库行数 + 1（answeredCount 由 chat() 传入）。
                // 旧实现按"历史中的 assistant 消息数"计数，Redis 记忆被 trimChatMemory 物理截断到 12 条后
                // 封顶在 6 —— 实测轮次号 1,2,1,4,5,6,6,6…、收尾判断 followUpDone/hardCapReached 永远无法触发，
                // 防死循环兜底形同虚设。评分表每次作答（含放弃0分）恰落一行，天然免疫记忆裁剪；
                // 异步落库极端延迟只会让计数少 1（答辩多走一轮才收尾），方向安全。
                int assistantCountInHistory = answeredCount + 1;

                if (userInput != null && !userInput.trim().isEmpty()) {
                    Integer defenseId = defenseRecordsService.getOrCreateDefenseRecord(topicId, userId);
                    int currentRoundNum = assistantCountInHistory;

                    Map<String, Object> scores = scorePersistenceService.parseScoresFromResponse(aiResponse);
                    String comment = (String) scores.getOrDefault("comment",
                            extractFeedbackFromResponse(aiResponse));

                    if (defenseId != null && !scores.isEmpty()) {
                        DefenseScoreRecord record = new DefenseScoreRecord();
                        record.setDefenseId(defenseId);
                        int questionIndex = assistantCountInHistory - 1;
                        if (questionIndex >= 0 && questionIndex < existingQuestionIds.size()) {
                            record.setQuestionId(existingQuestionIds.get(questionIndex));
                        }
                        record.setRoundNum(currentRoundNum);
                        record.setExpressionScore(toBigDecimal(scores.get("expression")));
                        record.setLogicScore(toBigDecimal(scores.get("logic")));
                        record.setProfessionalScore(toBigDecimal(scores.get("professional")));
                        record.setAdaptabilityScore(toBigDecimal(scores.get("adaptability")));
                        record.setInnovationScore(toBigDecimal(scores.get("innovation")));
                        record.setComment(comment);
                        record.setCreatedAt(LocalDateTime.now());
                        scorePersistenceService.saveRoundScoreAsync(record);
                    }

                    Double legacyScore = null;
                    if (scores.containsKey("totalScore")) {
                        legacyScore = (Double) scores.get("totalScore");
                    }
                    if (legacyScore == null) {
                        legacyScore = extractScoreFromResponse(aiResponse);
                    }
                    String feedback = comment != null ? comment : extractFeedbackFromResponse(aiResponse);

                    if (assistantCountInHistory <= existingQuestionCount) {
                        try {
                            int qi = assistantCountInHistory - 1;
                            if (qi >= 0 && qi < existingQuestionIds.size()) {
                                Integer currentQuestionId = existingQuestionIds.get(qi);
                                defenseRecordsService.savePresetQuestionAnswer(
                                        topicId, userId, currentQuestionId, userInput, feedback, legacyScore);
                                log.info("预设问题回答保存成功 - questionId: {}, round: {}", currentQuestionId, currentRoundNum);
                            }
                        } catch (Exception e) {
                            log.error("保存预设问题回答时发生异常", e);
                        }
                    } else {
                        saveExtraQuestionPhase(aiResponse, defenseId, userId, topicId, trimmedHistory, userInput, feedback, legacyScore);
                    }

                    if (defenseId != null
                            && assistantCountInHistory < existingQuestionCount + EXTRA_QUESTION_LIMIT) {
                        String nextQuestion = extractNextQuestionFromResponse(aiResponse);
                        if ((nextQuestion == null || nextQuestion.isEmpty()) && scores.containsKey("question")) {
                            nextQuestion = (String) scores.get("question");
                        }
                        // 清洗：防止模型把"答案要点"混入下一题
                        nextQuestion = cleanNextQuestion(nextQuestion);
                        // 兜底：预设题阶段解析不到下一题时，直接从题库取
                        if ((nextQuestion == null || nextQuestion.isEmpty())
                                && assistantCountInHistory < existingQuestionCount) {
                            try {
                                Result<List<DefenseQuestions>> qr = defenseTopicsService.getDefenseQuestionById(topicId);
                                if (qr.getCode() == 1 && qr.getData() != null) {
                                    List<DefenseQuestions> qs = qr.getData();
                                    int nqi = assistantCountInHistory;
                                    if (nqi >= 0 && nqi < qs.size()) {
                                        nextQuestion = qs.get(nqi).getQuestion();
                                        log.info("AI未输出下一题，已从题库兜底获取: {}", nextQuestion);
                                    }
                                }
                            } catch (Exception ex) {
                                log.warn("题库兜底获取下一题失败", ex);
                            }
                        }
                        if (nextQuestion != null && !nextQuestion.isEmpty()
                                && assistantCountInHistory >= existingQuestionCount) {
                            // 仅追问阶段由模型自拟的下一题才登记入库；预设题阶段不动，避免污染追问额度计数
                            submitIfNewQuestion(defenseId, nextQuestion);
                        }
                    }

                    // 最后一轮总结生成后收尾：聚合五维平均分写入 defense_records，供前端答辩记录展示
                    // 权威口径（2026-09-15 修复）：assistantCountInHistory = 本次作答序号（评分落库行数+1，免疫记忆裁剪）。
                    // 共 existingQuestionCount + EXTRA_QUESTION_LIMIT = 10 轮，第 10 次作答即收尾：
                    // ① 模型输出"总结:" → 正常收尾；② 模型仍输出"下一题:" → 剥离残留提问并补占位总结强制收尾（防死循环兜底）。
                    // 旧实现的两个收尾条件都按"历史消息中的 assistant 数"计数，被裁剪封顶在 6，两个分支均永远无法触发。
                    boolean hasSummary = aiResponse != null
                            && (aiResponse.contains("总结:") || aiResponse.contains("总结："));
                    int totalRounds = existingQuestionCount + EXTRA_QUESTION_LIMIT;
                    boolean lastRound = assistantCountInHistory >= totalRounds;
                    if (lastRound) {
                        if (!hasSummary) {
                            // 到最后一轮模型仍未输出总结：剥离残留"下一题"并补占位总结，保证前端收到"总结:"信号正常结束
                            aiResponse = stripNextQuestionFromText(aiResponse);
                            aiResponse = (aiResponse == null ? "" : aiResponse)
                                    + "\n总结:本轮答辩已进行" + assistantCountInHistory + "轮，已自动结束。";
                            log.warn("硬上限兜底收尾：第{}轮未见总结，已强制收尾", assistantCountInHistory);
                        }
                        if (defenseId != null) {
                            finishDefenseAggregation(defenseId, extractSummaryText(aiResponse));
                        }
                    } else {
                        // 未到最后一轮：剥离提前出现的总结，防止前端误判答辩提前结束
                        String stripped = stripSummaryFromText(aiResponse);
                        if (!stripped.equals(aiResponse)) {
                            aiResponse = stripped;
                            log.warn("硬校验拦截提前总结：当前第{}轮/共{}轮，已剥离总结行",
                                    assistantCountInHistory, totalRounds);
                        }
                        // 兜底：剥离后若无"下一题/追问"，按阶段补一行干净标签，避免前端"有分无题"
                        aiResponse = appendNextQuestionFallback(aiResponse, assistantCountInHistory >= existingQuestionCount,
                                topicId, assistantCountInHistory);
                    }
                }
            }

            if (aiResponse != null && !aiResponse.trim().isEmpty()) {
                if (userInput != null && !userInput.trim().isEmpty()) {
                    chatMemory.add(sessionId, List.of(
                            new UserMessage(userInput),
                            new AssistantMessage(aiResponse)
                    ));
                } else {
                    chatMemory.add(sessionId, List.of(
                            new AssistantMessage(aiResponse)
                    ));
                }
                trimChatMemory(sessionId);
            }

        } catch (Exception e) {
            log.error("存储会话记忆失败 - sessionId: {}, error: {}", sessionId, e.getMessage(), e);
        }

        return aiResponse;
    }

    /** 用于 chat() 方法调用时包装为 Flux */
    private Flux<String> toFlux(String text) {
        return text != null ? Flux.just(text) : Flux.empty();
    }

    /**
     * 从AI回复中提取简短摘要（用于历史拼接时减少token）
     */
    private String extractBriefFromAiMessage(String aiText) {
        if (aiText == null || aiText.isEmpty()) return "";
        // 新三段式：提取 点评 内容作为摘要（不携带评分行，避免污染后续 prompt）
        if (aiText.contains("点评:")) {
            int start = aiText.indexOf("点评:") + 3;
            int end = aiText.indexOf("\n", start);
            if (end == -1) end = aiText.length();
            String c = aiText.substring(start, end).trim();
            if (!c.isEmpty()) return c.length() > 60 ? c.substring(0, 60) + "..." : c;
        }
        // 提取【评语】或【问题】作为摘要
        int commentStart = aiText.indexOf("【评语】");
        int questionStart = aiText.indexOf("【问题】");
        if (questionStart != -1) {
            String q = aiText.substring(questionStart + 4).trim();
            return q.length() > 60 ? q.substring(0, 60) + "..." : q;
        }
        if (commentStart != -1) {
            int end = aiText.indexOf("【", commentStart + 4);
            String c = end != -1 ? aiText.substring(commentStart + 4, end).trim() : aiText.substring(commentStart + 4).trim();
            return c.length() > 80 ? c.substring(0, 80) + "..." : c;
        }
        return aiText.length() > 80 ? aiText.substring(0, 80) + "..." : aiText;
    }

    /**
     * 裁剪ChatMemory中的历史消息，确保不超过 MAX_HISTORY_MESSAGES 条
     */
    private void trimChatMemory(String sessionId) {
        try {
            List<Message> all = chatMemory.get(sessionId);
            if (all.size() > MAX_HISTORY_MESSAGES) {
                chatMemory.clear(sessionId);
                List<Message> keep = all.subList(all.size() - MAX_HISTORY_MESSAGES, all.size());
                chatMemory.add(sessionId, keep);
                log.info("Redis ChatMemory 裁剪完成 - 从 {} 条 -> {} 条", all.size(), keep.size());
            }
        } catch (Exception e) {
            log.warn("裁剪 ChatMemory 失败: {}", e.getMessage());
        }
    }

    // ==================== 额外问题保存逻辑 ====================

    private void saveExtraQuestionPhase(String aiResponse, Integer defenseId, String userId, Integer topicId,
                                        List<Message> history, String userInput, String feedback, Double score) {
        log.info("额外问题阶段");

        AiAnalysis aiAnalysis = new AiAnalysis();
        aiAnalysis.setDefenseId(defenseId);
        aiAnalysis.setUserId(userId);
        aiAnalysis.setFeedback(summary(aiResponse));
        aiAnalysis.setVideoAnalysis(videoAnalysis(aiResponse));
        aiAnalysis.setReportAnalysis(reportAnalysis(aiResponse));

        Double totalScore = extractTotalScoreFromResponse(aiResponse);
        if (totalScore != null) {
            aiAnalysis.setScore(totalScore);
        }

        if (aiAnalysis.getFeedback() != null || aiAnalysis.getVideoAnalysis() != null || aiAnalysis.getReportAnalysis() != null) {
            defenseAnswersMapper.insertAiFeedback(aiAnalysis);
        }

        if (defenseId != null) {
            String currentQuestion = extractQuestionFromLastAiMessage(history);
            if (currentQuestion != null && !currentQuestion.isEmpty()) {
                DefenseStudentQuestions existingQuestion = findExistingStudentQuestion(defenseId, currentQuestion);
                Integer sqId;
                if (existingQuestion != null) {
                    sqId = existingQuestion.getSqId();
                } else {
                    int nextSort = defenseStudentQuestionsMapper.getNextSortNumber(defenseId);
                    DefenseStudentQuestions studentQuestion = new DefenseStudentQuestions();
                    studentQuestion.setDefenseId(defenseId);
                    studentQuestion.setQuestionId(null);
                    studentQuestion.setCustomQuestion(currentQuestion);
                    studentQuestion.setCustomStandardAnswer("");
                    studentQuestion.setQuestionType("ai");
                    studentQuestion.setSort(nextSort);
                    studentQuestion.setCreatedAt(LocalDateTime.now());
                    defenseStudentQuestionsMapper.insertStudentQuestion(studentQuestion);
                    sqId = studentQuestion.getSqId();
                }

                if (sqId != null) {
                    DefenseAnswers answer = new DefenseAnswers();
                    answer.setDefenseId(defenseId);
                    answer.setQuestionId(null);
                    answer.setSqId(sqId);
                    answer.setStudentAnswer(userInput);
                    answer.setFeedback(feedback);
                    answer.setScore(score != null ? new BigDecimal(score) : null);
                    answer.setCreatedAt(LocalDateTime.now());
                    defenseAnswersMapper.insertAnswer(answer);
                }
            }
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 截断文本至指定长度，超出部分用"..."代替
     */
    private String truncateSummary(String text, int maxLength) {
        if (text == null || text.isEmpty()) return "";
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength) + "...";
    }

    private BigDecimal toBigDecimal(Object value) {
        if (value == null) return null;
        if (value instanceof BigDecimal) return (BigDecimal) value;
        if (value instanceof Double) return BigDecimal.valueOf((Double) value);
        if (value instanceof Integer) return BigDecimal.valueOf((Integer) value);
        if (value instanceof String) {
            try {
                return new BigDecimal((String) value);
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    private int countExtraQuestions(Integer topicId, String userId) {
        try {
            Integer defenseId = defenseRecordsService.getOrCreateDefenseRecord(topicId, userId);
            if (defenseId == null) return 0;
            List<DefenseStudentQuestions> list = defenseStudentQuestionsMapper.getQuestionsByDefenseId(defenseId);
            int count = 0;
            if (list != null) {
                for (DefenseStudentQuestions q : list) {
                    if ("ai".equals(q.getQuestionType())) count++;
                }
            }
            return count;
        } catch (Exception e) {
            log.warn("统计额外问题数失败", e);
            return 0;
        }
    }

    private int countAssistantMessages(List<Message> history) {
        int count = 0;
        for (Message msg : history) {
            if (msg instanceof AssistantMessage) count++;
        }
        return count;
    }

    // ==================== AI回复解析方法（保留原有兼容逻辑） ====================

    private Double extractScoreFromResponse(String aiResponse) {
        if (aiResponse == null || aiResponse.isEmpty()) return null;
        try {
            if (aiResponse.contains("【得分】")) {
                int start = aiResponse.indexOf("【得分】") + 4;
                int end = aiResponse.indexOf("\n", start);
                if (end == -1) end = aiResponse.length();
                String s = aiResponse.substring(start, end).trim().replaceAll("[^0-9.]", "");
                if (!s.isEmpty()) return Double.parseDouble(s);
            }
            // 新三段式：评分:38/50|...
            java.util.regex.Matcher mNew = java.util.regex.Pattern
                    .compile("评分[:：]\\s*(\\d+(?:\\.\\d+)?)/50").matcher(aiResponse);
            if (mNew.find()) return Double.parseDouble(mNew.group(1));
            java.util.regex.Pattern p = java.util.regex.Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*分");
            java.util.regex.Matcher m = p.matcher(aiResponse);
            if (m.find()) return Double.parseDouble(m.group(1));
        } catch (Exception e) {
            log.warn("提取分数失败: {}", e.getMessage());
        }
        return null;
    }

    private Double extractTotalScoreFromResponse(String aiResponse) {
        if (aiResponse == null || aiResponse.isEmpty()) return null;
        try {
            if (aiResponse.contains("【总得分】")) {
                int start = aiResponse.indexOf("【总得分】") + 5;
                int end = aiResponse.indexOf("\n", start);
                if (end == -1) end = aiResponse.length();
                String s = aiResponse.substring(start, end).trim().replaceAll("[^0-9.]", "");
                if (!s.isEmpty()) return Double.parseDouble(s);
            }
        } catch (Exception e) {
            log.warn("提取总得分失败: {}", e.getMessage());
        }
        return null;
    }

    private String extractFeedbackFromResponse(String aiResponse) {
        if (aiResponse == null || aiResponse.isEmpty()) return aiResponse;
        try {
            // 新三段式：点评:...
            if (aiResponse.contains("点评:")) {
                int start = aiResponse.indexOf("点评:") + 3;
                int end = aiResponse.indexOf("下一题:", start);
                if (end == -1) end = aiResponse.length();
                String d = aiResponse.substring(start, end).trim();
                if (!d.isEmpty()) return d;
            }
            if (aiResponse.contains("【评价】")) {
                int start = aiResponse.indexOf("【评价】") + 4;
                int end = aiResponse.length();
                if (aiResponse.contains("【得分】")) end = aiResponse.indexOf("【得分】");
                else if (aiResponse.contains("【问题】")) end = aiResponse.indexOf("【问题】");
                else if (aiResponse.contains("【总结】")) end = aiResponse.indexOf("【总结】");
                return aiResponse.substring(start, end).trim();
            }
        } catch (Exception e) {
            log.warn("提取评价失败: {}", e.getMessage());
        }
        return aiResponse;
    }

    private String extractNextQuestionFromResponse(String aiResponse) {
        if (aiResponse == null || aiResponse.isEmpty()) return null;
        // 新三段式：下一题:...
        if (aiResponse.contains("下一题:")) {
            int start = aiResponse.indexOf("下一题:") + 4;
            String q = aiResponse.substring(start).trim();
            if (!q.isEmpty()) return q;
        }
        if (aiResponse.contains("【问题】")) {
            int start = aiResponse.indexOf("【问题】") + 4;
            String q = aiResponse.substring(start).trim();
            if (!q.isEmpty()) return q;
        }
        return null;
    }

    /** 清洗下一题文本：只保留第一行并截断"答案要点"等内容，防止评分参考或总结串入题目 */
    private String cleanNextQuestion(String q) {
        if (q == null) return null;
        String cleaned = q.trim();
        int nl = cleaned.indexOf('\n');
        if (nl > -1) cleaned = cleaned.substring(0, nl).trim();
        int idx = cleaned.indexOf("要点");
        if (idx > 0) cleaned = cleaned.substring(0, idx).trim();
        if (cleaned.isEmpty() || "无".equals(cleaned)) return null;
        return cleaned;
    }

    /**
     * 还未到最后一轮时，剥离模型提前输出的"总结"行（含其后内容）。
     * 仅在题库模式（existingQuestionCount > 0）下生效；追问额度用完的最后一轮允许总结。
     */
    private String stripPrematureSummary(String aiResponse, int existingQuestionCount, int extraAskedCount) {
        if (aiResponse == null || aiResponse.isEmpty()) return aiResponse;
        if (existingQuestionCount <= 0) return aiResponse;
        // 权威口径（2026-09-15 修复）：extraAskedCount = 本轮之前已完成的追问次数。最后一次追问
        // （第 EXTRA_QUESTION_LIMIT 次）作答时该值恰为 EXTRA_QUESTION_LIMIT - 1，此时模型的"总结:"
        // 是合法收尾信号，不能剥离（旧阈值会把它剥掉，导致最后一轮只能走占位总结兜底）
        if (extraAskedCount >= EXTRA_QUESTION_LIMIT - 1) return aiResponse;

        int pos = aiResponse.indexOf("总结:");
        int posFull = aiResponse.indexOf("总结：");
        if (pos == -1 || (posFull != -1 && posFull < pos)) pos = posFull;
        if (pos == -1) return aiResponse;

        if (pos == 0) {
            // 整条回复只有总结：无法安全剥离，保留原样并告警（提示词已约束，正常流程极少出现）
            log.warn("模型在非最后一轮输出了纯总结回复，未剥离: {}", aiResponse);
            return aiResponse;
        }
        log.warn("模型在非最后一轮提前输出总结，已剥离: {}", aiResponse.substring(pos));
        return aiResponse.substring(0, pos).trim();
    }

    /**
     * 纯文本剥离函数：移除回复中"总结:"（含全角）及其后内容。
     * 供硬校验兜底使用（区别于 stripPrematureSummary 的额度判断）。
     */
    private String stripSummaryFromText(String aiResponse) {
        if (aiResponse == null || aiResponse.isEmpty()) return aiResponse;
        int pos = aiResponse.indexOf("总结:");
        int posFull = aiResponse.indexOf("总结：");
        if (pos == -1 || (posFull != -1 && posFull < pos)) pos = posFull;
        if (pos == -1 || pos == 0) return aiResponse;
        return aiResponse.substring(0, pos).trim();
    }

    /**
     * 纯文本剥离函数：移除回复中"下一题:"（含全角）及其后内容。
     * 供最后一轮强制收尾使用：模型到第 10 轮仍输出"下一题:"时，先剥掉残留提问再补占位总结，
     * 保证前端收到的收尾消息干净（只有点评/评分/总结）。
     */
    private String stripNextQuestionFromText(String aiResponse) {
        if (aiResponse == null || aiResponse.isEmpty()) return aiResponse;
        int pos = aiResponse.indexOf("下一题:");
        int posFull = aiResponse.indexOf("下一题：");
        if (pos == -1 || (posFull != -1 && posFull < pos)) pos = posFull;
        if (pos == -1 || pos == 0) return aiResponse;
        return aiResponse.substring(0, pos).trim();
    }

    /**
     * 硬校验兜底的补充行：剥离总结后，若 aiResponse 未携带"下一题/追问"标签，
     * 按阶段补一行，保证前端始终能拿到下一题/追问：
     *  - 预设未答完：从题库取下一题
     *  - 追问阶段：调 generateFollowUpQuestion 生成
     *  统一拼"下一题:"标签（前端 parseSegmentFormat 只认 下一题/下一问，不认"追问:"）。
     */
    private String appendNextQuestionFallback(String aiResponse, boolean presetDone, Integer topicId,
                                              int assistantCountInHistory) {
        if (aiResponse == null) return null;
        // 已含下一题或追问标签则不用补
        if (aiResponse.contains("下一题:") || aiResponse.contains("下一题：")
                || aiResponse.contains("追问:") || aiResponse.contains("追问：")) {
            return aiResponse;
        }
        String nextQuestion;
        if (!presetDone) {
            // 预设阶段：取题库中当前轮次的下一道题（assistantCountInHistory 同样用于该下标）
            nextQuestion = fetchPresetQuestionText(topicId, assistantCountInHistory);
        } else {
            nextQuestion = generateFollowUpQuestion(topicId);
        }
        if (nextQuestion == null || nextQuestion.trim().isEmpty()) {
            return aiResponse;
        }
        // 统一用"下一题:"标签：前端 parseSegmentFormat 只认 下一题/下一问，不认"追问:"
        return aiResponse + "\n下一题:" + nextQuestion.trim();
    }

    // ==================== 放弃作答（"不知道/不会"）固定流程 ====================

    /** 判定为放弃作答的短语 */
    private static final String[] GIVE_UP_PHRASES = {
            "不知道", "不会", "不清楚", "不了解", "不懂", "没学过", "没复习", "没准备", "没印象", "跳过",
            // 2026-09-15 补充：contains 匹配要求连续子串，"不太清楚/不太会"等带语气词的变体原本漏判
            // （实测同一"不太清楚"三次得分 25/0/20 口径不一），故显式补充常见变体
            "不太清楚", "太不清楚", "不太懂", "不太会"
    };

    /** 放弃作答判定的最大回答长度（过长回答不视为放弃，走正常评分） */
    private static final int GIVE_UP_MAX_LENGTH = 15;

    private boolean isGiveUpAnswer(String userInput) {
        if (userInput == null) return false;
        String text = userInput.trim();
        if (text.isEmpty() || text.length() > GIVE_UP_MAX_LENGTH) return false;
        for (String phrase : GIVE_UP_PHRASES) {
            if (text.contains(phrase)) return true;
        }
        return false;
    }

    /** 判定为敷衍/无实质作答的短语（归一化+首尾修饰剥离后精确相等才命中，避免子串误伤正常回答，如"一般用快排"） */
    private static final String[] JUNK_ANSWER_PHRASES = {
            "开始", "一般", "一般般", "差不多", "还好", "还行", "还可以", "就这样", "就这", "随便", "过",
            "好的", "知道了", "明白了", "没什么", "没啥说的",
            "不太行", "不行", "你是对的", "我是对的", "你说的对", "你说得对", "有道理", "对的", "是的", "没错",
            "我会这道题", "这题我会", "我会", "我会做", "我知道", "我知道这个", "我知道答案",
            "简单", "很简单", "挺简单", "太简单", "很容易", "没问题",
            "说不上来", "答不上来", "忘了", "忘记了",
            "ok", "okay", "next", "emm", "emmm"
    };

    /** 语气词/填充字符：回答仅由这些字符组成（"额""嗯嗯""额啊"等任意组合）视为敷衍输入 */
    private static final String FILLER_CHARS = "嗯哦啊呃额唔哎嘿诶唉噢喔哈呀哇";

    /**
     * 敷衍/无实质作答判定（2026-09-15 新增，同日实测后二次加强）：与放弃作答同走固定零分流程，不调用评分模型。
     * 归一化：转小写、去空白/标点/符号，再剥离开头缓和语（感觉/我觉得/我认为）与结尾语气词（吧/呢/啊…），
     * 覆盖"一般吧""还好吧""感觉不太行"等实测漏网变体。判定：①剥离后为空（纯标点/纯语气词，如"？？""额"）；
     * ②1~2位纯数字（如"1"）；③仅由语气词字符组成；④与敷衍短语精确相等（含"我会这道题"等只声称会但不回答）。
     * 3位及以上纯数字（如端口号"3306"）可能是有效简答，不在此判定，交由评分模型按提示词规则给分。
     */
    private boolean isJunkAnswer(String userInput) {
        if (userInput == null) return false;
        String trimmed = userInput.trim();
        if (trimmed.isEmpty()) return false;
        String text = trimmed.toLowerCase().replaceAll("[\\s\\p{P}\\p{S}]+", "");
        text = text.replaceAll("^(感觉|我觉得|我认为)+", "");
        text = text.replaceAll("[吧呢啊呀哦嘛呗啦哟唷哇哈]+$", "");
        if (text.isEmpty()) return true;
        if (text.matches("[0-9]{1,2}")) return true;
        boolean allFiller = true;
        for (int i = 0; i < text.length(); i++) {
            if (FILLER_CHARS.indexOf(text.charAt(i)) < 0) { allFiller = false; break; }
        }
        if (allFiller) return true;
        // 复读型敷衍（15:44 实测漏网补网）："对对对""嗯嗯""666"（同一字符重复）与"对的对的""是的是的"（双字单元重复）
        if (text.length() >= 2 && text.chars().distinct().count() == 1) return true;
        if (text.length() >= 4 && text.length() % 2 == 0
                && text.substring(0, text.length() / 2).equals(text.substring(text.length() / 2))) return true;
        for (String phrase : JUNK_ANSWER_PHRASES) {
            if (text.equals(phrase)) return true;
        }
        return false;
    }

    /**
     * 学生放弃作答的固定处理：本题五维0分、不调用评分模型，直接给出下一题或收尾总结。
     *
     * @param zeroComment 固定零分点评文案（按判定来源区分：放弃作答/敷衍作答）
     * @return 固定模板响应；返回 null 表示轮次/题库异常，需回退正常模型流程
     */
    private String handleGiveUpAnswer(List<Integer> existingQuestionIds, int existingQuestionCount,
                                      String userId, Integer topicId,
                                      String userInput, String sessionId, List<Message> history, int answeredCount,
                                      String zeroComment) {
        try {
            Integer defenseId = defenseRecordsService.getOrCreateDefenseRecord(topicId, userId);
            if (defenseId == null) {
                log.warn("放弃作答处理：无法获取答辩记录，回退正常流程 - topicId: {}, userId: {}", topicId, userId);
                return null;
            }

            // 权威轮次（2026-09-15 修复）：本次作答序号 = 已落库行数 + 1。旧实现按"历史中的 assistant 消息数"
            // 计数，被记忆裁剪封顶在 6（实测放弃轮次号全部卡在 6）。
            int currentRound = answeredCount + 1;
            int qi = currentRound - 1;
            boolean inPresetPhase = qi >= 0 && qi < existingQuestionCount;
            // 本轮已是最后一轮（第 existingQuestionCount + EXTRA_QUESTION_LIMIT 次作答）→ 本题0分后直接收尾总结
            boolean lastRound = currentRound >= existingQuestionCount + EXTRA_QUESTION_LIMIT;

            // 先确定下一题：预设题未问完 → 题库取下一题；未到最后一轮 → 模型生成追问；否则收尾。
            // 旧实现的 quotaRemains 基于 countExtraQuestions（DB 追问题条数虚高≈2倍），会导致提前收尾。
            boolean terminal = false;
            String nextQuestion = null;
            if (inPresetPhase && qi + 1 < existingQuestionCount) {
                nextQuestion = fetchPresetQuestionText(topicId, qi + 1);
            } else if (!lastRound) {
                nextQuestion = generateFollowUpQuestion(topicId);
            } else {
                terminal = true;
            }
            if (!terminal && (nextQuestion == null || nextQuestion.isEmpty())) {
                log.warn("放弃作答处理：下一题获取失败，回退正常流程 - topicId: {}", topicId);
                return null;
            }

            // 落库：评分记录 + 回答记录
            if (inPresetPhase) {
                saveGiveUpScoreRecord(defenseId, existingQuestionIds.get(qi), currentRound, zeroComment);
                defenseRecordsService.savePresetQuestionAnswer(
                        topicId, userId, existingQuestionIds.get(qi), userInput, zeroComment, 0.0);
            } else {
                saveGiveUpScoreRecord(defenseId, null, currentRound, zeroComment);
                saveGiveUpFollowUpAnswer(defenseId, history, userInput, zeroComment, 0.0);
            }

            // 仅当本轮抛出的是"新追问题"时才登记入库（预设最后一题切换到追问的第一题也在此登记）
            boolean nextIsNewFollowUp = !terminal && !(inPresetPhase && qi + 1 < existingQuestionCount);
            if (nextIsNewFollowUp) {
                submitIfNewQuestion(defenseId, nextQuestion);
            }

            String fixedResponse;
            if (terminal) {
                String summary = "本次答辩到此结束，系统已按各轮评分汇总最终成绩，可在答辩记录中查看。";
                fixedResponse = "点评:" + zeroComment + "\n评分:0/50|0|0|0|0|0\n总结:" + summary;
                finishDefenseAggregation(defenseId, summary);
            } else {
                fixedResponse = "点评:" + zeroComment + "\n评分:0/50|0|0|0|0|0\n下一题:" + nextQuestion;
            }

            chatMemory.add(sessionId, List.of(
                    new UserMessage(userInput),
                    new AssistantMessage(fixedResponse)
            ));
            trimChatMemory(sessionId);

            log.info("学生放弃/敷衍作答，走固定零分流程 - defenseId: {}, round: {}, terminal: {}, 点评: {}",
                    defenseId, currentRound, terminal, zeroComment);
            return fixedResponse;
        } catch (Exception e) {
            log.error("放弃作答固定流程异常，回退正常模型流程", e);
            return null;
        }
    }

    /** 从题库获取指定下标的预设题文本 */
    private String fetchPresetQuestionText(Integer topicId, int index) {
        try {
            Result<List<DefenseQuestions>> qr = defenseTopicsService.getDefenseQuestionById(topicId);
            if (qr.getCode() == 1 && qr.getData() != null && index >= 0 && index < qr.getData().size()) {
                return qr.getData().get(index).getQuestion();
            }
        } catch (Exception e) {
            log.warn("题库获取下一题失败: {}", e.getMessage());
        }
        return null;
    }

    /** 追问阶段：让模型围绕课题出一个新的追问问题（仅输出问题本身） */
    private String generateFollowUpQuestion(Integer topicId) {
        String topicName = "";
        try {
            Result<Object> topicResult = defenseTopicsService.getTopicById(topicId);
            if (topicResult.getCode() == 1 && topicResult.getData() instanceof TopicDto) {
                topicName = ((TopicDto) topicResult.getData()).getTopicName();
            }
        } catch (Exception e) {
            log.warn("获取课题名称失败: {}", e.getMessage());
        }

        String prompt = "你是一名答辩考官，正在考核学生的课题《" + topicName + "》。"
                + "请提出一个新的追问问题，只输出问题本身（30字以内，以？结尾），不要输出其他任何内容。";
        try {
            String resp = chatClient.prompt()
                    .user(prompt)
                    .options(OpenAiChatOptions.builder()
                            .model("qwen2.5:3b-16k")
                            .maxTokens(60)
                            .build())
                    .call()
                    .content();
            String question = cleanNextQuestion(resp);
            if (question != null && !question.isEmpty()) {
                return question;
            }
        } catch (Exception e) {
            log.warn("模型生成追问失败，使用兜底问题: {}", e.getMessage());
        }

        String[] fallbacks = {
                "请结合实际应用场景，谈谈该课题的不足与改进方向？",
                "针对你刚才的回答，请补充说明关键的实现细节？",
                "如果时间或资源受限，你会如何调整该课题的方案？"
        };
        return fallbacks[java.util.concurrent.ThreadLocalRandom.current().nextInt(fallbacks.length)];
    }

    /** 放弃作答的固定零分评分记录（五维全0） */
    private void saveGiveUpScoreRecord(Integer defenseId, Integer questionId, int roundNum, String comment) {
        DefenseScoreRecord record = new DefenseScoreRecord();
        record.setDefenseId(defenseId);
        record.setQuestionId(questionId);
        record.setRoundNum(roundNum);
        record.setExpressionScore(BigDecimal.ZERO);
        record.setLogicScore(BigDecimal.ZERO);
        record.setProfessionalScore(BigDecimal.ZERO);
        record.setAdaptabilityScore(BigDecimal.ZERO);
        record.setInnovationScore(BigDecimal.ZERO);
        record.setComment(comment);
        record.setCreatedAt(LocalDateTime.now());
        scorePersistenceService.saveRoundScoreAsync(record);
    }

    /** 追问阶段放弃作答：将该回答按0分存入 defense_answers（问题行通常已由追问登记时创建） */
    private void saveGiveUpFollowUpAnswer(Integer defenseId, List<Message> history,
                                          String userInput, String feedback, Double score) {
        String currentQuestion = extractQuestionFromLastAiMessage(history);
        if (currentQuestion == null || currentQuestion.isEmpty()) {
            log.warn("放弃作答处理：未从历史中解析到当前追问题，跳过回答落库 - defenseId: {}", defenseId);
            return;
        }
        try {
            DefenseStudentQuestions existingQuestion = findExistingStudentQuestion(defenseId, currentQuestion);
            Integer sqId;
            if (existingQuestion != null) {
                sqId = existingQuestion.getSqId();
            } else {
                int nextSort = defenseStudentQuestionsMapper.getNextSortNumber(defenseId);
                DefenseStudentQuestions studentQuestion = new DefenseStudentQuestions();
                studentQuestion.setDefenseId(defenseId);
                studentQuestion.setQuestionId(null);
                studentQuestion.setCustomQuestion(currentQuestion);
                studentQuestion.setCustomStandardAnswer("");
                studentQuestion.setQuestionType("ai");
                studentQuestion.setSort(nextSort);
                studentQuestion.setCreatedAt(LocalDateTime.now());
                defenseStudentQuestionsMapper.insertStudentQuestion(studentQuestion);
                sqId = studentQuestion.getSqId();
            }
            if (sqId == null) return;
            DefenseAnswers answer = new DefenseAnswers();
            answer.setDefenseId(defenseId);
            answer.setQuestionId(null);
            answer.setSqId(sqId);
            answer.setStudentAnswer(userInput);
            answer.setFeedback(feedback);
            answer.setScore(score != null ? new BigDecimal(score) : null);
            answer.setCreatedAt(LocalDateTime.now());
            defenseAnswersMapper.insertAnswer(answer);
        } catch (Exception e) {
            log.warn("放弃作答追问回答落库失败 - defenseId: {}", defenseId, e);
        }
    }

    /**
     * 答辩收尾：聚合各轮五维评分（0-50制平均），连同总结写回 defense_records，供前端答辩记录展示
     */
    @SuppressWarnings("unchecked")
    private void finishDefenseAggregation(Integer defenseId, String summary) {
        try {
            if (defenseId == null) {
                log.warn("答辩收尾：defenseId为空，跳过总分落库");
                return;
            }
            Map<String, Object> aggregated = scorePersistenceService.aggregateScores(defenseId);
            List<DefenseScoreRecord> records = (List<DefenseScoreRecord>) aggregated.get("records");
            if (records == null || records.isEmpty()) {
                log.warn("答辩收尾：无评分记录，跳过总分落库 - defenseId: {}", defenseId);
                return;
            }
            double total = 0;
            for (DefenseScoreRecord r : records) {
                total += nvl(r.getExpressionScore()) + nvl(r.getLogicScore()) + nvl(r.getProfessionalScore())
                        + nvl(r.getAdaptabilityScore()) + nvl(r.getInnovationScore());
            }
            BigDecimal finalScore = BigDecimal.valueOf(Math.round(total / records.size() * 10) / 10.0);
            defenseRecordsService.finishDefenseRecord(defenseId, finalScore, summary);
            log.info("答辩收尾完成 - defenseId: {}, 总分: {}, 轮次数: {}", defenseId, finalScore, records.size());
        } catch (Exception e) {
            log.error("答辩收尾聚合失败 - defenseId: {}", defenseId, e);
        }
    }

    private double nvl(BigDecimal v) {
        return v == null ? 0.0 : v.doubleValue();
    }

    /** 从AI回复中提取"总结:"后的文本 */
    private String extractSummaryText(String aiResponse) {
        if (aiResponse == null) return null;
        int pos = aiResponse.indexOf("总结:");
        int posFull = aiResponse.indexOf("总结：");
        if (pos == -1 || (posFull != -1 && posFull < pos)) pos = posFull;
        if (pos == -1) return null;
        return aiResponse.substring(pos + 3).trim();
    }

    private String extractQuestionFromLastAiMessage(List<Message> history) {
        if (history == null || history.isEmpty()) return null;
        for (int i = history.size() - 1; i >= 0; i--) {
            Message msg = history.get(i);
            if (msg instanceof AssistantMessage) {
                String text = ((AssistantMessage) msg).getText();
                if (text != null && text.contains("【问题】")) {
                    int start = text.indexOf("【问题】") + 4;
                    String q = text.substring(start).trim();
                    if (!q.isEmpty()) return q;
                }
                String[] lines = text.split("\n");
                for (int j = lines.length - 1; j >= 0; j--) {
                    String line = lines[j].trim();
                    if (!line.isEmpty() && (line.contains("?") || line.contains("？"))) return line;
                }
                break;
            }
        }
        return null;
    }

    private DefenseStudentQuestions findExistingStudentQuestion(Integer defenseId, String question) {
        try {
            List<DefenseStudentQuestions> questions = defenseStudentQuestionsMapper.getQuestionsByDefenseId(defenseId);
            if (questions != null) {
                for (DefenseStudentQuestions q : questions) {
                    if (q.getCustomQuestion() != null && q.getCustomQuestion().equals(question)) return q;
                }
            }
        } catch (Exception e) {
            log.warn("查找已有问题时发生异常: {}", e.getMessage());
        }
        return null;
    }

    private void submitIfNewQuestion(Integer defenseId, String question) {
        try {
            DefenseStudentQuestions existing = findExistingStudentQuestion(defenseId, question);
            if (existing != null) return;
            int nextSort = defenseStudentQuestionsMapper.getNextSortNumber(defenseId);
            DefenseStudentQuestions sq = new DefenseStudentQuestions();
            sq.setDefenseId(defenseId);
            sq.setQuestionId(null);
            sq.setCustomQuestion(question);
            sq.setCustomStandardAnswer("");
            sq.setQuestionType("ai");
            sq.setSort(nextSort);
            sq.setCreatedAt(LocalDateTime.now());
            defenseStudentQuestionsMapper.insertStudentQuestion(sq);
        } catch (Exception e) {
            log.warn("保存额外问题时发生异常", e);
        }
    }

    private String summary(String aiResponse) {
        if (aiResponse == null || !aiResponse.contains("【总结】")) return null;
        int start = aiResponse.indexOf("【总结】") + 4;
        int end = aiResponse.length();
        if (aiResponse.contains("【视频分析】")) end = aiResponse.indexOf("【视频分析】");
        else if (aiResponse.contains("【报告分析】")) end = aiResponse.indexOf("【报告分析】");
        else if (aiResponse.contains("【总得分】")) end = aiResponse.indexOf("【总得分】");
        return aiResponse.substring(start, end).trim();
    }

    private String videoAnalysis(String aiResponse) {
        if (aiResponse == null || !aiResponse.contains("【视频分析】")) return null;
        int start = aiResponse.indexOf("【视频分析】") + 6;
        int end = aiResponse.length();
        if (aiResponse.contains("【报告分析】")) end = aiResponse.indexOf("【报告分析】");
        else if (aiResponse.contains("【总得分】")) end = aiResponse.indexOf("【总得分】");
        return aiResponse.substring(start, end).trim();
    }

    private String reportAnalysis(String aiResponse) {
        if (aiResponse == null || !aiResponse.contains("【报告分析】")) return null;
        int start = aiResponse.indexOf("【报告分析】") + 6;
        int end = aiResponse.length();
        if (aiResponse.contains("【总得分】")) end = aiResponse.indexOf("【总得分】");
        return aiResponse.substring(start, end).trim();
    }

    private Flux<String> handleFallback(String userId, Integer topicId, String prompt, String reason,
                                         String sessionId, String userInput) {
        String fallbackPrompt = reason + "，直接回答问题。\n\n" + prompt;
        return toFlux(sendMessageWithMemory(new ArrayList<>(), 0, 0, userId, topicId, fallbackPrompt, sessionId, userInput, 0));
    }

    // ==================== 视频辅助模式 Prompt ====================

    @SuppressWarnings("unchecked")
    private StringBuilder buildVideoAssistModePrompt(Object topicData, Integer topicId, String prompt) {
        StringBuilder contextPrompt = new StringBuilder();
        contextPrompt.append("【答辩主题信息】\n");
        contextPrompt.append("主题 ID: ").append(topicId).append("\n");

        if (topicData instanceof TopicDto) {
            TopicDto topic = (TopicDto) topicData;
            if (topic.getTopicName() != null) {
                contextPrompt.append("主题名称：").append(topic.getTopicName()).append("\n");
            }
            if (topic.getTopicDescription() != null) {
                contextPrompt.append("主题描述：").append(topic.getTopicDescription()).append("\n");
            }
        }

        contextPrompt.append("\n【系统提示】\n");
        contextPrompt.append("你是一名专业的答辩助手。\n");
        contextPrompt.append("当前答辩主题暂时没有预设问题，请结合题目和视频内容进行回答。\n");
        contextPrompt.append("请务必使用 getVideoContentByTopicId 工具查询 topicId=").append(topicId);
        contextPrompt.append(" 的视频文字内容（包括 PPT 文字和演讲内容）来获取详细信息。\n");
        contextPrompt.append("基于查询到的视频内容，为用户提供专业、准确的回答。\n\n");
        contextPrompt.append("【用户回答】\n").append(prompt);

        return contextPrompt;
    }
}
