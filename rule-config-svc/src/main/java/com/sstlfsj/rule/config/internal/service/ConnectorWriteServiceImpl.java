package com.sstlfsj.rule.config.internal.service;

import com.sstlfsj.rule.config.api.event.ConnectorChangedEvent;
import com.sstlfsj.rule.config.api.service.ConnectorWriteService;
import com.sstlfsj.rule.config.internal.domain.ConnectorDefinition;
import com.sstlfsj.rule.config.internal.domain.ConnectorStatus;
import com.sstlfsj.rule.config.internal.event.ConnectorChangedSnapshot;
import com.sstlfsj.rule.config.internal.event.OperationAuditedEvent;
import com.sstlfsj.rule.config.internal.repository.ConnectorDefinitionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 连接器写服务实现：写时校验、落库、发审计与失效事件（CRUD，不升版，无 publish 流程）。
 * 审计走 {@link OperationAuditedEvent}（B 类同事务），失效走 {@link ConnectorChangedEvent}（A 类跨模块）。
 */
@Service
@Transactional
@RequiredArgsConstructor
public class ConnectorWriteServiceImpl implements ConnectorWriteService {

    private final ConnectorDefinitionMapper mapper;
    private final ApplicationEventPublisher eventPublisher;
    private final Supplier<Set<String>> endpointNames;
    private final ConnectorSafetyValidator validator = new ConnectorSafetyValidator();

    @Override
    public Long create(Long tenantId, String connectorCode, ConnectorWriteCommand cmd, String actorId) {
        if (mapper.findByCode(tenantId, connectorCode) != null) {
            throw new IllegalArgumentException("连接器已存在: " + connectorCode);
        }
        validator.validate(cmd.descriptor(), endpointNames.get());

        ConnectorDefinition c = new ConnectorDefinition();
        c.setTenantId(tenantId);
        c.setConnectorCode(connectorCode);
        c.setName(cmd.name());
        c.setDescriptor(cmd.descriptor());
        c.setStatus(ConnectorStatus.ACTIVE);
        c.setCreatedBy(actorId);
        c.setCreatedAt(LocalDateTime.now());
        c.setUpdatedBy(actorId);
        c.setUpdatedAt(LocalDateTime.now());
        mapper.insert(c);

        // CREATE 类 before/after 传同一快照实例，审计行始终 before/after 都有值（照 MetricWriteServiceImpl）
        ConnectorChangedSnapshot snapshot = new ConnectorChangedSnapshot(connectorCode, cmd.name());
        publishAudit(tenantId, actorId, "CREATE", c.getId(), snapshot, snapshot);
        eventPublisher.publishEvent(new ConnectorChangedEvent(String.valueOf(tenantId), connectorCode));
        return c.getId();
    }

    @Override
    public int update(Long tenantId, String connectorCode, ConnectorWriteCommand cmd, String actorId) {
        ConnectorDefinition existing = mapper.findByCode(tenantId, connectorCode);
        if (existing == null) {
            throw new IllegalArgumentException("连接器不存在: " + connectorCode);
        }
        validator.validate(cmd.descriptor(), endpointNames.get());

        ConnectorChangedSnapshot before = new ConnectorChangedSnapshot(connectorCode, existing.getName());
        existing.setName(cmd.name());
        existing.setDescriptor(cmd.descriptor());
        existing.setUpdatedBy(actorId);
        existing.setUpdatedAt(LocalDateTime.now());
        int n = mapper.updateById(existing);

        publishAudit(tenantId, actorId, "UPDATE", existing.getId(), before,
                new ConnectorChangedSnapshot(connectorCode, cmd.name()));
        eventPublisher.publishEvent(new ConnectorChangedEvent(String.valueOf(tenantId), connectorCode));
        return n;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ConnectorView> listActive(Long tenantId) {
        return mapper.findActiveByTenant(tenantId).stream()
                .map(c -> new ConnectorView(c.getConnectorCode(), c.getName(), c.getStatus().name()))
                .toList();
    }

    /** 发布操作审计事件，由集中监听器 BEFORE_COMMIT 同事务落 audit_log（D14 约定）。 */
    private void publishAudit(Long tenantId, String actorId, String action, Long id,
                              ConnectorChangedSnapshot before, ConnectorChangedSnapshot after) {
        eventPublisher.publishEvent(new OperationAuditedEvent(
                tenantId, actorId, "USER", action, "connector_definition", String.valueOf(id),
                before, after, LocalDateTime.now()));
    }
}
