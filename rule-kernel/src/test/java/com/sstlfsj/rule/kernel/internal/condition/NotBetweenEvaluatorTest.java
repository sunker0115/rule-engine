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

class NotBetweenEvaluatorTest {

    private final NotBetweenEvaluator evaluator = new NotBetweenEvaluator();

    private EvalContext ctx(String metric, Object value) {
        RuleEvent event = new RuleEvent("e1", "t1", "s1", "sub1", "EVT",
                Instant.now(), Map.of(), Map.of());
        return new EvalContext("t1", event, new Subject("sub1", SubjectType.USER, Map.of()),
                Map.of(metric, new MetricValue(value, "UNKNOWN", "PROVIDED")));
    }

    private ConditionNode node(String metric, Object min, Object max) {
        return new ConditionNode("NOT_BETWEEN", metric, "", Map.of("min", min, "max", max), 0.0);
    }

    @Test
    void valueBelowRange_returnsTrue() {
        assertThat(evaluator.evaluate(node("score", 10, 100), ctx("score", 5))).isTrue();
    }

    @Test
    void valueAboveRange_returnsTrue() {
        assertThat(evaluator.evaluate(node("score", 10, 100), ctx("score", 200))).isTrue();
    }

    @Test
    void valueWithinRange_returnsFalse() {
        assertThat(evaluator.evaluate(node("score", 10, 100), ctx("score", 50))).isFalse();
    }

    @Test
    void valueAtBoundary_returnsFalse() {
        assertThat(evaluator.evaluate(node("score", 10, 100), ctx("score", 10))).isFalse();
    }
}
