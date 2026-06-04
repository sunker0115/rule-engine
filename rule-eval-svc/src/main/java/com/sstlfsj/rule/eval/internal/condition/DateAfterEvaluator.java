package com.sstlfsj.rule.eval.internal.condition;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.MetricValue;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.spi.condition.ConditionEvaluator;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * DATE_AFTER 条件算子：metric 日期值严格晚于阈值。
 * 解析规则与 DateBeforeEvaluator.toInstant 一致。
 * params 格式：{"threshold": "2024-01-01"} 或 {"threshold": "2024-01-01T00:00:00Z"}
 */
@Component("DATE_AFTER")
class DateAfterEvaluator implements ConditionEvaluator {

    @Override
    public boolean evaluate(ConditionNode node, EvalContext ctx) {
        MetricValue mv = ctx.getMetric(node.metricCode());
        if (mv == null) return false;
        Object thresholdObj = node.params().get("threshold");
        if (thresholdObj == null) return false;
        Instant actual    = DateBeforeEvaluator.toInstant(mv.value());
        Instant threshold = DateBeforeEvaluator.toInstant(thresholdObj);
        if (actual == null || threshold == null) return false;
        return actual.isAfter(threshold);
    }
}
