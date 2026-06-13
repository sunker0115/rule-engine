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
import static org.assertj.core.api.Assertions.assertThatCode;

class NotBetweenEvaluatorTest {

    private final NotBetweenEvaluator evaluator = new NotBetweenEvaluator();

    private EvalContext ctx(String metric, Object value) {
        RuleEvent event = new RuleEvent("e1", "t1", "s1", "sub1", "EVT",
                Instant.now(), Map.of(), Map.of(), com.sstlfsj.rule.kernel.api.model.EventSource.HTTP);
        return new EvalContext("t1", event, new Subject("sub1", SubjectType.USER, Map.of()),
                Map.of(metric, new MetricValue(value, "UNKNOWN", "PROVIDED")), Instant.parse("2026-06-01T00:00:00Z"));
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

    @Test
    void withDataTypeLong_aboveMax_returnsTrue() {
        // dataType=LONG 走 Numeric 策略，actual > max => NOT_BETWEEN 返回 true
        ConditionNode node = new ConditionNode("NOT_BETWEEN", "amount", null,
                Map.of("min", 10, "max", 100), 0.0, "LONG");
        assertThat(evaluator.evaluate(node, ctx("amount", 200))).isTrue();
    }

    @Test
    void dataTypeNull_booleanActual_doesNotThrow_returnsFalse() {
        // dataType=null + 指标值为 Boolean -> DefaultStrategy 路由到 BooleanStrategy.compare，
        // 后者抛 UnsupportedOperationException，evaluate 必须捕获并返回 false，不得穿透
        ConditionNode node = new ConditionNode("NOT_BETWEEN", "flag", null,
                Map.of("min", 0, "max", 1), 0.0, null);
        assertThatCode(() -> assertThat(evaluator.evaluate(node, ctx("flag", true))).isFalse())
                .doesNotThrowAnyException();
    }

    @Test
    void notBetween_dateType_outOfRange_returnsTrue() {
        ConditionNode node = new ConditionNode("NOT_BETWEEN", "d", "",
                Map.of("min", "2026-01-01", "max", "2026-06-30"), 0.0, "DATE");
        RuleEvent ev = new RuleEvent("t1", "s1", "E", "u1", "e1",
                Instant.parse("2026-12-01T00:00:00Z"), Map.of(), Map.of(), com.sstlfsj.rule.kernel.api.model.EventSource.HTTP);
        EvalContext ctx = new EvalContext("t1", ev, null,
                Map.of("d", new MetricValue("2026-12-01", "DATE", "PROVIDED")),
                Instant.parse("2026-12-01T00:00:00Z"));
        assertThat(new NotBetweenEvaluator().evaluate(node, ctx)).isTrue();
    }

    @Test
    void notBetween_dateType_inRange_returnsFalse() {
        ConditionNode node = new ConditionNode("NOT_BETWEEN", "d", "",
                Map.of("min", "2026-01-01", "max", "2026-06-30"), 0.0, "DATE");
        RuleEvent ev = new RuleEvent("t1", "s1", "E", "u1", "e1",
                Instant.parse("2026-03-01T00:00:00Z"), Map.of(), Map.of(), com.sstlfsj.rule.kernel.api.model.EventSource.HTTP);
        EvalContext ctx = new EvalContext("t1", ev, null,
                Map.of("d", new MetricValue("2026-03-01", "DATE", "PROVIDED")),
                Instant.parse("2026-03-01T00:00:00Z"));
        assertThat(new NotBetweenEvaluator().evaluate(node, ctx)).isFalse();
    }

    @Test
    void spec_describesOperator() {
        var spec = evaluator.spec().orElseThrow();
        assertThat(spec.code()).isEqualTo(ConditionTypes.NOT_BETWEEN);
        assertThat(spec.requiredParamKeys()).isEqualTo(Set.of(ConditionParams.MIN, ConditionParams.MAX));
        assertThat(spec.requiresMetric()).isTrue();
    }
}
