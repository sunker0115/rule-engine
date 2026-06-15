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

    @Test
    void threeArgConstructor_keepsErrorCodeNull() {
        MetricValue mv = new MetricValue(42, "LONG", "PROVIDED");
        assertNull(mv.errorCode());
        assertFalse(mv.isError());
    }

    @Test
    void error_factory_marksError() {
        MetricValue mv = MetricValue.error("METRIC_FETCH_FAIL");
        assertTrue(mv.isError());
        assertEquals("METRIC_FETCH_FAIL", mv.errorCode());
        assertNull(mv.value());
        assertEquals("FETCHED", mv.valueSource());
    }

    @Test
    void errorEnumOverload_storesEnumName() {
        MetricValue mv = MetricValue.error(EvalErrorCode.METRIC_FETCH_FAIL);
        assertTrue(mv.isError());
        assertEquals("METRIC_FETCH_FAIL", mv.errorCode());
        assertEquals("FETCHED", mv.valueSource());
    }

    @Test
    void convenienceConstructors_leaveReasonNull() {
        assertNull(new MetricValue(42, "LONG", "PROVIDED").reason());
        assertNull(new MetricValue(null, "UNKNOWN", "FETCHED", "TIMEOUT").reason());
        assertNull(MetricValue.error("TIMEOUT").reason());
    }

    @Test
    void fiveArgConstructor_retainsReason() {
        MetricValue mv = new MetricValue(null, "UNKNOWN", "FETCHED", "TIMEOUT", "upstream took >300ms");
        assertEquals("upstream took >300ms", mv.reason());
        assertEquals("TIMEOUT", mv.errorCode());
        assertTrue(mv.isError());
    }
}
