package com.example.rulechain.web.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.util.HashMap;
import java.util.Map;

public class RuleChainNodeRequest {

    @NotBlank
    private String type;

    @NotNull
    private Map<String, Object> configuration = new HashMap<>();

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Map<String, Object> getConfiguration() {
        return configuration;
    }

    public void setConfiguration(Map<String, Object> configuration) {
        this.configuration = configuration == null ? new HashMap<>() : new HashMap<>(configuration);
    }
}
