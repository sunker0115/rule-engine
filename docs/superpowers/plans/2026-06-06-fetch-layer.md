# B21 FETCHED 取数层 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让规则引擎真正具备 FETCHED 取数能力——`EvalContextAssembler` 按 metric `sourceType` 并发拉取指标值（provided 优先 → 缓存 → 并发 fetch → 失败降级），并落地 SQL_AGGREGATE / EXTERNAL_HTTP 两种取数范式 + 发布期安全校验。

**Architecture:** 内核保持纯 Java——新增 `MetricDefinitionResolver` / `MetricCache` 两个 SPI（运行时由 eval-svc 注入实现），`EvalContextAssembler` 用 `CompletableFuture` + 专用线程池并发取数；运行期 metric 定义走 SPI 解析（不冻进快照，保持操作配置可热调），取数失败降级为 `MetricValue.error(METRIC_FETCH_FAIL)` 并在 `NodeTrace` 标错码、整树继续。SQL/HTTP handler 以 infra 命名句柄引用外部资源（凭证不落表、白名单灭 SSRF），发布期扫描 SQL 安全 + 资源名注册。

**Tech Stack:** Java 25 / Spring Boot 4 / Spring Modulith / MyBatis-Plus / Caffeine（新增，eval-svc）/ Guava（已有，murmur3 hash）/ JUnit 5 + Mockito + Testcontainers(MySQL)。

**关键设计决策（核实 spec 后定，可破坏性重构）：**
- **Metric 定义来源 = 新内核 SPI `MetricDefinitionResolver`**（Option A）。理由：镜像现有 `SubjectLoader`/`MetricSourceHandler` SPI 注入风格，内核保持纯净；`sourceType`/`datasource`/`cacheTtlSeconds` 是**操作配置**应保持可热调，不应像 `dataType`（B19 冻进 AST 的类型契约）那样冻进快照。
- **兼容性优先（外科式）**：`MetricValue` 加第 4 字段 `errorCode` 但保留 3 参便利构造（~40 处调用点不破）；`EvalContextAssembler` 保留旧 `(List, List)` 构造（fetch 禁用，~25 处测试/sdk 不破），新增富构造供 eval-svc。`MetricQuery` 加 `now`（仅 3 处测试调用点需改）。
- **缓存抽象在内核**：`MetricCache` SPI（内核不依赖 Caffeine），Caffeine 实现落 eval-svc。
- **前向兼容嵌入式 SDK 取数（B2，见 `specs/2026-06-06-sdk-fetch-design.md`）**：本 Phase 的 SPI 必须保持**数据源无关**，使将来 SDK 取数零补丁落地。四条约束：① metric 定义是独立可下发配置，不冻进 `rule_version` 快照；② `MetricDefinitionResolver` 数据源无关（服务端读库 / 嵌入式读下发缓存共用）；③ `EvalContextAssembler` 富构造为服务端与 SDK 统一取数入口（旧 2 参构造保留为 providedMetrics-only 退化路径）；④ `MetricDescriptor` 是定义下发的序列化契约，字段保持中性可 JSON 序列化。

---

## 环境与命令约定

- **跑测试前必须先设置 mvn 环境**：调用 `mvn-env` skill，得到 `$MVN`。
- 单模块测试：`$MVN -pl <module> -am test`
- 单测试类：`$MVN -pl <module> -am test -Dtest=<ClassName>`
- 不得用 `-DskipTests` 绕过失败。每个 Task 提交前跑该模块全部测试通过才 commit。
- 注释一律中文（项目约定）；`public`/SPI/AutoConfiguration 必须有 Javadoc。

---

## 文件结构总览

### Phase 1 — 核心取数管线（rule-kernel + rule-eval-svc）

| 文件 | 责任 | 动作 |
|------|------|------|
| `rule-kernel/.../api/model/MetricValue.java` | 取数结果，新增 errorCode 错误通道 | 改 |
| `rule-kernel/.../api/model/MetricQuery.java` | 取数请求，新增 `now` | 改 |
| `rule-kernel/.../api/model/MetricDescriptor.java` | metric 运行时定义（sourceType/dataType/allowProvided/ttl/params） | 建 |
| `rule-kernel/.../api/model/RuleVersionSnapshot.java` | 快照新增 `metricDependencies` | 改 |
| `rule-kernel/.../api/spi/metric/MetricDefinitionResolver.java` | 解析 metricCode→descriptor 的 SPI | 建 |
| `rule-kernel/.../api/spi/metric/MetricCache.java` | 取数结果缓存 SPI（per-entry TTL） | 建 |
| `rule-kernel/.../internal/codec/RuleVersionRow.java` | DB 行新增 `metricDependenciesJson` | 改 |
| `rule-kernel/.../internal/codec/SnapshotAssembler.java` | 装配 metricDependencies | 改 |
| `rule-kernel/.../internal/context/EvalContextAssembler.java` | 取数管线核心重写 | 改 |
| `rule-kernel/.../internal/evaluator/ConditionOutcome.java` | 条件求值三态结果（满足/不满足/不可判定） | 建 |
| `rule-kernel/.../internal/evaluator/ConditionEvaluation.java` | 统一条件求值门面（拦截 metric ERROR） | 建 |
| `rule-kernel/.../internal/evaluator/{Tracing,Interpreted,Scorecard,DecisionTree,DecisionTable}Executor.java` | 经门面求值，各落 ERROR 语义 | 改 |
| `rule-eval-svc/.../internal/repository/MetricDefinitionReadMapper.java` | 读 metric_definition | 建 |
| `rule-eval-svc/.../internal/domain/MetricDefinitionRow.java` | 读映射载体 | 建 |
| `rule-eval-svc/.../internal/metric/DbMetricDefinitionResolver.java` | resolver 实现（Caffeine 缓存定义） | 建 |
| `rule-eval-svc/.../internal/metric/CaffeineMetricCache.java` | MetricCache 实现 | 建 |
| `rule-eval-svc/.../EvalAutoConfiguration.java` | 接线 resolver/cache/fetchExecutor/assembler | 改 |
| `rule-eval-svc/.../internal/repository/RuleVersionReadMapper.java` | SELECT 加 metric_dependencies | 改 |
| `rule-eval-svc/pom.xml` | 加 Caffeine 依赖 | 改 |

### Phase 2 — SQL_AGGREGATE 范式（rule-eval-svc）

| 文件 | 责任 | 动作 |
|------|------|------|
| `rule-eval-svc/.../internal/metric/sql/FetchResourceProperties.java` | `rule.fetch.*` 配置属性（datasources/endpoints/超时） | 建 |
| `rule-eval-svc/.../internal/metric/sql/MetricDataSourceRegistry.java` | 命名只读 DataSource 注册 | 建 |
| `rule-eval-svc/.../internal/metric/sql/SqlAggregateMetricSourceHandler.java` | SQL 取数 handler | 建 |
| `rule-eval-svc/.../internal/metric/DataTypeCoercion.java` | 结果按 dataType 强转（SQL/HTTP 共用） | 建 |

### Phase 3 — EXTERNAL_HTTP 范式（rule-eval-svc）

| 文件 | 责任 | 动作 |
|------|------|------|
| `rule-eval-svc/.../internal/metric/http/HttpEndpointRegistry.java` | 命名 HTTP 端点注册 | 建 |
| `rule-eval-svc/.../internal/metric/http/ExternalHttpMetricSourceHandler.java` | HTTP 取数 handler | 建 |

### Phase 4 — 发布期校验（rule-config-svc）

| 文件 | 责任 | 动作 |
|------|------|------|
| `rule-config-svc/.../api/spi/MetricResourceCatalog.java` | 已注册资源名目录 SPI（eval-svc 实现） | 建 |
| `rule-config-svc/.../internal/publish/MetricSafetyValidator.java` | SQL 安全扫描 + 资源名校验 | 建 |
| `rule-config-svc/.../internal/publish/PublishService.java` | 发布链接入校验 | 改 |
| `rule-eval-svc/.../internal/metric/RegistryMetricResourceCatalog.java` | catalog 实现（基于两个 registry） | 建 |

### Phase 5 — 文档同步（docs，spec §12）

`02-runtime §3.4` / `01-concepts §3.9` / `03-rule-expression §7.3` / `04-extension` / `00-decisions` / `05-storage`。

---

## 包根说明

内核包根 `com.sstlfsj.rule.kernel`，eval-svc `com.sstlfsj.rule.eval`，config-svc `com.sstlfsj.rule.config`。下文路径省略 `src/main/java/com/sstlfsj/rule/...` 前缀时会写全或写相对，**以"文件结构总览"表中的全路径为准**。

---

# Phase 1 — 核心取数管线

> 产出：一个可工作的 FETCHED 管线——provided 优先、缓存、并发 fetch、失败降级，端到端可测（用 stub handler）。后三个 Phase 的 handler 全部依赖本 Phase 建立的契约。

### Task 1: `MetricValue` 加错误通道（保留 3 参构造）

**Files:**
- Modify: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/MetricValue.java`
- Test: `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/api/model/MetricValueTest.java`

- [ ] **Step 1: 追加失败测试**

在 `MetricValueTest.java` 末尾（类内）追加：

```java
    @Test
    void threeArgConstructor_keepsErrorCodeNull() {
        MetricValue mv = new MetricValue(42, "LONG", "PROVIDED");
        assertThat(mv.errorCode()).isNull();
        assertThat(mv.isError()).isFalse();
    }

    @Test
    void error_factory_marksError() {
        MetricValue mv = MetricValue.error("METRIC_FETCH_FAIL");
        assertThat(mv.isError()).isTrue();
        assertThat(mv.errorCode()).isEqualTo("METRIC_FETCH_FAIL");
        assertThat(mv.value()).isNull();
        assertThat(mv.valueSource()).isEqualTo("FETCHED");
    }
```

确认文件顶部已 `import static org.assertj.core.api.Assertions.assertThat;`（现有测试已用 assertThat，应已存在；若无则补）。

- [ ] **Step 2: 跑测试确认编译失败**

Run: `$MVN -pl rule-kernel -am test -Dtest=MetricValueTest`
Expected: 编译失败——`error(...)` / `isError()` / `errorCode()` 不存在。

- [ ] **Step 3: 改 `MetricValue`**

整文件替换为：

```java
package com.sstlfsj.rule.kernel.api.model;

/**
 * 单个指标的取数结果。
 * <p>{@code valueSource} 标记来源（PROVIDED / FETCHED）；{@code errorCode} 非 null 表示取数失败降级，
 * 此时 {@code value} 通常为 null，引用该指标的条件节点应不命中（satisfied=false）。</p>
 */
