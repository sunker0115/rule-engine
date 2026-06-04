package com.sstlfsj.rule.kernel.internal.condition;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.MetricValue;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.spi.condition.ConditionEvaluator;

/**
 * BETWEEN 条件算子：min <= actual <= max（双端闭区间）。
 * params 格式：{"min": ..., "max": ...}
 */
public class BetweenEvaluator implements ConditionEvaluator {

    @Override
    public boolean evaluate(ConditionNode node, EvalContext ctx) {
        MetricValue mv = ctx.getMetric(node.metricCode());
        if (mv == null) return false;
        Number actual = AbstractNumericEvaluator.toNumber(mv.value());
        Number min    = AbstractNumericEvaluator.toNumber(node.params().get("min"));
        Number max    = AbstractNumericEvaluator.toNumber(node.params().get("max"));
        if (actual == null || min == null || max == null) return false;
        double v = actual.doubleValue();
        return v >= min.doubleValue() && v <= max.doubleValue();
    }
}