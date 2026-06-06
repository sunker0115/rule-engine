package com.sstlfsj.rule.sdk.metric;

import com.sstlfsj.rule.kernel.api.model.MetricDescriptor;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * SDK 本地 metric 定义注册表：键 {@code tenantId:metricCode:version}，值为下发的 {@link MetricDescriptor}。
 * 定义来源写入，HTTP 轮询热更整体替换（按租户前缀清理旧版本）；{@link SnapshotMetricDefinitionResolver} 读取。线程安全。
 */
public class MetricDefinitionRegistry {

    private final ConcurrentMap<String, MetricDescriptor> definitions = new ConcurrentHashMap<>();

    private static String key(String tenantId, String metricCode, int metricVersion) {
        return tenantId + ":" + metricCode + ":" + metricVersion;
    }

    /**
     * 写入或覆盖单个定义（键含 metricVersion，同 code 不同版本独立存储）。
     *
     * @param tenantId   租户 id
     * @param descriptor metric 定义快照（版本号取自 descriptor.metricVersion()）
     */
    public void put(String tenantId, MetricDescriptor descriptor) {
        definitions.put(key(tenantId, descriptor.metricCode(), descriptor.metricVersion()), descriptor);
    }

    /**
     * 按 (tenantId, metricCode, metricVersion) 读取定义。
     *
     * @param tenantId      租户 id
     * @param metricCode    指标编码
     * @param metricVersion 版本号
     * @return 命中的定义；不存在返回 null
     */
    public MetricDescriptor get(String tenantId, String metricCode, int metricVersion) {
        return definitions.get(key(tenantId, metricCode, metricVersion));
    }

    /**
     * 用给定列表整体替换某租户的定义集合（HTTP 热更语义）：先移除该租户所有版本条目，再写入新列表。
     *
     * @param tenantId    租户 id
     * @param descriptors 该租户最新定义列表（空列表将清空该租户全部定义）
     */
    public void replaceAll(String tenantId, List<MetricDescriptor> descriptors) {
        // 按租户前缀清理，覆盖所有版本旧条目
        String prefix = tenantId + ":";
        definitions.keySet().removeIf(k -> k.startsWith(prefix));
        for (MetricDescriptor d : descriptors) {
            definitions.put(key(tenantId, d.metricCode(), d.metricVersion()), d);
        }
    }
}
