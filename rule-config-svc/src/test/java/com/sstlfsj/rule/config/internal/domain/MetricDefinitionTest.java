package com.sstlfsj.rule.config.internal.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 验证 MetricDefinition Lombok getter/setter 及字段覆盖。 */
class MetricDefinitionTest {

    @Test
    void getterSetter_roundTrip() {
        MetricDefinition metric = new MetricDefinition();
        metric.setId(3L);
        metric.setTenantId(1L);
        metric.setMetricCode("user.age");
        metric.setName("年龄");
        metric.setSourceType("ATTRIBUTE");
        metric.setDataType("LONG");
        metric.setParams("{\"field\":\"age\"}");
        metric.setCacheTtlSeconds(60);
        metric.setAllowProvided(true);
        metric.setStatus("ACTIVE");
        metric.setCreatedBy("operator1");
        metric.setUpdatedBy("operator2");

        assertEquals(3L, metric.getId());
        assertEquals(1L, metric.getTenantId());
        assertEquals("user.age", metric.getMetricCode());
        assertEquals("年龄", metric.getName());
        assertEquals("ATTRIBUTE", metric.getSourceType());
        assertEquals("LONG", metric.getDataType());
        assertEquals("{\"field\":\"age\"}", metric.getParams());
        assertEquals(60, metric.getCacheTtlSeconds());
        assertTrue(metric.getAllowProvided());
        assertEquals("ACTIVE", metric.getStatus());
        assertEquals("operator1", metric.getCreatedBy());
        assertEquals("operator2", metric.getUpdatedBy());
    }

    @Test
    void defaultValues_areNull() {
        MetricDefinition metric = new MetricDefinition();
        assertNull(metric.getId());
        assertNull(metric.getAllowProvided());
        assertNull(metric.getCreatedAt());
        assertNull(metric.getUpdatedAt());
    }
}
