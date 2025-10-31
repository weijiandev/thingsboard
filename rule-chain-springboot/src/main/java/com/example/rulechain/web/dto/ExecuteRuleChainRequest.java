package com.example.rulechain.web.dto;

import javax.validation.Valid;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ExecuteRuleChainRequest {

    @Valid
    @NotEmpty
    private List<RuleChainNodeRequest> nodes = new ArrayList<>();

    @NotNull
    private Map<String, Object> payload = new HashMap<>();

    public List<RuleChainNodeRequest> getNodes() {
        return nodes;
    }

    public void setNodes(List<RuleChainNodeRequest> nodes) {
        this.nodes = nodes == null ? new ArrayList<>() : new ArrayList<>(nodes);
    }

    public Map<String, Object> getPayload() {
        return payload;
    }

    public void setPayload(Map<String, Object> payload) {
        this.payload = payload == null ? new HashMap<>() : new HashMap<>(payload);
    }
}
