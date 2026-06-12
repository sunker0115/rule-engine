# EXPRESSION_SCRIPT config-svc 写路径 Implementation Plan(Plan 4b)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development(推荐)或 superpowers:executing-plans 逐任务实现。步骤用 checkbox(`- [ ]`)跟踪。

**Goal:** 让运营能经 API **创建/编辑/发布** EXPRESSION_SCRIPT 规则:`script` 从 controller 请求 DTO 一路透传到 `PublishService`;`resolveAndValidate` 加 EXPRESSION_SCRIPT 分支——**引擎无关层**:用 `ExpressionEngine.compile` 做语法校验 + 从 `referencedVariables()` 拆出 metric/payload 依赖并冻结(复用既有 ACTIVE-metric / payloadSchema 校验),`conditionAst=null`。`RuleVersion` 实体加 `scriptSource` 列字段持久化。

**Architecture:** 写路径分两层(本 plan 只做**引擎无关层**;**typed 类型检查是 Plan 4c**)。引擎无关层 = 语法 compile + `referencedVariables()` 冻依赖 + 决策码绑定冻结(决策码 ⊆ bindings 的运行期兜底已在 ScriptExecutor)。script 的 metric/payload 依赖不走 AST collector(脚本无 AST),改从 `referencedVariables()`(形如 `metrics.x`/`payload.x`)按前缀拆,喂给抽取出的 `freezeMetricDeps`/`freezePayloadDeps` 复用既有冻结逻辑。

**Tech Stack:** Java 25、Spring Boot 4、MyBatis-Plus(`Jackson3TypeHandler`)、`dev.cel`(经 expression-cel starter 注入 `ExpressionEngine`)、JUnit5、`$MVN`(mvn-env,JDK 25)。

**前置依赖:** Plan 2(`ScriptSource`/`ExpressionEngine` SPI)、Plan 3(`CelExpressionEngine` + starter)、Plan 4a(`script_source` 列 V1_28 + kernel 读路径 + eval-svc 运行装配)均已完成。

**plan 序列定位:** 第 5 个 plan(4b)。后续 Plan 4c(发布期 typed 校验:`ExpressionEngine` 加 `default typeCheck`,CEL 用 `StructType` from dataType,弱引擎降级 no-op)/ Plan 5(SDK opt-in + API 契约 doc)。

---

## 关键事实(实现者必读)

- `mvn-env`(JDK 25),`$MVN`。跨模块带 `-am`,收尾 `$MVN clean test`。中文注释,public 写 Javadoc。不得 `-DskipTests`。
- **写路径链**:`CreateRuleRequest`/`EditDraftRequest`/`NewVersionRequest`(`rule-api/.../web/admin/dto/`,record,含 `AstNode conditionAst`)→ `RuleController`(`/admin/v1/rules`)→ `ConfigService`/`ConfigServiceImpl` → `PublishService.{createDraft,editDraft,newVersion}` → `resolveAndValidate` + `buildDraftVersion`。
- `PublishService.resolveAndValidate(tenantId, SceneDef scene, RuleKind kind, AstNode conditionAst, List<DecisionBinding> rawBindings, List<PreGateConfig> preGates, List<String> triggerEventTypes)` → `ResolvedDraft(kind, resolvedAst, decisionBindings, preGates, triggerEventTypes, metricDeps, payloadDeps)`。当前 validKinds = {AST_BOOLEAN,SCORECARD,DECISION_TREE,DECISION_TABLE}(EXPRESSION_SCRIPT 会被拒,见 `PublishService.java:362`)。
- 既有冻依赖逻辑在 `resolveAndValidate` 内联(metric:行 374-400 查 ACTIVE + 冻版本;payload:行 402-419 查 payloadSchema)。本 plan **抽成私有方法** `freezeMetricDeps`/`freezePayloadDeps` 供 AST 路径与 script 路径共用。
- `RuleVersion` 实体(`rule-config-svc/.../domain/RuleVersion.java`,`@TableName(autoResultMap=true)`):字段用 `@TableField(typeHandler = Jackson3TypeHandler.class)`(模板:`conditionAst`/`decisionBindings`)。Plan 4a 已建 `script_source` 列;本 plan 加实体字段 `scriptSource`。
- config-svc 当前**不依赖**表达式引擎;本 plan 加依赖 `rule-expression-cel-spring-boot-starter`(注入 `ExpressionEngine` bean)。
- `ScriptSource(source, lang)` 在 `com.sstlfsj.rule.kernel.api.model`;`ExpressionEngine`(`lang()`/`compile`/`evaluate`)+ `CompiledExpression.referencedVariables()` 在 `com.sstlfsj.rule.kernel.api.spi.expression`;`ExpressionCompileException` 同包。
- **本 plan 不做**:typed 类型检查(Plan 4c)、script 返回决策码的静态枚举校验(运行期 `INVALID_DECISION_CODE` 兜底已在 Plan 2)、SDK(Plan 5)。

