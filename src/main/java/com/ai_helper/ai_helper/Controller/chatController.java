package com.ai_helper.ai_helper.Controller;

import com.ai_helper.ai_helper.Service.DefenseTopicsService;
import com.ai_helper.ai_helper.pojo.dto.TopicDto;
import com.ai_helper.ai_helper.pojo.entity.DefenseQuestions;
import com.ai_helper.ai_helper.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.List;

@RestController
@RequestMapping("/api")
@Slf4j
public class chatController {

    @Autowired
    private ChatClient chatClient;
    
    @Autowired
    private DefenseTopicsService defenseTopicsService;
    
    @Autowired
    private ChatMemory chatMemory;

    @RequestMapping(value = "/chat", produces = "text/html;charset=utf-8")
    public Flux<String> chat(@RequestParam(required = false, defaultValue = "") String prompt, 
                            @RequestParam(required = false) Integer topicId,
                            @RequestParam(required = false) String sessionId,
                            @RequestParam(required = false) String userId) {
        
        log.info("收到聊天请求 - prompt: {}, topicId: {}, sessionId: {}, userId: {}", prompt, topicId, sessionId, userId);
        
        if (sessionId == null || sessionId.trim().isEmpty()) {
            if (userId != null && !userId.isEmpty()) {
                sessionId = "user_" + userId + "_topic_" + topicId;
            } else {
                sessionId = "topic_" + topicId + "_" + System.currentTimeMillis();
            }
        }
        
        final String finalSessionId = sessionId;
        final String finalUserInput = prompt;

        if (topicId != null) {
            try {
                Result<Object> topicResult = defenseTopicsService.getTopicById(topicId);
                
                if (topicResult.getCode() != 1 || topicResult.getData() == null) {
                    log.warn("查询主题信息失败，topicId: {}, code: {}, msg: {}", 
                            topicId, topicResult.getCode(), topicResult.getMsg());
                    return handleFallback(prompt, "未找到相关主题信息", finalSessionId, finalUserInput);
                }
                
                Result<Object> questionResult = defenseTopicsService.getDefenseQuestionById(topicId);
                
                if (questionResult.getCode() == 1 && questionResult.getData() != null) {
                    List<DefenseQuestions> questions = (List<DefenseQuestions>) questionResult.getData();
                    
                    if (!questions.isEmpty()) {
                        log.info("找到 {} 个答辩题目，进入提问模式", questions.size());
                        StringBuilder contextPrompt = buildQuestionModePrompt(
                                topicResult.getData(), questions, topicId, prompt);
                        
                        return sendMessageWithMemory(contextPrompt.toString(), finalSessionId, finalUserInput);
                    } else {
                        log.info("该题目下暂无问题，进入视频内容辅助回答模式");
                        StringBuilder contextPrompt = buildVideoAssistModePrompt(
                                topicResult.getData(), topicId, prompt);
                        
                        return sendMessageWithMemory(contextPrompt.toString(), finalSessionId, finalUserInput);
                    }
                } else {
                    log.warn("查询题目信息失败，topicId: {}, code: {}, msg: {}", 
                            topicId, questionResult.getCode(), questionResult.getMsg());
                    return handleFallback(prompt, "未找到相关题目信息", finalSessionId, finalUserInput);
                }
                
            } catch (Exception e) {
                log.error("查询题目信息时发生异常，topicId: {}", topicId, e);
                return handleFallback(prompt, "查询题目信息时发生错误", finalSessionId, finalUserInput);
            }
        } else {
            log.info("无 topicId，使用通用模式回答");
            return sendMessageWithMemory(prompt, finalSessionId, finalUserInput);
        }
    }

    private Flux<String> sendMessageWithMemory(String fullPrompt, String sessionId, String userInput) {
        List<Message> history = chatMemory.get(sessionId);
        
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
                
                if (userInput != null && !userInput.trim().isEmpty()) {
                    chatMemory.add(sessionId, List.of(
                        new UserMessage(userInput),
                        new AssistantMessage(aiResponse)
                    ));
                }
                
                log.info("会话记忆已更新 - sessionId: {}, 用户消息长度：{}, AI 回复长度：{}", 
                        sessionId, userInput != null ? userInput.length() : 0, aiResponse.length());
            } catch (Exception e) {
                log.error("存储会话记忆失败 - sessionId: {}, error: {}", sessionId, e.getMessage(), e);
            }
        });
    }

    private Flux<String> handleFallback(String prompt, String reason, String sessionId, String userInput) {
        String fallbackPrompt = reason + "，将直接回答问题。\n\n" + prompt;
        return sendMessageWithMemory(fallbackPrompt, sessionId, userInput);
    }

    @SuppressWarnings("unchecked")
    private StringBuilder buildQuestionModePrompt(Object topicData, List<DefenseQuestions> questions, 
                                                  Integer topicId, String prompt) {
        StringBuilder contextPrompt = new StringBuilder();
        contextPrompt.append("=== 答辩考试场景 ===\n\n");
        
        contextPrompt.append("【你的角色】\n");
        contextPrompt.append("你是一名专业的答辩考官，正在主持一场毕业答辩考试。\n");
        contextPrompt.append("你的任务是根据下面的题目列表，逐一向学生提问，并评估他们的回答。\n\n");
        
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
        
        contextPrompt.append("\n【答辩题目列表】（这是你作为考官要提的问题）\n");
        
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
        
        contextPrompt.append("\n\n【考试规则】\n");
        contextPrompt.append("1. 你必须严格按照题目列表中的问题进行提问，不能更改问题内容\n");
        contextPrompt.append("2. 每次只提一个问题，等待学生回答\n");
        contextPrompt.append("3. 根据学生的回答，使用评分参考进行评价\n");
        contextPrompt.append("4. 如果学生回答不完整，可以适当追问\n");
        contextPrompt.append("5. 完成所有问题后，给出总体评价\n\n");
        
        contextPrompt.append("【重要指令】\n");
        contextPrompt.append("- 现在请开始考试：先向学生问好，然后直接提出第 1 个问题\n");
        contextPrompt.append("- 不要暴露你有参考答案，这些是给你的评分标准\n");
        contextPrompt.append("- 保持专业、严肃的考官语气\n\n");
        
        if (prompt != null && !prompt.trim().isEmpty()) {
            contextPrompt.append("【学生当前回答】\n");
            contextPrompt.append(prompt).append("\n\n");
            contextPrompt.append("请根据学生的上述回答进行评价或继续提问。\n");
        } else {
            contextPrompt.append("【开始考试】\n");
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
