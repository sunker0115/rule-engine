package com.sstlfsj.rule.config.internal.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.sstlfsj.rule.config.api.service.MetricWriteService;
import com.sstlfsj.rule.config.internal.domain.AuditLog;
import com.sstlfsj.rule.config.internal.domain.MetricDefinition;
import com.sstlfsj.rule.config.internal.domain.RuleDefinition;
import com.sstlfsj.rule.config.internal.domain.RuleVersion;
import com.sstlfsj.rule.config.internal.repository.AuditLogMapper;
import com.sstlfsj.rule.config.internal.repository.MetricDefinitionMapper;
import com.sstlfsj.rule.config.internal.repository.RuleDefinitionMapper;
import com.sstlfsj.rule.config.internal.repository.RuleVersionMapper;
import com.sstlfsj.rule.kernel.api.model.MetricDependency;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * MetricWriteService 实现：create 注册 v1；update 带 breakingChange——
 * true 触发升版（旧 ACTIVE→SUPERSEDED + 插新版本行，同事务保证至多一行 ACTIVE），false 原地更新。
 * findReferencingRules 扫描所有 ACTIVE rule_version 的 metric_dependencies，返回引用指定版本的规则。
 */
@Service
@Transactional
public class MetricWriteServiceImpl implements MetricWriteService {

    private static final TypeReference<List<MetricDependency>> METRIC_DEP_TYPE =
            new TypeReference<>() {};

    private final MetricDefinitionMapper metricDefinitionMapper;
    private final AuditLogMapper auditLogMapper;
    private final RuleVersionMapper ruleVersionMapper;
    private final RuleDefinitionMapper ruleDefinitionMapper;
    private final ObjectMapper objectMapper;

    public MetricWriteServiceImpl(MetricDefinitionMapper metricDefinitionMapper,
                                  AuditLogMapper auditLogMapper,
                                  RuleVersionMapper ruleVersionMapper,
                                  RuleDefinitionMapper ruleDefinitionMapper,
                                  ObjectMapper objectMapper) {
        this.metricDefinitionMapper = metricDefinitionMapper;
        this.auditLogMapper = auditLogMapper;
        this.ruleVersionMapper = ruleVersionMapper;
        this.ruleDefinitionMapper = ruleDefinitionMapper;
        this.objectMapper = objectMapper;
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

    @Override
    @Transactional(readOnly = true)
    public List<RuleRef> findReferencingRules(Long tenantId, String metricCode, int metricVersion) {
        // 第一步：查该 tenant 下所有 rule_definition，建 id → (code, name) 索引
        List<RuleDefinition> defs = ruleDefinitionMapper.selectList(
                new LambdaQueryWrapper<RuleDefinition>()
                        .eq(RuleDefinition::getTenantId, tenantId)
                        .select(RuleDefinition::getId, RuleDefinition::getCode, RuleDefinition::getName));
        if (defs.isEmpty()) {
            return List.of();
        }
        Map<Long, RuleDefinition> defMap = defs.stream()
                .collect(Collectors.toMap(RuleDefinition::getId, d -> d));

        // 第二步：查这批 rule_definition_id 下所有 ACTIVE rule_version
        List<RuleVersion> activeVersions = ruleVersionMapper.selectList(
                new LambdaQueryWrapper<RuleVersion>()
                        .in(RuleVersion::getRuleDefinitionId, defMap.keySet())
                        .eq(RuleVersion::getStatus, "ACTIVE"));

        // 第三步：反序列化 metric_dependencies，筛出含目标 (metricCode, metricVersion) 的行
        List<RuleRef> result = new ArrayList<>();
        for (RuleVersion rv : activeVersions) {
            if (containsDependency(rv.getMetricDependencies(), metricCode, metricVersion)) {
                RuleDefinition def = defMap.get(rv.getRuleDefinitionId());
                result.add(new RuleRef(def.getId(), def.getCode(), def.getName(), rv.getId()));
            }
        }
        return result;
    }

    /**
     * 判断 metric_dependencies JSON 数组是否包含指定 (metricCode, metricVersion)。
     * 反序列化失败（如 null 或格式异常）视为不包含。
     */
    private boolean containsDependency(String metricDependenciesJson,
                                       String metricCode, int metricVersion) {
        if (metricDependenciesJson == null || metricDependenciesJson.isBlank()) {
            return false;
        }
        try {
            List<MetricDependency> deps = objectMapper.readValue(metricDependenciesJson, METRIC_DEP_TYPE);
            return deps.stream().anyMatch(
                    d -> metricCode.equals(d.metricCode()) && d.metricVersion() == metricVersion);
        } catch (Exception e) {
            return false;
        }
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