---

## Task 1: `RuleVersion` 实体加 `scriptSource` + config-svc 依赖引擎 starter

**Files:**
- Modify: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/domain/RuleVersion.java`
- Modify: `rule-config-svc/pom.xml`

- [ ] **Step 1: 实体加字段**

在 `RuleVersion` 的 `payloadDependencies` 字段后加(import `com.sstlfsj.rule.kernel.api.model.ScriptSource;`):

```java
    /** EXPRESSION_SCRIPT 规则的脚本载体;其它 kind 为 null。 */
    @TableField(typeHandler = Jackson3TypeHandler.class)
    private ScriptSource scriptSource;
```

- [ ] **Step 2: config-svc pom 依赖引擎 starter**

`rule-config-svc/pom.xml` `<dependencies>` 加(version 由根 dependencyManagement 管理):

```xml
        <!-- 发布期脚本语法校验 + referencedVariables 冻依赖:经 starter 注入 ExpressionEngine -->
        <dependency>
            <groupId>com.sstlfsj.rule</groupId>
            <artifactId>rule-expression-cel-spring-boot-starter</artifactId>
        </dependency>
```

- [ ] **Step 3: 编译**

Run: `$MVN -pl rule-config-svc -am test-compile`
Expected: BUILD SUCCESS。

- [ ] **Step 4: 提交**

```bash
git add rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/domain/RuleVersion.java rule-config-svc/pom.xml
git commit -m "feat(config): RuleVersion.scriptSource 实体字段 + 依赖 expression-cel starter"
```

---

## Task 2: `PublishService` 抽出 `freezeMetricDeps`/`freezePayloadDeps`(行为保持重构)

**Files:**
- Modify: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/publish/PublishService.java`

> 把 `resolveAndValidate` 内联的 metric/payload 冻结逻辑抽成私有方法,供 AST 与 script 路径共用。**纯重构,既有测试须仍绿**。

- [ ] **Step 1: 抽方法**

在 `PublishService` 加两个私有方法(把 `resolveAndValidate` 行 374-419 的逻辑迁入,签名如下):

