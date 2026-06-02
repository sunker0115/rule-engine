package com.sstlfsj.rule.config.api.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies MetadataService nested record types can be constructed and
 * their accessors return the expected values.
 */
class MetadataServiceTest {

    @Test
    void metadataResponse_recordAccessors() {
        var condType = new MetadataService.ConditionTypeMeta("GT", "大于", null, true);
        var actType = new MetadataService.ActionTypeMeta("BLOCK", "拦截", null, false);
        var metric = new MetadataService.MetricMeta("age", "年龄", "INTEGER", "DB", true);
        var response = new MetadataService.MetadataResponse(
                List.of(condType), List.of(actType), List.of(metric));

        assertEquals(1, response.conditionTypes().size());
        assertEquals("GT", response.conditionTypes().get(0).code());
        assertTrue(response.conditionTypes().get(0).requiresMetric());

        assertEquals(1, response.actionTypes().size());
        assertEquals("BLOCK", response.actionTypes().get(0).code());
        assertFalse(response.actionTypes().get(0).compensatable());

        assertEquals(1, response.availableMetrics().size());
        assertEquals("age", response.availableMetrics().get(0).metricCode());
        assertTrue(response.availableMetrics().get(0).allowProvided());
    }

    @Test
    void conditionTypeMeta_recordEquality() {
        var a = new MetadataService.ConditionTypeMeta("EQ", "等于", null, false);
        var b = new MetadataService.ConditionTypeMeta("EQ", "等于", null, false);
        assertEquals(a, b);
    }

    @Test
    void metricMeta_recordEquality() {
        var a = new MetadataService.MetricMeta("score", "评分", "DECIMAL", "COMPUTE", false);
        var b = new MetadataService.MetricMeta("score", "评分", "DECIMAL", "COMPUTE", false);
        assertEquals(a, b);
    }
}
