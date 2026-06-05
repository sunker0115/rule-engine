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
import static org.assertj.core.api.Assertions.assertThatCode;

class BetweenEvaluatorTest {

    private final BetweenEvaluator evaluator = new BetweenEvaluator();

    private EvalContext ctx(String metric, Object value) {
        RuleEvent event = new RuleEvent("e1", "t1", "s1", "sub1", "EVT",
                Instant.now(), Map.of(), Map.of());
        Subject subject = new Subject("sub1", SubjectType.USER, Map.of());
        EvalContext ctx = new EvalContext("t1", event, subject,
                Map.of(metric, new MetricValue(value, "UNKNOWN", "PROVIDED")));
        return ctx;
    }

    private ConditionNode node(String metric, Object min, Object max) {
        return new ConditionNode("BETWEEN", metric, "", Map.of("min", min, "max", max), 0.0);
    }

    @Test
    void value_withinRange_returnsTrue() {
        assertThat(evaluator.evaluate(node("score", 10, 100), ctx("score", 50))).isTrue();
    }

    @Test
    void value_atMin_returnsTrue() {
        assertThat(evaluator.evaluate(node("score", 10, 100), ctx("score", 10))).isTrue();
    }

    @Test
    void value_atMax_returnsTrue() {
        assertThat(evaluator.evaluate(node("score", 10, 100), ctx("score", 100))).isTrue();
    }

    @Test
    void value_belowMin_returnsFalse() {
        assertThat(evaluator.evaluate(node("score", 10, 100), ctx("score", 5))).isFalse();
    }

    @Test
    void value_aboveMax_returnsFalse() {
        assertThat(evaluator.evaluate(node("score", 10, 100), ctx("score", 200))).isFalse();
    }

    @Test
    void metricMissing_returnsFalse() {
        RuleEvent event = new RuleEvent("e1", "t1", "s1", "sub1", "EVT",
                Instant.now(), Map.of(), Map.of());
        Subject subject = new Subject("sub1", SubjectType.USER, Map.of());
        EvalContext emptyCtx = new EvalContext("t1", event, subject, Map.of());
        assertThat(evaluator.evaluate(node("score", 10, 100), emptyCtx)).isFalse();
    }

    @Test
    void withDataTypeLong_bigDecimalPrecision_atBoundary_returnsTrue() {
        // dataType=LONG 走 Numeric 策略，50000.00 == 50000（scale 不同，BigDecimal.compareTo 视为相等）
        ConditionNode node = new ConditionNode("BETWEEN", "amount", null,
                Map.of("min", new java.math.BigDecimal("50000.00"), "max", 60000), 0.0, "LONG");
        assertThat(evaluator.evaluate(node, ctx("amount", 50000))).isTrue();
    }

    @Test
    void dataTypeNull_booleanActual_doesNotThrow_returnsFalse() {
        // dataType=null + 指标值为 Boolean -> DefaultStrategy 路由到 BooleanStrategy.compare，
        // 后者抛 UnsupportedOperationException，evaluate 必须捕获并返回 false，不得穿透
        ConditionNode node = new ConditionNode("BETWEEN", "flag", null,
                Map.of("min", 0, "max", 1), 0.0, null);
        assertThatCode(() -> assertThat(evaluator.evaluate(node, ctx("flag", true))).isFalse())
                .doesNotThrowAnyException();
    }
}
