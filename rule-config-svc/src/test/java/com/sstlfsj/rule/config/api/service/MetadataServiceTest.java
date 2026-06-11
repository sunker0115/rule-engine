package com.sstlfsj.rule.config.api.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 验证 MetadataService 内嵌 record 类型可正常构造，accessor 返回预期值。 */
class MetadataServiceTest {

    @Test
    void metadataResponse_recordAccessors() {
        Map<String, Object> condSchema = Map.of("type", "object");
        var condType = new MetadataService.ConditionTypeMeta("GT", "大于", condSchema, true);
        var metric = new MetadataService.MetricMeta("age", "年龄", "INTEGER", "DB", true);
        var response = new MetadataService.MetadataResponse(
                List.of(condType), List.of(metric));

        assertEquals(1, response.conditionTypes().size());
        assertEquals("GT", response.conditionTypes().get(0).code());
        assertTrue(response.conditionTypes().get(0).requiresMetric());
        assertEquals(condSchema, response.conditionTypes().get(0).paramsSchema());

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
