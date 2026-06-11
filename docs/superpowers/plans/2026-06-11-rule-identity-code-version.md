# 规则身份 (code, version) 阶段甲 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让逻辑身份 `code` + 版本号 `version` 成为运行时与审计的一等概念,与现有代理键 `ruleVersionId` 并存(supplement),贯穿 kernel 模型 / trace / 决策溯源 / DB 落库;并把 `@RuleDef` 注解从手编 `long id` 改为 `code`。

**Architecture:** Camunda 式"逻辑自然键 (tenant,scene,code,version) + 代理键并存"。给 `RuleVersionSnapshot`/`NodeTrace`/`Decision` 各加 `code`+`version` 字段(保留 `ruleVersionId`);config 路径从 `rule_definition.code`+`rule_version.version` 填充,SDK 注解/文件/轮询路径同步带上;`node_trace`/`evaluation_session`/`dry_run_node_trace` 各加 `rule_code`+`rule_version` 列(保留 `rule_version_id`)。`SceneRuleIndex` 去重逻辑不变。

**Tech Stack:** Java 25 / Spring Boot 4 / MyBatis-Plus / Flyway / rule-kernel(纯 Java) / rule-sdk / GraalVM native 硬约束。

**前置环境:** 跑 mvn 前用 `mvn-env` skill 设 `$MVN`(JDK 25)。跨模块改动必带 `-am`,一轮结束用全量 `$MVN clean test` 兜底。

**贯穿决策(实现时遵守,不再每处重述):**
- **version 类型 = `long`**(对齐 `rule_version.version` BIGINT)。
- **降低 record 构造点 churn**:给加了字段的 record 保留/新增便利构造器与 builder 默认值(`code=null`、`version=0L`),沿用该 record 现有"便利构造器"惯例(如 RuleVersionSnapshot 已有省略 metricDependencies 的便利构造器)。仅**生产路径**(SnapshotAssembler / AnnotationRuleSource / 各 executor)显式传真实 code/version;测试桩用 builder 默认值即可编过,无需逐个改。
- **`ruleVersionId` 一律保留**,不删、不改去重逻辑。
- 每个 Task 提交前跑该模块 `$MVN -pl <module> -am test` 全绿;跨模块用 `clean test`。
- 测试方法名用英文,注释/Javadoc 用中文。

---

## 文件结构(改动落点)

| 文件 | 改动 |
|---|---|
| `rule-kernel/.../api/model/RuleVersionSnapshot.java` | +`code`/`version` 字段、builder、便利构造 |
| `rule-kernel/.../api/model/NodeTrace.java` | +`ruleCode`/`ruleVersion`、container 工厂重载 |
| `rule-kernel/.../api/model/Decision.java` | +`fromRuleCode`/`fromRuleVersion`、便利构造 |
| `rule-kernel/.../internal/evaluator/{Interpreted,Scorecard,DecisionTree,DecisionTable}Executor.java` | trace 注入 + Decision 构造透传 code/version |
| `rule-kernel/.../internal/codec/RuleVersionRow.java` | +`code`/`version` 字段 |
| `rule-kernel/.../internal/codec/SnapshotAssembler.java` | assemble 填 code/version |
| `rule-config-svc` 规则版本查询 Mapper/SQL | JOIN 取 `rule_definition.code` + `rule_version.version` |
| `rule-config-svc/.../publish/PublishService.java` | 构建 snapshot 带 code/version |
| `rule-api/.../web/sdk/SdkSnapshotController.java` 及快照 DTO | 序列化带 code/version |
| `rule-sdk/.../source/{File,Dsl,Polling}RuleSource.java`、`Condition.java`、`source/AnnotationRuleSource.java` | JSON/构造带 code/version;Condition.of 重载;注解派生 id |
| `rule-kernel/.../api/annotation/RuleDef.java` | `id`→`code`、+`version`、`tenantId` 默认空 |
| `rule-sdk-spring-boot-starter/.../RuleEngineClientAutoConfiguration.java` | 把 client 租户传给 AnnotationRuleSource |
| `rule-config-svc/src/main/resources/db/migration/V1_26__node_trace_rule_code_version.sql` | 新增列 |
| `rule-eval-svc` trace/session persister + trace 读出 VO | 写/读 code/version |
| `rule-samples/.../annotation/LargeTradeRule.java` | 用新 `@RuleDef` |
| `docs/00-decisions.md`、`04-extension.md`、D40 | 决策 + 注解文档 |

---

## Part A — 身份贯通(核心 + trace + DB)

### Task 1: RuleVersionSnapshot 增 code/version

**Files:** Modify `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/RuleVersionSnapshot.java`

- [ ] **Step 1: 写失败测试**

`rule-kernel/src/test/java/com/sstlfsj/rule/kernel/api/model/RuleVersionSnapshotBuilderTest.java` 追加:

