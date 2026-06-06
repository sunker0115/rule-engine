package com.sstlfsj.rule.sdk.source;

import com.sstlfsj.rule.kernel.api.model.MetricDescriptor;
import com.sstlfsj.rule.sdk.metric.MetricDefinitionRegistry;

import java.util.List;

/** 代码 DSL 模式定义来源：直接持有某租户的 {@link MetricDescriptor} 列表，逐条写入注册表（追加语义）。 */
public class DslMetricDefinitionSource implements MetricDefinitionSource {

    private final String tenantId;
    private final List<MetricDescriptor> descriptors;

    /**
     * @param tenantId    定义所属租户 id（注册表按 tenantId 归类）
     * @param descriptors metric 定义列表
     */
    public DslMetricDefinitionSource(String tenantId, List<MetricDescriptor> descriptors) {
        this.tenantId = tenantId;
        this.descriptors = List.copyOf(descriptors);
    }

    @Override
    public void loadInto(MetricDefinitionRegistry registry) {
        for (MetricDescriptor d : descriptors) {
            registry.put(tenantId, d);
        }
    }
}
