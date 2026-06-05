package com.sstlfsj.rule.kernel.internal.condition;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.MetricValue;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.spi.condition.ConditionEvaluator;
import com.sstlfsj.rule.kernel.internal.condition.strategy.ComparisonStrategyFactory;
import com.sstlfsj.rule.kernel.internal.condition.time.PlaceholderResolver;
import com.sstlfsj.rule.kernel.internal.condition.time.TimeZoneResolver;

import java.time.ZoneId;

/**
 * NEQ（不等于）条件算子：按 node.dataType() 选策略后取 equals 的非。
 * DATE/DATETIME 先走解析段；解析失败时"不等"成立返回 true。
 * 其余 dataType 直通原始值。dataType=null（DSL）走 DefaultComparisonStrategy。
 */
public class NeqEvaluator implements ConditionEvaluator {

    @Override
    public boolean evaluate(ConditionNode node, EvalContext ctx) {
        MetricValue mv = ctx.getMetric(node.metricCode());
        if (mv == null) return false;
        Object threshold = node.params().get("threshold");
        if (threshold == null) return false;
        String dt = node.dataType();
        Object actual = mv.value();
        if ("DATE".equals(dt) || "DATETIME".equals(dt)) {
            ZoneId zone = TimeZoneResolver.resolve((String) node.params().get("timezone"), null);
            actual    = PlaceholderResolver.resolveTyped(dt, actual, ctx, zone);
            threshold = PlaceholderResolver.resolveTyped(dt, threshold, ctx, zone);
            // 解析失败时"不等"成立
            if (actual == null || threshold == null) return true;
        }
        return !ComparisonStrategyFactory.forType(dt).equals(actual, threshold);
    }
}
