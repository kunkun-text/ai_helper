package com.ai_helper.ai_helper.Controller;

import com.ai_helper.ai_helper.Service.DefenseRecordsService;
import com.ai_helper.ai_helper.Service.DefenseTopicsService;
import com.ai_helper.ai_helper.pojo.dto.TopicDto;
import com.ai_helper.ai_helper.pojo.entity.DefenseAnswers;
import com.ai_helper.ai_helper.pojo.entity.DefenseQuestions;
import com.ai_helper.ai_helper.pojo.entity.DefenseStudentQuestions;
import com.ai_helper.ai_helper.pojo.query.TextQuery;
import com.ai_helper.ai_helper.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

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
    private com.ai_helper.ai_helper.mapper.DefenseAnswersMapper defenseAnswersMapper;

    @Autowired
    private com.ai_helper.ai_helper.mapper.DefenseStudentQuestionsMapper defenseStudentQuestionsMapper;

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
                    return handleFallback(userId,topicId,finalUserInput, "未找到相关主题信息", finalSessionId, finalUserInput);
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
                        StringBuilder contextPrompt = buildQuestionModePrompt(
                                topicResult.getData(),questions, topicId, finalUserInput, userId);

                        return sendMessageWithMemory(existingQuestionIds, existingQuestionCount, userId, topicId, contextPrompt.toString(), finalSessionId, finalUserInput);
                    } else {
                        log.info("该题目下暂无问题，进入视频内容辅助回答模式");
                        StringBuilder contextPrompt = buildVideoAssistModePrompt(
                                topicResult.getData(), topicId, finalUserInput);

                        return sendMessageWithMemory(existingQuestionIds, existingQuestionCount, userId, topicId, contextPrompt.toString(), finalSessionId, finalUserInput);
                    }
                } else {
                    log.warn("查询题目信息失败，topicId: {}, code: {}, msg: {}",
                            topicId, questionResult.getCode(), questionResult.getMsg());
                    return handleFallback(userId,topicId,finalUserInput, "未找到相关题目信息", finalSessionId, finalUserInput);
                }

            } catch (Exception e) {
                log.error("查询题目信息时发生异常，topicId: {}", topicId, e);
                return handleFallback(userId,topicId,finalUserInput, "查询题目信息时发生错误", finalSessionId, finalUserInput);
            }
        } else {
            log.info("无 topicId，使用通用模式回答");
            return sendMessageWithMemory(new ArrayList<>(), 0, userId, null, finalUserInput, finalSessionId, finalUserInput);
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

private Flux<String> sendMessageWithMemory(List<Integer> existingQuestionIds, int existingQuestionCount, String userId, Integer topicId, String fullPrompt, String sessionId, String userInput) {
        List<Message> history = chatMemory.get(sessionId);

        log.info("【调试】sessionId: {}, 获取到的历史消息数量: {}", sessionId, history.size());
        for (int i = 0; i < history.size(); i++) {
            Message msg = history.get(i);
            String role = msg instanceof UserMessage ? "用户" : "AI";
            String preview = msg instanceof UserMessage ?
                ((UserMessage)msg).getText().substring(0, Math.min(30, ((UserMessage)msg).getText().length())) :
                ((AssistantMessage)msg).getText().substring(0, Math.min(30, ((AssistantMessage)msg).getText().length()));
            log.info("【调试】历史消息 {}: {} - {}", i + 1, role, preview);
        }

        StringBuilder completePrompt = new StringBuilder();

        if (!history.isEmpty()) {
            completePrompt.append("【对话历史】\n");
            for (Message msg : history) {
                if (msg instanceof UserMessage userMsg) {
                    completePrompt.append("用户：").append(userMsg.getText()).append("\n");
                } else if (msg instanceof AssistantMessage asstMsg) {
                    completePrompt.append("助手：").append(asstMsg.getText()).append("\n");
                }
            }
            completePrompt.append("\n【最新请求】\n");
        }

        completePrompt.append(fullPrompt);

        StringBuilder aiResponseBuilder = new StringBuilder();

        Flux<String> response = chatClient.prompt()
                .user(completePrompt.toString())
                .stream()
                .content()
                .doOnNext(chunk -> {
                    aiResponseBuilder.append(chunk);
                });

        return response.doOnComplete(() -> {
            try {
                String aiResponse = aiResponseBuilder.toString();

                log.info("【调试】AI回复完成，长度: {}", aiResponse.length());

                if(topicId != null && userId != null) {
                    int assistantCountInHistory = countAssistantMessages(history);

                    log.info("【关键判断】保存前 - 历史中的AI消息数: {}, 已有问题数量: {}", assistantCountInHistory, existingQuestionCount);

                    if (userInput != null && !userInput.trim().isEmpty()) {
                        Integer defenseId = defenseRecordsService.getOrCreateDefenseRecord(topicId, userId);
                        
                        if (assistantCountInHistory < existingQuestionCount) {
                            log.info("✅ 当前是第{}个预设问题的回答（共{}个），准备保存",
                                    assistantCountInHistory + 1, existingQuestionCount);

                            try {
                                if (assistantCountInHistory < existingQuestionIds.size()) {
                                    Integer currentQuestionId = existingQuestionIds.get(assistantCountInHistory);
                                    log.info("保存预设问题回答 - questionId: {}, userId: {}", currentQuestionId, userId);

                                    Double score = extractScoreFromResponse(aiResponse);
                                    String feedback = extractFeedbackFromResponse(aiResponse);

                                    defenseRecordsService.savePresetQuestionAnswer(
                                            topicId, userId, currentQuestionId, userInput, feedback, score);

                                    log.info("✅ 预设问题回答保存成功，得分: {}, 评价长度: {}", score, feedback.length());
                                } else {
                                    log.warn("⚠️ 无法获取对应的预设问题ID");
                                }
                            } catch (Exception e) {
                                log.error("❌ 保存预设问题回答时发生异常", e);
                            }
                        } else {
                            log.info("✅ 额外问题阶段（历史AI消息数={}, 预设问题数={}）",
                                    assistantCountInHistory, existingQuestionCount);

                            Double score = extractScoreFromResponse(aiResponse);
                            String feedback = extractFeedbackFromResponse(aiResponse);

                            if (defenseId == null) {
                                log.error("❌ defenseId 为 null，无法保存额外问题回答");
                            } else {
                                int currentExtraQuestionIndex = assistantCountInHistory - existingQuestionCount + 1;
                                
                                String currentQuestionBeingAnswered = getCurrentQuestionFromHistory(history, existingQuestionCount, currentExtraQuestionIndex, defenseId);

                                log.info("【调试】当前正在回答第{}个额外问题: {}", currentExtraQuestionIndex, currentQuestionBeingAnswered != null ? currentQuestionBeingAnswered.substring(0, Math.min(50, currentQuestionBeingAnswered.length())) : "未找到");

                                if (currentQuestionBeingAnswered != null && !currentQuestionBeingAnswered.isEmpty()) {
                                    log.info("✅ 找到当前问题,准备保存问题和回答");
                                    
                                    DefenseStudentQuestions existingQuestion = findExistingStudentQuestion(defenseId, currentQuestionBeingAnswered);

                                    Integer sqId;
                                    if (existingQuestion != null) {
                                        sqId = existingQuestion.getSqId();
                                        log.info("✅ 使用已存在的问题记录 - sqId: {}", sqId);
                                    } else {
                                        int nextSort = defenseStudentQuestionsMapper.getNextSortNumber(defenseId);

                                        DefenseStudentQuestions studentQuestion = new DefenseStudentQuestions();
                                        studentQuestion.setDefenseId(defenseId);
                                        studentQuestion.setQuestionId(null);
                                        studentQuestion.setCustomQuestion(currentQuestionBeingAnswered);
                                        studentQuestion.setCustomStandardAnswer("");
                                        studentQuestion.setQuestionType("ai");
                                        studentQuestion.setSort(nextSort);
                                        studentQuestion.setCreatedAt(java.time.LocalDateTime.now());

                                        defenseStudentQuestionsMapper.insertStudentQuestion(studentQuestion);
                                        sqId = studentQuestion.getSqId();
                                        log.info("✅ 创建新问题记录 - sqId: {}, sort: {}", sqId, nextSort);
                                    }
                                    
                                    if (sqId != null) {
                                        DefenseAnswers answer = new DefenseAnswers();
                                        answer.setDefenseId(defenseId);
                                        answer.setQuestionId(null);
                                        answer.setSqId(sqId);
                                        answer.setStudentAnswer(userInput);
                                        answer.setFeedback(feedback);
                                        answer.setScore(score != null ? new java.math.BigDecimal(score) : null);
                                        answer.setCreatedAt(java.time.LocalDateTime.now());
                                        
                                        defenseAnswersMapper.insertAnswer(answer);
                                        log.info("✅ 额外问题回答保存成功 - sqId: {}, 得分: {}", sqId, score);
                                    } else {
                                        log.warn("⚠️ sqId 为 null，无法保存回答");
                                    }
                                } else {
                                    log.warn("⚠️ 未找到当前正在回答的额外问题，可能是第一个额外问题");
                                    
                                    String firstExtraQuestion = extractFirstExtraQuestionFromPreviousAiResponse(history, existingQuestionCount);
                                    
                                    if (firstExtraQuestion != null && !firstExtraQuestion.isEmpty()) {
                                        log.info("✅ 从上一条AI消息中提取到第一个额外问题: {}", firstExtraQuestion.substring(0, Math.min(50, firstExtraQuestion.length())));
                                        
                                        DefenseStudentQuestions existingFirstQuestion = findExistingStudentQuestion(defenseId, firstExtraQuestion);
                                        
                                        Integer sqId;
                                        if (existingFirstQuestion != null) {
                                            sqId = existingFirstQuestion.getSqId();
                                            log.info("✅ 使用已存在的第一个额外问题 - sqId: {}", sqId);
                                        } else {
                                            int nextSort = defenseStudentQuestionsMapper.getNextSortNumber(defenseId);
                                            
                                            DefenseStudentQuestions studentQuestion = new DefenseStudentQuestions();
                                            studentQuestion.setDefenseId(defenseId);
                                            studentQuestion.setQuestionId(null);
                                            studentQuestion.setCustomQuestion(firstExtraQuestion);
                                            studentQuestion.setCustomStandardAnswer("");
                                            studentQuestion.setQuestionType("ai");
                                            studentQuestion.setSort(nextSort);
                                            studentQuestion.setCreatedAt(java.time.LocalDateTime.now());
                                            
                                            defenseStudentQuestionsMapper.insertStudentQuestion(studentQuestion);
                                            sqId = studentQuestion.getSqId();
                                            log.info("✅ 创建第一个额外问题记录 - sqId: {}, sort: {}", sqId, nextSort);
                                        }
                                        
                                        if (sqId != null) {
                                            DefenseAnswers answer = new DefenseAnswers();
                                            answer.setDefenseId(defenseId);
                                            answer.setQuestionId(null);
                                            answer.setSqId(sqId);
                                            answer.setStudentAnswer(userInput);
                                            answer.setFeedback(feedback);
                                            answer.setScore(score != null ? new java.math.BigDecimal(score) : null);
                                            answer.setCreatedAt(java.time.LocalDateTime.now());
                                            
                                            defenseAnswersMapper.insertAnswer(answer);
                                            log.info("✅ 第一个额外问题回答保存成功 - sqId: {}, 得分: {}", sqId, score);
                                        }
                                    } else {
                                        log.warn("⚠️ 无法从历史中提取第一个额外问题");
                                    }
                                }
                                
                                String nextQuestion = extractNextQuestionFromResponse(aiResponse);
                                if (nextQuestion != null && !nextQuestion.isEmpty()) {
                                    log.info("✅ AI提出了新的额外问题，检查是否需要保存");
                                    
                                    DefenseStudentQuestions existingNextQuestion = findExistingStudentQuestion(defenseId, nextQuestion);
                                    
                                    if (existingNextQuestion == null) {
                                        int nextSort = defenseStudentQuestionsMapper.getNextSortNumber(defenseId);
                                        
                                        DefenseStudentQuestions studentQuestion = new DefenseStudentQuestions();
                                        studentQuestion.setDefenseId(defenseId);
                                        studentQuestion.setQuestionId(null);
                                        studentQuestion.setCustomQuestion(nextQuestion);
                                        studentQuestion.setCustomStandardAnswer("");
                                        studentQuestion.setQuestionType("ai");
                                        studentQuestion.setSort(nextSort);
                                        studentQuestion.setCreatedAt(java.time.LocalDateTime.now());
                                        
                                        defenseStudentQuestionsMapper.insertStudentQuestion(studentQuestion);
                                        log.info("✅ 新额外问题已保存 - sqId: {}, sort: {}", studentQuestion.getSqId(), nextSort);
                                    } else {
                                        log.info("✅ 新额外问题已存在，跳过保存 - sqId: {}", existingNextQuestion.getSqId());
                                    }
                                } else {
                                    log.info("ℹ️ AI回复中没有新的额外问题");
                                }
                            }
                        }
                    } else {
                        log.info("⏸️ 用户未提供回答（可能是首次提问或总结阶段），不保存回答记录");
                    }
                } else {
                    log.warn("⚠️ topicId 或 userId 为空，跳过问题保存检查 - topicId: {}, userId: {}", topicId, userId);
                }

                if (userInput != null && !userInput.trim().isEmpty()) {
                    log.info("【调试】准备保存消息到Redis - sessionId: {}", sessionId);

                    chatMemory.add(sessionId, List.of(
                        new UserMessage(userInput),
                        new AssistantMessage(aiResponse)
                    ));

                    log.info("【调试】消息已保存到Redis");

                    List<Message> verifyHistory = chatMemory.get(sessionId);
                    log.info("【调试】验证：保存后Redis中的消息数量: {}", verifyHistory.size());
                }

                log.info("会话记忆已更新 - sessionId: {}, 用户消息长度：{}, AI 回复长度：{}",
                        sessionId, userInput != null ? userInput.length() : 0, aiResponse.length());

            } catch (Exception e) {
                log.error("存储会话记忆失败 - sessionId: {}, error: {}", sessionId, e.getMessage(), e);
            }



        });


    }

    private int countAssistantMessages(List<Message> history) {
        int count = 0;
        for (Message msg : history) {
            if (msg instanceof AssistantMessage) {
                count++;
            }
        }
        log.info("【调试】countAssistantMessages - 历史消息总数: {}, AI消息数: {}", history.size(), count);
        return count;
    }

    private String extractFirstExtraQuestionFromPreviousAiResponse(List<Message> history, int existingQuestionCount) {
        if (history == null || history.isEmpty()) {
            return null;
        }
        
        int aiCount = 0;
        for (int i = history.size() - 1; i >= 0; i--) {
            Message msg = history.get(i);
            if (msg instanceof AssistantMessage) {
                aiCount++;
                
                if (aiCount == 1) {
                    String aiText = ((AssistantMessage) msg).getText();
                    
                    if (aiText.contains("【问题】")) {
                        int startIndex = aiText.indexOf("【问题】") + 4;
                        String question = aiText.substring(startIndex).trim();
                        
                        if (!question.isEmpty()) {
                            log.info("从上一条AI消息中提取到第一个额外问题");
                            return question;
                        }
                    }
                    
                    String[] lines = aiText.split("\n");
                    for (int j = lines.length - 1; j >= 0; j--) {
                        String line = lines[j].trim();
                        if (!line.isEmpty() &&
                            (line.contains("?") || line.contains("？") ||
                             line.matches(".*请.*回答.*") || line.matches(".*你的.*看法.*"))) {
                            log.info("从上一条AI消息最后一行提取到问题: {}", line.substring(0, Math.min(50, line.length())));
                            return line;
                        }
                    }
                    
                    break;
                }
            }
        }
        
        return null;
    }

    private String extractQuestionFromResponse(String aiResponse, int assistantCount, int existingQuestionCount) {
        if (aiResponse == null || aiResponse.isEmpty()) {
            return null;
        }

        if (assistantCount >= existingQuestionCount) {
            if (aiResponse.contains("【总结】")) {
                log.info("检测到总结内容，不提取问题");
                return null;
            }

            if (aiResponse.contains("【问题】")) {
                int startIndex = aiResponse.indexOf("【问题】") + 4;
                String question = aiResponse.substring(startIndex).trim();
                log.info("从【问题】标记中提取到问题: {}", question.substring(0, Math.min(50, question.length())));
                return question;
            }
        }

        String[] lines = aiResponse.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (!line.isEmpty() &&
                (line.contains("?") || line.contains("？") ||
                 line.matches(".*请.*回答.*") || line.matches(".*你的.*看法.*") ||
                 line.matches(".*你怎么.*"))) {
                log.info("从最后一行提取到问题: {}", line.substring(0, Math.min(50, line.length())));
                return line;
            }
        }

        log.info("未找到明确的问题，返回完整回复");
        return aiResponse;
    }

    private String extractNextQuestionFromResponse(String aiResponse) {
        if (aiResponse == null || aiResponse.isEmpty()) {
            return null;
        }

        if (aiResponse.contains("【问题】")) {
            int startIndex = aiResponse.indexOf("【问题】") + 4;
            String question = aiResponse.substring(startIndex).trim();

            if (!question.isEmpty()) {
                log.info("提取到下一个问题: {}", question.substring(0, Math.min(50, question.length())));
                return question;
            }
        }

        return null;
    }

    private String getCurrentQuestionFromHistory(List<Message> history, int existingQuestionCount, int currentExtraQuestionIndex, Integer defenseId) {
        if (history == null || history.isEmpty()) {
            return null;
        }

        log.info("【调试】查找第{}个额外问题", currentExtraQuestionIndex);

        List<com.ai_helper.ai_helper.pojo.entity.DefenseStudentQuestions> savedQuestions =
            defenseStudentQuestionsMapper.getQuestionsByDefenseId(defenseId);

        if (currentExtraQuestionIndex > 0 && savedQuestions != null && currentExtraQuestionIndex <= savedQuestions.size()) {
            String question = savedQuestions.get(currentExtraQuestionIndex - 1).getCustomQuestion();
            log.info("从已保存的问题中找到第{}个额外问题: {}", currentExtraQuestionIndex, question.substring(0, Math.min(50, question.length())));
            return question;
        }

        log.warn("⚠️ 无法从数据库中找到第{}个额外问题，savedQuestions大小: {}", currentExtraQuestionIndex, savedQuestions != null ? savedQuestions.size() : 0);

        return null;
    }

    private com.ai_helper.ai_helper.pojo.entity.DefenseStudentQuestions findExistingStudentQuestion(Integer defenseId, String question) {
        try {
            List<com.ai_helper.ai_helper.pojo.entity.DefenseStudentQuestions> questions =
                defenseStudentQuestionsMapper.getQuestionsByDefenseId(defenseId);

            if (questions != null) {
                for (com.ai_helper.ai_helper.pojo.entity.DefenseStudentQuestions q : questions) {
                    if (q.getCustomQuestion() != null && q.getCustomQuestion().equals(question)) {
                        return q;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("查找已有问题时发生异常: {}", e.getMessage());
        }

        return null;
    }

    private Double extractScoreFromResponse(String aiResponse) {
        if (aiResponse == null || aiResponse.isEmpty()) {
            return null;
        }

        try {
            if (aiResponse.contains("【得分】")) {
                int startIndex = aiResponse.indexOf("【得分】") + 4;
                int endIndex = aiResponse.indexOf("\n", startIndex);
                if (endIndex == -1) {
                    endIndex = aiResponse.length();
                }
                String scoreStr = aiResponse.substring(startIndex, endIndex).trim();

                scoreStr = scoreStr.replaceAll("[^0-9.]", "");

                if (!scoreStr.isEmpty()) {
                    double score = Double.parseDouble(scoreStr);
                    log.info("从【得分】标记中提取到分数: {}", score);
                    return score;
                }
            }

            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*分");
            java.util.regex.Matcher matcher = pattern.matcher(aiResponse);

            if (matcher.find()) {
                double score = Double.parseDouble(matcher.group(1));
                log.info("从正则表达式中提取到分数: {}", score);
                return score;
            }

        } catch (Exception e) {
            log.warn("提取分数失败: {}", e.getMessage());
        }

        log.info("未找到分数信息");
        return null;
    }

    private String extractFeedbackFromResponse(String aiResponse) {
        if (aiResponse == null || aiResponse.isEmpty()) {
            return aiResponse;
        }

        try {
            if (aiResponse.contains("【评价】")) {
                int startIndex = aiResponse.indexOf("【评价】") + 4;

                int endIndex = aiResponse.length();
                if (aiResponse.contains("【得分】")) {
                    endIndex = aiResponse.indexOf("【得分】");
                } else if (aiResponse.contains("【问题】")) {
                    endIndex = aiResponse.indexOf("【问题】");
                } else if (aiResponse.contains("【总结】")) {
                    endIndex = aiResponse.indexOf("【总结】");
                }

                String feedback = aiResponse.substring(startIndex, endIndex).trim();
                log.info("从【评价】标记中提取到评价内容，长度: {}", feedback.length());
                return feedback;
            }

            String[] lines = aiResponse.split("\n");
            StringBuilder feedbackBuilder = new StringBuilder();

            for (String line : lines) {
                if (line.trim().startsWith("【得分】") ||
                    line.trim().startsWith("【问题】") ||
                    line.trim().startsWith("【总结】")) {
                    break;
                }
                if (!line.trim().isEmpty()) {
                    if (feedbackBuilder.length() > 0) {
                        feedbackBuilder.append("\n");
                    }
                    feedbackBuilder.append(line);
                }
            }

            if (feedbackBuilder.length() > 0) {
                log.info("从行解析中提取到评价内容，长度: {}", feedbackBuilder.length());
                return feedbackBuilder.toString();
            }

        } catch (Exception e) {
            log.warn("提取评价失败: {}", e.getMessage());
        }

        log.info("未找到明确的评价标记，返回完整回复");
        return aiResponse;
    }

    private String summary(String aiResponse) {
        if (aiResponse == null || aiResponse.isEmpty()) {
            return aiResponse;
        }

        try {
            if (aiResponse.contains("【总结】")) {
                int startIndex = aiResponse.indexOf("【总结】") + 4;

                int endIndex = aiResponse.length();
                if (aiResponse.contains("【视频分析】")) {
                    endIndex = aiResponse.indexOf("【视频分析】");
                }

                String summary = aiResponse.substring(startIndex, endIndex).trim();
                log.info("从【评价】标记中提取到评价内容，长度: {}", summary.length());
                return summary;
            }

            String[] lines = aiResponse.split("\n");
            StringBuilder feedbackBuilder = new StringBuilder();

            for (String line : lines) {
                if (line.trim().startsWith("【视频分析】") ||
                        line.trim().startsWith("【报告分析】") ||
                        line.trim().startsWith("【总得分】")) {
                    break;
                }
                if (!line.trim().isEmpty()) {
                    if (feedbackBuilder.length() > 0) {
                        feedbackBuilder.append("\n");
                    }
                    feedbackBuilder.append(line);
                }
            }

            if (feedbackBuilder.length() > 0) {
                log.info("从行解析中提取总结，长度: {}", feedbackBuilder.length());
                return feedbackBuilder.toString();
            }

        } catch (Exception e) {
            log.warn("提取总结失败: {}", e.getMessage());
        }

        log.info("未找到明确的总结标记，返回完整回复");
        return aiResponse;
    }

    private Flux<String> handleFallback(String userId, Integer topicId, String prompt, String reason, String sessionId, String userInput) {
        String fallbackPrompt = reason + "，直将接回答问题。\n\n" + prompt;
        return sendMessageWithMemory(new ArrayList<>(), 0, userId, topicId, fallbackPrompt, sessionId, userInput);
    }

    @SuppressWarnings("unchecked")
    private StringBuilder buildQuestionModePrompt(Object topicData, List<DefenseQuestions> questions,
                                                  Integer topicId, String prompt,String userId) {
        StringBuilder contextPrompt = new StringBuilder();
        contextPrompt.append("=== 答辩考试场景 ===\n\n");

        contextPrompt.append("【你的角色】\n");
        contextPrompt.append("你是一名专业的答辩考官，正在主持一场答辩考试。\n");
        contextPrompt.append("你的任务是根据下面的题目列表，逐一向学生提问，每次只问一个问题，并评估他们的回答。\n\n");

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
        TextQuery videoContent = defenseRecordsService.selectVideoAndPptWords(topicId,userId);

        contextPrompt.append("\n【学生上传答辩视频转换的文字以及ppt提取的文字】（你可以参考这些进行后续提问）\n");


        contextPrompt.append("\n【答辩题目列表】（这是你作为考官要提的问题，问题问完后，你可以参考学生答辩的内容以及答辩项目进行适当提问）\n");

        for (int i = 0; i < questions.size(); i++) {
            DefenseQuestions q = questions.get(i);
            contextPrompt.append("\n### 考题 ").append(i + 1).append("\n");
            contextPrompt.append("类型：").append(q.getQuestionType()).append("\n");
            contextPrompt.append("问题：").append(q.getQuestion()).append("\n");

            if (q.getStandardAnswer() != null && !q.getStandardAnswer().isEmpty()) {
                contextPrompt.append("评分参考：").append(q.getStandardAnswer()).append("\n");
            }
            if (q.getAiStandardAnswer() != null && !q.getAiStandardAnswer().isEmpty()) {
                contextPrompt.append("补充参考：").append(q.getAiStandardAnswer()).append("\n");
            }
        }

        contextPrompt.append("\n\n【答辩规则】\n");
        contextPrompt.append("1. 你必须严格按照题目列表中的问题进行提问，不能更改问题内容\n");
        contextPrompt.append("2. 每次只提一个问题，等待学生回答\n");
        contextPrompt.append("3. 根据学生的回答，使用评分参考进行评价\n");
        contextPrompt.append("4. 题目列表提问完后，你可以根据答辩项目以及学生答辩的ppt和内容进行适当多余提问\n");
        contextPrompt.append("5. 【重要】额外提问最多只能提 2 个问题，提完 2 个额外问题后必须进入总结阶段\n");
        contextPrompt.append("6. 完成所有问题（预设问题 + 最多2个额外问题）后，给出总体评价和总结\n\n");

        contextPrompt.append("【评分标准】\n");
        contextPrompt.append("- 每个问题满分 100 分\n");
        contextPrompt.append("- 评分维度：准确性(40%)、完整性(30%)、逻辑性(20%)、表达清晰度(10%)\n");
        contextPrompt.append("- 必须在评价中明确给出分数\n\n");

        contextPrompt.append("【重要指令】\n");
        contextPrompt.append("- 现在请开始考试：先向学生问好，然后直接提出第 1 个问题\n");
        contextPrompt.append("- 不要暴露你有参考答案，这些是给你的评分标准\n");
        contextPrompt.append("- 保持专业、严肃的考官语气\n\n");

        contextPrompt.append("【输出格式要求】\n");
        contextPrompt.append("- 如果你的回复中包含评价和新问题，请使用以下格式：\n");
        contextPrompt.append("  【评价】你的评价内容...\n");
        contextPrompt.append("  【得分】XX分\n");
        contextPrompt.append("  【问题】你的新问题...\n");
        contextPrompt.append("- 如果只有评价没有新问题（如最后总结），请使用：\n");
        contextPrompt.append("  【总结】你的总结内容...\n");
        contextPrompt.append("  【视频分析】你对视频的分析...\n");
        contextPrompt.append("  【报告分析】你对报告的分析...\n");
        contextPrompt.append("  【总得分】XX分\n");
        contextPrompt.append("- 如果只有问题，直接输出问题即可\n\n");

        if (prompt != null && !prompt.trim().isEmpty()) {
            contextPrompt.append("【学生当前回答】\n");
            contextPrompt.append(prompt).append("\n\n");
            contextPrompt.append("请根据学生的上述回答进行评价、打分，并继续提问。\n");
        } else {
            contextPrompt.append("【开始答辩】\n");
            contextPrompt.append("请向考生问好并开始提问。\n");
        }

        return contextPrompt;
    }

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
