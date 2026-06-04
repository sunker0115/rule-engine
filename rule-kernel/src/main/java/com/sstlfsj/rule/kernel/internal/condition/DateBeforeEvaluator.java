package com.sstlfsj.rule.kernel.internal.condition;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.MetricValue;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.spi.condition.ConditionEvaluator;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

/**
 * DATE_BEFORE 条件算子：metric 日期值严格早于阈值。
 * metric 值和 threshold 均支持 ISO-8601（日期或日期时间字符串）或 Instant。
 * params 格式：{"threshold": "2024-01-01"} 或 {"threshold": "2024-01-01T00:00:00Z"}
 */
public class DateBeforeEvaluator implements ConditionEvaluator {

    @Override
    public boolean evaluate(ConditionNode node, EvalContext ctx) {
        MetricValue mv = ctx.getMetric(node.metricCode());
        if (mv == null) return false;
        Object thresholdObj = node.params().get("threshold");
        if (thresholdObj == null) return false;
        Instant actual    = toInstant(mv.value());
        Instant threshold = toInstant(thresholdObj);
        if (actual == null || threshold == null) return false;
        return actual.isBefore(threshold);
    }

    static Instant toInstant(Object o) {
        if (o instanceof Instant i) return i;
        if (o instanceof String s) {
            try { return Instant.parse(s); }
            catch (DateTimeParseException e1) {
                try { return LocalDate.parse(s).atStartOfDay().toInstant(ZoneOffset.UTC); }
                catch (DateTimeParseException e2) { return null; }
            }
        }
        return null;
    }
}
