package com.sstlfsj.rule.sdk.metric;

import com.sstlfsj.rule.kernel.api.model.MetricDescriptor;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * SDK 本地 metric 定义注册表：键 {@code tenantId:metricCode}，值为下发的 {@link MetricDescriptor}。
 * 定义来源写入，HTTP 轮询热更整体替换；{@link SnapshotMetricDefinitionResolver} 读取。线程安全。
 */
public class MetricDefinitionRegistry {

    private final ConcurrentMap<String, MetricDescriptor> definitions = new ConcurrentHashMap<>();

    private static String key(String tenantId, String metricCode) {
        return tenantId + ":" + metricCode;
    }

    /**
     * 写入或覆盖单个定义。
     *
     * @param tenantId   租户 id
     * @param descriptor metric 定义快照
     */
    public void put(String tenantId, MetricDescriptor descriptor) {
        definitions.put(key(tenantId, descriptor.metricCode()), descriptor);
    }

    /**
     * 读取定义。
     *
     * @param tenantId   租户 id
     * @param metricCode 指标编码
     * @return 命中的定义；不存在返回 null
     */
    public MetricDescriptor get(String tenantId, String metricCode) {
        return definitions.get(key(tenantId, metricCode));
    }

    /**
     * 用给定列表整体替换某租户的定义集合（HTTP 热更语义）：先移除该租户旧条目，再写入新列表。
     *
     * @param tenantId    租户 id
     * @param descriptors 该租户最新定义列表
     */
    public void replaceAll(String tenantId, List<MetricDescriptor> descriptors) {
        String prefix = tenantId + ":";
        definitions.keySet().removeIf(k -> k.startsWith(prefix));
        for (MetricDescriptor d : descriptors) {
            definitions.put(key(tenantId, d.metricCode()), d);
        }
    }
}
