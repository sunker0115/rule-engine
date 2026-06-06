package com.sstlfsj.rule.sdk.metric;

import com.sstlfsj.rule.kernel.api.model.MetricDescriptor;
import com.sstlfsj.rule.kernel.api.spi.metric.MetricDefinitionResolver;

/**
 * {@link MetricDefinitionResolver} 的嵌入式实现：从下发到本地的 {@link MetricDefinitionRegistry} 读定义。
 * 与服务端读库实现共用同一 SPI 抽象，上层取数编排不感知数据源。
 */
public class SnapshotMetricDefinitionResolver implements MetricDefinitionResolver {

    private final MetricDefinitionRegistry registry;

    /**
     * @param registry 本地定义注册表
     */
    public SnapshotMetricDefinitionResolver(MetricDefinitionRegistry registry) {
        this.registry = registry;
    }

    @Override
    public MetricDescriptor resolve(String tenantId, String metricCode, int metricVersion) {
        return registry.get(tenantId, metricCode, metricVersion);
    }
}
