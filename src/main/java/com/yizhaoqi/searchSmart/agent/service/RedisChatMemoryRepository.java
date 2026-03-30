package com.yizhaoqi.searchSmart.agent.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.searchSmart.agent.dto.StoredAgentMessage;
import com.yizhaoqi.searchSmart.config.AgentProperties;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Repository
public class RedisChatMemoryRepository implements ChatMemoryRepository {

    private static final String MEMORY_KEY_PREFIX = "agent:conversation:";

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;
    private final AgentProperties agentProperties;

    public RedisChatMemoryRepository(RedisTemplate<String, Object> redisTemplate,
                                     ObjectMapper objectMapper,
                                     AgentProperties agentProperties) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.agentProperties = agentProperties;
    }

    @Override
    public List<String> findConversationIds() {
        Set<String> keys = redisTemplate.keys(MEMORY_KEY_PREFIX + "*:memory");
        if (keys == null || keys.isEmpty()) {
            return List.of();
        }
        return keys.stream()
                .map(this::extractConversationId)
                .toList();
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        Object value = redisTemplate.opsForValue().get(memoryKey(conversationId));
        if (value == null) {
            return new ArrayList<>();
        }
        try {
            List<StoredAgentMessage> storedMessages = objectMapper.readValue(
                    String.valueOf(value),
                    new TypeReference<List<StoredAgentMessage>>() {
                    });
            return storedMessages.stream()
                    .map(this::toMessage)
                    .collect(Collectors.toCollection(ArrayList::new));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to read agent chat memory", e);
        }
    }

    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        List<StoredAgentMessage> storedMessages = messages.stream()
                .map(this::toStoredMessage)
                .toList();
        try {
            redisTemplate.opsForValue().set(
                    memoryKey(conversationId),
                    objectMapper.writeValueAsString(storedMessages),
                    Duration.ofDays(agentProperties.getMemory().getTtlDays()));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to persist agent chat memory", e);
        }
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        redisTemplate.delete(memoryKey(conversationId));
    }

    private String memoryKey(String conversationId) {
        return MEMORY_KEY_PREFIX + conversationId + ":memory";
    }

    private String extractConversationId(String key) {
        String suffix = key.substring(MEMORY_KEY_PREFIX.length());
        return suffix.replace(":memory", "");
    }

    private StoredAgentMessage toStoredMessage(Message message) {
        return new StoredAgentMessage(message.getMessageType().name(), message.getText());
    }

    private Message toMessage(StoredAgentMessage storedAgentMessage) {
        MessageType messageType = MessageType.valueOf(storedAgentMessage.getType());
        return switch (messageType) {
            case USER -> new UserMessage(storedAgentMessage.getText());
            case SYSTEM -> new SystemMessage(storedAgentMessage.getText());
            case ASSISTANT -> new AssistantMessage(storedAgentMessage.getText());
            default -> new AssistantMessage(storedAgentMessage.getText());
        };
    }
}
