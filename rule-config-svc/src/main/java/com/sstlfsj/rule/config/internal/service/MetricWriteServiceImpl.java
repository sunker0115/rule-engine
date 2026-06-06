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
        m.setName(cmd.name());
        m.setSourceType(cmd.sourceType());
        m.setDataType(cmd.dataType());
        m.setParams(cmd.paramsJson() == null ? "{}" : cmd.paramsJson());
        m.setCacheTtlSeconds(cmd.cacheTtlSeconds() == null ? 60 : cmd.cacheTtlSeconds());
        m.setAllowProvided(cmd.allowProvided());
        m.setStatus("ACTIVE");
        m.setCreatedBy(actorId);
        m.setCreatedAt(LocalDateTime.now());
        metricDefinitionMapper.insert(m);

        // 审计写入，参照 PublishService 风格（内联构造，同事务 D14）
        AuditLog log = new AuditLog();
        log.setTenantId(tenantId);
        log.setActor(actorId);
        log.setActorType("USER");
        log.setAction("CREATE");
        log.setTargetType("metric_definition");
        log.setTargetId(m.getId().toString());
        log.setAfterSnapshot("{\"metricCode\":\"" + metricCode + "\",\"version\":1}");
        log.setOperatedAt(LocalDateTime.now());
        auditLogMapper.insert(log);

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
            active.setName(cmd.name());
            active.setSourceType(cmd.sourceType());
            active.setDataType(cmd.dataType());
            active.setParams(cmd.paramsJson() == null ? "{}" : cmd.paramsJson());
            active.setCacheTtlSeconds(cmd.cacheTtlSeconds() == null ? 60 : cmd.cacheTtlSeconds());
            active.setAllowProvided(cmd.allowProvided());
            active.setUpdatedBy(actorId);
            active.setUpdatedAt(LocalDateTime.now());
            metricDefinitionMapper.updateById(active);

            AuditLog log = new AuditLog();
            log.setTenantId(tenantId);
            log.setActor(actorId);
            log.setActorType("USER");
            log.setAction("UPDATE");
            log.setTargetType("metric_definition");
            log.setTargetId(active.getId().toString());
            log.setAfterSnapshot("{\"metricCode\":\"" + metricCode + "\",\"version\":"
                    + active.getVersion() + ",\"breaking\":false}");
            log.setOperatedAt(LocalDateTime.now());
            auditLogMapper.insert(log);

            return active.getVersion() == null ? 1 : active.getVersion();
        }

        // breakingChange=true：旧行 SUPERSEDED + 插入新版本行
        int newVersion = (active.getVersion() == null ? 1 : active.getVersion()) + 1;
        active.setStatus("SUPERSEDED");
        active.setUpdatedBy(actorId);
        active.setUpdatedAt(LocalDateTime.now());
        metricDefinitionMapper.updateById(active);

        MetricDefinition next = new MetricDefinition();
        next.setTenantId(tenantId);
        next.setMetricCode(metricCode);
        next.setVersion(newVersion);
        next.setName(cmd.name());
        next.setSourceType(cmd.sourceType());
        next.setDataType(cmd.dataType());
        next.setParams(cmd.paramsJson() == null ? "{}" : cmd.paramsJson());
        next.setCacheTtlSeconds(cmd.cacheTtlSeconds() == null ? 60 : cmd.cacheTtlSeconds());
        next.setAllowProvided(cmd.allowProvided());
        next.setStatus("ACTIVE");
        next.setCreatedBy(actorId);
        next.setCreatedAt(LocalDateTime.now());
        metricDefinitionMapper.insert(next);

        AuditLog log = new AuditLog();
        log.setTenantId(tenantId);
        log.setActor(actorId);
        log.setActorType("USER");
        log.setAction("UPDATE");
        log.setTargetType("metric_definition");
        log.setTargetId(next.getId().toString());
        log.setAfterSnapshot("{\"metricCode\":\"" + metricCode + "\",\"version\":"
                + newVersion + ",\"breaking\":true}");
        log.setOperatedAt(LocalDateTime.now());
        auditLogMapper.insert(log);

        return newVersion;
    }
}
