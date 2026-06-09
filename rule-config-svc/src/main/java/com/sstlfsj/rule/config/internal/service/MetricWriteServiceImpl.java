package com.sstlfsj.rule.config.internal.service;

import lombok.RequiredArgsConstructor;
import com.sstlfsj.rule.config.api.service.MetricWriteService;
import com.sstlfsj.rule.config.internal.MetricProperties;
import com.sstlfsj.rule.config.internal.domain.MetricDefinition;
import com.sstlfsj.rule.config.internal.domain.MetricEnums;
import com.sstlfsj.rule.config.internal.domain.RuleDefinition;
import com.sstlfsj.rule.config.internal.domain.RuleVersion;
import com.sstlfsj.rule.config.internal.domain.SceneDef;
import com.sstlfsj.rule.config.internal.event.OperationAuditedEvent;
import com.sstlfsj.rule.config.internal.repository.MetricDefinitionMapper;
import com.sstlfsj.rule.config.internal.repository.RuleDefinitionMapper;
import com.sstlfsj.rule.config.internal.repository.RuleVersionMapper;
import com.sstlfsj.rule.config.internal.repository.SceneMapper;
import com.sstlfsj.rule.kernel.api.model.MetricDependency;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * MetricWriteService 实现：create 注册 v1；update 带 breakingChange——
 * true 触发升版（旧 ACTIVE→SUPERSEDED + 插新版本行，同事务保证至多一行 ACTIVE），false 原地更新。
 * findReferencingRules 扫描所有 ACTIVE rule_version 的 metric_dependencies，返回引用指定版本的规则。
 */
@Service
@Transactional
@RequiredArgsConstructor
public class MetricWriteServiceImpl implements MetricWriteService {

    private final MetricDefinitionMapper metricDefinitionMapper;
    private final RuleVersionMapper ruleVersionMapper;
    private final RuleDefinitionMapper ruleDefinitionMapper;
    private final SceneMapper sceneMapper;
    private final MetricProperties metricProperties;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    public Long create(Long tenantId, String metricCode, MetricWriteCommand cmd, String actorId) {
        validateEnums(cmd);
        MetricDefinition m = new MetricDefinition();
        m.setTenantId(tenantId);
        m.setMetricCode(metricCode);
        m.setVersion(1);
        applyCommandFields(m, cmd);
        m.setStatus("ACTIVE");
        m.setCreatedBy(actorId);
        m.setCreatedAt(LocalDateTime.now());
        metricDefinitionMapper.insert(m);

        publishAudit(tenantId, actorId, "CREATE", m.getId().toString(),
                "{\"metricCode\":\"" + metricCode + "\",\"version\":1}");
        return m.getId();
    }

    @Override
    public int update(Long tenantId, String metricCode, MetricWriteCommand cmd,
                      boolean breakingChange, String actorId) {
        validateEnums(cmd);
        MetricDefinition active = metricDefinitionMapper.findActiveByCode(tenantId, metricCode);
        if (active == null) {
            throw new IllegalArgumentException("metric 不存在或无 ACTIVE 版本: " + metricCode);
        }

        // sourceType/dataType 冻结进 AST 快照并影响取数语义，变更必须升版（D6/B6），
        // 否则存量规则评估期 resolve 到被静默修改的定义。
        boolean effectiveBreaking = breakingChange
                || !Objects.equals(active.getSourceType(), cmd.sourceType())
                || !Objects.equals(active.getDataType(), cmd.dataType());

        if (!effectiveBreaking) {
            // 原地更新，version 不变
            applyCommandFields(active, cmd);
            active.setUpdatedBy(actorId);
            active.setUpdatedAt(LocalDateTime.now());
            metricDefinitionMapper.updateById(active);

            publishAudit(tenantId, actorId, "UPDATE", active.getId().toString(),
                    "{\"metricCode\":\"" + metricCode + "\",\"version\":"
                    + active.getVersion() + ",\"breaking\":false}");
            return active.getVersion();
        }

        // effectiveBreaking=true：旧行 SUPERSEDED + 插入新版本行
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

        publishAudit(tenantId, actorId, "UPDATE", next.getId().toString(),
                "{\"metricCode\":\"" + metricCode + "\",\"version\":"
                + newVersion + ",\"breaking\":true}");
        return newVersion;
    }

