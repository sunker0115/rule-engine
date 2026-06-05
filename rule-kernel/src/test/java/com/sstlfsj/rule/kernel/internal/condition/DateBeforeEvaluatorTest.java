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
                Instant.now(), Map.of(), Map.of());
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
                Instant.now(), Map.of(), Map.of());
        Subject subject = new Subject("sub1", SubjectType.USER, Map.of());
        EvalContext emptyCtx = new EvalContext("t1", event, subject, Map.of(), Instant.parse("2026-06-01T00:00:00Z"));
        assertThat(evaluator.evaluate(node("d", "2023-01-01"), emptyCtx)).isFalse();
    }

    @Test
    void toInstant_localDateString_parsedCorrectly() {
        Instant result = DateBeforeEvaluator.toInstant("2023-06-15");
        assertThat(result).isNotNull();
        assertThat(result.toString()).startsWith("2023-06-15");
    }

    @Test
    void toInstant_instantString_parsedCorrectly() {
        Instant result = DateBeforeEvaluator.toInstant("2023-06-15T10:00:00Z");
        assertThat(result).isEqualTo(Instant.parse("2023-06-15T10:00:00Z"));
    }

    @Test
    void toInstant_instantObject_returnedAsIs() {
        Instant now = Instant.now();
        assertThat(DateBeforeEvaluator.toInstant(now)).isEqualTo(now);
    }
}
