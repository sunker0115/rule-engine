package com.sstlfsj.rule.kernel.internal.condition;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.MetricValue;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.spi.condition.ConditionEvaluator;

/**
 * EQ（等于）条件算子：支持数值和字符串比较。
 * 数值时走 Double.compare；非数值时走 String.equals。
 */
public class EqEvaluator implements ConditionEvaluator {

    @Override
    public boolean evaluate(ConditionNode node, EvalContext ctx) {
        MetricValue mv = ctx.getMetric(node.metricCode());
        if (mv == null) return false;
        Object threshold = node.params().get("threshold");
        if (threshold == null) return false;

        Number actualNum    = AbstractNumericEvaluator.toNumber(mv.value());
        Number thresholdNum = AbstractNumericEvaluator.toNumber(threshold);
        if (actualNum != null && thresholdNum != null) {
            return Double.compare(actualNum.doubleValue(), thresholdNum.doubleValue()) == 0;
        }
        return String.valueOf(mv.value()).equals(String.valueOf(threshold));
    }
}
