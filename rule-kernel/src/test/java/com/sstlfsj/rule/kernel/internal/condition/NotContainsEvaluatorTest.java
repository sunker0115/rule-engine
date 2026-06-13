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
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class NotContainsEvaluatorTest {

    private final NotContainsEvaluator evaluator = new NotContainsEvaluator();

    private EvalContext ctx(String metric, Object value) {
        RuleEvent event = new RuleEvent("e1", "t1", "s1", "sub1", "EVT",
                Instant.now(), Map.of(), Map.of(), com.sstlfsj.rule.kernel.api.model.EventSource.HTTP);
        return new EvalContext("t1", event, new Subject("sub1", SubjectType.USER, Map.of()),
                Map.of(metric, new MetricValue(value, "UNKNOWN", "PROVIDED")), Instant.parse("2026-06-01T00:00:00Z"));
    }

    private ConditionNode node(String metric, Object element) {
        return new ConditionNode("NOT_CONTAINS", metric, "", Map.of("element", element), 0.0);
    }

    @Test
    void listDoesNotContain_returnsTrue() {
        assertThat(evaluator.evaluate(node("tags", "banned"), ctx("tags", List.of("vip", "gold")))).isTrue();
    }

    @Test
    void listContains_returnsFalse() {
        assertThat(evaluator.evaluate(node("tags", "vip"), ctx("tags", List.of("vip", "gold")))).isFalse();
    }

    @Test
    void metricMissing_returnsTrue() {
        RuleEvent event = new RuleEvent("e1", "t1", "s1", "sub1", "EVT",
                Instant.now(), Map.of(), Map.of(), com.sstlfsj.rule.kernel.api.model.EventSource.HTTP);
        EvalContext emptyCtx = new EvalContext("t1", event,
                new Subject("sub1", SubjectType.USER, Map.of()), Map.of(), Instant.parse("2026-06-01T00:00:00Z"));
        assertThat(evaluator.evaluate(node("tags", "banned"), emptyCtx)).isTrue();
    }

    @Test
    void metricNotCollection_returnsTrue() {
        assertThat(evaluator.evaluate(node("score", "banned"), ctx("score", 100))).isTrue();
    }

    @Test
    void annotation_describesOperator() {
        var ann = NotContainsEvaluator.class.getAnnotation(
                com.sstlfsj.rule.kernel.api.annotation.ConditionType.class);
        assertThat(ann).isNotNull();
        assertThat(ann.value()).isEqualTo(ConditionTypes.NOT_CONTAINS);
        assertThat(ann.schema().requiredParamKeys).isEqualTo(Set.of(ConditionParams.ELEMENT));
        assertThat(ann.schema().requiresMetric).isTrue();
    }
}
