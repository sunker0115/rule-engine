package com.sstlfsj.rule.sdk.metric;

import com.sstlfsj.rule.kernel.api.model.MetricDescriptor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MetricDefinitionRegistryTest {

    private static MetricDescriptor desc(String code) {
        return new MetricDescriptor(code, "TEST", "LONG", false, 0, Map.of());
    }

    @Test
    void put_then_get_returnsDescriptor() {
        MetricDefinitionRegistry registry = new MetricDefinitionRegistry();
        registry.put("t1", desc("risk.score"));
        assertThat(registry.get("t1", "risk.score").metricCode()).isEqualTo("risk.score");
    }

    @Test
    void get_missing_returnsNull() {
        assertThat(new MetricDefinitionRegistry().get("t1", "nope")).isNull();
    }

    @Test
    void get_otherTenant_returnsNull() {
        MetricDefinitionRegistry registry = new MetricDefinitionRegistry();
        registry.put("t1", desc("risk.score"));
        assertThat(registry.get("t2", "risk.score")).isNull();
    }

    @Test
    void replaceAll_removesStaleEntries() {
        MetricDefinitionRegistry registry = new MetricDefinitionRegistry();
        registry.put("t1", desc("old.metric"));
        registry.replaceAll("t1", List.of(desc("new.metric")));
        assertThat(registry.get("t1", "old.metric")).isNull();
        assertThat(registry.get("t1", "new.metric")).isNotNull();
    }

    @Test
    void replaceAll_doesNotTouchOtherTenant() {
        MetricDefinitionRegistry registry = new MetricDefinitionRegistry();
        registry.put("t2", desc("keep.metric"));
        registry.replaceAll("t1", List.of(desc("new.metric")));
        assertThat(registry.get("t2", "keep.metric")).isNotNull();
    }

    @Test
    void replaceAll_emptyList_clearsTenant() {
        MetricDefinitionRegistry registry = new MetricDefinitionRegistry();
        registry.put("t1", desc("a"));
        registry.replaceAll("t1", List.of());
        assertThat(registry.get("t1", "a")).isNull();
    }
}
