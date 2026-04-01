package com.ai_helper.ai_helper.Config;

import com.ai_helper.ai_helper.util.TextTools;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Configuration
@Slf4j
public class ChatConfiguration {

    @Bean
    public ChatMemory chatMemory(RedisTemplate<String, Object> redisTemplate) {
        return new RedisChatMemory(redisTemplate);
    }
    
    @Bean
    @Primary
    public ChatClient chatClient(ChatModel chatModel, TextTools textTools) {
        ChatClient.Builder builder = ChatClient
                .builder(chatModel)
                .defaultSystem("你是一个智能答辩助手，请以老师的身份回答问题。你可以根据需要使用工具查询视频内容来辅助回答。")
                .defaultTools(textTools);
        
        return builder.build();
    }
    
    static class RedisChatMemory implements ChatMemory {
        
        private final RedisTemplate<String, Object> redisTemplate;
        private static final String KEY_PREFIX = "chat:memory:";
        private static final long EXPIRE_MINUTES = 30;
        private static final String LOCK_KEY_PREFIX = "chat:lock:";
        
        public RedisChatMemory(RedisTemplate<String, Object> redisTemplate) {
            this.redisTemplate = redisTemplate;
        }
        
        @Override
        public List<Message> get(String conversationId) {
            String key = KEY_PREFIX + conversationId;
            List<Object> messages = redisTemplate.opsForList().range(key, 0, -1);
            
            if (messages == null || messages.isEmpty()) {
                return new ArrayList<>();
            }
            
            List<Message> result = new ArrayList<>();
            for (Object msg : messages) {
                if (msg instanceof Map) {
                    Map<?, ?> map = (Map<?, ?>) msg;
                    String type = (String) map.get("type");
                    String content = (String) map.get("content");
                    
                    if ("user".equals(type)) {
                        result.add(new org.springframework.ai.chat.messages.UserMessage(content));
                    } else if ("assistant".equals(type)) {
                        result.add(new org.springframework.ai.chat.messages.AssistantMessage(content));
                    }
                }
            }
            return result;
        }
        
        @Override
        public void add(String conversationId, List<Message> messages) {
            String key = KEY_PREFIX + conversationId;
            String lockKey = LOCK_KEY_PREFIX + conversationId;
            String lockValue = java.util.UUID.randomUUID().toString() + ":" + System.currentTimeMillis();
            
            Boolean locked = redisTemplate.opsForValue().setIfAbsent(lockKey, lockValue, 10, TimeUnit.SECONDS);
            
            if (Boolean.TRUE.equals(locked)) {
                try {
                    List<Map<String, Object>> messageMaps = new ArrayList<>();
                    for (Message msg : messages) {
                        Map<String, Object> map = new HashMap<>();
                        if (msg instanceof UserMessage userMsg) {
                            map.put("type", "user");
                            map.put("content", userMsg.getText());
                        } else if (msg instanceof AssistantMessage asstMsg) {
                            map.put("type", "assistant");
                            map.put("content", asstMsg.getText());
                        }
                        messageMaps.add(map);
                    }
                    
                    if (!messageMaps.isEmpty()) {
                        redisTemplate.opsForList().rightPushAll(key, messageMaps);
                    }
                    
                    redisTemplate.expire(key, EXPIRE_MINUTES, TimeUnit.MINUTES);
                } finally {
                    String currentLockValue = (String) redisTemplate.opsForValue().get(lockKey);
                    if (lockValue.equals(currentLockValue)) {
                        redisTemplate.delete(lockKey);
                    } else {
                        log.warn("锁已被其他线程持有，不删除 - conversationId: {}, expected: {}, actual: {}", 
                                conversationId, lockValue, currentLockValue);
                    }
                }
            } else {
                log.warn("无法获取锁，跳过存储操作 - conversationId: {}", conversationId);
            }
        }
        
        @Override
        public void clear(String conversationId) {
            String key = KEY_PREFIX + conversationId;
            String lockKey = LOCK_KEY_PREFIX + conversationId;
            redisTemplate.delete(key);
            redisTemplate.delete(lockKey);
        }
    }
}