package com.sstlfsj.rule.kernel.internal.condition;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.MetricValue;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.spi.condition.ConditionEvaluator;
import com.sstlfsj.rule.kernel.internal.condition.strategy.ComparisonStrategyFactory;

/**
 * BETWEEN 条件算子：min <= actual <= max（双端闭区间）。
 * params 格式：{"min": ..., "max": ...}
 * 委托 ComparisonStrategyFactory 按 node.dataType() 选策略执行比较。
 */
public class BetweenEvaluator implements ConditionEvaluator {

    @Override
    public boolean evaluate(ConditionNode node, EvalContext ctx) {
        MetricValue mv = ctx.getMetric(node.metricCode());
        if (mv == null) return false;
        Object min = node.params().get("min");
        Object max = node.params().get("max");
        if (min == null || max == null) return false;
        var strategy = ComparisonStrategyFactory.forType(node.dataType());
        int cmpMin = strategy.compare(mv.value(), min);
        int cmpMax = strategy.compare(mv.value(), max);
        if (cmpMin == Integer.MAX_VALUE || cmpMax == Integer.MAX_VALUE) return false;
        // min <= actual <= max
        return cmpMin >= 0 && cmpMax <= 0;
    }
}
