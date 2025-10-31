package com.example.rulechain.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class RuleChain {

    private final List<RuleNode> nodes = new ArrayList<>();

    public RuleChain addNode(RuleNode node) {
        if (node == null) {
            throw new IllegalArgumentException("Rule node cannot be null");
        }
        nodes.add(node);
        return this;
    }

    public List<RuleNode> getNodes() {
        return Collections.unmodifiableList(nodes);
    }

    public RuleContext run(RuleContext context) {
        RuleContext executionContext = context == null ? new RuleContext(Collections.emptyMap()) : context;
        for (RuleNode node : nodes) {
            RuleNodeResult result = node.process(executionContext);
            if (result == RuleNodeResult.STOP) {
                break;
            }
        }
        return executionContext;
    }
}