public record MetricValue(
        Object value,
        String dataType,
        String valueSource,
        String errorCode
) {
    /**
     * 兼容旧调用点的便利构造：无错误的成功结果，errorCode 默认 null。
     *
     * @param value       指标值
     * @param dataType    数据类型
     * @param valueSource 来源（PROVIDED / FETCHED）
     */
    public MetricValue(Object value, String dataType, String valueSource) {
        this(value, dataType, valueSource, null);
    }

    /**
     * 构造取数失败的降级结果（value=null，valueSource=FETCHED）。
     *
     * @param errorCode 失败错误码（如 METRIC_FETCH_FAIL）
     * @return 标记 isError 的 MetricValue
     */
    public static MetricValue error(String errorCode) {
        return new MetricValue(null, "UNKNOWN", "FETCHED", errorCode);
    }

    /** @return 是否为取数失败的降级值。 */
    public boolean isError() {
        return errorCode != null;
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `$MVN -pl rule-kernel -am test -Dtest=MetricValueTest`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/MetricValue.java rule-kernel/src/test/java/com/sstlfsj/rule/kernel/api/model/MetricValueTest.java
git commit -m "feat(kernel): MetricValue 增加 errorCode 错误通道(B21 取数失败降级)"
```

---

### Task 2: `MetricQuery` 加 `now`

**Files:**
- Modify: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/MetricQuery.java`
- Modify: `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/api/model/MetricQueryTest.java`
- Modify: `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/api/spi/metric/MetricSourceHandlerTest.java`

- [ ] **Step 1: 改测试以使用 6 参构造（先失败）**

`MetricQueryTest.java` 中现有三处 `new MetricQuery(...)` 改为 6 参（末尾加 `now`）。文件顶部加 `import java.time.Instant;`。例如：

```java
        Instant now = Instant.parse("2026-06-06T00:00:00Z");
        MetricQuery a = new MetricQuery("balance", "t1", "u1", Map.of("window", 7), Map.of(), now);
        MetricQuery b = new MetricQuery("balance", "t1", "u1", Map.of("window", 7), Map.of(), now);
```

第三处：
```java
        MetricQuery q = new MetricQuery("score", "t1", "u1", Map.of("k", "v"), Map.of("p", 1),
                Instant.parse("2026-06-06T00:00:00Z"));
```

`MetricSourceHandlerTest.java` 第 17 行的 `new MetricQuery("ACCOUNT_BALANCE", "t1", "u1", Map.of(), Map.of())` 改为：
```java
        return new MetricQuery("ACCOUNT_BALANCE", "t1", "u1", Map.of(), Map.of(),
                java.time.Instant.parse("2026-06-06T00:00:00Z"));
```

- [ ] **Step 2: 跑测试确认编译失败**

Run: `$MVN -pl rule-kernel -am test -Dtest=MetricQueryTest`
Expected: 编译失败——构造器参数个数不匹配。

- [ ] **Step 3: 改 `MetricQuery`**

```java
package com.sstlfsj.rule.kernel.api.model;

import java.time.Instant;
import java.util.Map;

/** MetricSourceHandler.fetch() 的入参，描述一次指标取数请求。 */
public record MetricQuery(
        String metricCode,
        String tenantId,
        String subjectId,
        Map<String, Object> params,
        Map<String, Object> eventPayload,
        /** 引擎统一时钟，来自 EvalContext.now；SQL 的 :now 即取此字段（非 DB NOW()），保 dry-run 可重放。 */
        Instant now
) {}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `$MVN -pl rule-kernel -am test -Dtest=MetricQueryTest,MetricSourceHandlerTest`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/MetricQuery.java rule-kernel/src/test/java/com/sstlfsj/rule/kernel/api/model/MetricQueryTest.java rule-kernel/src/test/java/com/sstlfsj/rule/kernel/api/spi/metric/MetricSourceHandlerTest.java
git commit -m "feat(kernel): MetricQuery 增加 now 字段(B20/B21 统一时钟,dry-run 可重放)"
```

---

### Task 3: `RuleVersionSnapshot` 携带 `metricDependencies`

> 快照需暴露依赖的 metricCode 集合，让 assembler 不必每次评估重走 AST。DB `rule_version.metric_dependencies` 已存（发布期写入），只是没读进运行时。

**Files:**
- Modify: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/RuleVersionSnapshot.java`
- Modify: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/codec/RuleVersionRow.java`
- Modify: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/codec/SnapshotAssembler.java`
- Test: `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/codec/SnapshotAssemblerTest.java`（若不存在则建）

- [ ] **Step 1: 改 `RuleVersionSnapshot`（record 加字段 + 紧凑构造 + Builder）**

record 头部在 `kind` 后追加字段（注意调整紧凑构造与 Builder）：

```java
public record RuleVersionSnapshot(
        Long ruleVersionId,
        String sceneCode,
        String tenantId,
        AstNode conditionAst,
        List<PreGateConfig> preGates,
        List<DecisionBinding> decisionBindings,
        List<String> triggerEventTypes,
        String kind,
        /** AST 引用的 metricCode 列表（发布期冻结），供取数管线确定取数范围。 */
        List<String> metricDependencies
) {
    public RuleVersionSnapshot {
        preGates = preGates == null ? List.of() : List.copyOf(preGates);
        decisionBindings = decisionBindings == null ? List.of() : List.copyOf(decisionBindings);
        triggerEventTypes = triggerEventTypes == null ? List.of() : List.copyOf(triggerEventTypes);
        kind = kind == null ? "AST_BOOLEAN" : kind;
        metricDependencies = metricDependencies == null ? List.of() : List.copyOf(metricDependencies);
    }
```

Builder 内：加字段 `private final List<String> metricDependencies = new ArrayList<>();`，加方法
```java
        /** 追加一个 metric 依赖。 */
        public Builder addMetricDependency(String metricCode) { metricDependencies.add(metricCode); return this; }
```
并把 `build()` 改为传入新字段：
```java
        public RuleVersionSnapshot build() {
            return new RuleVersionSnapshot(ruleVersionId, sceneCode, tenantId, conditionAst,
                    preGates, decisionBindings, triggerEventTypes, kind, metricDependencies);
        }
```

> ⚠️ 此处会破坏所有 `new RuleVersionSnapshot(... 8 参)` 直接构造点（含 `PublishService` 第 229 行）。下一步统一修。

- [ ] **Step 2: 修所有直接构造点**

`PublishService.java` 第 229 行的 8 参构造改为 9 参，末尾加 `metricDeps`（该方法已有 `List<String> metricDeps` 局部变量）：

```java
        RuleVersionSnapshot snapshot = new RuleVersionSnapshot(
                newRv.getId(), scene.getCode(), String.valueOf(tenantId), resolvedAst,
                List.of(), List.of(), List.of(), kind, metricDeps);
```

全局搜索其它直接 9-参/8-参构造点并修复：
Run: `grep -rn "new RuleVersionSnapshot(" --include=*.java rule-kernel rule-eval-svc rule-config-svc rule-sdk`
对每个匹配（非 Builder 路径），在末尾参数补 `List.of()`（或合适的 metric 列表）。Builder 用法 (`RuleVersionSnapshot.builder()...build()`) 无需改。

- [ ] **Step 3: 改 `RuleVersionRow`**

```java
public record RuleVersionRow(
        Long ruleVersionId,
        String sceneCode,
        Long tenantId,
        String conditionAstJson,
        String preGatesJson,
        String decisionBindingsJson,
        String triggerEventTypesJson,
        String kind,
        String decisionStrategy,
        /** rule_version.metric_dependencies JSON 数组字符串；可能为 null（旧行容错）。 */
        String metricDependenciesJson
) {}
```

> ⚠️ 破坏 `RuleVersionRow` 直接构造点（测试中的手工行）。grep 修复：
> `grep -rn "new RuleVersionRow(" --include=*.java rule-kernel rule-eval-svc`，每处末尾补 `"[]"`。

- [ ] **Step 4: 改 `SnapshotAssembler.assemble`**

在 `triggerEventTypes` 之后、`return new RuleVersionSnapshot(...)` 之前加：
```java
        List<String> metricDependencies = codec.deserializeStringList(
                row.metricDependenciesJson() == null ? "[]" : row.metricDependenciesJson());
```
并把 return 改为 9 参，末尾加 `metricDependencies`：
```java
        return new RuleVersionSnapshot(
                row.ruleVersionId(),
                row.sceneCode(),
                String.valueOf(row.tenantId()),
                conditionAst,
                preGates,
                decisionBindings,
                triggerEventTypes,
                row.kind() != null ? row.kind() : "AST_BOOLEAN",
                metricDependencies
        );
```

- [ ] **Step 5: 写装配测试**

`SnapshotAssemblerTest.java`（若已存在则在其内加方法；否则新建）：

```java
package com.sstlfsj.rule.kernel.internal.codec;

import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SnapshotAssemblerTest {

    private static final String AST = "{\"type\":\"CONDITION\",\"conditionType\":\"GT\",\"metricCode\":\"balance\",\"params\":{\"threshold\":100}}";

    @Test
    void assemble_populatesMetricDependencies() throws Exception {
        RuleVersionRow row = new RuleVersionRow(1L, "PAY", 1L, AST, "[]", "[]", "[]",
                "AST_BOOLEAN", "HIGHEST_PRIORITY", "[\"balance\",\"score\"]");
        RuleVersionSnapshot snap = new SnapshotAssembler().assemble(row);
        assertThat(snap.metricDependencies()).containsExactly("balance", "score");
    }

    @Test
    void assemble_nullMetricDependenciesJson_yieldsEmptyList() throws Exception {
        RuleVersionRow row = new RuleVersionRow(1L, "PAY", 1L, AST, "[]", "[]", "[]",
                "AST_BOOLEAN", "HIGHEST_PRIORITY", null);
        RuleVersionSnapshot snap = new SnapshotAssembler().assemble(row);
        assertThat(snap.metricDependencies()).isEmpty();
    }
}
```

- [ ] **Step 6: 跑 kernel 全量测试**

Run: `$MVN -pl rule-kernel -am test`
Expected: PASS（含修好的所有构造点）。

- [ ] **Step 7: 提交**

```bash
git add rule-kernel/src rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/publish/PublishService.java
git commit -m "feat(kernel): RuleVersionSnapshot 携带 metricDependencies(取数范围来源)"
```

---

### Task 4: 新增 `MetricDescriptor` + `MetricDefinitionResolver` + `MetricCache` SPI

**Files:**
- Create: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/MetricDescriptor.java`
- Create: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/spi/metric/MetricDefinitionResolver.java`
- Create: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/spi/metric/MetricCache.java`
- Test: `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/api/model/MetricDescriptorTest.java`

- [ ] **Step 1: 写 `MetricDescriptor` 测试（先失败）**

```java
package com.sstlfsj.rule.kernel.api.model;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MetricDescriptorTest {

    @Test
    void nullParams_normalizedToEmptyImmutableMap() {
        MetricDescriptor d = new MetricDescriptor("balance", "SQL_AGGREGATE", "LONG", false, 60, null);
        assertThat(d.params()).isEmpty();
    }

    @Test
    void holdsAllFields() {
        MetricDescriptor d = new MetricDescriptor("balance", "SQL_AGGREGATE", "LONG", true, 0,
                Map.of("datasource", "risk_ro"));
        assertThat(d.sourceType()).isEqualTo("SQL_AGGREGATE");
        assertThat(d.allowProvided()).isTrue();
        assertThat(d.cacheTtlSeconds()).isZero();
        assertThat(d.params()).containsEntry("datasource", "risk_ro");
    }
}
```

- [ ] **Step 2: 跑测试确认编译失败**

Run: `$MVN -pl rule-kernel -am test -Dtest=MetricDescriptorTest`
Expected: 编译失败——`MetricDescriptor` 不存在。

- [ ] **Step 3: 写 `MetricDescriptor`**

```java
package com.sstlfsj.rule.kernel.api.model;

import java.util.Map;

/**
 * metric 的运行时定义快照，由 {@link com.sstlfsj.rule.kernel.api.spi.metric.MetricDefinitionResolver}
 * 解析提供，驱动取数管线的 provided 判定、handler 路由与缓存。
 * 区别于发布期冻进 AST 的 dataType（类型契约）：此处是可热调的操作配置。
 * 字段保持中性、可 JSON 序列化——同时作为嵌入式 SDK 取数（B2）的定义下发契约。
 */
public record MetricDescriptor(
        String metricCode,
        String sourceType,
        String dataType,
        boolean allowProvided,
        int cacheTtlSeconds,
        Map<String, Object> params
) {
    public MetricDescriptor {
        params = params == null ? Map.of() : Map.copyOf(params);
    }
}
```

- [ ] **Step 4: 写 `MetricDefinitionResolver` SPI**

```java
package com.sstlfsj.rule.kernel.api.spi.metric;

import com.sstlfsj.rule.kernel.api.model.MetricDescriptor;

/**
 * 运行期解析 metricCode 到 {@link MetricDescriptor} 的 SPI。
 * <p><b>数据源无关</b>：服务端实现读 metric_definition 表（rule-eval-svc），
 * 嵌入式 SDK 实现读下发缓存（见 specs/2026-06-06-sdk-fetch-design.md）；两者共用同一抽象，
 * 上层取数编排不感知数据源。</p>
 */
public interface MetricDefinitionResolver {

    /**
     * 解析指定租户下某 metric 的运行时定义。
     *
     * @param tenantId   租户 id
     * @param metricCode 指标编码
     * @return 定义快照；不存在或未启用时返回 null
     */
    MetricDescriptor resolve(String tenantId, String metricCode);
}
```

- [ ] **Step 5: 写 `MetricCache` SPI**

```java
package com.sstlfsj.rule.kernel.api.spi.metric;

import com.sstlfsj.rule.kernel.api.model.MetricValue;

/** 取数结果缓存 SPI（按 metric 各自 TTL 生效）；由 rule-eval-svc 用 Caffeine 实现。 */
public interface MetricCache {

    /**
     * 读缓存。
     *
     * @param key 缓存键
     * @return 命中的 MetricValue；未命中或已过期返回 null
     */
    MetricValue get(String key);

    /**
     * 写缓存（仅成功结果应写入）。
     *
     * @param key        缓存键
     * @param value      指标值
     * @param ttlSeconds 过期秒数；&le;0 表示不缓存
     */
    void put(String key, MetricValue value, int ttlSeconds);
}
```

- [ ] **Step 6: 跑测试确认通过**

Run: `$MVN -pl rule-kernel -am test -Dtest=MetricDescriptorTest`
Expected: PASS。

- [ ] **Step 7: 提交**

```bash
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/MetricDescriptor.java rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/spi/metric/MetricDefinitionResolver.java rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/spi/metric/MetricCache.java rule-kernel/src/test/java/com/sstlfsj/rule/kernel/api/model/MetricDescriptorTest.java
git commit -m "feat(kernel): 新增 MetricDescriptor + MetricDefinitionResolver/MetricCache SPI(B21)"
```

---

### Task 5: `EvalContextAssembler` 取数管线重写

> 核心。保留旧 `(List, List)` 构造（fetch 禁用 = 历史行为，~25 处调用点不破），新增富构造。assemble 流程：收集候选 metricDependencies 并集 → provided 优先(allowProvided) → 缓存 → 并发 fetch → 失败/超时降级。

**Files:**
- Modify: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/context/EvalContextAssembler.java`
- Test: `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/context/EvalContextAssemblerFetchTest.java`（新建；旧 `EvalContextAssemblerTest` 保持不变，验证兼容构造仍工作）

- [ ] **Step 1: 写 fetch 行为测试（先失败）**

新建 `EvalContextAssemblerFetchTest.java`：

```java
package com.sstlfsj.rule.kernel.internal.context;

import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.kernel.api.model.ast.AstNode;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.spi.metric.MetricCache;
import com.sstlfsj.rule.kernel.api.spi.metric.MetricDefinitionResolver;
import com.sstlfsj.rule.kernel.api.spi.metric.MetricSourceHandler;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class EvalContextAssemblerFetchTest {

    private static final Instant NOW = Instant.parse("2026-06-06T00:00:00Z");

    private RuleEvent event(Map<String, Object> provided) {
        return new RuleEvent("1", "PAY", "transfer", "u1", "e1", NOW, Map.of("amt", 500), provided);
    }

    private RuleVersionSnapshot snapWithDep(String metricCode) {
        AstNode ast = new ConditionNode("GT", metricCode, null, Map.of("threshold", 100), null, "LONG");
        return RuleVersionSnapshot.builder()
                .ruleVersionId(1L).sceneCode("PAY").tenantId("1").conditionAst(ast)
                .addMetricDependency(metricCode).build();
    }

    private MetricDescriptor sqlDef(String code, boolean allowProvided, int ttl) {
        return new MetricDescriptor(code, "SQL_AGGREGATE", "LONG", allowProvided, ttl, Map.of());
    }

    @Test
    void fetch_routesBySourceType_andStoresFetched() {
        MetricSourceHandler handler = q -> new MetricValue(999L, "LONG", "FETCHED");
        MetricDefinitionResolver resolver = (t, c) -> sqlDef(c, false, 0);
        EvalContextAssembler asm = new EvalContextAssembler(
                List.of(), Map.of("SQL_AGGREGATE", handler), resolver, null, Runnable::run, 1000L);

        EvalContext ctx = asm.assemble(event(Map.of()), List.of(snapWithDep("balance")), NOW);

        MetricValue mv = ctx.getMetric("balance");
        assertThat(mv.value()).isEqualTo(999L);
        assertThat(mv.valueSource()).isEqualTo("FETCHED");
        assertThat(mv.isError()).isFalse();
    }

    @Test
    void providedPriority_skipsFetch_whenAllowProvidedTrue() {
        AtomicInteger calls = new AtomicInteger();
        MetricSourceHandler handler = q -> { calls.incrementAndGet(); return new MetricValue(1L, "LONG", "FETCHED"); };
        MetricDefinitionResolver resolver = (t, c) -> sqlDef(c, true, 0);
        EvalContextAssembler asm = new EvalContextAssembler(
                List.of(), Map.of("SQL_AGGREGATE", handler), resolver, null, Runnable::run, 1000L);

        EvalContext ctx = asm.assemble(event(Map.of("balance", 7L)), List.of(snapWithDep("balance")), NOW);

        assertThat(ctx.getMetric("balance").value()).isEqualTo(7L);
        assertThat(ctx.getMetric("balance").valueSource()).isEqualTo("PROVIDED");
        assertThat(calls.get()).isZero();
    }

    @Test
    void providedIgnored_whenAllowProvidedFalse_thenFetched() {
        MetricSourceHandler handler = q -> new MetricValue(42L, "LONG", "FETCHED");
        MetricDefinitionResolver resolver = (t, c) -> sqlDef(c, false, 0);
        EvalContextAssembler asm = new EvalContextAssembler(
                List.of(), Map.of("SQL_AGGREGATE", handler), resolver, null, Runnable::run, 1000L);

        EvalContext ctx = asm.assemble(event(Map.of("balance", 7L)), List.of(snapWithDep("balance")), NOW);

        assertThat(ctx.getMetric("balance").value()).isEqualTo(42L); // provided 被忽略，走 fetch
        assertThat(ctx.getMetric("balance").valueSource()).isEqualTo("FETCHED");
    }

    @Test
    void handlerThrows_degradesToError() {
        MetricSourceHandler handler = q -> { throw new RuntimeException("db down"); };
        MetricDefinitionResolver resolver = (t, c) -> sqlDef(c, false, 0);
        EvalContextAssembler asm = new EvalContextAssembler(
                List.of(), Map.of("SQL_AGGREGATE", handler), resolver, null, Runnable::run, 1000L);

        EvalContext ctx = asm.assemble(event(Map.of()), List.of(snapWithDep("balance")), NOW);

        MetricValue mv = ctx.getMetric("balance");
        assertThat(mv.isError()).isTrue();
        assertThat(mv.errorCode()).isEqualTo("METRIC_FETCH_FAIL");
    }

    @Test
    void missingHandlerForSourceType_degradesToError() {
        MetricDefinitionResolver resolver = (t, c) -> sqlDef(c, false, 0);
        EvalContextAssembler asm = new EvalContextAssembler(
                List.of(), Map.of(), resolver, null, Runnable::run, 1000L);

        EvalContext ctx = asm.assemble(event(Map.of()), List.of(snapWithDep("balance")), NOW);

        assertThat(ctx.getMetric("balance").isError()).isTrue();
    }

    @Test
    void cacheHit_skipsFetch() {
        AtomicInteger calls = new AtomicInteger();
        MetricSourceHandler handler = q -> { calls.incrementAndGet(); return new MetricValue(1L, "LONG", "FETCHED"); };
        MetricDefinitionResolver resolver = (t, c) -> sqlDef(c, false, 60);
        MetricValue cached = new MetricValue(500L, "LONG", "FETCHED");
        MetricCache cache = new MetricCache() {
            public MetricValue get(String key) { return cached; }
            public void put(String key, MetricValue value, int ttlSeconds) { }
        };
        EvalContextAssembler asm = new EvalContextAssembler(
                List.of(), Map.of("SQL_AGGREGATE", handler), resolver, cache, Runnable::run, 1000L);

        EvalContext ctx = asm.assemble(event(Map.of()), List.of(snapWithDep("balance")), NOW);

        assertThat(ctx.getMetric("balance").value()).isEqualTo(500L);
        assertThat(calls.get()).isZero();
    }

    @Test
    void queryCarriesNow() {
        Instant[] seen = new Instant[1];
        MetricSourceHandler handler = q -> { seen[0] = q.now(); return new MetricValue(1L, "LONG", "FETCHED"); };
        MetricDefinitionResolver resolver = (t, c) -> sqlDef(c, false, 0);
        EvalContextAssembler asm = new EvalContextAssembler(
                List.of(), Map.of("SQL_AGGREGATE", handler), resolver, null, Runnable::run, 1000L);

        asm.assemble(event(Map.of()), List.of(snapWithDep("balance")), NOW);

        assertThat(seen[0]).isEqualTo(NOW);
    }
}
```

> 注：测试用 `Runnable::run` 作为同步 Executor，避免线程不确定性。

- [ ] **Step 2: 跑测试确认编译失败**

Run: `$MVN -pl rule-kernel -am test -Dtest=EvalContextAssemblerFetchTest`
Expected: 编译失败——富构造不存在。

- [ ] **Step 3: 重写 `EvalContextAssembler`**

整文件替换为：

```java
package com.sstlfsj.rule.kernel.internal.context;

import com.google.common.hash.Hashing;
import com.sstlfsj.rule.kernel.api.annotation.MetricSourceType;
import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.kernel.api.spi.metric.MetricCache;
import com.sstlfsj.rule.kernel.api.spi.metric.MetricDefinitionResolver;
import com.sstlfsj.rule.kernel.api.spi.metric.MetricSourceHandler;
import com.sstlfsj.rule.kernel.api.spi.subject.SubjectLoader;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * 装配 EvalContext：SubjectLoader（可选 SPI）+ provided 优先 + 按 sourceType 并发 fetch + 缓存 + 失败降级。
 * 纯 Java，无 Spring 依赖。fetch 相关依赖（resolver/cache/executor）为 null 时退化为"仅 providedMetrics 生效"。
 */
public class EvalContextAssembler {

    private static final String METRIC_FETCH_FAIL = "METRIC_FETCH_FAIL";

    private final SubjectLoader subjectLoader;
    private final Map<String, MetricSourceHandler> handlersBySourceType;
    private final MetricDefinitionResolver definitionResolver;
    private final MetricCache cache;
    private final Executor fetchExecutor;
    private final long fetchTimeoutMs;

    /**
     * 兼容构造：仅 providedMetrics 生效，无取数（保持历史行为，供本地 SDK / 测试使用）。
     *
     * @param subjectLoaders 可选 SubjectLoader 列表
     * @param metricHandlers 可选 MetricSourceHandler 列表（按 @MetricSourceType 归类）
     */
    public EvalContextAssembler(List<SubjectLoader> subjectLoaders,
                                List<MetricSourceHandler> metricHandlers) {
        this(subjectLoaders, toSourceTypeMap(metricHandlers), null, null, null, 0L);
    }

    /**
     * 取数构造。
     *
     * @param subjectLoaders       可选 SubjectLoader 列表
     * @param handlersBySourceType sourceType → handler 映射
     * @param definitionResolver   metric 定义解析器（null 时禁用 fetch）
     * @param cache                取数缓存（null 时不缓存）
     * @param fetchExecutor        并发取数线程池（null 时用 ForkJoinPool.commonPool）
     * @param fetchTimeoutMs       全局取数超时毫秒（&le;0 表示不限）
     */
    public EvalContextAssembler(List<SubjectLoader> subjectLoaders,
                                Map<String, MetricSourceHandler> handlersBySourceType,
                                MetricDefinitionResolver definitionResolver,
                                MetricCache cache,
                                Executor fetchExecutor,
                                long fetchTimeoutMs) {
        this.subjectLoader = pickUserLoader(subjectLoaders);
        this.handlersBySourceType =
                handlersBySourceType == null ? Map.of() : Map.copyOf(handlersBySourceType);
        this.definitionResolver = definitionResolver;
        this.cache = cache;
        this.fetchExecutor = fetchExecutor;
        this.fetchTimeoutMs = fetchTimeoutMs;
    }

    private static SubjectLoader pickUserLoader(List<SubjectLoader> loaders) {
        if (loaders == null) return null;
        return loaders.stream()
                .filter(l -> l.supportedTypes().contains(SubjectType.USER))
                .findFirst().orElse(null);
    }

    private static Map<String, MetricSourceHandler> toSourceTypeMap(List<MetricSourceHandler> handlers) {
        if (handlers == null) return Map.of();
        Map<String, MetricSourceHandler> m = new HashMap<>();
        for (MetricSourceHandler h : handlers) {
            MetricSourceType ann = h.getClass().getAnnotation(MetricSourceType.class);
            if (ann != null) m.put(ann.value(), h);
        }
        return m;
    }

    /**
     * 装配一次评估的 EvalContext。
     *
     * @param event      触发事件
     * @param candidates 已过 Pre-Gate 的候选快照（取其 metricDependencies 并集为取数范围）
     * @param now        本次评估统一时刻
     * @return 不可变 EvalContext
     */
    public EvalContext assemble(RuleEvent event,
                                List<RuleVersionSnapshot> candidates,
                                Instant now) {
        Subject subject = loadSubject(event);
        Map<String, MetricValue> metrics = new HashMap<>();

        // 无解析器：退化为历史行为——所有 providedMetrics 直接进 context
        if (definitionResolver == null) {
            for (Map.Entry<String, Object> e : event.providedMetrics().entrySet()) {
                metrics.put(e.getKey(), new MetricValue(e.getValue(), "UNKNOWN", "PROVIDED"));
            }
            return new EvalContext(event.tenantId(), event, subject, metrics, now);
        }

        Set<String> required = collectMetricCodes(candidates);
        Map<String, MetricDescriptor> descriptors = new HashMap<>();
        Set<String> needFetch = new LinkedHashSet<>();

        for (String code : required) {
            MetricDescriptor def = definitionResolver.resolve(event.tenantId(), code);
            if (def != null) descriptors.put(code, def);

            boolean hasProvided = event.providedMetrics().containsKey(code);
            if (hasProvided && (def == null || def.allowProvided())) {
                String dt = def != null ? def.dataType() : "UNKNOWN";
                metrics.put(code, new MetricValue(event.providedMetrics().get(code), dt, "PROVIDED"));
                continue;
            }
            if (hasProvided) {
                // allowProvided=false：忽略传值并 WARN（继续走 fetch）
                System.err.println("[EvalContextAssembler] metric=" + code
                        + " allowProvided=false，忽略 providedMetrics 传值");
            }
            if (def == null) {
                metrics.put(code, MetricValue.error(METRIC_FETCH_FAIL));
                continue;
            }
            if (cache != null && def.cacheTtlSeconds() > 0) {
                MetricValue cached = cache.get(
                        cacheKey(event.tenantId(), code, event.subjectId(), def.params()));
                if (cached != null) { metrics.put(code, cached); continue; }
            }
            needFetch.add(code);
        }

        // 候选未引用但调用方仍推送的 provided 指标：补入（不影响 allowProvided 语义）
        for (Map.Entry<String, Object> e : event.providedMetrics().entrySet()) {
            if (!required.contains(e.getKey())) {
                metrics.putIfAbsent(e.getKey(), new MetricValue(e.getValue(), "UNKNOWN", "PROVIDED"));
            }
        }

        if (!needFetch.isEmpty()) {
            fetchConcurrently(event, now, needFetch, descriptors, metrics);
        }
        return new EvalContext(event.tenantId(), event, subject, metrics, now);
    }

    private static Set<String> collectMetricCodes(List<RuleVersionSnapshot> candidates) {
        Set<String> codes = new LinkedHashSet<>();
        for (RuleVersionSnapshot snap : candidates) {
            codes.addAll(snap.metricDependencies());
        }
        return codes;
    }

    private void fetchConcurrently(RuleEvent event, Instant now, Set<String> codes,
                                   Map<String, MetricDescriptor> descriptors,
                                   Map<String, MetricValue> metrics) {
        Executor exec = fetchExecutor != null ? fetchExecutor : ForkJoinPool.commonPool();
        Map<String, CompletableFuture<MetricValue>> futures = new HashMap<>();
        for (String code : codes) {
            MetricDescriptor def = descriptors.get(code);
            MetricQuery query = new MetricQuery(code, event.tenantId(), event.subjectId(),
                    def.params(), event.payload(), now);
            MetricSourceHandler handler = handlersBySourceType.get(def.sourceType());
            futures.put(code, CompletableFuture
                    .supplyAsync(() -> {
                        if (handler == null) return MetricValue.error(METRIC_FETCH_FAIL);
                        MetricValue v = handler.fetch(query);
                        return v != null ? v : MetricValue.error(METRIC_FETCH_FAIL);
                    }, exec)
                    .exceptionally(ex -> MetricValue.error(METRIC_FETCH_FAIL)));
        }
        try {
            CompletableFuture.allOf(futures.values().toArray(new CompletableFuture[0]))
                    .get(fetchTimeoutMs > 0 ? fetchTimeoutMs : Long.MAX_VALUE, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
            // 超时/中断：已完成 future 仍取其值，未完成的下方按 ERROR 处理
        }
        for (Map.Entry<String, CompletableFuture<MetricValue>> e : futures.entrySet()) {
            String code = e.getKey();
            CompletableFuture<MetricValue> f = e.getValue();
            MetricValue v;
            if (f.isDone() && !f.isCompletedExceptionally()) {
                v = f.getNow(MetricValue.error(METRIC_FETCH_FAIL));
            } else {
                v = MetricValue.error(METRIC_FETCH_FAIL);
                f.cancel(true);
            }
            metrics.put(code, v);
            if (cache != null && !v.isError()) {
                MetricDescriptor def = descriptors.get(code);
                if (def.cacheTtlSeconds() > 0) {
                    cache.put(cacheKey(event.tenantId(), code, event.subjectId(), def.params()),
                            v, def.cacheTtlSeconds());
                }
            }
        }
    }

    /** 缓存键 = tenant:metricCode:subjectId:murmur3(排序后 params)；params 顺序不影响键。 */
    private static String cacheKey(String tenantId, String metricCode, String subjectId,
                                   Map<String, Object> params) {
        String canonical = new TreeMap<>(params).toString();
        String h = Hashing.murmur3_128().hashString(canonical, StandardCharsets.UTF_8).toString();
        return tenantId + ":" + metricCode + ":" + subjectId + ":" + h;
    }

    private Subject loadSubject(RuleEvent event) {
        if (subjectLoader == null) {
            return new Subject(event.subjectId(), SubjectType.USER, Map.of());
        }
        try {
            return subjectLoader.load(event.subjectId(), SubjectType.USER, event);
        } catch (Exception e) {
            return new Subject(event.subjectId(), SubjectType.USER, Map.of());
        }
    }
}
```

- [ ] **Step 4: 跑 fetch 测试 + 旧兼容测试**

Run: `$MVN -pl rule-kernel -am test -Dtest=EvalContextAssemblerFetchTest,EvalContextAssemblerTest`
Expected: PASS（旧 `EvalContextAssemblerTest` 仍走兼容构造，行为不变）。

- [ ] **Step 5: 跑 kernel 全量**

Run: `$MVN -pl rule-kernel -am test`
Expected: PASS。

- [ ] **Step 6: 提交**

```bash
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/context/EvalContextAssembler.java rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/context/EvalContextAssemblerFetchTest.java
git commit -m "feat(kernel): EvalContextAssembler 取数管线(provided优先/缓存/并发fetch/降级)"
```

---

### Task 6: 统一条件求值门面（三态）+ 各执行器 ERROR 语义落地

> **框架级降级语义**：metric 取数失败不是「条件 false」而是「不可判定(indeterminate)」。引入三态 `ConditionOutcome` + 共享门面 `ConditionEvaluation`，5 个执行器全部经门面求值，各按语义落 ERROR。集中、显式，不依赖 evaluator 对 null 的巧合，未来加执行器/算子自动覆盖。`ConditionEvaluator` SPI 不变（自定义算子不破）。

**各执行器对 ERROR 的动作矩阵：**

| 执行器 | SATISFIED | NOT_SATISFIED | ERROR（METRIC_FETCH_FAIL / NO_EVALUATOR） |
|---|---|---|---|
| TracingInterpretedExecutor（布尔，产 trace） | trace=true | trace=false | 节点不命中 + NodeTrace.errorCode，整树继续(D15) |
| InterpretedExecutor（布尔，无 trace，SDK 用） | true | false | METRIC_FETCH_FAIL→false；NO_EVALUATOR→抛异常（保留严格） |
| ScorecardExecutor | 加 weight | 不加分 | **整卡置 ERROR 不出分**（风控保守：避免漏分误判） |
| DecisionTreeExecutor | 走 then | 走 else | **整规则 ERROR + miss**（不静默走 else，避免命中错误叶子） |
| DecisionTableExecutor | 列匹配 | 列不匹配→本行 fail | **整表 ERROR + miss**（不静默落下一行，避免命中错误行） |

> `EvalEngine.evaluateAllCandidates` 已把 `EvalResult.errorCode()` 汇总到会话级（`session.status=ERROR`），无需改 EvalEngine。FIRST_HIT 路径的 errorCode 透传是已知小边界（可选后续，不在本 Task）。

**Files:**
- Create: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/evaluator/ConditionOutcome.java`
- Create: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/evaluator/ConditionEvaluation.java`
- Modify: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/evaluator/TracingInterpretedExecutor.java`
- Modify: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/evaluator/InterpretedExecutor.java`
- Modify: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/evaluator/ScorecardExecutor.java`
- Modify: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/evaluator/DecisionTreeExecutor.java`
- Modify: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/evaluator/DecisionTableExecutor.java`
- Test: `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/evaluator/ConditionEvaluationTest.java`
- Test: `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/evaluator/MetricErrorTraceTest.java`
- Test: `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/evaluator/MetricErrorScorecardTest.java`
- Test: `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/evaluator/MetricErrorDecisionTreeTest.java`
- Test: `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/evaluator/MetricErrorDecisionTableTest.java`

- [ ] **Step 1: 写门面 + 三态（先建实现，再写测试）**

`ConditionOutcome.java`：
```java
package com.sstlfsj.rule.kernel.internal.evaluator;

/** 条件求值三态：满足 / 不满足 / 不可判定（取数失败或无算子，携带 errorCode）。 */
record ConditionOutcome(Status status, String errorCode) {

    /** 三态枚举。 */
    enum Status { SATISFIED, NOT_SATISFIED, ERROR }

    static final ConditionOutcome SATISFIED = new ConditionOutcome(Status.SATISFIED, null);
    static final ConditionOutcome NOT_SATISFIED = new ConditionOutcome(Status.NOT_SATISFIED, null);

    /** 由布尔结果构造满足/不满足。 */
    static ConditionOutcome of(boolean satisfied) {
        return satisfied ? SATISFIED : NOT_SATISFIED;
    }

    /** 构造不可判定结果。 */
    static ConditionOutcome error(String errorCode) {
        return new ConditionOutcome(Status.ERROR, errorCode);
    }

    boolean satisfied() { return status == Status.SATISFIED; }
    boolean isError()   { return status == Status.ERROR; }
}
```

`ConditionEvaluation.java`：
```java
package com.sstlfsj.rule.kernel.internal.evaluator;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.MetricValue;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.spi.condition.ConditionEvaluator;

import java.util.Map;

/**
 * 统一条件求值门面：在委托 ConditionEvaluator 前拦截「引用的 metric 取数失败」，返回三态。
 * 集中降级语义，避免散落各执行器、避免依赖 evaluator 对 null 的巧合行为；
 * 未注册 evaluator 也归入 ERROR(NO_EVALUATOR)，由各执行器按语义决定动作。
 */
final class ConditionEvaluation {

    /** 取数失败错误码。 */
    static final String METRIC_FETCH_FAIL = "METRIC_FETCH_FAIL";
    /** 无注册算子错误码。 */
    static final String NO_EVALUATOR = "NO_EVALUATOR";

    private ConditionEvaluation() {}

    /**
     * 求值单个条件节点。
     *
     * @param node       条件节点
     * @param ctx        执行上下文
     * @param evaluators conditionType → evaluator 映射
     * @return 三态结果
     */
    static ConditionOutcome evaluate(ConditionNode node, EvalContext ctx,
                                     Map<String, ConditionEvaluator> evaluators) {
        String mc = node.metricCode();
        if (mc != null) {
            MetricValue mv = ctx.getMetric(mc);
            if (mv != null && mv.isError()) {
                return ConditionOutcome.error(
                        mv.errorCode() != null ? mv.errorCode() : METRIC_FETCH_FAIL);
            }
        }
        ConditionEvaluator evaluator = evaluators.get(node.conditionType());
        if (evaluator == null) return ConditionOutcome.error(NO_EVALUATOR);
        return ConditionOutcome.of(evaluator.evaluate(node, ctx));
    }
}
```

- [ ] **Step 2: 写门面单测**

`ConditionEvaluationTest.java`：
```java
package com.sstlfsj.rule.kernel.evaluator;

import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.internal.condition.KernelEvaluators;
import com.sstlfsj.rule.kernel.internal.evaluator.ConditionEvaluation;
import com.sstlfsj.rule.kernel.internal.evaluator.ConditionOutcome;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConditionEvaluationTest {

    private EvalContext ctx(Map<String, MetricValue> metrics) {
        return new EvalContext("1",
                new RuleEvent("1", "PAY", "transfer", "u1", "e1", Instant.now(), Map.of(), Map.of()),
                new Subject("u1", SubjectType.USER, Map.of()), metrics, Instant.now());
    }

    private ConditionNode gt(String metric, int threshold) {
        return new ConditionNode("GT", metric, null, Map.of("threshold", threshold), null, "LONG");
    }

    @Test
    void errorMetric_yieldsError() {
        ConditionOutcome out = ConditionEvaluation.evaluate(gt("balance", 100),
                ctx(Map.of("balance", MetricValue.error("METRIC_FETCH_FAIL"))),
                KernelEvaluators.defaults());
        assertThat(out.isError()).isTrue();
        assertThat(out.errorCode()).isEqualTo("METRIC_FETCH_FAIL");
    }

    @Test
    void satisfied() {
        ConditionOutcome out = ConditionEvaluation.evaluate(gt("balance", 100),
                ctx(Map.of("balance", new MetricValue(200L, "LONG", "FETCHED"))),
                KernelEvaluators.defaults());
        assertThat(out.satisfied()).isTrue();
    }

    @Test
    void notSatisfied() {
        ConditionOutcome out = ConditionEvaluation.evaluate(gt("balance", 100),
                ctx(Map.of("balance", new MetricValue(50L, "LONG", "FETCHED"))),
                KernelEvaluators.defaults());
        assertThat(out.status()).isEqualTo(ConditionOutcome.Status.NOT_SATISFIED);
    }

    @Test
    void missingEvaluator_yieldsNoEvaluatorError() {
        ConditionOutcome out = ConditionEvaluation.evaluate(
                new ConditionNode("UNKNOWN_OP", "x", null, Map.of(), null, "LONG"),
                ctx(Map.of("x", new MetricValue(1L, "LONG", "FETCHED"))), Map.of());
        assertThat(out.isError()).isTrue();
        assertThat(out.errorCode()).isEqualTo("NO_EVALUATOR");
    }
}
```

Run: `$MVN -pl rule-kernel -am test -Dtest=ConditionEvaluationTest` → 先确认编译失败（门面未建则失败），建好后 PASS。

- [ ] **Step 3: 改 `TracingInterpretedExecutor` 经门面**

把 `traceCondition` 方法替换为（顶部 import 可移除未用的 MetricValue/ConditionEvaluator，保留 ConditionNode/NodeTrace）：
```java
    private boolean traceCondition(ConditionNode node, EvalContext ctx, List<NodeTrace> sink) {
        ConditionOutcome outcome = ConditionEvaluation.evaluate(node, ctx, evaluators);
        if (outcome.isError()) {
            // ERROR(取数失败/无算子)：节点不命中，trace 标错码，整树继续(D15)
            sink.add(new NodeTrace("ConditionNode", node.conditionType(), node.metricCode(),
                    false, null, null, outcome.errorCode(), List.of(), null));
            return false;
        }
        sink.add(new NodeTrace("ConditionNode", node.conditionType(), node.metricCode(),
                outcome.satisfied(), null, null, null, List.of(), null));
        return outcome.satisfied();
    }
```

`MetricErrorTraceTest.java`（同 Step 1 的布尔用例）：
```java
package com.sstlfsj.rule.kernel.evaluator;

import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.internal.condition.KernelEvaluators;
import com.sstlfsj.rule.kernel.internal.evaluator.TracingInterpretedExecutor;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MetricErrorTraceTest {

    @Test
    void errorMetric_nodeNotSatisfied_andErrorCodeOnTrace() {
        ConditionNode node = new ConditionNode("GT", "balance", null, Map.of("threshold", 100), null, "LONG");
        RuleVersionSnapshot snap = RuleVersionSnapshot.builder()
                .ruleVersionId(1L).sceneCode("PAY").tenantId("1").conditionAst(node)
                .addMetricDependency("balance").build();
        EvalContext ctx = new EvalContext("1",
                new RuleEvent("1", "PAY", "transfer", "u1", "e1", Instant.now(), Map.of(), Map.of()),
                new Subject("u1", SubjectType.USER, Map.of()),
                Map.of("balance", MetricValue.error("METRIC_FETCH_FAIL")),
                Instant.now());

        EvalResult r = new TracingInterpretedExecutor(KernelEvaluators.defaults()).execute(snap, ctx);

        assertThat(r.ruleHit()).isFalse();
        NodeTrace t = r.nodeTrace().get(0);
        assertThat(t.result()).isFalse();
        assertThat(t.errorCode()).isEqualTo("METRIC_FETCH_FAIL");
    }
}
```

- [ ] **Step 4: 改 `InterpretedExecutor` 经门面（NO_EVALUATOR 保留抛异常）**

把 `evaluateCondition` 替换为：
```java
    private boolean evaluateCondition(ConditionNode node, EvalContext ctx) {
        ConditionOutcome outcome = ConditionEvaluation.evaluate(node, ctx, evaluators);
        if (outcome.isError()) {
            // 无算子是配置错误，保留严格中止；取数失败则降级不命中
            if (ConditionEvaluation.NO_EVALUATOR.equals(outcome.errorCode())) {
                throw new IllegalStateException(
                        "No ConditionEvaluator registered for type: " + node.conditionType());
            }
            return false;
        }
        return outcome.satisfied();
    }
```

- [ ] **Step 5: 改 `ScorecardExecutor`——整卡 ERROR（风控保守）**

把 `execute` 方法替换为：
```java
    @Override
    public EvalResult execute(RuleVersionSnapshot snapshot, EvalContext ctx) {
        if (!(snapshot.conditionAst() instanceof ScorecardRootNode root)) {
            return new EvalResult(false, null, List.of(), List.of(),
                    "SCORECARD_AST_TYPE_MISMATCH", List.of(), null, null, null);
        }
        List<NodeTrace> traces = new ArrayList<>();
        double score = 0.0;
        Long rvId = snapshot.ruleVersionId();
        for (ConditionNode node : root.conditions()) {
            ConditionOutcome outcome = ConditionEvaluation.evaluate(node, ctx, evaluators);
            if (outcome.isError()) {
                // 风控保守：任一条件取数失败/无算子 → 整卡置 ERROR 不出分，避免漏分误判
                traces.add(new NodeTrace("ConditionNode", node.conditionType(), node.metricCode(),
                        false, null, null, outcome.errorCode(), List.of(), rvId));
                return new EvalResult(false, null, List.of(), traces,
                        outcome.errorCode(), List.of(), null, null, null);
            }
            boolean met = outcome.satisfied();
            if (met && node.weight() != null) score += node.weight();
            traces.add(new NodeTrace("ConditionNode", node.conditionType(), node.metricCode(),
                    met, null, null, null, List.of(), rvId));
        }
        boolean hit = score >= root.threshold();
        return new EvalResult(hit, null, List.of(), traces, null, List.of(), score, null, null);
    }
```
顶部加 import：`com.sstlfsj.rule.kernel.api.model.MetricValue` 不需要；确保 `ScorecardRootNode` 已 import（原文件已有）。

`MetricErrorScorecardTest.java`：
```java
package com.sstlfsj.rule.kernel.evaluator;

import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.model.ast.ScorecardRootNode;
import com.sstlfsj.rule.kernel.internal.condition.KernelEvaluators;
import com.sstlfsj.rule.kernel.internal.evaluator.ScorecardExecutor;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MetricErrorScorecardTest {

    @Test
    void anyErrorCondition_wholeCardError_noScore() {
        ConditionNode c1 = new ConditionNode("GT", "good", null, Map.of("threshold", 1), 10.0, "LONG");
        ConditionNode c2 = new ConditionNode("GT", "broken", null, Map.of("threshold", 1), 10.0, "LONG");
        ScorecardRootNode root = new ScorecardRootNode(List.of(c1, c2), 5.0);
        RuleVersionSnapshot snap = RuleVersionSnapshot.builder()
                .ruleVersionId(1L).sceneCode("PAY").tenantId("1").conditionAst(root)
                .kind("SCORECARD").build();
        EvalContext ctx = new EvalContext("1",
                new RuleEvent("1", "PAY", "transfer", "u1", "e1", Instant.now(), Map.of(), Map.of()),
                new Subject("u1", SubjectType.USER, Map.of()),
                Map.of("good", new MetricValue(100L, "LONG", "FETCHED"),
                       "broken", MetricValue.error("METRIC_FETCH_FAIL")),
                Instant.now());

        EvalResult r = new ScorecardExecutor(KernelEvaluators.defaults()).execute(snap, ctx);

        assertThat(r.ruleHit()).isFalse();
        assertThat(r.errorCode()).isEqualTo("METRIC_FETCH_FAIL");
        assertThat(r.score()).isNull();
    }
}
```

- [ ] **Step 6: 改 `DecisionTreeExecutor`——遇 ERROR 整规则 ERROR + miss**

把 `evaluateIf` 与 `evaluateCondition` 替换为（`evaluateCondition` 改为返回 `ConditionOutcome` 并传播 ERROR）：
```java
    private EvalResult evaluateIf(IfNode ifNode, RuleVersionSnapshot snapshot, EvalContext ctx) {
        ConditionOutcome cond = evaluateCondition(ifNode.condition(), ctx);
        if (cond.isError()) {
            // 取数失败：不静默走 else，整规则置 ERROR + miss（避免命中错误叶子）
            return new EvalResult(false, null, List.of(), List.of(),
                    cond.errorCode(), List.of(), null, null, null);
        }
        if (cond.satisfied()) {
            return evaluate(ifNode.thenBranch(), snapshot, ctx);
        } else if (ifNode.elseBranch() != null) {
            return evaluate(ifNode.elseBranch(), snapshot, ctx);
        } else {
            return EvalResult.miss();
        }
    }

    private ConditionOutcome evaluateCondition(AstNode node, EvalContext ctx) {
        return switch (node) {
            case ConditionNode c -> ConditionEvaluation.evaluate(c, ctx, evaluators);
            case AndNode and -> {
                for (AstNode child : and.children()) {
                    ConditionOutcome o = evaluateCondition(child, ctx);
                    if (o.isError()) yield o;                       // ERROR 传播
                    if (!o.satisfied()) yield ConditionOutcome.NOT_SATISFIED; // 短路 false
                }
                yield ConditionOutcome.SATISFIED;
            }
            case OrNode or -> {
                String errCode = null;
                for (AstNode child : or.children()) {
                    ConditionOutcome o = evaluateCondition(child, ctx);
                    if (o.satisfied()) yield ConditionOutcome.SATISFIED; // 命中即短路，不在意其它
                    if (o.isError()) errCode = o.errorCode();
                }
                // 全不满足；若曾有 ERROR 则整体不可判定（保守）
                yield errCode != null ? ConditionOutcome.error(errCode) : ConditionOutcome.NOT_SATISFIED;
            }
            case NotNode not -> {
                ConditionOutcome o = evaluateCondition(not.child(), ctx);
                yield o.isError() ? o : ConditionOutcome.of(!o.satisfied());
            }
            default -> ConditionOutcome.error(ConditionEvaluation.NO_EVALUATOR);
        };
    }
```

`MetricErrorDecisionTreeTest.java`：
```java
package com.sstlfsj.rule.kernel.evaluator;

import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.model.ast.DecisionLeafNode;
import com.sstlfsj.rule.kernel.api.model.ast.IfNode;
import com.sstlfsj.rule.kernel.internal.condition.KernelEvaluators;
import com.sstlfsj.rule.kernel.internal.evaluator.DecisionTreeExecutor;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MetricErrorDecisionTreeTest {

    @Test
    void errorCondition_wholeRuleError_doesNotTakeElse() {
        ConditionNode cond = new ConditionNode("GT", "balance", null, Map.of("threshold", 100), null, "LONG");
        IfNode root = new IfNode(cond,
                new DecisionLeafNode("REVIEW", "high"),
                new DecisionLeafNode("PASS", "low"));   // else 是"放行"，绝不能因取数失败静默命中
        RuleVersionSnapshot snap = RuleVersionSnapshot.builder()
                .ruleVersionId(1L).sceneCode("PAY").tenantId("1").conditionAst(root)
                .kind("DECISION_TREE").build();
        EvalContext ctx = new EvalContext("1",
                new RuleEvent("1", "PAY", "transfer", "u1", "e1", Instant.now(), Map.of(), Map.of()),
                new Subject("u1", SubjectType.USER, Map.of()),
                Map.of("balance", MetricValue.error("METRIC_FETCH_FAIL")), Instant.now());

        EvalResult r = new DecisionTreeExecutor(KernelEvaluators.defaults()).execute(snap, ctx);

        assertThat(r.ruleHit()).isFalse();           // 没有静默命中 PASS
        assertThat(r.errorCode()).isEqualTo("METRIC_FETCH_FAIL");
    }
}
```
> ⚠️ 核对 `DecisionLeafNode` / `IfNode` 构造签名（实现期对照实际 AST record，本测试按 `DecisionLeafNode(decisionCode, category)` / `IfNode(condition, then, else)` 写；若签名不同照实改）。

- [ ] **Step 7: 改 `DecisionTableExecutor`——遇 ERROR 整表 ERROR + miss**

把 `execute` 与 `rowMatches` 替换为（引入 `RowResult` 区分「不匹配」与「ERROR」）：
```java
    @Override
    public EvalResult execute(RuleVersionSnapshot snapshot, EvalContext ctx) {
        if (!(snapshot.conditionAst() instanceof DecisionTableNode table)) {
            return new EvalResult(false, null, List.of(), List.of(),
                    "DECISION_TABLE_AST_TYPE_MISMATCH", List.of(), null, null, null);
        }
        List<DecisionTableNode.Column> columns = table.columns();
        for (DecisionTableNode.Row row : table.rows()) {
            RowResult rr = rowMatches(row, columns, ctx);
            if (rr.error() != null) {
                // 取数失败：不静默落下一行，整表置 ERROR + miss
                return new EvalResult(false, null, List.of(), List.of(),
                        rr.error(), List.of(), null, null, null);
            }
            if (rr.matched()) return hit(row.decisionCode(), snapshot);
        }
        return EvalResult.miss();
    }

    /** 行匹配结果：matched=该行是否全列满足；error 非 null 表示取数失败（中止整表）。 */
    private record RowResult(boolean matched, String error) {}

    private RowResult rowMatches(DecisionTableNode.Row row,
                                 List<DecisionTableNode.Column> columns, EvalContext ctx) {
        List<Object> conditions = row.conditions();
        for (int i = 0; i < columns.size(); i++) {
            Object condValue = (i < conditions.size()) ? conditions.get(i) : null;
            if (condValue == null) continue; // null 通配
            DecisionTableNode.Column col = columns.get(i);
            Map<String, Object> params = buildParams(col.operator(), condValue);
            ConditionNode node = new ConditionNode(col.operator(), col.metricCode(), null, params, 0.0);
            ConditionOutcome o = ConditionEvaluation.evaluate(node, ctx, evaluators);
            if (o.isError()) return new RowResult(false, o.errorCode());
            if (!o.satisfied()) return new RowResult(false, null); // 本行不匹配，试下一行
        }
        return new RowResult(true, null);
    }
```

`MetricErrorDecisionTableTest.java`：
```java
package com.sstlfsj.rule.kernel.evaluator;

import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.kernel.api.model.ast.DecisionTableNode;
import com.sstlfsj.rule.kernel.internal.condition.KernelEvaluators;
import com.sstlfsj.rule.kernel.internal.evaluator.DecisionTableExecutor;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MetricErrorDecisionTableTest {

    @Test
    void errorColumn_wholeTableError_doesNotFallThrough() {
        DecisionTableNode.Column col = new DecisionTableNode.Column("balance", "GT");
        DecisionTableNode.Row row = new DecisionTableNode.Row(List.of(100), "REVIEW");
        DecisionTableNode table = new DecisionTableNode(List.of(col), List.of(row));
        RuleVersionSnapshot snap = RuleVersionSnapshot.builder()
                .ruleVersionId(1L).sceneCode("PAY").tenantId("1").conditionAst(table)
                .kind("DECISION_TABLE").build();
        EvalContext ctx = new EvalContext("1",
                new RuleEvent("1", "PAY", "transfer", "u1", "e1", Instant.now(), Map.of(), Map.of()),
                new Subject("u1", SubjectType.USER, Map.of()),
                Map.of("balance", MetricValue.error("METRIC_FETCH_FAIL")), Instant.now());

        EvalResult r = new DecisionTableExecutor(KernelEvaluators.defaults()).execute(snap, ctx);

        assertThat(r.ruleHit()).isFalse();
        assertThat(r.errorCode()).isEqualTo("METRIC_FETCH_FAIL");
    }
}
```
> ⚠️ 核对 `DecisionTableNode.Column` / `Row` / `DecisionTableNode` 构造签名（实现期对照实际 AST record）。

- [ ] **Step 8: 跑 kernel 全量（含原有执行器回归）+ 提交**

Run: `$MVN -pl rule-kernel -am test`
Expected: PASS（原有 Scorecard/DecisionTree/DecisionTable 测试不受影响——非 ERROR 路径行为不变）。

```bash
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/evaluator/ rule-kernel/src/test/java/com/sstlfsj/rule/kernel/evaluator/
git commit -m "feat(kernel): 统一条件求值门面(三态)+各执行器取数失败语义(评分卡/决策树表整规则ERROR)"
```

---

### Task 7: eval-svc 接线（resolver + Caffeine 缓存 + fetchExecutor + 读 metric_dependencies）

> 把内核 SPI 落地：读 metric_definition 的 resolver（带定义缓存）、Caffeine 取数缓存、专用线程池，并在 `EvalAutoConfiguration` 用富构造装配 assembler。RuleVersionReadMapper 补查 `metric_dependencies`。

**Files:**
- Modify: `rule-eval-svc/pom.xml`
- Modify: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/repository/RuleVersionReadMapper.java`
- Create: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/domain/MetricDefinitionRow.java`
- Create: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/repository/MetricDefinitionReadMapper.java`
- Create: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/metric/DbMetricDefinitionResolver.java`
- Create: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/metric/CaffeineMetricCache.java`
- Modify: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/EvalAutoConfiguration.java`
- Test: `rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/metric/CaffeineMetricCacheTest.java`
- Test: `rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/metric/DbMetricDefinitionResolverIT.java`

- [ ] **Step 1: 加 Caffeine 依赖**

`rule-eval-svc/pom.xml`，在 `<dependencies>` 内（guava 依赖之后）加：

```xml
        <dependency>
            <groupId>com.github.ben-manes.caffeine</groupId>
            <artifactId>caffeine</artifactId>
        </dependency>
```

> 版本由 Spring Boot BOM 管理（Spring 缓存抽象内置 Caffeine 版本），不写 `<version>`。若根 `pom.xml` 的 `<dependencyManagement>` 未覆盖，则在根 pom 加 caffeine BOM 版本管理。先不写版本跑构建，缺版本再补。

- [ ] **Step 2: RuleVersionReadMapper 三个 SELECT 补 metric_dependencies**

每个 `@Select` 的列清单里，在 `rd.kind AS kind,` 之后加一行：
```
              rv.metric_dependencies AS metricDependenciesJson,
```
（三处 SELECT 都加。`loadById` 同样加。）

- [ ] **Step 3: 跑现有 eval-svc 测试确认 RuleVersionRow 映射仍工作**

Run: `$MVN -pl rule-eval-svc -am test -Dtest=*SnapshotLoader*,*IndexStartup*`
Expected: PASS（metricDependenciesJson 现在被填充；旧断言不受影响）。
> 若 RuleVersionRow 构造在 Task 3 已加 `metricDependenciesJson` 字段，则映射自动按列名匹配。

- [ ] **Step 4: 写 `MetricDefinitionRow`**

```java
package com.sstlfsj.rule.eval.internal.domain;

/** metric_definition 只读映射载体（供 DbMetricDefinitionResolver 装配 MetricDescriptor）。 */
public record MetricDefinitionRow(
        String metricCode,
        String sourceType,
        String dataType,
        Boolean allowProvided,
        Integer cacheTtlSeconds,
        String paramsJson
) {}
```

- [ ] **Step 5: 写 `MetricDefinitionReadMapper`**

```java
package com.sstlfsj.rule.eval.internal.repository;

import com.sstlfsj.rule.eval.internal.domain.MetricDefinitionRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 只读 Mapper：按 tenant + metricCode 查 ACTIVE metric_definition。 */
@Mapper
public interface MetricDefinitionReadMapper {

    /**
     * 查询单个 ACTIVE metric 定义。
     *
     * @param tenantId   租户 id
     * @param metricCode 指标编码
     * @return 行；不存在返回 null
     */
    @Select("""
            SELECT metric_code       AS metricCode,
                   source_type       AS sourceType,
                   data_type         AS dataType,
                   allow_provided    AS allowProvided,
                   cache_ttl_seconds AS cacheTtlSeconds,
                   params            AS paramsJson
            FROM metric_definition
            WHERE tenant_id = #{tenantId} AND metric_code = #{metricCode} AND status = 'ACTIVE'
            """)
    MetricDefinitionRow findActive(@Param("tenantId") long tenantId,
                                   @Param("metricCode") String metricCode);
}
```

- [ ] **Step 6: 写 `CaffeineMetricCache` + 单元测试**

实现（per-entry TTL 用 Caffeine `Expiry`）：

```java
package com.sstlfsj.rule.eval.internal.metric;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.sstlfsj.rule.kernel.api.model.MetricValue;
import com.sstlfsj.rule.kernel.api.spi.metric.MetricCache;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/** 进程内 Caffeine 取数缓存，按 metric 各自 ttlSeconds 过期。 */
@Component
public class CaffeineMetricCache implements MetricCache {

    private record Entry(MetricValue value, int ttlSeconds) {}

    private final Cache<String, Entry> cache = Caffeine.newBuilder()
            .maximumSize(100_000)
            .expireAfter(new Expiry<String, Entry>() {
                @Override public long expireAfterCreate(String k, Entry e, long now) {
                    return TimeUnit.SECONDS.toNanos(e.ttlSeconds());
                }
                @Override public long expireAfterUpdate(String k, Entry e, long now, long dur) {
                    return TimeUnit.SECONDS.toNanos(e.ttlSeconds());
                }
                @Override public long expireAfterRead(String k, Entry e, long now, long dur) {
                    return dur;
                }
            })
            .build();

    @Override
    public MetricValue get(String key) {
        Entry e = cache.getIfPresent(key);
        return e == null ? null : e.value();
    }

    @Override
    public void put(String key, MetricValue value, int ttlSeconds) {
        if (ttlSeconds > 0) cache.put(key, new Entry(value, ttlSeconds));
    }
}
```

单元测试 `CaffeineMetricCacheTest.java`：

```java
package com.sstlfsj.rule.eval.internal.metric;

import com.sstlfsj.rule.kernel.api.model.MetricValue;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CaffeineMetricCacheTest {

    @Test
    void put_then_get_returnsValue() {
        CaffeineMetricCache c = new CaffeineMetricCache();
        c.put("k", new MetricValue(5L, "LONG", "FETCHED"), 60);
        assertThat(c.get("k").value()).isEqualTo(5L);
    }

    @Test
    void ttlZero_notCached() {
        CaffeineMetricCache c = new CaffeineMetricCache();
        c.put("k", new MetricValue(5L, "LONG", "FETCHED"), 0);
        assertThat(c.get("k")).isNull();
    }

    @Test
    void missing_returnsNull() {
        assertThat(new CaffeineMetricCache().get("absent")).isNull();
    }
}
```

- [ ] **Step 7: 写 `DbMetricDefinitionResolver`**

```java
package com.sstlfsj.rule.eval.internal.metric;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.sstlfsj.rule.eval.internal.domain.MetricDefinitionRow;
import com.sstlfsj.rule.eval.internal.repository.MetricDefinitionReadMapper;
import com.sstlfsj.rule.kernel.api.model.MetricDescriptor;
import com.sstlfsj.rule.kernel.api.spi.metric.MetricDefinitionResolver;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;

/**
 * 读 metric_definition 实现 MetricDefinitionResolver；用 Caffeine 缓存定义快照（短 TTL），
 * 避免每次评估查库。定义级配置变更在 TTL 内最终一致。
 */
@Component
public class DbMetricDefinitionResolver implements MetricDefinitionResolver {

    private final MetricDefinitionReadMapper mapper;
    private final ObjectMapper objectMapper;
    private final Cache<String, MetricDescriptor> cache = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(Duration.ofSeconds(60))
            .build();

    public DbMetricDefinitionResolver(MetricDefinitionReadMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public MetricDescriptor resolve(String tenantId, String metricCode) {
        String key = tenantId + ":" + metricCode;
        MetricDescriptor cached = cache.getIfPresent(key);
        if (cached != null) return cached;
        MetricDefinitionRow row = mapper.findActive(Long.parseLong(tenantId), metricCode);
        if (row == null) return null;   // 不缓存 null，避免遮蔽新建定义
        MetricDescriptor d = new MetricDescriptor(
                row.metricCode(), row.sourceType(), row.dataType(),
                Boolean.TRUE.equals(row.allowProvided()),
                row.cacheTtlSeconds() == null ? 0 : row.cacheTtlSeconds(),
                parseParams(row.paramsJson()));
        cache.put(key, d);
        return d;
    }

    private Map<String, Object> parseParams(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }
}
```

- [ ] **Step 8: 改 `EvalAutoConfiguration`——fetchExecutor bean + 富构造装配 assembler**

文件顶部加 import：
```java
import com.sstlfsj.rule.kernel.api.annotation.MetricSourceType;
import com.sstlfsj.rule.kernel.api.model.MetricDescriptor;
import com.sstlfsj.rule.kernel.api.spi.metric.MetricCache;
import com.sstlfsj.rule.kernel.api.spi.metric.MetricDefinitionResolver;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import java.util.concurrent.Executor;
```

加 fetchExecutor bean：
```java
    /**
     * 取数专用线程池：并发 fetch 多个 metric，延迟 = max 而非 sum。
     *
     * @return 命名为 metricFetchExecutor 的线程池
     */
    @Bean(name = "metricFetchExecutor")
    public Executor metricFetchExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(8);
        ex.setMaxPoolSize(32);
        ex.setQueueCapacity(256);
        ex.setThreadNamePrefix("metric-fetch-");
        ex.initialize();
        return ex;
    }
```

把 `evalContextAssembler` bean 整体替换为富构造版本：
```java
    /**
     * 装配 EvalContext；按 @MetricSourceType 归类 handler，注入 resolver/cache/fetchExecutor。
     * resolver 为 null（无定义解析器）时 assembler 退化为仅 providedMetrics 生效。
     *
     * @param subjectLoaders     可选 SubjectLoader SPI 列表
     * @param metricHandlers     可选 MetricSourceHandler SPI 列表
     * @param definitionResolver metric 定义解析器（可选）
     * @param metricCache        取数缓存（可选）
     * @param fetchExecutor      取数线程池
     * @param fetchTimeoutMs     全局取数超时毫秒
     * @return EvalContextAssembler 实例
     */
    @Bean
    public EvalContextAssembler evalContextAssembler(
            @Autowired(required = false) List<SubjectLoader> subjectLoaders,
            @Autowired(required = false) List<MetricSourceHandler> metricHandlers,
            @Autowired(required = false) MetricDefinitionResolver definitionResolver,
            @Autowired(required = false) MetricCache metricCache,
            @Qualifier("metricFetchExecutor") Executor fetchExecutor,
            @Value("${rule.fetch.timeout-ms:800}") long fetchTimeoutMs) {
        Map<String, MetricSourceHandler> bySource = new HashMap<>();
        if (metricHandlers != null) {
            for (MetricSourceHandler h : metricHandlers) {
                MetricSourceType ann = h.getClass().getAnnotation(MetricSourceType.class);
                if (ann != null) bySource.put(ann.value(), h);
            }
        }
        return new EvalContextAssembler(
                subjectLoaders == null ? List.of() : subjectLoaders,
                bySource, definitionResolver, metricCache, fetchExecutor, fetchTimeoutMs);
    }
```

- [ ] **Step 9: 写 resolver 集成测试（Testcontainers）**

参考现有 eval-svc 集成测试（如 `*SnapshotLoader*IT` / 用 `@SpringBootTest` + Testcontainers MySQL + Flyway 的既有基类）。`DbMetricDefinitionResolverIT.java`：

```java
package com.sstlfsj.rule.eval.internal.metric;

import com.sstlfsj.rule.kernel.api.model.MetricDescriptor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

// 复用 eval-svc 既有集成测试基类（Testcontainers MySQL + Flyway）。
// 若项目用 @SpringBootTest + @Testcontainers 注解组合，照搬 SceneSnapshotLoaderIT 的类级注解。
class DbMetricDefinitionResolverIT extends AbstractEvalIntegrationTest {

    @Autowired DbMetricDefinitionResolver resolver;
    @Autowired JdbcTemplate jdbc;

    @Test
    void resolve_readsMetricDefinition() {
        jdbc.update("""
                INSERT INTO metric_definition
                  (tenant_id, metric_code, name, source_type, data_type, params, cache_ttl_seconds, allow_provided, status)
                VALUES (1, 'risk.balance', '余额', 'SQL_AGGREGATE', 'LONG',
                        '{"datasource":"risk_ro","sql":"SELECT 1"}', 30, 0, 'ACTIVE')
                """);

        MetricDescriptor d = resolver.resolve("1", "risk.balance");

        assertThat(d).isNotNull();
        assertThat(d.sourceType()).isEqualTo("SQL_AGGREGATE");
        assertThat(d.cacheTtlSeconds()).isEqualTo(30);
        assertThat(d.allowProvided()).isFalse();
        assertThat(d.params()).containsEntry("datasource", "risk_ro");
    }

    @Test
    void resolve_missing_returnsNull() {
        assertThat(resolver.resolve("1", "nope.absent")).isNull();
    }
}
```

> ⚠️ 实现期核对：`AbstractEvalIntegrationTest` 是占位名——替换为 eval-svc 现有集成测试基类的真实名字（`grep -rln "Testcontainers" rule-eval-svc/src/test`）。若无共享基类，照搬 `SceneSnapshotLoader` 集成测试的类级注解到本类。

- [ ] **Step 10: 跑 eval-svc 全量测试**

Run: `$MVN -pl rule-eval-svc -am test`
Expected: PASS。

- [ ] **Step 11: 提交**

```bash
git add rule-eval-svc/pom.xml rule-eval-svc/src
git commit -m "feat(eval): 接线取数管线(DbMetricDefinitionResolver/Caffeine缓存/fetchExecutor)"
```

**Phase 1 完成标志：** kernel + eval-svc 全量测试通过；FETCHED 管线端到端可用（resolver 读库 → 并发 fetch → 缓存 → 失败降级 → NodeTrace 错码）。此时尚无真实 SQL/HTTP handler，但契约齐备。

---

# Phase 2 — SQL_AGGREGATE 范式

> 命名只读 DataSource + 命名参数（禁拼接、禁 DB 时间函数、:now 绑引擎时钟）+ 结果按 dataType 强转。依赖 Phase 1 的 `MetricSourceHandler`/`MetricQuery.now`/`MetricValue.error`。

### Task 8: `FetchResourceProperties` + `MetricDataSourceRegistry`

**Files:**
- Create: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/metric/sql/FetchResourceProperties.java`
- Create: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/metric/sql/MetricDataSourceRegistry.java`
- Modify: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/EvalAutoConfiguration.java`（启用 `@EnableConfigurationProperties`）
- Test: `rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/metric/sql/MetricDataSourceRegistryTest.java`

- [ ] **Step 1: 写 `FetchResourceProperties`**

```java
package com.sstlfsj.rule.eval.internal.metric.sql;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 取数资源配置：命名 DataSource / HTTP 端点 / 全局超时。
 * 凭证从环境变量/secrets 注入（如 password: ${RISK_RO_PASSWORD}），不落配置表。
 */
@ConfigurationProperties(prefix = "rule.fetch")
public class FetchResourceProperties {

    /** 全局取数超时毫秒（assembler 用，默认 800）。 */
    private long timeoutMs = 800;
    /** 命名只读数据源列表。 */
    private List<DataSourceDef> datasources = new ArrayList<>();
    /** 命名 HTTP 端点列表（Phase 3 使用）。 */
    private List<EndpointDef> endpoints = new ArrayList<>();

    public long getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(long timeoutMs) { this.timeoutMs = timeoutMs; }
    public List<DataSourceDef> getDatasources() { return datasources; }
    public void setDatasources(List<DataSourceDef> datasources) { this.datasources = datasources; }
    public List<EndpointDef> getEndpoints() { return endpoints; }
    public void setEndpoints(List<EndpointDef> endpoints) { this.endpoints = endpoints; }

    /** 命名只读数据源定义。 */
    public static class DataSourceDef {
        private String name;
        private String url;
        private String username;
        private String password;
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
    }

    /** 命名 HTTP 端点定义（Phase 3）。 */
    public static class EndpointDef {
        private String name;
        private String baseUrl;
        private String authHeaderName;
        private String authHeaderValue;
        private int connectTimeoutMs = 1000;
        private int readTimeoutMs = 2000;
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getAuthHeaderName() { return authHeaderName; }
        public void setAuthHeaderName(String authHeaderName) { this.authHeaderName = authHeaderName; }
        public String getAuthHeaderValue() { return authHeaderValue; }
        public void setAuthHeaderValue(String authHeaderValue) { this.authHeaderValue = authHeaderValue; }
        public int getConnectTimeoutMs() { return connectTimeoutMs; }
        public void setConnectTimeoutMs(int connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }
        public int getReadTimeoutMs() { return readTimeoutMs; }
        public void setReadTimeoutMs(int readTimeoutMs) { this.readTimeoutMs = readTimeoutMs; }
    }
}
```

- [ ] **Step 2: 写 `MetricDataSourceRegistry`**

```java
package com.sstlfsj.rule.eval.internal.metric.sql;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 命名只读 DataSource 注册表：按配置建只读 Hikari 连接池，封 NamedParameterJdbcTemplate。
 * metric 只能引用已注册的逻辑名（杜绝误写主库、灭 SSRF）。
 */
@Component
public class MetricDataSourceRegistry implements AutoCloseable {

    private final Map<String, NamedParameterJdbcTemplate> templates = new HashMap<>();
    private final Map<String, HikariDataSource> pools = new HashMap<>();

    public MetricDataSourceRegistry(FetchResourceProperties props) {
        for (FetchResourceProperties.DataSourceDef def : props.getDatasources()) {
            HikariConfig cfg = new HikariConfig();
            cfg.setJdbcUrl(def.getUrl());
            cfg.setUsername(def.getUsername());
            cfg.setPassword(def.getPassword());
            cfg.setReadOnly(true);                 // 只读：拒绝写操作，卸载主库
            cfg.setMaximumPoolSize(8);
            cfg.setPoolName("metric-ro-" + def.getName());
            HikariDataSource ds = new HikariDataSource(cfg);
            pools.put(def.getName(), ds);
            templates.put(def.getName(), new NamedParameterJdbcTemplate(ds));
        }
    }

    /**
     * 取命名数据源的 NamedParameterJdbcTemplate。
     *
     * @param name 逻辑数据源名
     * @return template；未注册返回 null
     */
    public NamedParameterJdbcTemplate template(String name) {
        return templates.get(name);
    }

    /** @return 是否已注册该名字。 */
    public boolean isRegistered(String name) {
        return templates.containsKey(name);
    }

    /** @return 所有已注册的数据源名（供发布期资源名校验）。 */
    public java.util.Set<String> names() {
        return java.util.Set.copyOf(templates.keySet());
    }

    @Override
    public void close() {
        pools.values().forEach(HikariDataSource::close);
    }
}
```

> HikariCP 已由 `mybatis-plus-spring-boot4-starter` / Spring Boot JDBC 传递依赖引入（主数据源即用 Hikari），无需新增依赖。确认：`grep -rn "HikariDataSource" rule-eval-svc rule-app` 或看主 DataSource 类型。

- [ ] **Step 3: `EvalAutoConfiguration` 启用配置属性**

类注解加：
```java
@org.springframework.boot.context.properties.EnableConfigurationProperties(
        com.sstlfsj.rule.eval.internal.metric.sql.FetchResourceProperties.class)
```
并把 assembler bean 的 `@Value("${rule.fetch.timeout-ms:800}") long fetchTimeoutMs` 改为注入 `FetchResourceProperties props` 取 `props.getTimeoutMs()`（统一来源）：把参数 `@Value(...) long fetchTimeoutMs` 换成 `FetchResourceProperties fetchProps`，构造调用末参传 `fetchProps.getTimeoutMs()`。

- [ ] **Step 4: 写 registry 测试**

```java
package com.sstlfsj.rule.eval.internal.metric.sql;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MetricDataSourceRegistryTest {

    @Test
    void unregisteredName_returnsNull() {
        FetchResourceProperties props = new FetchResourceProperties();
        props.setDatasources(List.of());
        try (MetricDataSourceRegistry reg = new MetricDataSourceRegistry(props)) {
            assertThat(reg.template("nope")).isNull();
            assertThat(reg.isRegistered("nope")).isFalse();
            assertThat(reg.names()).isEmpty();
        }
    }

    @Test
    void h2InMemory_registersAndQueries() {
        FetchResourceProperties.DataSourceDef def = new FetchResourceProperties.DataSourceDef();
        def.setName("ro");
        def.setUrl("jdbc:h2:mem:rotest;DB_CLOSE_DELAY=-1");
        def.setUsername("sa");
        def.setPassword("");
        FetchResourceProperties props = new FetchResourceProperties();
        props.setDatasources(List.of(def));
        try (MetricDataSourceRegistry reg = new MetricDataSourceRegistry(props)) {
            assertThat(reg.isRegistered("ro")).isTrue();
            Long one = reg.template("ro").getJdbcTemplate().queryForObject("SELECT 1", Long.class);
            assertThat(one).isEqualTo(1L);
        }
    }
}
```

> ⚠️ 上面用 H2 做内存数据源烟雾测试。若 eval-svc 测试 classpath 无 H2，改为 `<scope>test</scope>` 加 `com.h2database:h2` 依赖，或删第二个用例只留 registry 行为单测。先 `grep -rn "h2database" rule-eval-svc/pom.xml` 确认。

- [ ] **Step 5: 跑测试**

Run: `$MVN -pl rule-eval-svc -am test -Dtest=MetricDataSourceRegistryTest`
Expected: PASS。

- [ ] **Step 6: 提交**

```bash
git add rule-eval-svc/src
git commit -m "feat(eval): 命名只读 DataSource 注册 + rule.fetch 配置属性(B21 SQL 范式)"
```

---

### Task 9: `DataTypeCoercion` + `SqlAggregateMetricSourceHandler`

**Files:**
- Create: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/metric/DataTypeCoercion.java`
- Create: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/metric/sql/SqlAggregateMetricSourceHandler.java`
- Test: `rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/metric/DataTypeCoercionTest.java`
- Test: `rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/metric/sql/SqlAggregateMetricSourceHandlerTest.java`

- [ ] **Step 1: 写 `DataTypeCoercion` 测试**

```java
package com.sstlfsj.rule.eval.internal.metric;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class DataTypeCoercionTest {

    @Test
    void longFromBigDecimal() {
        assertThat(DataTypeCoercion.coerce(new BigDecimal("42"), "LONG")).isEqualTo(42L);
    }

    @Test
    void doubleFromInteger() {
        assertThat(DataTypeCoercion.coerce(7, "DOUBLE")).isEqualTo(7.0d);
    }

    @Test
    void stringFromNumber() {
        assertThat(DataTypeCoercion.coerce(5L, "STRING")).isEqualTo("5");
    }

    @Test
    void booleanFromNumber() {
        assertThat(DataTypeCoercion.coerce(1, "BOOLEAN")).isEqualTo(true);
        assertThat(DataTypeCoercion.coerce(0, "BOOLEAN")).isEqualTo(false);
    }

    @Test
    void nullStaysNull() {
        assertThat(DataTypeCoercion.coerce(null, "LONG")).isNull();
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `$MVN -pl rule-eval-svc -am test -Dtest=DataTypeCoercionTest`
Expected: 编译失败——`DataTypeCoercion` 不存在。

- [ ] **Step 3: 写 `DataTypeCoercion`**

```java
package com.sstlfsj.rule.eval.internal.metric;

import java.math.BigDecimal;

/** 把取数原始值（ResultSet / JSON）按 metric dataType 强转。null 透传。 */
public final class DataTypeCoercion {

    private DataTypeCoercion() {}

    /**
     * 按 dataType 强转。
     *
     * @param raw      原始值（可能为 Number / String / Boolean / null）
     * @param dataType LONG/DOUBLE/STRING/BOOLEAN/DATE/DATETIME
     * @return 强转后的值；无法转换时返回 null
     */
    public static Object coerce(Object raw, String dataType) {
        if (raw == null || dataType == null) return raw;
        try {
            return switch (dataType) {
                case "LONG" -> toLong(raw);
                case "DOUBLE" -> toDouble(raw);
                case "BOOLEAN" -> toBoolean(raw);
                // STRING / DATE / DATETIME：字符串化，交由 evaluator 的 PlaceholderResolver 再解析
                default -> raw.toString();
            };
        } catch (Exception e) {
            return null;
        }
    }

    private static Long toLong(Object raw) {
        if (raw instanceof Number n) return n.longValue();
        if (raw instanceof BigDecimal b) return b.longValue();
        return Long.parseLong(raw.toString().trim());
    }

    private static Double toDouble(Object raw) {
        if (raw instanceof Number n) return n.doubleValue();
        return Double.parseDouble(raw.toString().trim());
    }

    private static Boolean toBoolean(Object raw) {
        if (raw instanceof Boolean b) return b;
        if (raw instanceof Number n) return n.doubleValue() != 0;
        String s = raw.toString().trim();
        return "1".equals(s) || Boolean.parseBoolean(s);
    }
}
```

- [ ] **Step 4: 写 SQL handler 参数绑定测试**

```java
package com.sstlfsj.rule.eval.internal.metric.sql;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SqlAggregateMetricSourceHandlerTest {

    @Test
    void normalize_replacesDottedPlaceholders_andBindsValues() {
        Instant now = Instant.parse("2026-06-06T00:00:00Z");
        SqlAggregateMetricSourceHandler.Bound b = SqlAggregateMetricSourceHandler.bind(
                "SELECT COUNT(*) FROM t WHERE uid = :subjectId AND amt > :payload.amt "
                        + "AND created_at >= :now - INTERVAL :params.win DAY",
                "u1", "1", now, Map.of("amt", 500), Map.of("win", 7));

        assertThat(b.sql()).contains(":subjectId").contains(":payload_amt")
                .contains(":params_win").contains(":now").doesNotContain(":payload.amt");
        MapSqlParameterSource src = b.params();
        assertThat(src.getValue("subjectId")).isEqualTo("u1");
        assertThat(src.getValue("payload_amt")).isEqualTo(500);
        assertThat(src.getValue("params_win")).isEqualTo(7);
        assertThat(src.getValue("now")).isNotNull();
    }
}
```

- [ ] **Step 5: 跑测试确认失败**

Run: `$MVN -pl rule-eval-svc -am test -Dtest=SqlAggregateMetricSourceHandlerTest`
Expected: 编译失败——`SqlAggregateMetricSourceHandler` 不存在。

- [ ] **Step 6: 写 `SqlAggregateMetricSourceHandler`**

```java
package com.sstlfsj.rule.eval.internal.metric.sql;

import com.sstlfsj.rule.eval.internal.metric.DataTypeCoercion;
import com.sstlfsj.rule.kernel.api.annotation.MetricSourceType;
import com.sstlfsj.rule.kernel.api.model.MetricQuery;
import com.sstlfsj.rule.kernel.api.model.MetricValue;
import com.sstlfsj.rule.kernel.api.spi.metric.MetricSourceHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL_AGGREGATE 取数 handler：命名参数绑定（禁拼接）、:now 绑引擎时钟、取首行首列按 dataType 强转。
 * datasource/sql 来自 metric.params；handler 只跑只读命名数据源。
 */
@Component
@MetricSourceType("SQL_AGGREGATE")
public class SqlAggregateMetricSourceHandler implements MetricSourceHandler {

    /** 占位符：:ns.field 或 :name（点号命名空间用于 payload./params.）。 */
    private static final Pattern PLACEHOLDER = Pattern.compile(":([a-zA-Z_][\\w.]*)");
    private static final String METRIC_FETCH_FAIL = "METRIC_FETCH_FAIL";

    private final MetricDataSourceRegistry registry;

    public SqlAggregateMetricSourceHandler(MetricDataSourceRegistry registry) {
        this.registry = registry;
    }

    @Override
    public MetricValue fetch(MetricQuery query) {
        Object dsName = query.params().get("datasource");
        Object sqlText = query.params().get("sql");
        Object dataType = query.params().get("dataType"); // 由 resolver 注入到 params，见下
        if (dsName == null || sqlText == null) return MetricValue.error(METRIC_FETCH_FAIL);
        NamedParameterJdbcTemplate tpl = registry.template(dsName.toString());
        if (tpl == null) return MetricValue.error(METRIC_FETCH_FAIL);
        try {
            Bound bound = bind(sqlText.toString(), query.subjectId(), query.tenantId(),
                    query.now(), query.eventPayload(), castParams(query.params().get("params")));
            List<Object> firstCol = tpl.query(bound.sql(), bound.params(),
                    (rs, rowNum) -> rs.getObject(1));
            Object raw = firstCol.isEmpty() ? null : firstCol.get(0);
            String dt = dataType != null ? dataType.toString() : null;
            return new MetricValue(DataTypeCoercion.coerce(raw, dt), dt, "FETCHED");
        } catch (Exception e) {
            return MetricValue.error(METRIC_FETCH_FAIL);
        }
    }

    @SuppressWarnings("unchecked")
    private static java.util.Map<String, Object> castParams(Object raw) {
        return raw instanceof java.util.Map ? (java.util.Map<String, Object>) raw : java.util.Map.of();
    }

    /** 绑定结果：规范化后的 SQL（点号占位符→下划线）+ 参数源。 */
    public record Bound(String sql, MapSqlParameterSource params) {}

    /**
     * 把 SQL 中的命名占位符规范化为合法参数名并绑定值。
     * :subjectId/:tenantId/:now 直绑；:payload.X→:payload_X 绑 eventPayload.X；:params.X→:params_X 绑 params.X。
     *
     * @param sql       原始 SQL（仅命名参数，禁拼接）
     * @param subjectId 主体 id
     * @param tenantId  租户 id
     * @param now       引擎统一时钟
     * @param payload   事件 payload
     * @param params    metric.params.params 子 map
     * @return 绑定结果
     */
    public static Bound bind(String sql, String subjectId, String tenantId, java.time.Instant now,
                             java.util.Map<String, Object> payload, java.util.Map<String, Object> params) {
        MapSqlParameterSource src = new MapSqlParameterSource();
        src.addValue("subjectId", subjectId);
        src.addValue("tenantId", tenantId);
        src.addValue("now", now == null ? null : Timestamp.from(now));
        StringBuilder out = new StringBuilder();
        Matcher m = PLACEHOLDER.matcher(sql);
        while (m.find()) {
            String token = m.group(1);
            String replacement;
            if (token.contains(".")) {
                String[] parts = token.split("\\.", 2);
                String safe = parts[0] + "_" + parts[1];
                Object value = switch (parts[0]) {
                    case "payload" -> payload.get(parts[1]);
                    case "params" -> params.get(parts[1]);
                    default -> null;
                };
                src.addValue(safe, value);
                replacement = ":" + safe;
            } else {
                replacement = ":" + token; // subjectId/tenantId/now，已绑
            }
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return new Bound(out.toString(), src);
    }
}
```

> **dataType 注入约定**：handler 需要 metric 的 dataType 做结果强转。最干净的做法是 resolver 把 `dataType` 与 `sql`/`datasource` 一并放进传给 handler 的 `MetricQuery.params()`。落地方式：`DbMetricDefinitionResolver` 装配 `MetricDescriptor.params()` 时，把 DB `params` JSON 解析出的 map **附加** `dataType` 键（值取 `row.dataType()`）。在 Task 7 的 `DbMetricDefinitionResolver.parseParams` 之后合并：
> ```java
> Map<String, Object> p = new java.util.HashMap<>(parseParams(row.paramsJson()));
> p.put("dataType", row.dataType());
> // descriptor 用 p
> ```
> 执行本 Task 时回到 `DbMetricDefinitionResolver.resolve` 做这一处补充（并加单测断言 descriptor.params() 含 dataType）。

- [ ] **Step 7: 跑测试**

Run: `$MVN -pl rule-eval-svc -am test -Dtest=DataTypeCoercionTest,SqlAggregateMetricSourceHandlerTest`
Expected: PASS。

- [ ] **Step 8: 集成验证（Testcontainers，可选但推荐）**

新增 `SqlAggregateMetricSourceHandlerIT`（复用 eval-svc 集成基类）：在测试容器内建一张表插数据，注册一个指向同容器的命名只读源（`rule.fetch.datasources[0]` 通过 `@DynamicPropertySource` 写容器 JDBC url），构造 `MetricQuery`（params 含 datasource/sql/dataType + :now）验证返回正确强转值，并验证 `created_at >= :now - INTERVAL 7 DAY` 用注入 now 而非 DB NOW()（dry-run 重放：传一个历史 now，确认窗口按该 now 计算）。

> ⚠️ 类级注解与 `@DynamicPropertySource` 照搬 eval-svc 现有集成测试基类；只读源用同一容器但需注意 `setReadOnly(true)` 下 SELECT 正常、写被拒。

- [ ] **Step 9: 提交**

```bash
git add rule-eval-svc/src
git commit -m "feat(eval): SqlAggregateMetricSourceHandler(命名参数/:now绑引擎钟/结果强转)"
```

**Phase 2 完成标志：** SQL_AGGREGATE metric 可端到端取数；命名参数防注入、:now 保 dry-run 可重放、只读源卸载主库。

---

# Phase 3 — EXTERNAL_HTTP 范式

> 命名端点（baseURL+鉴权+超时，凭证不落表，灭 SSRF）；metric 只引用「端点名+path+jsonPath」。依赖 Phase 1 契约 + Phase 2 的 `FetchResourceProperties.EndpointDef`。

### Task 10: `HttpEndpointRegistry`

**Files:**
- Create: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/metric/http/HttpEndpointRegistry.java`
- Test: `rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/metric/http/HttpEndpointRegistryTest.java`

- [ ] **Step 1: 写 `HttpEndpointRegistry`**

```java
package com.sstlfsj.rule.eval.internal.metric.http;

import com.sstlfsj.rule.eval.internal.metric.sql.FetchResourceProperties;
import org.springframework.stereotype.Component;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 命名 HTTP 端点注册表：按配置建 HttpClient 与端点元数据（baseUrl/鉴权头/超时）。
 * metric 只能引用已注册的端点名，杜绝自由 URL 与 SSRF；凭证来自配置（env/secrets），不进 metric。
 */
@Component
public class HttpEndpointRegistry {

    /** 端点运行时句柄。 */
    public record Endpoint(String baseUrl, String authHeaderName, String authHeaderValue,
                           int readTimeoutMs, HttpClient client) {}

    private final Map<String, Endpoint> endpoints = new HashMap<>();

    public HttpEndpointRegistry(FetchResourceProperties props) {
        for (FetchResourceProperties.EndpointDef def : props.getEndpoints()) {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(def.getConnectTimeoutMs()))
                    .build();
            endpoints.put(def.getName(), new Endpoint(
                    def.getBaseUrl(), def.getAuthHeaderName(), def.getAuthHeaderValue(),
                    def.getReadTimeoutMs(), client));
        }
    }

    /**
     * 取命名端点句柄。
     *
     * @param name 端点逻辑名
     * @return 句柄；未注册返回 null
     */
    public Endpoint get(String name) {
        return endpoints.get(name);
    }

    /** @return 所有已注册端点名（供发布期资源名校验）。 */
    public Set<String> names() {
        return Set.copyOf(endpoints.keySet());
    }
}
```

- [ ] **Step 2: 写测试**

```java
package com.sstlfsj.rule.eval.internal.metric.http;

import com.sstlfsj.rule.eval.internal.metric.sql.FetchResourceProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HttpEndpointRegistryTest {

    @Test
    void registersByName() {
        FetchResourceProperties.EndpointDef def = new FetchResourceProperties.EndpointDef();
        def.setName("kyc");
        def.setBaseUrl("https://kyc.internal");
        def.setAuthHeaderName("X-Api-Key");
        def.setAuthHeaderValue("secret");
        FetchResourceProperties props = new FetchResourceProperties();
        props.setEndpoints(List.of(def));

        HttpEndpointRegistry reg = new HttpEndpointRegistry(props);

        assertThat(reg.get("kyc")).isNotNull();
        assertThat(reg.get("kyc").baseUrl()).isEqualTo("https://kyc.internal");
        assertThat(reg.names()).containsExactly("kyc");
        assertThat(reg.get("absent")).isNull();
    }
}
```

- [ ] **Step 3: 跑测试 + 提交**

Run: `$MVN -pl rule-eval-svc -am test -Dtest=HttpEndpointRegistryTest` → PASS。
```bash
git add rule-eval-svc/src
git commit -m "feat(eval): 命名 HTTP 端点注册(灭 SSRF,凭证不落表)"
```

---

### Task 11: `ExternalHttpMetricSourceHandler`

**Files:**
- Create: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/metric/http/ExternalHttpMetricSourceHandler.java`
- Test: `rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/metric/http/ExternalHttpMetricSourceHandlerTest.java`

- [ ] **Step 1: 写 path 渲染 + jsonPath 取值测试**

```java
package com.sstlfsj.rule.eval.internal.metric.http;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExternalHttpMetricSourceHandlerTest {

    private final ObjectMapper om = new ObjectMapper();

    @Test
    void renderPath_substitutesAndUrlEncodes() {
        String path = ExternalHttpMetricSourceHandler.renderPath(
                "/score/{payload.uid}/{params.kind}", Map.of("uid", "a b"), Map.of("kind", "risk"));
        assertThat(path).isEqualTo("/score/a%20b/risk");
    }

    @Test
    void extractJsonPath_navigatesDotPath() throws Exception {
        var node = om.readTree("{\"data\":{\"balance\":1234}}");
        Object v = ExternalHttpMetricSourceHandler.extractJsonPath(node, "data.balance");
        assertThat(v).isEqualTo(1234);
    }

    @Test
    void extractJsonPath_missing_returnsNull() throws Exception {
        var node = om.readTree("{\"data\":{}}");
        assertThat(ExternalHttpMetricSourceHandler.extractJsonPath(node, "data.balance")).isNull();
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `$MVN -pl rule-eval-svc -am test -Dtest=ExternalHttpMetricSourceHandlerTest`
Expected: 编译失败。

- [ ] **Step 3: 写 `ExternalHttpMetricSourceHandler`**

```java
package com.sstlfsj.rule.eval.internal.metric.http;

import com.sstlfsj.rule.eval.internal.metric.DataTypeCoercion;
import com.sstlfsj.rule.kernel.api.annotation.MetricSourceType;
import com.sstlfsj.rule.kernel.api.model.MetricQuery;
import com.sstlfsj.rule.kernel.api.model.MetricValue;
import com.sstlfsj.rule.kernel.api.spi.metric.MetricSourceHandler;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * EXTERNAL_HTTP 取数 handler：引用命名端点 + 相对 path（占位符）+ jsonPath。
 * 200+jsonPath 命中→FETCHED；200 无匹配→null；非 200/超时/连接失败→METRIC_FETCH_FAIL。
 */
@Component
@MetricSourceType("EXTERNAL_HTTP")
public class ExternalHttpMetricSourceHandler implements MetricSourceHandler {

    private static final String METRIC_FETCH_FAIL = "METRIC_FETCH_FAIL";
    private static final Pattern PH = Pattern.compile("\\{([a-zA-Z_][\\w.]*)\\}");

    private final HttpEndpointRegistry registry;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ExternalHttpMetricSourceHandler(HttpEndpointRegistry registry) {
        this.registry = registry;
    }

    @Override
    public MetricValue fetch(MetricQuery query) {
        Map<String, Object> p = query.params();
        Object endpointName = p.get("endpoint");
        Object path = p.get("path");
        Object jsonPath = p.get("jsonPath");
        Object dataType = p.get("dataType");
        if (endpointName == null || path == null || jsonPath == null) return MetricValue.error(METRIC_FETCH_FAIL);
        HttpEndpointRegistry.Endpoint ep = registry.get(endpointName.toString());
        if (ep == null) return MetricValue.error(METRIC_FETCH_FAIL);
        try {
            String rendered = renderPath(path.toString(), query.eventPayload(), castParams(p.get("params")));
            HttpRequest.Builder req = HttpRequest.newBuilder()
                    .uri(URI.create(ep.baseUrl() + rendered))
                    .timeout(Duration.ofMillis(ep.readTimeoutMs()))
                    .GET();
            if (ep.authHeaderName() != null && !ep.authHeaderName().isBlank()) {
                req.header(ep.authHeaderName(), ep.authHeaderValue());
            }
            HttpResponse<String> resp = ep.client().send(req.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return MetricValue.error(METRIC_FETCH_FAIL);
            JsonNode root = objectMapper.readTree(resp.body());
            Object raw = extractJsonPath(root, jsonPath.toString());
            String dt = dataType != null ? dataType.toString() : null;
            return new MetricValue(DataTypeCoercion.coerce(raw, dt), dt, "FETCHED");
        } catch (Exception e) {
            return MetricValue.error(METRIC_FETCH_FAIL);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castParams(Object raw) {
        return raw instanceof Map ? (Map<String, Object>) raw : Map.of();
    }

    /**
     * 渲染 path 占位符 {payload.x}/{params.x}，替换后对各段做 URL 编码。
     *
     * @param path    含占位符的相对路径
     * @param payload 事件 payload
     * @param params  metric.params.params 子 map
     * @return 渲染并编码后的路径
     */
    public static String renderPath(String path, Map<String, Object> payload, Map<String, Object> params) {
        Matcher m = PH.matcher(path);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String token = m.group(1);
            String[] parts = token.split("\\.", 2);
            Object value = parts.length == 2 ? switch (parts[0]) {
                case "payload" -> payload.get(parts[1]);
                case "params" -> params.get(parts[1]);
                default -> null;
            } : null;
            String enc = URLEncoder.encode(String.valueOf(value), StandardCharsets.UTF_8).replace("+", "%20");
            m.appendReplacement(out, Matcher.quoteReplacement(enc));
        }
        m.appendTail(out);
        return out.toString();
    }

    /**
     * 按点号 jsonPath 从 JSON 树取值（如 "data.balance"）。
     *
     * @param root     JSON 根
     * @param jsonPath 点号路径
     * @return 命中的标量值（Number/String/Boolean）；未命中返回 null
     */
    public static Object extractJsonPath(JsonNode root, String jsonPath) {
        JsonNode cur = root;
        for (String seg : jsonPath.split("\\.")) {
            if (cur == null) return null;
            cur = cur.get(seg);
        }
        if (cur == null || cur.isNull() || cur.isMissingNode()) return null;
        if (cur.isIntegralNumber()) return cur.intValue();
        if (cur.isFloatingPointNumber()) return cur.doubleValue();
        if (cur.isBoolean()) return cur.booleanValue();
        return cur.asString();
    }
}
```

> ⚠️ Jackson 3（`tools.jackson`）API 核对：`JsonNode.intValue()/doubleValue()/booleanValue()/asString()/isIntegralNumber()` 在 Jackson 3 的可用性，实现期对照已用的 `tools.jackson.databind` 版本（项目已用 Jackson 3，见 PublishService import）。若 `asString()` 不存在则用 `asText()`。

- [ ] **Step 4: 跑测试**

Run: `$MVN -pl rule-eval-svc -am test -Dtest=ExternalHttpMetricSourceHandlerTest`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add rule-eval-svc/src
git commit -m "feat(eval): ExternalHttpMetricSourceHandler(命名端点/path渲染/jsonPath取值)"
```

**Phase 3 完成标志：** EXTERNAL_HTTP metric 可端到端取数；只打白名单端点、凭证在端点配置、非 200/超时降级。

---

# Phase 4 — 发布期校验

> 接 B19 的 PublishService 校验链：SQL 安全扫描（拒 DB 时间函数 / ${} 拼接）+ 资源名注册校验（datasource/endpoint 必须已注册）。资源名目录由 eval-svc 实现、config-svc 运行期可选注入（Modulith 边界：config 定义 SPI，eval 提供实现）。

### Task 12: `MetricResourceCatalog` SPI + `RegistryMetricResourceCatalog`

**Files:**
- Create: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/spi/MetricResourceCatalog.java`
- Create: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/metric/RegistryMetricResourceCatalog.java`
- Test: `rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/metric/RegistryMetricResourceCatalogTest.java`

- [ ] **Step 1: 写 config-svc SPI（确认 `api/spi` 包存在或新建）**

```java
package com.sstlfsj.rule.config.api.spi;

import java.util.Set;

/**
 * 已注册取数资源名目录：供发布期校验 metric 引用的 datasource/endpoint 是否注册。
 * 由 rule-eval-svc（持有实际 registry）实现并以 Bean 暴露；config-svc 运行期可选注入。
 */
public interface MetricResourceCatalog {

    /** @return 已注册的命名数据源名集合。 */
    Set<String> datasourceNames();

    /** @return 已注册的命名 HTTP 端点名集合。 */
    Set<String> endpointNames();
}
```

> 确认 config-svc 已有 `com.sstlfsj.rule.config.api` 暴露包（Modulith named interface）。若 `api/spi` 子包不存在，新建即可——`api` 是模块对外开放包。

- [ ] **Step 2: 写 eval-svc 实现**

```java
package com.sstlfsj.rule.eval.internal.metric;

import com.sstlfsj.rule.config.api.spi.MetricResourceCatalog;
import com.sstlfsj.rule.eval.internal.metric.http.HttpEndpointRegistry;
import com.sstlfsj.rule.eval.internal.metric.sql.MetricDataSourceRegistry;
import org.springframework.stereotype.Component;

import java.util.Set;

/** 基于运行时 registry 暴露已注册资源名，供发布期校验。 */
@Component
public class RegistryMetricResourceCatalog implements MetricResourceCatalog {

    private final MetricDataSourceRegistry dataSourceRegistry;
    private final HttpEndpointRegistry endpointRegistry;

    public RegistryMetricResourceCatalog(MetricDataSourceRegistry dataSourceRegistry,
                                         HttpEndpointRegistry endpointRegistry) {
        this.dataSourceRegistry = dataSourceRegistry;
        this.endpointRegistry = endpointRegistry;
    }

    @Override
    public Set<String> datasourceNames() {
        return dataSourceRegistry.names();
    }

    @Override
    public Set<String> endpointNames() {
        return endpointRegistry.names();
    }
}
```

- [ ] **Step 3: 写测试**

```java
package com.sstlfsj.rule.eval.internal.metric;

import com.sstlfsj.rule.eval.internal.metric.http.HttpEndpointRegistry;
import com.sstlfsj.rule.eval.internal.metric.sql.FetchResourceProperties;
import com.sstlfsj.rule.eval.internal.metric.sql.MetricDataSourceRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RegistryMetricResourceCatalogTest {

    @Test
    void exposesRegisteredNames() {
        FetchResourceProperties props = new FetchResourceProperties();
        try (MetricDataSourceRegistry ds = new MetricDataSourceRegistry(props)) {
            HttpEndpointRegistry ep = new HttpEndpointRegistry(props);
            RegistryMetricResourceCatalog cat = new RegistryMetricResourceCatalog(ds, ep);
            assertThat(cat.datasourceNames()).isEmpty();
            assertThat(cat.endpointNames()).isEmpty();
        }
    }
}
```

- [ ] **Step 4: 跑测试 + 提交**

Run: `$MVN -pl rule-eval-svc -am test -Dtest=RegistryMetricResourceCatalogTest` → PASS。
```bash
git add rule-config-svc/src rule-eval-svc/src
git commit -m "feat: MetricResourceCatalog SPI + eval-svc registry 实现(发布期资源名来源)"
```

---

### Task 13: `MetricSafetyValidator` + 接入 `PublishService`

**Files:**
- Create: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/publish/MetricSafetyValidator.java`
- Modify: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/publish/PublishService.java`
- Test: `rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/publish/MetricSafetyValidatorTest.java`

- [ ] **Step 1: 写 validator 测试**

```java
package com.sstlfsj.rule.config.internal.publish;

import com.sstlfsj.rule.config.internal.domain.MetricDefinition;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

class MetricSafetyValidatorTest {

    private final MetricSafetyValidator validator = new MetricSafetyValidator(new ObjectMapper());

    private MetricDefinition sqlMetric(String code, String paramsJson) {
        MetricDefinition m = new MetricDefinition();
        m.setMetricCode(code);
        m.setSourceType("SQL_AGGREGATE");
        m.setParams(paramsJson);
        return m;
    }

    @Test
    void rejectsDbTimeFunction() {
        MetricDefinition m = sqlMetric("balance",
                "{\"datasource\":\"ro\",\"sql\":\"SELECT 1 WHERE t >= NOW() - INTERVAL 7 DAY\"}");
        assertThatThrownBy(() -> validator.validate(List.of(m), Set.of("ro"), Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NOW");
    }

    @Test
    void rejectsDollarBraceInterpolation() {
        MetricDefinition m = sqlMetric("balance",
                "{\"datasource\":\"ro\",\"sql\":\"SELECT ${col} FROM t\"}");
        assertThatThrownBy(() -> validator.validate(List.of(m), Set.of("ro"), Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnregisteredDatasource() {
        MetricDefinition m = sqlMetric("balance",
                "{\"datasource\":\"unknown\",\"sql\":\"SELECT 1\"}");
        assertThatThrownBy(() -> validator.validate(List.of(m), Set.of("ro"), Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown");
    }

    @Test
    void passesCleanSql() {
        MetricDefinition m = sqlMetric("balance",
                "{\"datasource\":\"ro\",\"sql\":\"SELECT COUNT(*) FROM t WHERE created_at >= :now - INTERVAL 7 DAY\"}");
        assertThatCode(() -> validator.validate(List.of(m), Set.of("ro"), Set.of()))
                .doesNotThrowAnyException();
    }

    @Test
    void nullCatalogNames_skipsResourceCheck_butStillScansSql() {
        MetricDefinition clean = sqlMetric("balance", "{\"datasource\":\"any\",\"sql\":\"SELECT 1\"}");
        // datasource 名集合为 null → 跳过资源名校验（容错），SQL 仍扫描
        assertThatCode(() -> validator.validate(List.of(clean), null, null))
                .doesNotThrowAnyException();
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `$MVN -pl rule-config-svc -am test -Dtest=MetricSafetyValidatorTest`
Expected: 编译失败——`MetricSafetyValidator` 不存在。

- [ ] **Step 3: 写 `MetricSafetyValidator`**

```java
package com.sstlfsj.rule.config.internal.publish;

import com.sstlfsj.rule.config.internal.domain.MetricDefinition;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 发布期 metric 安全校验：
 * <ul>
 *   <li>SQL_AGGREGATE：拒绝 DB 时间函数（NOW/SYSDATE/CURRENT_TIMESTAMP）与 ${} 拼接；datasource 必须已注册。</li>
 *   <li>EXTERNAL_HTTP：endpoint 必须已注册。</li>
 * </ul>
 * 资源名集合为 null 时跳过资源名校验（容错，如纯 config 部署无 eval registry）；SQL 文本扫描始终执行。
 */
class MetricSafetyValidator {

    // 大小写不敏感匹配 DB 时间函数调用（NOW()/SYSDATE()/CURRENT_TIMESTAMP）。
    private static final Pattern DB_TIME = Pattern.compile(
            "(?i)\\b(NOW|SYSDATE)\\s*\\(|(?i)\\bCURRENT_TIMESTAMP\\b");
    private static final Pattern DOLLAR_BRACE = Pattern.compile("\\$\\{");

    private final ObjectMapper objectMapper;

    MetricSafetyValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 校验一批 metric 定义。
     *
     * @param metrics         规则引用的 metric 定义
     * @param datasourceNames 已注册数据源名（null = 跳过资源名校验）
     * @param endpointNames   已注册端点名（null = 跳过资源名校验）
     * @throws IllegalArgumentException 校验失败
     */
    void validate(List<MetricDefinition> metrics, Set<String> datasourceNames, Set<String> endpointNames) {
        for (MetricDefinition m : metrics) {
            Map<String, Object> params = parse(m.getParams());
            switch (m.getSourceType() == null ? "" : m.getSourceType()) {
                case "SQL_AGGREGATE" -> validateSql(m, params, datasourceNames);
                case "EXTERNAL_HTTP" -> validateHttp(m, params, endpointNames);
                default -> { /* ATTRIBUTE/STREAM：无需 SQL/资源校验 */ }
            }
        }
    }

    private void validateSql(MetricDefinition m, Map<String, Object> params, Set<String> datasourceNames) {
        Object sql = params.get("sql");
        if (sql != null) {
            String text = sql.toString();
            if (DB_TIME.matcher(text).find()) {
                throw new IllegalArgumentException(
                        "metric=" + m.getMetricCode() + " 的 SQL 含 DB 时间函数（NOW/SYSDATE/CURRENT_TIMESTAMP），请用 :now");
            }
            if (DOLLAR_BRACE.matcher(text).find()) {
                throw new IllegalArgumentException(
                        "metric=" + m.getMetricCode() + " 的 SQL 含 ${} 拼接，禁止");
            }
        }
        if (datasourceNames != null) {
            Object ds = params.get("datasource");
            if (ds == null || !datasourceNames.contains(ds.toString())) {
                throw new IllegalArgumentException(
                        "metric=" + m.getMetricCode() + " 引用未注册的 datasource: " + ds);
            }
        }
    }

    private void validateHttp(MetricDefinition m, Map<String, Object> params, Set<String> endpointNames) {
        if (endpointNames != null) {
            Object ep = params.get("endpoint");
            if (ep == null || !endpointNames.contains(ep.toString())) {
                throw new IllegalArgumentException(
                        "metric=" + m.getMetricCode() + " 引用未注册的 endpoint: " + ep);
            }
        }
    }

    private Map<String, Object> parse(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }
}
```

- [ ] **Step 4: 接入 `PublishService`**

构造器注入可选 catalog：在字段区加
```java
    private final com.sstlfsj.rule.config.api.spi.MetricResourceCatalog metricResourceCatalog;
```
构造器加参数 `@org.springframework.beans.factory.annotation.Autowired(required = false) com.sstlfsj.rule.config.api.spi.MetricResourceCatalog metricResourceCatalog` 并 `this.metricResourceCatalog = metricResourceCatalog;`。

在 `publish(...)` 的 step 4.5（`metricDefs` 已加载、`resolvedAst` 计算之后）插入校验：
```java
        // 4.6. metric 安全校验（B21）：SQL 安全 + 资源名注册
        if (!metricDeps.isEmpty()) {
            java.util.List<MetricDefinition> metricDefsForSafety = metricDefinitionMapper.selectList(
                    new LambdaQueryWrapper<MetricDefinition>()
                            .eq(MetricDefinition::getTenantId, tenantId)
                            .in(MetricDefinition::getMetricCode, metricDeps));
            java.util.Set<String> dsNames = metricResourceCatalog != null
                    ? metricResourceCatalog.datasourceNames() : null;
            java.util.Set<String> epNames = metricResourceCatalog != null
                    ? metricResourceCatalog.endpointNames() : null;
            new MetricSafetyValidator(objectMapper).validate(metricDefsForSafety, dsNames, epNames);
        }
```
> 复用 step 4.5 已查的 `metricDefs` 即可，避免二次查询——若 step 4.5 的 `metricDefs` 变量在作用域内，直接传它，删掉上面的重复查询。核对 `PublishService.publish` 中 `metricDefs` 的作用域（它在 `if (!metricDeps.isEmpty())` 块内声明，需提升到方法级或在同块内调用 validator）。**实现要点：把 4.5 与 4.6 合并进同一个 `if (!metricDeps.isEmpty())` 块，共用 `metricDefs`。**

- [ ] **Step 5: 写 PublishService 集成回归（确认含 NOW() 的 SQL metric 发布被拒）**

在现有 `PublishServiceTest`（或新 IT）加一个用例：mock `metricDefinitionMapper.selectList` 返回一个 `SQL_AGGREGATE` + params 含 `NOW()` 的 metric，断言 `publish(...)` 抛 `IllegalArgumentException`。参考 `PublishServiceTest` 既有 mock 风格。

- [ ] **Step 6: 跑 config-svc 全量**

Run: `$MVN -pl rule-config-svc -am test`
Expected: PASS。

- [ ] **Step 7: 提交**

```bash
git add rule-config-svc/src
git commit -m "feat(config): 发布期 metric 安全校验(SQL 时间函数/拼接拒绝 + 资源名注册)"
```

**Phase 4 完成标志：** 含 `NOW()`/`${}` 的 SQL metric 发布被拒；引用未注册 datasource/endpoint 的 metric 发布被拒（catalog 在场时）。

---

# Phase 5 — 文档同步（spec §12）

### Task 14: 更新设计文档

> 跨文档改动——**改前先跑 `doc-consistency-review` skill** 扫自洽性（CLAUDE.md 约定）。

**Files（均 Modify）:**
- `docs/02-runtime.md §3.4`：EvalContext 构建五步标注"已实装"（provided 优先 → 缓存 → 并发 fetch → 降级）。
- `docs/01-concepts.md §3.9`：SQL_AGGREGATE / EXTERNAL_HTTP 范式实装说明。
- `docs/03-rule-expression.md §7.3`：示例 SQL 由 `NOW()` 改 `:now`，加 B21 注入说明。
- `docs/04-extension.md`：MetricSourceHandler 接线（@MetricSourceType 路由）、命名 DataSource/端点注册约定、`MetricDefinitionResolver`/`MetricCache` SPI、缓存 key 规范。
- `docs/00-decisions.md`：追加 B21 决策条目（命名句柄 / :now / 降级 / provided 优先 / Resolver SPI 落地）——**追加，不改历史条目**。**并追加 B2 前向兼容决策四条**（见 `specs/2026-06-06-sdk-fetch-design.md`「前向兼容约束」）：① 定义独立可下发不冻进快照；② Resolver 数据源无关；③ assembler 富构造为统一取数入口；④ MetricDescriptor 为下发序列化契约。
- `docs/05-storage.md`：`metric_definition.params` 的 datasource/sql/endpoint/path/jsonPath 字段约定。

- [ ] **Step 1: 跑 doc-consistency-review**

调用 `doc-consistency-review` skill 对 `docs/` 扫一遍，记录现状基线。

- [ ] **Step 2: 逐文件更新**（按上表，每节落地实装说明，与代码一致）

- [ ] **Step 3: 再跑 doc-consistency-review 确认无新增矛盾**

- [ ] **Step 4: 调用 `rule-engine-reviewer` agent 审"代码 ↔ 文档对齐"**（CLAUDE.md 要求，改 docs/src 后显式调用）

- [ ] **Step 5: 提交**

```bash
git add docs
git commit -m "docs: B21 取数层实装同步(runtime/concepts/expression/extension/decisions/storage)"
```

---

# 自检清单（Self-Review，写计划者已过一遍）

**Spec 覆盖核对（spec §→Task）：**
- §1 取数管线接线 → Task 5（assemble 重写）+ Task 3（metricDependencies 来源）
- §2 MetricQuery.now → Task 2
- §3 SQL_AGGREGATE（命名参数/:now/强转/只读源）→ Task 8、Task 9
- §4 EXTERNAL_HTTP（命名端点/path/jsonPath/鉴权）→ Task 10、Task 11
- §5 缓存（key/ttl=0/Caffeine）→ Task 4（MetricCache SPI）+ Task 5（key 逻辑）+ Task 7（Caffeine 实现）
- §6 失败与超时 → Task 5（fetchConcurrently 降级）+ Task 6（节点错码）
- §7 provided 优先（D30）→ Task 5
- §8 发布期校验 → Task 13
- §9 与 B19/B20/B10 关系 → Task 2（now 同源）/ Task 4（纯算法不收 ctx）/ §文档
- §10 不做（STREAM 抛异常）→ STREAM 无 handler 注册 → Task 5 自动降级 METRIC_FETCH_FAIL（等价于 spec 的"占位抛异常→METRIC_FETCH_FAIL"）
- §11 实现期待定 → 计划内以 ⚠️ 标注（集成基类名、Caffeine 版本、占位符库选择、Jackson3 API、H2 依赖）
- §12 影响文档 → Task 14
- §13 测试要点 → 分散在各 Task 的测试步骤（接线/并发/降级/:now 重放/SQL 安全/数据源/HTTP/缓存）

**类型一致性核对：**
- `MetricValue.error(String)` / `isError()` / `errorCode()` — 全计划一致。
- `MetricDescriptor(metricCode, sourceType, dataType, allowProvided:boolean, cacheTtlSeconds:int, params:Map)` — Task 4 定义，Task 5/7/9/11 使用一致。
- `MetricDefinitionResolver.resolve(String tenantId, String metricCode)` — Task 4 定义，Task 5/7 一致。
- `MetricCache.get(String)` / `put(String,MetricValue,int)` — Task 4 定义，Task 5/7 一致。
- `EvalContextAssembler` 富构造 `(List<SubjectLoader>, Map<String,MetricSourceHandler>, MetricDefinitionResolver, MetricCache, Executor, long)` — Task 5 定义，Task 7 装配一致。
- `RuleVersionSnapshot.metricDependencies()` — Task 3 加，Task 5 用。
- `MetricQuery(..., Instant now)` — Task 2 加，Task 5/9/11 用。
- handler dataType 来源：resolver 把 `dataType` 塞进 `descriptor.params()`（Task 9 Step 6 约定），Task 9/11 从 `query.params().get("dataType")` 读 — 一致。

**已知边界（非占位符，明确不做）：**
- ERROR 降级走统一门面三态（Task 6）：布尔路径标 per-node 错码、整树继续；评分卡整卡 ERROR（风控保守）；决策树/表整规则 ERROR + miss（不静默走错分支）。决策树/表不产 per-node trace（原本即不产），ERROR 仅落 `EvalResult.errorCode` → 会话级。FIRST_HIT 路径的 errorCode 透传是已知小边界。
- STREAM v1 无 handler → 自动降级（对齐 spec §10）。
- jsonPath 仅支持点号路径（无数组下标/通配），对齐 v1 范围。
- 缓存 key 的 params 仅做一层 TreeMap 排序（嵌套 map 顺序不保证），v1 可接受。

---

# 执行交接

计划已存到 `docs/superpowers/plans/2026-06-06-fetch-layer.md`，14 个 Task 分 5 个 Phase。两种执行方式：

1. **Subagent-Driven（推荐）** — 每个 Task 派新 subagent 执行，Task 间双段 review，迭代快。
2. **Inline Execution** — 本会话内分批执行，带 checkpoint review。

> 依赖顺序：Phase 1（Task 1→7）必须先全部完成（建立契约）；Phase 2/3 可并行（都只依赖 Phase 1）；Phase 4 依赖 Phase 2/3 的 registry；Phase 5 最后。
