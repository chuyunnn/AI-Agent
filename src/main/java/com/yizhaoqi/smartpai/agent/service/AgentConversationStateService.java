package com.yizhaoqi.smartpai.agent.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.agent.dto.LastChatResponseSnapshot;
import com.yizhaoqi.smartpai.config.AgentProperties;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

@Service
public class AgentConversationStateService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;
    private final AgentProperties agentProperties;

    public AgentConversationStateService(RedisTemplate<String, Object> redisTemplate,
                                         ObjectMapper objectMapper,
                                         AgentProperties agentProperties) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.agentProperties = agentProperties;
    }

    public String getOrCreateConversationId(String userId) {
        String key = currentConversationKey(userId);
        Object value = redisTemplate.opsForValue().get(key);
        if (value != null) {
            return String.valueOf(value);
        }
        String conversationId = UUID.randomUUID().toString();
        redisTemplate.opsForValue().set(key, conversationId, Duration.ofDays(agentProperties.getMemory().getTtlDays()));
        return conversationId;
    }

    public void saveLastResponse(String conversationId, LastChatResponseSnapshot snapshot) {
        try {
            redisTemplate.opsForValue().set(
                    lastResponseKey(conversationId),
                    objectMapper.writeValueAsString(snapshot),
                    Duration.ofDays(agentProperties.getMemory().getTtlDays()));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to persist last chat response snapshot", e);
        }
    }

    public LastChatResponseSnapshot getLastResponse(String conversationId) {
        Object value = redisTemplate.opsForValue().get(lastResponseKey(conversationId));
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.readValue(String.valueOf(value), LastChatResponseSnapshot.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to read last chat response snapshot", e);
        }
    }

    private String currentConversationKey(String userId) {
        return "agent:user:" + userId + ":current_conversation";
    }

    private String lastResponseKey(String conversationId) {
        return "agent:conversation:" + conversationId + ":last_response";
    }
}
