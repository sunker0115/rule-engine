package com.sstlfsj.rule.kernel.internal.condition;

import com.sstlfsj.rule.kernel.api.model.DataType;
import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.MetricValue;
import com.sstlfsj.rule.kernel.api.model.ConditionParams;
import com.sstlfsj.rule.kernel.api.model.SceneDefaultParams;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.spi.condition.ConditionEvaluator;
import com.sstlfsj.rule.kernel.internal.condition.strategy.ComparisonStrategyFactory;
import com.sstlfsj.rule.kernel.internal.condition.time.PlaceholderResolver;
import com.sstlfsj.rule.kernel.internal.condition.time.TimeZoneResolver;

import java.time.ZoneId;

/**
 * EQ（等于）条件算子：按 node.dataType() 选策略后调用 strategy.equals()。
 * DATE/DATETIME 先走解析段把 actual/operand 解析为 java.time 值再比较；
 * 其余 dataType 直通原始值（恒等段）。dataType=null（DSL）走 DefaultComparisonStrategy。
 */
public class EqEvaluator implements ConditionEvaluator {

    @Override
    public boolean evaluate(ConditionNode node, EvalContext ctx) {
        MetricValue mv = ctx.getMetric(node.metricCode());
        if (mv == null) return false;
        Object threshold = node.params().get(ConditionParams.THRESHOLD);
        if (threshold == null) return false;
        String dt = node.dataType();
        Object actual = mv.value();
        if (DataType.DATE.tag().equals(dt) || DataType.DATETIME.tag().equals(dt)) {
            ZoneId zone = TimeZoneResolver.resolve((String) node.params().get(ConditionParams.TIMEZONE),
                    (String) ctx.sceneDefaultParams().get(SceneDefaultParams.TIMEZONE));
            actual    = PlaceholderResolver.resolveTyped(dt, actual, ctx, zone);
            threshold = PlaceholderResolver.resolveTyped(dt, threshold, ctx, zone);
            if (actual == null || threshold == null) return false;
        }
        return ComparisonStrategyFactory.forType(dt).equals(actual, threshold);
    }
}