```java
    /**
     * 按 metricCode 列表查 ACTIVE 定义,冻结 (code, version) 依赖,并产出 code→dataType 映射 + 安全校验。
     *
     * @param tenantId    租户 id
     * @param metricCodes 被引用的 metric code(去重)
     * @param dataTypeMap [出参] 填充 code→dataType(供 AST 路径 AstDataTypeResolver 用;script 路径可忽略)
     * @return 冻结的 metric 依赖
     */
    private List<MetricDependency> freezeMetricDeps(Long tenantId, List<String> metricCodes,
                                                    Map<String, String> dataTypeMap) {
        List<MetricDependency> metricDeps = new ArrayList<>();
        if (metricCodes.isEmpty()) return metricDeps;
        List<MetricDefinition> metricDefs = metricDefinitionMapper.findActiveByCodes(tenantId, metricCodes);
        Map<String, MetricDefinition> activeByCode = new HashMap<>();
        for (MetricDefinition m : metricDefs) {
            if (activeByCode.putIfAbsent(m.getMetricCode(), m) != null) {
                throw new IllegalArgumentException("metric 存在多个 ACTIVE 版本,数据异常: " + m.getMetricCode());
            }
        }
        for (String code : metricCodes) {
            MetricDefinition m = activeByCode.get(code);
            if (m == null) throw new IllegalArgumentException("被引用的 metric 无 ACTIVE 版本: " + code);
            metricDeps.add(new MetricDependency(code, m.getVersion() == null ? 1 : m.getVersion()));
        }
        dataTypeMap.putAll(activeByCode.values().stream()
                .collect(Collectors.toMap(MetricDefinition::getMetricCode, MetricDefinition::getDataType)));
        java.util.Set<String> dsNames = metricResourceCatalog != null ? metricResourceCatalog.datasourceNames() : null;
        java.util.Set<String> epNames = metricResourceCatalog != null ? metricResourceCatalog.endpointNames() : null;
        new MetricSafetyValidator().validate(new ArrayList<>(activeByCode.values()), dsNames, epNames);
        return metricDeps;
    }

    /**
     * 按 payload 字段列表查 scene.payloadSchema 声明,冻结依赖,并产出 field→dataType 映射。
     *
     * @param scene          所属场景
     * @param payloadFields  被引用的 payload 字段(去重)
     * @param payloadTypeMap [出参] 填充 field→dataType
     * @return 冻结的 payload 依赖
     */
    private List<PayloadDependency> freezePayloadDeps(SceneDef scene, List<String> payloadFields,
                                                      Map<String, String> payloadTypeMap) {
        List<PayloadDependency> payloadDeps = new ArrayList<>();
        if (payloadFields.isEmpty()) return payloadDeps;
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
        return payloadDeps;
    }
```

- [ ] **Step 2: `resolveAndValidate` 的 AST 路径改用新方法**

把 `resolveAndValidate` 内 metric 收集段(行 374-400)替换为:

```java
        List<String> metricCodes = MetricDependencyCollector.collect(ast);
        Map<String, String> dataTypeMap = new HashMap<>();
        List<MetricDependency> metricDeps = freezeMetricDeps(tenantId, metricCodes, dataTypeMap);
```

把 payload 收集段(行 402-419)替换为:

```java
        List<String> payloadFields = PayloadFieldCollector.collect(ast);
        Map<String, String> payloadTypeMap = new HashMap<>();
        List<PayloadDependency> payloadDeps = freezePayloadDeps(scene, payloadFields, payloadTypeMap);
```

(其余 `AstDataTypeResolver.resolve` / `freezeDecisionBindings` / return 不变。)

- [ ] **Step 3: config-svc 全量测试(验证重构零行为变更)**

Run: `$MVN -pl rule-config-svc -am test`
Expected: BUILD SUCCESS,既有发布/草稿测试全绿。

- [ ] **Step 4: 提交**

```bash
git add rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/publish/PublishService.java
git commit -m "refactor(config): 抽出 freezeMetricDeps/freezePayloadDeps 供 AST 与 script 路径共用"
```

---

## Task 3: `resolveAndValidate` 加 `script` 参 + EXPRESSION_SCRIPT 分支 + `ResolvedDraft.scriptSource`

**Files:**
- Modify: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/publish/PublishService.java`
- Test: `rule-config-svc/src/test/java/com/sstlfsj/rule/config/publish/ScriptResolveValidateTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.sstlfsj.rule.config.publish;

import com.sstlfsj.rule.kernel.api.model.RuleKind;
import com.sstlfsj.rule.kernel.api.model.ScriptSource;
import com.sstlfsj.rule.kernel.expression.cel.CelExpressionEngine;
import com.sstlfsj.rule.kernel.api.spi.expression.ExpressionEngine;
// ... 其余按 PublishService 现有测试的依赖装配(mapper mock、scene 等)引入

