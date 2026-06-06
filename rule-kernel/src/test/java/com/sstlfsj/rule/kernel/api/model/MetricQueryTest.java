package com.sstlfsj.rule.kernel.api.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MetricQueryTest {

    private static final Instant NOW = Instant.parse("2026-06-06T00:00:00Z");

    @Test
    void recordEquality_byValue() {
        MetricQuery a = new MetricQuery("balance", "t1", "u1", Map.of("window", 7), Map.of(), NOW);
        MetricQuery b = new MetricQuery("balance", "t1", "u1", Map.of("window", 7), Map.of(), NOW);
        assertEquals(a, b);
    }

    @Test
    void fields_areRetained() {
        MetricQuery q = new MetricQuery("score", "t1", "u1", Map.of("k", "v"), Map.of("p", 1), NOW);
        assertEquals("score", q.metricCode());
        assertEquals("t1", q.tenantId());
        assertEquals("u1", q.subjectId());
        assertEquals("v", q.params().get("k"));
        assertEquals(1, q.eventPayload().get("p"));
        assertEquals(NOW, q.now());
    }
}
