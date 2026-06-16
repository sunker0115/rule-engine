package com.sstlfsj.rule.config.internal.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sstlfsj.rule.config.api.dto.ConnectorListQuery;
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
        ConnectorChangedSnapshot snapshot = new ConnectorChangedSnapshot(
                connectorCode, cmd.name(), ConnectorStatus.ACTIVE.name());
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

        // update 不改 status，前后快照都带当前状态以保持字段完整
        String status = existing.getStatus().name();
        ConnectorChangedSnapshot before = new ConnectorChangedSnapshot(connectorCode, existing.getName(), status);
        existing.setName(cmd.name());
        existing.setDescriptor(cmd.descriptor());
        existing.setUpdatedBy(actorId);
        existing.setUpdatedAt(LocalDateTime.now());
        int n = mapper.updateById(existing);

        publishAudit(tenantId, actorId, "UPDATE", existing.getId(), before,
                new ConnectorChangedSnapshot(connectorCode, cmd.name(), status));
        eventPublisher.publishEvent(new ConnectorChangedEvent(String.valueOf(tenantId), connectorCode));
        return n;
    }

    @Override
    public void disable(Long tenantId, String connectorCode, String actorId) {
        ConnectorDefinition existing = mapper.findByCode(tenantId, connectorCode);
        if (existing == null) {
            throw new IllegalArgumentException("连接器不存在: " + connectorCode);
        }

        // before 在置 DISABLED 前取当前状态（ACTIVE），after 为 DISABLED，使审计能还原状态变迁
        ConnectorChangedSnapshot before = new ConnectorChangedSnapshot(
                connectorCode, existing.getName(), existing.getStatus().name());
        existing.setStatus(ConnectorStatus.DISABLED);
        existing.setUpdatedBy(actorId);
        existing.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(existing);

        publishAudit(tenantId, actorId, "DISABLE", existing.getId(), before,
                new ConnectorChangedSnapshot(connectorCode, existing.getName(), ConnectorStatus.DISABLED.name()));
        eventPublisher.publishEvent(new ConnectorChangedEvent(String.valueOf(tenantId), connectorCode));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ConnectorDefinition> listPage(ConnectorListQuery q) {
        Long tid = (q.tenantId() != null && !q.tenantId().isBlank()) ? Long.valueOf(q.tenantId()) : null;
        return mapper.searchPage(new Page<>(q.page(), q.size()), tid, q.keyword(), q.status());
    }

    @Override
    @Transactional(readOnly = true)
    public List<ConnectorView> listActive(Long tenantId) {
        // tenantId 为 null 时返回全部租户的 ACTIVE 连接器
        List<ConnectorDefinition> rows = tenantId == null
                ? mapper.findAllActive()
                : mapper.findActiveByTenant(tenantId);
        return rows.stream()
                .map(c -> new ConnectorView(c.getTenantId(), c.getConnectorCode(), c.getName(), c.getStatus().name(),
                        c.getCreatedAt() != null ? c.getCreatedAt().toString() : null,
                        c.getUpdatedAt() != null ? c.getUpdatedAt().toString() : null))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public ConnectorDetailView getByCode(Long tenantId, String connectorCode) {
        ConnectorDefinition c = mapper.findByCode(tenantId, connectorCode);
        if (c == null) {
            throw new IllegalArgumentException("连接器不存在: " + connectorCode);
        }
        return new ConnectorDetailView(c.getConnectorCode(), c.getName(),
                c.getDescriptor(), c.getStatus().name(),
                c.getCreatedAt() != null ? c.getCreatedAt().toString() : null,
                c.getUpdatedAt() != null ? c.getUpdatedAt().toString() : null);
    }

    /** 发布操作审计事件，由集中监听器 BEFORE_COMMIT 同事务落 audit_log（D14 约定）。 */
    private void publishAudit(Long tenantId, String actorId, String action, Long id,
                              ConnectorChangedSnapshot before, ConnectorChangedSnapshot after) {
        eventPublisher.publishEvent(new OperationAuditedEvent(
                tenantId, actorId, "USER", action, "connector_definition", String.valueOf(id),
                before, after, LocalDateTime.now()));
    }
}
