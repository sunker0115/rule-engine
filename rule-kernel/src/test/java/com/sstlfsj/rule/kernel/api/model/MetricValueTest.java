package com.sstlfsj.rule.kernel.api.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MetricValueTest {

    @Test
    void recordEquality_byValue() {
        MetricValue a = new MetricValue(42, "NUMBER", "FETCHED");
        MetricValue b = new MetricValue(42, "NUMBER", "FETCHED");
        assertEquals(a, b);
    }

    @Test
    void nullableValue_allowsNull() {
        MetricValue mv = new MetricValue(null, "NUMBER", "PROVIDED");
        assertNull(mv.value());
    }

    @Test
    void valueSource_isRetained() {
        MetricValue mv = new MetricValue("active", "STRING", "PROVIDED");
        assertEquals("PROVIDED", mv.valueSource());
    }
}
