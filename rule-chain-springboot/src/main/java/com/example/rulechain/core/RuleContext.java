package com.example.rulechain.core;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Mutable context shared between rule nodes during a single rule chain execution.
 */
public class RuleContext {

    private final Map<String, Object> payload;
    private final Map<String, Object> metadata;

    public RuleContext(Map<String, Object> payload) {
        this(payload, new HashMap<>());
    }

    public RuleContext(Map<String, Object> payload, Map<String, Object> metadata) {
        this.payload = payload == null ? new HashMap<>() : new HashMap<>(payload);
        this.metadata = metadata == null ? new HashMap<>() : new HashMap<>(metadata);
    }

    public Map<String, Object> getPayload() {
        return payload;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public Object getPayloadValue(String key) {
        return payload.get(key);
    }

    public void putPayloadValue(String key, Object value) {
        payload.put(key, value);
    }

    public void putMetadataValue(String key, Object value) {
        metadata.put(key, value);
    }

    public Map<String, Object> getUnmodifiablePayload() {
        return Collections.unmodifiableMap(payload);
    }

    public Map<String, Object> getUnmodifiableMetadata() {
        return Collections.unmodifiableMap(metadata);
    }
}
