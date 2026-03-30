package com.yizhaoqi.smartpai.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.agent.dto.LastChatResponseSnapshot;
import com.yizhaoqi.smartpai.config.AgentProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentConversationStateServiceTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    private AgentConversationStateService stateService;

    @BeforeEach
    void setUp() {
        AgentProperties agentProperties = new AgentProperties();
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        stateService = new AgentConversationStateService(redisTemplate, new ObjectMapper(), agentProperties);
    }

    @Test
    void shouldReturnExistingConversationId() {
        when(valueOperations.get("agent:user:alice:current_conversation")).thenReturn("conv-1");

        String conversationId = stateService.getOrCreateConversationId("alice");

        assertEquals("conv-1", conversationId);
    }

    @Test
    void shouldPersistLastResponseSnapshot() {
        LastChatResponseSnapshot snapshot = new LastChatResponseSnapshot();
        snapshot.setConversationId("conv-1");
        snapshot.setUserId("alice");
        snapshot.setFinalAnswer("done");

        stateService.saveLastResponse("conv-1", snapshot);

        verify(valueOperations).set(eq("agent:conversation:conv-1:last_response"), any(), any());
    }
}
