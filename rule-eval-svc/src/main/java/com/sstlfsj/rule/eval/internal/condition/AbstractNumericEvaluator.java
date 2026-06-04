package com.sstlfsj.rule.eval.internal.condition;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.MetricValue;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.spi.condition.ConditionEvaluator;

/**
 * 数值比较算子基类。
 * 子类实现 {@link #compare(int)} 方法，参数为 actual.compareTo(threshold) 的符号值。
 * 指标值或阈值缺失时返回 false（不抛异常，由 errorCode 机制上报）。
 */
abstract class AbstractNumericEvaluator implements ConditionEvaluator {

    /**
     * 根据 actual.compareTo(threshold) 的结果决定条件是否成立。
     *
     * @param cmp Double.compare(actual, threshold) 的结果：负数/零/正数
     * @return 条件是否满足
     */
    protected abstract boolean compare(int cmp);

    @Override
    public boolean evaluate(ConditionNode node, EvalContext ctx) {
        MetricValue mv = ctx.getMetric(node.metricCode());
        if (mv == null) return false;
        Number actual    = toNumber(mv.value());
        Number threshold = toNumber(node.params().get("threshold"));
        if (actual == null || threshold == null) return false;
        return compare(Double.compare(actual.doubleValue(), threshold.doubleValue()));
    }

    /** 将 Object 转为 Number，支持 Number 子类和数字字符串，其余返回 null。 */
    static Number toNumber(Object o) {
        if (o instanceof Number n) return n;
        if (o instanceof String s) {
            try { return Long.parseLong(s); }
            catch (NumberFormatException e1) {
                try { return Double.parseDouble(s); }
                catch (NumberFormatException e2) { return null; }
            }
        }
        return null;
    }
}
