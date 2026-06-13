package com.sstlfsj.rule.kernel.internal.condition;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import com.sstlfsj.rule.kernel.api.model.ConditionParams;
import com.sstlfsj.rule.kernel.api.model.ConditionTypes;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OccurredAtEvaluatorTest {

    private final OccurredAtEvaluator evaluator = new OccurredAtEvaluator();

    private EvalContext ctx(Instant occurredAt, Instant now) {
        RuleEvent ev = new RuleEvent("t1", "s1", "E", "u1", "e1", occurredAt, Map.of(), Map.of(), com.sstlfsj.rule.kernel.api.model.EventSource.HTTP);
        return new EvalContext("t1", ev, null, Map.of(), now);
    }

    private ConditionNode node(Map<String, Object> params) {
        return new ConditionNode("time.occurred_at", null, "", params, 0.0);
    }

    @Test
    void before_now_true() {
        Instant now = Instant.parse("2026-06-01T12:00:00Z");
        Instant occurred = Instant.parse("2026-06-01T10:00:00Z");
        ConditionNode n = node(Map.of("operator", "BEFORE", "value", "$now"));
        assertThat(evaluator.evaluate(n, ctx(occurred, now))).isTrue();
    }

    @Test
    void before_now_false_whenAfter() {
        Instant now = Instant.parse("2026-06-01T08:00:00Z");
        Instant occurred = Instant.parse("2026-06-01T10:00:00Z");
        ConditionNode n = node(Map.of("operator", "BEFORE", "value", "$now"));
        assertThat(evaluator.evaluate(n, ctx(occurred, now))).isFalse();
    }

    @Test
    void between_inclusiveBounds() {
        Instant occurred = Instant.parse("2026-06-01T10:00:00Z");
        ConditionNode n = node(Map.of("operator", "BETWEEN",
                "start", "2026-06-01T09:00:00Z", "end", "2026-06-01T11:00:00Z"));
        assertThat(evaluator.evaluate(n, ctx(occurred, Instant.EPOCH))).isTrue();
    }

    @Test
    void bareDate_withTimezone_parsed() {
        // occurred=2026-06-01T00:00:00+08:00 = 2026-05-31T16:00Z；AFTER 2026-05-30 → true
        Instant occurred = Instant.parse("2026-05-31T16:00:00Z");
        ConditionNode n = node(Map.of("operator", "AFTER",
                "value", "2026-05-30", "timezone", "Asia/Shanghai"));
        assertThat(evaluator.evaluate(n, ctx(occurred, Instant.EPOCH))).isTrue();
    }

    @Test
    void today_throwsForConditionEvalError() {
        ConditionNode n = node(Map.of("operator", "BEFORE", "value", "$today"));
        assertThatThrownBy(() -> evaluator.evaluate(n, ctx(Instant.EPOCH, Instant.EPOCH)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullOccurredAt_returnsFalse() {
        RuleEvent ev = new RuleEvent("t1", "s1", "E", "u1", "e1", null, Map.of(), Map.of(), com.sstlfsj.rule.kernel.api.model.EventSource.HTTP);
        EvalContext c = new EvalContext("t1", ev, null, Map.of(), Instant.EPOCH);
        ConditionNode n = node(Map.of("operator", "BEFORE", "value", "$now"));
        assertThat(evaluator.evaluate(n, c)).isFalse();
    }

    @Test
    void spec_describesOperator() {
        var spec = evaluator.spec().orElseThrow();
        assertThat(spec.code()).isEqualTo(ConditionTypes.TIME_OCCURRED_AT);
        assertThat(spec.requiredParamKeys()).isEqualTo(Set.of(ConditionParams.OPERATOR));
        assertThat(spec.requiresMetric()).isFalse();
    }
}
