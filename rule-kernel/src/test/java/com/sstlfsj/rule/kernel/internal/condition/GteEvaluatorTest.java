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

class GteEvaluatorTest {

    private final GteEvaluator evaluator = new GteEvaluator();

    private EvalContext ctx(String metric, Object value) {
        RuleEvent event = new RuleEvent("e1", "t1", "s1", "sub1", "EVT",
                Instant.now(), Map.of(), Map.of(), com.sstlfsj.rule.kernel.api.model.EventSource.HTTP);
        return new EvalContext("t1", event, new Subject("sub1", SubjectType.USER, Map.of()),
                Map.of(metric, new MetricValue(value, "UNKNOWN", "PROVIDED")), Instant.parse("2026-06-01T00:00:00Z"));
    }

    private ConditionNode node(String metric, Object threshold) {
        return new ConditionNode("GTE", metric, "", Map.of("threshold", threshold), 0.0);
    }

    @Test
    void actualGreater_returnsTrue() {
        assertThat(evaluator.evaluate(node("score", 80), ctx("score", 100))).isTrue();
    }

    @Test
    void actualEqual_returnsTrue() {
        assertThat(evaluator.evaluate(node("score", 80), ctx("score", 80))).isTrue();
    }

    @Test
    void actualLess_returnsFalse() {
        assertThat(evaluator.evaluate(node("score", 80), ctx("score", 50))).isFalse();
    }

    @Test
    void gte_withDataTypeLong_equalValues_returnsTrue() {
        // dataType=LONG 走 Numeric 策略，equal 时 GTE 应返回 true
        ConditionNode node = new ConditionNode("GTE", "amount", null,
                Map.of("threshold", 9007199254740993L), 0.0, "LONG");
        assertThat(evaluator.evaluate(node, ctx("amount", 9007199254740993L))).isTrue();
    }

    @Test
    void annotation_describesOperator() {
        var ann = GteEvaluator.class.getAnnotation(
                com.sstlfsj.rule.kernel.api.annotation.ConditionType.class);
        assertThat(ann).isNotNull();
        assertThat(ann.value()).isEqualTo(ConditionTypes.GTE);
        assertThat(ann.schema().requiredParamKeys).isEqualTo(Set.of(ConditionParams.THRESHOLD));
        assertThat(ann.schema().requiresMetric).isTrue();
    }
}
