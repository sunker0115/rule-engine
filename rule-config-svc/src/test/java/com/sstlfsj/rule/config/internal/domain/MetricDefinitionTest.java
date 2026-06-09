package com.sstlfsj.rule.config.internal.domain;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 验证 MetricDefinition Lombok getter/setter 及字段覆盖。 */
class MetricDefinitionTest {

    @Test
    void getterSetter_roundTrip() {
        MetricDefinition metric = new MetricDefinition();
        metric.setId(3L);
        metric.setTenantId(1L);
        metric.setMetricCode("user.age");
        metric.setVersion(2);
        metric.setName("年龄");
        metric.setSourceType("ATTRIBUTE");
        metric.setDataType("LONG");
        metric.setParams(Map.of("field", "age"));
        metric.setCacheTtlSeconds(60);
        metric.setAllowProvided(true);
        metric.setStatus(MetricStatus.ACTIVE);
        metric.setCreatedBy("operator1");
        metric.setUpdatedBy("operator2");

        assertEquals(3L, metric.getId());
        assertEquals(1L, metric.getTenantId());
        assertEquals("user.age", metric.getMetricCode());
        assertEquals(2, metric.getVersion());
        assertEquals("年龄", metric.getName());
        assertEquals("ATTRIBUTE", metric.getSourceType());
        assertEquals("LONG", metric.getDataType());
        assertEquals(Map.of("field", "age"), metric.getParams());
        assertEquals(60, metric.getCacheTtlSeconds());
        assertTrue(metric.getAllowProvided());
        assertEquals(MetricStatus.ACTIVE, metric.getStatus());
        assertEquals("operator1", metric.getCreatedBy());
        assertEquals("operator2", metric.getUpdatedBy());
    }

    @Test
    void defaultValues_areNull() {
        MetricDefinition metric = new MetricDefinition();
        assertNull(metric.getId());
        assertNull(metric.getVersion());
        assertNull(metric.getAllowProvided());
        assertNull(metric.getCreatedAt());
        assertNull(metric.getUpdatedAt());
    }
}
