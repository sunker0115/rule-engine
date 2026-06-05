package com.sstlfsj.rule.kernel.internal.condition;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.MetricValue;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.spi.condition.ConditionEvaluator;
import com.sstlfsj.rule.kernel.internal.condition.strategy.ComparisonStrategyFactory;

/**
 * EQ（等于）条件算子：按 node.dataType() 选策略后调用 strategy.equals()。
 * dataType=null（DSL）时走 DefaultComparisonStrategy，按 actual 运行时类型推断。
 */
public class EqEvaluator implements ConditionEvaluator {

    @Override
    public boolean evaluate(ConditionNode node, EvalContext ctx) {
        MetricValue mv = ctx.getMetric(node.metricCode());
        if (mv == null) return false;
        Object threshold = node.params().get("threshold");
        if (threshold == null) return false;
        return ComparisonStrategyFactory.forType(node.dataType()).equals(mv.value(), threshold);
    }
}
