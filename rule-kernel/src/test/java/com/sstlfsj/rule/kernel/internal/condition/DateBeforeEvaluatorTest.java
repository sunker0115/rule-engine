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

class DateBeforeEvaluatorTest {

    private final DateBeforeEvaluator evaluator = new DateBeforeEvaluator();

    private EvalContext ctx(String metric, Object value) {
        RuleEvent event = new RuleEvent("e1", "t1", "s1", "sub1", "EVT",
                Instant.now(), Map.of(), Map.of(), com.sstlfsj.rule.kernel.api.model.EventSource.HTTP);
        Subject subject = new Subject("sub1", SubjectType.USER, Map.of());
        return new EvalContext("t1", event, subject,
                Map.of(metric, new MetricValue(value, "UNKNOWN", "PROVIDED")), Instant.parse("2026-06-01T00:00:00Z"));
    }

    private ConditionNode node(String metric, Object threshold) {
        return new ConditionNode("DATE_BEFORE", metric, "", Map.of("threshold", threshold), 0.0);
    }

    @Test
    void dateBefore_isoString_returnsTrue() {
        assertThat(evaluator.evaluate(node("d", "2023-01-01"), ctx("d", "2022-06-01"))).isTrue();
    }

    @Test
    void dateAfter_isoString_returnsFalse() {
        assertThat(evaluator.evaluate(node("d", "2020-01-01"), ctx("d", "2023-06-01"))).isFalse();
    }

    @Test
    void dateSame_returnsFalse() {
        assertThat(evaluator.evaluate(node("d", "2023-01-01"), ctx("d", "2023-01-01"))).isFalse();
    }

    @Test
    void instantValue_worksCorrectly() {
        Instant past = Instant.parse("2022-01-01T00:00:00Z");
        Instant future = Instant.parse("2030-01-01T00:00:00Z");
        assertThat(evaluator.evaluate(node("d", future.toString()), ctx("d", past))).isTrue();
    }

    @Test
    void invalidDateString_returnsFalse() {
        assertThat(evaluator.evaluate(node("d", "not-a-date"), ctx("d", "2023-01-01"))).isFalse();
    }

    @Test
    void metricMissing_returnsFalse() {
        RuleEvent event = new RuleEvent("e1", "t1", "s1", "sub1", "EVT",
                Instant.now(), Map.of(), Map.of(), com.sstlfsj.rule.kernel.api.model.EventSource.HTTP);
        Subject subject = new Subject("sub1", SubjectType.USER, Map.of());
        EvalContext emptyCtx = new EvalContext("t1", event, subject, Map.of(), Instant.parse("2026-06-01T00:00:00Z"));
        assertThat(evaluator.evaluate(node("d", "2023-01-01"), emptyCtx)).isFalse();
    }

    @Test
    void dateBefore_dateType_today_comparesAsLocalDate() {
        // metric=2026-06-01，threshold=$today，now 投影到 UTC 是 2026-06-02 → before=true
        Instant now = Instant.parse("2026-06-02T00:00:00Z");
        ConditionNode node = new ConditionNode("DATE_BEFORE", "d", "",
                Map.of("threshold", "$today"), 0.0, "DATE");
        RuleEvent event = new RuleEvent("t1", "s1", "E", "u1", "e1", now, Map.of(), Map.of(), com.sstlfsj.rule.kernel.api.model.EventSource.HTTP);
        EvalContext ctx = new EvalContext("t1", event, null,
                Map.of("d", new MetricValue("2026-06-01", "DATE", "PROVIDED")), now);
        assertThat(evaluator.evaluate(node, ctx)).isTrue();
    }
}