// 说明:本测试验证 EXPRESSION_SCRIPT 分支——
// ① 脚本引用 metrics.X/payload.Y 经 referencedVariables 冻成依赖;
// ② conditionAst 为 null;③ 语法错抛 IllegalArgumentException/编译异常。
// 装配方式参照同包既有 PublishService 测试(构造 PublishService 注入 mock mapper + 真实 CelExpressionEngine)。
```

> 注:PublishService 测试的精确装配(mock 哪些 mapper、如何造 SceneDef + payloadSchema + ACTIVE metric)参照同包既有测试(如 `PublishServiceTest`)。本测试要点:用真实 `new CelExpressionEngine()` 注入,脚本 `"metrics.txn_cnt_1d > 50 && payload.amount > 0 ? 'REVIEW' : 'PASS'"`,断言 `resolved.metricDeps()` 含 `txn_cnt_1d`、`payloadDeps()` 含 `amount`、`resolvedAst()` 为 null、`scriptSource()` 等于入参。

- [ ] **Step 2: 跑测试确认失败**

Run: `$MVN -pl rule-config-svc -am test -Dtest=ScriptResolveValidateTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败(`resolveAndValidate` 签名无 script 参 / `ResolvedDraft.scriptSource` 不存在)。

- [ ] **Step 3: `ResolvedDraft` 加 scriptSource**

```java
    public record ResolvedDraft(
            RuleKind kind,
            AstNode resolvedAst,
            List<RuleVersionSnapshot.DecisionBinding> decisionBindings,
            List<RuleVersionSnapshot.PreGateConfig> preGates,
            List<String> triggerEventTypes,
            List<MetricDependency> metricDeps,
            List<PayloadDependency> payloadDeps,
            ScriptSource scriptSource) {
    }
```

- [ ] **Step 4: 注入引擎 + `resolveAndValidate` 加 script 参 + 分支**

4a. PublishService 加字段(构造注入 `List<ExpressionEngine>` → 按 lang 建 map,镜像 eval-svc 的 ScriptExecutor 装配):

```java
    private final Map<String, ExpressionEngine> expressionEngines;
```
在构造器形参加 `List<ExpressionEngine> expressionEngines`,体内:
```java
        Map<String, ExpressionEngine> byLang = new HashMap<>();
        if (expressionEngines != null) {
            for (ExpressionEngine e : expressionEngines) byLang.put(e.lang(), e);
        }
        this.expressionEngines = byLang;
```

4b. `resolveAndValidate` 签名末尾加 `ScriptSource script`:
```java
    public ResolvedDraft resolveAndValidate(
            Long tenantId, SceneDef scene, RuleKind kind,
            AstNode conditionAst,
            List<RuleVersionSnapshot.DecisionBinding> rawBindings,
            List<RuleVersionSnapshot.PreGateConfig> preGates,
            List<String> triggerEventTypes,
            ScriptSource script) {
```

4c. validKinds 纳入 EXPRESSION_SCRIPT(行 362-364):
```java
        java.util.Set<String> validKinds = java.util.Set.of(
                RuleKind.AST_BOOLEAN.tag(), RuleKind.SCORECARD.tag(),
                RuleKind.DECISION_TREE.tag(), RuleKind.DECISION_TABLE.tag(),
                RuleKind.EXPRESSION_SCRIPT.tag());
```

4d. 在 kind 校验后、AST 处理前,插入 EXPRESSION_SCRIPT 分支(命中则提前 return,不走 AST 收集/解析):
```java
        if (RuleKind.EXPRESSION_SCRIPT.tag().equals(kindTag)) {
            return resolveScriptDraft(tenantId, scene, script, bindings, gates, triggers);
        }
```

