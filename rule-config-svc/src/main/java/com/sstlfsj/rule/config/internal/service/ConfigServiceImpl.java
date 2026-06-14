package com.sstlfsj.rule.config.internal.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sstlfsj.rule.config.api.dto.DraftCreatedResult;
import com.sstlfsj.rule.config.api.dto.RuleDetailVO;
import com.sstlfsj.rule.config.api.dto.RuleListItemVO;
import com.sstlfsj.rule.config.api.dto.RuleListQuery;
import com.sstlfsj.rule.config.api.dto.TenantItemVO;
import com.sstlfsj.rule.config.api.service.ConfigService;
import com.sstlfsj.rule.config.internal.domain.RuleDefinition;
import com.sstlfsj.rule.config.internal.domain.RuleDefinitionStatus;
import com.sstlfsj.rule.config.internal.domain.RuleVersion;
import com.sstlfsj.rule.config.internal.domain.SceneDef;
import com.sstlfsj.rule.config.internal.domain.Tenant;
import com.sstlfsj.rule.config.internal.domain.TenantStatus;
import com.sstlfsj.rule.config.internal.event.OperationAuditedEvent;
import com.sstlfsj.rule.config.internal.event.RuleStatusSnapshot;
import com.sstlfsj.rule.config.internal.publish.PublishService;
import com.sstlfsj.rule.config.internal.repository.RuleDefinitionMapper;
import com.sstlfsj.rule.config.internal.repository.RuleVersionMapper;
import com.sstlfsj.rule.config.internal.repository.SceneMapper;
import com.sstlfsj.rule.config.internal.repository.TenantMapper;
import com.sstlfsj.rule.kernel.api.model.RuleKind;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot.DecisionBinding;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot.PreGateConfig;
import com.sstlfsj.rule.kernel.api.model.ScriptSource;
import com.sstlfsj.rule.kernel.api.model.ast.AstNode;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** ConfigService 实现，委托 PublishService 执行发布流程。 */
@Service
@RequiredArgsConstructor
class ConfigServiceImpl implements ConfigService {

    private final PublishService publishService;
    private final RuleDefinitionMapper ruleDefinitionMapper;
    private final SceneMapper sceneMapper;
    private final RuleVersionMapper ruleVersionMapper;
    private final TenantMapper tenantMapper;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    public RuleVersionSnapshot publish(String tenantId, Long ruleDefinitionId, String actorId) {
        return publishService.publish(Long.valueOf(tenantId), ruleDefinitionId, actorId);
    }

    @Override
    @Transactional
    public void disable(String tenantId, Long ruleDefinitionId, String actorId) {
        RuleDefinition rule = ruleDefinitionMapper.selectById(ruleDefinitionId);
        if (rule == null || !tenantId.equals(String.valueOf(rule.getTenantId()))) {
            throw new IllegalArgumentException("规则不存在: id=" + ruleDefinitionId);
        }
        // 捕获禁用前状态作为 before 快照（D14 审计完整性，能还原"禁用前规则是什么状态"）
        RuleStatusSnapshot before = new RuleStatusSnapshot(
                ruleDefinitionId, rule.getStatus().name(), rule.getCurrentVersion());
        rule.setStatus(RuleDefinitionStatus.DISABLED);
        ruleDefinitionMapper.updateById(rule);
        RuleStatusSnapshot after = new RuleStatusSnapshot(
                ruleDefinitionId, RuleDefinitionStatus.DISABLED.name(), rule.getCurrentVersion());

        eventPublisher.publishEvent(new OperationAuditedEvent(
                Long.valueOf(tenantId), actorId, "USER", "DISABLE", "rule_definition",
                ruleDefinitionId.toString(), before, after, LocalDateTime.now()));
    }

    @Override
    public Page<RuleDefinition> listRules(RuleListQuery q) {
        Long sceneId = null;
        if (q.sceneCode() != null && !q.sceneCode().isBlank()) {
            SceneDef scene = sceneMapper.findByCode(Long.valueOf(q.tenantId()), q.sceneCode());
            if (scene == null) {
                return new Page<>(q.page(), q.size());
            }
            sceneId = scene.getId();
        }

        LocalDate fromDate = q.from() != null && !q.from().isBlank()
                ? LocalDate.parse(q.from()) : null;
        LocalDate toDate = q.to() != null && !q.to().isBlank()
                ? LocalDate.parse(q.to()) : null;

        return ruleDefinitionMapper.selectRulePage(
                new Page<>(q.page(), q.size()), Long.valueOf(q.tenantId()), sceneId, q.status(), fromDate, toDate);
    }

