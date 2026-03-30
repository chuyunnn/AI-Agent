package com.yizhaoqi.searchSmart.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CitationRecord {
    private Integer index;
    private String fileName;
    private String fileMd5;
    private Integer chunkId;
    private Double score;
}
