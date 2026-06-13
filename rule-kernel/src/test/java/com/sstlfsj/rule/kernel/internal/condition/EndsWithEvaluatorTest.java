package com.sstlfsj.rule.kernel.internal.condition;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.MetricValue;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import com.sstlfsj.rule.kernel.api.model.Subject;
import com.sstlfsj.rule.kernel.api.model.SubjectType;
import com.sstlfsj.rule.kernel.api.model.ConditionParams;
import com.sstlfsj.rule.kernel.api.model.ConditionTypes;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class EndsWithEvaluatorTest {

    private final EndsWithEvaluator evaluator = new EndsWithEvaluator();

    private EvalContext ctx(String metric, Object value) {
        RuleEvent event = new RuleEvent("e1", "t1", "s1", "sub1", "EVT",
                Instant.now(), Map.of(), Map.of(), com.sstlfsj.rule.kernel.api.model.EventSource.HTTP);
        return new EvalContext("t1", event, new Subject("sub1", SubjectType.USER, Map.of()),
                Map.of(metric, new MetricValue(value, "UNKNOWN", "PROVIDED")), Instant.parse("2026-06-01T00:00:00Z"));
    }

    private ConditionNode node(String metric, String suffix) {
        return new ConditionNode("ENDS_WITH", metric, "", Map.of("suffix", suffix), 0.0);
    }

    @Test
    void stringEndsWith_returnsTrue() {
        assertThat(evaluator.evaluate(node("email", ".com"), ctx("email", "user@example.com"))).isTrue();
    }

    @Test
    void stringNotEndsWith_returnsFalse() {
        assertThat(evaluator.evaluate(node("email", ".org"), ctx("email", "user@example.com"))).isFalse();
    }

    @Test
    void metricMissing_returnsFalse() {
        RuleEvent event = new RuleEvent("e1", "t1", "s1", "sub1", "EVT",
                Instant.now(), Map.of(), Map.of(), com.sstlfsj.rule.kernel.api.model.EventSource.HTTP);
        EvalContext emptyCtx = new EvalContext("t1", event,
                new Subject("sub1", SubjectType.USER, Map.of()), Map.of(), Instant.parse("2026-06-01T00:00:00Z"));
        assertThat(evaluator.evaluate(node("email", ".com"), emptyCtx)).isFalse();
    }

    @Test
    void spec_describesOperator() {
        var spec = evaluator.spec().orElseThrow();
        assertThat(spec.code()).isEqualTo(ConditionTypes.ENDS_WITH);
        assertThat(spec.requiredParamKeys()).isEqualTo(Set.of(ConditionParams.SUFFIX));
        assertThat(spec.requiresMetric()).isTrue();
    }
}
