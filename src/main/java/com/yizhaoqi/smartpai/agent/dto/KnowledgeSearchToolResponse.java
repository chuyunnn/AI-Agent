package com.yizhaoqi.smartpai.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeSearchToolResponse {
    private String query;
    private Integer topK;
    private String message;
    private List<KnowledgeSearchHit> hits = new ArrayList<>();
}