```java
@Test
void builderCarriesCodeAndVersion() {
    RuleVersionSnapshot s = RuleVersionSnapshot.builder()
            .ruleVersionId(100L).sceneCode("scene").tenantId("t1")
            .code("large-trade").version(3L)
            .build();
    assertThat(s.code()).isEqualTo("large-trade");
    assertThat(s.version()).isEqualTo(3L);
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `$MVN -pl rule-kernel test -Dtest=RuleVersionSnapshotBuilderTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败(`code()`/`version()`/`.code(..)` 未定义)。

- [ ] **Step 3: 实现——record 加字段 + builder + 便利构造**

在 record 头部 `String kind,` 之后、`List<MetricDependency> metricDependencies,` 之前插入两个组件:
```java
        /** 逻辑规则编码(= rule_definition.code,(tenant,scene) 内唯一);本地/旧构造默认 null。 */
        String code,
        /** 版本号(= rule_version.version,per code 单调);本地/旧构造默认 0。 */
        long version,
```
紧凑构造器无需为 `code`/`version` 加默认(primitive long 默认 0、String 允许 null)。

更新**规范构造**调用方:现有"省略 metricDependencies 的便利构造器"补传 `code=null, version=0L`:
```java
    public RuleVersionSnapshot(Long ruleVersionId, String sceneCode, String tenantId, AstNode conditionAst,
                               List<PreGateConfig> preGates, List<DecisionBinding> decisionBindings,
                               List<String> triggerEventTypes, String kind) {
        this(ruleVersionId, sceneCode, tenantId, conditionAst, preGates, decisionBindings,
                triggerEventTypes, kind, null, 0L, List.of(), List.of());
    }
```
(即规范构造参数顺序为 `..., kind, code, version, metricDependencies, payloadDependencies`。)

Builder 增字段与方法:
```java
        private String code;
        private long version;
        ...
        /** 逻辑规则编码。 */
        public Builder code(String v)    { this.code = v; return this; }
        /** 版本号。 */
        public Builder version(long v)   { this.version = v; return this; }
```
Builder.build() 改为:
```java
        public RuleVersionSnapshot build() {
            return new RuleVersionSnapshot(ruleVersionId, sceneCode, tenantId, conditionAst,
                    preGates, decisionBindings, triggerEventTypes, kind, code, version,
                    metricDependencies, payloadDependencies);
        }
```

- [ ] **Step 4: 跑测试确认通过**

