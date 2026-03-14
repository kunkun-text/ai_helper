
package com.ai_helper.ai_helper.Config;


import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChatConfiguration {
    @Bean
    public ChatClient chatClient(ChatModel chatModel) {
        return ChatClient
                .builder(chatModel)
                .defaultSystem("你是一个大数据智能答辩助手,请以大数据课程老师的身份回答问题。")
                .defaultAdvisors(new SimpleLoggerAdvisor())
                .build();
    }
}