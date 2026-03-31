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

    /*
    * 接收消息
    * */
    public void processMessage(String userId, String userMessage, WebSocketSession session) {
        stopFlags.put(session.getId(), false);
        CompletableFuture.runAsync(() -> doProcessMessage(userId, userMessage, session));
    }

    /*
    *  停止回答
    * */
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
        // 1. 把当前用户ID存入线程上下文（工具类需要知道当前是谁在提问）
        AgentToolContextHolder.setUserId(userId);
        // 2. 获取/创建一个对话ID（同一个用户多次聊天，用同一个conversationId）
        //    作用：实现上下文记忆，AI知道你之前问过什么
        String conversationId = conversationStateService.getOrCreateConversationId(userId);
        try {
            // 3. 获取AI模型会话（选择用哪个大模型：豆包/通义/OpenAI等）
                // 不是简单用chatclient，封装用于复杂agent操作
            AgentModelRouter.ModelSession modelSession = agentModelRouter.getDefaultSession();
            // 4. 获取上一轮对话的快照（AI需要上下文才能连续聊天）
            LastChatResponseSnapshot lastSnapshot = conversationStateService.getLastResponse(conversationId);

            // ======================= 核心 =======================
            // 5. 执行AI智能体循环（调用AI + 自动调用工具查知识库）
            //    返回最终答案、检索到的资料、引用记录等
            // ====================================================
            AgentLoopResult loopResult = executeLoop(modelSession, conversationId, userMessage, lastSnapshot, session);

            // 6. 检查用户是否点击了【停止】按钮，如果停止了，直接结束
            if (isStopped(session)) {
                return;
            }

            // 流式推送用户回答
            streamAnswer(session, loopResult.finalAnswer());
            if (isStopped(session)) {
                return;
            }

            // 9. 把【用户问题】和【AI回答】存入对话记忆库
            //    下次提问时，AI能看到历史记录
            chatMemory.add(conversationId, new UserMessage(userMessage));
            chatMemory.add(conversationId, new AssistantMessage(loopResult.finalAnswer()));

            // 10. 构建本次对话的完整快照（存起来，方便下次上下文使用）
            LastChatResponseSnapshot snapshot = new LastChatResponseSnapshot(
                    conversationId, // 会话id
                    userId, // 用户id
                    userMessage, // 用户提问
                    loopResult.finalAnswer(), // 用户回答
                    loopResult.retrievedResults(), // 检索资料
                    loopResult.citations(), // 资料引用
                    loopResult.toolExecutions(), // 工具调用记录
                    modelSession.profileName(), // 使用的ai模型
                    loopResult.iterationCount(), // ai循环思考次数
                    LocalDateTime.now().toString() // 时间
            );
            // 11. 保存本次对话快照（下次聊天用）
            conversationStateService.saveLastResponse(conversationId, snapshot);
            // 12. 把对话记录存入数据库（审计日志、后台可查看）
            saveAudit(snapshot);
            // 返回消息给前端
            sendCompletionNotification(session);
        } catch (Exception e) {
            logger.error("Agent chat processing failed", e);
            handleError(session, e);
        } finally {
            AgentToolContextHolder.clear();
            stopFlags.remove(session.getId());
        }
    }
    /*
    *  执行循环
    * */
    private AgentLoopResult executeLoop(AgentModelRouter.ModelSession modelSession,
                                        String conversationId,
                                        String userMessage,
                                        LastChatResponseSnapshot lastSnapshot, // 过去的回答，可以作为辅助，比如之前根据什么需求已经查到了什么结果
                                        WebSocketSession session) throws JsonProcessingException {
        // 根据conversationId从chatmemory中拉取历史聊天记录
        List<Message> historyMessages = new ArrayList<>(chatMemory.get(conversationId));
        // 【构建当前工作区】存放本次循环中产生的所有临时对话（包含 AI 调工具的过程）
        List<Message> workingMessages = new ArrayList<>();
        workingMessages.add(new UserMessage(userMessage));

        // 存储搜索到的知识库碎片、工具执行情况等中间结果
        List<KnowledgeSearchHit> retrievedResults = new ArrayList<>();
        List<ToolExecutionSummary> toolExecutions = new ArrayList<>();
        AssistantMessage finalAssistant = null; // 最终存放在这里的才是给用户的答案
        // 3. 【设置循环上限】防止 AI 陷入死循环（3次）
        int maxIterations = Math.max(agentProperties.getLoop().getMaxIterations(), 1);
        int iterationCount = 0;

        for (int iteration = 1; iteration <= maxIterations; iteration++) {
            // 是否停止
            ensureNotStopped(session);
            iterationCount = iteration;

            // 构建提示词
            String systemPrompt = agentPromptService.buildSystemPrompt(
                    lastSnapshot,
                    toolExecutions,
                    false,
                    iteration,
                    maxIterations);
            // 调用大模型
            ChatResponse response = modelSession.chatModel().call(new Prompt(
                    buildPromptMessages(systemPrompt, historyMessages, workingMessages),
                    buildToolEnabledOptions(modelSession)));

            AssistantMessage assistantMessage = response.getResult() != null
                    ? response.getResult().getOutput()
                    : null;
            if (assistantMessage == null) {
                break;
            }
            // 判断返回是否有工具
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

        // 兜底回复（如果llm什么都没有生成）
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

    /*
    * 执行工具调用
    * */
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

    /*
    *  记录对话日志
    * */
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

    /*
    * 流式输出结果
    * */
    private void streamAnswer(WebSocketSession session, String answer) {
        if (!StringUtils.hasText(answer)) {
            return;
        }
        int chunkSize = Math.max(agentProperties.getStreamChunkSize(), 1); // 120
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

    /*
    *   异常处理
    * */
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
