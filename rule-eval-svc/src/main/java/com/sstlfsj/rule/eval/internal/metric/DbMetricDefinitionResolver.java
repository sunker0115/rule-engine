package com.sstlfsj.rule.eval.internal.metric;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.sstlfsj.rule.eval.internal.domain.MetricDefinitionRow;
import com.sstlfsj.rule.eval.internal.repository.MetricDefinitionReadMapper;
import com.sstlfsj.rule.kernel.api.model.MetricDescriptor;
import com.sstlfsj.rule.kernel.api.spi.metric.MetricDefinitionResolver;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * 读 metric_definition 实现 MetricDefinitionResolver；用 Caffeine 缓存定义快照（短 TTL），
 * 避免每次评估查库。定义级配置变更在 TTL 内最终一致。
 */
@Component
public class DbMetricDefinitionResolver implements MetricDefinitionResolver {

    private final MetricDefinitionReadMapper mapper;
    private final ObjectMapper objectMapper;
    private final Cache<String, MetricDescriptor> cache = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(Duration.ofSeconds(60))
            .build();

    public DbMetricDefinitionResolver(MetricDefinitionReadMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public MetricDescriptor resolve(String tenantId, String metricCode, int metricVersion) {
        // 缓存键含 version，避免不同版本互相覆盖
        String key = tenantId + ":" + metricCode + ":" + metricVersion;
        MetricDescriptor cached = cache.getIfPresent(key);
        if (cached != null) return cached;
        MetricDefinitionRow row = mapper.findByVersion(Long.parseLong(tenantId), metricCode, metricVersion);
        if (row == null) return null;   // 不缓存 null，避免遮蔽新建定义
        // 把 dataType 一并塞进 params，供 SQL/HTTP handler 结果强转使用
        Map<String, Object> params = new HashMap<>(parseParams(row.paramsJson()));
        params.put("dataType", row.dataType());
        MetricDescriptor d = new MetricDescriptor(
                row.metricCode(), row.version(), row.sourceType(), row.dataType(),
                Boolean.TRUE.equals(row.allowProvided()),
                row.cacheTtlSeconds() == null ? 0 : row.cacheTtlSeconds(),
                params);
        cache.put(key, d);
        return d;
    }

    private Map<String, Object> parseParams(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }
}
