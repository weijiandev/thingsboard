package com.example.rulechain.registry;

import java.util.Collections;
import java.util.List;

public class RuleNodeDefinition {

    private final String type;
    private final String name;
    private final String description;
    private final List<RuleNodeConfigField> configFields;

    public RuleNodeDefinition(String type, String name, String description, List<RuleNodeConfigField> configFields) {
        this.type = type;
        this.name = name;
        this.description = description;
        this.configFields = configFields == null ? Collections.emptyList() : List.copyOf(configFields);
    }

    public String getType() {
        return type;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public List<RuleNodeConfigField> getConfigFields() {
        return configFields;
    }
}
