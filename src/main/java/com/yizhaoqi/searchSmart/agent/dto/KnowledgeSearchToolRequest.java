package com.yizhaoqi.searchSmart.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeSearchToolRequest {
    private String query;
    private Integer topK;
    private String fileMd5;
    private String orgTag;
}
