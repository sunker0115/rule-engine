package com.sstlfsj.rule.kernel.api.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EvalContextTest {

    private static final Instant NOW = Instant.parse("2026-06-01T00:00:00Z");

    private static RuleEvent event() {
        return new RuleEvent("t1", "s1", "LOGIN", "u1", "e1", Instant.EPOCH, Map.of(), null, com.sstlfsj.rule.kernel.api.model.EventSource.HTTP);
    }

    private static Subject subject() {
        return new Subject("u1", SubjectType.USER, Map.of());
    }

    @Test
    void getMetric_returnsValueWhenPresent() {
        MetricValue mv = new MetricValue(100, "NUMBER", "FETCHED");
        EvalContext ctx = new EvalContext("t1", event(), subject(), Map.of("balance", mv), NOW);
        assertSame(mv, ctx.getMetric("balance"));
    }

    @Test
    void getMetric_returnsNullWhenAbsent() {
        EvalContext ctx = new EvalContext("t1", event(), subject(), Map.of(), NOW);
        assertNull(ctx.getMetric("missing"));
    }

    @Test
    void hasMetric_trueWhenPresent() {
        MetricValue mv = new MetricValue(1, "NUMBER", "FETCHED");
        EvalContext ctx = new EvalContext("t1", event(), subject(), Map.of("x", mv), NOW);
        assertTrue(ctx.hasMetric("x"));
    }

    @Test
    void hasMetric_falseWhenAbsent() {
        EvalContext ctx = new EvalContext("t1", event(), subject(), Map.of(), NOW);
        assertFalse(ctx.hasMetric("x"));
    }

    @Test
    void metrics_areImmutable() {
        Map<String, MetricValue> mutable = new HashMap<>();
        mutable.put("a", new MetricValue(1, "NUMBER", "FETCHED"));
        EvalContext ctx = new EvalContext("t1", event(), subject(), mutable, NOW);
        mutable.put("b", new MetricValue(2, "NUMBER", "FETCHED"));
        assertFalse(ctx.hasMetric("b"), "构造后修改原始 map 不应影响 EvalContext");
    }

    @Test
    void accessors_returnConstructorValues() {
        RuleEvent ev = event();
        Subject sub = subject();
        EvalContext ctx = new EvalContext("t1", ev, sub, Map.of(), NOW);
        assertEquals("t1", ctx.tenantId());
        assertSame(ev, ctx.event());
        assertSame(sub, ctx.subject());
    }

    @Test
    void metrics_returnsFullMetricMap() {
        MetricValue mv = new MetricValue(99, "NUMBER", "FETCHED");
        EvalContext ctx = new EvalContext("t1", event(), subject(), Map.of("score", mv), NOW);
        assertEquals(1, ctx.metrics().size());
        assertSame(mv, ctx.metrics().get("score"));
    }

    @Test
    void metrics_isImmutable() {
        EvalContext ctx = new EvalContext("t1", event(), subject(), Map.of("a", new MetricValue(1, "NUMBER", "FETCHED")), NOW);
        assertThrows(UnsupportedOperationException.class, () -> ctx.metrics().put("b", new MetricValue(2, "NUMBER", "FETCHED")));
    }

    @Test
    void now_isStoredAndReturned() {
        Instant fixed = Instant.parse("2026-06-01T00:00:00Z");
        EvalContext ctx = new EvalContext("t1", event(), subject(), Map.of(), fixed);
        assertSame(fixed, ctx.now());
    }

    @Test
    void sceneDefaultParams_carriedAndImmutable() {
        EvalContext ctx = new EvalContext("t1", event(), subject(), Map.of(), NOW,
                Map.of("timezone", "Asia/Shanghai"));
        assertEquals("Asia/Shanghai", ctx.sceneDefaultParams().get("timezone"));
        assertThrows(UnsupportedOperationException.class,
                () -> ctx.sceneDefaultParams().put("x", "y"));
    }

    @Test
    void compatConstructor_defaultsEmptySceneParams() {
        EvalContext ctx = new EvalContext("t1", event(), subject(), Map.of(), NOW);
        assertTrue(ctx.sceneDefaultParams().isEmpty());
    }
}
