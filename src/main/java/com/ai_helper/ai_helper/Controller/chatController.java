package com.ai_helper.ai_helper.Controller;

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
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

    /** 额外问题上限 */
    private static final int EXTRA_QUESTION_LIMIT = 2;

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
                        int extraAskedCount = countExtraQuestions(topicId, userId);

                        // --- 获取当前轮次：基于历史中AI消息数 ---
                        List<Message> history = chatMemory.get(finalSessionId);
                        int currentRound = countAssistantMessages(history);

                        StringBuilder contextPrompt = buildQuestionModePrompt(
                                topicResult.getData(), questions, topicId, finalUserInput, userId,
                                extraAskedCount, extraQuestionLimit, currentRound, existingQuestionIds);

                        return toFlux(sendMessageWithMemory(existingQuestionIds, existingQuestionCount, extraAskedCount, userId, topicId,
                                contextPrompt.toString(), finalSessionId, finalUserInput));
                    } else {
                        log.info("该题目下暂无问题，进入视频内容辅助回答模式");
                        StringBuilder contextPrompt = buildVideoAssistModePrompt(
                                topicResult.getData(), topicId, finalUserInput);

                        return toFlux(sendMessageWithMemory(existingQuestionIds, existingQuestionCount, 0, userId, topicId,
                                contextPrompt.toString(), finalSessionId, finalUserInput));
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
            return toFlux(sendMessageWithMemory(new ArrayList<>(), 0, 0, userId, null, finalUserInput, finalSessionId, finalUserInput));
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

        p.append("你是答辩评委，正在对学生进行一对一答辩考核。除首轮外，你必须严格按照以下三行格式输出，顺序不可颠倒，每行以固定标签开头，不要任何多余内容：\n");
        p.append("点评:（40字以内，先一句话肯定优点，再具体指出不足和一条改进建议，必须结合学生刚才的实际回答，禁止空话套话）\n");
        p.append("评分:总分/50|表达分|逻辑分|专业分|应变分|创新分（各0-10整数，五维分数相加必须等于总分）\n");
        p.append("下一题:（30字以内，提问下一道题目）\n");
        p.append("示例：\n点评:概念阐述准确、逻辑清晰，但缺少实际案例支撑，建议结合具体业务场景补充说明。\n评分:38/50|8|7|8|7|8\n下一题:请解释HDFS中NameNode的作用。\n\n");
        p.append("打分必须客观公正：学生回答正确、完整、条理清晰才给高分；回答错误、答非所问、含糊其辞或直接说“不知道”必须给低分（对应维度只给0-4分，总分不超过25/50），严禁凭印象乱给高分。\n\n");
        p.append("特别注意：学生回答“不知道/不会/不清楚”类短语时，本题五维全部给0分，点评后必须照常输出『下一题』继续提问；严禁因此输出『总结』或提前结束答辩，除非本轮提示明确说明这是最后一轮。\n\n");

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

        return p;
    }

    // ==================== 消息发送与记忆管理 ====================

    private String sendMessageWithMemory(List<Integer> existingQuestionIds, int existingQuestionCount,
                                                int extraAskedCount, String userId, Integer topicId, String fullPrompt,
                                                String sessionId, String userInput) {
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

        // --- 学生放弃作答（"不知道/不会"类短语）：不调用评分模型，本题零分并直接进入下一题 ---
        if (topicId != null && userId != null && isGiveUpAnswer(userInput)) {
            String fixedResponse = handleGiveUpAnswer(existingQuestionIds, existingQuestionCount,
                    extraAskedCount, userId, topicId, userInput, sessionId, history);
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
                // 轮次口径与 chat() 一致：基于未裁剪历史统计，避免裁剪到12条后轮次封顶错乱
                int assistantCountInHistory = countAssistantMessages(history);

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
                    boolean hasSummary = aiResponse != null
                            && (aiResponse.contains("总结:") || aiResponse.contains("总结："));
                    if (hasSummary && extraAskedCount >= EXTRA_QUESTION_LIMIT) {
                        finishDefenseAggregation(defenseId, extractSummaryText(aiResponse));
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
        if (extraAskedCount >= EXTRA_QUESTION_LIMIT) return aiResponse;

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

    // ==================== 放弃作答（"不知道/不会"）固定流程 ====================

    /** 判定为放弃作答的短语 */
    private static final String[] GIVE_UP_PHRASES = {
            "不知道", "不会", "不清楚", "不了解", "不懂", "没学过", "没复习", "没准备", "没印象", "跳过"
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

    /**
     * 学生放弃作答的固定处理：本题五维0分、不调用评分模型，直接给出下一题或收尾总结。
     *
     * @return 固定模板响应；返回 null 表示轮次/题库异常，需回退正常模型流程
     */
    private String handleGiveUpAnswer(List<Integer> existingQuestionIds, int existingQuestionCount,
                                      int extraAskedCount, String userId, Integer topicId,
                                      String userInput, String sessionId, List<Message> history) {
        try {
            int currentRound = countAssistantMessages(history);
            int qi = currentRound - 1;
            boolean inPresetPhase = qi >= 0 && qi < existingQuestionCount;
            boolean quotaRemains = extraAskedCount < EXTRA_QUESTION_LIMIT;

            Integer defenseId = defenseRecordsService.getOrCreateDefenseRecord(topicId, userId);
            if (defenseId == null) {
                log.warn("放弃作答处理：无法获取答辩记录，回退正常流程 - topicId: {}, userId: {}", topicId, userId);
                return null;
            }

            String zeroComment = "学生表示不知道该题，本题计0分，建议课后补强该知识点。";

            // 先确定下一题：预设题未问完 → 题库取下一题；追问额度未用完 → 模型生成追问；否则收尾
            boolean terminal = false;
            String nextQuestion = null;
            if (inPresetPhase && qi + 1 < existingQuestionCount) {
                nextQuestion = fetchPresetQuestionText(topicId, qi + 1);
            } else if (quotaRemains) {
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

            log.info("学生放弃作答，走固定零分流程 - defenseId: {}, round: {}, terminal: {}",
                    defenseId, currentRound, terminal);
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
        return toFlux(sendMessageWithMemory(new ArrayList<>(), 0, 0, userId, topicId, fallbackPrompt, sessionId, userInput));
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
