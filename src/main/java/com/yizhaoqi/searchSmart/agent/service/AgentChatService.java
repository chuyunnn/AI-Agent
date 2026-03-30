package com.yizhaoqi.searchSmart.agent.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.searchSmart.agent.dto.CitationRecord;
import com.yizhaoqi.searchSmart.agent.dto.KnowledgeSearchHit;
import com.yizhaoqi.searchSmart.agent.dto.KnowledgeSearchToolResponse;
import com.yizhaoqi.searchSmart.agent.dto.LastChatResponseSnapshot;
import com.yizhaoqi.searchSmart.agent.dto.ToolExecutionSummary;
import com.yizhaoqi.searchSmart.agent.model.AgentConversationAudit;
import com.yizhaoqi.searchSmart.agent.repository.AgentConversationAuditRepository;
import com.yizhaoqi.searchSmart.config.AgentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AgentChatService {

    private static final Logger logger = LoggerFactory.getLogger(AgentChatService.class);

    private final AgentModelRouter agentModelRouter;
    private final AgentPromptService agentPromptService;
    private final AgentConversationStateService conversationStateService;
    private final ChatMemory chatMemory;
    private final AgentConversationAuditRepository auditRepository;
    private final ObjectMapper objectMapper;
    private final AgentProperties agentProperties;
    private final ToolCallback[] toolCallbacks;
    private final Map<String, ToolCallback> toolCallbackMap = new LinkedHashMap<>();
    private final Map<String, Boolean> stopFlags = new ConcurrentHashMap<>();

    public AgentChatService(AgentModelRouter agentModelRouter,
                            AgentPromptService agentPromptService,
                            AgentConversationStateService conversationStateService,
                            ChatMemory chatMemory,
                            AgentConversationAuditRepository auditRepository,
                            ObjectMapper objectMapper,
                            AgentProperties agentProperties,
                            KnowledgeSearchTools knowledgeSearchTools) {
        this.agentModelRouter = agentModelRouter;
        this.agentPromptService = agentPromptService;
        this.conversationStateService = conversationStateService;
        this.chatMemory = chatMemory;
        this.auditRepository = auditRepository;
        this.objectMapper = objectMapper;
        this.agentProperties = agentProperties;
        this.toolCallbacks = ToolCallbacks.from(knowledgeSearchTools);
        for (ToolCallback toolCallback : toolCallbacks) {
            toolCallbackMap.put(toolCallback.getToolDefinition().name(), toolCallback);
        }
    }

    public void processMessage(String userId, String userMessage, WebSocketSession session) {
        stopFlags.put(session.getId(), false);
        CompletableFuture.runAsync(() -> doProcessMessage(userId, userMessage, session));
    }

    public void stopResponse(String userId, WebSocketSession session) {
        logger.info("Stop agent response, userId={}, sessionId={}", userId, session.getId());
        stopFlags.put(session.getId(), true);
        Map<String, Object> response = new HashMap<>();
        response.put("type", "stop");
        response.put("message", "响应已停止");
        response.put("timestamp", System.currentTimeMillis());
        sendJson(session, response);
    }

    private void doProcessMessage(String userId, String userMessage, WebSocketSession session) {
        AgentToolContextHolder.setUserId(userId);
        String conversationId = conversationStateService.getOrCreateConversationId(userId);
        try {
            AgentModelRouter.ModelSession modelSession = agentModelRouter.getDefaultSession();
            LastChatResponseSnapshot lastSnapshot = conversationStateService.getLastResponse(conversationId);
            AgentLoopResult loopResult = executeLoop(modelSession, conversationId, userMessage, lastSnapshot, session);

            if (isStopped(session)) {
                return;
            }

            streamAnswer(session, loopResult.finalAnswer());
            if (isStopped(session)) {
                return;
            }

            chatMemory.add(conversationId, new UserMessage(userMessage));
            chatMemory.add(conversationId, new AssistantMessage(loopResult.finalAnswer()));

            LastChatResponseSnapshot snapshot = new LastChatResponseSnapshot(
                    conversationId,
                    userId,
                    userMessage,
                    loopResult.finalAnswer(),
                    loopResult.retrievedResults(),
                    loopResult.citations(),
                    loopResult.toolExecutions(),
                    modelSession.profileName(),
                    loopResult.iterationCount(),
                    LocalDateTime.now().toString()
            );
            conversationStateService.saveLastResponse(conversationId, snapshot);
            saveAudit(snapshot);
            sendCompletionNotification(session);
        } catch (Exception e) {
            logger.error("Agent chat processing failed", e);
            handleError(session, e);
        } finally {
            AgentToolContextHolder.clear();
            stopFlags.remove(session.getId());
        }
    }

    private AgentLoopResult executeLoop(AgentModelRouter.ModelSession modelSession,
                                        String conversationId,
                                        String userMessage,
                                        LastChatResponseSnapshot lastSnapshot,
                                        WebSocketSession session) throws JsonProcessingException {
        List<Message> historyMessages = new ArrayList<>(chatMemory.get(conversationId));
        List<Message> workingMessages = new ArrayList<>();
        workingMessages.add(new UserMessage(userMessage));

        List<KnowledgeSearchHit> retrievedResults = new ArrayList<>();
        List<ToolExecutionSummary> toolExecutions = new ArrayList<>();
        AssistantMessage finalAssistant = null;
        int maxIterations = Math.max(agentProperties.getLoop().getMaxIterations(), 1);
        int iterationCount = 0;

        for (int iteration = 1; iteration <= maxIterations; iteration++) {
            ensureNotStopped(session);
            iterationCount = iteration;

            String systemPrompt = agentPromptService.buildSystemPrompt(
                    lastSnapshot,
                    toolExecutions,
                    false,
                    iteration,
                    maxIterations);
            ChatResponse response = modelSession.chatModel().call(new Prompt(
                    buildPromptMessages(systemPrompt, historyMessages, workingMessages),
                    buildToolEnabledOptions(modelSession)));

            AssistantMessage assistantMessage = response.getResult() != null
                    ? response.getResult().getOutput()
                    : null;
            if (assistantMessage == null) {
                break;
            }

            if (assistantMessage.hasToolCalls() && iteration < maxIterations) {
                workingMessages.add(assistantMessage);
                ToolExecutionBatch batch = executeToolCalls(assistantMessage);
                workingMessages.add(new ToolResponseMessage(batch.toolResponses()));
                retrievedResults = mergeRetrievedResults(retrievedResults, batch.retrievedResults());
                toolExecutions.addAll(batch.toolExecutions());
                continue;
            }

            if (assistantMessage.hasToolCalls()) {
                workingMessages.add(assistantMessage);
                finalAssistant = forceFinalAnswer(modelSession, historyMessages, workingMessages, lastSnapshot, toolExecutions, maxIterations);
            } else {
                finalAssistant = assistantMessage;
            }
            break;
        }

        if (finalAssistant == null || !StringUtils.hasText(finalAssistant.getText())) {
            String fallbackText = retrievedResults.isEmpty()
                    ? "暂无相关信息，当前知识库中没有检索到足够证据支持回答。"
                    : buildFallbackAnswer(retrievedResults);
            finalAssistant = new AssistantMessage(fallbackText);
        }

        List<CitationRecord> citations = buildCitations(retrievedResults);
        return new AgentLoopResult(
                sanitizeAnswer(finalAssistant.getText()),
                retrievedResults,
                citations,
                toolExecutions,
                iterationCount
        );
    }

    private AssistantMessage forceFinalAnswer(AgentModelRouter.ModelSession modelSession,
                                              List<Message> historyMessages,
                                              List<Message> workingMessages,
                                              LastChatResponseSnapshot lastSnapshot,
                                              List<ToolExecutionSummary> toolExecutions,
                                              int maxIterations) {
        String systemPrompt = agentPromptService.buildSystemPrompt(
                lastSnapshot,
                toolExecutions,
                true,
                maxIterations,
                maxIterations);
        ChatResponse response = modelSession.chatModel().call(new Prompt(
                buildPromptMessages(systemPrompt, historyMessages, workingMessages),
                modelSession.plainOptions()));
        return response.getResult() != null ? response.getResult().getOutput() : null;
    }

    private List<Message> buildPromptMessages(String systemPrompt,
                                              List<Message> historyMessages,
                                              List<Message> workingMessages) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPrompt));
        messages.addAll(historyMessages);
        messages.addAll(workingMessages);
        return messages;
    }

    private OpenAiChatOptions buildToolEnabledOptions(AgentModelRouter.ModelSession modelSession) {
        return OpenAiChatOptions.builder()
                .model(modelSession.plainOptions().getModel())
                .temperature(modelSession.plainOptions().getTemperature())
                .topP(modelSession.plainOptions().getTopP())
                .maxTokens(modelSession.plainOptions().getMaxTokens())
                .toolCallbacks(toolCallbacks)
                .internalToolExecutionEnabled(false)
                .build();
    }

    private ToolExecutionBatch executeToolCalls(AssistantMessage assistantMessage) throws JsonProcessingException {
        List<ToolResponseMessage.ToolResponse> toolResponses = new ArrayList<>();
        List<ToolExecutionSummary> executionSummaries = new ArrayList<>();
        List<KnowledgeSearchHit> retrievedResults = new ArrayList<>();

        for (AssistantMessage.ToolCall toolCall : assistantMessage.getToolCalls()) {
            ToolCallback callback = toolCallbackMap.get(toolCall.name());
            if (callback == null) {
                throw new IllegalArgumentException("Unsupported tool call: " + toolCall.name());
            }

            String result = callback.call(toolCall.arguments());
            toolResponses.add(new ToolResponseMessage.ToolResponse(toolCall.id(), toolCall.name(), result));

            int resultCount = 0;
            String resultPreview = result;
            if ("searchKnowledge".equals(toolCall.name())) {
                KnowledgeSearchToolResponse response = objectMapper.readValue(result, KnowledgeSearchToolResponse.class);
                if (response.getHits() != null) {
                    retrievedResults.addAll(response.getHits());
                    resultCount = response.getHits().size();
                }
                resultPreview = response.getMessage();
            }

            executionSummaries.add(new ToolExecutionSummary(
                    toolCall.name(),
                    toolCall.arguments(),
                    abbreviate(resultPreview, 200),
                    resultCount,
                    true,
                    LocalDateTime.now().toString()
            ));
        }

        return new ToolExecutionBatch(toolResponses, executionSummaries, retrievedResults);
    }

    private List<KnowledgeSearchHit> mergeRetrievedResults(List<KnowledgeSearchHit> existing,
                                                           List<KnowledgeSearchHit> newlyRetrieved) {
        Map<String, KnowledgeSearchHit> merged = new LinkedHashMap<>();
        for (KnowledgeSearchHit hit : existing) {
            merged.put(hit.getFileMd5() + "#" + hit.getChunkId(), hit);
        }
        for (KnowledgeSearchHit hit : newlyRetrieved) {
            merged.put(hit.getFileMd5() + "#" + hit.getChunkId(), hit);
        }
        List<KnowledgeSearchHit> results = new ArrayList<>(merged.values());
        if (results.size() > KnowledgeSearchTools.DEFAULT_TOP_K) {
            return new ArrayList<>(results.subList(0, KnowledgeSearchTools.DEFAULT_TOP_K));
        }
        return results;
    }

    private List<CitationRecord> buildCitations(List<KnowledgeSearchHit> retrievedResults) {
        List<CitationRecord> citations = new ArrayList<>();
        for (KnowledgeSearchHit hit : retrievedResults) {
            citations.add(new CitationRecord(
                    hit.getIndex(),
                    hit.getFileName(),
                    hit.getFileMd5(),
                    hit.getChunkId(),
                    hit.getScore()
            ));
        }
        return citations;
    }

    private void saveAudit(LastChatResponseSnapshot snapshot) throws JsonProcessingException {
        AgentConversationAudit audit = new AgentConversationAudit();
        audit.setConversationId(snapshot.getConversationId());
        audit.setUserId(snapshot.getUserId());
        audit.setQuestion(snapshot.getUserMessage());
        audit.setFinalAnswer(snapshot.getFinalAnswer());
        audit.setToolSummaryJson(objectMapper.writeValueAsString(snapshot.getToolExecutions()));
        audit.setRetrievedResultsJson(objectMapper.writeValueAsString(snapshot.getRetrievedResults()));
        audit.setCitationJson(objectMapper.writeValueAsString(snapshot.getCitations()));
        audit.setModelProfile(snapshot.getModelProfile());
        audit.setIterationCount(snapshot.getIterationCount());
        auditRepository.save(audit);
    }

    private void streamAnswer(WebSocketSession session, String answer) {
        if (!StringUtils.hasText(answer)) {
            return;
        }
        int chunkSize = Math.max(agentProperties.getStreamChunkSize(), 1);
        for (int index = 0; index < answer.length(); index += chunkSize) {
            if (isStopped(session)) {
                return;
            }
            String chunk = answer.substring(index, Math.min(index + chunkSize, answer.length()));
            sendResponseChunk(session, chunk);
        }
    }

    private String buildFallbackAnswer(List<KnowledgeSearchHit> retrievedResults) {
        StringBuilder builder = new StringBuilder("已检索到相关知识，但模型未返回稳定答案。相关结果包括：\n");
        retrievedResults.forEach(hit -> builder.append("- ")
                .append(hit.getFileName())
                .append(" (来源#")
                .append(hit.getIndex())
                .append(": ")
                .append(hit.getFileName())
                .append(")\n"));
        return builder.toString().trim();
    }

    private String sanitizeAnswer(String answer) {
        if (!StringUtils.hasText(answer)) {
            return "暂无相关信息，当前无法生成有效回答。";
        }
        return answer.trim();
    }

    private void ensureNotStopped(WebSocketSession session) {
        if (isStopped(session)) {
            throw new AgentStoppedException();
        }
    }

    private boolean isStopped(WebSocketSession session) {
        return Boolean.TRUE.equals(stopFlags.get(session.getId()));
    }

    private void sendResponseChunk(WebSocketSession session, String chunk) {
        Map<String, String> payload = Map.of("chunk", chunk);
        sendJson(session, payload);
    }

    private void sendCompletionNotification(WebSocketSession session) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "completion");
        payload.put("status", "finished");
        payload.put("message", "响应已完成");
        payload.put("timestamp", System.currentTimeMillis());
        payload.put("date", LocalDateTime.now().toString());
        sendJson(session, payload);
    }

    private void handleError(WebSocketSession session, Throwable error) {
        if (error instanceof AgentStoppedException) {
            return;
        }
        Map<String, String> payload = Map.of("error", "AI服务暂时不可用，请稍后重试");
        sendJson(session, payload);
    }

    private void sendJson(WebSocketSession session, Object payload) {
        try {
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(payload)));
        } catch (Exception e) {
            logger.error("Failed to send websocket payload", e);
        }
    }

    private String abbreviate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "…";
    }

    private record ToolExecutionBatch(List<ToolResponseMessage.ToolResponse> toolResponses,
                                      List<ToolExecutionSummary> toolExecutions,
                                      List<KnowledgeSearchHit> retrievedResults) {
    }

    private record AgentLoopResult(String finalAnswer,
                                   List<KnowledgeSearchHit> retrievedResults,
                                   List<CitationRecord> citations,
                                   List<ToolExecutionSummary> toolExecutions,
                                   Integer iterationCount) {
    }

    private static class AgentStoppedException extends RuntimeException {
    }
}
