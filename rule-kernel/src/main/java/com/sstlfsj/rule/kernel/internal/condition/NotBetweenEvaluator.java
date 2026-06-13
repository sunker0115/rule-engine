package com.sstlfsj.rule.kernel.internal.condition;

import com.sstlfsj.rule.kernel.api.model.DataType;
import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.MetricValue;
import com.sstlfsj.rule.kernel.api.model.ConditionParams;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.spi.condition.ConditionEvaluator;
import com.sstlfsj.rule.kernel.internal.condition.strategy.ComparisonStrategy;
import com.sstlfsj.rule.kernel.internal.condition.strategy.ComparisonStrategyFactory;
import com.sstlfsj.rule.kernel.internal.condition.time.PlaceholderResolver;
import com.sstlfsj.rule.kernel.internal.condition.time.TimeZoneResolver;

import java.time.ZoneId;

/**
 * NOT_BETWEEN 条件算子：value &lt; min 或 value &gt; max（BETWEEN 取反）。
 * params 格式：{"min": ..., "max": ...}。DATE/DATETIME 先走解析段再比较。
 */
public class NotBetweenEvaluator implements ConditionEvaluator {

    @Override
    public boolean evaluate(ConditionNode node, EvalContext ctx) {
        MetricValue mv = ctx.getMetric(node.metricCode());
        if (mv == null) return false;
        Object min = node.params().get(ConditionParams.MIN);
        Object max = node.params().get(ConditionParams.MAX);
        if (min == null || max == null) return false;
        String dt = node.dataType();
        Object actual = mv.value();
        if (DataType.DATE.tag().equals(dt) || DataType.DATETIME.tag().equals(dt)) {
            ZoneId zone = TimeZoneResolver.resolve((String) node.params().get(ConditionParams.TIMEZONE), null);
            actual = PlaceholderResolver.resolveTyped(dt, actual, ctx, zone);
            min    = PlaceholderResolver.resolveTyped(dt, min, ctx, zone);
            max    = PlaceholderResolver.resolveTyped(dt, max, ctx, zone);
            if (actual == null || min == null || max == null) return false;
        }
        ComparisonStrategy strategy = ComparisonStrategyFactory.forType(dt);
        int cmpMin, cmpMax;
        try {
            cmpMin = strategy.compare(actual, min);
            cmpMax = strategy.compare(actual, max);
        } catch (UnsupportedOperationException e) {
            // 无序类型（如 BOOLEAN）不支持排序比较，视为不满足
            return false;
        }
        if (cmpMin == Integer.MAX_VALUE || cmpMax == Integer.MAX_VALUE) return false;
        // actual < min 或 actual > max
        return cmpMin < 0 || cmpMax > 0;
    }
}
