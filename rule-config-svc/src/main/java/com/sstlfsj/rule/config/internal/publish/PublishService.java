package com.sstlfsj.rule.config.internal.publish;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sstlfsj.rule.config.internal.domain.*;
import com.sstlfsj.rule.config.api.event.RulePublishedEvent;
import com.sstlfsj.rule.config.internal.repository.*;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.model.ast.AstNode;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 规则发布核心流程。
 * <p>
 * 事务边界：整个发布流程在一个本地事务内完成（INSERT rule_version +
 * UPDATE rule_definition + INSERT audit_log），事务提交后发布 Modulith 事件。
 * </p>
 */
@Service
public class PublishService {

    private final RuleDefinitionMapper ruleDefinitionMapper;
    private final SceneMapper sceneMapper;
    private final RuleVersionMapper ruleVersionMapper;
    private final DecisionDefinitionMapper decisionDefinitionMapper;
    private final AuditLogMapper auditLogMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final AstSerializer astSerializer;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PublishService(RuleDefinitionMapper ruleDefinitionMapper,
                          SceneMapper sceneMapper,
                          RuleVersionMapper ruleVersionMapper,
                          DecisionDefinitionMapper decisionDefinitionMapper,
                          AuditLogMapper auditLogMapper,
                          ApplicationEventPublisher eventPublisher,
                          AstSerializer astSerializer) {
        this.ruleDefinitionMapper = ruleDefinitionMapper;
        this.sceneMapper = sceneMapper;
        this.ruleVersionMapper = ruleVersionMapper;
        this.decisionDefinitionMapper = decisionDefinitionMapper;
        this.auditLogMapper = auditLogMapper;
        this.eventPublisher = eventPublisher;
        this.astSerializer = astSerializer;
    }

    /**
     * 发布规则：从最新草稿 rule_version 生成正式版本快照。
     *
     * @param tenantId         租户 id
     * @param ruleDefinitionId 规则定义 id
     * @param actorId          操作人（来自 X-Actor-Id header）
     * @return 新生成的 RuleVersionSnapshot（供 eval-svc 倒排索引热更使用）
     */
    @Transactional
    public RuleVersionSnapshot publish(Long tenantId, Long ruleDefinitionId, String actorId) {
        // 1. 加载 RuleDefinition，校验 tenantId 和 status
        RuleDefinition rule = ruleDefinitionMapper.selectById(ruleDefinitionId);
        if (rule == null || !tenantId.equals(rule.getTenantId())) {
            throw new IllegalArgumentException("规则不存在: id=" + ruleDefinitionId);
        }
        if (!"DRAFT".equals(rule.getStatus())) {
            throw new IllegalStateException("只有 DRAFT 状态的规则可以发布，当前状态: " + rule.getStatus());
        }

        // 2. 加载 Scene
        SceneDef scene = sceneMapper.selectById(rule.getSceneId());
        if (scene == null) {
            throw new IllegalStateException("Scene 不存在: id=" + rule.getSceneId());
        }

        // 3. 查最新草稿 rule_version 行（status=DRAFT），作为 AST 来源
        RuleVersion draftVersion = ruleVersionMapper.selectOne(
                new LambdaQueryWrapper<RuleVersion>()
                        .eq(RuleVersion::getRuleDefinitionId, ruleDefinitionId)
                        .eq(RuleVersion::getStatus, "DRAFT")
                        .orderByDesc(RuleVersion::getVersion)
                        .last("LIMIT 1")
        );
        if (draftVersion == null) {
            throw new IllegalStateException("没有找到草稿版本，请先保存规则草稿");
        }

        // 4. 反序列化 AST，收集 metricDependencies
        AstNode ast = astSerializer.fromJson(draftVersion.getConditionAst());
        List<String> metricDeps = MetricDependencyCollector.collect(ast);

        // 5. 计算新版本号（max(version)+1）
        long newVersion = ruleVersionMapper.maxVersion(ruleDefinitionId) + 1;

        // 6. INSERT 新 rule_version（status=ACTIVE，不可变）
        RuleVersion newRv = new RuleVersion();
        newRv.setRuleDefinitionId(ruleDefinitionId);
        newRv.setVersion(newVersion);
        newRv.setConditionAst(draftVersion.getConditionAst());
        newRv.setDecisionBindings(draftVersion.getDecisionBindings() != null
                ? draftVersion.getDecisionBindings() : "[]");
        newRv.setPreGates(draftVersion.getPreGates() != null
                ? draftVersion.getPreGates() : "[]");
        newRv.setRollout(draftVersion.getRollout() != null
                ? draftVersion.getRollout() : "{}");
        newRv.setKind(rule.getKind() != null ? rule.getKind() : "AST_BOOLEAN");
        newRv.setTriggerEventTypes(scene.getEventTypes());
        newRv.setMetricDependencies(toJson(metricDeps));
        newRv.setStatus("ACTIVE");
        newRv.setPublishedBy(actorId);
        newRv.setPublishedAt(LocalDateTime.now());
        ruleVersionMapper.insert(newRv);

        // 7. 旧 ACTIVE rule_version 改为 SUPERSEDED（如有前一个正式版本）
        if (rule.getCurrentVersion() != null) {
            ruleVersionMapper.update(null,
                    new LambdaUpdateWrapper<RuleVersion>()
                            .eq(RuleVersion::getId, rule.getCurrentVersion())
                            .eq(RuleVersion::getStatus, "ACTIVE")
                            .set(RuleVersion::getStatus, "SUPERSEDED"));
        }

        // 8. UPDATE rule_definition：状态改为 PUBLISHED，记录 currentVersion
        rule.setStatus("PUBLISHED");
        rule.setCurrentVersion(newRv.getId());
        rule.setPublishedBy(actorId);
        rule.setPublishedAt(LocalDateTime.now());
        ruleDefinitionMapper.updateById(rule);

        // 9. INSERT audit_log（D14 同步事务写）
        AuditLog auditLog = new AuditLog();
        auditLog.setTenantId(tenantId);
        auditLog.setActor(actorId);
        auditLog.setActorType("USER");
        auditLog.setAction("PUBLISH");
        auditLog.setTargetType("rule_definition");
        auditLog.setTargetId(ruleDefinitionId.toString());
        auditLog.setAfterSnapshot("{\"ruleVersionId\":" + newRv.getId() + ",\"version\":" + newVersion + "}");
        auditLog.setOperatedAt(LocalDateTime.now());
        auditLogMapper.insert(auditLog);

        // 10. 生成 RuleVersionSnapshot 供返回和事件携带
        RuleVersionSnapshot snapshot = new RuleVersionSnapshot(
                newRv.getId(),
                scene.getCode(),
                String.valueOf(tenantId),
                ast,
                List.of(),   // preGates v1 暂时不反序列化
                List.of(),   // decisionBindings v1 暂时不反序列化
                List.of()    // triggerEventTypes v1 暂时不反序列化，通配
        );

        // 11. 发布 Modulith 事件（事务提交后由 Spring 事件机制触发，eval-svc 监听热更索引）
        eventPublisher.publishEvent(new RulePublishedEvent(
                String.valueOf(tenantId), scene.getCode(), newRv.getId()));

        return snapshot;
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "[]";
        }
    }
}
