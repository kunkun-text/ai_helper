package com.ai_helper.ai_helper.Config;

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
    
    /**
     * 注意（2026-09-27）：这里【不要】再挂 .defaultTools(textTools)。
     * 实测 defenseId=295 第 2 轮——3B 模型把工具调用意图直接输出成自然语言
     * （"由于获取视频内容的文字信息失败，我们将根据学生提供的信息进行评分和点评。"），
     * 整轮没有「点评:」「评分:」行，服务端只能落兜底默认分（35 分）+ 评语"无评价"，
     * 且这条垃圾消息会写进 Redis 会话记忆、污染后续每一轮。
     * 去除后本轮即恢复正常打分。若将来确需工具调用，需另做"回复中不含评分行则不落分"的兜底。
     */
    @Bean
    @Primary
    public ChatClient chatClient(ChatModel chatModel) {
        return ChatClient.builder(chatModel)
                .build();
    }
    
    static class RedisChatMemory implements ChatMemory {
        
        private final RedisTemplate<String, Object> redisTemplate;
        private static final String KEY_PREFIX = "chat:memory:";
        private static final long EXPIRE_MINUTES = 30;
        private static final String LOCK_KEY_PREFIX = "chat:lock:";

        /** 取锁失败后的重试次数与间隔：给并发的另一条请求留出释放锁的时间 */
        private static final int LOCK_RETRY_TIMES = 5;
        private static final long LOCK_RETRY_INTERVAL_MILLIS = 50;
        
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

            if (appendWithLock(key, lockKey, lockValue, messages, true)) {
                return;
            }

            // 【N22 · 2026-09-28】取不到锁时改为「等待 + 重试」，重试仍失败则放弃本次写入。
            // 旧实现走的是「无锁强制写」兜底 —— 并发下同一批消息会被重复写进会话记忆
            // （两个请求都觉得自己该写），模型随后看到重复的问答，轮次与上下文一起错乱。
            // 放弃写入的代价只是少一条上下文（下一轮仍可继续作答），远小于污染整场会话。
            for (int attempt = 1; attempt <= LOCK_RETRY_TIMES; attempt++) {
                try {
                    Thread.sleep(LOCK_RETRY_INTERVAL_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (appendWithLock(key, lockKey, lockValue, messages, false)) {
                    log.info("【Redis锁】第 {} 次重试取得锁并写入 - conversationId: {}", attempt, conversationId);
                    return;
                }
            }
            log.error("【Redis锁】重试 {} 次仍未取得锁，放弃本次会话写入（避免无锁写入造成消息重复） - conversationId: {}",
                    LOCK_RETRY_TIMES, conversationId);
        }

        /** 取锁成功则写入消息并释放锁；取不到锁返回 false（由调用方决定是否重试） */
        private boolean appendWithLock(String key, String lockKey, String lockValue,
                                       List<Message> messages, boolean firstAttempt) {
            Boolean locked = redisTemplate.opsForValue().setIfAbsent(lockKey, lockValue, 10, TimeUnit.SECONDS);
            if (!Boolean.TRUE.equals(locked)) {
                if (firstAttempt) {
                    log.warn("【Redis锁】未取得锁，进入重试 - lockKey: {}", lockKey);
                }
                return false;
            }
            try {
                appendMessages(key, messages);
            } finally {
                releaseLock(lockKey, lockValue);
            }
            return true;
        }

        /** 写入消息并续期（写入与 TTL 只有这一处实现，加锁 / 重试两条路径共用） */
        private void appendMessages(String key, List<Message> messages) {
            int pushCount = 0;
            for (Message msg : messages) {
                Map<String, Object> map = new HashMap<>();
                if (msg instanceof UserMessage userMsg) {
                    map.put("type", "user");
                    map.put("content", userMsg.getText());
                } else if (msg instanceof AssistantMessage asstMsg) {
                    map.put("type", "assistant");
                    map.put("content", asstMsg.getText());
                } else {
                    continue;
                }
                redisTemplate.opsForList().rightPush(key, map);
                pushCount++;
            }
            redisTemplate.expire(key, EXPIRE_MINUTES, TimeUnit.MINUTES);
            log.info("【Redis保存】写入 {} 条消息，当前总数: {} - key: {}",
                    pushCount, redisTemplate.opsForList().size(key), key);
        }

        private void releaseLock(String lockKey, String lockValue) {
            String currentLockValue = (String) redisTemplate.opsForValue().get(lockKey);
            if (lockValue.equals(currentLockValue)) {
                redisTemplate.delete(lockKey);
            } else {
                log.warn("【Redis锁】锁已被其他线程持有，不删除 - lockKey: {}, expected: {}, actual: {}",
                        lockKey, lockValue, currentLockValue);
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
