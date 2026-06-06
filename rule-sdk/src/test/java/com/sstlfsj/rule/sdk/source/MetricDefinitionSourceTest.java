package com.sstlfsj.rule.sdk.source;

import com.sstlfsj.rule.kernel.api.model.MetricDescriptor;
import com.sstlfsj.rule.sdk.metric.MetricDefinitionRegistry;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MetricDefinitionSourceTest {

    /** 自定义 SPI 实现：证明 MetricDefinitionSource 是可被任意宿主代码扩展的落点（对称 RuleSource）。 */
    @Test
    void customImplementation_loadInto_writesViaSpi() {
        MetricDefinitionSource custom = registry ->
                registry.put("t9", new MetricDescriptor("custom.m", 1, "TEST", "LONG", false, 0, Map.of()));

        MetricDefinitionRegistry registry = new MetricDefinitionRegistry();
        custom.loadInto(registry);   // 经 SPI 引用调用

        assertThat(registry.get("t9", "custom.m", 1)).isNotNull();
    }
}
