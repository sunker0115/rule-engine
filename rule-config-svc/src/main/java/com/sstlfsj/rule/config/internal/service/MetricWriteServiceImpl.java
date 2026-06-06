package com.sstlfsj.rule.config.internal.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sstlfsj.rule.config.api.service.MetricWriteService;
import com.sstlfsj.rule.config.internal.domain.AuditLog;
import com.sstlfsj.rule.config.internal.domain.MetricDefinition;
import com.sstlfsj.rule.config.internal.repository.AuditLogMapper;
import com.sstlfsj.rule.config.internal.repository.MetricDefinitionMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * MetricWriteService 实现：create 注册 v1；update 带 breakingChange——
 * true 触发升版（旧 ACTIVE→SUPERSEDED + 插新版本行，同事务保证至多一行 ACTIVE），false 原地更新。
 */
@Service
@Transactional
public class MetricWriteServiceImpl implements MetricWriteService {

    private final MetricDefinitionMapper metricDefinitionMapper;
    private final AuditLogMapper auditLogMapper;

    public MetricWriteServiceImpl(MetricDefinitionMapper metricDefinitionMapper,
                                  AuditLogMapper auditLogMapper) {
        this.metricDefinitionMapper = metricDefinitionMapper;
        this.auditLogMapper = auditLogMapper;
    }

    @Override
    public Long create(Long tenantId, String metricCode, MetricWriteCommand cmd, String actorId) {
        MetricDefinition m = new MetricDefinition();
        m.setTenantId(tenantId);
        m.setMetricCode(metricCode);
        m.setVersion(1);
        applyCommandFields(m, cmd);
        m.setStatus("ACTIVE");
        m.setCreatedBy(actorId);
        m.setCreatedAt(LocalDateTime.now());
        metricDefinitionMapper.insert(m);

        writeAudit(tenantId, actorId, "CREATE", m.getId().toString(),
                "{\"metricCode\":\"" + metricCode + "\",\"version\":1}");
        return m.getId();
    }

    @Override
    public int update(Long tenantId, String metricCode, MetricWriteCommand cmd,
                      boolean breakingChange, String actorId) {
        MetricDefinition active = metricDefinitionMapper.selectOne(
                new LambdaQueryWrapper<MetricDefinition>()
                        .eq(MetricDefinition::getTenantId, tenantId)
                        .eq(MetricDefinition::getMetricCode, metricCode)
                        .eq(MetricDefinition::getStatus, "ACTIVE"));
        if (active == null) {
            throw new IllegalArgumentException("metric 不存在或无 ACTIVE 版本: " + metricCode);
        }

        if (!breakingChange) {
            // 原地更新，version 不变
            applyCommandFields(active, cmd);
            active.setUpdatedBy(actorId);
            active.setUpdatedAt(LocalDateTime.now());
            metricDefinitionMapper.updateById(active);

            writeAudit(tenantId, actorId, "UPDATE", active.getId().toString(),
                    "{\"metricCode\":\"" + metricCode + "\",\"version\":"
                    + active.getVersion() + ",\"breaking\":false}");
            return active.getVersion();
        }

        // breakingChange=true：旧行 SUPERSEDED + 插入新版本行
        int newVersion = active.getVersion() + 1;
        active.setStatus("SUPERSEDED");
        active.setUpdatedBy(actorId);
        active.setUpdatedAt(LocalDateTime.now());
        metricDefinitionMapper.updateById(active);

        MetricDefinition next = new MetricDefinition();
        next.setTenantId(tenantId);
        next.setMetricCode(metricCode);
        next.setVersion(newVersion);
        applyCommandFields(next, cmd);
        next.setStatus("ACTIVE");
        next.setCreatedBy(actorId);
        next.setCreatedAt(LocalDateTime.now());
        metricDefinitionMapper.insert(next);

        writeAudit(tenantId, actorId, "UPDATE", next.getId().toString(),
                "{\"metricCode\":\"" + metricCode + "\",\"version\":"
                + newVersion + ",\"breaking\":true}");
        return newVersion;
    }

    /** 将 MetricWriteCommand 字段批量 set 到 MetricDefinition，含 null 默认值处理。 */
    private void applyCommandFields(MetricDefinition m, MetricWriteCommand cmd) {
        m.setName(cmd.name());
        m.setSourceType(cmd.sourceType());
        m.setDataType(cmd.dataType());
        m.setParams(cmd.paramsJson() == null ? "{}" : cmd.paramsJson());
        m.setCacheTtlSeconds(cmd.cacheTtlSeconds() == null ? 60 : cmd.cacheTtlSeconds());
        m.setAllowProvided(cmd.allowProvided());
    }

    /** 写入 audit_log，同事务（D14 约定）。 */
    private void writeAudit(Long tenantId, String actorId, String action,
                            String targetId, String afterSnapshot) {
        AuditLog log = new AuditLog();
        log.setTenantId(tenantId);
        log.setActor(actorId);
        log.setActorType("USER");
        log.setAction(action);
        log.setTargetType("metric_definition");
        log.setTargetId(targetId);
        log.setAfterSnapshot(afterSnapshot);
        log.setOperatedAt(LocalDateTime.now());
        auditLogMapper.insert(log);
    }
}