4e. 加 `resolveScriptDraft` 私有方法:
```java
    /**
     * EXPRESSION_SCRIPT 分支:引擎无关层校验。语法 compile + referencedVariables 冻依赖,conditionAst=null。
     * typed 类型检查在 Plan 4c(发布期 typeCheck)。
     */
    private ResolvedDraft resolveScriptDraft(Long tenantId, SceneDef scene, ScriptSource script,
            List<RuleVersionSnapshot.DecisionBinding> bindings,
            List<RuleVersionSnapshot.PreGateConfig> gates, List<String> triggers) {
        if (script == null || script.source() == null || script.source().isBlank()) {
            throw new IllegalArgumentException("EXPRESSION_SCRIPT 规则必须提供非空脚本");
        }
        ExpressionEngine engine = expressionEngines.get(script.lang());
        if (engine == null) {
            throw new IllegalArgumentException("无对应表达式引擎,lang=" + script.lang());
        }
        java.util.Set<String> refVars;
        try {
            refVars = engine.compile(script.source()).referencedVariables();   // 语法/编译失败抛 ExpressionCompileException
        } catch (com.sstlfsj.rule.kernel.api.spi.expression.ExpressionCompileException e) {
            throw new IllegalArgumentException("脚本编译失败: " + e.getMessage(), e);
        }
        // referencedVariables 形如 metrics.x / payload.y / subject.z;按前缀拆(subject.* 开放,不校验/不冻)
        List<String> metricCodes = stripPrefix(refVars, "metrics.");
        List<String> payloadFields = stripPrefix(refVars, "payload.");
        List<MetricDependency> metricDeps = freezeMetricDeps(tenantId, metricCodes, new HashMap<>());
        List<PayloadDependency> payloadDeps = freezePayloadDeps(scene, payloadFields, new HashMap<>());

        validateTriggerEventTypes(triggers, scene.getEventTypes());
        validatePreGateParams(gates);
        List<RuleVersionSnapshot.DecisionBinding> frozenBindings = freezeDecisionBindings(tenantId, scene, bindings);
        // resolvedAst=null:脚本规则不进 AST;script 原样冻入
        return new ResolvedDraft(RuleKind.EXPRESSION_SCRIPT, null, frozenBindings, gates, triggers,
                metricDeps, payloadDeps, script);
    }

    /** 从点路径集合按前缀(如 "metrics.")过滤并去前缀,去重保序。 */
    private static List<String> stripPrefix(java.util.Set<String> refVars, String prefix) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        for (String v : refVars) {
            if (v.startsWith(prefix)) out.add(v.substring(prefix.length()));
        }
        return new ArrayList<>(out);
    }
```

4f. AST 路径的 return(行 426)补 scriptSource=null:
```java
        return new ResolvedDraft(kind, resolvedAst, frozenBindings, gates, triggers, metricDeps, payloadDeps, null);
```

- [ ] **Step 5: 跑测试确认通过**

Run: `$MVN -pl rule-config-svc -am test -Dtest=ScriptResolveValidateTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS。

- [ ] **Step 6: 提交**

```bash
git add rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/publish/PublishService.java \
        rule-config-svc/src/test/java/com/sstlfsj/rule/config/publish/ScriptResolveValidateTest.java
git commit -m "feat(config): resolveAndValidate EXPRESSION_SCRIPT 分支(引擎 compile + refVars 冻依赖)"
```

---

## Task 4: `buildDraftVersion` + `publish` snapshot + 三个 lifecycle 方法透传 script

**Files:**
- Modify: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/publish/PublishService.java`

- [ ] **Step 1: `buildDraftVersion` 写 scriptSource**

在 `buildDraftVersion` 末尾(`rv.setStatus(...)` 前)加:
```java
        rv.setScriptSource(r.scriptSource());
```

- [ ] **Step 2: `createDraft`/`editDraft`/`newVersion` 加 script 参并传入 resolveAndValidate**

- `createDraft(...)` 签名末尾加 `ScriptSource script`;调用 `resolveAndValidate(tenantId, scene, effectiveRuleKind, conditionAst, decisionBindings, preGates, triggerEventTypes, script)`。
- `editDraft(...)` 同样加 `ScriptSource script` 参;调用 resolveAndValidate 传 script;并把 `draft.setConditionAst(resolved.resolvedAst())` 之后加 `draft.setScriptSource(resolved.scriptSource())`。
- `newVersion(...)` 加 `ScriptSource script` 参;新建草稿路径传 script(回退 fromVersionId 路径:克隆旧版本的 scriptSource,与克隆 conditionAst 同处理)。

