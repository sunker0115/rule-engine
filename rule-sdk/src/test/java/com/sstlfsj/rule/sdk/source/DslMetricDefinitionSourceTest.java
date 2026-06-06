package com.sstlfsj.rule.sdk.source;

import com.sstlfsj.rule.kernel.api.model.MetricDescriptor;
import com.sstlfsj.rule.sdk.metric.MetricDefinitionRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DslMetricDefinitionSourceTest {

    @Test
    void loadInto_registersDescriptorsUnderTenant() {
        MetricDefinitionRegistry registry = new MetricDefinitionRegistry();
        MetricDescriptor d = new MetricDescriptor("risk.score", "TEST", "LONG", false, 0, Map.of());
        new DslMetricDefinitionSource("t1", List.of(d)).loadInto(registry);
        assertThat(registry.get("t1", "risk.score", 1)).isNotNull();
    }

    @Test
    void loadInto_isAdditive_doesNotWipeExisting() {
        MetricDefinitionRegistry registry = new MetricDefinitionRegistry();
        registry.put("t1", new MetricDescriptor("a", "TEST", "LONG", false, 0, Map.of()));
        new DslMetricDefinitionSource("t1",
                List.of(new MetricDescriptor("b", "TEST", "LONG", false, 0, Map.of())))
                .loadInto(registry);
        assertThat(registry.get("t1", "a", 1)).isNotNull();
        assertThat(registry.get("t1", "b", 1)).isNotNull();
    }
}
