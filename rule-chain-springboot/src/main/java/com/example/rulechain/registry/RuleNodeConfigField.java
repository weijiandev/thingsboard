package com.example.rulechain.registry;

public class RuleNodeConfigField {

    private final String name;
    private final String type;
    private final String description;
    private final boolean required;

    public RuleNodeConfigField(String name, String type, String description, boolean required) {
        this.name = name;
        this.type = type;
        this.description = description;
        this.required = required;
    }

    public String getName() {
        return name;
    }

    public String getType() {
        return type;
    }

    public String getDescription() {
        return description;
    }

    public boolean isRequired() {
        return required;
    }
}
