# 规则草稿/版本生命周期 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把规则的"草稿—版本—发布"生命周期补全为 createDraft / editDraft / newVersion / rollback / publish / delete,以"草稿即完整冻结快照"为基石(premise A),并从结构上根除 dry-run 副作用 bug。

**Architecture:** 把 `PublishService.publish` 里的"解析+校验"段抽成可复用方法 `resolveAndValidate`,由 createDraft/editDraft/newVersion 在建/改草稿时调用,产出完整冻结的 `rule_version`;`publish` 退化为"激活"(把最新 DRAFT 行原地置 ACTIVE + supersede 旧 ACTIVE + 发 `RulePublishedEvent` 触发 eval 索引热更),不再重解析。dry-run 改为 `ruleId`/`ruleVersionId` 二选一必传,永远先解析出一个版本 id,从而恒走"带版本"单快照分支。

**Tech Stack:** Java 25 / Spring Boot 4 / Spring Modulith / MyBatis-Plus(`BaseMapper` + `LambdaQueryWrapper` default 方法) / JUnit5 + Mockito + AssertJ。包根 `com.sstlfsj.rule`。

---

## 关键设计决策(实现者必读)

1. **publish 不再 INSERT 新 ACTIVE 行,而是把最新 DRAFT 行原地翻成 ACTIVE**(spec §二:publish 不增版本、只激活)。version 号只在 createDraft(v1)/newVersion(v_max+1) 时产生;editDraft/publish 不增版本。这是相对现状最大的行为变化——现状是 publish 插一条新 version=max+1 的 ACTIVE 行并保留 DRAFT 行。

2. **premise A:草稿在 create/edit/newVersion 时就跑全套 `resolveAndValidate`**(metric 须 ACTIVE、payload 须在 scene.payloadSchema 声明、decision 须存在;结构/算子×dataType 校验),校验不过即拒绝建/改草稿。落库的 DRAFT 行已是冻结快照(resolvedAst 含 dataType、metricDependencies/payloadDependencies 已冻、decisionBindings 含 name/actions)。

3. **triggerEventTypes 冻结口径变更**:现状 publish 把 ACTIVE 行的 `triggerEventTypes` 覆盖成 `scene.getEventTypes()` 全集(`PublishService.java:222`)。新设计下冻结的是**草稿自己的 triggerEventTypes**(已校验 ⊆ scene.eventTypes),保证"你预览(dry-run 草稿)的 == 你发布的"。空 triggerEventTypes 仍归 eval 侧 `*` 通配桶。

4. **客户端错误一律抛 `IllegalArgumentException`**(`GlobalExceptionHandler` 映射 → 400 `INVALID_ARGUMENT`)。`IllegalStateException` 未被映射会落 500,仅用于"不该发生"的内部不变量。dry-run 缺目标用 `IllegalArgumentException("MISSING_DRYRUN_TARGET: ...")`。

5. **索引热更事件保持 `RulePublishedEvent`**(`RuleIndexEventListener` 监听),不改成 spec 文字里提到的 `SceneChangedEvent`——publish 路径现用 `RulePublishedEvent`,沿用即可。

6. **greenfield**:库里旧的 raw(未解析)草稿直接清掉,不做兼容;`RuleVersion.draftV1`(raw 草稿工厂)删除。

---

## 文件结构图

**rule-config-svc**(配置写):
- `internal/publish/PublishService.java` — 抽 `resolveAndValidate` + 内嵌 `ResolvedDraft` record;`publish` 改激活;新增 `editDraft`/`newVersion`/`deleteRule`/`deleteDraftVersion`。
- `internal/domain/RuleVersion.java` — 删 `draftV1`;改由 PublishService 私有 helper 组装冻结 DRAFT 行。
- `internal/repository/RuleVersionMapper.java` — 加 `findByIdAndRule`/`hasNonDraftVersion`/`deleteByRuleDefinitionId`。
- `api/service/ConfigService.java` + `internal/service/ConfigServiceImpl.java` — 接口扩展 editDraft/newVersion/deleteRule/deleteDraftVersion。

**rule-api**(HTTP):
- `web/admin/RuleController.java` — 加 PUT `/draft`、POST `/versions`、DELETE `/{ruleId}`、DELETE `/{ruleId}/versions/{versionId}`。
- `web/admin/dto/EditDraftRequest.java`(新)、`web/admin/dto/NewVersionRequest.java`(新)。
- `web/api/EvalController.java` — `dryRun` 加 `ruleId` 参数。

**rule-eval-svc**(评估):
- `api/service/EvalService.java` + `internal/service/EvalServiceImpl.java` — `dryRun` 改签名 `(event, ruleId, ruleVersionId)`,解析目标版本 id。
- `internal/repository/RuleVersionReadMapper.java` — 加 `latestVersionIdByRule`。

**docs**:`00-decisions`/`10-api-contract`/`01-concepts`/`02-runtime`/`functional-test-coverage`。

---

## Task 1: 抽取 `resolveAndValidate`(纯重构,publish 行为不变)

把 publish 里"3.5~5(resolvedAst+metricDeps+payloadDeps+frozenBindings)"段抽成 `resolveAndValidate`,publish 改为调用它并用返回值组装 ACTIVE 行。**本任务不改变任何外部行为**——publish 仍 INSERT 新 ACTIVE 行、仍重解析。全部既有测试保持绿。

**Files:**
- Modify: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/publish/PublishService.java`
- Test: `rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/publish/PublishServiceTest.java`(不改,作为回归基线)

- [ ] **Step 1: 在 PublishService 内新增 `ResolvedDraft` record 与 `resolveAndValidate` 方法**

在 `PublishService` 类体内(`publish` 方法之后)新增:

```java
    /**
     * 草稿解析+校验产出:已冻结的 rule_version 内容字段(resolvedAst 含 dataType、
     * metricDeps/payloadDeps 已冻、decisionBindings 含 name/actions、triggerEventTypes/preGates 规整)。
     */
    public record ResolvedDraft(
            RuleKind kind,
            AstNode resolvedAst,
            List<RuleVersionSnapshot.DecisionBinding> decisionBindings,
            List<RuleVersionSnapshot.PreGateConfig> preGates,
            List<String> triggerEventTypes,
            List<MetricDependency> metricDeps,
            List<PayloadDependency> payloadDeps) {
    }

    /**
     * 解析+校验草稿输入,产出完整冻结的版本内容(premise A)。供 createDraft/editDraft/newVersion 调用。
     * 任一校验不过抛 IllegalArgumentException(→ 400)。
     *
     * @param tenantId          租户 id
     * @param scene             所属场景(已加载)
     * @param kind              规则类型(非空)
     * @param conditionAst      草稿条件 AST,null 兜底为空 AndNode
     * @param rawBindings       草稿决策绑定(仅 decisionCode + 占位 priority),null 视为空
     * @param preGates          前置门控,null 视为空
     * @param triggerEventTypes 触发事件类型,null 视为空
     * @return 冻结后的版本内容
     */
    public ResolvedDraft resolveAndValidate(
            Long tenantId, SceneDef scene, RuleKind kind,
            AstNode conditionAst,
            List<RuleVersionSnapshot.DecisionBinding> rawBindings,
            List<RuleVersionSnapshot.PreGateConfig> preGates,
            List<String> triggerEventTypes) {

        AstNode ast = conditionAst != null ? conditionAst
                : new AndNode(List.of(), null, null);
        List<RuleVersionSnapshot.DecisionBinding> bindings = rawBindings != null ? rawBindings : List.of();
        List<RuleVersionSnapshot.PreGateConfig> gates = preGates != null ? preGates : List.of();
        List<String> triggers = triggerEventTypes != null ? triggerEventTypes : List.of();

        String kindTag = kind.name();
        java.util.Set<String> validKinds = java.util.Set.of(
                RuleKind.AST_BOOLEAN.tag(), RuleKind.SCORECARD.tag(),
                RuleKind.DECISION_TREE.tag(), RuleKind.DECISION_TABLE.tag());
        if (!validKinds.contains(kindTag)) {
            throw new IllegalArgumentException("不支持的规则 kind: " + kindTag);
        }
        // 结构校验:SCORECARD 根/权重、DECISION_TREE 结构、DECISION_TABLE 行列一致
        validateKindStructure(kindTag, ast);

        validateTriggerEventTypes(triggers, scene.getEventTypes());
        validatePreGateParams(gates);

        // metric 收集 + ACTIVE 冻结 + 安全校验
        List<String> metricCodes = MetricDependencyCollector.collect(ast);
        List<MetricDependency> metricDeps = new ArrayList<>();
        Map<String, String> dataTypeMap = new HashMap<>();
        if (!metricCodes.isEmpty()) {
            List<MetricDefinition> metricDefs = metricDefinitionMapper.findActiveByCodes(tenantId, metricCodes);
            Map<String, MetricDefinition> activeByCode = new HashMap<>();
            for (MetricDefinition m : metricDefs) {
                MetricDefinition prev = activeByCode.putIfAbsent(m.getMetricCode(), m);
                if (prev != null) {
                    throw new IllegalArgumentException("metric 存在多个 ACTIVE 版本,数据异常: " + m.getMetricCode());
                }
            }
            for (String code : metricCodes) {
                MetricDefinition m = activeByCode.get(code);
                if (m == null) {
                    throw new IllegalArgumentException("被引用的 metric 无 ACTIVE 版本: " + code);
                }
                int ver = m.getVersion() == null ? 1 : m.getVersion();
                metricDeps.add(new MetricDependency(code, ver));
            }
            dataTypeMap.putAll(activeByCode.values().stream()
                    .collect(Collectors.toMap(MetricDefinition::getMetricCode, MetricDefinition::getDataType)));
            java.util.Set<String> dsNames = metricResourceCatalog != null ? metricResourceCatalog.datasourceNames() : null;
            java.util.Set<String> epNames = metricResourceCatalog != null ? metricResourceCatalog.endpointNames() : null;
            new MetricSafetyValidator().validate(new ArrayList<>(activeByCode.values()), dsNames, epNames);
        }

        // payload 收集 + scene.payloadSchema 声明校验 + 冻结依赖
        List<String> payloadFields = PayloadFieldCollector.collect(ast);
        Map<String, String> payloadTypeMap = new HashMap<>();
        List<PayloadDependency> payloadDeps = new ArrayList<>();
        if (!payloadFields.isEmpty()) {
            List<PayloadFieldSpec> schema = scene.getPayloadSchema() != null ? scene.getPayloadSchema() : List.of();
            Map<String, PayloadFieldSpec> specByName = new HashMap<>();
            for (PayloadFieldSpec f : schema) specByName.put(f.name(), f);
            for (String field : payloadFields) {
                PayloadFieldSpec spec = specByName.get(field);
                if (spec == null) {
                    throw new IllegalArgumentException("规则引用的 payload 字段未在 scene.payloadSchema 声明: " + field);
                }
                String dataTypeTag = PayloadDataTypeMapper.toDataTypeTag(spec.type());
                payloadTypeMap.put(field, dataTypeTag);
                payloadDeps.add(new PayloadDependency(field, dataTypeTag, spec.required()));
            }
        }

        AstNode resolvedAst = (metricCodes.isEmpty() && payloadFields.isEmpty())
                ? ast : AstDataTypeResolver.resolve(ast, dataTypeMap, payloadTypeMap);

        List<RuleVersionSnapshot.DecisionBinding> frozenBindings = freezeDecisionBindings(tenantId, scene, bindings);

        return new ResolvedDraft(kind, resolvedAst, frozenBindings, gates, triggers, metricDeps, payloadDeps);
    }

    /** kind 结构校验(从 publish 抽取,逻辑原样)。 */
    private void validateKindStructure(String kindTag, AstNode ast) {
        if (RuleKind.SCORECARD.tag().equals(kindTag)) {
            if (!(ast instanceof ScorecardRootNode scorecardRoot)) {
                throw new IllegalArgumentException("kind=SCORECARD 的规则 conditionAst 根节点必须是 ScorecardRootNode");
            }
            for (ConditionNode leaf : scorecardRoot.conditions()) {
                if (leaf.weight() == null || leaf.weight() <= 0) {
                    throw new IllegalArgumentException("SCORECARD 条件节点 weight 必须 > 0,conditionType=" + leaf.conditionType());
                }
            }
        }
        if (RuleKind.DECISION_TREE.tag().equals(kindTag)) {
            if (!(ast instanceof IfNode ifRoot)) {
                throw new IllegalArgumentException("kind=DECISION_TREE 的规则 conditionAst 根节点必须是 IfNode");
            }
            validateDecisionTree(ifRoot);
        }
        if (RuleKind.DECISION_TABLE.tag().equals(kindTag)) {
            if (!(ast instanceof DecisionTableNode tableRoot)) {
                throw new IllegalArgumentException("kind=DECISION_TABLE 的规则 conditionAst 根节点必须是 DecisionTableNode");
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
                    throw new IllegalArgumentException("DECISION_TABLE 第 " + i + " 行 conditions 数量("
                            + row.conditions().size() + ")与列数(" + colCount + ")不一致");
                }
            }
        }
    }
