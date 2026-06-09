package com.sstlfsj.rule.config.internal.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sstlfsj.rule.config.api.dto.DraftCreatedResult;
import com.sstlfsj.rule.config.api.dto.RuleDetailVO;
import com.sstlfsj.rule.config.api.dto.RuleListItemVO;
import com.sstlfsj.rule.config.api.service.ConfigService;
import com.sstlfsj.rule.config.internal.domain.RuleDefinition;
import com.sstlfsj.rule.config.internal.domain.RuleDefinitionStatus;
import com.sstlfsj.rule.config.internal.domain.RuleVersion;
import com.sstlfsj.rule.config.internal.domain.SceneDef;
import com.sstlfsj.rule.config.internal.event.OperationAuditedEvent;
import com.sstlfsj.rule.config.internal.publish.PublishService;
import com.sstlfsj.rule.config.internal.repository.RuleDefinitionMapper;
import com.sstlfsj.rule.config.internal.repository.RuleVersionMapper;
import com.sstlfsj.rule.config.internal.repository.SceneMapper;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot.DecisionBinding;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot.PreGateConfig;
import com.sstlfsj.rule.kernel.api.model.ast.AstNode;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/** ConfigService 实现，委托 PublishService 执行发布流程。 */
@Service
@RequiredArgsConstructor
class ConfigServiceImpl implements ConfigService {

    private final PublishService publishService;
    private final RuleDefinitionMapper ruleDefinitionMapper;
    private final SceneMapper sceneMapper;
    private final RuleVersionMapper ruleVersionMapper;
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
        rule.setStatus(RuleDefinitionStatus.DISABLED.name());
        ruleDefinitionMapper.updateById(rule);

        eventPublisher.publishEvent(new OperationAuditedEvent(
                Long.valueOf(tenantId), actorId, "USER", "DISABLE", "rule_definition",
                ruleDefinitionId.toString(), null, null, LocalDateTime.now()));
    }

    @Override
    public Page<RuleListItemVO> listRules(String tenantId, String sceneCode, String status, int page, int size) {
        // 按 sceneCode 解析 sceneId（未传时不过滤）
        Long sceneId = null;
        if (sceneCode != null && !sceneCode.isBlank()) {
            SceneDef scene = sceneMapper.findByCode(Long.valueOf(tenantId), sceneCode);
            if (scene == null) {
                return new Page<>(page, size);
            }
            sceneId = scene.getId();
        }

        Page<RuleDefinition> rdPage = ruleDefinitionMapper.selectRulePage(
                new Page<>(page, size), Long.valueOf(tenantId), sceneId, status);

        Page<RuleListItemVO> voPage = new Page<>(rdPage.getCurrent(), rdPage.getSize(), rdPage.getTotal());
        voPage.setRecords(rdPage.getRecords().stream()
                .map(rd -> new RuleListItemVO(
                        rd.getId(), rd.getCode(), rd.getName(),
                        rd.getStatus(), rd.getCurrentVersion(), rd.getPublishedAt()
                ))
                .toList());
        return voPage;
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
                rule.getId(), rule.getCode(), rule.getName(), rule.getStatus(), rule.getKind(),
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
            String kind, String actorId) {
        return publishService.createDraft(Long.valueOf(tenantId), sceneCode,
                code, name,
                conditionAst, decisionBindings,
                preGates, triggerEventTypes,
                kind, actorId);
    }
}
