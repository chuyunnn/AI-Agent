package com.yizhaoqi.smartpai.controller;

import com.yizhaoqi.smartpai.handler.AgentWebSocketHandler;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/agent")
public class AgentController {

    @GetMapping("/websocket-token")
    public ResponseEntity<?> getWebSocketToken() {
        return ResponseEntity.ok(Map.of(
                "code", 200,
                "message", "获取Agent WebSocket停止指令Token成功",
                "data", Map.of("cmdToken", AgentWebSocketHandler.getInternalCmdToken())
        ));
    }
}
