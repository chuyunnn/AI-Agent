package com.yizhaoqi.smartpai.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeSearchHit {
    private Integer index;
    private String fileName;
    private String fileMd5;
    private Integer chunkId;
    private String snippet;
    private Double score;
    private String orgTag;
    private Boolean isPublic;
}
