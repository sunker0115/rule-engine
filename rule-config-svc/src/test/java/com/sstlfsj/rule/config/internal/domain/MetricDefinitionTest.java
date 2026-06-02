package com.sstlfsj.rule.config.internal.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Verifies MetricDefinition getter/setter round-trips. */
class MetricDefinitionTest {

    @Test
    void getterSetter_roundTrip() {
        MetricDefinition metric = new MetricDefinition();
        metric.setId(3L);
        metric.setTenantId("tenant1");
        metric.setMetricCode("age");
        metric.setName("年龄");
        metric.setDataType("INTEGER");
        metric.setSourceType("DB");
        metric.setAllowProvided(true);

        assertEquals(3L, metric.getId());
        assertEquals("tenant1", metric.getTenantId());
        assertEquals("age", metric.getMetricCode());
        assertEquals("年龄", metric.getName());
        assertEquals("INTEGER", metric.getDataType());
        assertEquals("DB", metric.getSourceType());
        assertTrue(metric.isAllowProvided());
    }

    @Test
    void allowProvided_defaultFalse() {
        MetricDefinition metric = new MetricDefinition();
        assertFalse(metric.isAllowProvided());
    }
}
