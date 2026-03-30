package com.yizhaoqi.searchSmart.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class StoredAgentMessage {
    private String type;
    private String text;
}
