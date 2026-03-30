package com.yizhaoqi.smartpai.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.agent.service.AgentChatService;
import com.yizhaoqi.smartpai.utils.JwtUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class AgentWebSocketHandler extends TextWebSocketHandler {

    private static final Logger logger = LoggerFactory.getLogger(AgentWebSocketHandler.class);
    private static final String INTERNAL_CMD_TOKEN = "AGENT_WSS_STOP_CMD_" + System.currentTimeMillis() % 1000000;

    private final AgentChatService agentChatService;
    private final JwtUtils jwtUtils;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ConcurrentHashMap<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    public AgentWebSocketHandler(AgentChatService agentChatService, JwtUtils jwtUtils) {
        this.agentChatService = agentChatService;
        this.jwtUtils = jwtUtils;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String userId = extractUserId(session);
        sessions.put(userId, session);
        logger.info("Agent websocket connected, userId={}, sessionId={}", userId, session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String userId = extractUserId(session);
        try {
            String payload = message.getPayload();
            if (payload.trim().startsWith("{")) {
                Map<String, Object> jsonMessage = objectMapper.readValue(payload, Map.class);
                String type = (String) jsonMessage.get("type");
                String internalToken = (String) jsonMessage.get("_internal_cmd_token");
                if ("stop".equals(type) && INTERNAL_CMD_TOKEN.equals(internalToken)) {
                    agentChatService.stopResponse(userId, session);
                    return;
                }
            }
            agentChatService.processMessage(userId, payload, session);
        } catch (Exception e) {
            logger.error("Agent websocket failed", e);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String userId = extractUserId(session);
        sessions.remove(userId);
        logger.info("Agent websocket disconnected, userId={}, sessionId={}, status={}", userId, session.getId(), status);
    }

    public static String getInternalCmdToken() {
        return INTERNAL_CMD_TOKEN;
    }

    private String extractUserId(WebSocketSession session) {
        String path = session.getUri().getPath();
        String[] segments = path.split("/");
        String jwtToken = segments[segments.length - 1];
        String username = jwtUtils.extractUsernameFromToken(jwtToken);
        return username != null ? username : jwtToken;
    }
}