```

- [ ] **Step 2: 重写 `publish` 方法体,改为调用 `resolveAndValidate`(行为不变)**

把 `publish` 方法 line 62~272 的方法体替换为(保留方法签名/Javadoc):

```java
    @Transactional
    public RuleVersionSnapshot publish(Long tenantId, Long ruleDefinitionId, String actorId) {
        RuleDefinition rule = ruleDefinitionMapper.selectById(ruleDefinitionId);
        if (rule == null || !tenantId.equals(rule.getTenantId())) {
            throw new IllegalArgumentException("规则不存在: id=" + ruleDefinitionId);
        }
        if (rule.getStatus() != RuleDefinitionStatus.DRAFT) {
            throw new IllegalStateException("只有 DRAFT 状态的规则可以发布,当前状态: " + rule.getStatus());
        }
        SceneDef scene = sceneMapper.selectById(rule.getSceneId());
        if (scene == null) {
            throw new IllegalStateException("Scene 不存在: id=" + rule.getSceneId());
        }
        RuleVersion draftVersion = ruleVersionMapper.findLatestDraft(ruleDefinitionId);
        if (draftVersion == null) {
            throw new IllegalStateException("没有找到草稿版本,请先保存规则草稿");
        }

        RuleKind kind = rule.getKind() != null ? rule.getKind() : RuleKind.AST_BOOLEAN;
        ResolvedDraft resolved = resolveAndValidate(
                tenantId, scene, kind,
                draftVersion.getConditionAst(),
                draftVersion.getDecisionBindings(),
                draftVersion.getPreGates(),
                draftVersion.getTriggerEventTypes());

        long newVersion = ruleVersionMapper.maxVersion(ruleDefinitionId) + 1;
        RuleVersion newRv = new RuleVersion();
        newRv.setRuleDefinitionId(ruleDefinitionId);
        newRv.setVersion(newVersion);
        newRv.setConditionAst(resolved.resolvedAst());
        newRv.setDecisionBindings(resolved.decisionBindings());
        newRv.setPreGates(resolved.preGates());
        newRv.setKind(kind);
        newRv.setTriggerEventTypes(resolved.triggerEventTypes());
        newRv.setMetricDependencies(resolved.metricDeps());
        newRv.setPayloadDependencies(resolved.payloadDeps());
        newRv.setStatus(RuleVersionStatus.ACTIVE);
        newRv.setPublishedBy(actorId);
        newRv.setPublishedAt(LocalDateTime.now());
        ruleVersionMapper.insert(newRv);

        if (rule.getCurrentVersion() != null) {
            ruleVersionMapper.markSuperseded(rule.getCurrentVersion());
        }
        RuleStatusSnapshot beforeSnap = new RuleStatusSnapshot(
                ruleDefinitionId, rule.getStatus().name(), rule.getCurrentVersion());
        rule.setStatus(RuleDefinitionStatus.PUBLISHED);
        rule.setCurrentVersion(newRv.getId());
        rule.setPublishedBy(actorId);
        rule.setPublishedAt(LocalDateTime.now());
        ruleDefinitionMapper.updateById(rule);

        eventPublisher.publishEvent(new OperationAuditedEvent(
                tenantId, actorId, "USER", "PUBLISH", "rule_definition", ruleDefinitionId.toString(),
                beforeSnap, new RulePublishedSnapshot(newRv.getId(), newVersion), LocalDateTime.now()));

        RuleVersionSnapshot snapshot = new RuleVersionSnapshot(
                newRv.getId(), scene.getCode(), String.valueOf(tenantId),
                resolved.resolvedAst(), List.of(), List.of(), List.of(),
                kind.name(), resolved.metricDeps(), newRv.getPayloadDependencies());
        eventPublisher.publishEvent(new RulePublishedEvent(
                String.valueOf(tenantId), scene.getCode(), newRv.getId()));
        return snapshot;
    }
```

注意:`validateTriggerEventTypes` / `validatePreGateParams` / `validateDecisionTree` / `validateTreeCondition` / `freezeDecisionBindings` 私有方法**保留不动**(被 resolveAndValidate 复用)。删除 publish 旧方法体里这些内联段(现已移入 resolveAndValidate)。

- [ ] **Step 3: 设环境并跑 config-svc 测试,确认绿(纯重构)**

Run(先用 mvn-env skill 设 `$MVN`):`$MVN -pl rule-config-svc -am test -Dtest=PublishServiceTest`
Expected: PASS。所有既有 publish/createDraft 测试通过——行为未变。

> 注:本任务后 publish 行为与现状一致(triggerEventTypes 此刻仍是草稿自己的值;现状 publish 旧代码用 `scene.getEventTypes()` 覆盖——这里**已悄然改为草稿值**。`publish_draftRule_createsVersionAndUpdatesDefinition` 断言的是 `snapshot.triggerEventTypes()` 来自 RuleVersionSnapshot 第 7 参 `List.of()`,与落库行无关,故仍绿)。若该测试失败,核对断言对象是 snapshot 还是落库实体。

- [ ] **Step 4: Commit**

```bash
git add rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/publish/PublishService.java
git commit -m "refactor(config): 抽取 resolveAndValidate,publish 复用(行为不变)"
```

---

## Task 2: createDraft 跑 resolveAndValidate 并落冻结草稿(premise A)

让 createDraft 在建草稿时即跑 `resolveAndValidate`,落库的 DRAFT 行已是冻结快照。删除 `RuleVersion.draftV1`。迁移校验类测试到 createDraft。

**Files:**
- Modify: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/publish/PublishService.java`(createDraft)
- Modify: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/domain/RuleVersion.java`(删 draftV1)
- Test: `rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/publish/PublishServiceTest.java`

- [ ] **Step 1: 加私有 helper `buildDraftVersion`,重写 createDraft**

在 PublishService 加:

```java
    /** 用冻结内容组装 DRAFT 版本行(createDraft/newVersion 共用)。 */
    private RuleVersion buildDraftVersion(Long ruleDefinitionId, long version, ResolvedDraft r) {
        RuleVersion rv = new RuleVersion();
        rv.setRuleDefinitionId(ruleDefinitionId);
        rv.setVersion(version);
        rv.setConditionAst(r.resolvedAst());
        rv.setDecisionBindings(r.decisionBindings());
        rv.setPreGates(r.preGates());
        rv.setKind(r.kind());
        rv.setTriggerEventTypes(r.triggerEventTypes());
        rv.setMetricDependencies(r.metricDeps());
        rv.setPayloadDependencies(r.payloadDeps());
        rv.setStatus(RuleVersionStatus.DRAFT);
        rv.setCreatedAt(LocalDateTime.now());
        return rv;
    }
