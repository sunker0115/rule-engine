package com.sstlfsj.rule.config.internal.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sstlfsj.rule.config.api.service.DecisionService;
import com.sstlfsj.rule.config.api.service.UsageCount;
import com.sstlfsj.rule.config.internal.domain.ActorType;
import com.sstlfsj.rule.config.internal.domain.AuditAction;
import com.sstlfsj.rule.config.internal.domain.AuditTargetType;
import com.sstlfsj.rule.config.internal.domain.DecisionDefinition;
import com.sstlfsj.rule.config.internal.domain.DecisionStatus;
import com.sstlfsj.rule.config.internal.domain.RuleDefinition;
import com.sstlfsj.rule.config.internal.domain.RuleVersion;
import com.sstlfsj.rule.config.internal.event.OperationAuditedEvent;
import com.sstlfsj.rule.config.internal.repository.DecisionDefinitionMapper;
import com.sstlfsj.rule.config.internal.repository.RuleDefinitionMapper;
import com.sstlfsj.rule.config.internal.repository.RuleVersionMapper;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot.DecisionBinding;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@link DecisionService} 实现：tenant 级 CRUD + 审计事件（B 类同事务，无 SceneChangedEvent——decision 非 scene 级）。
 */
@Service
@RequiredArgsConstructor
public class DecisionServiceImpl implements DecisionService {

    private final DecisionDefinitionMapper mapper;
    private final ApplicationEventPublisher eventPublisher;
    private final RuleDefinitionMapper ruleDefinitionMapper;
    private final RuleVersionMapper ruleVersionMapper;

    @Override
    @Transactional
    public Long create(Long tenantId, String code, String name, Integer priority,
                       String description, String actorId) {
        if (mapper.findByCode(tenantId, code) != null) {
            throw new IllegalArgumentException("decision 编码已存在: code=" + code);
        }
        DecisionDefinition d = new DecisionDefinition();
        d.setTenantId(tenantId);
        d.setCode(code);
        d.setName(name);
        d.setPriority(priority);
        d.setDescription(description);
        d.setStatus(DecisionStatus.ACTIVE);
        d.setCreatedBy(actorId);
        d.setCreatedAt(LocalDateTime.now());
        mapper.insert(d);
        audit(tenantId, actorId, AuditAction.CREATE, d.getId());
        return d.getId();
    }

    @Override
    @Transactional
    public void update(Long tenantId, String code, String name, Integer priority,
                       String description, String actorId) {
        DecisionDefinition d = requireDecision(tenantId, code);
        d.setName(name);
        d.setPriority(priority);
        d.setDescription(description);
        d.setUpdatedBy(actorId);
        d.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(d);
        audit(tenantId, actorId, AuditAction.UPDATE, d.getId());
    }

    @Override
    @Transactional
    public void disable(Long tenantId, String code, String actorId) {
        DecisionDefinition d = requireDecision(tenantId, code);
        d.setStatus(DecisionStatus.DISABLED);
        d.setUpdatedBy(actorId);
        d.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(d);
        audit(tenantId, actorId, AuditAction.DISABLE, d.getId());
    }

    @Override
    @Transactional
    public void enable(Long tenantId, String code, String actorId) {
        DecisionDefinition d = requireDecision(tenantId, code);
        d.setStatus(DecisionStatus.ACTIVE);
        d.setUpdatedBy(actorId);
        d.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(d);
        audit(tenantId, actorId, AuditAction.ENABLE, d.getId());
    }

    @Override
    public List<DecisionDefinition> list(Long tenantId) {
        return mapper.selectList(new LambdaQueryWrapper<DecisionDefinition>()
                .eq(DecisionDefinition::getTenantId, tenantId));
    }

    @Override
    @Transactional(readOnly = true)
    public DecisionDefinition get(Long tenantId, String code) {
        DecisionDefinition d = mapper.findByCode(tenantId, code);
        if (d == null) {
            throw new IllegalArgumentException("decision 不存在: code=" + code);
        }
        return d;
    }

    @Override
    @Transactional(readOnly = true)
    public List<RuleRef> findRulesProducingDecision(Long tenantId, String decisionCode) {
        List<RuleDefinition> defs = ruleDefinitionMapper.findByTenant(tenantId);
        if (defs.isEmpty()) {
            return List.of();
        }
        Map<Long, RuleDefinition> defMap = defs.stream()
                .collect(Collectors.toMap(RuleDefinition::getId, d -> d));
        List<RuleVersion> activeVersions = ruleVersionMapper.findActiveWithDecisionByRuleDefIds(defMap.keySet());

        List<RuleRef> result = new ArrayList<>();
        for (RuleVersion rv : activeVersions) {
            if (containsDecision(rv.getDecisionBindings(), decisionCode)) {
                RuleDefinition def = defMap.get(rv.getRuleDefinitionId());
                result.add(new RuleRef(def.getId(), def.getCode(), def.getName(),
                        def.getSceneCode(), def.getStatus().name()));
            }
        }
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public List<UsageCount> countRuleUsages(Long tenantId) {
        List<RuleDefinition> defs = ruleDefinitionMapper.findByTenant(tenantId);
        if (defs.isEmpty()) {
            return List.of();
        }
        Set<Long> defIds = defs.stream().map(RuleDefinition::getId).collect(Collectors.toSet());
        List<RuleVersion> activeVersions = ruleVersionMapper.findActiveWithDecisionByRuleDefIds(defIds);
        Map<String, Integer> counts = new HashMap<>();
        for (RuleVersion rv : activeVersions) {
            List<DecisionBinding> bindings = rv.getDecisionBindings();
            if (bindings == null) continue;
            // 同一规则版本对同一 decisionCode 多次绑定只计一次；decisionCode 必填，null 防御性过滤避免 merge NPE
            bindings.stream().map(DecisionBinding::decisionCode).filter(Objects::nonNull).distinct()
                    .forEach(code -> counts.merge(code, 1, Integer::sum));
        }
        return counts.entrySet().stream().map(e -> new UsageCount(e.getKey(), e.getValue())).toList();
    }

    /** 判断 typed decisionBindings 是否含指定 decisionCode；null/空视为不含。 */
    private boolean containsDecision(List<DecisionBinding> bindings, String decisionCode) {
        if (bindings == null || bindings.isEmpty()) {
            return false;
        }
        return bindings.stream().anyMatch(b -> decisionCode.equals(b.decisionCode()));
    }

    private DecisionDefinition requireDecision(Long tenantId, String code) {
        DecisionDefinition d = mapper.findByCode(tenantId, code);
        if (d == null) {
            throw new IllegalArgumentException("decision 不存在: code=" + code);
        }
        return d;
    }

    private void audit(Long tenantId, String actorId, AuditAction action, Long id) {
        eventPublisher.publishEvent(new OperationAuditedEvent(
                tenantId, actorId, ActorType.USER, action, AuditTargetType.DECISION_DEFINITION, String.valueOf(id),
                null, null, LocalDateTime.now()));
    }
}
