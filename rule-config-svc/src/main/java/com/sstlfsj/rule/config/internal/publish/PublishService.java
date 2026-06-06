package com.sstlfsj.rule.config.internal.publish;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;
import com.sstlfsj.rule.config.api.dto.DraftCreatedResult;
import com.sstlfsj.rule.config.internal.domain.*;
import com.sstlfsj.rule.config.api.event.RulePublishedEvent;
import com.sstlfsj.rule.config.internal.repository.*;
import com.sstlfsj.rule.kernel.api.model.MetricDependency;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.model.ast.AstNode;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.model.ast.DecisionTableNode;
import com.sstlfsj.rule.kernel.api.model.ast.IfNode;
import com.sstlfsj.rule.kernel.api.model.ast.ScorecardRootNode;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 规则发布核心流程。
 * <p>
 * 事务边界：整个发布流程在一个本地事务内完成（INSERT rule_version +
 * UPDATE rule_definition + INSERT audit_log），事务提交后发布 Modulith 事件。
 * </p>
 */
@Service
@RequiredArgsConstructor
public class PublishService {

    private final RuleDefinitionMapper ruleDefinitionMapper;
    private final SceneMapper sceneMapper;
    private final RuleVersionMapper ruleVersionMapper;
    private final AuditLogMapper auditLogMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final AstSerializer astSerializer;
    private final ObjectMapper objectMapper;
    private final MetricDefinitionMapper metricDefinitionMapper;

