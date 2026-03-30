package com.yizhaoqi.smartpai.agent.service;

import com.yizhaoqi.smartpai.config.AgentProperties;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class AgentModelRouter {

    private final AgentProperties agentProperties;
    private final Map<String, ModelSession> sessions = new ConcurrentHashMap<>();

    public AgentModelRouter(AgentProperties agentProperties) {
        this.agentProperties = agentProperties;
    }

    public ModelSession getDefaultSession() {
        return getSession(agentProperties.getDefaultModelProfile());
    }

    public ModelSession getSession(String profileName) {
        String resolvedProfile = StringUtils.hasText(profileName)
                ? profileName
                : agentProperties.getDefaultModelProfile();
        return sessions.computeIfAbsent(resolvedProfile, this::createSession);
    }

    private ModelSession createSession(String profileName) {
        AgentProperties.ModelProfile profile = agentProperties.getModels().get(profileName);
        if (profile == null) {
            throw new IllegalArgumentException("Unknown agent model profile: " + profileName);
        }

        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(profile.getBaseUrl())
                .apiKey(profile.getApiKey())
                .build();

        OpenAiChatOptions plainOptions = OpenAiChatOptions.builder()
                .model(profile.getModel())
                .temperature(profile.getTemperature())
                .topP(profile.getTopP())
                .maxTokens(profile.getMaxTokens())
                .build();

        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(plainOptions)
                .retryTemplate(RetryTemplate.defaultInstance())
                .observationRegistry(ObservationRegistry.NOOP)
                .build();

        ChatClient chatClient = ChatClient.builder(chatModel)
                .defaultOptions(plainOptions)
                .build();

        return new ModelSession(profileName, chatModel, chatClient, plainOptions);
    }

    public record ModelSession(String profileName,
                               ChatModel chatModel,
                               ChatClient chatClient,
                               OpenAiChatOptions plainOptions) {
    }
}
