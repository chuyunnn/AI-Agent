package com.yizhaoqi.smartpai.agent.service;

import com.yizhaoqi.smartpai.agent.dto.LastChatResponseSnapshot;
import com.yizhaoqi.smartpai.agent.dto.ToolExecutionSummary;
import com.yizhaoqi.smartpai.config.AiProperties;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AgentPromptService {

    private final AiProperties aiProperties;

    public AgentPromptService(AiProperties aiProperties) {
        this.aiProperties = aiProperties;
    }

    public String buildSystemPrompt(LastChatResponseSnapshot lastSnapshot,
                                    List<ToolExecutionSummary> toolExecutions,
                                    boolean forceDirectAnswer,
                                    int currentIteration,
                                    int maxIterations) {
        StringBuilder prompt = new StringBuilder();
        if (aiProperties.getPrompt().getRules() != null) {
            prompt.append(aiProperties.getPrompt().getRules()).append("\n\n");
        }

        prompt.append("你现在运行在一个支持工具调用的知识库 Agent 中。\n")
                .append("可用工具只有 searchKnowledge，用于从知识库检索最多10条相关片段。\n")
                .append("当问题涉及知识库内容时，优先调用工具，再基于工具结果作答。\n")
                .append("如果工具无结果或证据不足，必须明确说明“暂无相关信息”或“证据不足”。\n")
                .append("回答时优先输出结论，再给论据。\n")
                .append("如引用检索结果，请使用 (来源#编号: 文件名) 形式。\n")
                .append("不要编造工具结果中不存在的文件名、编号或内容。\n")
                .append("本轮工具循环上限为 ").append(maxIterations).append(" 轮，当前为第 ")
                .append(currentIteration).append(" 轮。\n");

        if (forceDirectAnswer) {
            prompt.append("你必须基于当前对话中的已有工具结果直接生成最终答案，禁止再调用任何工具。\n");
        }

        if (lastSnapshot != null && lastSnapshot.getFinalAnswer() != null && !lastSnapshot.getFinalAnswer().isBlank()) {
            prompt.append("\n上轮对话摘要：\n")
                    .append("上一轮问题：").append(nullToEmpty(lastSnapshot.getUserMessage())).append("\n")
                    .append("上一轮回答：").append(lastSnapshot.getFinalAnswer()).append("\n");
            if (lastSnapshot.getRetrievedResults() != null && !lastSnapshot.getRetrievedResults().isEmpty()) {
                prompt.append("上一轮检索命中：\n");
                lastSnapshot.getRetrievedResults().stream()
                        .limit(5)
                        .forEach(hit -> prompt.append("- #")
                                .append(hit.getIndex())
                                .append(" ")
                                .append(nullToEmpty(hit.getFileName()))
                                .append("\n"));
            }
        }

        if (toolExecutions != null && !toolExecutions.isEmpty()) {
            prompt.append("\n本轮已执行工具摘要：\n");
            toolExecutions.forEach(summary -> prompt.append("- ")
                    .append(summary.getToolName())
                    .append(" => ")
                    .append(nullToEmpty(summary.getResultPreview()))
                    .append("\n"));
        }

        return prompt.toString();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
