package com.sstlfsj.rule.kernel.api.model.ast;

import java.util.Map;

/** 叶子节点：持有具体条件类型标识及参数，由对应 ConditionEvaluator 求值。 */
public record ConditionNode(
        String conditionType,
        String metricCode,
        String displayLabel,
        Map<String, Object> params,
        /** 评分卡权重；AST_BOOLEAN kind 时忽略，SCORECARD kind 时由 ScorecardExecutor 累加。 */
        Double weight
) implements AstNode {
    public ConditionNode {
        params = Map.copyOf(params);
    }
}
