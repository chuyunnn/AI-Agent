package com.yizhaoqi.searchSmart.config;

import com.yizhaoqi.searchSmart.agent.service.RedisChatMemoryRepository;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AgentAiConfig {

    @Bean
    public ChatMemory agentChatMemory(RedisChatMemoryRepository chatMemoryRepository,
                                      AgentProperties agentProperties) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .maxMessages(agentProperties.getMemory().getMaxMessages())
                .build();
    }
}