- [ ] **Step 3: `publish` snapshot 带 script(行 109-113)**

`publish` 里构造返回 snapshot 处,改用带 script 的 13 参构造(末参 `draft.getScriptSource()`):
```java
        RuleVersionSnapshot snapshot = new RuleVersionSnapshot(
                draft.getId(), scene.getCode(), String.valueOf(tenantId),
                draft.getConditionAst(), List.of(), List.of(), List.of(),
                kind.name(), rule.getCode(), draft.getVersion(),
                draft.getMetricDependencies(), draft.getPayloadDependencies(),
                draft.getScriptSource());
```

- [ ] **Step 4: config-svc 编译**

Run: `$MVN -pl rule-config-svc -am test-compile`
Expected: 编译错误集中在 createDraft/editDraft/newVersion 的调用方(ConfigServiceImpl)——Task 5 修。本步先确认 PublishService 自身编译通过(其内部调用一致)。

- [ ] **Step 5: 提交**

```bash
git add rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/publish/PublishService.java
git commit -m "feat(config): 草稿生命周期 + publish snapshot 透传 scriptSource"
```

---

## Task 5: `ConfigService` 接口 + `ConfigServiceImpl` 透传 script

**Files:**
- Modify: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/service/ConfigService.java`
- Modify: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/service/ConfigServiceImpl.java`

- [ ] **Step 1: 接口加 script 参**

`ConfigService` 的 `createDraft`/`editDraft`/`newVersion` 签名末尾各加 `ScriptSource script`(import `com.sstlfsj.rule.kernel.api.model.ScriptSource;`),Javadoc 补 `@param script EXPRESSION_SCRIPT 脚本载体,其它 kind 传 null`。

- [ ] **Step 2: Impl 透传**

`ConfigServiceImpl` 三个方法加 `ScriptSource script` 形参,传给 `publishService.{createDraft,editDraft,newVersion}(..., script)`(createDraft/editDraft 末参 script;newVersion 在 fromVersionId 之后或之前按签名顺序加 script——与 PublishService Task 4 的形参顺序一致)。

- [ ] **Step 3: 编译**

Run: `$MVN -pl rule-config-svc -am test-compile`
Expected: 编译错误转移到 rule-api 的 RuleController 调用点——Task 6 修。PublishService/ConfigServiceImpl 链一致即可。

- [ ] **Step 4: 提交**

```bash
git add rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/service/ConfigService.java \
        rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/service/ConfigServiceImpl.java
git commit -m "feat(config): ConfigService create/edit/newVersion 透传 script"
```

---

## Task 6: 请求 DTO + `RuleController` 带 script

**Files:**
- Modify: `rule-api/src/main/java/com/sstlfsj/rule/web/admin/dto/CreateRuleRequest.java`
- Modify: `rule-api/src/main/java/com/sstlfsj/rule/web/admin/dto/EditDraftRequest.java`
- Modify: `rule-api/src/main/java/com/sstlfsj/rule/web/admin/dto/NewVersionRequest.java`
- Modify: `rule-api/src/main/java/com/sstlfsj/rule/web/admin/RuleController.java`
- Test: `rule-api/src/test/java/com/sstlfsj/rule/web/admin/RuleControllerScriptTest.java`

- [ ] **Step 1: 三个请求 DTO 加 `script` 字段**

各 record 末尾加分量(import `com.sstlfsj.rule.kernel.api.model.ScriptSource;`):
```java
        ScriptSource script
```
(`CreateRuleRequest`/`EditDraftRequest`/`NewVersionRequest` 都加;EXPRESSION_SCRIPT 时 conditionAst 传 null、script 非空。)

- [ ] **Step 2: `RuleController` 三个端点传 `req.script()`**

`createDraft`/`editDraft`/`newVersion` 三处 `configService.xxx(...)` 调用末尾(与接口 script 形参位置一致)加 `req.script()`。

- [ ] **Step 3: 写 controller 测试(MockMvc 建脚本规则)**

