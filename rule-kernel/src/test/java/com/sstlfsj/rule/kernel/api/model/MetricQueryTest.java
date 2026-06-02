package com.sstlfsj.rule.kernel.api.model;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MetricQueryTest {

    @Test
    void recordEquality_byValue() {
        MetricQuery a = new MetricQuery("balance", "t1", "u1", Map.of("window", 7), Map.of());
        MetricQuery b = new MetricQuery("balance", "t1", "u1", Map.of("window", 7), Map.of());
        assertEquals(a, b);
    }

    @Test
    void fields_areRetained() {
        MetricQuery q = new MetricQuery("score", "t1", "u1", Map.of("k", "v"), Map.of("p", 1));
        assertEquals("score", q.metricCode());
        assertEquals("t1", q.tenantId());
        assertEquals("u1", q.subjectId());
        assertEquals("v", q.params().get("k"));
        assertEquals(1, q.eventPayload().get("p"));
    }
}
