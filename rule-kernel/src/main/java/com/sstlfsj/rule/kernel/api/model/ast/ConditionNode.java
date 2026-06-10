package com.sstlfsj.rule.kernel.api.model.ast;

import com.sstlfsj.rule.kernel.api.model.ValueRef;

import java.util.Map;

/**
 * 叶子节点：持有具体条件类型标识及参数，由对应 ConditionEvaluator 求值。
 * dataType 由发布期 AstDataTypeResolver 冻结（LONG/DOUBLE/STRING/BOOLEAN/LIST）；
 * DSL 构造时为 null，求值期走 DefaultComparisonStrategy 按值推断。
 * valueRef 标识取值来源：METRIC（默认，走 ctx.metrics）/ PAYLOAD（直接读 event.payload，metricCode 复用为字段名）。
 */
public record ConditionNode(
        String conditionType,
        String metricCode,
        String displayLabel,
        Map<String, Object> params,
        /** 评分卡权重；AST_BOOLEAN kind 时忽略，SCORECARD kind 时由 ScorecardExecutor 累加。 */
        Double weight,
        String dataType,
        ValueRef valueRef
) implements AstNode {
    public ConditionNode {
        params = Map.copyOf(params);
        // 旧 JSON / 旧构造路径无 valueRef 时默认 METRIC，保证语义不变
        if (valueRef == null) valueRef = ValueRef.METRIC;
    }

    /** 带 dataType 的构造入口（发布期 AstDataTypeResolver 重建），valueRef 默认 METRIC。 */
    public ConditionNode(String conditionType, String metricCode, String displayLabel,
                         Map<String, Object> params, Double weight, String dataType) {
        this(conditionType, metricCode, displayLabel, params, weight, dataType, ValueRef.METRIC);
    }

    /** 未声明类型的构造入口（DSL、DecisionTableExecutor 合成节点等），dataType=null（走 Default 策略），valueRef=METRIC。 */
    public ConditionNode(String conditionType, String metricCode, String displayLabel,
                         Map<String, Object> params, Double weight) {
        this(conditionType, metricCode, displayLabel, params, weight, null, ValueRef.METRIC);
    }
}
