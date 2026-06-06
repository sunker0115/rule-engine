package com.sstlfsj.rule.kernel.api.model.ast;

import java.util.Map;

/**
 * 叶子节点：持有具体条件类型标识及参数，由对应 ConditionEvaluator 求值。
 * dataType 由发布期 AstDataTypeResolver 冻结（LONG/DOUBLE/STRING/BOOLEAN/LIST）；
 * DSL 构造时为 null，求值期走 DefaultComparisonStrategy 按值推断。
 */
public record ConditionNode(
        String conditionType,
        String metricCode,
        String displayLabel,
        Map<String, Object> params,
        /** 评分卡权重；AST_BOOLEAN kind 时忽略，SCORECARD kind 时由 ScorecardExecutor 累加。 */
        Double weight,
        String dataType
) implements AstNode {
    public ConditionNode {
        params = Map.copyOf(params);
    }

    /** 未声明类型的构造入口（DSL、DecisionTableExecutor 合成节点等），dataType=null（走 Default 策略）。 */
    public ConditionNode(String conditionType, String metricCode, String displayLabel,
                         Map<String, Object> params, Double weight) {
        this(conditionType, metricCode, displayLabel, params, weight, null);
    }
}
