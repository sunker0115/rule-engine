package com.sstlfsj.rule.kernel.api.model;

/** 规则命中后的决策描述，priority 越大越优先。 */
public record Decision(
        String code,
        String name,
        int priority,
        Long fromRuleVersionId
) {}
