package com.yizhaoqi.searchSmart.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "searchsmart.agent")
@Data
public class AgentProperties {

    private String defaultModelProfile = "deepseek";

    private int streamChunkSize = 120;

    private Memory memory = new Memory();

    private Loop loop = new Loop();

    private Map<String, ModelProfile> models = new LinkedHashMap<>();

    @Data
    public static class Memory {
        private int maxMessages = 20;
        private long ttlDays = 7;
    }

    @Data
    public static class Loop {
        private int maxIterations = 3;
    }

    @Data
    public static class ModelProfile {
        private String baseUrl;
        private String apiKey;
        private String model;
        private Double temperature = 0.3;
        private Integer maxTokens = 2000;
        private Double topP = 0.9;
    }
}
