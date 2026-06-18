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

class StartsWithEvaluatorTest {

    private final StartsWithEvaluator evaluator = new StartsWithEvaluator();

    private EvalContext ctx(String metric, Object value) {
        RuleEvent event = new RuleEvent("e1", "t1", "s1", "sub1", "EVT",
                Instant.now(), Map.of(), Map.of(), com.sstlfsj.rule.kernel.api.model.EventSource.HTTP);
        return new EvalContext("t1", event, new Subject("sub1", SubjectType.USER, Map.of()),
                Map.of(metric, new MetricValue(value, "UNKNOWN", "PROVIDED")), Instant.parse("2026-06-01T00:00:00Z"));
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
    void nullMetricValue_returnsFalse() {
        // metric value=null："null".startsWith("nul")=true 属误命中，修后直接 false
        java.util.HashMap<String, com.sstlfsj.rule.kernel.api.model.MetricValue> metrics = new java.util.HashMap<>();
        metrics.put("code", new com.sstlfsj.rule.kernel.api.model.MetricValue(null, "UNKNOWN", "PROVIDED"));
        RuleEvent event = new RuleEvent("e1", "t1", "s1", "sub1", "EVT",
                Instant.now(), Map.of(), Map.of(), com.sstlfsj.rule.kernel.api.model.EventSource.HTTP);
        EvalContext ctx = new EvalContext("t1", event, new Subject("sub1", SubjectType.USER, Map.of()),
                metrics, Instant.parse("2026-06-01T00:00:00Z"));
        assertThat(evaluator.evaluate(node("code", "nul"), ctx)).isFalse();
    }

    @Test
    void metricMissing_returnsFalse() {
        RuleEvent event = new RuleEvent("e1", "t1", "s1", "sub1", "EVT",
                Instant.now(), Map.of(), Map.of(), com.sstlfsj.rule.kernel.api.model.EventSource.HTTP);
        EvalContext emptyCtx = new EvalContext("t1", event,
                new Subject("sub1", SubjectType.USER, Map.of()), Map.of(), Instant.parse("2026-06-01T00:00:00Z"));
        assertThat(evaluator.evaluate(node("code", "VIP"), emptyCtx)).isFalse();
    }

    @Test
    void annotation_describesOperator() {
        var ann = StartsWithEvaluator.class.getAnnotation(
                com.sstlfsj.rule.kernel.api.annotation.ConditionType.class);
        assertThat(ann).isNotNull();
        assertThat(ann.value()).isEqualTo(ConditionTypes.STARTS_WITH);
        assertThat(ann.schema().requiredParamKeys).isEqualTo(Set.of(ConditionParams.PREFIX));
        assertThat(ann.schema().requiresMetric).isTrue();
    }
}
