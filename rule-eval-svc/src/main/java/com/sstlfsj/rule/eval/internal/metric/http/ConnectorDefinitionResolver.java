package com.sstlfsj.rule.eval.internal.metric.http;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.sstlfsj.rule.config.api.connector.ConnectorDescriptor;
import com.sstlfsj.rule.eval.internal.domain.ConnectorDefinitionRow;
import com.sstlfsj.rule.eval.internal.repository.ConnectorDefinitionReadMapper;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * 连接器描述符解析（DB 读 + Caffeine 缓存，热路径不直查库；镜像 DbMetricDefinitionResolver）。
 * 缓存键含 tenant，缓存负结果（Optional.empty）避免缺失连接器反复穿透查库；短 TTL 使变更最终一致，
 * ConnectorChangedEvent 监听器经 invalidate 主动失效。
 */
@Component
public class ConnectorDefinitionResolver {

    private final ConnectorDefinitionReadMapper mapper;
    private final Cache<String, Optional<ConnectorDescriptor>> cache = Caffeine.newBuilder()
            .maximumSize(2_000)
            .expireAfterWrite(Duration.ofSeconds(60))
            .build();

    public ConnectorDefinitionResolver(ConnectorDefinitionReadMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 解析 ACTIVE 连接器描述符。
     *
     * @param tenantId      租户 id
     * @param connectorCode 连接器编码
     * @return 描述符；不存在返回 null（缓存负结果避免穿透）
     */
    public ConnectorDescriptor resolve(Long tenantId, String connectorCode) {
        return cache.get(tenantId + ":" + connectorCode, k -> {
            ConnectorDefinitionRow row = mapper.findActive(tenantId, connectorCode);
            return Optional.ofNullable(row == null ? null : row.getDescriptor());
        }).orElse(null);
    }

    /**
     * 失效指定连接器缓存（供 ConnectorChangedEvent 监听器）。
     *
     * @param tenantId      租户 id
     * @param connectorCode 连接器编码
     */
    public void invalidate(Long tenantId, String connectorCode) {
        cache.invalidate(tenantId + ":" + connectorCode);
    }
}
