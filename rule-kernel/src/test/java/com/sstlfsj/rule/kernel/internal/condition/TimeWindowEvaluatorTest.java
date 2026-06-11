package com.sstlfsj.rule.kernel.internal.condition;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TimeWindowEvaluatorTest {

    private final TimeWindowEvaluator evaluator = new TimeWindowEvaluator();

    private EvalContext ctxAt(String isoOffset) {
        Instant now = OffsetDateTime.parse(isoOffset).toInstant();
        RuleEvent ev = new RuleEvent("t1", "s1", "E", "u1", "e1", now, Map.of(), Map.of(), com.sstlfsj.rule.kernel.api.model.EventSource.HTTP);
        return new EvalContext("t1", ev, null, Map.of(), now);
    }

    private ConditionNode node(Map<String, Object> params) {
        return new ConditionNode("time.window", null, "", params, 0.0);
    }

    @Test
    void within_inclusiveBounds_shanghai() {
        Map<String, Object> p = Map.of("start", "09:00", "end", "22:00", "timezone", "Asia/Shanghai");
        assertThat(evaluator.evaluate(node(p), ctxAt("2026-06-01T09:00:00+08:00"))).isTrue();
        assertThat(evaluator.evaluate(node(p), ctxAt("2026-06-01T08:59:59+08:00"))).isFalse();
        assertThat(evaluator.evaluate(node(p), ctxAt("2026-06-01T22:00:00+08:00"))).isTrue();
        assertThat(evaluator.evaluate(node(p), ctxAt("2026-06-01T22:00:01+08:00"))).isFalse();
    }

    @Test
    void crossMidnight_window() {
        Map<String, Object> p = Map.of("start", "22:00", "end", "06:00", "timezone", "Asia/Shanghai");
        assertThat(evaluator.evaluate(node(p), ctxAt("2026-06-01T23:00:00+08:00"))).isTrue();
        assertThat(evaluator.evaluate(node(p), ctxAt("2026-06-01T01:00:00+08:00"))).isTrue();
        assertThat(evaluator.evaluate(node(p), ctxAt("2026-06-01T07:00:00+08:00"))).isFalse();
    }

    @Test
    void daysOfWeek_excludesSaturday() {
        // 2026-06-06 是周六
        Map<String, Object> p = Map.of("start", "00:00", "end", "23:59",
                "timezone", "Asia/Shanghai", "daysOfWeek", List.of("MON", "FRI"));
        assertThat(evaluator.evaluate(node(p), ctxAt("2026-06-06T10:00:00+08:00"))).isFalse();
    }

    @Test
    void datesExclude_holidayAlwaysFalse() {
        Map<String, Object> p = Map.of("start", "00:00", "end", "23:59",
                "timezone", "Asia/Shanghai", "datesExclude", List.of("10-01"));
        assertThat(evaluator.evaluate(node(p), ctxAt("2026-10-01T10:00:00+08:00"))).isFalse();
    }

    @Test
    void timezone_defaultsToUtc() {
        Map<String, Object> p = Map.of("start", "09:00", "end", "17:00");
        // 10:00Z 在 UTC 命中
        assertThat(evaluator.evaluate(node(p), ctxAt("2026-06-01T10:00:00Z"))).isTrue();
    }
}
