package com.sstlfsj.rule.sdk.source;

import com.sstlfsj.rule.kernel.api.model.MetricDescriptor;
import com.sstlfsj.rule.sdk.metric.MetricDefinitionRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 {@link MetricDefinitionSource} SPI 契约：实现必须将定义写入注册表。
 * 使用 {@link DslMetricDefinitionSource} 作为最小实现代表验证接口语义。
 */
class MetricDefinitionSourceTest {

    @Test
    void loadInto_writesDefinitionToRegistry() {
        MetricDefinitionRegistry registry = new MetricDefinitionRegistry();
        MetricDescriptor d = new MetricDescriptor("m1", "TEST", "LONG", false, 0, Map.of());
        MetricDefinitionSource source = new DslMetricDefinitionSource("tenant1", List.of(d));

        source.loadInto(registry);

        assertThat(registry.get("tenant1", "m1")).isNotNull();
    }
}
