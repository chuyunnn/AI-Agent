package com.yizhaoqi.smartpai.agent.service;

import com.yizhaoqi.smartpai.agent.dto.KnowledgeSearchHit;
import com.yizhaoqi.smartpai.agent.dto.LastChatResponseSnapshot;
import com.yizhaoqi.smartpai.agent.dto.ToolExecutionSummary;
import com.yizhaoqi.smartpai.config.AiProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentPromptServiceTest {

    @Test
    void shouldIncludeLastSnapshotAndForceAnswerInstruction() {
        AiProperties aiProperties = new AiProperties();
        aiProperties.getPrompt().setRules("请用中文回答");
        AgentPromptService promptService = new AgentPromptService(aiProperties);

        LastChatResponseSnapshot snapshot = new LastChatResponseSnapshot();
        snapshot.setUserMessage("上一个问题");
        snapshot.setFinalAnswer("上一个答案");
        snapshot.setRetrievedResults(List.of(
                new KnowledgeSearchHit(1, "spec.md", "md5", 2, "snippet", 0.9, "TECH", true)
        ));

        String prompt = promptService.buildSystemPrompt(
                snapshot,
                List.of(new ToolExecutionSummary("searchKnowledge", "{}", "成功检索到1条", 1, true, "2026-03-30T10:00:00")),
                true,
                3,
                3
        );

        assertTrue(prompt.contains("请用中文回答"));
        assertTrue(prompt.contains("上一轮回答：上一个答案"));
        assertTrue(prompt.contains("禁止再调用任何工具"));
        assertTrue(prompt.contains("searchKnowledge => 成功检索到1条"));
    }
}
