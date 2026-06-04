package com.sstlfsj.rule.eval.internal.condition;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.MetricValue;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.spi.condition.ConditionEvaluator;
import org.springframework.stereotype.Component;

/**
 * NOT_BETWEEN 条件算子：value < min 或 value > max（BETWEEN 取反）。
 * params 格式：{"min": ..., "max": ...}
 */
@Component("NOT_BETWEEN")
class NotBetweenEvaluator implements ConditionEvaluator {

    @Override
    public boolean evaluate(ConditionNode node, EvalContext ctx) {
        MetricValue mv = ctx.getMetric(node.metricCode());
        if (mv == null) return false;
        Number actual = AbstractNumericEvaluator.toNumber(mv.value());
        Number min    = AbstractNumericEvaluator.toNumber(node.params().get("min"));
        Number max    = AbstractNumericEvaluator.toNumber(node.params().get("max"));
        if (actual == null || min == null || max == null) return false;
        double v = actual.doubleValue();
        return v < min.doubleValue() || v > max.doubleValue();
    }
}
