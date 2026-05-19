package com.ai_helper.ai_helper.Controller;

import com.ai_helper.ai_helper.Service.DefenseRecordsService;
import com.ai_helper.ai_helper.Service.DefenseTopicsService;
import com.ai_helper.ai_helper.pojo.dto.AiAnalysis;
import com.ai_helper.ai_helper.pojo.dto.TopicDto;
import com.ai_helper.ai_helper.pojo.entity.DefenseAnswers;
import com.ai_helper.ai_helper.pojo.entity.DefenseQuestions;
import com.ai_helper.ai_helper.pojo.entity.DefenseStudentQuestions;
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

                        // 统计已提的额外问题数，用于动态限制
                        int extraQuestionLimit = 2;
                        int extraAskedCount = countExtraQuestions(topicId, userId);

                        StringBuilder contextPrompt = buildQuestionModePrompt(
                                topicResult.getData(), questions, topicId, finalUserInput, userId, extraAskedCount, extraQuestionLimit);

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

    /**
     * 统计该学生该主题下已提的额外问题数
     */
    private int countExtraQuestions(Integer topicId, String userId) {
        try {
            Integer defenseId = defenseRecordsService.getOrCreateDefenseRecord(topicId, userId);
            if (defenseId == null) return 0;
            List<DefenseStudentQuestions> list = defenseStudentQuestionsMapper.getQuestionsByDefenseId(defenseId);
            // 额外问题只有 ai 类型的（预设问题是 teacher 类型）
            int count = 0;
            if (list != null) {
                for (DefenseStudentQuestions q : list) {
                    if ("ai".equals(q.getQuestionType())) {
                        count++;
                    }
                }
            }
            log.info("已提额外问题数: {}/{}", count, defenseId);
            return count;
        } catch (Exception e) {
            log.warn("统计额外问题数失败", e);
            return 0;
        }
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

                        Double score = extractScoreFromResponse(aiResponse);
                        String feedback = extractFeedbackFromResponse(aiResponse);

                        // 用 <= 确保第 N 个预设问题的回答能进入预设路径
                        if (assistantCountInHistory <= existingQuestionCount) {
                            log.info("当前是第{}个预设问题的回答（共{}个），准备保存",
                                    assistantCountInHistory, existingQuestionCount);

                            try {
                                int questionIndex = assistantCountInHistory - 1;
                                if (questionIndex >= 0 && questionIndex < existingQuestionIds.size()) {
                                    Integer currentQuestionId = existingQuestionIds.get(questionIndex);
                                    log.info("保存预设问题回答 - questionId: {}, userId: {}", currentQuestionId, userId);

                                    defenseRecordsService.savePresetQuestionAnswer(
                                            topicId, userId, currentQuestionId, userInput, feedback, score);

                                    log.info("预设问题回答保存成功，得分: {}, 评价长度: {}", score, feedback != null ? feedback.length() : 0);
                                } else {
                                    log.warn("无法获取对应的预设问题ID");
                                }
                            } catch (Exception e) {
                                log.error("保存预设问题回答时发生异常", e);
                            }
                        } else {
                            log.info("额外问题阶段（历史AI消息数={}, 预设问题数={}）",
                                    assistantCountInHistory, existingQuestionCount);

                            // 保存总结/视频分析/报告分析到答辩记录
                            AiAnalysis aiAnalysis = new AiAnalysis();
                            aiAnalysis.setDefenseId(defenseId);
                            aiAnalysis.setUserId(userId);
                            aiAnalysis.setFeedback(summary(aiResponse));
                            aiAnalysis.setVideoAnalysis(videoAnalysis(aiResponse));
                            aiAnalysis.setReportAnalysis(reportAnalysis(aiResponse));

                            // 提取总得分并设置到 AiAnalysis
                            Double totalScore = extractTotalScoreFromResponse(aiResponse);
                            if (totalScore != null) {
                                aiAnalysis.setScore(totalScore);
                                log.info("设置总得分: {} 到 defense_records", totalScore);
                            }

                            if (aiAnalysis.getFeedback() != null || aiAnalysis.getVideoAnalysis() != null || aiAnalysis.getReportAnalysis() != null) {
                                log.info("检测到总结内容，准备保存答辩总反馈");
                                defenseAnswersMapper.insertAiFeedback(aiAnalysis);
                                log.info("答辩总结保存成功");
                            }

                            if (defenseId != null) {
                                // 从历史中获取上一条AI消息末尾的问题（即当前正在回答的问题）
                                String currentQuestion = extractQuestionFromLastAiMessage(history);
                                log.info("从历史中提取当前问题: {}",
                                        currentQuestion != null ? currentQuestion.substring(0, Math.min(50, currentQuestion.length())) : "未找到");

                                if (currentQuestion != null && !currentQuestion.isEmpty()) {
                                    DefenseStudentQuestions existingQuestion = findExistingStudentQuestion(defenseId, currentQuestion);

                                    Integer sqId;
                                    if (existingQuestion != null) {
                                        sqId = existingQuestion.getSqId();
                                        log.info("使用已存在的额外问题记录 - sqId: {}", sqId);
                                    } else {
                                        int nextSort = defenseStudentQuestionsMapper.getNextSortNumber(defenseId);
                                        DefenseStudentQuestions studentQuestion = new DefenseStudentQuestions();
                                        studentQuestion.setDefenseId(defenseId);
                                        studentQuestion.setQuestionId(null);
                                        studentQuestion.setCustomQuestion(currentQuestion);
                                        studentQuestion.setCustomStandardAnswer("");
                                        studentQuestion.setQuestionType("ai");
                                        studentQuestion.setSort(nextSort);
                                        studentQuestion.setCreatedAt(java.time.LocalDateTime.now());
                                        defenseStudentQuestionsMapper.insertStudentQuestion(studentQuestion);
                                        sqId = studentQuestion.getSqId();
                                        log.info("创建新的额外问题记录 - sqId: {}, sort: {}", sqId, nextSort);
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
                                        log.info("额外问题回答保存成功 - sqId: {}, 得分: {}", sqId, score);
                                    }
                                } else {
                                    log.warn("无法从历史中提取当前问题，跳过额外问题保存");
                                }
                            }
                        }

                        // ===== 无论预设题还是额外题阶段，都保存AI回复中的下一个额外问题 =====
                        if (defenseId != null) {
                            String nextQuestion = extractNextQuestionFromResponse(aiResponse);
                            if (nextQuestion != null && !nextQuestion.isEmpty()) {
                                submitIfNewQuestion(defenseId, nextQuestion);
                            } else {
                                log.info("AI回复中没有新的额外问题");
                            }
                        }
                    } else {
                        log.info("用户未提供回答（可能是首次提问或总结阶段），不保存回答记录");
                    }
                } else {
                    log.warn("topicId 或 userId 为空，跳过问题保存检查 - topicId: {}, userId: {}", topicId, userId);
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
                }

                log.info("会话记忆已更新 - sessionId: {}, 用户消息长度：{}, AI 回复长度：{}",
                        sessionId, userInput != null ? userInput.length() : 0, aiResponse.length());

            } catch (Exception e) {
                log.error("存储会话记忆失败 - sessionId: {}, error: {}", sessionId, e.getMessage(), e);
            }

        });


    }

    /**
     * 从历史中提取最后一条AI消息末尾的问题（【问题】标记后的内容）
     */
    private String extractQuestionFromLastAiMessage(List<Message> history) {
        if (history == null || history.isEmpty()) return null;

        // 从后往前找最后一条 AI 消息
        for (int i = history.size() - 1; i >= 0; i--) {
            Message msg = history.get(i);
            if (msg instanceof AssistantMessage) {
                String text = ((AssistantMessage) msg).getText();
                if (text != null && text.contains("【问题】")) {
                    int start = text.indexOf("【问题】") + 4;
                    String question = text.substring(start).trim();
                    if (!question.isEmpty()) return question;
                }
                // 没有【问题】标记则返回最后一行带问号的句子
                String[] lines = text.split("\n");
                for (int j = lines.length - 1; j >= 0; j--) {
                    String line = lines[j].trim();
                    if (!line.isEmpty() && (line.contains("?") || line.contains("？"))) {
                        return line;
                    }
                }
                break;
            }
        }
        return null;
    }

    /**
     * 如果问题不存在则保存到 defense_student_questions
     */
    private void submitIfNewQuestion(Integer defenseId, String question) {
        try {
            DefenseStudentQuestions existing = findExistingStudentQuestion(defenseId, question);
            if (existing != null) {
                log.info("额外问题已存在，跳过保存 - sqId: {}", existing.getSqId());
                return;
            }
            int nextSort = defenseStudentQuestionsMapper.getNextSortNumber(defenseId);
            DefenseStudentQuestions studentQuestion = new DefenseStudentQuestions();
            studentQuestion.setDefenseId(defenseId);
            studentQuestion.setQuestionId(null);
            studentQuestion.setCustomQuestion(question);
            studentQuestion.setCustomStandardAnswer("");
            studentQuestion.setQuestionType("ai");
            studentQuestion.setSort(nextSort);
            studentQuestion.setCreatedAt(java.time.LocalDateTime.now());
            defenseStudentQuestionsMapper.insertStudentQuestion(studentQuestion);
            log.info("新额外问题已保存 - sqId: {}, sort: {}", studentQuestion.getSqId(), nextSort);
        } catch (Exception e) {
            log.warn("保存额外问题时发生异常", e);
        }
    }

    private int countAssistantMessages(List<Message> history) {
        int count = 0;
        for (Message msg : history) {
            if (msg instanceof AssistantMessage) {
                count++;
            }
        }
        log.info("countAssistantMessages - 历史消息总数: {}, AI消息数: {}", history.size(), count);
        return count;
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
                } else if (aiResponse.contains("【报告分析】")) {
                    endIndex = aiResponse.indexOf("【报告分析】");
                } else if (aiResponse.contains("【总得分】")) {
                    endIndex = aiResponse.indexOf("【总得分】");
                }

                String summary = aiResponse.substring(startIndex, endIndex).trim();
                log.info("从【总结】标记中提取到总结内容，长度: {}", summary.length());
                return summary;
            }
            log.info("未找到【总结】标记");
            return null;

        } catch (Exception e) {
            log.warn("提取总结失败: {}", e.getMessage());
        }

        return null;
    }

    private String videoAnalysis (String aiResponse) {
        if (aiResponse == null || aiResponse.isEmpty()) {
            return aiResponse;
        }
        try {
            if (aiResponse.contains("【视频分析】")) {
                int startIndex = aiResponse.indexOf("【视频分析】")+ 6;

                int endIndex = aiResponse.length();
                if (aiResponse.contains("【报告分析】")) {
                    endIndex = aiResponse.indexOf("【报告分析】");
                } else if (aiResponse.contains("【总得分】")) {
                    endIndex = aiResponse.indexOf("【总得分】");
                }

                String summary = aiResponse.substring(startIndex, endIndex).trim();
                log.info("从【视频分析】标记中提取到总结内容，长度: {}", summary.length());
                return summary;
            }
            log.info("未找到【视频分析】标记");
            return null;

        } catch (Exception e) {
            log.warn("提取视频分析失败: {}", e.getMessage());
        }

        return null;
    }

    /**
     * 从AI回复中提取总得分（【总得分】标记后的数字）
     */
    private Double extractTotalScoreFromResponse(String aiResponse) {
        if (aiResponse == null || aiResponse.isEmpty()) {
            return null;
        }
        try {
            if (aiResponse.contains("【总得分】")) {
                int startIndex = aiResponse.indexOf("【总得分】") + 5;
                int endIndex = aiResponse.indexOf("\n", startIndex);
                if (endIndex == -1) {
                    endIndex = aiResponse.length();
                }
                String scoreStr = aiResponse.substring(startIndex, endIndex).trim();
                scoreStr = scoreStr.replaceAll("[^0-9.]", "");
                if (!scoreStr.isEmpty()) {
                    double score = Double.parseDouble(scoreStr);
                    log.info("从【总得分】标记中提取到总分数: {}", score);
                    return score;
                }
            }
        } catch (Exception e) {
            log.warn("提取总得分失败: {}", e.getMessage());
        }
        return null;
    }

    private String reportAnalysis (String aiResponse) {
        if (aiResponse == null || aiResponse.isEmpty()) {
            return aiResponse;
        }
        try {
            if (aiResponse.contains("【报告分析】")) {
                int startIndex = aiResponse.indexOf("【报告分析】") + 6;

                int endIndex = aiResponse.length();
                if (aiResponse.contains("【总得分】")) {
                    endIndex = aiResponse.indexOf("【总得分】");
                }

                String summary = aiResponse.substring(startIndex, endIndex).trim();
                log.info("从【报告分析】标记中提取到总结内容，长度: {}", summary.length());
                return summary;
            }
            log.info("未找到【报告分析】标记");
            return null;

        } catch (Exception e) {
            log.warn("提取报告分析失败: {}", e.getMessage());
        }

        return null;
    }


    private Flux<String> handleFallback(String userId, Integer topicId, String prompt, String reason, String sessionId, String userInput) {
        String fallbackPrompt = reason + "，直将接回答问题。\n\n" + prompt;
        return sendMessageWithMemory(new ArrayList<>(), 0, userId, topicId, fallbackPrompt, sessionId, userInput);
    }

    @SuppressWarnings("unchecked")
    private StringBuilder buildQuestionModePrompt(Object topicData, List<DefenseQuestions> questions,
                                                  Integer topicId, String prompt, String userId,
                                                  int extraAskedCount, int extraQuestionLimit) {
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

        int remainingExtra = extraQuestionLimit - extraAskedCount;

        contextPrompt.append("\n\n【答辩规则】\n");
        contextPrompt.append("1. 你必须严格按照题目列表中的问题进行提问，不能更改问题内容\n");
        contextPrompt.append("2. 每次只提一个问题，等待学生回答\n");
        contextPrompt.append("3. 根据学生的回答，使用评分参考进行评价\n");
        contextPrompt.append("4. 题目列表提问完后，你可以根据答辩项目以及学生答辩的ppt和内容进行适当多余提问\n");
        contextPrompt.append("5. 【重要】你已经提了 ").append(extraAskedCount).append(" 个额外问题，最多还能提 ").append(remainingExtra).append(" 个额外问题。")
                .append("提完这 ").append(remainingExtra).append(" 个后必须进入总结阶段，不能再提任何新问题！\n");
        contextPrompt.append("6. 完成所有问题（预设问题 + 最多").append(extraQuestionLimit).append("个额外问题）后，给出总体评价和总结\n\n");

        contextPrompt.append("【评分标准】\n");
        contextPrompt.append("- 每个问题满分 100 分\n");
        contextPrompt.append("- 评分维度：准确性(40%)、完整性(30%)、逻辑性(20%)、表达清晰度(10%)\n");
        contextPrompt.append("- 必须在评价中明确给出分数\n\n");

        contextPrompt.append("【重要指令】\n");
        contextPrompt.append("- 现在请开始考试：先向学生问好，然后直接提出第 1 个问题\n");
        contextPrompt.append("- 不要暴露你有参考答案，这些是给你的评分标准\n");
        contextPrompt.append("- 保持专业、严肃的考官语气\n\n");
        contextPrompt.append("- 学生回答后直接进行下一个问题，不要换个方式提出相同问题！！！\n");
        contextPrompt.append("- 如果学生回答'不知道'或表示无法回答，视为该题已作答，直接进入下一题，绝对不要重复或重述当前问题！\n\n");

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
