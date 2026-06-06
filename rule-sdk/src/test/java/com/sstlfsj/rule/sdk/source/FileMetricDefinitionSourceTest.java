package com.sstlfsj.rule.sdk.source;

import com.sstlfsj.rule.sdk.metric.MetricDefinitionRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileMetricDefinitionSourceTest {

    @Test
    void classpath_loadsDescriptors() {
        MetricDefinitionRegistry registry = new MetricDefinitionRegistry();
        FileMetricDefinitionSource.classpath("t1", "metric-definitions/test-defs.json")
                .loadInto(registry);
        assertThat(registry.get("t1", "risk.score", 1).params()).containsEntry("window", "30d");
    }

    @Test
    void classpath_missingResource_throws() {
        assertThatThrownBy(() ->
                FileMetricDefinitionSource.classpath("t1", "metric-definitions/missing.json"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
