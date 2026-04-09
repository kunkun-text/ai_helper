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
            
            log.info("【Redis读取】从Redis获取原始数据 - conversationId: {}, 原始数量: {}", conversationId, messages != null ? messages.size() : 0);
            
            if (messages == null || messages.isEmpty()) {
                return new ArrayList<>();
            }
            
            List<Message> result = new ArrayList<>();
            for (Object msg : messages) {
                log.info("【Redis读取】消息类型: {}", msg != null ? msg.getClass().getName() : "null");
                
                if (msg instanceof Map) {
                    Map<?, ?> map = (Map<?, ?>) msg;
                    log.info("【Redis读取】Map内容: {}", map);
                    
                    String type = (String) map.get("type");
                    String content = (String) map.get("content");
                    
                    log.info("【Redis读取】type: {}, content长度: {}", type, content != null ? content.length() : 0);
                    
                    if ("user".equals(type) && content != null) {
                        result.add(new org.springframework.ai.chat.messages.UserMessage(content));
                        log.info("【Redis读取】成功转换为用户消息");
                    } else if ("assistant".equals(type) && content != null) {
                        result.add(new org.springframework.ai.chat.messages.AssistantMessage(content));
                        log.info("【Redis读取】成功转换为AI消息");
                    } else {
                        log.warn("【Redis读取】无法识别的消息类型或内容为空 - type: {}, content: {}", type, content);
                    }
                } else {
                    log.warn("【Redis读取】消息不是Map类型，实际类型: {}", msg != null ? msg.getClass().getName() : "null");
                }
            }
            
            log.info("【Redis读取】最终转换后的消息数量: {}", result.size());
            return result;
        }
        
        @Override
        public void add(String conversationId, List<Message> messages) {
            String key = KEY_PREFIX + conversationId;
            String lockKey = LOCK_KEY_PREFIX + conversationId;
            String lockValue = java.util.UUID.randomUUID().toString() + ":" + System.currentTimeMillis();
            
            log.info("【Redis锁】尝试获取锁 - conversationId: {}, lockKey: {}", conversationId, lockKey);
            
            Boolean locked = redisTemplate.opsForValue().setIfAbsent(lockKey, lockValue, 10, TimeUnit.SECONDS);
            
            if (Boolean.TRUE.equals(locked)) {
                log.info("【Redis锁】成功获取锁 - conversationId: {}", conversationId);
                try {
                    int pushCount = 0;
                    for (Message msg : messages) {
                        Map<String, Object> map = new HashMap<>();
                        if (msg instanceof UserMessage userMsg) {
                            map.put("type", "user");
                            map.put("content", userMsg.getText());
                            log.info("【Redis保存】准备保存用户消息: {}", userMsg.getText().substring(0, Math.min(50, userMsg.getText().length())));
                        } else if (msg instanceof AssistantMessage asstMsg) {
                            map.put("type", "assistant");
                            map.put("content", asstMsg.getText());
                            log.info("【Redis保存】准备保存AI消息: {}", asstMsg.getText().substring(0, Math.min(50, asstMsg.getText().length())));
                        }
                        
                        redisTemplate.opsForList().rightPush(key, map);
                        pushCount++;
                        log.info("【Redis保存】已推送第 {} 条消息", pushCount);
                    }
                    
                    log.info("【Redis保存】共推送 {} 条消息到列表", pushCount);
                    
                    redisTemplate.expire(key, EXPIRE_MINUTES, TimeUnit.MINUTES);
                    log.info("【Redis保存】设置过期时间为 {} 分钟", EXPIRE_MINUTES);
                    
                    Long listSize = redisTemplate.opsForList().size(key);
                    log.info("【Redis保存】当前列表中消息总数: {}", listSize);
                } finally {
                    String currentLockValue = (String) redisTemplate.opsForValue().get(lockKey);
                    if (lockValue.equals(currentLockValue)) {
                        redisTemplate.delete(lockKey);
                        log.info("【Redis锁】释放锁成功 - conversationId: {}", conversationId);
                    } else {
                        log.warn("【Redis锁】锁已被其他线程持有，不删除 - conversationId: {}, expected: {}, actual: {}", 
                                conversationId, lockValue, currentLockValue);
                    }
                }
            } else {
                log.error("【Redis锁】无法获取锁，跳过存储操作 - conversationId: {}, lockKey: {}", conversationId, lockKey);
                
                String existingLock = (String) redisTemplate.opsForValue().get(lockKey);
                log.error("【Redis锁】当前锁的值: {}", existingLock);
                
                log.warn("【Redis锁】尝试强制保存（无锁模式）");
                try {
                    int pushCount = 0;
                    for (Message msg : messages) {
                        Map<String, Object> map = new HashMap<>();
                        if (msg instanceof UserMessage userMsg) {
                            map.put("type", "user");
                            map.put("content", userMsg.getText());
                        } else if (msg instanceof AssistantMessage asstMsg) {
                            map.put("type", "assistant");
                            map.put("content", asstMsg.getText());
                        }
                        
                        redisTemplate.opsForList().rightPush(key, map);
                        pushCount++;
                    }
                    
                    log.info("【Redis强制保存】共推送 {} 条消息到列表", pushCount);
                    
                    redisTemplate.expire(key, EXPIRE_MINUTES, TimeUnit.MINUTES);
                    
                    Long listSize = redisTemplate.opsForList().size(key);
                    log.info("【Redis强制保存】当前列表中消息总数: {}", listSize);
                } catch (Exception e) {
                    log.error("【Redis强制保存】失败", e);
                }
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