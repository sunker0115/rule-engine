package com.sstlfsj.rule.kernel.api.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RuleEventTest {

    private static RuleEvent minimal() {
        return new RuleEvent("t1", "scene1", "LOGIN", "u1", "evt-001",
                Instant.EPOCH, Map.of(), null);
    }

    @Test
    void providedMetrics_defaultsToEmptyWhenNull() {
        RuleEvent event = minimal();
        assertNotNull(event.providedMetrics());
        assertTrue(event.providedMetrics().isEmpty());
    }

    @Test
    void payload_areImmutable() {
        Map<String, Object> mutable = new HashMap<>();
        mutable.put("k", "v");
        RuleEvent event = new RuleEvent("t1", "s1", "T", "u1", "e1",
                Instant.EPOCH, mutable, null);
        mutable.put("extra", "x");
        assertEquals(1, event.payload().size(), "构造后修改原始 map 不应影响 payload");
    }

    @Test
    void payload_mapIsUnmodifiable() {
        RuleEvent event = minimal();
        assertThrows(UnsupportedOperationException.class,
                () -> event.payload().put("k", "v"));
    }

    @Test
    void providedMetrics_areImmutableWhenProvided() {
        Map<String, Object> mutable = new HashMap<>();
        mutable.put("balance", 100);
        RuleEvent event = new RuleEvent("t1", "s1", "T", "u1", "e1",
                Instant.EPOCH, Map.of(), mutable);
        mutable.put("extra", "x");
        assertEquals(1, event.providedMetrics().size());
    }

    @Test
    void recordEquality_byValue() {
        Instant now = Instant.EPOCH;
        RuleEvent a = new RuleEvent("t1", "s1", "T", "u1", "e1", now, Map.of(), Map.of());
        RuleEvent b = new RuleEvent("t1", "s1", "T", "u1", "e1", now, Map.of(), Map.of());
        assertEquals(a, b);
    }
}
