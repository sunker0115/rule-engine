package com.sstlfsj.rule.sdk.metric;

import com.sstlfsj.rule.kernel.api.model.MetricDescriptor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MetricDefinitionRegistryTest {

    /** 版本默认为 1 的描述符。 */
    private static MetricDescriptor desc(String code) {
        return new MetricDescriptor(code, 1, "TEST", "LONG", false, 0, Map.of());
    }

    /** 指定版本的描述符。 */
    private static MetricDescriptor desc(String code, int version) {
        return new MetricDescriptor(code, version, "TEST", "LONG", false, 0, Map.of());
    }

    @Test
    void put_then_get_returnsDescriptor() {
        MetricDefinitionRegistry registry = new MetricDefinitionRegistry();
        registry.put("t1", desc("risk.score"));
        assertThat(registry.get("t1", "risk.score", 1).metricCode()).isEqualTo("risk.score");
    }

    @Test
    void get_missing_returnsNull() {
        assertThat(new MetricDefinitionRegistry().get("t1", "nope", 1)).isNull();
    }

    @Test
    void get_otherTenant_returnsNull() {
        MetricDefinitionRegistry registry = new MetricDefinitionRegistry();
        registry.put("t1", desc("risk.score"));
        assertThat(registry.get("t2", "risk.score", 1)).isNull();
    }

    @Test
    void get_wrongVersion_returnsNull() {
        MetricDefinitionRegistry registry = new MetricDefinitionRegistry();
        registry.put("t1", desc("risk.score", 1));
        // 注册的是 version=1，查 version=2 应返回 null
        assertThat(registry.get("t1", "risk.score", 2)).isNull();
    }

    @Test
    void put_multipleVersions_retrievedIndependently() {
        MetricDefinitionRegistry registry = new MetricDefinitionRegistry();
        registry.put("t1", desc("risk.score", 1));
        registry.put("t1", desc("risk.score", 2));
        assertThat(registry.get("t1", "risk.score", 1).metricVersion()).isEqualTo(1);
        assertThat(registry.get("t1", "risk.score", 2).metricVersion()).isEqualTo(2);
    }

    @Test
    void replaceAll_removesStaleEntries() {
        MetricDefinitionRegistry registry = new MetricDefinitionRegistry();
        registry.put("t1", desc("old.metric"));
        registry.replaceAll("t1", List.of(desc("new.metric")));
        assertThat(registry.get("t1", "old.metric", 1)).isNull();
        assertThat(registry.get("t1", "new.metric", 1)).isNotNull();
    }

    @Test
    void replaceAll_removesAllVersionsOfTenant() {
        MetricDefinitionRegistry registry = new MetricDefinitionRegistry();
        registry.put("t1", desc("risk.score", 1));
        registry.put("t1", desc("risk.score", 2));
        registry.replaceAll("t1", List.of(desc("risk.score", 3)));
        assertThat(registry.get("t1", "risk.score", 1)).isNull();
        assertThat(registry.get("t1", "risk.score", 2)).isNull();
        assertThat(registry.get("t1", "risk.score", 3)).isNotNull();
    }

    @Test
    void replaceAll_doesNotTouchOtherTenant() {
        MetricDefinitionRegistry registry = new MetricDefinitionRegistry();
        registry.put("t2", desc("keep.metric"));
        registry.replaceAll("t1", List.of(desc("new.metric")));
        assertThat(registry.get("t2", "keep.metric", 1)).isNotNull();
    }

    @Test
    void replaceAll_emptyList_clearsTenant() {
        MetricDefinitionRegistry registry = new MetricDefinitionRegistry();
        registry.put("t1", desc("a"));
        registry.replaceAll("t1", List.of());
        assertThat(registry.get("t1", "a", 1)).isNull();
    }
}
