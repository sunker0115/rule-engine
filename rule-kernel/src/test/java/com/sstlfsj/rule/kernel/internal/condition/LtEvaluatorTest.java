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

class LtEvaluatorTest {

    private final LtEvaluator evaluator = new LtEvaluator();

    private EvalContext ctx(String metric, Object value) {
        RuleEvent event = new RuleEvent("e1", "t1", "s1", "sub1", "EVT",
                Instant.now(), Map.of(), Map.of());
        return new EvalContext("t1", event, new Subject("sub1", SubjectType.USER, Map.of()),
                Map.of(metric, new MetricValue(value, "UNKNOWN", "PROVIDED")));
    }

    private ConditionNode node(String metric, Object threshold) {
        return new ConditionNode("LT", metric, "", Map.of("threshold", threshold), 0.0);
    }

    @Test
    void actualLessThan_returnsTrue() {
        assertThat(evaluator.evaluate(node("score", 80), ctx("score", 50))).isTrue();
    }

    @Test
    void actualEqual_returnsFalse() {
        assertThat(evaluator.evaluate(node("score", 80), ctx("score", 80))).isFalse();
    }

    @Test
    void actualGreater_returnsFalse() {
        assertThat(evaluator.evaluate(node("score", 80), ctx("score", 100))).isFalse();
    }
}
