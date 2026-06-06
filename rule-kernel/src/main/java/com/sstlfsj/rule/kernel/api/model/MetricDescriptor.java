package com.sstlfsj.rule.kernel.api.model;

import java.util.Map;

/**
 * metric 的运行时定义快照，由 {@link com.sstlfsj.rule.kernel.api.spi.metric.MetricDefinitionResolver}
 * 解析提供，驱动取数管线的 provided 判定、handler 路由与缓存。
 * 区别于发布期冻进 AST 的 dataType（类型契约）：此处是可热调的操作配置。
 * 字段保持中性、可 JSON 序列化——同时作为嵌入式 SDK 取数（B2）的定义下发契约。
 */
public record MetricDescriptor(
        String metricCode,
        int metricVersion,
        String sourceType,
        String dataType,
        boolean allowProvided,
        int cacheTtlSeconds,
        Map<String, Object> params
) {
    public MetricDescriptor {
        params = params == null ? Map.of() : Map.copyOf(params);
    }

    /** 兼容旧调用点的便利构造：metricVersion 默认 1（B6 引入版本字段前的调用方无需感知版本）。 */
    public MetricDescriptor(String metricCode, String sourceType, String dataType,
                            boolean allowProvided, int cacheTtlSeconds, Map<String, Object> params) {
        this(metricCode, 1, sourceType, dataType, allowProvided, cacheTtlSeconds, params);
    }
}
