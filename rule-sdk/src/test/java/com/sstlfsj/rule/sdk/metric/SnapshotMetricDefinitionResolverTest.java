package com.sstlfsj.rule.sdk.metric;

import com.sstlfsj.rule.kernel.api.model.MetricDescriptor;
import com.sstlfsj.rule.kernel.api.spi.metric.MetricDefinitionResolver;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SnapshotMetricDefinitionResolverTest {

    @Test
    void resolve_registered_returnsDescriptor() {
        MetricDefinitionRegistry registry = new MetricDefinitionRegistry();
        registry.put("t1", new MetricDescriptor("risk.score", "TEST", "LONG", false, 0, Map.of()));
        MetricDefinitionResolver resolver = new SnapshotMetricDefinitionResolver(registry);
        assertThat(resolver.resolve("t1", "risk.score").sourceType()).isEqualTo("TEST");
    }

    @Test
    void resolve_missing_returnsNull() {
        MetricDefinitionResolver resolver =
                new SnapshotMetricDefinitionResolver(new MetricDefinitionRegistry());
        assertThat(resolver.resolve("t1", "nope")).isNull();
    }
}