    @Override
    @Transactional(readOnly = true)
    public List<RuleRef> findReferencingRules(Long tenantId, String metricCode, int metricVersion) {
        // 第一步：查该 tenant 下所有 rule_definition，取 id/code/name/sceneId/status
        List<RuleDefinition> defs = ruleDefinitionMapper.findByTenant(tenantId);
        if (defs.isEmpty()) {
            return List.of();
        }
        Map<Long, RuleDefinition> defMap = defs.stream()
                .collect(Collectors.toMap(RuleDefinition::getId, d -> d));

        // 第二步：批量查 scene，建 sceneId → sceneCode 索引
        Set<Long> sceneIds = defs.stream()
                .map(RuleDefinition::getSceneId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, String> sceneCodeMap = sceneIds.isEmpty() ? Map.of() :
                sceneMapper.findByIds(sceneIds)
                        .stream()
                        .collect(Collectors.toMap(SceneDef::getId, SceneDef::getCode));

        // 第三步：查这批 rule_definition_id 下所有 ACTIVE rule_version，只取影响面判断所需列
        // 口径对齐 eval 侧 RuleVersionReadMapper：以 rv.status=ACTIVE 为"参与评估"判定，
        // 不按 rule_definition.status 过滤（eval 的 loadAllActive/loadActiveByScene 均如此）。
        List<RuleVersion> activeVersions = ruleVersionMapper.findActiveByRuleDefIds(defMap.keySet());

        // 第四步：反序列化 metric_dependencies，筛出含目标 (metricCode, metricVersion) 的行
        List<RuleRef> result = new ArrayList<>();
        for (RuleVersion rv : activeVersions) {
            if (containsDependency(rv.getMetricDependencies(), metricCode, metricVersion)) {
                RuleDefinition def = defMap.get(rv.getRuleDefinitionId());
                String sceneCode = sceneCodeMap.getOrDefault(def.getSceneId(), "");
                result.add(new RuleRef(def.getId(), def.getCode(), def.getName(),
                        sceneCode, def.getStatus()));
            }
        }
        return result;
    }

    /** 判断 typed metricDependencies 列表是否包含指定 (metricCode, metricVersion)；null 视为不包含。 */
    private boolean containsDependency(List<MetricDependency> deps,
                                       String metricCode, int metricVersion) {
        if (deps == null || deps.isEmpty()) {
            return false;
        }
        return deps.stream().anyMatch(
                d -> metricCode.equals(d.metricCode()) && d.metricVersion() == metricVersion);
    }

    /**
     * 校验 cmd 的枚举列取值（DB ENUM 去除后由 app 兜底，单一真相源 {@link MetricEnums}）。
     * status 不校验：写路径恒由服务端内部置为 ACTIVE/SUPERSEDED，从不取自 cmd。
     */
    private void validateEnums(MetricWriteCommand cmd) {
        if (!MetricEnums.DATA_TYPES.contains(cmd.dataType())) {
            throw new IllegalArgumentException("非法 data_type: " + cmd.dataType());
        }
        if (!MetricEnums.SOURCE_TYPES.contains(cmd.sourceType())) {
            throw new IllegalArgumentException("非法 source_type: " + cmd.sourceType());
        }
    }

    /** 将 MetricWriteCommand 字段批量 set 到 MetricDefinition，含 null 默认值处理。 */
    private void applyCommandFields(MetricDefinition m, MetricWriteCommand cmd) {
        m.setName(cmd.name());
        m.setSourceType(cmd.sourceType());
        m.setDataType(cmd.dataType());
        m.setParams(cmd.params() != null ? cmd.params() : Map.of());
        m.setCacheTtlSeconds(cmd.cacheTtlSeconds() == null
                ? metricProperties.getDefaultCacheTtlSeconds() : cmd.cacheTtlSeconds());
        m.setAllowProvided(cmd.allowProvided());
    }

    /** 发布操作审计事件，由集中监听器 BEFORE_COMMIT 同事务落 audit_log（D14 约定）。 */
    private void publishAudit(Long tenantId, String actorId, String action,
                            String targetId, String afterSnapshot) {
        eventPublisher.publishEvent(new OperationAuditedEvent(
                tenantId, actorId, "USER", action, "metric_definition", targetId,
                null, afterSnapshot, LocalDateTime.now()));
    }
}
