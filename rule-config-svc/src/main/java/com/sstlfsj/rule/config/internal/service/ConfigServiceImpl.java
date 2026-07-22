package com.sstlfsj.rule.config.internal.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sstlfsj.rule.config.api.dto.DraftCreatedResult;
import com.sstlfsj.rule.config.api.dto.RuleContent;
import com.sstlfsj.rule.config.api.dto.RuleDetailVO;
import com.sstlfsj.rule.config.api.dto.RuleListItemVO;
import com.sstlfsj.rule.config.api.dto.RuleListQuery;
import com.sstlfsj.rule.config.api.dto.RuleVersionContentVO;
import com.sstlfsj.rule.config.api.dto.TenantItemVO;
import com.sstlfsj.rule.config.api.event.RulePublishedEvent;
import com.sstlfsj.rule.config.api.service.ConfigService;
import com.sstlfsj.rule.config.internal.domain.ActorType;
import com.sstlfsj.rule.config.internal.domain.AuditAction;
import com.sstlfsj.rule.config.internal.domain.AuditTargetType;
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
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
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
    public RuleVersionSnapshot publish(Long tenantId, Long ruleDefinitionId, String actorId) {
        return publishService.publish(tenantId, ruleDefinitionId, actorId);
    }

    @Override
    @Transactional
    public void disable(Long tenantId, Long ruleDefinitionId, String actorId) {
        // 关停：PUBLISHED → DISABLED，单向。
        transitionStatus(tenantId, ruleDefinitionId, actorId,
                RuleDefinitionStatus.PUBLISHED, RuleDefinitionStatus.DISABLED, AuditAction.DISABLE, "禁用");
    }

    @Override
    @Transactional
    public void enable(Long tenantId, Long ruleDefinitionId, String actorId) {
        // 重新启用：DISABLED → PUBLISHED，单向。
        transitionStatus(tenantId, ruleDefinitionId, actorId,
                RuleDefinitionStatus.DISABLED, RuleDefinitionStatus.PUBLISHED, AuditAction.ENABLE, "启用");
    }

    /**
     * 规则启停状态单向迁移（D19 DISABLED↔PUBLISHED 解耦切换）：仅当规则处于 {@code from} 态才迁到 {@code to}，
     * 其它态（DRAFT 或已是目标态）一律拒绝，并落一条对应 action 的审计事件。
     * 严格校验源态杜绝"未发布规则被 disable 再 enable 成无 current_version 的脏 PUBLISHED"。
     *
     * @param from   要求的源状态
     * @param to     迁移后的目标状态
     * @param action 审计动作（ENABLE / DISABLE）
     * @param verb   面向用户错误信息中的动词（启用 / 禁用）
     */
    private void transitionStatus(Long tenantId, Long ruleDefinitionId, String actorId,
            RuleDefinitionStatus from, RuleDefinitionStatus to, AuditAction action, String verb) {
        RuleDefinition rule = ruleDefinitionMapper.selectById(ruleDefinitionId);
        if (rule == null || !tenantId.equals(rule.getTenantId())) {
            throw new IllegalArgumentException("规则不存在: id=" + ruleDefinitionId);
        }
        if (rule.getStatus() != from) {
            throw new IllegalArgumentException(
                    "仅 " + from + " 规则可" + verb + "，当前状态: " + rule.getStatus());
        }
        RuleStatusSnapshot before = new RuleStatusSnapshot(
                ruleDefinitionId, rule.getStatus().name(), rule.getCurrentVersion());

        rule.setStatus(to);
        ruleDefinitionMapper.updateById(rule);

        RuleStatusSnapshot after = new RuleStatusSnapshot(
                ruleDefinitionId, rule.getStatus().name(), rule.getCurrentVersion());
        eventPublisher.publishEvent(new OperationAuditedEvent(
                tenantId, actorId, ActorType.USER, action, AuditTargetType.RULE_DEFINITION,
                ruleDefinitionId.toString(), before, after, LocalDateTime.now()));

        // 状态变更须刷新 eval 索引:disable→loader 按 rd.status='PUBLISHED' 过滤摘除、enable→装回。
        // 复用 RulePublishedEvent（提交后异步，Modulith）触发该 scene 索引重建 + 编译缓存清除。
        SceneDef scene = sceneMapper.selectById(rule.getSceneId());
        if (scene != null) {
            eventPublisher.publishEvent(new RulePublishedEvent(
                    String.valueOf(tenantId), scene.getCode(), rule.getCurrentVersion()));
        }
    }

    @Override
    public Page<RuleDefinition> listRules(RuleListQuery q) {
        Long sceneId = null;
        if (q.sceneCode() != null && !q.sceneCode().isBlank()) {
            SceneDef scene = sceneMapper.findByCode(q.tenantId(), q.sceneCode());
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
                new Page<>(q.page(), q.size()), q.tenantId(), sceneId, q.status(), fromDate, toDate);
    }

    @Override
    public Map<Long, String> getSceneCodeMap(Set<Long> sceneIds) {
        if (sceneIds == null || sceneIds.isEmpty()) return Collections.emptyMap();
        return sceneMapper.selectBatchIds(sceneIds).stream()
                .collect(Collectors.toMap(SceneDef::getId, SceneDef::getCode));
    }

    @Override
    public RuleDetailVO getRuleDetail(Long tenantId, Long ruleId) {
        RuleDefinition rule = ruleDefinitionMapper.selectById(ruleId);
        if (rule == null || !tenantId.equals(rule.getTenantId())) {
            throw new IllegalArgumentException("规则不存在: id=" + ruleId);
        }
        SceneDef scene = sceneMapper.selectById(rule.getSceneId());

        // 按规则状态取对应版本：DRAFT → DRAFT 版本，PUBLISHED/DISABLED → ACTIVE 版本
        RuleVersion current;
        if (rule.getStatus() == RuleDefinitionStatus.DRAFT) {
            current = ruleVersionMapper.findLatestDraft(ruleId);
        } else {
            current = ruleVersionMapper.findActiveVersion(ruleId);
        }

        List<RuleDetailVO.VersionItem> versions = ruleVersionMapper.findByRuleDefId(ruleId)
                .stream()
                .map(v -> new RuleDetailVO.VersionItem(
                        v.getId(), v.getVersion(), v.getStatus().name(),
                        v.getCreatedAt() != null ? v.getCreatedAt().toString() : null,
                        v.getPublishedBy(), v.getPublishedAt() != null ? v.getPublishedAt().toString() : null))
                .collect(java.util.stream.Collectors.toList());

        return new RuleDetailVO(
                rule.getTenantId(),
                rule.getId(), rule.getCode(), rule.getName(), rule.getStatus().name(),
                rule.getKind() != null ? rule.getKind().name() : null,
                scene != null ? scene.getCode() : null,
                current != null ? current.getConditionAst() : null,
                current != null ? current.getDecisionBindings() : null,
                current != null ? current.getPreGates() : null,
                current != null ? current.getTriggerEventTypes() : null,
                current != null ? current.getScriptSource() : null,
                current != null ? current.getFlowGraph() : null,
                current != null ? current.getId() : null,
                versions);
    }

    @Override
    public RuleVersionContentVO getRuleVersion(Long tenantId, Long ruleId, Long versionId) {
        RuleDefinition rule = ruleDefinitionMapper.selectById(ruleId);
        if (rule == null || !tenantId.equals(rule.getTenantId())) {
            throw new IllegalArgumentException("规则不存在: id=" + ruleId);
        }
        RuleVersion v = ruleVersionMapper.selectById(versionId);
        if (v == null || !ruleId.equals(v.getRuleDefinitionId())) {
            throw new IllegalArgumentException("版本不存在或不属于该规则: versionId=" + versionId);
        }
        return new RuleVersionContentVO(
                v.getId(), v.getVersion(), v.getStatus().name(),
                v.getKind() != null ? v.getKind().name() : null,
                v.getConditionAst(), v.getDecisionBindings(), v.getPreGates(),
                v.getTriggerEventTypes(), v.getScriptSource(),
                v.getFlowGraph(),
                v.getCreatedAt() != null ? v.getCreatedAt().toString() : null,
                v.getPublishedBy(), v.getPublishedAt() != null ? v.getPublishedAt().toString() : null);
    }

    @Override
    public DraftCreatedResult createDraft(Long tenantId, String sceneCode,
            String code, RuleContent content, String actorId) {
        return publishService.createDraft(tenantId, sceneCode, code, content, actorId);
    }

    @Override
    public DraftCreatedResult editDraft(Long tenantId, Long ruleId, RuleContent content, String actorId) {
        return publishService.editDraft(tenantId, ruleId, content, actorId);
    }

    @Override
    public DraftCreatedResult newVersion(Long tenantId, Long ruleId, RuleContent content,
            Long fromVersionId, String actorId) {
        return publishService.newVersion(tenantId, ruleId, content, fromVersionId, actorId);
    }

    @Override
    public void deleteRule(Long tenantId, Long ruleId, String actorId) {
        publishService.deleteRule(tenantId, ruleId, actorId);
    }

    @Override
    public void deleteDraftVersion(Long tenantId, Long ruleId, Long versionId, String actorId) {
        publishService.deleteDraftVersion(tenantId, ruleId, versionId, actorId);
    }

    @Override
    public List<TenantItemVO> listTenants(String keyword, String status) {
        LambdaQueryWrapper<Tenant> qw = new LambdaQueryWrapper<>();
        if (status != null && !status.isBlank()) {
            qw.eq(Tenant::getStatus, TenantStatus.valueOf(status));
        }
        if (keyword != null && !keyword.isBlank()) {
            qw.and(w -> w.like(Tenant::getCode, keyword).or().like(Tenant::getName, keyword));
        }
        return tenantMapper.selectList(qw).stream()
                .map(t -> new TenantItemVO(t.getId(), t.getCode(), t.getName(), t.getStatus().name(),
                        t.getCreatedAt(), t.getUpdatedAt()))
                .toList();
    }

    @Override
    public void toggleTenantStatus(Long tenantId, boolean enable) {
        Tenant t = tenantMapper.selectById(tenantId);
        if (t == null) throw new IllegalArgumentException("租户不存在: " + tenantId);
        t.setStatus(enable ? TenantStatus.ACTIVE : TenantStatus.DISABLED);
        tenantMapper.updateById(t);
    }
}
