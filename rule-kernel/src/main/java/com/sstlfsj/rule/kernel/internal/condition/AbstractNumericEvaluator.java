package com.sstlfsj.rule.kernel.internal.condition;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.MetricValue;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.spi.condition.ConditionEvaluator;
import com.sstlfsj.rule.kernel.internal.condition.strategy.ComparisonStrategy;
import com.sstlfsj.rule.kernel.internal.condition.strategy.ComparisonStrategyFactory;

/**
 * 数值比较算子基类（GT/GTE/LT/LTE 继承）。
 * 委托 {@link ComparisonStrategyFactory} 按 node.dataType() 选策略，
 * 子类实现 {@link #accept(int)} 方法解读 compare 符号结果。
 * 指标值或阈值缺失/无法比较时返回 false（不抛异常）。
 */
abstract class AbstractNumericEvaluator implements ConditionEvaluator {

    /**
     * 根据 actual.compare(threshold) 的结果决定条件是否成立。
     *
     * @param cmp 策略 compare 返回值：负数/零/正数
     * @return 条件是否满足
     */
    protected abstract boolean accept(int cmp);

    @Override
    public boolean evaluate(ConditionNode node, EvalContext ctx) {
        MetricValue mv = ctx.getMetric(node.metricCode());
        if (mv == null) return false;
        Object threshold = node.params().get("threshold");
        if (threshold == null) return false;
        ComparisonStrategy strategy = ComparisonStrategyFactory.forType(node.dataType());
        int cmp = strategy.compare(mv.value(), threshold);
        // compare 返回 Integer.MAX_VALUE 表示转换失败（null/NaN/Infinity），视为 false
        if (cmp == Integer.MAX_VALUE) return false;
        return accept(cmp);
    }
}
