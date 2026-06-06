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
        registry.put("t1", new MetricDescriptor("risk.score", 2, "TEST", "LONG", false, 0, Map.of()));
        MetricDefinitionResolver resolver = new SnapshotMetricDefinitionResolver(registry);
        MetricDescriptor d = resolver.resolve("t1", "risk.score", 2);
        assertThat(d.sourceType()).isEqualTo("TEST");
        assertThat(d.metricVersion()).isEqualTo(2);
    }

    @Test
    void resolve_missing_returnsNull() {
        MetricDefinitionResolver resolver =
                new SnapshotMetricDefinitionResolver(new MetricDefinitionRegistry());
        assertThat(resolver.resolve("t1", "nope", 1)).isNull();
    }

    @Test
    void resolve_wrongVersion_returnsNull() {
        MetricDefinitionRegistry registry = new MetricDefinitionRegistry();
        registry.put("t1", new MetricDescriptor("risk.score", 1, "TEST", "LONG", false, 0, Map.of()));
        MetricDefinitionResolver resolver = new SnapshotMetricDefinitionResolver(registry);
        // 注册的是 version=1，查 version=2 应返回 null
        assertThat(resolver.resolve("t1", "risk.score", 2)).isNull();
    }
}
