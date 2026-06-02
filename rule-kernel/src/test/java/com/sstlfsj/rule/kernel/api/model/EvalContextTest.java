package com.sstlfsj.rule.kernel.api.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EvalContextTest {

    private static RuleEvent event() {
        return new RuleEvent("t1", "s1", "LOGIN", "u1", "e1", Instant.EPOCH, Map.of(), null);
    }

    private static Subject subject() {
        return new Subject("u1", SubjectType.USER, Map.of());
    }

    @Test
    void getMetric_returnsValueWhenPresent() {
        MetricValue mv = new MetricValue(100, "NUMBER", "FETCHED");
        EvalContext ctx = new EvalContext("t1", event(), subject(), Map.of("balance", mv));
        assertSame(mv, ctx.getMetric("balance"));
    }

    @Test
    void getMetric_returnsNullWhenAbsent() {
        EvalContext ctx = new EvalContext("t1", event(), subject(), Map.of());
        assertNull(ctx.getMetric("missing"));
    }

    @Test
    void hasMetric_trueWhenPresent() {
        MetricValue mv = new MetricValue(1, "NUMBER", "FETCHED");
        EvalContext ctx = new EvalContext("t1", event(), subject(), Map.of("x", mv));
        assertTrue(ctx.hasMetric("x"));
    }

    @Test
    void hasMetric_falseWhenAbsent() {
        EvalContext ctx = new EvalContext("t1", event(), subject(), Map.of());
        assertFalse(ctx.hasMetric("x"));
    }

    @Test
    void metrics_areImmutable() {
        Map<String, MetricValue> mutable = new HashMap<>();
        mutable.put("a", new MetricValue(1, "NUMBER", "FETCHED"));
        EvalContext ctx = new EvalContext("t1", event(), subject(), mutable);
        mutable.put("b", new MetricValue(2, "NUMBER", "FETCHED"));
        assertFalse(ctx.hasMetric("b"), "构造后修改原始 map 不应影响 EvalContext");
    }

    @Test
    void getters_returnConstructorValues() {
        RuleEvent ev = event();
        Subject sub = subject();
        EvalContext ctx = new EvalContext("t1", ev, sub, Map.of());
        assertEquals("t1", ctx.getTenantId());
        assertSame(ev, ctx.getEvent());
        assertSame(sub, ctx.getSubject());
    }
}
