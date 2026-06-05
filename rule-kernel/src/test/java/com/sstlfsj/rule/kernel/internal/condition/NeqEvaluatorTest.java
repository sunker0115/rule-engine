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

class NeqEvaluatorTest {

    private final NeqEvaluator evaluator = new NeqEvaluator();

    private EvalContext ctx(String metric, Object value) {
        RuleEvent event = new RuleEvent("e1", "t1", "s1", "sub1", "EVT",
                Instant.now(), Map.of(), Map.of());
        return new EvalContext("t1", event, new Subject("sub1", SubjectType.USER, Map.of()),
                Map.of(metric, new MetricValue(value, "UNKNOWN", "PROVIDED")), Instant.parse("2026-06-01T00:00:00Z"));
    }

    private ConditionNode node(String metric, Object threshold) {
        return new ConditionNode("NEQ", metric, "", Map.of("threshold", threshold), 0.0);
    }

    @Test
    void numericNotEqual_returnsTrue() {
        assertThat(evaluator.evaluate(node("score", 100), ctx("score", 99))).isTrue();
    }

    @Test
    void numericEqual_returnsFalse() {
        assertThat(evaluator.evaluate(node("score", 100), ctx("score", 100))).isFalse();
    }

    @Test
    void stringNotEqual_returnsTrue() {
        assertThat(evaluator.evaluate(node("status", "ACTIVE"), ctx("status", "BLOCKED"))).isTrue();
    }

    @Test
    void stringEqual_returnsFalse() {
        assertThat(evaluator.evaluate(node("status", "ACTIVE"), ctx("status", "ACTIVE"))).isFalse();
    }

    @Test
    void neq_stringDataType_zeroPrefix_returnsTrue() {
        // STRING dataType："0100" 不等于 "100" => NEQ 返回 true
        ConditionNode n = new ConditionNode("NEQ", "code", null,
                Map.of("threshold", "100"), 0.0, "STRING");
        assertThat(evaluator.evaluate(n, ctx("code", "0100"))).isTrue();
    }

    @Test
    void neq_longDataType_sameValue_returnsFalse() {
        // LONG dataType：相同大整数 => NEQ 返回 false
        long v = 9007199254740993L;
        ConditionNode n = new ConditionNode("NEQ", "id", null,
                Map.of("threshold", v), 0.0, "LONG");
        assertThat(evaluator.evaluate(n, ctx("id", v))).isFalse();
    }

    @Test
    void neq_dateType_differentDate_returnsTrue() {
        ConditionNode node = new ConditionNode("NEQ", "d", "",
                Map.of("threshold", "2026-06-01"), 0.0, "DATE");
        RuleEvent ev = new RuleEvent("t1", "s1", "E", "u1", "e1",
                Instant.parse("2026-06-01T00:00:00Z"), Map.of(), Map.of());
        EvalContext ctx = new EvalContext("t1", ev, null,
                Map.of("d", new MetricValue("2026-01-01", "DATE", "PROVIDED")),
                Instant.parse("2026-06-01T00:00:00Z"));
        assertThat(new NeqEvaluator().evaluate(node, ctx)).isTrue();
    }

    @Test
    void neq_dateType_sameDate_returnsFalse() {
        ConditionNode node = new ConditionNode("NEQ", "d", "",
                Map.of("threshold", "2026-06-01"), 0.0, "DATE");
        RuleEvent ev = new RuleEvent("t1", "s1", "E", "u1", "e1",
                Instant.parse("2026-06-01T00:00:00Z"), Map.of(), Map.of());
        EvalContext ctx = new EvalContext("t1", ev, null,
                Map.of("d", new MetricValue("2026-06-01", "DATE", "PROVIDED")),
                Instant.parse("2026-06-01T00:00:00Z"));
        assertThat(new NeqEvaluator().evaluate(node, ctx)).isFalse();
    }

    @Test
    void neq_dateType_unparseable_actual_returnsTrue() {
        ConditionNode node = new ConditionNode("NEQ", "d", "",
                Map.of("threshold", "2026-06-01"), 0.0, "DATE");
        RuleEvent ev = new RuleEvent("t1", "s1", "E", "u1", "e1",
                Instant.parse("2026-06-01T00:00:00Z"), Map.of(), Map.of());
        EvalContext ctx = new EvalContext("t1", ev, null,
                Map.of("d", new MetricValue("not-a-date", "DATE", "PROVIDED")),
                Instant.parse("2026-06-01T00:00:00Z"));
        assertThat(new NeqEvaluator().evaluate(node, ctx)).isTrue();
    }
}
