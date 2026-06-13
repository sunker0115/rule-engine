package com.sstlfsj.rule.config.internal.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sstlfsj.rule.config.api.service.DecisionService;
import com.sstlfsj.rule.config.internal.domain.DecisionDefinition;
import com.sstlfsj.rule.config.internal.domain.DecisionStatus;
import com.sstlfsj.rule.config.internal.event.OperationAuditedEvent;
import com.sstlfsj.rule.config.internal.repository.DecisionDefinitionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * {@link DecisionService} 实现：tenant 级 CRUD + 审计事件（B 类同事务，无 SceneChangedEvent——decision 非 scene 级）。
 */
@Service
@RequiredArgsConstructor
public class DecisionServiceImpl implements DecisionService {

    private final DecisionDefinitionMapper mapper;
    private final ApplicationEventPublisher eventPublisher;

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
        audit(tenantId, actorId, "CREATE", d.getId());
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
        audit(tenantId, actorId, "UPDATE", d.getId());
    }

    @Override
    @Transactional
    public void disable(Long tenantId, String code, String actorId) {
        DecisionDefinition d = requireDecision(tenantId, code);
        d.setStatus(DecisionStatus.DISABLED);
        d.setUpdatedBy(actorId);
        d.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(d);
        audit(tenantId, actorId, "DISABLE", d.getId());
    }

    @Override
    public List<DecisionDefinition> list(Long tenantId) {
        return mapper.selectList(new LambdaQueryWrapper<DecisionDefinition>()
                .eq(DecisionDefinition::getTenantId, tenantId));
    }

    private DecisionDefinition requireDecision(Long tenantId, String code) {
        DecisionDefinition d = mapper.findByCode(tenantId, code);
        if (d == null) {
            throw new IllegalArgumentException("decision 不存在: code=" + code);
        }
        return d;
    }

    private void audit(Long tenantId, String actorId, String action, Long id) {
        eventPublisher.publishEvent(new OperationAuditedEvent(
                tenantId, actorId, "USER", action, "decision_definition", String.valueOf(id),
                null, null, LocalDateTime.now()));
    }
}
