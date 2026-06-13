package com.sstlfsj.rule.config.api.service;

import com.sstlfsj.rule.kernel.api.operator.OperatorSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** 验证 MetadataService 内嵌 record 类型可正常构造，accessor 返回预期值。 */
class MetadataServiceTest {

    @Test
    void metadataResponse_recordAccessors() {
        var condType = OperatorSpec.builder()
                .code("GT").displayName("大于")
                .requiredParamKeys(Set.of("threshold"))
                .allowedDataTypes(Set.of())
                .requiresMetric(true).build();
        var metric = new MetadataService.MetricMeta("age", "年龄", "INTEGER", "DB", true);
        var response = new MetadataService.MetadataResponse(
                List.of(condType), List.of(metric));

        assertEquals(1, response.conditionTypes().size());
        assertEquals("GT", response.conditionTypes().get(0).code());
        assertTrue(response.conditionTypes().get(0).requiresMetric());
        assertEquals(Set.of("threshold"), response.conditionTypes().get(0).requiredParamKeys());

        assertEquals(1, response.availableMetrics().size());
        assertEquals("age", response.availableMetrics().get(0).metricCode());
        assertTrue(response.availableMetrics().get(0).allowProvided());
    }

    @Test
    void metricMeta_recordEquality() {
        var a = new MetadataService.MetricMeta("score", "评分", "DECIMAL", "COMPUTE", false);
        var b = new MetadataService.MetricMeta("score", "评分", "DECIMAL", "COMPUTE", false);
        assertEquals(a, b);
    }
}
