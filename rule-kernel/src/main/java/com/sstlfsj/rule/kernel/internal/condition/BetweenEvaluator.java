package com.sstlfsj.rule.kernel.internal.condition;

import com.sstlfsj.rule.kernel.api.model.ConditionTypes;
import com.sstlfsj.rule.kernel.api.model.DataType;
import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.MetricValue;
import com.sstlfsj.rule.kernel.api.model.ConditionParams;
import com.sstlfsj.rule.kernel.api.model.SceneDefaultParams;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.operator.OperatorSpec;
import com.sstlfsj.rule.kernel.api.spi.condition.ConditionEvaluator;
import com.sstlfsj.rule.kernel.internal.condition.strategy.ComparisonStrategy;
import com.sstlfsj.rule.kernel.internal.condition.strategy.ComparisonStrategyFactory;
import com.sstlfsj.rule.kernel.internal.condition.time.PlaceholderResolver;
import com.sstlfsj.rule.kernel.internal.condition.time.TimeZoneResolver;

import java.time.ZoneId;
import java.util.Optional;
import java.util.Set;

/**
 * BETWEEN 条件算子：min &lt;= actual &lt;= max（双端闭区间）。
 * params 格式：{"min": ..., "max": ...}。DATE/DATETIME 先走解析段再比较。
 */
public class BetweenEvaluator implements ConditionEvaluator {

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
            ZoneId zone = TimeZoneResolver.resolve((String) node.params().get(ConditionParams.TIMEZONE),
                    (String) ctx.sceneDefaultParams().get(SceneDefaultParams.TIMEZONE));
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
        // min <= actual <= max
        return cmpMin >= 0 && cmpMax <= 0;
    }

    @Override
    public Optional<OperatorSpec> spec() {
        return Optional.of(OperatorSpec.builder().code(ConditionTypes.BETWEEN).displayName("区间内")
                .requiredParamKeys(Set.of(ConditionParams.MIN, ConditionParams.MAX))
                .allowedDataTypes(Set.of(DataType.LONG.tag(), DataType.DOUBLE.tag(), DataType.DECIMAL.tag(),
                        DataType.DATE.tag(), DataType.DATETIME.tag()))
                .requiresMetric(true).build());
    }
}