    @Override
    public Map<Long, String> getSceneCodeMap(Set<Long> sceneIds) {
        if (sceneIds == null || sceneIds.isEmpty()) return Collections.emptyMap();
        return sceneMapper.selectBatchIds(sceneIds).stream()
                .collect(Collectors.toMap(SceneDef::getId, SceneDef::getCode));
    }

    @Override
    public RuleDetailVO getRuleDetail(String tenantId, Long ruleId) {
        RuleDefinition rule = ruleDefinitionMapper.selectById(ruleId);
        if (rule == null || !tenantId.equals(String.valueOf(rule.getTenantId()))) {
            throw new IllegalArgumentException("规则不存在: id=" + ruleId);
        }
        SceneDef scene = sceneMapper.selectById(rule.getSceneId());
        RuleVersion active = ruleVersionMapper.findActiveVersion(ruleId);
        return new RuleDetailVO(
                rule.getId(), rule.getCode(), rule.getName(), rule.getStatus().name(),
                rule.getKind() != null ? rule.getKind().name() : null,
                scene != null ? scene.getCode() : null,
                active != null ? active.getConditionAst() : null,
                active != null ? active.getDecisionBindings() : null,
                active != null ? active.getId() : null);
    }

    @Override
    public DraftCreatedResult createDraft(String tenantId, String sceneCode,
            String code, String name,
            AstNode conditionAst, List<DecisionBinding> decisionBindings,
            List<PreGateConfig> preGates, List<String> triggerEventTypes,
            String kind, ScriptSource script, String actorId) {
        return publishService.createDraft(Long.valueOf(tenantId), sceneCode,
                code, name,
                conditionAst, decisionBindings,
                preGates, triggerEventTypes,
                kind, script, actorId);
    }

    @Override
    public DraftCreatedResult editDraft(String tenantId, Long ruleId, String name, String kind,
            AstNode conditionAst, List<DecisionBinding> decisionBindings,
            List<PreGateConfig> preGates, List<String> triggerEventTypes,
            ScriptSource script, String actorId) {
        return publishService.editDraft(Long.valueOf(tenantId), ruleId, name, parseKind(kind),
                conditionAst, decisionBindings, preGates, triggerEventTypes, script, actorId);
    }

    @Override
    public DraftCreatedResult newVersion(String tenantId, Long ruleId, String name, String kind,
            AstNode conditionAst, List<DecisionBinding> decisionBindings,
            List<PreGateConfig> preGates, List<String> triggerEventTypes,
            Long fromVersionId, ScriptSource script, String actorId) {
        return publishService.newVersion(Long.valueOf(tenantId), ruleId, name, parseKind(kind),
                conditionAst, decisionBindings, preGates, triggerEventTypes, fromVersionId, script, actorId);
    }

    @Override
    public void deleteRule(String tenantId, Long ruleId, String actorId) {
        publishService.deleteRule(Long.valueOf(tenantId), ruleId, actorId);
    }

    @Override
    public void deleteDraftVersion(String tenantId, Long ruleId, Long versionId, String actorId) {
        publishService.deleteDraftVersion(Long.valueOf(tenantId), ruleId, versionId, actorId);
    }

    @Override
    public List<TenantItemVO> listTenants(String keyword, String status) {
        LambdaQueryWrapper<Tenant> qw = new LambdaQueryWrapper<>();
        if (status != null && !status.isBlank()) {
            qw.eq(Tenant::getStatus, TenantStatus.valueOf(status));
        } else {
            qw.eq(Tenant::getStatus, TenantStatus.ACTIVE);
        }
        if (keyword != null && !keyword.isBlank()) {
            qw.and(w -> w.like(Tenant::getCode, keyword).or().like(Tenant::getName, keyword));
        }
        return tenantMapper.selectList(qw).stream()
                .map(t -> new TenantItemVO(t.getId(), t.getCode(), t.getName(), t.getStatus().name()))
                .toList();
    }

    @Override
    public void toggleTenantStatus(Long tenantId, boolean enable) {
        Tenant t = tenantMapper.selectById(tenantId);
        if (t == null) throw new IllegalArgumentException("租户不存在: " + tenantId);
        t.setStatus(enable ? TenantStatus.ACTIVE : TenantStatus.DISABLED);
        tenantMapper.updateById(t);
    }

    /** 解析 kind 字符串为 RuleKind，null/空返回 null（由下游兜底 AST_BOOLEAN），非法抛 IllegalArgumentException。 */
    private static RuleKind parseKind(String kind) {
        if (kind == null || kind.isBlank()) {
            return null;
        }
        try {
            return RuleKind.valueOf(kind);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("不支持的规则 kind: " + kind);
        }
    }
}
