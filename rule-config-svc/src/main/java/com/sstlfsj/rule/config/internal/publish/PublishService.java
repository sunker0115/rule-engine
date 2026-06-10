package com.sstlfsj.rule.config.internal.publish;

import com.sstlfsj.rule.config.api.dto.DraftCreatedResult;
import com.sstlfsj.rule.config.api.dto.PayloadFieldSpec;
import com.sstlfsj.rule.config.api.event.RulePublishedEvent;
import com.sstlfsj.rule.config.internal.domain.*;
import com.sstlfsj.rule.config.internal.event.DraftCreatedSnapshot;
import com.sstlfsj.rule.config.internal.event.OperationAuditedEvent;
import com.sstlfsj.rule.config.internal.event.RulePublishedSnapshot;
import com.sstlfsj.rule.config.internal.event.RuleStatusSnapshot;
import com.sstlfsj.rule.config.internal.repository.*;
import com.sstlfsj.rule.kernel.api.model.MetricDependency;
import com.sstlfsj.rule.kernel.api.model.RuleKind;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.model.ast.*;
import lombok.RequiredArgsConstructor;
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
    private final ApplicationEventPublisher eventPublisher;
    private final MetricDefinitionMapper metricDefinitionMapper;
    private final DecisionDefinitionMapper decisionDefinitionMapper;

    /**
     * 已注册取数资源名目录（由 eval-svc 提供）；纯 config 部署时为 null，资源名校验跳过。
     */
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
        if (rule.getStatus() != RuleDefinitionStatus.DRAFT) {
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

        // 4. 取草稿 AST（已 typed），收集 metricDependencies
        AstNode ast = draftVersion.getConditionAst();
        // kind 合法性校验：null 视为 AST_BOOLEAN（兼容历史存量数据）
        String kind = (rule.getKind() != null ? rule.getKind() : RuleKind.AST_BOOLEAN).name();
        java.util.Set<String> validKinds = java.util.Set.of(
                RuleKind.AST_BOOLEAN.tag(), RuleKind.SCORECARD.tag(),
                RuleKind.DECISION_TREE.tag(), RuleKind.DECISION_TABLE.tag());
        if (!validKinds.contains(kind)) {
            throw new IllegalArgumentException("不支持的规则 kind: " + kind);
        }
        // SCORECARD kind 校验：根节点必须是 ScorecardRootNode，叶子 weight 必须 > 0
        if (RuleKind.SCORECARD.tag().equals(kind)) {
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
        // DECISION_TREE 校验：根节点必须是 IfNode；递归校验分支节点类型 + thenBranch 非空 + 条件子树不含不支持节点（如 XOR）
        if (RuleKind.DECISION_TREE.tag().equals(kind)) {
            if (!(ast instanceof IfNode ifRoot)) {
                throw new IllegalArgumentException(
                        "kind=DECISION_TREE 的规则 conditionAst 根节点必须是 IfNode");
            }
            validateDecisionTree(ifRoot);
        }
        // DECISION_TABLE 校验：根节点必须是 DecisionTableNode，columns/rows 非空，行列数一致
        if (RuleKind.DECISION_TABLE.tag().equals(kind)) {
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
        List<MetricDependency> metricDeps = new ArrayList<>();
        Map<String, String> dataTypeMap = new HashMap<>();
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
            dataTypeMap.putAll(activeByCode.values().stream()
                    .collect(Collectors.toMap(MetricDefinition::getMetricCode, MetricDefinition::getDataType)));

            // 4.6. metric 安全校验（B21）：SQL 时间函数/拼接拒绝 + 资源名注册（catalog 为 null 时跳过资源名校验）
            java.util.Set<String> dsNames = metricResourceCatalog != null
                    ? metricResourceCatalog.datasourceNames() : null;
            java.util.Set<String> epNames = metricResourceCatalog != null
                    ? metricResourceCatalog.endpointNames() : null;
            new MetricSafetyValidator().validate(new ArrayList<>(activeByCode.values()), dsNames, epNames);
        }

        // 4.7. payload 引用校验：valueRef=PAYLOAD 字段必须在 scene.payloadSchema 声明，并注入 dataType
        List<String> payloadFields = PayloadFieldCollector.collect(ast);
        Map<String, String> payloadTypeMap = new HashMap<>();
        if (!payloadFields.isEmpty()) {
            List<PayloadFieldSpec> schema = scene.getPayloadSchema() != null
                    ? scene.getPayloadSchema() : java.util.List.of();
            Map<String, String> schemaTypeByName = new HashMap<>();
            for (PayloadFieldSpec f : schema) schemaTypeByName.put(f.name(), f.type());
            for (String field : payloadFields) {
                if (!schemaTypeByName.containsKey(field)) {
                    throw new IllegalArgumentException(
                            "规则引用的 payload 字段未在 scene.payloadSchema 声明: " + field);
                }
                payloadTypeMap.put(field, PayloadDataTypeMapper.toDataTypeTag(schemaTypeByName.get(field)));
            }
        }

        // 仅当有 metric 或 payload 引用时才重建 AST（注入 dataType）；都没有则原样保留
        AstNode resolvedAst = (metricCodes.isEmpty() && payloadFields.isEmpty())
                ? ast : AstDataTypeResolver.resolve(ast, dataTypeMap, payloadTypeMap);

        // 5. 计算新版本号（max(version)+1）
        long newVersion = ruleVersionMapper.maxVersion(ruleDefinitionId) + 1;

        // 6. INSERT 新 rule_version（status=ACTIVE，不可变）
        RuleVersion newRv = new RuleVersion();
        newRv.setRuleDefinitionId(ruleDefinitionId);
        newRv.setVersion(newVersion);
        newRv.setConditionAst(resolvedAst);
        // D27:发布期冻结 decision.name/actions 进 binding 快照(方案甲,守 D6);引用的 decisionCode 必须存在
        List<RuleVersionSnapshot.DecisionBinding> rawBindings = draftVersion.getDecisionBindings() != null
                ? draftVersion.getDecisionBindings() : java.util.List.of();
        newRv.setDecisionBindings(freezeDecisionBindings(tenantId, scene, rawBindings));
        newRv.setPreGates(draftVersion.getPreGates() != null
                ? draftVersion.getPreGates() : java.util.List.of());
        newRv.setKind(rule.getKind() != null ? rule.getKind() : RuleKind.AST_BOOLEAN);
        newRv.setTriggerEventTypes(scene.getEventTypes() != null
                ? scene.getEventTypes() : java.util.List.of());
        newRv.setMetricDependencies(metricDeps);
        newRv.setStatus(RuleVersionStatus.ACTIVE);
        newRv.setPublishedBy(actorId);
        newRv.setPublishedAt(LocalDateTime.now());
        ruleVersionMapper.insert(newRv);

        // 7. 旧 ACTIVE rule_version 改为 SUPERSEDED（如有前一个正式版本）
        if (rule.getCurrentVersion() != null) {
            ruleVersionMapper.markSuperseded(rule.getCurrentVersion());
        }

        // 8. UPDATE rule_definition：状态改为 PUBLISHED，记录 currentVersion
        // 捕获发布前状态作为 before 快照（D14 审计完整性，能还原"发布前的状态 + 上一个生效版本"）
        RuleStatusSnapshot beforeSnap = new RuleStatusSnapshot(
                ruleDefinitionId, rule.getStatus().name(), rule.getCurrentVersion());
        rule.setStatus(RuleDefinitionStatus.PUBLISHED);
        rule.setCurrentVersion(newRv.getId());
        rule.setPublishedBy(actorId);
        rule.setPublishedAt(LocalDateTime.now());
        ruleDefinitionMapper.updateById(rule);

        // 9. 发布操作审计事件（集中监听器 BEFORE_COMMIT 同事务落 audit_log，D14 红线）
        eventPublisher.publishEvent(new OperationAuditedEvent(
                tenantId, actorId, "USER", "PUBLISH", "rule_definition", ruleDefinitionId.toString(),
                beforeSnap,
                new RulePublishedSnapshot(newRv.getId(), newVersion),
                LocalDateTime.now()));

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
                metricDeps,
                newRv.getPayloadDependencies()
        );

        // 11. 发布 Modulith 事件（事务提交后由 Spring 事件机制触发，eval-svc 监听热更索引）
        eventPublisher.publishEvent(new RulePublishedEvent(
                String.valueOf(tenantId), scene.getCode(), newRv.getId()));

        return snapshot;
    }

    /**
     * 创建规则草稿：INSERT rule_definition + rule_version（status=DRAFT）+ audit_log。
     *
     * @param tenantId          租户 id
     * @param sceneCode         场景编码
     * @param code              规则编码
     * @param name              规则名称
     * @param conditionAst      条件 AST，null 视为空 AND
     * @param decisionBindings  决策绑定列表，null 视为空
     * @param preGates          前置门控列表，null 视为空
     * @param triggerEventTypes 触发事件类型列表，null 视为空
     * @param kind              规则类型（AST_BOOLEAN / SCORECARD / DECISION_TREE / DECISION_TABLE），null 时默认 AST_BOOLEAN
     * @param actorId           操作人
     * @return 新建草稿的 id 和版本信息
     */
    @Transactional
    public DraftCreatedResult createDraft(Long tenantId, String sceneCode,
                                          String code, String name,
                                          AstNode conditionAst, java.util.List<RuleVersionSnapshot.DecisionBinding> decisionBindings,
                                          java.util.List<RuleVersionSnapshot.PreGateConfig> preGates, java.util.List<String> triggerEventTypes,
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
        String effectiveKind = (kind == null || kind.isBlank()) ? RuleKind.AST_BOOLEAN.tag() : kind;
        java.util.Set<String> validKinds = java.util.Set.of(
                RuleKind.AST_BOOLEAN.tag(), RuleKind.SCORECARD.tag(),
                RuleKind.DECISION_TREE.tag(), RuleKind.DECISION_TABLE.tag());
        if (!validKinds.contains(effectiveKind)) {
            throw new IllegalArgumentException("不支持的规则 kind: " + effectiveKind);
        }
        RuleKind effectiveRuleKind = RuleKind.valueOf(effectiveKind);

        // 4. INSERT rule_definition（status=DRAFT）
        RuleDefinition rd = RuleDefinition.draft(tenantId, scene.getId(), code, name, effectiveRuleKind, actorId);
        ruleDefinitionMapper.insert(rd);

        // 5. INSERT rule_version（version=1，status=DRAFT）
        RuleVersion rv = RuleVersion.draftV1(rd.getId(), conditionAst, decisionBindings, preGates,
                triggerEventTypes, effectiveRuleKind);
        ruleVersionMapper.insert(rv);

        // 6. 发布操作审计事件（集中监听器 BEFORE_COMMIT 同事务落 audit_log，D14 约定）
        // CREATE 类 before/after 传同一快照实例，审计行始终 before/after 都有值，避免 null 特殊处理
        DraftCreatedSnapshot draftSnapshot = new DraftCreatedSnapshot(rd.getId(), rv.getId());
        eventPublisher.publishEvent(new OperationAuditedEvent(
                tenantId, actorId, "USER", "CREATE", "rule_definition", rd.getId().toString(),
                draftSnapshot,
                draftSnapshot,
                LocalDateTime.now()));

        return new DraftCreatedResult(rd.getId(), rv.getId(), 1L, RuleDefinitionStatus.DRAFT.name());
    }

    /**
     * 把 draft 的 (decisionCode, priority) binding 富化为含 name/actions 的快照 binding（方案甲，守 D6）。
     * 引用的 decisionCode 必须在 decision_definition 存在，否则拒绝发布（DECISION_CODE_NOT_FOUND）。
     */
    private List<RuleVersionSnapshot.DecisionBinding> freezeDecisionBindings(
            Long tenantId, SceneDef scene, List<RuleVersionSnapshot.DecisionBinding> rawBindings) {
        if (rawBindings.isEmpty()) return java.util.List.of();
        List<String> codes = rawBindings.stream()
                .map(RuleVersionSnapshot.DecisionBinding::decisionCode).distinct().toList();
        Map<String, DecisionDefinition> byCode = decisionDefinitionMapper.findByCodes(tenantId, codes).stream()
                .collect(Collectors.toMap(DecisionDefinition::getCode, d -> d, (a, b) -> a));
        boolean pullScene = scene.getDominantMode() == DominantMode.PULL;
        List<RuleVersionSnapshot.DecisionBinding> frozen = new ArrayList<>(rawBindings.size());
        for (RuleVersionSnapshot.DecisionBinding b : rawBindings) {
            DecisionDefinition d = byCode.get(b.decisionCode());
            if (d == null) {
                throw new IllegalArgumentException(
                        "DECISION_CODE_NOT_FOUND: 引用的 decision 不存在: " + b.decisionCode());
            }
            List<RuleVersionSnapshot.DecisionAction> actions =
                    d.getActions() != null ? d.getActions() : java.util.List.of();
            // PULL Scene 下 Decision.actions 必须为空(D27/D54)：PULL 同步返回不派发 action
            if (pullScene && !actions.isEmpty()) {
                throw new IllegalArgumentException(
                        "PULL Scene 的 Decision 不得挂 action(同步返回不派发): decisionCode=" + b.decisionCode());
            }
            // priority 从 decision_definition 回填(草稿期 binding priority 是 0 占位，DecisionBindingInput 契约)
            int priority = d.getPriority() != null ? d.getPriority() : b.priority();
            frozen.add(new RuleVersionSnapshot.DecisionBinding(
                    b.decisionCode(), d.getName(), priority, actions));
        }
        return frozen;
    }

    /**
     * 校验 pre_gates 中 ROLLOUT 项的 params 合法性（仅单规则校验，不查兄弟规则）。
     * percentage∈[0,100]；若给桶区间则 0<=bucketStart<bucketEnd<=100；experimentId 非空白。
     * pre_gates JSON 格式异常时容错跳过（不阻断发布），仅参数语义越界抛 IllegalArgumentException。
     */
    private void validatePreGateParams(List<RuleVersionSnapshot.PreGateConfig> gates) {
        if (gates == null || gates.isEmpty()) return;
        for (RuleVersionSnapshot.PreGateConfig gate : gates) {
            // pre-gate 收敛:仅 ROLLOUT 是注册的合法 gate;RATE_LIMIT/MUTEX 等已砍,配了即拒绝发布
            if (!"ROLLOUT".equals(gate.gateType())) {
                throw new IllegalArgumentException(
                        "不支持的 pre-gate gateType(仅 ROLLOUT 合法): " + gate.gateType());
            }
            if (gate.params() == null) continue;
            RolloutParams params = RolloutParams.from(gate.params());

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
    private void validateTriggerEventTypes(List<String> ruleTypes, List<String> sceneTypes) {
        if (ruleTypes == null || ruleTypes.isEmpty()) return;
        if (sceneTypes == null || sceneTypes.isEmpty()) return;   // Scene 未设置白名单，容错通过

        java.util.Set<String> allowed = new java.util.HashSet<>(sceneTypes);
        java.util.List<String> invalid = ruleTypes.stream()
                .filter(et -> !allowed.contains(et))
                .toList();
        if (!invalid.isEmpty()) {
            throw new IllegalArgumentException(
                    "triggerEventType 不在 Scene 允许列表，非法值: " + invalid);
        }
    }

    /**
     * 递归校验决策树结构：分支节点只能是 IfNode 或 DecisionLeafNode，每个 IfNode 的 thenBranch 非空、
     * 条件子树仅含决策树支持的节点（见 {@link #validateTreeCondition}）。
     */
    private static void validateDecisionTree(AstNode node) {
        switch (node) {
            case IfNode ifn -> {
                if (ifn.thenBranch() == null) {
                    throw new IllegalArgumentException("kind=DECISION_TREE 的 IfNode thenBranch 不得为 null");
                }
                if (ifn.condition() == null) {
                    throw new IllegalArgumentException("kind=DECISION_TREE 的 IfNode condition 不得为 null");
                }
                validateTreeCondition(ifn.condition());
                validateDecisionTree(ifn.thenBranch());
                if (ifn.elseBranch() != null) validateDecisionTree(ifn.elseBranch());
            }
            case DecisionLeafNode ignored -> { /* 终点叶子，合法 */ }
            default -> throw new IllegalArgumentException(
                    "kind=DECISION_TREE 的分支节点只能是 IfNode 或 DecisionLeafNode，实际: "
                            + node.getClass().getSimpleName());
        }
    }

    /**
     * 决策树条件子树仅支持 ConditionNode/AndNode/OrNode/NotNode；出现 XorNode 等不支持的节点即拒绝发布
     * （决策树条件求值不支持 XOR，避免上线后运行时才报 NO_EVALUATOR）。
     */
    private static void validateTreeCondition(AstNode cond) {
        switch (cond) {
            case ConditionNode ignored -> {
            }
            case AndNode and -> and.children().forEach(PublishService::validateTreeCondition);
            case OrNode or -> or.children().forEach(PublishService::validateTreeCondition);
            case NotNode not -> validateTreeCondition(not.child());
            default -> throw new IllegalArgumentException(
                    "kind=DECISION_TREE 的条件不支持节点类型: " + cond.getClass().getSimpleName()
                            + "（决策树条件仅支持 Condition/And/Or/Not；XOR 等逻辑请用 AST_BOOLEAN kind）");
        }
    }
}