Run: `$MVN -pl rule-kernel test -Dtest=RuleVersionSnapshotBuilderTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS。

- [ ] **Step 5: 修复编译——全模块构造点**

Run: `$MVN -pl rule-kernel -am test-compile`
若有 `new RuleVersionSnapshot(...)` 规范构造(10 参/12 参)调用点报错,改用 builder 或补 `null, 0L`。SnapshotAssembler 留到 Task 5。
Run: `$MVN -pl rule-kernel test` → 全绿。

- [ ] **Step 6: Commit**

```bash
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/RuleVersionSnapshot.java rule-kernel/src/test/java/com/sstlfsj/rule/kernel/api/model/RuleVersionSnapshotBuilderTest.java
git commit -m "feat(kernel): RuleVersionSnapshot 增 code/version 逻辑身份字段"
```

---

### Task 2: NodeTrace 增 ruleCode/ruleVersion

**Files:** Modify `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/NodeTrace.java`

- [ ] **Step 1: 写失败测试**

`rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/evaluator/TraceContentTest.java`(已存在的 trace 测试)追加:
```java
@Test
void containerCarriesRuleCodeAndVersion() {
    NodeTrace t = NodeTrace.container(NodeType.AND, true, java.util.List.of(), 100L, "large-trade", 3L);
    assertThat(t.ruleCode()).isEqualTo("large-trade");
    assertThat(t.ruleVersion()).isEqualTo(3L);
    assertThat(t.ruleVersionId()).isEqualTo(100L);
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `$MVN -pl rule-kernel test -Dtest=TraceContentTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败(`ruleCode()`/`ruleVersion()` 未定义、6 参 container 不存在)。

- [ ] **Step 3: 实现——record 加字段 + 工厂重载**

record 在 `Long ruleVersionId,` 之后加:
```java
        /** 所属规则逻辑编码;顶层 trace 由执行器填充后向下透传。 */
        String ruleCode,
        /** 所属规则版本号。 */
        long ruleVersion,
```
(放在 `ruleVersionId` 与 `expectedValue` 之间;规范构造参数顺序随之变。)

保留现有 4 参/5 参 `container(...)`(内部补 `ruleCode=null, ruleVersion=0L`),并新增带 code/version 的重载:
```java
    public static NodeTrace container(NodeType type, Boolean result, List<NodeTrace> children, Long ruleVersionId) {
        return new NodeTrace(type.tag(), null, null, result, null, null, null, children, ruleVersionId, null, 0L, null, null);
    }
    public static NodeTrace container(NodeType type, Boolean result, List<NodeTrace> children,
                                      Long ruleVersionId, String ruleCode, long ruleVersion) {
        return new NodeTrace(type.tag(), null, null, result, null, null, null, children, ruleVersionId, ruleCode, ruleVersion, null, null);
    }
    public static NodeTrace container(NodeType type, Boolean result, String errorCode,
                                      List<NodeTrace> children, Long ruleVersionId) {
        return new NodeTrace(type.tag(), null, null, result, null, null, errorCode, children, ruleVersionId, null, 0L, null, null);
    }
```
(规范构造里 `ruleVersionId, ruleCode, ruleVersion, expectedValue, displayLabel` 的顺序保持一致;若有直接 `new NodeTrace(...)` 的叶子构造点,补 `null, 0L`。)

- [ ] **Step 4: 跑测试确认通过**

Run: `$MVN -pl rule-kernel test -Dtest=TraceContentTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS。

- [ ] **Step 5: 修复编译**

Run: `$MVN -pl rule-kernel -am test-compile`
所有 `new NodeTrace(...)` 叶子构造(在 InterpretedExecutor 等)补 `null, 0L`(ruleCode/ruleVersion 默认,Task 4 再透传真实值)。
Run: `$MVN -pl rule-kernel test` → 全绿。

- [ ] **Step 6: Commit**

```bash
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/NodeTrace.java rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/evaluator/TraceContentTest.java
git commit -m "feat(kernel): NodeTrace 增 ruleCode/ruleVersion"
```

---

### Task 3: Decision 增 fromRuleCode/fromRuleVersion

**Files:** Modify `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/Decision.java`

- [ ] **Step 1: 写失败测试**

`rule-kernel/src/test/java/com/sstlfsj/rule/kernel/api/model/DecisionTest.java` 追加:
```java
@Test
void decisionCarriesFromRuleCodeAndVersion() {
    Decision d = new Decision("REVIEW", "人工审核", 50, 100L, "large-trade", 3L, null, java.util.List.of());
    assertThat(d.fromRuleCode()).isEqualTo("large-trade");
    assertThat(d.fromRuleVersion()).isEqualTo(3L);
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `$MVN -pl rule-kernel test -Dtest=DecisionTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败。

- [ ] **Step 3: 实现**

record 在 `Long fromRuleVersionId,` 之后加:
```java
        String fromRuleCode,
        long fromRuleVersion,
```
(规范构造顺序变为 `code, name, priority, fromRuleVersionId, fromRuleCode, fromRuleVersion, category, actions`。)
现有 3 个便利构造器补传 `fromRuleCode=null, fromRuleVersion=0L`:
```java
    public Decision(String code, String name, int priority, Long fromRuleVersionId, String category) {
        this(code, name, priority, fromRuleVersionId, null, 0L, category, List.of());
    }
    public Decision(String code, String name, int priority, Long fromRuleVersionId) {
        this(code, name, priority, fromRuleVersionId, null, 0L, null, List.of());
    }
```

- [ ] **Step 4: 跑测试确认通过**

Run: `$MVN -pl rule-kernel test -Dtest=DecisionTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS。

- [ ] **Step 5: 修复编译 + Commit**

Run: `$MVN -pl rule-kernel test` → 全绿(便利构造覆盖旧调用点,应无破坏)。
```bash
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/Decision.java rule-kernel/src/test/java/com/sstlfsj/rule/kernel/api/model/DecisionTest.java
git commit -m "feat(kernel): Decision 增 fromRuleCode/fromRuleVersion"
```

---

### Task 4: 4 个 executor 透传 code/version 进 trace 与 Decision

**Files:** Modify `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/evaluator/InterpretedExecutor.java`、`ScorecardExecutor.java`、`DecisionTreeExecutor.java`、`DecisionTableExecutor.java`

背景:各 executor 持有 `RuleVersionSnapshot snapshot`,顶层 trace 用 `snapshot.ruleVersionId()` 注入并向下透传(见 InterpretedExecutor 的 `injectRuleVersionId` 递归,约 line 60-75);命中时构造 `Decision`。现要同源带上 `snapshot.code()` / `snapshot.version()`。

- [ ] **Step 1: 写失败测试**

`rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/evaluator/TraceContentTest.java` 追加(用现有 InterpretedExecutor 测试套路构造带 code/version 的 snapshot 求值):
```java
@Test
void interpretedExecutorPropagatesRuleCodeVersionToTopTrace() {
    RuleVersionSnapshot snap = RuleVersionSnapshot.builder()
            .ruleVersionId(100L).tenantId("t1").sceneCode("s").code("r1").version(2L)
            .conditionAst(/* 复用本测试类已有的恒真/简单 AST 构造 */ alwaysTrueAst())
            .addDecisionBinding("PASS", 10)
            .build();
    EvalResult r = newInterpretedExecutor().execute(snap, ctxFor(snap), true);
    assertThat(r.nodeTrace().get(0).ruleCode()).isEqualTo("r1");
    assertThat(r.nodeTrace().get(0).ruleVersion()).isEqualTo(2L);
    assertThat(r.finalDecision().fromRuleCode()).isEqualTo("r1");
    assertThat(r.finalDecision().fromRuleVersion()).isEqualTo(2L);
}
```
(`alwaysTrueAst()`/`newInterpretedExecutor()`/`ctxFor(..)` 用该测试类现有 helper;无则参照同类 `InterpretedExecutorTest` 的构造方式内联。)

- [ ] **Step 2: 跑测试确认失败**

Run: `$MVN -pl rule-kernel test -Dtest=TraceContentTest#interpretedExecutorPropagatesRuleCodeVersionToTopTrace -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL(顶层 trace ruleCode 为 null / Decision fromRuleCode 为 null)。

- [ ] **Step 3: 实现——InterpretedExecutor**

顶层 trace 注入处(现 `Long rvId = snapshot.ruleVersionId();` 后用 `injectRuleVersionId(traces, rvId)`)改为同时透传 code/version:把注入 helper 签名扩为 `inject(List<NodeTrace> nodes, Long rvId, String code, long version)`,在重建每个 NodeTrace 时用带 code/version 的 6 参 `container(...)`(容器节点)或在叶子重建时填 ruleCode/ruleVersion。命中构造 Decision 处补 `snapshot.code()`, `snapshot.version()`:
```java
new Decision(decisionCode, name, priority, snapshot.ruleVersionId(),
             snapshot.code(), snapshot.version(), category, actions)
```

- [ ] **Step 4: 实现——其余 3 executor**

`ScorecardExecutor`/`DecisionTreeExecutor`/`DecisionTableExecutor` 各自:trace 顶层注入 + Decision 构造同样补 `snapshot.code()`/`snapshot.version()`。逐个文件改,改完单测:
Run: `$MVN -pl rule-kernel test -Dtest=ScorecardExecutorTest,DecisionTreeExecutorTest,DecisionTableTraceTest,DecisionTreeTraceTest -Dsurefire.failIfNoSpecifiedTests=false`

- [ ] **Step 5: 全量 kernel 测试 + Commit**

Run: `$MVN -pl rule-kernel test`
Expected: 全绿。
```bash
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/evaluator/ rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/evaluator/TraceContentTest.java
git commit -m "feat(kernel): executor 透传 code/version 进 trace 与 Decision"
```

---

### Task 5: SnapshotAssembler + RuleVersionRow 填 code/version

**Files:** Modify `rule-kernel/.../internal/codec/RuleVersionRow.java`、`SnapshotAssembler.java`

- [ ] **Step 1: 写失败测试**

新建 `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/codec/SnapshotAssemblerCodeTest.java`:
```java
class SnapshotAssemblerCodeTest {
    @Test
    void assembleCarriesCodeAndVersion() {
        RuleVersionRow row = new RuleVersionRow(
                100L, "scene", 1L, "{\"type\":\"AndNode\",\"children\":[]}",
                "[]", "[]", "[\"ev\"]", "AST_BOOLEAN", "HIGHEST_PRIORITY",
                "[]", "[]", "large-trade", 3L);
        RuleVersionSnapshot s = new SnapshotAssembler().assemble(row);
        assertThat(s.code()).isEqualTo("large-trade");
        assertThat(s.version()).isEqualTo(3L);
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `$MVN -pl rule-kernel test -Dtest=SnapshotAssemblerCodeTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败(RuleVersionRow 无 code/version)。

- [ ] **Step 3: 实现——RuleVersionRow 加字段**

在 record 末尾(`payloadDependenciesJson` 之后)加:
```java
        /** rule_definition.code 逻辑编码。 */
        String code,
        /** rule_version.version 版本号。 */
        long version
```
现有省略 metric/payload 的便利构造器补传 `code=null, version=0L`(末尾两个新参)。

- [ ] **Step 4: 实现——SnapshotAssembler.assemble 末尾构造补 code/version**

`return new RuleVersionSnapshot(...)` 改用规范 12 参构造,在 `row.kind()...` 之后传 `row.code(), row.version(),` 再接 metric/payload:
```java
        return new RuleVersionSnapshot(
                row.ruleVersionId(), row.sceneCode(), String.valueOf(row.tenantId()),
                conditionAst, preGates, decisionBindings, triggerEventTypes,
                row.kind() != null ? row.kind() : RuleKind.AST_BOOLEAN.tag(),
                row.code(), row.version(),
                metricDependencies, payloadDependencies);
```

- [ ] **Step 5: 跑测试确认通过 + 全量**

Run: `$MVN -pl rule-kernel test -Dtest=SnapshotAssemblerCodeTest -Dsurefire.failIfNoSpecifiedTests=false` → PASS
Run: `$MVN -pl rule-kernel test` → 全绿。

- [ ] **Step 6: Commit**

```bash
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/codec/RuleVersionRow.java rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/codec/SnapshotAssembler.java rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/codec/SnapshotAssemblerCodeTest.java
git commit -m "feat(kernel): SnapshotAssembler/RuleVersionRow 填 code/version"
```

---

### Task 6: config 路径——查询 SQL 取 code/version + PublishService

**Files:** Modify rule-config-svc 中构建 `RuleVersionRow` 的 Mapper(`@Select` 或 XML)与 `publish/PublishService.java`

前置:先 `grep -rn "new RuleVersionRow\|RuleVersionRow(" rule-config-svc/src/main/java` 与查 mapper 里 SELECT rule_version JOIN 的语句,定位装配点。

- [ ] **Step 1: 写失败测试(集成)**

在 `ConfigServiceImplTest` 或 snapshot 加载相关测试中,断言:发布一条规则后,加载出的 `RuleVersionSnapshot.code()` == 规则 code、`.version()` == 1。
(用现有测试套路:createScene→createRule→publish→加载快照。)

- [ ] **Step 2: 跑测试确认失败**

Run: `$MVN -pl rule-config-svc -am test -Dtest=ConfigServiceImplTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL(code/version 为 null/0)。

- [ ] **Step 3: 实现——SQL JOIN 取 code/version**

在装配 `RuleVersionRow` 的查询里:`rule_version rv JOIN rule_definition rd ON rv.rule_definition_id = rd.id`,SELECT 增 `rd.code AS code, rv.version AS version`;`RuleVersionRow` 映射补这两列(MyBatis 列名→record 组件)。`PublishService` 若手工构造 snapshot/row,补 code(来自 rule_definition)+ version(来自 rule_version)。

- [ ] **Step 4: 跑测试确认通过 + 全量**

Run: `$MVN -pl rule-config-svc -am test`
Expected: 全绿。

- [ ] **Step 5: Commit**

```bash
git add rule-config-svc/src/main/java rule-config-svc/src/main/resources rule-config-svc/src/test/java
git commit -m "feat(config): 规则版本查询装配 code/version 进快照"
```

---

### Task 7: rule-api /sdk/v1/snapshots 序列化带 code/version

**Files:** Modify `rule-api/.../web/sdk/SdkSnapshotController.java` 及其快照响应 DTO(若有)

前置:确认 `/sdk/v1/snapshots` 返回的是 `RuleVersionSnapshot`(则加了字段自动序列化)还是中间 DTO(需补字段)。`grep -rn "snapshots" rule-api/src/main/java/com/sstlfsj/rule/web/sdk/`。

- [ ] **Step 1: 写失败测试**

`SdkSnapshotControllerTest`:发布规则后调 `/sdk/v1/snapshots`,断言返回 JSON 含 `code` 与 `version` 字段且值正确。

- [ ] **Step 2: 跑测试确认失败**

Run: `$MVN -pl rule-api -am test -Dtest=SdkSnapshotControllerTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL。

- [ ] **Step 3: 实现**

若直接序列化 `RuleVersionSnapshot`:Task 1 已带字段,确认无字段过滤即可;若有中间 DTO,补 `code`/`version` 并在映射处填充。

- [ ] **Step 4: 跑测试确认通过 + Commit**

Run: `$MVN -pl rule-api -am test -Dtest=SdkSnapshotControllerTest -Dsurefire.failIfNoSpecifiedTests=false` → PASS
```bash
git add rule-api/src/main/java rule-api/src/test/java
git commit -m "feat(api): /sdk/v1/snapshots 序列化带 code/version"
```

---

### Task 8: DB 迁移——node_trace/evaluation_session/dry_run_node_trace 加 rule_code/rule_version

**Files:** Create `rule-config-svc/src/main/resources/db/migration/V1_26__node_trace_rule_code_version.sql`(确认最新版本号:`ls rule-config-svc/src/main/resources/db/migration | sort | tail -3`,取下一个号)

- [ ] **Step 1: 写迁移**

```sql
-- 规则身份 (code, version) 阶段甲:审计行补冗余逻辑键(supplement,保留 rule_version_id)
ALTER TABLE node_trace          ADD COLUMN rule_code VARCHAR(128) NULL COMMENT '规则逻辑编码(冗余,人类可读)',
                                ADD COLUMN rule_version BIGINT NULL COMMENT '规则版本号(冗余)';
ALTER TABLE evaluation_session  ADD COLUMN rule_code VARCHAR(128) NULL COMMENT '规则逻辑编码(冗余)',
                                ADD COLUMN rule_version BIGINT NULL COMMENT '规则版本号(冗余)';
ALTER TABLE dry_run_node_trace  ADD COLUMN rule_code VARCHAR(128) NULL COMMENT '规则逻辑编码(冗余)',
                                ADD COLUMN rule_version BIGINT NULL COMMENT '规则版本号(冗余)';
```
(若某表实际无 `rule_version_id`/语义不符,以 `grep -n` 实表为准调整;evaluation_session 若按"每命中规则一行"存,则加列,否则只在 node_trace 加——按实表结构定。)

- [ ] **Step 2: 跑迁移验证**

Run: `$MVN -pl rule-app -am test -Dtest=*Migration* -Dsurefire.failIfNoSpecifiedTests=false` 或起一次 Testcontainers 集成测试触发 Flyway。
Expected: 迁移成功、版本推进。

- [ ] **Step 3: Commit**

```bash
git add rule-config-svc/src/main/resources/db/migration/V1_26__node_trace_rule_code_version.sql
git commit -m "feat(db): node_trace 等审计表增 rule_code/rule_version 列"
```

---

### Task 9: eval-svc 落库 writer + trace 读出填 code/version

**Files:** Modify rule-eval-svc 的 node_trace / evaluation_session / dry-run 落库 writer(`internal/async/*Persister.java` 等)与 trace 读出 VO/mapper;rule-api 的 trace 读接口 VO

前置:`grep -rln "rule_version_id\|node_trace\|NodeTrace" rule-eval-svc/src/main/java` 定位写入点;trace 读出在 admin(`AuditController` / evaluation-sessions/trace 端点)。

- [ ] **Step 1: 写失败测试**

eval-svc 落库测试:评估命中后查 `node_trace` 行,断言 `rule_code`/`rule_version` 已写入(非 null,等于规则 code/version)。

- [ ] **Step 2: 跑测试确认失败**

Run: `$MVN -pl rule-eval-svc -am test -Dtest=<相关落库测试> -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL。

- [ ] **Step 3: 实现**

落库 entity/mapper 增 `ruleCode`/`ruleVersion` 字段映射到新列,从 `NodeTrace.ruleCode()`/`ruleVersion()`(Task 2/4 已填)取值写入;trace 读出 VO 增字段并在 mapper/SQL select 出 `rule_code`/`rule_version`,admin trace 接口透出。

- [ ] **Step 4: 跑测试确认通过 + 全量**

Run: `$MVN -pl rule-eval-svc -am test`
Expected: 全绿。

- [ ] **Step 5: Commit**

```bash
git add rule-eval-svc/src/main rule-eval-svc/src/test rule-api/src/main
git commit -m "feat(eval): trace/session 落库与读出携带 code/version"
```

---

## Part B — @RuleDef 人机工程学(依赖 Part A 的 code 模型)

### Task 10: @RuleDef 改 id→code + version + tenantId 可选

**Files:** Modify `rule-kernel/.../api/annotation/RuleDef.java`

- [ ] **Step 1: 写失败测试**

`rule-kernel/src/test/java/com/sstlfsj/rule/kernel/api/annotation/RuleDefTest.java` 改/加:断言 `@RuleDef` 有 `code()`、`version()`(默认 1)、`tenantId()`(默认 "")、无 `id()`。
```java
@RuleDef(code = "r1", sceneCode = "s")
static class Sample {}
@Test
void ruleDefDefaults() throws Exception {
    RuleDef a = Sample.class.getAnnotation(RuleDef.class);
    assertThat(a.code()).isEqualTo("r1");
    assertThat(a.version()).isEqualTo(1L);
    assertThat(a.tenantId()).isEmpty();
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `$MVN -pl rule-kernel test -Dtest=RuleDefTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败(`code()`/`version()` 不存在、`id()`/`tenantId()` 必填)。

- [ ] **Step 3: 实现**

```java
public @interface RuleDef {
    /** 规则逻辑编码,= 配置路径 rule.code,(tenant,scene) 内唯一。 */
    String code();
    /** 场景编码。 */
    String sceneCode();
    /** 租户 ID;空 = 用 RuleEngineClient 配置的租户。 */
    String tenantId() default "";
    /** 版本号,代码定义规则默认 1。 */
    long version() default 1L;
    /** 触发事件类型;空数组表示通配。 */
    String[] trigger() default {};
    /** Decision 绑定列表。 */
    DecisionBinding[] decisions() default {};
}
```

- [ ] **Step 4: 跑测试确认失败(下游编译)**

Run: `$MVN -pl rule-kernel test` → 本模块 PASS。
注:AnnotationRuleSource(rule-sdk)与 sample 会编译失败,Task 11/13 修。

- [ ] **Step 5: Commit**

```bash
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/annotation/RuleDef.java rule-kernel/src/test/java/com/sstlfsj/rule/kernel/api/annotation/RuleDefTest.java
git commit -m "feat(kernel): @RuleDef 改 code 标识 + version + 可选 tenantId"
```

---

### Task 11: AnnotationRuleSource 派生 ruleVersionId + 填 code/version + 默认租户

**Files:** Modify `rule-sdk/.../source/AnnotationRuleSource.java`

- [ ] **Step 1: 写失败测试**

`rule-sdk/src/test/java/com/sstlfsj/rule/sdk/source/AnnotationRuleSourceTest.java`:用新 `@RuleDef(code="fraud-amt", sceneCode="fraud", trigger="TXN", decisions=@DecisionBinding(code="BLOCK",priority=100))` 的 spec,loadInto 后从 index 取出快照,断言 `code()`=="fraud-amt"、`version()`==1、`ruleVersionId()` 非 null 且稳定(两次构造同值)。

- [ ] **Step 2: 跑测试确认失败**

Run: `$MVN -pl rule-sdk -am test -Dtest=AnnotationRuleSourceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败(ann.id() 没了)。

- [ ] **Step 3: 实现**

`loadInto` 中改为:
```java
String tenant = ann.tenantId().isBlank() ? defaultTenantId : ann.tenantId();
long ruleVersionId = stableId(tenant, ann.sceneCode(), ann.code());
RuleVersionSnapshot.Builder builder = RuleVersionSnapshot.builder()
        .ruleVersionId(ruleVersionId)
        .tenantId(tenant)
        .sceneCode(ann.sceneCode())
        .code(ann.code())
        .version(ann.version())
        .conditionAst(spec.condition().toAst());
```
新增 `defaultTenantId`(构造器注入,见下)与:
```java
/** 由 (tenant,scene,code) 派生稳定 64-bit 版本 id;同输入跨进程稳定,满足幂等 loadInto。 */
private static long stableId(String tenant, String scene, String code) {
    return (tenant + ":" + scene + ":" + code).hashCode() & 0xffffffffL;
}
```
构造器增可选 `defaultTenantId`:
```java
private final List<InlineRuleSpec> specs;
private final String defaultTenantId;
public AnnotationRuleSource(List<InlineRuleSpec> specs) { this(specs, ""); }
public AnnotationRuleSource(List<InlineRuleSpec> specs, String defaultTenantId) {
    this.specs = List.copyOf(specs);
    this.defaultTenantId = defaultTenantId == null ? "" : defaultTenantId;
}
```

- [ ] **Step 4: 跑测试确认通过 + 全量 rule-sdk**

Run: `$MVN -pl rule-sdk -am test`
Expected: 全绿(含 InlineRuleSpecTest)。

- [ ] **Step 5: Commit**

```bash
git add rule-sdk/src/main/java/com/sstlfsj/rule/sdk/source/AnnotationRuleSource.java rule-sdk/src/test/java/com/sstlfsj/rule/sdk/source/AnnotationRuleSourceTest.java
git commit -m "feat(sdk): AnnotationRuleSource 派生 ruleVersionId + 填 code/version + 默认租户"
```

---

### Task 12: Condition.of 无 metric 重载 + starter 传默认租户

**Files:** Modify `rule-sdk/.../Condition.java`、`rule-sdk-spring-boot-starter/.../RuleEngineClientAutoConfiguration.java`

- [ ] **Step 1: 写失败测试**

`rule-sdk` 适当测试:`Condition.of("BUSINESS_HOURS", Map.of()).toAst()` 生成 conditionType=BUSINESS_HOURS、metricCode=null 的 ConditionNode。

- [ ] **Step 2: 跑测试确认失败**

Run: `$MVN -pl rule-sdk test -Dtest=<ConditionTest> -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败(2 参 of 不存在)。

- [ ] **Step 3: 实现**

Condition 增重载:
```java
/** 自定义算子,无绑定 metric(直接读 payload/context 的算子)。 */
public static Condition of(String conditionType, Map<String, Object> params) {
    return leaf(conditionType, null, params);
}
```
starter `RuleEngineClientAutoConfiguration`:构造 `AnnotationRuleSource` 处把 `props.getTenantId()` 作为默认租户传入:
```java
builder.ruleSource(new AnnotationRuleSource(inlineSpecs, props.getTenantId()));
```

- [ ] **Step 4: 跑测试确认通过 + 全量 starter**

Run: `$MVN -pl rule-sdk-spring-boot-starter -am test`
Expected: 全绿(含 RuleEngineClientAutoConfigurationTest,可能需把测试里的 @RuleDef 改 code)。

- [ ] **Step 5: Commit**

```bash
git add rule-sdk/src/main/java/com/sstlfsj/rule/sdk/Condition.java rule-sdk-spring-boot-starter/src/main/java rule-sdk/src/test rule-sdk-spring-boot-starter/src/test
git commit -m "feat(sdk): Condition.of 无 metric 重载 + starter 传默认租户给注解规则"
```

---

### Task 13: 更新 rule-samples annotation demo

**Files:** Modify `rule-samples/.../annotation/LargeTradeRule.java`

- [ ] **Step 1: 改用新 @RuleDef**

```java
@RuleDef(code = "large-trade", sceneCode = "merchant-trade", trigger = "trade",
         decisions = @DecisionBinding(code = "REVIEW", priority = 50))
@Component
public class LargeTradeRule implements InlineRuleSpec {
    @Override
    public Condition condition() {
        return Condition.payloadGt("amount", 5000)
                .and(Condition.of("BUSINESS_HOURS", Map.of()));
    }
}
```
(去掉 `id`、`tenantId`;`Condition.of` 用 2 参重载。`import java.util.Map;` 保留。)

- [ ] **Step 2: 编译 + 实跑(零依赖 demo)**

Run: `$MVN -pl rule-samples -am compile`
Run: `$MVN -pl rule-samples exec:java -Dexec.mainClass="com.sstlfsj.rule.samples.annotation.AnnotationDemoApplication"`
Expected: `[annotation] ... ruleHit=true finalDecision=REVIEW`。

- [ ] **Step 3: Commit**

```bash
git add rule-samples/src/main/java/com/sstlfsj/rule/samples/annotation/LargeTradeRule.java
git commit -m "refactor(rule-samples): annotation demo 用新 @RuleDef(code 标识)"
```

---

### Task 14: 文档 + 全量兜底 + 端到端

**Files:** Modify `docs/00-decisions.md`、`docs/04-extension.md`、`docs/superpowers/plans/2026-06-04-d40-rule-def-annotation.md`(或补注)

- [ ] **Step 1: 追加 00-decisions 决策**

追加一条(append-only):规则身份 = 逻辑键 `(tenant,scene,code,version)` + 代理键 `ruleVersionId` 并存(supplement,Camunda 范式);阶段甲落地核心+trace,阶段乙落地 admin API 寻址;不移除代理主键(丙不做)。

- [ ] **Step 2: 04-extension 补 @RuleDef 注解模式**

新增一节说明 `@RuleDef`(code/sceneCode/tenantId 可选/version/trigger/decisions)+ `InlineRuleSpec.condition()` + `Condition` DSL 的代码定义规则用法。

- [ ] **Step 3: 全量 clean test 兜底**

Run: `$MVN clean test`
Expected: 全模块 BUILD SUCCESS。

- [ ] **Step 4: 端到端(真起服务)**

按 `rule-samples` 流程起 rule-app,跑 httpclient demo,查 `node_trace` 行确认 `rule_code`/`rule_version` 真写入,`/api/v1/rule/evaluate` 返回的 `finalDecision` 含 `fromRuleCode`/`fromRuleVersion`。验完清理测试数据。

- [ ] **Step 5: Commit + rule-engine-reviewer 审查**

```bash
git add docs/
git commit -m "docs: 规则身份 code/version 决策 + @RuleDef 注解模式说明"
```
然后显式调用 `rule-engine-reviewer` agent 审"代码 ↔ 文档对齐"。

---

## Self-Review

**Spec 覆盖:**
- §四 4.1 kernel 模型 → Task 1/2/3 ✅
- §四 4.2 executor 透传 → Task 4 ✅
- §四 4.3 SceneRuleIndex 不变 → 计划未改它,符合 ✅
- §四 4.4 snapshot 各来源 → config:Task 5/6;sdk 序列化:Task 7;annotation:Task 11;DSL/File JSON:RuleVersionSnapshot 加字段后 Jackson 自动序列化(Task 1 覆盖,File/Dsl 无需单独改,除非有显式 DTO——Task 7 已含 sdk 序列化确认)✅
- §四 4.5 DB 落库 → Task 8(迁移)+ Task 9(读写)✅
- §四 4.6 @RuleDef 工程学 → Task 10/11/12/13 ✅
- §四 4.7 文档 → Task 14 ✅
- §七 验收 → Task 14 Step 3/4 ✅

**占位符扫描:** 模型/注解/Condition/迁移任务含完整代码;config/eval/api 三处因 mapper/persister 具体文件名需实现期 `grep` 定位,已给出精确定位命令 + 字段签名 + 改法,非"TODO"式占位。

**类型一致性:** `code`(String)/`version`(long)/`ruleCode`/`ruleVersion`/`fromRuleCode`/`fromRuleVersion` 命名跨 Task 一致;record 规范构造参数顺序在各 Task 显式给出;`stableId` 在 Task 11 定义并使用。

**说明(诚实标注):** Task 6/7/9 涉及的 config mapper SQL、sdk 快照 DTO、eval persister 的确切文件/方法名未在本机逐一读取,每个 Task 已给 `grep` 定位命令 + 要改的字段 + 改法;实现期先定位再按签名落地。其余 Task(kernel 模型、注解、Condition、迁移、sample)为完整可直接落地代码。
