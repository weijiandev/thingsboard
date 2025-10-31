package com.example.rulechain.nodes;

import com.example.rulechain.core.RuleContext;
import com.example.rulechain.core.RuleNode;
import com.example.rulechain.core.RuleNodeResult;

public class MetadataEnricherRuleNode implements RuleNode {

    private final String payloadKey;
    private final String metadataKey;

    public MetadataEnricherRuleNode(String payloadKey, String metadataKey) {
        this.payloadKey = payloadKey;
        this.metadataKey = metadataKey;
    }

    @Override
    public RuleNodeResult process(RuleContext context) {
        Object value = context.getPayloadValue(payloadKey);
        if (value != null) {
            context.putMetadataValue(metadataKey, value);
        }
        return RuleNodeResult.NEXT;
    }
}
