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

class GtEvaluatorTest {

    private final GtEvaluator evaluator = new GtEvaluator();

    private EvalContext ctx(String metric, Object value) {
        RuleEvent event = new RuleEvent("e1", "t1", "s1", "sub1", "EVT",
                Instant.now(), Map.of(), Map.of(), com.sstlfsj.rule.kernel.api.model.EventSource.HTTP);
        return new EvalContext("t1", event, new Subject("sub1", SubjectType.USER, Map.of()),
                Map.of(metric, new MetricValue(value, "UNKNOWN", "PROVIDED")), Instant.parse("2026-06-01T00:00:00Z"));
    }

    private ConditionNode node(String metric, Object threshold) {
        return new ConditionNode("GT", metric, "", Map.of("threshold", threshold), 0.0);
    }

    @Test
    void actualGreaterThan_returnsTrue() {
        assertThat(evaluator.evaluate(node("score", 80), ctx("score", 100))).isTrue();
    }

    @Test
    void actualEqual_returnsFalse() {
        assertThat(evaluator.evaluate(node("score", 80), ctx("score", 80))).isFalse();
    }

    @Test
    void actualLess_returnsFalse() {
        assertThat(evaluator.evaluate(node("score", 80), ctx("score", 50))).isFalse();
    }

    @Test
    void gt_withDataTypeLong_usesBigDecimalPrecision() {
        // dataType=LONG 时走 Numeric 策略（BigDecimal），大整数精度不丢失
        GtEvaluator ev = new GtEvaluator();
        long bigVal = 9007199254740994L;
        long bigThreshold = 9007199254740993L;
        ConditionNode node = new ConditionNode("GT", "id", null,
                Map.of("threshold", bigThreshold), 0.0, "LONG");
        EvalContext ctx = ctx("id", bigVal);
        assertThat(ev.evaluate(node, ctx)).isTrue();
    }

    @Test
    void annotation_describesOperator() {
        var ann = GtEvaluator.class.getAnnotation(
                com.sstlfsj.rule.kernel.api.annotation.ConditionType.class);
        assertThat(ann).isNotNull();
        assertThat(ann.value()).isEqualTo(ConditionTypes.GT);
        assertThat(ann.schema().requiredParamKeys).isEqualTo(Set.of(ConditionParams.THRESHOLD));
        assertThat(ann.schema().requiresMetric).isTrue();
    }

    @Test
    void gt_withDataTypeNull_fallsBackToDefault() {
        // dataType=null 走 Default，Number 实际值按数值路径，100 > 50 => true
        GtEvaluator ev = new GtEvaluator();
        ConditionNode node = new ConditionNode("GT", "score", null,
                Map.of("threshold", 50), 0.0, null);
        EvalContext ctx = ctx("score", 100);
        assertThat(ev.evaluate(node, ctx)).isTrue();
    }
}
