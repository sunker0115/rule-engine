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

class InEvaluatorTest {

    private final InEvaluator evaluator = new InEvaluator();

    private EvalContext ctx(String metric, Object value) {
        RuleEvent event = new RuleEvent("e1", "t1", "s1", "sub1", "EVT",
                Instant.now(), Map.of(), Map.of());
        return new EvalContext("t1", event, new Subject("sub1", SubjectType.USER, Map.of()),
                Map.of(metric, new MetricValue(value, "UNKNOWN", "PROVIDED")));
    }

    private ConditionNode node(String metric, Object... values) {
        return new ConditionNode("IN", metric, "", Map.of("values", List.of(values)), 0.0);
    }

    @Test
    void valueInList_returnsTrue() {
        assertThat(evaluator.evaluate(node("status", "ACTIVE", "PENDING"), ctx("status", "ACTIVE"))).isTrue();
    }

    @Test
    void valueNotInList_returnsFalse() {
        assertThat(evaluator.evaluate(node("status", "ACTIVE", "PENDING"), ctx("status", "BLOCKED"))).isFalse();
    }

    @Test
    void metricMissing_returnsFalse() {
        RuleEvent event = new RuleEvent("e1", "t1", "s1", "sub1", "EVT",
                Instant.now(), Map.of(), Map.of());
        EvalContext emptyCtx = new EvalContext("t1", event,
                new Subject("sub1", SubjectType.USER, Map.of()), Map.of());
        assertThat(evaluator.evaluate(node("status", "ACTIVE"), emptyCtx)).isFalse();
    }

    @Test
    void in_stringDataType_zeroPrefix_notMatchPlain100() {
        // STRING dataType：列表 ["100"]，actual="0100" 不命中
        InEvaluator ev = new InEvaluator();
        ConditionNode n = new ConditionNode("IN", "code", null,
                Map.of("values", java.util.List.of("100")), 0.0, "STRING");
        EvalContext c = ctx("code", "0100");
        assertThat(ev.evaluate(n, c)).isFalse();
    }

    @Test
    void in_longDataType_numericMatch() {
        // LONG dataType：列表 [100, 200]，actual=100L 命中
        InEvaluator ev = new InEvaluator();
        ConditionNode n = new ConditionNode("IN", "score", null,
                Map.of("values", java.util.List.of(100, 200)), 0.0, "LONG");
        EvalContext c = ctx("score", 100L);
        assertThat(ev.evaluate(n, c)).isTrue();
    }
}
