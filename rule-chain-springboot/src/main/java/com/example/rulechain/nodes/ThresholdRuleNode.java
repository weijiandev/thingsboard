package com.example.rulechain.nodes;

import com.example.rulechain.core.RuleContext;
import com.example.rulechain.core.RuleNode;
import com.example.rulechain.core.RuleNodeResult;

public class ThresholdRuleNode implements RuleNode {

    public enum Operator {
        GREATER_THAN,
        LESS_THAN
    }

    private final String payloadKey;
    private final double threshold;
    private final Operator operator;

    public ThresholdRuleNode(String payloadKey, double threshold, Operator operator) {
        this.payloadKey = payloadKey;
        this.threshold = threshold;
        this.operator = operator;
    }

    @Override
    public RuleNodeResult process(RuleContext context) {
        Object value = context.getPayloadValue(payloadKey);
        if (!(value instanceof Number)) {
            return RuleNodeResult.STOP;
        }
        double numericValue = ((Number) value).doubleValue();
        boolean conditionMet;
        if (operator == Operator.GREATER_THAN) {
            conditionMet = numericValue > threshold;
        } else {
            conditionMet = numericValue < threshold;
        }
        return conditionMet ? RuleNodeResult.NEXT : RuleNodeResult.STOP;
    }
}