```java
// RuleControllerScriptTest:POST /admin/v1/rules,body kind=EXPRESSION_SCRIPT + script={source,lang},
// conditionAst=null;mock configService.createDraft 断言收到的 script 参非空、conditionAst 为 null。
// 装配参照同包既有 RuleControllerTest(@WebMvcTest + MockBean configService)。
```

- [ ] **Step 4: 跑测试 + rule-api 全量**

Run: `$MVN -pl rule-api -am test`
Expected: BUILD SUCCESS。

- [ ] **Step 5: 提交**

```bash
git add rule-api/src/main/java/com/sstlfsj/rule/web/admin/dto/ \
        rule-api/src/main/java/com/sstlfsj/rule/web/admin/RuleController.java \
        rule-api/src/test/java/com/sstlfsj/rule/web/admin/RuleControllerScriptTest.java
git commit -m "feat(api): 规则请求 DTO + RuleController 带 script(EXPRESSION_SCRIPT 写入口)"
```

---

## Task 7: 全量兜底 + 功能测试剧本(真 DB 端到端)

**Files:** 无(验证)+ 可选 `docs/examples/` curl 剧本

- [ ] **Step 1: 全量 clean test**

Run: `$MVN clean test`
Expected: BUILD SUCCESS(全模块)。

- [ ] **Step 2: 真服务端到端(CLAUDE.md 功能测试纪律)**

打包起真实服务,按 `docs/examples/` 剧本:建 scene(声明 payloadSchema)+ ACTIVE metric + decision → 建 EXPRESSION_SCRIPT 规则(`script={source:"metrics.txn_cnt_1d > 50 && payload.amount > 10000 ? 'REVIEW' : 'PASS'", lang:"CEL"}`)→ publish → 发评估事件 → 断言出 `REVIEW`/`PASS`。
**写后核对真落库**:`rule_version.script_source` 真写入(非 null)、`metric_dependencies`/`payload_dependencies` 从 refVars 冻入(含 `txn_cnt_1d`/`amount`)、`condition_ast` 为 null。验证完清理测试数据。

- [ ] **Step 3: 未越界核查**

Run: `git diff --name-only <Plan4b 起点>..HEAD | grep -v '^docs/'`
Expected: 仅 rule-config-svc(实体/pom/PublishService/ConfigService/Impl)+ rule-api(DTO/controller/test)。未碰 kernel/eval-svc/expression-cel。

---

## Self-Review(已执行)

**1. Spec 覆盖**:对应 spec §5.7 发布校验的**引擎无关层**(语法 compile + referencedVariables 冻依赖)Task 3;`script_source` 写入(实体字段 + buildDraftVersion + publish snapshot)Task 1/4;写路径透传(DTO→controller→service→PublishService)Task 5/6。**明确不在本 plan**:§5.7 的 typed 类型检查(Plan 4c);决策码静态枚举(运行期兜底已 Plan 2)。

**2. 占位扫描**:Task 3/6 的测试给了"装配参照同包既有测试"的指引而非完整 mock 装配代码——因 PublishService/RuleController 测试的 mock 装配是既有模式,实现者照搬同包既有测试结构即可;测试**断言点**(metricDeps/payloadDeps/conditionAst null/script 透传)已明确。其余步骤含确切代码。

**3. 类型一致**:`resolveAndValidate(..., ScriptSource script)` / `ResolvedDraft(+scriptSource)` / `createDraft·editDraft·newVersion(+ScriptSource script)` / DTO `script()` / `RuleVersion.scriptSource` 贯穿一致;`freezeMetricDeps`/`freezePayloadDeps` 签名 AST 与 script 路径共用;`stripPrefix` 拆 `metrics.`/`payload.`。

**4. 执行顺序**:Task 2(抽方法,纯重构)→ Task 3(script 分支用新方法)→ Task 4(lifecycle/snapshot)→ Task 5(ConfigService 链)→ Task 6(api 层)→ Task 7 端到端。每层签名变更向下游传导,Task 4/5/6 的 test-compile 步显式说明编译错误转移到下一层。
