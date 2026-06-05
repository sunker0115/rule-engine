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

/**
 * 通过 GtEvaluator（accept(cmp>0)）覆盖 AbstractNumericEvaluator 的公共路径。
 */
class AbstractNumericEvaluatorTest {

    private final GtEvaluator ev = new GtEvaluator();

    private EvalContext ctx(String metric, Object value) {
        RuleEvent event = new RuleEvent("e1", "t1", "s1", "sub1", "EVT",
                Instant.now(), Map.of(), Map.of());
        return new EvalContext("t1", event, new Subject("sub1", SubjectType.USER, Map.of()),
                Map.of(metric, new MetricValue(value, "UNKNOWN", "PROVIDED")));
    }

    @Test
    void metricMissing_returnsFalse() {
        // ctx 里没有 "score"，getMetric 返回 null -> false
        ConditionNode node = new ConditionNode("GT", "score", null,
                Map.of("threshold", 50), 0.0, null);
        EvalContext empty = new EvalContext("t1",
                new RuleEvent("e1", "t1", "s1", "sub1", "EVT", Instant.now(), Map.of(), Map.of()),
                new Subject("sub1", SubjectType.USER, Map.of()),
                Map.of());
        assertThat(ev.evaluate(node, empty)).isFalse();
    }

    @Test
    void thresholdMissing_returnsFalse() {
        // params 里没有 "threshold" -> false
        ConditionNode node = new ConditionNode("GT", "score", null,
                Map.of(), 0.0, null);
        assertThat(ev.evaluate(node, ctx("score", 100))).isFalse();
    }

    @Test
    void sentinelMaxValue_returnsFalse() {
        // Double.NaN 无法转 BigDecimal -> compare 返回 Integer.MAX_VALUE -> false
        ConditionNode node = new ConditionNode("GT", "score", null,
                Map.of("threshold", 50), 0.0, "LONG");
        assertThat(ev.evaluate(node, ctx("score", Double.NaN))).isFalse();
    }

    @Test
    void dataTypeNull_defaultStrategy_numberPath() {
        // dataType=null 走 Default，Number actual 走数值路径，100 > 50 => true
        ConditionNode node = new ConditionNode("GT", "score", null,
                Map.of("threshold", 50), 0.0, null);
        assertThat(ev.evaluate(node, ctx("score", 100))).isTrue();
    }

    @Test
    void dataTypeLong_numericStrategy_bigDecimalPrecision() {
        // dataType=LONG 走 Numeric 策略（BigDecimal），大整数精度不丢失
        long bigVal = 9007199254740994L;
        long bigThreshold = 9007199254740993L;
        ConditionNode node = new ConditionNode("GT", "id", null,
                Map.of("threshold", bigThreshold), 0.0, "LONG");
        assertThat(ev.evaluate(node, ctx("id", bigVal))).isTrue();
    }

    @Test
    void dataTypeNull_booleanActual_doesNotThrow_returnsFalse() {
        // dataType=null + 指标值为 Boolean -> DefaultStrategy 路由到 BooleanStrategy.compare，
        // 后者抛 UnsupportedOperationException，evaluate 必须捕获并返回 false，不得穿透
        ConditionNode node = new ConditionNode("GT", "flag", null,
                Map.of("threshold", 50), 0.0, null);
        assertThatCode(() -> assertThat(ev.evaluate(node, ctx("flag", true))).isFalse())
                .doesNotThrowAnyException();
    }
}