    /** 已注册取数资源名目录（由 eval-svc 提供）；纯 config 部署时为 null，资源名校验跳过。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.sstlfsj.rule.config.api.spi.MetricResourceCatalog metricResourceCatalog;

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
        RuleVersion draftVersion = ruleVersionMapper.findLatestDraft(ruleDefinitionId);
        if (draftVersion == null) {
            throw new IllegalStateException("没有找到草稿版本，请先保存规则草稿");
        }

        // 3.5. 校验 triggerEventTypes ⊆ Scene.eventTypes（D13）
        validateTriggerEventTypes(draftVersion.getTriggerEventTypes(), scene.getEventTypes());

        // 3.6. 校验 pre_gates 中 ROLLOUT 项参数合法性
        validatePreGateParams(draftVersion.getPreGates());

        // 4. 反序列化 AST，收集 metricDependencies
        AstNode ast = astSerializer.fromJson(draftVersion.getConditionAst());
        // kind 合法性校验：null/blank 视为 AST_BOOLEAN（兼容历史存量数据）
        String rawKind = rule.getKind();
        String kind = (rawKind == null || rawKind.isBlank()) ? "AST_BOOLEAN" : rawKind;
        java.util.Set<String> validKinds = java.util.Set.of(
                "AST_BOOLEAN", "SCORECARD", "DECISION_TREE", "DECISION_TABLE");
        if (!validKinds.contains(kind)) {
            throw new IllegalArgumentException("不支持的规则 kind: " + kind);
        }
        // SCORECARD kind 校验：根节点必须是 ScorecardRootNode，叶子 weight 必须 > 0
        if ("SCORECARD".equals(kind)) {
            if (!(ast instanceof ScorecardRootNode scorecardRoot)) {
                throw new IllegalArgumentException(
                        "kind=SCORECARD 的规则 conditionAst 根节点必须是 ScorecardRootNode");
            }
            for (ConditionNode leaf : scorecardRoot.conditions()) {
                if (leaf.weight() == null || leaf.weight() <= 0) {
                    throw new IllegalArgumentException(
                            "SCORECARD 条件节点 weight 必须 > 0，conditionType=" + leaf.conditionType());
                }
            }
        }
        // DECISION_TREE 校验：根节点必须是 IfNode，thenBranch 不得为 null
        if ("DECISION_TREE".equals(kind)) {
            if (!(ast instanceof IfNode ifRoot)) {
                throw new IllegalArgumentException(
                        "kind=DECISION_TREE 的规则 conditionAst 根节点必须是 IfNode");
            }
            if (ifRoot.thenBranch() == null) {
                throw new IllegalArgumentException(
                        "kind=DECISION_TREE 的 IfNode thenBranch 不得为 null");
            }
        }
        // DECISION_TABLE 校验：根节点必须是 DecisionTableNode，columns/rows 非空，行列数一致
        if ("DECISION_TABLE".equals(kind)) {
            if (!(ast instanceof DecisionTableNode tableRoot)) {
                throw new IllegalArgumentException(
                        "kind=DECISION_TABLE 的规则 conditionAst 根节点必须是 DecisionTableNode");
            }
            if (tableRoot.columns() == null || tableRoot.columns().isEmpty()) {
                throw new IllegalArgumentException("DECISION_TABLE columns 不得为空");
            }
            if (tableRoot.rows() == null || tableRoot.rows().isEmpty()) {
                throw new IllegalArgumentException("DECISION_TABLE rows 不得为空");
            }
            int colCount = tableRoot.columns().size();
            for (int i = 0; i < tableRoot.rows().size(); i++) {
                DecisionTableNode.Row row = tableRoot.rows().get(i);
                if (row.conditions().size() != colCount) {
                    throw new IllegalArgumentException(
                            "DECISION_TABLE 第 " + i + " 行 conditions 数量（" + row.conditions().size()
                            + "）与列数（" + colCount + "）不一致");
                }
            }
        }
        List<String> metricCodes = MetricDependencyCollector.collect(ast);

        // 4.5. 查 ACTIVE metric，冻结版本号进 metricDeps（B6），同时提取 dataType 冻结进 AST（B19）
        AstNode resolvedAst = ast;
        List<MetricDependency> metricDeps = new ArrayList<>();
        if (!metricCodes.isEmpty()) {
            List<MetricDefinition> metricDefs = metricDefinitionMapper.findActiveByCodes(tenantId, metricCodes);
            // 按 metricCode 建索引，同 code 多行 ACTIVE = 数据异常，兜底拒绝
            Map<String, MetricDefinition> activeByCode = new HashMap<>();
            for (MetricDefinition m : metricDefs) {
                MetricDefinition prev = activeByCode.putIfAbsent(m.getMetricCode(), m);
                if (prev != null) {
                    throw new IllegalArgumentException(
                            "metric 存在多个 ACTIVE 版本，数据异常: " + m.getMetricCode());
                }
            }
            // 逐 code 冻结版本号，无 ACTIVE 行则拒绝发布
            for (String code : metricCodes) {
                MetricDefinition m = activeByCode.get(code);
                if (m == null) {
                    throw new IllegalArgumentException(
                            "被引用的 metric 无 ACTIVE 版本: " + code);
                }
                // version 为 null：存量无版本行，兜底为首版本号 1
                int ver = m.getVersion() == null ? 1 : m.getVersion();
                metricDeps.add(new MetricDependency(code, ver));
            }
            Map<String, String> dataTypeMap = activeByCode.values().stream()
                    .collect(Collectors.toMap(MetricDefinition::getMetricCode, MetricDefinition::getDataType));
            resolvedAst = AstDataTypeResolver.resolve(ast, dataTypeMap);

            // 4.6. metric 安全校验（B21）：SQL 时间函数/拼接拒绝 + 资源名注册（catalog 为 null 时跳过资源名校验）
            java.util.Set<String> dsNames = metricResourceCatalog != null
                    ? metricResourceCatalog.datasourceNames() : null;
            java.util.Set<String> epNames = metricResourceCatalog != null
                    ? metricResourceCatalog.endpointNames() : null;
            new MetricSafetyValidator(objectMapper).validate(new ArrayList<>(activeByCode.values()), dsNames, epNames);
        }

        // 5. 计算新版本号（max(version)+1）
        long newVersion = ruleVersionMapper.maxVersion(ruleDefinitionId) + 1;

        // 6. INSERT 新 rule_version（status=ACTIVE，不可变）
        RuleVersion newRv = new RuleVersion();
        newRv.setRuleDefinitionId(ruleDefinitionId);
        newRv.setVersion(newVersion);
        newRv.setConditionAst(astSerializer.toJson(resolvedAst));
        newRv.setDecisionBindings(draftVersion.getDecisionBindings() != null
                ? draftVersion.getDecisionBindings() : "[]");
        newRv.setPreGates(draftVersion.getPreGates() != null
                ? draftVersion.getPreGates() : "[]");
        newRv.setKind(rule.getKind() != null ? rule.getKind() : "AST_BOOLEAN");
        newRv.setTriggerEventTypes(scene.getEventTypes());
        newRv.setMetricDependencies(toJson(metricDeps));
        newRv.setStatus("ACTIVE");
        newRv.setPublishedBy(actorId);
        newRv.setPublishedAt(LocalDateTime.now());
        ruleVersionMapper.insert(newRv);

        // 7. 旧 ACTIVE rule_version 改为 SUPERSEDED（如有前一个正式版本）
        if (rule.getCurrentVersion() != null) {
            ruleVersionMapper.markSuperseded(rule.getCurrentVersion());
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
                resolvedAst,
                List.of(),   // preGates v1 暂时不反序列化
                List.of(),   // decisionBindings v1 暂时不反序列化
                List.of(),   // triggerEventTypes v1 暂时不反序列化，通配
                kind,
                metricDeps
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
     * @param kind                  规则类型（AST_BOOLEAN / SCORECARD / DECISION_TREE / DECISION_TABLE），null 时默认 AST_BOOLEAN
     * @param actorId               操作人
     * @return 新建草稿的 id 和版本信息
     */
    @Transactional
    public DraftCreatedResult createDraft(Long tenantId, String sceneCode,
            String code, String name,
            String conditionAstJson, String decisionBindingsJson,
            String preGatesJson, String triggerEventTypesJson,
            String kind, String actorId) {
        // 1. 按 tenantId + sceneCode 查询 SceneDef，不存在则报错
        SceneDef scene = sceneMapper.findByCode(tenantId, sceneCode);
        if (scene == null) {
            throw new IllegalArgumentException("Scene 不存在: code=" + sceneCode);
        }

        // 2. 校验 code 在同 tenant+scene 下唯一，提前给出友好错误
        boolean codeExists = ruleDefinitionMapper.findBySceneAndCode(tenantId, scene.getId(), code) != null;
        if (codeExists) {
            throw new IllegalArgumentException("规则编码已存在: code=" + code);
        }

        // 3. kind 合法性校验，null 时缺省 AST_BOOLEAN
        String effectiveKind = (kind == null || kind.isBlank()) ? "AST_BOOLEAN" : kind;
        java.util.Set<String> validKinds = java.util.Set.of(
                "AST_BOOLEAN", "SCORECARD", "DECISION_TREE", "DECISION_TABLE");
        if (!validKinds.contains(effectiveKind)) {
            throw new IllegalArgumentException("不支持的规则 kind: " + effectiveKind);
        }

        // 4. INSERT rule_definition（status=DRAFT）
        RuleDefinition rd = new RuleDefinition();
        rd.setTenantId(tenantId);
        rd.setSceneId(scene.getId());
        rd.setCode(code);
        rd.setName(name);
        rd.setStatus("DRAFT");
        rd.setKind(effectiveKind);
        rd.setCreatedBy(actorId);
        rd.setCreatedAt(LocalDateTime.now());
        ruleDefinitionMapper.insert(rd);

        // 5. INSERT rule_version（version=1，status=DRAFT）
        RuleVersion rv = new RuleVersion();
        rv.setRuleDefinitionId(rd.getId());
        rv.setVersion(1L);
        rv.setConditionAst(isBlank(conditionAstJson) ? "{}" : conditionAstJson);
        rv.setDecisionBindings(isBlank(decisionBindingsJson) ? "[]" : decisionBindingsJson);
        rv.setPreGates(isBlank(preGatesJson) ? "[]" : preGatesJson);
        rv.setKind(effectiveKind);
        rv.setTriggerEventTypes(isBlank(triggerEventTypesJson) ? "[]" : triggerEventTypesJson);
        rv.setMetricDependencies("[]");
        rv.setStatus("DRAFT");
        rv.setCreatedAt(LocalDateTime.now());
        ruleVersionMapper.insert(rv);

        // 6. INSERT audit_log（同事务写入，D14 约定）
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
     * 校验 pre_gates 中 ROLLOUT 项的 params 合法性（仅单规则校验，不查兄弟规则）。
     * percentage∈[0,100]；若给桶区间则 0<=bucketStart<bucketEnd<=100；experimentId 非空白。
     * pre_gates JSON 格式异常时容错跳过（不阻断发布），仅参数语义越界抛 IllegalArgumentException。
     */
    private void validatePreGateParams(String preGatesJson) {
        if (preGatesJson == null || preGatesJson.isBlank()) return;
        java.util.List<java.util.Map<String, Object>> gates;
        try {
            gates = objectMapper.readValue(preGatesJson, new tools.jackson.core.type.TypeReference<>() {});
        } catch (Exception e) {
            return;   // 格式异常容错跳过
        }
        for (java.util.Map<String, Object> gate : gates) {
            if (!"ROLLOUT".equals(String.valueOf(gate.get("gateType")))) continue;
            Object p = gate.get("params");
            if (!(p instanceof java.util.Map<?, ?> raw)) continue;
            @SuppressWarnings("unchecked")
            RolloutParams params = RolloutParams.from((java.util.Map<String, Object>) raw);

            if (params.percentage() != null
                    && (params.percentage() < 0 || params.percentage() > 100)) {
                throw new IllegalArgumentException(
                        "ROLLOUT percentage 必须在 [0,100]，实际值: " + params.percentage());
            }
            boolean hasStart = params.bucketStart() != null;
            boolean hasEnd = params.bucketEnd() != null;
            if (hasStart != hasEnd) {
                throw new IllegalArgumentException(
                        "ROLLOUT bucketStart/bucketEnd 必须成对出现");
            }
            if (hasStart) {
                int s = params.bucketStart(), en = params.bucketEnd();
                if (s < 0 || en > 100 || s >= en) {
                    throw new IllegalArgumentException(
                            "ROLLOUT 桶区间非法，要求 0<=bucketStart<bucketEnd<=100，实际: ["
                                    + s + "," + en + ")");
                }
            }
            if (params.experimentId() != null && params.experimentId().isBlank()) {
                throw new IllegalArgumentException(
                        "ROLLOUT experimentId 不得为空白字符串");
            }
        }
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
                    new tools.jackson.core.type.TypeReference<>() {});
            if (ruleTypes.isEmpty()) return;

            java.util.List<String> sceneTypes = objectMapper.readValue(sceneEventTypesJson,
                    new tools.jackson.core.type.TypeReference<>() {});
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
