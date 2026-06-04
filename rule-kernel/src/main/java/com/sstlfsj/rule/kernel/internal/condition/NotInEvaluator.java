package com.sstlfsj.rule.kernel.internal.condition;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.MetricValue;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.spi.condition.ConditionEvaluator;

import java.util.Collection;

/**
 * NOT_IN：actual 的字符串值不在 params.values 列表中。value 缺失时返回 true。
 */
public class NotInEvaluator implements ConditionEvaluator {

    @Override
    public boolean evaluate(ConditionNode node, EvalContext ctx) {
        MetricValue mv = ctx.getMetric(node.metricCode());
        if (mv == null) return true;
        Object valuesObj = node.params().get("values");
        if (!(valuesObj instanceof Collection<?> values)) return true;
        String actual = String.valueOf(mv.value());
        return values.stream().noneMatch(v -> actual.equals(String.valueOf(v)));
    }
}
