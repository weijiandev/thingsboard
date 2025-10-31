package com.example.rulechain.registry;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RuleNodeRegistry {

    private final Map<String, RuleNodeDefinition> definitions = new ConcurrentHashMap<>();

    public RuleNodeRegistry() {
        registerDefaultNodes();
    }

    private void registerDefaultNodes() {
        register(new RuleNodeDefinition(
                "log",
                "Log",
                "Write the payload or a message to the application log",
                List.of(
                        new RuleNodeConfigField("message", "string", "Optional static message to log", false),
                        new RuleNodeConfigField("payloadKey", "string", "Payload key to log when message is absent", false)
                )));

        register(new RuleNodeDefinition(
                "threshold",
                "Numeric Threshold",
                "Checks that a numeric payload value satisfies a comparison against a threshold",
                List.of(
                        new RuleNodeConfigField("payloadKey", "string", "Payload key that contains the numeric value", true),
                        new RuleNodeConfigField("threshold", "number", "Threshold to compare against", true),
                        new RuleNodeConfigField("operator", "string", "Comparison operator: GREATER_THAN or LESS_THAN", true)
                )));

        register(new RuleNodeDefinition(
                "enrich",
                "Metadata Enricher",
                "Copies a payload value into the metadata map under a new key",
                List.of(
                        new RuleNodeConfigField("payloadKey", "string", "Payload key to read", true),
                        new RuleNodeConfigField("metadataKey", "string", "Metadata key to write", true)
                )));
    }

    public void register(RuleNodeDefinition definition) {
        definitions.put(definition.getType(), definition);
    }

    public Optional<RuleNodeDefinition> findByType(String type) {
        return Optional.ofNullable(definitions.get(type));
    }

    public List<RuleNodeDefinition> getDefinitions() {
        return Collections.unmodifiableList(new ArrayList<>(definitions.values()));
    }
}
