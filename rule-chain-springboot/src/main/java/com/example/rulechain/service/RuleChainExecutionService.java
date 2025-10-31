package com.example.rulechain.service;

import com.example.rulechain.core.RuleChain;
import com.example.rulechain.core.RuleContext;
import com.example.rulechain.core.RuleNode;
import com.example.rulechain.registry.RuleNodeRegistry;
import com.example.rulechain.web.dto.RuleChainNodeRequest;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class RuleChainExecutionService {

    private final RuleNodeFactory ruleNodeFactory;
    private final RuleNodeRegistry registry;

    public RuleChainExecutionService(RuleNodeFactory ruleNodeFactory, RuleNodeRegistry registry) {
        this.ruleNodeFactory = ruleNodeFactory;
        this.registry = registry;
    }

    public RuleContext execute(List<RuleChainNodeRequest> nodeRequests, Map<String, Object> payload) {
        RuleChain chain = new RuleChain();
        for (RuleChainNodeRequest nodeRequest : nodeRequests) {
            RuleNode node = ruleNodeFactory.create(nodeRequest.getType(), nodeRequest.getConfiguration());
            chain.addNode(node);
        }
        return chain.run(new RuleContext(payload));
    }

    public RuleNodeRegistry getRegistry() {
        return registry;
    }
}
