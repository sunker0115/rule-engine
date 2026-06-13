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
 * NOT_IN 条件算子：actual 不在 params.values 列表中（按 node.dataType() 选策略做 equals 判定）。
 * params 格式：{"values": ["v1","v2",...]}
 * null 语义遵循 03-rule-expression §3.2 不变：指标缺失时返回 true；values 不是 Collection 时返回 true。
 */
public class NotInEvaluator implements ConditionEvaluator {

    @Override
    public boolean evaluate(ConditionNode node, EvalContext ctx) {
        MetricValue mv = ctx.getMetric(node.metricCode());
        if (mv == null) return true;
        Object valuesObj = node.params().get(ConditionParams.VALUES);
        if (!(valuesObj instanceof Collection<?> values)) return true;
        ComparisonStrategy strategy = ComparisonStrategyFactory.forType(node.dataType());
        return values.stream().noneMatch(v -> strategy.equals(mv.value(), v));
    }
}
