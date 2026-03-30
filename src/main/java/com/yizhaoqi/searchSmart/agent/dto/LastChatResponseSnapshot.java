package com.yizhaoqi.searchSmart.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LastChatResponseSnapshot {
    private String conversationId;
    private String userId;
    private String userMessage;
    private String finalAnswer;
    private List<KnowledgeSearchHit> retrievedResults = new ArrayList<>();
    private List<CitationRecord> citations = new ArrayList<>();
    private List<ToolExecutionSummary> toolExecutions = new ArrayList<>();
    private String modelProfile;
    private Integer iterationCount;
    private String createdAt;
}
