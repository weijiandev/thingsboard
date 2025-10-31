package com.example.rulechain.service;

import com.example.rulechain.core.RuleNode;
import com.example.rulechain.nodes.LogRuleNode;
import com.example.rulechain.nodes.MetadataEnricherRuleNode;
import com.example.rulechain.nodes.ThresholdRuleNode;
import com.example.rulechain.nodes.ThresholdRuleNode.Operator;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;

@Component
public class RuleNodeFactory {

    public RuleNode create(String type, Map<String, Object> configuration) {
        switch (type) {
            case "log":
                return new LogRuleNode(
                        asString(configuration.get("message")),
                        asString(configuration.get("payloadKey"))
                );
            case "threshold":
                String payloadKey = asString(configuration.get("payloadKey"));
                double threshold = asDouble(configuration.get("threshold"));
                Operator operator = parseOperator(configuration.get("operator"));
                return new ThresholdRuleNode(payloadKey, threshold, operator);
            case "enrich":
                return new MetadataEnricherRuleNode(
                        asString(configuration.get("payloadKey")),
                        asString(configuration.get("metadataKey"))
                );
            default:
                throw new IllegalArgumentException("Unsupported rule node type: " + type);
        }
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private double asDouble(Object value) {
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        return Double.parseDouble(String.valueOf(value));
    }

    private Operator parseOperator(Object value) {
        if (value == null) {
            throw new IllegalArgumentException("Missing operator configuration");
        }
        return Operator.valueOf(String.valueOf(value).toUpperCase(Locale.ROOT));
    }
}
