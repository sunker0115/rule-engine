package com.sstlfsj.rule.kernel.internal.condition;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.MetricValue;
import com.sstlfsj.rule.kernel.api.model.ConditionParams;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.spi.condition.ConditionEvaluator;
import com.sstlfsj.rule.kernel.internal.condition.strategy.ComparisonStrategy;
import com.sstlfsj.rule.kernel.internal.condition.strategy.ComparisonStrategyFactory;

import java.util.Collection;

/**
 * IN 条件算子：actual 在 params.values 列表中（按 node.dataType() 选策略做 equals 判定）。
 * params 格式：{"values": ["v1","v2",...]}
 */
public class InEvaluator implements ConditionEvaluator {

    @Override
    public boolean evaluate(ConditionNode node, EvalContext ctx) {
        MetricValue mv = ctx.getMetric(node.metricCode());
        if (mv == null) return false;
        Object valuesObj = node.params().get(ConditionParams.VALUES);
        if (!(valuesObj instanceof Collection<?> values)) return false;
        ComparisonStrategy strategy = ComparisonStrategyFactory.forType(node.dataType());
        return values.stream().anyMatch(v -> strategy.equals(mv.value(), v));
    }
}
