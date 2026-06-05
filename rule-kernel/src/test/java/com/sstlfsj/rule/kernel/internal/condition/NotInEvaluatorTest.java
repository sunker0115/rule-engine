package com.sstlfsj.rule.kernel.internal.condition;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.MetricValue;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import com.sstlfsj.rule.kernel.api.model.Subject;
import com.sstlfsj.rule.kernel.api.model.SubjectType;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class NotInEvaluatorTest {

    private final NotInEvaluator evaluator = new NotInEvaluator();

    private EvalContext ctx(String metric, Object value) {
        RuleEvent event = new RuleEvent("e1", "t1", "s1", "sub1", "EVT",
                Instant.now(), Map.of(), Map.of());
        return new EvalContext("t1", event, new Subject("sub1", SubjectType.USER, Map.of()),
                Map.of(metric, new MetricValue(value, "UNKNOWN", "PROVIDED")), Instant.parse("2026-06-01T00:00:00Z"));
    }

    private ConditionNode node(String metric, Object... values) {
        return new ConditionNode("NOT_IN", metric, "", Map.of("values", List.of(values)), 0.0);
    }

    @Test
    void valueNotInList_returnsTrue() {
        assertThat(evaluator.evaluate(node("status", "ACTIVE", "PENDING"), ctx("status", "BLOCKED"))).isTrue();
    }

    @Test
    void valueInList_returnsFalse() {
        assertThat(evaluator.evaluate(node("status", "ACTIVE", "PENDING"), ctx("status", "ACTIVE"))).isFalse();
    }

    @Test
    void metricMissing_returnsTrue() {
        RuleEvent event = new RuleEvent("e1", "t1", "s1", "sub1", "EVT",
                Instant.now(), Map.of(), Map.of());
        EvalContext emptyCtx = new EvalContext("t1", event,
                new Subject("sub1", SubjectType.USER, Map.of()), Map.of(), Instant.parse("2026-06-01T00:00:00Z"));
        assertThat(evaluator.evaluate(node("status", "ACTIVE"), emptyCtx)).isTrue();
    }

    @Test
    void notIn_stringDataType_zeroPrefix_notMatchPlain100_returnsTrue() {
        // STRING dataType："0100" 不在列表 ["100"] 中 -> true
        NotInEvaluator ev = new NotInEvaluator();
        ConditionNode n = new ConditionNode("NOT_IN", "code", null,
                Map.of("values", java.util.List.of("100")), 0.0, "STRING");
        EvalContext c = ctx("code", "0100");
        assertThat(ev.evaluate(n, c)).isTrue();
    }

    @Test
    void notIn_stringDataType_exactMatch_returnsFalse() {
        // STRING dataType："100" 在列表 ["100"] 中 -> false
        NotInEvaluator ev = new NotInEvaluator();
        ConditionNode n = new ConditionNode("NOT_IN", "code", null,
                Map.of("values", java.util.List.of("100")), 0.0, "STRING");
        EvalContext c = ctx("code", "100");
        assertThat(ev.evaluate(n, c)).isFalse();
    }
}