```

把 createDraft 的 step 5(`RuleVersion.draftV1(...)`)替换为:

```java
        // 5. resolveAndValidate(premise A):建草稿即冻结快照
        ResolvedDraft resolved = resolveAndValidate(
                tenantId, scene, effectiveRuleKind,
                conditionAst, decisionBindings, preGates, triggerEventTypes);
        RuleVersion rv = buildDraftVersion(rd.getId(), 1L, resolved);
        ruleVersionMapper.insert(rv);
```

- [ ] **Step 2: 删除 `RuleVersion.draftV1`**

删除 `RuleVersion.java` line 46~75 的 `draftV1` 静态工厂及其 Javadoc(已无调用方)。

- [ ] **Step 3: 迁移/更新 createDraft 测试**

`createDraft_insertsRuleDefinitionAndVersion`:kind=SCORECARD + 空 AndNode 现在会被结构校验拒(SCORECARD 根须 ScorecardRootNode)。改为 `AST_BOOLEAN` + 空 AndNode,断言落库 DRAFT 行 metricDependencies/payloadDependencies 为空、conditionAst 为 AndNode。新增 mock:`when(ruleDefinitionMapper.findBySceneAndCode(any(),any(),any())).thenReturn(null);`

`createDraft_nullKind_defaultsToAstBoolean`:空 AndNode + AST_BOOLEAN,保持绿。

新增测试(premise A 核心):

```java
    @Test
    void createDraft_freezesMetricAndDecision_intoDraftVersion() {
        SceneDef sc = new SceneDef();
        sc.setId(5L); sc.setTenantId(1L); sc.setCode("PAYMENT");
        sc.setEventTypes(List.of("payment.initiated"));
        when(sceneMapper.findByCode(any(), any())).thenReturn(sc);
        when(ruleDefinitionMapper.findBySceneAndCode(any(), any(), any())).thenReturn(null);
        doAnswer(inv -> { inv.getArgument(0, RuleDefinition.class).setId(10L); return 1; })
                .when(ruleDefinitionMapper).insert(any(RuleDefinition.class));
        doAnswer(inv -> { inv.getArgument(0, RuleVersion.class).setId(20L); return 1; })
                .when(ruleVersionMapper).insert(any(RuleVersion.class));
        MetricDefinition md = new MetricDefinition();
        md.setMetricCode("account.age"); md.setDataType("LONG"); md.setVersion(3); md.setStatus(MetricStatus.ACTIVE);
        when(metricDefinitionMapper.findActiveByCodes(any(), any())).thenReturn(List.of(md));

        publishService.createDraft(1L, "PAYMENT", "rule.test", "测试",
                new ConditionNode("GT", "account.age", null, Map.of("threshold", 30), 0.0),
                List.of(), List.of(), List.of(), "AST_BOOLEAN", "actor1");

        ArgumentCaptor<RuleVersion> cap = ArgumentCaptor.forClass(RuleVersion.class);
        verify(ruleVersionMapper).insert(cap.capture());
        RuleVersion frozen = cap.getValue();
        assertThat(frozen.getStatus()).isEqualTo(RuleVersionStatus.DRAFT);
        assertThat(frozen.getMetricDependencies()).containsExactly(new MetricDependency("account.age", 3));
        assertThat(((ConditionNode) frozen.getConditionAst()).dataType()).isEqualTo("LONG");
    }

    @Test
    void createDraft_metricNotActive_rejected() {
        SceneDef sc = new SceneDef();
        sc.setId(5L); sc.setTenantId(1L); sc.setCode("PAYMENT");
        when(sceneMapper.findByCode(any(), any())).thenReturn(sc);
        when(ruleDefinitionMapper.findBySceneAndCode(any(), any(), any())).thenReturn(null);
        doAnswer(inv -> { inv.getArgument(0, RuleDefinition.class).setId(10L); return 1; })
                .when(ruleDefinitionMapper).insert(any(RuleDefinition.class));
        when(metricDefinitionMapper.findActiveByCodes(any(), any())).thenReturn(List.of());

        assertThatThrownBy(() -> publishService.createDraft(1L, "PAYMENT", "rule.test", "测试",
                new ConditionNode("GT", "account.age", null, Map.of("threshold", 30), 0.0),
                List.of(), List.of(), List.of(), "AST_BOOLEAN", "actor1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ACTIVE");
    }
```

- [ ] **Step 4: 跑测试**

Run: `$MVN -pl rule-config-svc -am test -Dtest=PublishServiceTest`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/publish/PublishService.java \
        rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/domain/RuleVersion.java \
        rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/publish/PublishServiceTest.java
git commit -m "feat(config): createDraft 建时冻结快照(premise A),删 draftV1"
```

---

## Task 3: publish 退化为"激活"(原地翻 DRAFT→ACTIVE,不重解析)

**Files:**
- Modify: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/publish/PublishService.java`(publish)
- Test: `rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/publish/PublishServiceTest.java`

- [ ] **Step 1: 重写 publish 为激活语义**

把 publish 方法体替换为:

```java
    @Transactional
    public RuleVersionSnapshot publish(Long tenantId, Long ruleDefinitionId, String actorId) {
        RuleDefinition rule = ruleDefinitionMapper.selectById(ruleDefinitionId);
        if (rule == null || !tenantId.equals(rule.getTenantId())) {
            throw new IllegalArgumentException("规则不存在: id=" + ruleDefinitionId);
        }
        SceneDef scene = sceneMapper.selectById(rule.getSceneId());
        if (scene == null) {
            throw new IllegalStateException("Scene 不存在: id=" + rule.getSceneId());
        }
        // 加载最新 DRAFT(已是冻结快照,premise A);无待发布草稿则拒
        RuleVersion draft = ruleVersionMapper.findLatestDraft(ruleDefinitionId);
        if (draft == null) {
            throw new IllegalStateException("没有待发布的草稿版本,请先保存规则草稿");
        }
        Long previousActiveId = rule.getCurrentVersion();

        // 原地激活 DRAFT 行(不增版本、不重解析)
        draft.setStatus(RuleVersionStatus.ACTIVE);
        draft.setPublishedBy(actorId);
        draft.setPublishedAt(LocalDateTime.now());
        ruleVersionMapper.updateById(draft);

        if (previousActiveId != null) {
            ruleVersionMapper.markSuperseded(previousActiveId);
        }
        RuleStatusSnapshot beforeSnap = new RuleStatusSnapshot(
                ruleDefinitionId, rule.getStatus().name(), previousActiveId);
        rule.setStatus(RuleDefinitionStatus.PUBLISHED);
        rule.setCurrentVersion(draft.getId());
        rule.setPublishedBy(actorId);
        rule.setPublishedAt(LocalDateTime.now());
        ruleDefinitionMapper.updateById(rule);

        eventPublisher.publishEvent(new OperationAuditedEvent(
                tenantId, actorId, "USER", "PUBLISH", "rule_definition", ruleDefinitionId.toString(),
                beforeSnap, new RulePublishedSnapshot(draft.getId(), draft.getVersion()), LocalDateTime.now()));

        RuleKind kind = rule.getKind() != null ? rule.getKind() : RuleKind.AST_BOOLEAN;
        RuleVersionSnapshot snapshot = new RuleVersionSnapshot(
                draft.getId(), scene.getCode(), String.valueOf(tenantId),
                draft.getConditionAst(), List.of(), List.of(), List.of(),
                kind.name(), draft.getMetricDependencies(), draft.getPayloadDependencies());
        eventPublisher.publishEvent(new RulePublishedEvent(
                String.valueOf(tenantId), scene.getCode(), draft.getId()));
        return snapshot;
    }
```

注意:publish 不再校验 `rule.getStatus()==DRAFT`(已发布规则出 newVersion 后 status 仍 PUBLISHED,但有新 DRAFT 待发布)。激活前置只要求"存在 DRAFT 版本"。

- [ ] **Step 2: 迁移 publish 测试**

publish 现不再做解析/校验,以下"校验拒绝"类测试已被 Task 2 的 createDraft 测试覆盖,**从 PublishServiceTest 删除**(它们 mock 的是 publish 时解析,现 publish 不解析会因 mock 未触达而失败):

删除:`publish_rejectsWhenDecisionCodeNotFound`、`publish_pullSceneWithDecisionActions_throws`、`publish_freezesDecisionNameAndActionsIntoSnapshot`、`publish_scorecard_*`、`publish_decisionTree_*`(校验类)、`publish_decisionTable_*`(校验类)、`publish_triggerEventType不在Scene白名单_抛IllegalArgument`、`publish_unsupportedKind_throwsIllegalArgument`、`publish_rollout*`、`publish_unregisteredGateType_throws`、`publish_freezesDataTypeInConditionAst`、`publish_incompatibleOperatorDataType_*`、`publish_sqlMetric*`、`publish_freezesActiveMetricVersion_*`、`publish_multipleActiveVersions_*`、`publish_referencedMetricHasNoActiveVersion_*`、`publish_freezesPayloadDependencies_*`、`publish_metricValueRef_doesNotLeakIntoPayloadDependencies`、`publish_decisionTreeKind_正常通过`、`publish_decisionTableKind_正常通过`、`publish_triggerEventType在Scene白名单内_正常发布`、`publish_triggerEventTypes为空_跳过校验`、`publish_sceneEventTypes为空_跳过校验`、`publish_rolloutValidRange_publishes`。

> 这些校验逻辑现属 resolveAndValidate,应在新增的 `ResolvedAndValidateTest`(可选)或既有 createDraft 测试里覆盖。为不丢覆盖,Task 2 已加 metric 冻结/拒绝两例;如需补齐 kind 结构/payload/rollout 校验覆盖,在 createDraft 维度补测(同 mock 套路,把 `publish(1L,10L,...)` 换成 `createDraft(...)`)。

保留并改写为激活语义:`publish_ruleNotFound_throwsIllegalArgument`(不变)、`publish_noDraftVersion_throwsIllegalState`(`findLatestDraft` 返回 null → 抛"没有待发布的草稿版本";更新断言文案)。`publish_nonDraftRule_throwsIllegalState` **删除**(publish 不再要求 DRAFT 状态)。

改写 `publish_draftRule_createsVersionAndUpdatesDefinition` → `publish_activatesDraftInPlace`:

```java
    @Test
    void publish_activatesDraftInPlace() {
        draftRule.setCurrentVersion(null);
        draftVersion.setVersion(1L);
        draftVersion.setConditionAst(new ConditionNode("GT", "amount", "LONG", Map.of("threshold", 1), 0.0));
        draftVersion.setMetricDependencies(List.of(new MetricDependency("amount", 1)));
        draftVersion.setPayloadDependencies(List.of());
        draftVersion.setTriggerEventTypes(List.of());
        when(ruleDefinitionMapper.selectById(10L)).thenReturn(draftRule);
        when(sceneMapper.selectById(5L)).thenReturn(scene);
        when(ruleVersionMapper.findLatestDraft(any())).thenReturn(draftVersion);
        when(ruleVersionMapper.updateById((RuleVersion) any())).thenReturn(1);
        when(ruleDefinitionMapper.updateById((RuleDefinition) any())).thenReturn(1);

        RuleVersionSnapshot snapshot = publishService.publish(1L, 10L, "operator1");

        assertThat(snapshot.sceneCode()).isEqualTo("PAYMENT");
        assertThat(snapshot.metricDependencies()).containsExactly(new MetricDependency("amount", 1));
        // DRAFT 行原地翻 ACTIVE,无新 insert
        ArgumentCaptor<RuleVersion> rvCaptor = ArgumentCaptor.forClass(RuleVersion.class);
        verify(ruleVersionMapper).updateById(rvCaptor.capture());
        assertThat(rvCaptor.getValue().getStatus()).isEqualTo(RuleVersionStatus.ACTIVE);
        assertThat(rvCaptor.getValue().getVersion()).isEqualTo(1L);
        verify(ruleVersionMapper, never()).insert((RuleVersion) any());
        ArgumentCaptor<RuleDefinition> rdCaptor = ArgumentCaptor.forClass(RuleDefinition.class);
        verify(ruleDefinitionMapper).updateById(rdCaptor.capture());
        assertThat(rdCaptor.getValue().getStatus()).isEqualTo(RuleDefinitionStatus.PUBLISHED);
        assertThat(rdCaptor.getValue().getCurrentVersion()).isEqualTo(100L);
        verify(eventPublisher, times(2)).publishEvent(any());
    }

    @Test
    void publish_supersedesPreviousActive() {
        draftRule.setStatus(RuleDefinitionStatus.PUBLISHED);
        draftRule.setCurrentVersion(99L);
        draftVersion.setVersion(2L);
        draftVersion.setConditionAst(new AndNode(List.of(), null, null));
        draftVersion.setMetricDependencies(List.of());
        draftVersion.setPayloadDependencies(List.of());
        draftVersion.setTriggerEventTypes(List.of());
        when(ruleDefinitionMapper.selectById(10L)).thenReturn(draftRule);
        when(sceneMapper.selectById(5L)).thenReturn(scene);
        when(ruleVersionMapper.findLatestDraft(any())).thenReturn(draftVersion);
        when(ruleVersionMapper.updateById((RuleVersion) any())).thenReturn(1);
        when(ruleDefinitionMapper.updateById((RuleDefinition) any())).thenReturn(1);

        publishService.publish(1L, 10L, "op");

        verify(ruleVersionMapper).markSuperseded(99L);
    }
```

需要 import `AndNode`、`MetricDependency`(已在测试 import)。`scene` setUp 里 eventTypes 已设,补 `scene.setDominantMode(DominantMode.PUSH)` 若 freeze 路径需要(publish 已不调 freeze,可不加)。

- [ ] **Step 3: 跑测试**

Run: `$MVN -pl rule-config-svc -am test -Dtest=PublishServiceTest`
Expected: PASS。

- [ ] **Step 4: Commit**

```bash
git add rule-config-svc/.../PublishService.java rule-config-svc/.../PublishServiceTest.java
git commit -m "feat(config): publish 退化为激活(原地翻 DRAFT→ACTIVE,不重解析)"
```

---

## Task 4: editDraft(原地更新草稿,不增版本)

**Files:**
- Modify: `rule-config-svc/.../publish/PublishService.java`、`api/service/ConfigService.java`、`internal/service/ConfigServiceImpl.java`
- Create: `rule-api/.../web/admin/dto/EditDraftRequest.java`
- Modify: `rule-api/.../web/admin/RuleController.java`
- Test: `PublishServiceTest.java`、`RuleControllerTest.java`

- [ ] **Step 1(test first): PublishServiceTest 加 editDraft 测试**

```java
    @Test
    void editDraft_updatesLatestDraftInPlace_noVersionBump() {
        draftRule.setKind(RuleKind.AST_BOOLEAN);
        draftVersion.setVersion(1L);
        when(ruleDefinitionMapper.selectById(10L)).thenReturn(draftRule);
        when(sceneMapper.selectById(5L)).thenReturn(scene);
        when(ruleVersionMapper.findLatestDraft(10L)).thenReturn(draftVersion);
        when(ruleVersionMapper.updateById((RuleVersion) any())).thenReturn(1);
        MetricDefinition md = new MetricDefinition();
        md.setMetricCode("amount"); md.setDataType("LONG"); md.setStatus(MetricStatus.ACTIVE);
        when(metricDefinitionMapper.findActiveByCodes(any(), any())).thenReturn(List.of(md));

        DraftCreatedResult r = publishService.editDraft(1L, 10L, "新名", RuleKind.AST_BOOLEAN,
                new ConditionNode("GT", "amount", null, Map.of("threshold", 5), 0.0),
                List.of(), List.of(), List.of(), "actor");

        assertThat(r.version()).isEqualTo(1L);
        assertThat(r.status()).isEqualTo("DRAFT");
        ArgumentCaptor<RuleVersion> cap = ArgumentCaptor.forClass(RuleVersion.class);
        verify(ruleVersionMapper).updateById(cap.capture());
        assertThat(cap.getValue().getVersion()).isEqualTo(1L);
        assertThat(((ConditionNode) cap.getValue().getConditionAst()).dataType()).isEqualTo("LONG");
        verify(ruleVersionMapper, never()).insert((RuleVersion) any());
    }

    @Test
    void editDraft_noDraft_throws() {
        when(ruleDefinitionMapper.selectById(10L)).thenReturn(draftRule);
        when(sceneMapper.selectById(5L)).thenReturn(scene);
        when(ruleVersionMapper.findLatestDraft(10L)).thenReturn(null);
        assertThatThrownBy(() -> publishService.editDraft(1L, 10L, "n", RuleKind.AST_BOOLEAN,
                new AndNode(List.of(), null, null), List.of(), List.of(), List.of(), "actor"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("草稿");
    }
```

- [ ] **Step 2: PublishService 加 editDraft**

```java
    /** 原地更新该规则最新 DRAFT 版本内容(不增版本),重跑 resolveAndValidate。仅 DRAFT 可改。 */
    @Transactional
    public DraftCreatedResult editDraft(Long tenantId, Long ruleDefinitionId, String name, RuleKind kind,
            AstNode conditionAst, List<RuleVersionSnapshot.DecisionBinding> decisionBindings,
            List<RuleVersionSnapshot.PreGateConfig> preGates, List<String> triggerEventTypes, String actorId) {
        RuleDefinition rule = ruleDefinitionMapper.selectById(ruleDefinitionId);
        if (rule == null || !tenantId.equals(rule.getTenantId())) {
            throw new IllegalArgumentException("规则不存在: id=" + ruleDefinitionId);
        }
        SceneDef scene = sceneMapper.selectById(rule.getSceneId());
        if (scene == null) throw new IllegalStateException("Scene 不存在: id=" + rule.getSceneId());
        RuleVersion draft = ruleVersionMapper.findLatestDraft(ruleDefinitionId);
        if (draft == null) throw new IllegalStateException("没有可编辑的草稿版本");

        RuleKind effectiveKind = kind != null ? kind : RuleKind.AST_BOOLEAN;
        ResolvedDraft resolved = resolveAndValidate(
                tenantId, scene, effectiveKind, conditionAst, decisionBindings, preGates, triggerEventTypes);

        draft.setConditionAst(resolved.resolvedAst());
        draft.setDecisionBindings(resolved.decisionBindings());
        draft.setPreGates(resolved.preGates());
        draft.setKind(effectiveKind);
        draft.setTriggerEventTypes(resolved.triggerEventTypes());
        draft.setMetricDependencies(resolved.metricDeps());
        draft.setPayloadDependencies(resolved.payloadDeps());
        ruleVersionMapper.updateById(draft);

        if (name != null && !name.isBlank()) { rule.setName(name); }
        rule.setKind(effectiveKind);
        rule.setUpdatedBy(actorId);
        rule.setUpdatedAt(LocalDateTime.now());
        ruleDefinitionMapper.updateById(rule);

        DraftCreatedSnapshot snap = new DraftCreatedSnapshot(rule.getId(), draft.getId());
        eventPublisher.publishEvent(new OperationAuditedEvent(
                tenantId, actorId, "USER", "UPDATE", "rule_definition", rule.getId().toString(),
                snap, snap, LocalDateTime.now()));
        return new DraftCreatedResult(rule.getId(), draft.getId(), draft.getVersion(), RuleDefinitionStatus.DRAFT.name());
    }
```

- [ ] **Step 3: ConfigService 接口 + Impl 加 editDraft**

ConfigService.java 加方法声明(Javadoc 必填):
```java
    DraftCreatedResult editDraft(String tenantId, Long ruleId, String name, String kind,
            AstNode conditionAst, List<DecisionBinding> decisionBindings,
            List<PreGateConfig> preGates, List<String> triggerEventTypes, String actorId);
```
ConfigServiceImpl 实现:解析 kind String→RuleKind(非法抛 IllegalArgumentException "不支持的规则 kind"),委托 `publishService.editDraft`。kind null→传 null(editDraft 内兜底 AST_BOOLEAN)。

```java
    @Override
    public DraftCreatedResult editDraft(String tenantId, Long ruleId, String name, String kind,
            AstNode conditionAst, List<DecisionBinding> decisionBindings,
            List<PreGateConfig> preGates, List<String> triggerEventTypes, String actorId) {
        RuleKind rk = parseKind(kind);
        return publishService.editDraft(Long.valueOf(tenantId), ruleId, name, rk,
                conditionAst, decisionBindings, preGates, triggerEventTypes, actorId);
    }

    /** 解析 kind 字符串为 RuleKind,null/空返回 null(由下游兜底 AST_BOOLEAN),非法抛 IllegalArgumentException。 */
    private static RuleKind parseKind(String kind) {
        if (kind == null || kind.isBlank()) return null;
        try { return RuleKind.valueOf(kind); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("不支持的规则 kind: " + kind); }
    }
```
(ConfigServiceImpl 需 import `RuleKind`。)

- [ ] **Step 4: EditDraftRequest DTO + RuleController PUT 端点**

Create `rule-api/.../web/admin/dto/EditDraftRequest.java`:
```java
package com.sstlfsj.rule.web.admin.dto;

import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot.PreGateConfig;
import com.sstlfsj.rule.kernel.api.model.ast.AstNode;
import jakarta.validation.constraints.NotBlank;
import java.util.List;

/** 编辑规则草稿请求体:原地更新最新 DRAFT 版本(不增版本)。code/sceneCode 为身份不可改。 */
public record EditDraftRequest(
        @NotBlank String tenantId,
        String name,
        String kind,
        AstNode conditionAst,
        List<DecisionBindingInput> decisionBindings,
        List<PreGateConfig> preGates,
        List<String> triggerEventTypes
) {}
```

RuleController 加端点(复用 createDraft 的 DecisionBindingInput→DecisionBinding 映射):
```java
    /** PUT /admin/v1/rules/{ruleId}/draft — 原地编辑规则草稿(不增版本)。 */
    @PutMapping("/{ruleId}/draft")
    public ApiResponse<DraftCreatedResult> editDraft(
            @PathVariable Long ruleId,
            @Valid @RequestBody EditDraftRequest req,
            @RequestHeader("X-Actor-Id") String actorId) {
        List<DecisionBinding> bindings = req.decisionBindings() == null ? null
                : req.decisionBindings().stream().map(i -> new DecisionBinding(i.decisionCode(), 0)).toList();
        return ApiResponse.ok(configService.editDraft(
                req.tenantId(), ruleId, req.name(), req.kind(),
                req.conditionAst(), bindings, req.preGates(), req.triggerEventTypes(), actorId));
    }
```
(加 import `EditDraftRequest`、`PutMapping`。)

- [ ] **Step 5: 跑测试**

Run: `$MVN -pl rule-config-svc -am test -Dtest=PublishServiceTest` 然后 `$MVN -pl rule-api -am test -Dtest=RuleControllerTest`
Expected: PASS。(若 RuleControllerTest 不存在 editDraft 用例,补一条 mock configService.editDraft 返回、断言 200 + 透传参数的用例。)

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat(config): editDraft 原地更新草稿(不增版本)+ PUT /draft 端点"
```

---

## Task 5: newVersion + rollback(fromVersionId 克隆)

**Files:**
- Modify: `PublishService.java`、`ConfigService.java`、`ConfigServiceImpl.java`、`RuleController.java`
- Create: `rule-api/.../web/admin/dto/NewVersionRequest.java`
- Test: `PublishServiceTest.java`、`RuleControllerTest.java`

- [ ] **Step 1(test first): newVersion / rollback 测试**

```java
    @Test
    void newVersion_requiresNoPendingDraft_createsNextVersion() {
        draftRule.setStatus(RuleDefinitionStatus.PUBLISHED);
        when(ruleDefinitionMapper.selectById(10L)).thenReturn(draftRule);
        when(sceneMapper.selectById(5L)).thenReturn(scene);
        when(ruleVersionMapper.findLatestDraft(10L)).thenReturn(null);   // 无待发布草稿
        when(ruleVersionMapper.maxVersion(10L)).thenReturn(1L);
        doAnswer(inv -> { inv.getArgument(0, RuleVersion.class).setId(30L); return 1; })
                .when(ruleVersionMapper).insert(any(RuleVersion.class));
        MetricDefinition md = new MetricDefinition();
        md.setMetricCode("amount"); md.setDataType("LONG"); md.setStatus(MetricStatus.ACTIVE);
        when(metricDefinitionMapper.findActiveByCodes(any(), any())).thenReturn(List.of(md));

        DraftCreatedResult r = publishService.newVersion(1L, 10L, null, RuleKind.AST_BOOLEAN,
                new ConditionNode("GT", "amount", null, Map.of("threshold", 9), 0.0),
                List.of(), List.of(), List.of(), null, "actor");

        assertThat(r.version()).isEqualTo(2L);
        assertThat(r.status()).isEqualTo("DRAFT");
        ArgumentCaptor<RuleVersion> cap = ArgumentCaptor.forClass(RuleVersion.class);
        verify(ruleVersionMapper).insert(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo(RuleVersionStatus.DRAFT);
        assertThat(cap.getValue().getVersion()).isEqualTo(2L);
    }

    @Test
    void newVersion_pendingDraftExists_throws() {
        when(ruleDefinitionMapper.selectById(10L)).thenReturn(draftRule);
        when(sceneMapper.selectById(5L)).thenReturn(scene);
        when(ruleVersionMapper.findLatestDraft(10L)).thenReturn(draftVersion);   // 已有 DRAFT
        assertThatThrownBy(() -> publishService.newVersion(1L, 10L, null, RuleKind.AST_BOOLEAN,
                new AndNode(List.of(), null, null), List.of(), List.of(), List.of(), null, "actor"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("待发布");
    }

    @Test
    void rollback_clonesFromOldVersion_reresolvesAgainstCurrentWorld() {
        draftRule.setStatus(RuleDefinitionStatus.PUBLISHED);
        RuleVersion oldV = new RuleVersion();
        oldV.setId(50L); oldV.setRuleDefinitionId(10L); oldV.setVersion(1L);
        oldV.setConditionAst(new ConditionNode("GT", "amount", "LONG", Map.of("threshold", 1), 0.0));
        oldV.setDecisionBindings(List.of()); oldV.setPreGates(List.of()); oldV.setTriggerEventTypes(List.of());
        when(ruleDefinitionMapper.selectById(10L)).thenReturn(draftRule);
        when(sceneMapper.selectById(5L)).thenReturn(scene);
        when(ruleVersionMapper.findLatestDraft(10L)).thenReturn(null);
        when(ruleVersionMapper.findByIdAndRule(50L, 10L)).thenReturn(oldV);
        when(ruleVersionMapper.maxVersion(10L)).thenReturn(2L);
        doAnswer(inv -> { inv.getArgument(0, RuleVersion.class).setId(40L); return 1; })
                .when(ruleVersionMapper).insert(any(RuleVersion.class));
        MetricDefinition md = new MetricDefinition();
        md.setMetricCode("amount"); md.setDataType("LONG"); md.setStatus(MetricStatus.ACTIVE);
        when(metricDefinitionMapper.findActiveByCodes(any(), any())).thenReturn(List.of(md));

        DraftCreatedResult r = publishService.newVersion(1L, 10L, null, RuleKind.AST_BOOLEAN,
                null, null, null, null, 50L, "actor");

        assertThat(r.version()).isEqualTo(3L);   // v_max+1,克隆 v1 内容
        ArgumentCaptor<RuleVersion> cap = ArgumentCaptor.forClass(RuleVersion.class);
        verify(ruleVersionMapper).insert(cap.capture());
        assertThat(((ConditionNode) cap.getValue().getConditionAst()).metricCode()).isEqualTo("amount");
    }
```

- [ ] **Step 2: PublishService 加 newVersion(fromVersionId 即 rollback)**

```java
    /**
     * 给已发布规则出新版本草稿(v_max+1, DRAFT)。要求当前无未发布 DRAFT(一条规则同时一条 DRAFT)。
     * fromVersionId 非空时为"回退":草稿内容克隆自该版本,按当前世界重跑 resolveAndValidate;激活仍走显式 publish。
     */
    @Transactional
    public DraftCreatedResult newVersion(Long tenantId, Long ruleDefinitionId, String name, RuleKind kind,
            AstNode conditionAst, List<RuleVersionSnapshot.DecisionBinding> decisionBindings,
            List<RuleVersionSnapshot.PreGateConfig> preGates, List<String> triggerEventTypes,
            Long fromVersionId, String actorId) {
        RuleDefinition rule = ruleDefinitionMapper.selectById(ruleDefinitionId);
        if (rule == null || !tenantId.equals(rule.getTenantId())) {
            throw new IllegalArgumentException("规则不存在: id=" + ruleDefinitionId);
        }
        SceneDef scene = sceneMapper.selectById(rule.getSceneId());
        if (scene == null) throw new IllegalStateException("Scene 不存在: id=" + rule.getSceneId());
        if (ruleVersionMapper.findLatestDraft(ruleDefinitionId) != null) {
            throw new IllegalArgumentException("规则已有待发布草稿,请先发布或删除后再出新版本");
        }

        RuleKind effectiveKind = kind != null ? kind : (rule.getKind() != null ? rule.getKind() : RuleKind.AST_BOOLEAN);
        AstNode srcAst = conditionAst;
        List<RuleVersionSnapshot.DecisionBinding> srcBindings = decisionBindings;
        List<RuleVersionSnapshot.PreGateConfig> srcGates = preGates;
        List<String> srcTriggers = triggerEventTypes;
        if (fromVersionId != null) {
            // 回退:克隆旧版本内容(忽略 body 内容字段)
            RuleVersion from = ruleVersionMapper.findByIdAndRule(fromVersionId, ruleDefinitionId);
            if (from == null) {
                throw new IllegalArgumentException("回退源版本不存在: versionId=" + fromVersionId);
            }
            srcAst = from.getConditionAst();
            srcBindings = from.getDecisionBindings();
            srcGates = from.getPreGates();
            srcTriggers = from.getTriggerEventTypes();
            effectiveKind = from.getKind() != null ? from.getKind() : effectiveKind;
        }

        ResolvedDraft resolved = resolveAndValidate(
                tenantId, scene, effectiveKind, srcAst, srcBindings, srcGates, srcTriggers);
        long version = ruleVersionMapper.maxVersion(ruleDefinitionId) + 1;
        RuleVersion rv = buildDraftVersion(ruleDefinitionId, version, resolved);
        ruleVersionMapper.insert(rv);

        if (name != null && !name.isBlank()) { rule.setName(name); }
        rule.setKind(effectiveKind);
        rule.setUpdatedBy(actorId);
        rule.setUpdatedAt(LocalDateTime.now());
        ruleDefinitionMapper.updateById(rule);

        DraftCreatedSnapshot snap = new DraftCreatedSnapshot(rule.getId(), rv.getId());
        eventPublisher.publishEvent(new OperationAuditedEvent(
                tenantId, actorId, "USER", "CREATE", "rule_definition", rule.getId().toString(),
                snap, snap, LocalDateTime.now()));
        return new DraftCreatedResult(rule.getId(), rv.getId(), version, RuleDefinitionStatus.DRAFT.name());
    }
```

- [ ] **Step 3: RuleVersionMapper 加 `findByIdAndRule`**

在 `RuleVersionMapper.java` 加 default 方法:
```java
    /** 按 id + 规则定义 id 查版本(归属隔离),不存在返回 null。 */
    default RuleVersion findByIdAndRule(Long versionId, Long ruleDefinitionId) {
        return selectOne(new LambdaQueryWrapper<RuleVersion>()
                .eq(RuleVersion::getId, versionId)
                .eq(RuleVersion::getRuleDefinitionId, ruleDefinitionId));
    }
```

- [ ] **Step 4: ConfigService/Impl 加 newVersion**

ConfigService.java:
```java
    DraftCreatedResult newVersion(String tenantId, Long ruleId, String name, String kind,
            AstNode conditionAst, List<DecisionBinding> decisionBindings,
            List<PreGateConfig> preGates, List<String> triggerEventTypes,
            Long fromVersionId, String actorId);
```
ConfigServiceImpl:
```java
    @Override
    public DraftCreatedResult newVersion(String tenantId, Long ruleId, String name, String kind,
            AstNode conditionAst, List<DecisionBinding> decisionBindings,
            List<PreGateConfig> preGates, List<String> triggerEventTypes,
            Long fromVersionId, String actorId) {
        return publishService.newVersion(Long.valueOf(tenantId), ruleId, name, parseKind(kind),
                conditionAst, decisionBindings, preGates, triggerEventTypes, fromVersionId, actorId);
    }
```

- [ ] **Step 5: NewVersionRequest DTO + POST /versions 端点**

Create `NewVersionRequest.java`:
```java
package com.sstlfsj.rule.web.admin.dto;

import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot.PreGateConfig;
import com.sstlfsj.rule.kernel.api.model.ast.AstNode;
import jakarta.validation.constraints.NotBlank;
import java.util.List;

/** 出新版本草稿请求体。fromVersionId 非空时为回退(克隆该版本内容,忽略下面的内容字段)。 */
public record NewVersionRequest(
        @NotBlank String tenantId,
        String name,
        String kind,
        AstNode conditionAst,
        List<DecisionBindingInput> decisionBindings,
        List<PreGateConfig> preGates,
        List<String> triggerEventTypes,
        Long fromVersionId
) {}
```
RuleController:
```java
    /** POST /admin/v1/rules/{ruleId}/versions — 出新版本草稿(body 可带 fromVersionId = 回退克隆)。 */
    @PostMapping("/{ruleId}/versions")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<DraftCreatedResult> newVersion(
            @PathVariable Long ruleId,
            @Valid @RequestBody NewVersionRequest req,
            @RequestHeader("X-Actor-Id") String actorId) {
        List<DecisionBinding> bindings = req.decisionBindings() == null ? null
                : req.decisionBindings().stream().map(i -> new DecisionBinding(i.decisionCode(), 0)).toList();
        return ApiResponse.ok(configService.newVersion(
                req.tenantId(), ruleId, req.name(), req.kind(),
                req.conditionAst(), bindings, req.preGates(), req.triggerEventTypes(),
                req.fromVersionId(), actorId));
    }
```

- [ ] **Step 6: 跑测试 + Commit**

Run: `$MVN -pl rule-config-svc -am test -Dtest=PublishServiceTest` + `$MVN -pl rule-api -am test -Dtest=RuleControllerTest`
Expected: PASS。
```bash
git add -A
git commit -m "feat(config): newVersion + rollback(fromVersionId 克隆)+ POST /versions 端点"
```

---

## Task 6: 删草稿(级联)

**Files:**
- Modify: `PublishService.java`、`ConfigService.java`、`ConfigServiceImpl.java`、`RuleController.java`、`RuleVersionMapper.java`
- Test: `PublishServiceTest.java`、`RuleControllerTest.java`

- [ ] **Step 1: RuleVersionMapper 加 `hasNonDraftVersion`/`deleteByRuleDefinitionId`**

```java
    /** 该规则是否存在非 DRAFT 版本(判定"是否曾发布")。 */
    default boolean hasNonDraftVersion(Long ruleDefinitionId) {
        return selectCount(new LambdaQueryWrapper<RuleVersion>()
                .eq(RuleVersion::getRuleDefinitionId, ruleDefinitionId)
                .ne(RuleVersion::getStatus, RuleVersionStatus.DRAFT)) > 0;
    }

    /** 删除该规则下全部版本行,返回删除行数。 */
    default int deleteByRuleDefinitionId(Long ruleDefinitionId) {
        return delete(new LambdaQueryWrapper<RuleVersion>()
                .eq(RuleVersion::getRuleDefinitionId, ruleDefinitionId));
    }
```

- [ ] **Step 2(test first): 删除测试**

```java
    @Test
    void deleteRule_neverPublished_cascadeDeletes() {
        draftRule.setStatus(RuleDefinitionStatus.DRAFT);
        when(ruleDefinitionMapper.selectById(10L)).thenReturn(draftRule);
        when(ruleVersionMapper.hasNonDraftVersion(10L)).thenReturn(false);
        when(ruleVersionMapper.deleteByRuleDefinitionId(10L)).thenReturn(1);
        when(ruleDefinitionMapper.deleteById(10L)).thenReturn(1);

        publishService.deleteRule(1L, 10L, "actor");

        verify(ruleVersionMapper).deleteByRuleDefinitionId(10L);
        verify(ruleDefinitionMapper).deleteById(10L);
    }

    @Test
    void deleteRule_published_rejected() {
        draftRule.setStatus(RuleDefinitionStatus.PUBLISHED);
        when(ruleDefinitionMapper.selectById(10L)).thenReturn(draftRule);
        when(ruleVersionMapper.hasNonDraftVersion(10L)).thenReturn(true);
        assertThatThrownBy(() -> publishService.deleteRule(1L, 10L, "actor"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("已发布");
        verify(ruleDefinitionMapper, never()).deleteById(any());
    }

    @Test
    void deleteDraftVersion_draftStatus_deletesRow() {
        draftVersion.setStatus(RuleVersionStatus.DRAFT);
        when(ruleDefinitionMapper.selectById(10L)).thenReturn(draftRule);
        when(ruleVersionMapper.findByIdAndRule(100L, 10L)).thenReturn(draftVersion);
        when(ruleVersionMapper.deleteById(100L)).thenReturn(1);
        publishService.deleteDraftVersion(1L, 10L, 100L, "actor");
        verify(ruleVersionMapper).deleteById(100L);
    }

    @Test
    void deleteDraftVersion_nonDraft_rejected() {
        draftVersion.setStatus(RuleVersionStatus.ACTIVE);
        when(ruleDefinitionMapper.selectById(10L)).thenReturn(draftRule);
        when(ruleVersionMapper.findByIdAndRule(100L, 10L)).thenReturn(draftVersion);
        assertThatThrownBy(() -> publishService.deleteDraftVersion(1L, 10L, 100L, "actor"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("DRAFT");
        verify(ruleVersionMapper, never()).deleteById(any());
    }
```

- [ ] **Step 3: PublishService 加 deleteRule / deleteDraftVersion**

```java
    /** 删整条未发布规则:仅当从未发布过(无 ACTIVE/SUPERSEDED 版本)→ 级联删 rule_definition + 全部 rule_version。 */
    @Transactional
    public void deleteRule(Long tenantId, Long ruleDefinitionId, String actorId) {
        RuleDefinition rule = ruleDefinitionMapper.selectById(ruleDefinitionId);
        if (rule == null || !tenantId.equals(rule.getTenantId())) {
            throw new IllegalArgumentException("规则不存在: id=" + ruleDefinitionId);
        }
        if (ruleVersionMapper.hasNonDraftVersion(ruleDefinitionId)) {
            throw new IllegalArgumentException("规则已发布过(存在 ACTIVE/SUPERSEDED 版本),不可删除;请改用禁用");
        }
        RuleStatusSnapshot snap = new RuleStatusSnapshot(
                ruleDefinitionId, rule.getStatus().name(), rule.getCurrentVersion());
        ruleVersionMapper.deleteByRuleDefinitionId(ruleDefinitionId);
        ruleDefinitionMapper.deleteById(ruleDefinitionId);
        eventPublisher.publishEvent(new OperationAuditedEvent(
                tenantId, actorId, "USER", "DELETE", "rule_definition", ruleDefinitionId.toString(),
                snap, snap, LocalDateTime.now()));
    }

    /** 删单个待发布草稿版本:仅当该 version 是 DRAFT → 删那条 rule_version(线上 ACTIVE 不动)。 */
    @Transactional
    public void deleteDraftVersion(Long tenantId, Long ruleDefinitionId, Long versionId, String actorId) {
        RuleDefinition rule = ruleDefinitionMapper.selectById(ruleDefinitionId);
        if (rule == null || !tenantId.equals(rule.getTenantId())) {
            throw new IllegalArgumentException("规则不存在: id=" + ruleDefinitionId);
        }
        RuleVersion version = ruleVersionMapper.findByIdAndRule(versionId, ruleDefinitionId);
        if (version == null) {
            throw new IllegalArgumentException("版本不存在: versionId=" + versionId);
        }
        if (version.getStatus() != RuleVersionStatus.DRAFT) {
            throw new IllegalArgumentException("只能删除 DRAFT 版本,当前状态: " + version.getStatus());
        }
        ruleVersionMapper.deleteById(versionId);
        DraftCreatedSnapshot snap = new DraftCreatedSnapshot(ruleDefinitionId, versionId);
        eventPublisher.publishEvent(new OperationAuditedEvent(
                tenantId, actorId, "USER", "DELETE", "rule_version", versionId.toString(),
                snap, snap, LocalDateTime.now()));
    }
```

- [ ] **Step 4: ConfigService/Impl 加 deleteRule / deleteDraftVersion**

ConfigService.java:
```java
    void deleteRule(String tenantId, Long ruleId, String actorId);
    void deleteDraftVersion(String tenantId, Long ruleId, Long versionId, String actorId);
```
ConfigServiceImpl 委托 `publishService.deleteRule(Long.valueOf(tenantId), ruleId, actorId)` / `deleteDraftVersion(...)`。

- [ ] **Step 5: RuleController 加 DELETE 端点**

```java
    /** DELETE /admin/v1/rules/{ruleId} — 删整条未发布规则(级联)。 */
    @DeleteMapping("/{ruleId}")
    public ApiResponse<Void> deleteRule(@PathVariable Long ruleId,
            @RequestParam String tenantId, @RequestHeader("X-Actor-Id") String actorId) {
        configService.deleteRule(tenantId, ruleId, actorId);
        return ApiResponse.ok(null);
    }

    /** DELETE /admin/v1/rules/{ruleId}/versions/{versionId} — 删单个待发布草稿版本。 */
    @DeleteMapping("/{ruleId}/versions/{versionId}")
    public ApiResponse<Void> deleteDraftVersion(@PathVariable Long ruleId, @PathVariable Long versionId,
            @RequestParam String tenantId, @RequestHeader("X-Actor-Id") String actorId) {
        configService.deleteDraftVersion(tenantId, ruleId, versionId, actorId);
        return ApiResponse.ok(null);
    }
```
(加 import `DeleteMapping`。)

- [ ] **Step 6: 跑测试 + Commit**

Run: `$MVN -pl rule-config-svc -am test -Dtest=PublishServiceTest` + `$MVN -pl rule-api -am test -Dtest=RuleControllerTest`
Expected: PASS。
```bash
git add -A
git commit -m "feat(config): 删草稿(整条未发布规则级联 + 单个 DRAFT 版本)+ DELETE 端点"
```

---

## Task 7: dry-run 重设计(ruleId/ruleVersionId 二选一必传,根除副作用 bug)

**Files:**
- Modify: `rule-eval-svc/.../api/service/EvalService.java`、`internal/service/EvalServiceImpl.java`、`internal/repository/RuleVersionReadMapper.java`
- Modify: `rule-api/.../web/api/EvalController.java`
- Test: `rule-eval-svc` 下 EvalServiceImpl 相关测试(若有)、`rule-api/.../web/api/EvalControllerTest.java`

- [ ] **Step 1: RuleVersionReadMapper 加 latestVersionIdByRule**

在 `RuleVersionReadMapper.java` 加(需 import 已有的 `@Param`):
```java
    /** 按 ruleId 取最新版本 id(最高版本号,含 DRAFT),供 dry-run ruleId 模式解析目标。不存在返回 null。 */
    @Select("""
            SELECT rv.id
            FROM rule_version rv
            INNER JOIN rule_definition rd ON rv.rule_definition_id = rd.id
            WHERE rd.tenant_id = #{tenantId} AND rd.id = #{ruleId}
            ORDER BY rv.version DESC
            LIMIT 1
            """)
    Long latestVersionIdByRule(@Param("tenantId") Long tenantId, @Param("ruleId") Long ruleId);
```

- [ ] **Step 2: EvalService.dryRun 改签名**

```java
    /**
     * 执行 dry-run 评估,返回含节点 trace 的结果,不派发 Action、不落 evaluation_session。
     * ruleId / ruleVersionId 二选一必传:都不传抛 IllegalArgumentException(MISSING_DRYRUN_TARGET → 400)。
     *
     * @param event         待评估事件
     * @param ruleId        规则 id(取其最新版本,含 DRAFT);与 ruleVersionId 二选一
     * @param ruleVersionId 精确版本 id;与 ruleId 二选一,优先生效
     * @return 含节点 trace 的评估结果
     */
    EvalResult dryRun(RuleEvent event, Long ruleId, Long ruleVersionId);
```

- [ ] **Step 3: EvalServiceImpl.dryRun 解析目标版本 id(永远非空或抛)**

注入 `RuleVersionReadMapper`(构造器加参,字段保存)。把 dryRun 改为:
```java
    @Override
    public EvalResult dryRun(RuleEvent event, Long ruleId, Long ruleVersionId) {
        Long versionId = resolveDryRunVersionId(event, ruleId, ruleVersionId);
        return doEvaluate(event, EvalMode.PULL, true, versionId);
    }

    /** 解析 dry-run 目标版本 id:ruleVersionId 优先;否则 ruleId 取最新版本;都无则抛 400。 */
    private Long resolveDryRunVersionId(RuleEvent event, Long ruleId, Long ruleVersionId) {
        if (ruleVersionId != null) return ruleVersionId;
        if (ruleId != null) {
            Long tid = parseTenantId(event.tenantId());
            if (tid == null) {
                throw new IllegalArgumentException("MISSING_DRYRUN_TARGET: 无法解析租户");
            }
            Long vid = ruleVersionReadMapper.latestVersionIdByRule(tid, ruleId);
            if (vid == null) {
                throw new IllegalArgumentException("DRYRUN_RULE_NOT_FOUND: 规则无任何版本: ruleId=" + ruleId);
            }
            return vid;
        }
        throw new IllegalArgumentException("MISSING_DRYRUN_TARGET: 必须指定 ruleId 或 ruleVersionId");
    }
```

> `doEvaluate` 的 dry-run 分支保持 `if (isDryRun && specificVersionId != null)`。因 dryRun 现保证 versionId 非空(否则已抛),结构上 dry-run 永不落到候选分支——副作用 bug 根除。候选分支(line 90+)仅真评估 `isDryRun=false` 走,**保持原样不改**。

构造器改造(EvalServiceImpl 现有构造器加 `RuleVersionReadMapper ruleVersionReadMapper` 参数并赋值字段)。注意 `EvalActionDispatcher` lambda 仍 `doEvaluate(e, EvalMode.PUSH, false, null)` 不变。

- [ ] **Step 4: EvalController.dryRun 加 ruleId 参数**

```java
    @PostMapping("/dry-run")
    public ApiResponse<EvalResult> dryRun(
            @RequestBody EvalEventRequest req,
            @RequestParam(required = false) Long ruleId,
            @RequestParam(required = false) Long ruleVersionId) {
        return ApiResponse.ok(evalService.dryRun(toEvent(req), ruleId, ruleVersionId));
    }
```
更新 Javadoc:`ruleId`/`ruleVersionId` 二选一必传。

- [ ] **Step 5: 更新调用方与测试**

- `EvalControllerTest.dryRun_returns200_withResult`:mock `evalService.dryRun(any(), any(), any())`,请求带 `?ruleVersionId=1` 或 `?ruleId=1`,断言 200。补一条 `dryRun_missingTarget_returns400`:不带任何 param,mock `dryRun` 抛 `IllegalArgumentException("MISSING_DRYRUN_TARGET...")`,断言 400(配 `GlobalExceptionHandler`)。
- 全仓 grep `\.dryRun(` 找其他调用方(测试/SDK),更新为三参签名。

- [ ] **Step 6: 跑测试**

Run: `$MVN -pl rule-eval-svc -am test` + `$MVN -pl rule-api -am test -Dtest=EvalControllerTest`
Expected: PASS。

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "fix(eval): dry-run 二选一必传 + 永远解析版本 id,结构上根除副作用 bug"
```

---

## Task 8: 文档更新

改文档**前**先跑 `doc-consistency-review` skill 扫自洽性。

**Files:**
- Modify: `docs/00-decisions.md`(追加生命周期决策:premise A、publish 激活语义、删草稿边界、dry-run 二选一)
- Modify: `docs/10-api-contract.md`(新端点 PUT /draft、POST /versions、DELETE /{ruleId}、DELETE /versions/{versionId};dry-run 改 ruleId/ruleVersionId)
- Modify: `docs/01-concepts.md` / `docs/02-runtime.md`(草稿即冻结快照语义、版本号规则)
- Modify: `docs/superpowers/specs/2026-06-10-rule-draft-version-lifecycle-design.md`(§六 级联范围口径修正,见 Step 4)
- Modify: `docs/functional-test-coverage.md`:更新 dry-run 行 + 新增生命周期流程行

- [ ] **Step 1: 追加 00-decisions 条目**(append-only,不改历史条目)
- [ ] **Step 2: 更新 10-api-contract 端点表 + dry-run 参数**
- [ ] **Step 3: 更新 01/02 草稿语义**
- [ ] **Step 4: 修正 spec §六 删草稿级联范围口径(选项 A:dry-run 痕迹当历史,不级联删)**

spec §六 现论据"草稿从未激活 → 无 evaluation_session/node_trace/action_execution 引用"把"未激活"与"未评估"混为一谈——草稿可被 dry-run,会经 `DryRunRecordedEvent` 落 `dry_run_session` + `dry_run_node_trace`(按 ruleVersionId 关联)。改写 §六"级联范围只 rule_version"那一段为:

> **级联范围只 `rule_version`**:草稿从未激活 → 无 `evaluation_session`/`node_trace`/`action_execution` 引用、未进 Matcher 索引。**dry-run 痕迹(`dry_run_session`/`dry_run_node_trace`)不级联删**——视同审计历史(记录"某时刻对版本 X 做过 dry-run"),靠 `SessionRetentionCleaner` 的 TTL 退休自然清理;删除后这些行的 ruleVersionId 成悬空引用(无 FK 约束,不报错;会话查询 join 不到版本时取空,可接受)。`audit_log` 同理不动。

实现侧据此不在 `deleteRule`/`deleteDraftVersion`(Task 6)里触碰 dry_run 两表——Task 6 已是只删 rule_version/rule_definition,**无需改代码**,仅文档口径对齐。

- [ ] **Step 5: Commit**
```bash
git add docs/
git commit -m "docs: 规则草稿/版本生命周期(premise A + publish 激活 + 删草稿 + dry-run 二选一)"
```

---

## Task 9: 全量回归 + 功能测试(真服务端到端)

- [ ] **Step 1: 全量 clean test 兜底**

Run: `$MVN clean test`(无 `-pl`,强制重编译所有 test 类)
Expected: BUILD SUCCESS,全模块绿。

- [ ] **Step 2: 起真实服务跑端到端(参考 CLAUDE.md 功能测试纪律 + docs/examples/)**

打可执行包运行(别用 reactor 内 run 目标)。按依赖顺序:建 scene(payloadSchema/eventTypes)→ 建 ACTIVE metric → 建 decision → 走生命周期剧本:
1. `POST /admin/v1/rules` 建草稿 → 查 `rule_version` 确认**冻结真落库**(condition_ast 含 dataType、metric_dependencies/payload_dependencies 非空、decision_bindings 含 name/actions)。
2. `PUT /admin/v1/rules/{id}/draft` editDraft → 查版本号不变、内容变。
3. `POST /admin/v1/rules/{id}/publish` → 查 DRAFT 行原地翻 ACTIVE(version 不变)、rule_definition PUBLISHED + current_version 指向该行。
4. `POST /admin/v1/rules/{id}/versions` newVersion(v2)→ 查新 DRAFT 行 version=2。
5. `POST /api/v1/rule/dry-run?ruleId={id}` 试跑最新(v2 草稿)→ 结果忠实;**查 `evaluation_session` 无新增、`action_execution` 无新增**(无副作用回归断言)。
6. `POST /admin/v1/rules/{id}/versions` body `{"fromVersionId": <v1 versionId>}` 回退 → 出 v3 DRAFT,内容克隆 v1 + 按当前世界重解析。
7. `publish` v3 → 验线上回到旧逻辑(supersede v1 ACTIVE 行)。
8. 另建一条未发布草稿规则 → `DELETE /admin/v1/rules/{id}` → 查 rule_definition + rule_version 级联删净。
9. dry-run 不带任何 target → 400 `MISSING_DRYRUN_TARGET`。

- [ ] **Step 3: DB 字段落库审计**:对本轮改动点专项验证(冻结列真落库、删草稿真级联、dry-run 真无副作用)。逐表查恒空字段并分类。

- [ ] **Step 4: 清理测试数据**,恢复干净基线。

- [ ] **Step 5(可选): 派 rule-engine-reviewer agent 审"代码 ↔ 文档对齐"**(改了 docs/** 与 rule-*/ 代码)。

---

## Self-Review(spec 覆盖核对)

- §二 版本模型(createDraft v1 / editDraft 不增 / newVersion +1 / publish 激活不增 / rollback +1):Task 2/3/4/5 ✅
- §三 resolveAndValidate 抽取 + publish 不再调用它:Task 1/3 ✅
- §四 dry-run 二选一必传 + 结构根除副作用:Task 7 ✅
- §五 newVersion / rollback(fromVersionId 克隆,默认显式 publish):Task 5 ✅(未做自动 publish 开关——§七 API 面未列独立 rollback 端点,YAGNI 不实现)
- §六 删草稿(整条未发布级联 + 单个 DRAFT 版本;碰 ACTIVE/SUPERSEDED 拒):Task 6 ✅
- §六 级联范围口径:出站引用(metric/decision/scene/payload)全是 rule_version 冻结快照,删行即净;入站 dry-run 痕迹(dry_run_session/dry_run_node_trace)选项 A 当历史不级联删、靠 TTL 退休 → Task 8 Step 4 修正 spec 文字 ✅
- §6.1 删除边界:删行不碰被引用实体;资源无硬删除(本 spec 不实现资源硬删除,仅文档立约束)→ Task 8 docs ✅
- §七 API 面 7 行:createDraft(改)/editDraft(PUT)/newVersion(POST)/publish(改)/deleteRule(DELETE)/deleteVersion(DELETE)/dry-run(改)→ Task 4/5/6/7 ✅
- §九 影响面(rule-config-svc/rule-api/rule-eval-svc/docs/DB 无迁移)✅
- §十 测试矩阵:Task 2/3/4/5/6/7 单测 + Task 9 功能测试 ✅

**类型一致性核对**:`ResolvedDraft` 字段在 createDraft/editDraft/newVersion/publish 一致使用;`resolveAndValidate` 签名 7 参在三处调用一致;`findByIdAndRule(versionId, ruleDefinitionId)` 在 newVersion/deleteDraftVersion 一致;dry-run 三参 `(event, ruleId, ruleVersionId)` 在 EvalService/Impl/Controller 一致。
