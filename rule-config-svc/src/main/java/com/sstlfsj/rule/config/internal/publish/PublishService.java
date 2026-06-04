package com.sstlfsj.rule.config.internal.publish;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sstlfsj.rule.config.api.dto.DraftCreatedResult;
import com.sstlfsj.rule.config.internal.domain.*;
import com.sstlfsj.rule.config.api.event.RulePublishedEvent;
import com.sstlfsj.rule.config.internal.repository.*;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.model.ast.AstNode;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.model.ast.ScorecardRootNode;
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

        // 3.5. 校验 triggerEventTypes ⊆ Scene.eventTypes（D13）
        validateTriggerEventTypes(draftVersion.getTriggerEventTypes(), scene.getEventTypes());

        // 4. 反序列化 AST，收集 metricDependencies
        AstNode ast = astSerializer.fromJson(draftVersion.getConditionAst());
        // SCORECARD kind 校验：根节点必须是 ScorecardRootNode，叶子 weight 必须 > 0
        if ("SCORECARD".equals(rule.getKind())) {
            if (!(ast instanceof ScorecardRootNode scorecardRoot)) {
                throw new IllegalArgumentException(
                        "kind=SCORECARD 的规则 conditionAst 根节点必须是 ScorecardRootNode");
            }
            for (ConditionNode leaf : scorecardRoot.conditions()) {
                if (leaf.weight() <= 0) {
                    throw new IllegalArgumentException(
                            "SCORECARD 条件节点 weight 必须 > 0，conditionType=" + leaf.conditionType());
                }
            }
        }
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
                List.of(),   // triggerEventTypes v1 暂时不反序列化，通配
                newRv.getKind() != null ? newRv.getKind() : "AST_BOOLEAN"
        );

        // 11. 发布 Modulith 事件（事务提交后由 Spring 事件机制触发，eval-svc 监听热更索引）
        eventPublisher.publishEvent(new RulePublishedEvent(
                String.valueOf(tenantId), scene.getCode(), newRv.getId()));

        return snapshot;
    }

    /**
     * 创建规则草稿：INSERT rule_definition + rule_version（status=DRAFT）+ audit_log。
     *
     * @param tenantId              租户 id
     * @param sceneCode             场景编码
     * @param code                  规则编码
     * @param name                  规则名称
     * @param conditionAstJson      条件 AST JSON，为空时默认 "{}"
     * @param decisionBindingsJson  决策绑定 JSON，为空时默认 "[]"
     * @param preGatesJson          前置门控 JSON，为空时默认 "[]"
     * @param triggerEventTypesJson 触发事件类型 JSON，为空时默认 "[]"
     * @param actorId               操作人
     * @return 新建草稿的 id 和版本信息
     */
    @Transactional
    public DraftCreatedResult createDraft(Long tenantId, String sceneCode,
            String code, String name,
            String conditionAstJson, String decisionBindingsJson,
            String preGatesJson, String triggerEventTypesJson,
            String actorId) {
        // 1. 按 tenantId + sceneCode 查询 SceneDef，不存在则报错
        SceneDef scene = sceneMapper.selectOne(
                new LambdaQueryWrapper<SceneDef>()
                        .eq(SceneDef::getTenantId, tenantId)
                        .eq(SceneDef::getCode, sceneCode)
        );
        if (scene == null) {
            throw new IllegalArgumentException("Scene 不存在: code=" + sceneCode);
        }

        // 2. 校验 code 在同 tenant+scene 下唯一，提前给出友好错误
        long codeExists = ruleDefinitionMapper.selectCount(
                new LambdaQueryWrapper<RuleDefinition>()
                        .eq(RuleDefinition::getTenantId, tenantId)
                        .eq(RuleDefinition::getSceneId, scene.getId())
                        .eq(RuleDefinition::getCode, code)
        );
        if (codeExists > 0) {
            throw new IllegalArgumentException("规则编码已存在: code=" + code);
        }

        // 3. INSERT rule_definition（status=DRAFT）
        RuleDefinition rd = new RuleDefinition();
        rd.setTenantId(tenantId);
        rd.setSceneId(scene.getId());
        rd.setCode(code);
        rd.setName(name);
        rd.setStatus("DRAFT");
        rd.setKind("AST_BOOLEAN");
        rd.setCreatedBy(actorId);
        rd.setCreatedAt(LocalDateTime.now());
        ruleDefinitionMapper.insert(rd);

        // 4. INSERT rule_version（version=1，status=DRAFT）
        RuleVersion rv = new RuleVersion();
        rv.setRuleDefinitionId(rd.getId());
        rv.setVersion(1L);
        rv.setConditionAst(isBlank(conditionAstJson) ? "{}" : conditionAstJson);
        rv.setDecisionBindings(isBlank(decisionBindingsJson) ? "[]" : decisionBindingsJson);
        rv.setPreGates(isBlank(preGatesJson) ? "[]" : preGatesJson);
        rv.setRollout("{}");
        rv.setKind("AST_BOOLEAN");
        rv.setTriggerEventTypes(isBlank(triggerEventTypesJson) ? "[]" : triggerEventTypesJson);
        rv.setMetricDependencies("[]");
        rv.setStatus("DRAFT");
        rv.setCreatedAt(LocalDateTime.now());
        ruleVersionMapper.insert(rv);

        // 4. INSERT audit_log（同事务写入，D14 约定）
        AuditLog log = new AuditLog();
        log.setTenantId(tenantId);
        log.setActor(actorId);
        log.setActorType("USER");
        log.setAction("CREATE");
        log.setTargetType("rule_definition");
        log.setTargetId(rd.getId().toString());
        log.setAfterSnapshot("{\"ruleDefinitionId\":" + rd.getId() + ",\"ruleVersionId\":" + rv.getId() + "}");
        log.setOperatedAt(LocalDateTime.now());
        auditLogMapper.insert(log);

        return new DraftCreatedResult(rd.getId(), rv.getId(), 1L, "DRAFT");
    }

    /**
     * 校验规则的 triggerEventTypes 是否均在 Scene 允许的 eventTypes 白名单内。
     * scene.eventTypes 为空时跳过（Scene 尚未配置白名单，容错）；
     * triggerEventTypes 为空时也跳过（规则通配所有事件）。
     */
    private void validateTriggerEventTypes(String triggerEventTypesJson, String sceneEventTypesJson) {
        try {
            if (triggerEventTypesJson == null || triggerEventTypesJson.isBlank()) return;
            java.util.List<String> ruleTypes = objectMapper.readValue(triggerEventTypesJson,
                    new com.fasterxml.jackson.core.type.TypeReference<>() {});
            if (ruleTypes.isEmpty()) return;

            java.util.List<String> sceneTypes = objectMapper.readValue(sceneEventTypesJson,
                    new com.fasterxml.jackson.core.type.TypeReference<>() {});
            if (sceneTypes.isEmpty()) return;   // Scene 未设置白名单，容错通过

            java.util.Set<String> allowed = new java.util.HashSet<>(sceneTypes);
            java.util.List<String> invalid = ruleTypes.stream()
                    .filter(et -> !allowed.contains(et))
                    .toList();
            if (!invalid.isEmpty()) {
                throw new IllegalArgumentException(
                        "triggerEventType 不在 Scene 允许列表，非法值: " + invalid);
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            // JSON 解析失败时容错（不阻断发布）
        }
    }

    /** 判断字符串是否为 null 或空白。 */
    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "[]";
        }
    }
}
