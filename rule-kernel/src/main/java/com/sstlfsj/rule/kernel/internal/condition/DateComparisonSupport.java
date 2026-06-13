package com.sstlfsj.rule.kernel.internal.condition;

import com.sstlfsj.rule.kernel.api.model.DataType;
import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.MetricValue;
import com.sstlfsj.rule.kernel.api.model.ConditionParams;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.internal.condition.strategy.ComparisonStrategyFactory;
import com.sstlfsj.rule.kernel.internal.condition.time.PlaceholderResolver;
import com.sstlfsj.rule.kernel.internal.condition.time.TimeZoneResolver;

import java.time.ZoneId;

/** DATE_BEFORE / DATE_AFTER 共用解析+比较：dataType=null 时按 DATETIME 解析（复刻旧 toInstant 语义）。 */
final class DateComparisonSupport {

    private DateComparisonSupport() {}

    static boolean evaluate(ConditionNode node, EvalContext ctx, boolean before) {
        MetricValue mv = ctx.getMetric(node.metricCode());
        if (mv == null) return false;
        Object threshold = node.params().get(ConditionParams.THRESHOLD);
        if (threshold == null) return false;
        // dataType=null（DSL 默认）→ DATETIME（保持旧 toInstant UTC Instant 语义）；DATE → LocalDate 比较
        String effectiveType = DataType.DATE.tag().equals(node.dataType()) ? DataType.DATE.tag() : DataType.DATETIME.tag();
        ZoneId zone = TimeZoneResolver.resolve((String) node.params().get(ConditionParams.TIMEZONE), null);
        Object actual = PlaceholderResolver.resolveTyped(effectiveType, mv.value(), ctx, zone);
        Object operand = PlaceholderResolver.resolveTyped(effectiveType, threshold, ctx, zone);
        if (actual == null || operand == null) return false;
        int cmp = ComparisonStrategyFactory.forType(effectiveType).compare(actual, operand);
        if (cmp == Integer.MAX_VALUE) return false;
        return before ? cmp < 0 : cmp > 0;
    }
}
