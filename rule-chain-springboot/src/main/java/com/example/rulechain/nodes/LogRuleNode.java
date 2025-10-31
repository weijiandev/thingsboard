package com.example.rulechain.nodes;

import com.example.rulechain.core.RuleContext;
import com.example.rulechain.core.RuleNode;
import com.example.rulechain.core.RuleNodeResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LogRuleNode implements RuleNode {

    private static final Logger log = LoggerFactory.getLogger(LogRuleNode.class);

    private final String message;
    private final String payloadKey;

    public LogRuleNode(String message, String payloadKey) {
        this.message = message;
        this.payloadKey = payloadKey;
    }

    @Override
    public RuleNodeResult process(RuleContext context) {
        if (message != null && !message.isEmpty()) {
            log.info("[RuleChain] {}", message);
        } else if (payloadKey != null && !payloadKey.isEmpty()) {
            log.info("[RuleChain] {} = {}", payloadKey, context.getPayload().get(payloadKey));
        } else {
            log.info("[RuleChain] payload={}", context.getPayload());
        }
        return RuleNodeResult.NEXT;
    }
}
