package com.sstlfsj.rule.kernel.internal.condition;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.MetricValue;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import com.sstlfsj.rule.kernel.api.model.Subject;
import com.sstlfsj.rule.kernel.api.model.SubjectType;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DateAfterEvaluatorTest {

    private final DateAfterEvaluator evaluator = new DateAfterEvaluator();

    private EvalContext ctx(String metric, Object value) {
        RuleEvent event = new RuleEvent("e1", "t1", "s1", "sub1", "EVT",
                Instant.now(), Map.of(), Map.of(), com.sstlfsj.rule.kernel.api.model.EventSource.HTTP);
        Subject subject = new Subject("sub1", SubjectType.USER, Map.of());
        return new EvalContext("t1", event, subject,
                Map.of(metric, new MetricValue(value, "UNKNOWN", "PROVIDED")), Instant.parse("2026-06-01T00:00:00Z"));
    }

    private ConditionNode node(String metric, Object threshold) {
        return new ConditionNode("DATE_AFTER", metric, "", Map.of("threshold", threshold), 0.0);
    }

    @Test
    void dateAfterThreshold_returnsTrue() {
        assertThat(evaluator.evaluate(node("d", "2020-01-01"), ctx("d", "2023-06-01"))).isTrue();
    }

    @Test
    void dateBeforeThreshold_returnsFalse() {
        assertThat(evaluator.evaluate(node("d", "2030-01-01"), ctx("d", "2023-06-01"))).isFalse();
    }

    @Test
    void dateSameAsThreshold_returnsFalse() {
        assertThat(evaluator.evaluate(node("d", "2023-01-01"), ctx("d", "2023-01-01"))).isFalse();
    }

    @Test
    void metricMissing_returnsFalse() {
        RuleEvent event = new RuleEvent("e1", "t1", "s1", "sub1", "EVT",
                Instant.now(), Map.of(), Map.of(), com.sstlfsj.rule.kernel.api.model.EventSource.HTTP);
        Subject subject = new Subject("sub1", SubjectType.USER, Map.of());
        EvalContext emptyCtx = new EvalContext("t1", event, subject, Map.of(), Instant.parse("2026-06-01T00:00:00Z"));
        assertThat(evaluator.evaluate(node("d", "2020-01-01"), emptyCtx)).isFalse();
    }
}
