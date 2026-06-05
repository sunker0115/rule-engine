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

class EqEvaluatorTest {

    private final EqEvaluator evaluator = new EqEvaluator();

    private EvalContext ctx(String metric, Object value) {
        RuleEvent event = new RuleEvent("e1", "t1", "s1", "sub1", "EVT",
                Instant.now(), Map.of(), Map.of());
        return new EvalContext("t1", event, new Subject("sub1", SubjectType.USER, Map.of()),
                Map.of(metric, new MetricValue(value, "UNKNOWN", "PROVIDED")), Instant.parse("2026-06-01T00:00:00Z"));
    }

    private ConditionNode node(String metric, Object threshold) {
        return new ConditionNode("EQ", metric, "", Map.of("threshold", threshold), 0.0);
    }

    @Test
    void numericEqual_returnsTrue() {
        assertThat(evaluator.evaluate(node("score", 100), ctx("score", 100))).isTrue();
    }

    @Test
    void numericNotEqual_returnsFalse() {
        assertThat(evaluator.evaluate(node("score", 100), ctx("score", 99))).isFalse();
    }

    @Test
    void stringEqual_returnsTrue() {
        assertThat(evaluator.evaluate(node("status", "ACTIVE"), ctx("status", "ACTIVE"))).isTrue();
    }

    @Test
    void stringNotEqual_returnsFalse() {
        assertThat(evaluator.evaluate(node("status", "ACTIVE"), ctx("status", "INACTIVE"))).isFalse();
    }

    @Test
    void metricMissing_returnsFalse() {
        RuleEvent event = new RuleEvent("e1", "t1", "s1", "sub1", "EVT",
                Instant.now(), Map.of(), Map.of());
        EvalContext emptyCtx = new EvalContext("t1", event,
                new Subject("sub1", SubjectType.USER, Map.of()), Map.of(), Instant.parse("2026-06-01T00:00:00Z"));
        assertThat(evaluator.evaluate(node("score", 100), emptyCtx)).isFalse();
    }

    @Test
    void eq_stringDataType_zeroPrefix_notEqualTo100() {
        // STRING dataType：字符串 "0100" 不等于 "100"
        ConditionNode n = new ConditionNode("EQ", "code", null,
                Map.of("threshold", "100"), 0.0, "STRING");
        assertThat(evaluator.evaluate(n, ctx("code", "0100"))).isFalse();
    }

    @Test
    void eq_longDataType_bigInteger_notEqualWhenDifferent() {
        // LONG dataType：大整数精确比较，不走 double
        long a = 9007199254740993L;
        long b = 9007199254740994L;
        ConditionNode n = new ConditionNode("EQ", "id", null,
                Map.of("threshold", b), 0.0, "LONG");
        assertThat(evaluator.evaluate(n, ctx("id", a))).isFalse();
    }

    @Test
    void eq_booleanDataType_trueEqualsStringTrue() {
        // BOOLEAN dataType：true 等于字符串 "true"
        ConditionNode n = new ConditionNode("EQ", "flag", null,
                Map.of("threshold", "true"), 0.0, "BOOLEAN");
        assertThat(evaluator.evaluate(n, ctx("flag", true))).isTrue();
    }

    @Test
    void eq_dateType_sameDate_returnsTrue() {
        ConditionNode node = new ConditionNode("EQ", "d", "",
                Map.of("threshold", "2026-06-01"), 0.0, "DATE");
        RuleEvent ev = new RuleEvent("t1", "s1", "E", "u1", "e1",
                Instant.parse("2026-06-01T00:00:00Z"), Map.of(), Map.of());
        EvalContext ctx = new EvalContext("t1", ev, null,
                Map.of("d", new MetricValue("2026-06-01", "DATE", "PROVIDED")),
                Instant.parse("2026-06-01T00:00:00Z"));
        assertThat(new EqEvaluator().evaluate(node, ctx)).isTrue();
    }

    @Test
    void eq_datetimeType_now_matchesCtxNow() {
        Instant now = Instant.parse("2026-06-01T10:00:00Z");
        ConditionNode node = new ConditionNode("EQ", "d", "",
                Map.of("threshold", "$now"), 0.0, "DATETIME");
        RuleEvent ev = new RuleEvent("t1", "s1", "E", "u1", "e1", now, Map.of(), Map.of());
        EvalContext ctx = new EvalContext("t1", ev, null,
                Map.of("d", new MetricValue(now, "DATETIME", "PROVIDED")), now);
        assertThat(new EqEvaluator().evaluate(node, ctx)).isTrue();
    }
}
