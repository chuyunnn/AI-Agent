package com.yizhaoqi.smartpai.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ToolExecutionSummary {
    private String toolName;
    private String argumentsJson;
    private String resultPreview;
    private Integer resultCount;
    private boolean success;
    private String createdAt;
}
