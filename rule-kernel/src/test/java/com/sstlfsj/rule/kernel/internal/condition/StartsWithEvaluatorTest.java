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

class StartsWithEvaluatorTest {

    private final StartsWithEvaluator evaluator = new StartsWithEvaluator();

    private EvalContext ctx(String metric, Object value) {
        RuleEvent event = new RuleEvent("e1", "t1", "s1", "sub1", "EVT",
                Instant.now(), Map.of(), Map.of());
        return new EvalContext("t1", event, new Subject("sub1", SubjectType.USER, Map.of()),
                Map.of(metric, new MetricValue(value, "UNKNOWN", "PROVIDED")));
    }

    private ConditionNode node(String metric, String prefix) {
        return new ConditionNode("STARTS_WITH", metric, "", Map.of("prefix", prefix), 0.0);
    }

    @Test
    void stringStartsWith_returnsTrue() {
        assertThat(evaluator.evaluate(node("code", "VIP"), ctx("code", "VIP_001"))).isTrue();
    }

    @Test
    void stringNotStartsWith_returnsFalse() {
        assertThat(evaluator.evaluate(node("code", "VIP"), ctx("code", "REGULAR_001"))).isFalse();
    }

    @Test
    void metricMissing_returnsFalse() {
        RuleEvent event = new RuleEvent("e1", "t1", "s1", "sub1", "EVT",
                Instant.now(), Map.of(), Map.of());
        EvalContext emptyCtx = new EvalContext("t1", event,
                new Subject("sub1", SubjectType.USER, Map.of()), Map.of());
        assertThat(evaluator.evaluate(node("code", "VIP"), emptyCtx)).isFalse();
    }
}
