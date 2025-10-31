package com.example.rulechain.web.dto;

import java.util.Map;

public class RuleChainExecutionResponse {

    private final Map<String, Object> payload;
    private final Map<String, Object> metadata;

    public RuleChainExecutionResponse(Map<String, Object> payload, Map<String, Object> metadata) {
        this.payload = payload;
        this.metadata = metadata;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }
}
