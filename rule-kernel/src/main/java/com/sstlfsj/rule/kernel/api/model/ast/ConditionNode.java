package com.sstlfsj.rule.kernel.api.model.ast;

import java.util.Map;

/** 叶子节点：持有具体条件类型标识及参数，由对应 ConditionEvaluator 求值。 */
public record ConditionNode(
        String conditionType,
        String metricCode,
        String displayLabel,
        Map<String, Object> params
) implements AstNode {
    public ConditionNode {
        params = Map.copyOf(params);
    }
}
