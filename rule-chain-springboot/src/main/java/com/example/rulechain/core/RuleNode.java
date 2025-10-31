package com.example.rulechain.core;

public interface RuleNode {

    RuleNodeResult process(RuleContext context);
}
