# B6 Metric 版本化 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 metric 引入版本语义，使 metric 语义不兼容变更时，存量已发布规则继续按其发布时绑定的旧版本求值，杜绝"静默错误结果"，并为 B7 导出 Bundle 锁定 `metric_dependencies` JSON schema。

**Architecture（档 1）：** `metric_definition` 加 `version` 列（升语义=旧 ACTIVE 行转 SUPERSEDED + INSERT 新版本行）。`rule_version.metric_dependencies` 从 `["code"]` 升级为 `[{"metricCode":"code","metricVersion":1}]`。发布时自动冻结每个被引用 metric 的当前 ACTIVE 版本号进快照。评估期按规则快照绑定的版本 `resolve(code, version)` 解析 metric **定义**（取旧版的 sourceType/params/dataType 去取数）。`EvalContext` 仍按 code 索引单值、所有候选共享，`EvalEngine` 与 17 个 `ConditionEvaluator` **完全不改**；过渡期同一 code 多版本并存时按"最高版本"确定性取数一次（不做 per-rule 运行时投影 = 不做方案 D，业界无对应、对本项目过度）。

**Tech Stack:** Java 25 / Spring Boot 4 / Spring Modulith / MyBatis-Plus / Flyway / Jackson (`tools.jackson`) / Caffeine / JUnit5 + AssertJ / Testcontainers(MySQL)。模块：`rule-kernel`（纯 Java 评估核心）/ `rule-config-svc`（发布、metric 写）/ `rule-eval-svc`（服务端取数）/ `rule-sdk`（嵌入式）/ `rule-api`（HTTP 端点）。

**决策基线（已与用户锁定）：**
- **版本绑定**：发布时自动冻结当前 ACTIVE 版本号（运营无感，符合 D6 不可变快照；metric 升版后存量规则仍跑旧版语义，需重新发布才升级）。
- **升版触发**：metric 更新 API 带 `breakingChange` 标志——true 才 INSERT 新版本行（旧行 SUPERSEDED），false 原地 UPDATE 当前 ACTIVE 行。
- **评估隔离（档 1）**：评估期按规则绑定版本解析定义；过渡期同 code 多版本取最高版本取数一次共享（不做 per-rule 运行时投影）。稳态完全正确，过渡窗口接受弱隔离 + trace 标注。砍掉原方案 D 的 `EvalEngine`/`EvalContext` 改造（业界无对应、对单人 greenfield 过度）。
- **本批次范围**：后端影响面查询 API + schema/发布/评估解析。前端 UI 渲染不在本批次。

**前置事实（已核对源码，2026-06-06）：**
- 代码层零 `metricVersion` 字段；`01-concepts.md §3.9` 明示"v1 DDL 无此列"，纯概念占位。
- metric 当前**无写 API**，仅测试 `mapper.insert`；`10-api-contract.md §3` 的 `/api/v1/metrics` 契约从未实现 → 本批次补最小写服务+端点（升版必须有写入口才能验证）。
- 全部 17 个 `ConditionEvaluator` 经 `ctx.getMetric(node.metricCode())` 按纯 code 取值，节点不带 version。
- 开发阶段无生产数据（memory: Greenfield）→ `metric_dependencies` 直接换格式，不写向后兼容；examples/测试 fixture 同步迁移。
- **B23 契约例外（有意打破）**：`MetricDefinitionResolver.resolve` 由两参改三参（加 `version`），有意打破 `specs/2026-06-06-sdk-fetch-design.md` 的"换实现不换契约"承诺。理由：B6 版本化语义必须透传 version；B23 那条承诺针对 B21 既有方法签名，B6 是其后的新需求，合理覆盖。所有实现（`DbMetricDefinitionResolver` / `SnapshotMetricDefinitionResolver`）同步升签名，上层取数编排仍数据源无关。
- **执行期修正：MetricDescriptor 6 参兼容构造（对齐既有模式）**：`MetricDescriptor` 加 `metricVersion` 字段会破坏 eval-svc/config/sdk 全部 6 参 `new MetricDescriptor(...)` 调用点。解法用 codebase 既有模式（参照 `RuleVersionSnapshot` 的兼容构造）：给 `MetricDescriptor` 加 6 参兼容构造（`metricVersion` 默认 1），现有调用点零改动即可编译、语义=v1 占位。**T4 必须把 `DbMetricDefinitionResolver` 改 7 参传 `row.version()`；T9 必须把 `MetadataServiceImpl.toDescriptor` 改 7 参传 `m.getVersion()`**——否则这两处 version 永远停在占位 1（bug）。该兼容构造在 T2 Step 0 引入。

**环境：** 跑 Maven 前用 `mvn-env` skill 设置 `$MVN`（本机 mvn 不在 PATH）。每个 Task 提交前跑该模块 `$MVN -pl <module> -am test` 全绿才 commit。改 `docs/**` 前的自洽性由本计划末尾统一交代，不在每个 Task 重复。

---

## 文件结构总览

| 文件 | 责任 | 动作 |
|------|------|------|
| `rule-kernel/.../api/model/MetricDependency.java` | (code, version) 不可变对，发布期产出 + 评估期消费 + 快照携带 | Create |
| `rule-kernel/.../api/model/MetricDescriptor.java` | metric 运行时定义快照，加 `metricVersion` | Modify |
| `rule-kernel/.../api/model/RuleVersionSnapshot.java` | `metricDependencies` 类型迁移 + Builder | Modify |
| `rule-kernel/.../api/spi/metric/MetricDefinitionResolver.java` | SPI `resolve` 加 version 参 | Modify |
| `rule-kernel/.../internal/codec/AstJsonCodec.java` | 加 `deserializeMetricDependencies` | Modify |
| `rule-kernel/.../internal/codec/SnapshotAssembler.java` | 用新反序列化方法 | Modify |
| `rule-kernel/.../internal/context/EvalContextAssembler.java` | 按 code 取最高版本，`resolve(code,version)` 解析定义 | Modify |
| `rule-kernel/.../internal/engine/EvalEngine.java` | **不改**（档 1） | — |
| `rule-config-svc/.../resources/db/migration/V1_6__metric_versioning.sql` | metric_definition 加 version + SUPERSEDED + UK | Create |
| `rule-eval-svc/src/test/resources/db/migration/V1_6__metric_versioning.sql` | 同上（测试库副本） | Create |
| `rule-config-svc/.../internal/domain/MetricDefinition.java` | 实体加 `version` | Modify |
| `rule-config-svc/.../internal/publish/PublishService.java` | 冻结当前 ACTIVE 版本号进 metric_dependencies | Modify |
| `rule-config-svc/.../internal/publish/MetricDependencyCollector.java` | （保持收集 code，不变） | — |
| `rule-config-svc/.../api/service/MetricWriteService.java` | metric 写 + 升版 + 影响面 SPI | Create |
| `rule-config-svc/.../internal/service/MetricWriteServiceImpl.java` | 实现 | Create |
| `rule-config-svc/.../internal/service/MetadataServiceImpl.java` | `metric_dependencies` 解析改对象数组取 code | Modify |
| `rule-eval-svc/.../internal/metric/DbMetricDefinitionResolver.java` | 缓存键带 version + 按版本查 | Modify |
| `rule-eval-svc/.../internal/repository/MetricDefinitionReadMapper.java` | `findByVersion` | Modify |
| `rule-eval-svc/.../internal/domain/MetricDefinitionRow.java` | 加 version | Modify |
| `rule-sdk/.../metric/SnapshotMetricDefinitionResolver.java` | SPI 跟随 | Modify |
| `rule-sdk/.../metric/MetricDefinitionRegistry.java` | 按 (tenant,code,version) 索引 | Modify |
| `rule-api/.../web/config/MetricController.java` | POST/PUT/impact 端点 | Create |
| `docs/examples/**/rules/*.json`（6 个） | metricDependencies 升对象数组 | Modify |
| `docs/{01-concepts,08-evolution,10-api-contract}.md` | 文档同步 | Modify |

---

## Task 1: kernel 基础类型 —— MetricDependency + MetricDescriptor.metricVersion

**Files:**
- Create: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/MetricDependency.java`
- Modify: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/MetricDescriptor.java`
- Test: `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/api/model/MetricDependencyTest.java`

- [ ] **Step 1: 写失败测试**

`rule-kernel/src/test/java/com/sstlfsj/rule/kernel/api/model/MetricDependencyTest.java`：
```java
package com.sstlfsj.rule.kernel.api.model;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class MetricDependencyTest {

    @Test
    void holdsCodeAndVersion() {
        MetricDependency d = new MetricDependency("user.account.age.days", 2);
        assertThat(d.metricCode()).isEqualTo("user.account.age.days");
        assertThat(d.metricVersion()).isEqualTo(2);
    }

    @Test
    void equalityByValue() {
        assertThat(new MetricDependency("a", 1)).isEqualTo(new MetricDependency("a", 1));
        assertThat(new MetricDependency("a", 1)).isNotEqualTo(new MetricDependency("a", 2));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `$MVN -pl rule-kernel test -Dtest=MetricDependencyTest`
Expected: 编译失败（`MetricDependency` 不存在）。

- [ ] **Step 3: 写实现**

`MetricDependency.java`：
```java
package com.sstlfsj.rule.kernel.api.model;

/**
 * 规则对某 metric 的版本化依赖：发布期冻结的 (metricCode, metricVersion) 对。
 * 评估期据此为每条规则投影"版本特化"的指标视图；JSON 可序列化，作为
 * rule_version.metric_dependencies 数组元素的契约（B7 导出 Bundle 依赖此 schema）。
 */
public record MetricDependency(String metricCode, int metricVersion) {}
```

`MetricDescriptor.java`：record 头加 `int metricVersion`（放在 `dataType` 后，保持与 DB 列序一致）：
```java
public record MetricDescriptor(
        String metricCode,
        int metricVersion,
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

- [ ] **Step 4: 跑测试确认通过 + 全模块编译**

Run: `$MVN -pl rule-kernel -am test`
Expected: `MetricDependencyTest` 通过。**注意**：`MetricDescriptor` 加字段会破坏现有构造调用点（`DbMetricDefinitionResolver`、`MetadataServiceImpl.toDescriptor`、kernel 测试、SDK registry）。本 Task 仅修 **kernel 内**调用点（测试）让 kernel 编译通过；跨模块调用点在各自 Task（4/9）修。若 kernel 测试有 `new MetricDescriptor(...)`，按新签名补 `metricVersion`（本地构造传 `1`）。

- [ ] **Step 5: commit**

```bash
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/MetricDependency.java \
        rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/MetricDescriptor.java \
        rule-kernel/src/test/java/com/sstlfsj/rule/kernel/api/model/MetricDependencyTest.java
git commit -m "feat(kernel): 新增 MetricDependency 类型 + MetricDescriptor 加 metricVersion 字段(B6)"
```

---

## Task 2: DDL —— metric_definition 加 version + SUPERSEDED + UK + 实体

**Files:**
- Create: `rule-config-svc/src/main/resources/db/migration/V1_6__metric_versioning.sql`
- Create: `rule-eval-svc/src/test/resources/db/migration/V1_6__metric_versioning.sql`
- Modify: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/domain/MetricDefinition.java`

- [ ] **Step 1: 写两份 migration（内容相同）**

两个文件内容均为：
```sql
-- B6 Metric 版本化：metric_definition 加 version 列 + SUPERSEDED 状态 + UK 改为含 version
-- 升语义=旧 ACTIVE 行 status->SUPERSEDED + INSERT 新行 version+1 status=ACTIVE（应用层同事务保证至多一行 ACTIVE）
ALTER TABLE metric_definition
  ADD COLUMN version INT NOT NULL DEFAULT 1 COMMENT '指标定义版本号，per (tenant_id, metric_code) 单调递增' AFTER metric_code;

ALTER TABLE metric_definition
  MODIFY COLUMN status ENUM('ACTIVE','SUPERSEDED','DISABLED') NOT NULL DEFAULT 'ACTIVE';

ALTER TABLE metric_definition
  DROP INDEX uk_tenant_code,
  ADD UNIQUE KEY uk_tenant_code_version (tenant_id, metric_code, version);
```

- [ ] **Step 2: 实体加 version**

`MetricDefinition.java` 在 `metricCode` 字段后加：
```java
    private Integer version;
```

- [ ] **Step 3: 跑 config 集成测试确认 Flyway 通过**

Run: `$MVN -pl rule-config-svc -am test -Dtest=MetadataServiceIntegrationTest`
Expected: Testcontainers 起 MySQL，Flyway 跑到 V1_6 不报错。**此时该测试 seed 的 metric 未设 version → 走 DEFAULT 1**，断言不受影响，应仍通过。若 `MetricDefinition` 加字段导致别处编译错，本 Task 不引入新构造调用点，应无影响。

- [ ] **Step 4: commit**

```bash
git add rule-config-svc/src/main/resources/db/migration/V1_6__metric_versioning.sql \
        rule-eval-svc/src/test/resources/db/migration/V1_6__metric_versioning.sql \
        rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/domain/MetricDefinition.java
git commit -m "feat(config): metric_definition 加 version 列 + SUPERSEDED 状态 + UK 含 version(B6)"
```

---

## Task 3: 快照契约迁移 —— metric_dependencies 升对象数组

把 `RuleVersionSnapshot.metricDependencies` 从 `List<String>` 改为 `List<MetricDependency>`，并打通序列化/反序列化与所有 kernel/SDK 调用点。

**Files:**
- Modify: `rule-kernel/.../api/model/RuleVersionSnapshot.java`
- Modify: `rule-kernel/.../internal/codec/AstJsonCodec.java`
- Modify: `rule-kernel/.../internal/codec/SnapshotAssembler.java`
- Test: `rule-kernel/.../internal/codec/SnapshotAssemblerTest.java`（已存在，改断言）

- [ ] **Step 1: 改 RuleVersionSnapshot**

record 字段类型与规范化：
```java
        /** AST 引用的 (metricCode, metricVersion) 依赖（发布期冻结），供取数管线确定取数范围与版本。 */
        List<MetricDependency> metricDependencies
```
构造体规范化行改为：
```java
        metricDependencies = metricDependencies == null ? List.of() : List.copyOf(metricDependencies);
```
Builder 字段与方法：
```java
        private final List<MetricDependency> metricDependencies = new ArrayList<>();
        ...
        /** 追加一个 metric 版本化依赖。 */
        public Builder addMetricDependency(String metricCode, int metricVersion) {
            metricDependencies.add(new MetricDependency(metricCode, metricVersion)); return this;
        }
```
顶部 `import com.sstlfsj.rule.kernel.api.model.MetricDependency;`（同包则无需）—— `MetricDependency` 与 `RuleVersionSnapshot` 同包 `api.model`，无需 import。

- [ ] **Step 2: AstJsonCodec 加方法**

在 `deserializeStringList` 后加：
```java
    /**
     * 将 JSON 字符串反序列化为 MetricDependency 列表（rule_version.metric_dependencies）。
     *
     * @param json metric 依赖 JSON 数组字符串，元素形如 {"metricCode":"x","metricVersion":1}
     * @return MetricDependency 列表
     */
    public List<com.sstlfsj.rule.kernel.api.model.MetricDependency> deserializeMetricDependencies(String json)
            throws JacksonException {
        return mapper.readValue(json, new TypeReference<>() {});
    }
```

- [ ] **Step 3: SnapshotAssembler 改用新方法**

`SnapshotAssembler.assemble` 中：
```java
        List<com.sstlfsj.rule.kernel.api.model.MetricDependency> metricDependencies =
                codec.deserializeMetricDependencies(
                        row.metricDependenciesJson() == null ? "[]" : row.metricDependenciesJson());
```
（`row.metricDependenciesJson()` 与 `RuleVersionRow` 不变。）

- [ ] **Step 4: 改测试断言并跑**

`SnapshotAssemblerTest` 中凡构造 `RuleVersionRow` 的 `metricDependenciesJson` 用 `[{"metricCode":"m1","metricVersion":1}]`，断言 `snapshot.metricDependencies()` 含 `new MetricDependency("m1",1)`。
`RuleVersionSnapshotTest`（若有断言 `List<String>`）同步改为 `List<MetricDependency>`。

Run: `$MVN -pl rule-kernel -am test`
Expected: 此处会暴露 kernel 内其余调用点（`EvalContextAssembler.collectMetricCodes`、`RuleVersionSnapshot.Builder` 调用方测试）。`EvalContextAssembler` 的改造在 Task 5；**本 Task 先让 collect 编译通过的最小桥接**：把 `collectMetricCodes` 临时改为取 code（`snap.metricDependencies().stream().map(MetricDependency::metricCode)`），保证 kernel 编译与现有 `EvalContextAssemblerTest` 绿。Task 5 再替换为"按 code 取最高版本 + `resolve(code,version)`"逻辑。

- [ ] **Step 5: 修 SDK 侧 Builder 调用点**

`rule-sdk` 内 `AnnotationRuleSource` / 任何 `.addMetricDependency(code)` 调用改为 `.addMetricDependency(code, 1)`（本地 DSL/注解模式默认版本 1）。`SnapshotPoller` 用 `convertValue(... List<RuleVersionSnapshot>)` 自动跟随新字段类型，无需改。

Run: `$MVN -pl rule-sdk -am test`
Expected: 通过（或仅需补 `,1`）。

- [ ] **Step 6: commit**

```bash
git add rule-kernel/.../RuleVersionSnapshot.java rule-kernel/.../AstJsonCodec.java \
        rule-kernel/.../SnapshotAssembler.java rule-kernel/.../EvalContextAssembler.java \
        rule-kernel/src/test/... rule-sdk/src/main/...
git commit -m "feat(kernel): rule_version.metric_dependencies 升级为 (code,version) 对象数组(B6)"
```

---

## Task 4: 评估期 SPI 按版本解析定义

`MetricDefinitionResolver.resolve` 加 `metricVersion` 参；服务端按精确版本查库（含 SUPERSEDED 旧版）；SDK 按 (tenant,code,version) 索引。

**Files:**
- Modify: `rule-kernel/.../api/spi/metric/MetricDefinitionResolver.java`
- Modify: `rule-eval-svc/.../internal/repository/MetricDefinitionReadMapper.java`
- Modify: `rule-eval-svc/.../internal/domain/MetricDefinitionRow.java`
- Modify: `rule-eval-svc/.../internal/metric/DbMetricDefinitionResolver.java`
- Modify: `rule-sdk/.../metric/SnapshotMetricDefinitionResolver.java`
- Modify: `rule-sdk/.../metric/MetricDefinitionRegistry.java`
- Test: `rule-eval-svc/.../MetricDefinitionRowTest.java`、新增 resolver 单测

- [ ] **Step 1: 改 SPI 签名**

`MetricDefinitionResolver.java`：
```java
    /**
     * 解析指定租户下某 metric 指定版本的运行时定义。
     *
     * @param tenantId      租户 id
     * @param metricCode    指标编码
     * @param metricVersion 规则快照绑定的版本号
     * @return 定义快照；不存在时返回 null
     */
    MetricDescriptor resolve(String tenantId, String metricCode, int metricVersion);
```

- [ ] **Step 2: eval-svc Row + Mapper 按版本查**

`MetricDefinitionRow.java` 加 `int version`：
```java
public record MetricDefinitionRow(
        String metricCode,
        int version,
        String sourceType,
        String dataType,
        Boolean allowProvided,
        Integer cacheTtlSeconds,
        String paramsJson
) {}
```
`MetricDefinitionReadMapper.java` 用 `findByVersion` 替换 `findActive`（精确版本，不限 status，使 SUPERSEDED 旧版可解析）：
```java
    @Select("""
            SELECT metric_code       AS metricCode,
                   version           AS version,
                   source_type       AS sourceType,
                   data_type         AS dataType,
                   allow_provided    AS allowProvided,
                   cache_ttl_seconds AS cacheTtlSeconds,
                   params            AS paramsJson
            FROM metric_definition
            WHERE tenant_id = #{tenantId} AND metric_code = #{metricCode} AND version = #{version}
            """)
    MetricDefinitionRow findByVersion(@Param("tenantId") long tenantId,
                                      @Param("metricCode") String metricCode,
                                      @Param("version") int version);
```

- [ ] **Step 3: DbMetricDefinitionResolver 缓存键带 version**

```java
    @Override
    public MetricDescriptor resolve(String tenantId, String metricCode, int metricVersion) {
        String key = tenantId + ":" + metricCode + ":" + metricVersion;
        MetricDescriptor cached = cache.getIfPresent(key);
        if (cached != null) return cached;
        MetricDefinitionRow row = mapper.findByVersion(Long.parseLong(tenantId), metricCode, metricVersion);
        if (row == null) return null;
        Map<String, Object> params = new HashMap<>(parseParams(row.paramsJson()));
        params.put("dataType", row.dataType());
        MetricDescriptor d = new MetricDescriptor(
                row.metricCode(), row.version(), row.sourceType(), row.dataType(),
                Boolean.TRUE.equals(row.allowProvided()),
                row.cacheTtlSeconds() == null ? 0 : row.cacheTtlSeconds(),
                params);
        cache.put(key, d);
        return d;
    }
```

- [ ] **Step 4: SDK registry + resolver 按版本**

`MetricDefinitionRegistry`：内部 Map 键由 `tenant:code` 改为 `tenant:code:version`；`get` 方法签名加 version；写入侧（下发装载处）用 `descriptor.metricVersion()` 组键。
`SnapshotMetricDefinitionResolver.resolve`：
```java
    @Override
    public MetricDescriptor resolve(String tenantId, String metricCode, int metricVersion) {
        return registry.get(tenantId, metricCode, metricVersion);
    }
```

- [ ] **Step 5: 改测试并跑**

`MetricDefinitionRowTest` 按新 record 补 `version`。新增 `DbMetricDefinitionResolverTest`（mock mapper：`findByVersion(t,"a",2)` 返回 row → 断言 descriptor.metricVersion()==2；缓存命中不二次查）。SDK `MetricDefinitionRegistryTest` 按 (tenant,code,version) 改。

Run: `$MVN -pl rule-eval-svc -am test` 和 `$MVN -pl rule-sdk -am test`
Expected: 通过。`EvalContextAssembler` 仍调旧 `resolve(t,code)` → 编译错，**Task 5 修**；本 Task 若需让 eval-svc 编译，先在 `EvalContextAssembler` 把调用临时改 `resolve(code 的 dep.metricVersion())`——但更干净是 Task 4、5 连续执行、合并验证。建议执行者把 Task 4+5 作为一个连续工作段，最后一并跑 `-pl rule-eval-svc -am test`。

- [ ] **Step 6: commit**（与 Task 5 可合并提交）

```bash
git add rule-kernel/.../MetricDefinitionResolver.java rule-eval-svc/... rule-sdk/...
git commit -m "feat: MetricDefinitionResolver 按 (code,version) 解析定义(B6)"
```

---

## Task 5: 评估期按绑定版本解析定义（档 1）

`EvalContextAssembler` 收集候选并集 `(code, version)`，**按 code 取最高版本**确定每个 code 的解析版本，用 `resolve(code, version)` 取定义后 fetch，结果按 code 注入单一 `metrics` map。`EvalContext` / `EvalEngine` / 17 个 `ConditionEvaluator` **完全不改**。

**Files:**
- Modify: `rule-kernel/.../internal/context/EvalContextAssembler.java`
- Test: `EvalContextAssemblerTest`（现有，改 resolver lambda 签名）、新增版本解析测试

- [ ] **Step 1: 写失败测试**

`rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/context/MetricVersionResolveTest.java`：
```java
package com.sstlfsj.rule.kernel.internal.context;

import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.kernel.api.spi.metric.MetricDefinitionResolver;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** 评估期按规则绑定版本解析定义；过渡期同 code 多版本取最高版本。 */
class MetricVersionResolveTest {

    @Test
    void resolvesBoundVersion_singleVersionSteadyState() {
        AtomicInteger seenVersion = new AtomicInteger(-1);
        MetricDefinitionResolver resolver = (t, code, ver) -> {
            seenVersion.set(ver);
            return new MetricDescriptor(code, ver, "ATTRIBUTE", "LONG", true, 0, Map.of());
        };
        EvalContextAssembler asm = new EvalContextAssembler(
                List.of(), Map.of(), resolver, null, null, 0L);

        RuleEvent event = /* 按现有 EvalContextAssemblerTest 构造，providedMetrics 含 account.age=5 */ null;
        RuleVersionSnapshot rule = RuleVersionSnapshot.builder()
                .ruleVersionId(1L).tenantId("1").sceneCode("s").conditionAst(null)
                .addMetricDependency("account.age", 3).build();

        EvalContext ctx = asm.assemble(event, List.of(rule), Instant.now());
        assertThat(seenVersion.get()).isEqualTo(3);           // 用绑定版本解析定义
        assertThat(ctx.hasMetric("account.age")).isTrue();
    }

    @Test
    void multiVersionTransition_picksHighest() {
        AtomicInteger seenVersion = new AtomicInteger(-1);
        MetricDefinitionResolver resolver = (t, code, ver) -> {
            seenVersion.set(ver);
            return new MetricDescriptor(code, ver, "ATTRIBUTE", "LONG", true, 0, Map.of());
        };
        EvalContextAssembler asm = new EvalContextAssembler(
                List.of(), Map.of(), resolver, null, null, 0L);

        RuleEvent event = /* providedMetrics 含 account.age */ null;
        RuleVersionSnapshot ruleA = RuleVersionSnapshot.builder()
                .ruleVersionId(1L).tenantId("1").sceneCode("s").conditionAst(null)
                .addMetricDependency("account.age", 1).build();
        RuleVersionSnapshot ruleB = RuleVersionSnapshot.builder()
                .ruleVersionId(2L).tenantId("1").sceneCode("s").conditionAst(null)
                .addMetricDependency("account.age", 2).build();

        asm.assemble(event, List.of(ruleA, ruleB), Instant.now());
        assertThat(seenVersion.get()).isEqualTo(2);           // 过渡期取最高版本
    }
}
```
> **执行者注**：`RuleEvent` 按现有 `EvalContextAssemblerTest` 的真实构造器补全（含 providedMetrics）。本步只验证 resolver 被调用时的 version 参数，无需真实 AST 求值。

- [ ] **Step 2: 跑测试确认失败**

Run: `$MVN -pl rule-kernel test -Dtest=MetricVersionResolveTest`
Expected: 编译失败（`resolve` 旧签名仍是两参，且 `assemble` 还在用 `collectMetricCodes` 取 code）。

- [ ] **Step 3: 改 EvalContextAssembler 按版本解析**

把 Task 3 临时桥接的 `collectMetricCodes` 替换为按 code 取最高版本：
```java
    /** 候选并集中每个 metricCode 选定一个解析版本：同 code 多版本时取最高（过渡期确定性策略）。 */
    private static Map<String, Integer> collectChosenVersions(List<RuleVersionSnapshot> candidates) {
        Map<String, Integer> chosen = new LinkedHashMap<>();
        for (RuleVersionSnapshot snap : candidates) {
            for (MetricDependency dep : snap.metricDependencies()) {
                chosen.merge(dep.metricCode(), dep.metricVersion(), Math::max);
            }
        }
        return chosen;
    }
```
`assemble(...)` 主体：原 `Set<String> required = collectMetricCodes(candidates);` 改为：
```java
        Map<String, Integer> chosen = collectChosenVersions(candidates);
        Map<String, MetricDescriptor> descriptors = new HashMap<>();
        Set<String> needFetch = new LinkedHashSet<>();

        for (Map.Entry<String, Integer> entry : chosen.entrySet()) {
            String code = entry.getKey();
            int version = entry.getValue();
            MetricDescriptor def = definitionResolver.resolve(event.tenantId(), code, version);
            if (def != null) descriptors.put(code, def);
            // ...（其余 provided / cache / needFetch 判定逻辑保持现状，仅 resolve 多传 version；
            //      cache key 拼 code:version 避免跨版本串味：cacheKey(tenant, code + ":" + version, subject, params)）
        }
```
其余 `fetchConcurrently` / provided 透传 / cacheKey 逻辑保持现状（只把缓存键的 metricCode 段换成 `code + ":" + version`）。`EvalContext` 仍是单一 `Map<String, MetricValue>` 按 code 索引——**EvalEngine、EvalContext、17 个 evaluator 一行不改**。

- [ ] **Step 4: 跑测试确认通过**

Run: `$MVN -pl rule-kernel -am test`
Expected: `MetricVersionResolveTest` + 既有 `EvalContextAssemblerTest`（把其 resolver lambda 从两参 `(t,code)` 改三参 `(t,code,ver)`）全绿。

- [ ] **Step 5: 跑 eval-svc / sdk 端到端**

**先核对并迁移 seed**：`rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/integration/EvalIntegrationTest.java` 的 seed 直接 INSERT `metric_dependencies` 字段，当前为字符串数组格式（如 `'["risk.score"]'`）。格式变更后 `SnapshotAssembler.deserializeMetricDependencies` 会解析失败 → 该规则被跳过 → 评估行为变化。把该 seed 值改为对象数组 `'[{"metricCode":"risk.score","metricVersion":1}]'`。

Run: `$MVN -pl rule-eval-svc -am test` 和 `$MVN -pl rule-sdk -am test`
Expected: `EvalIntegrationTest` 等通过（单版本场景：所有 dep version=1，行为等价于旧）。

- [ ] **Step 6: commit**

```bash
git add rule-kernel/.../EvalContextAssembler.java rule-kernel/src/test/... \
        rule-eval-svc/src/test/.../EvalIntegrationTest.java
git commit -m "feat(kernel): 评估期按规则绑定版本解析 metric 定义，过渡期取最高版本(B6 档1)"
```

---

## Task 6: 发布期冻结当前 ACTIVE 版本

`PublishService.publish` 查每个被引用 metric 的当前 ACTIVE 版本，校验"恰好一个 ACTIVE"，组装 `List<MetricDependency>` 写入。

**Files:**
- Modify: `rule-config-svc/.../internal/publish/PublishService.java`
- Test: `rule-config-svc/.../internal/publish/PublishServiceTest.java`

- [ ] **Step 1: 写失败测试**

在 `PublishServiceTest` 加：metric `account.age` 当前 ACTIVE version=3 → 发布引用它的规则后，`rule_version.metric_dependencies` 反序列化含 `{"metricCode":"account.age","metricVersion":3}`；若该 code 无 ACTIVE 行 → 抛 `IllegalArgumentException`（映射 INVALID_ARGUMENT）。参考现有测试的 mock/seed 风格（`PublishServiceTest` 已 mock `metricDefinitionMapper`）。

- [ ] **Step 2: 跑确认失败**

Run: `$MVN -pl rule-config-svc test -Dtest=PublishServiceTest`
Expected: 失败（仍写字符串数组）。

- [ ] **Step 3: 改 PublishService**

4.5 节查询改为按 code 查 **ACTIVE** 行并拿 version：
```java
        AstNode resolvedAst = ast;
        List<MetricDependency> metricDeps = new ArrayList<>();
        if (!metricCodes.isEmpty()) {                       // metricCodes = MetricDependencyCollector.collect(ast)
            List<MetricDefinition> metricDefs = metricDefinitionMapper.selectList(
                    new LambdaQueryWrapper<MetricDefinition>()
                            .eq(MetricDefinition::getTenantId, tenantId)
                            .in(MetricDefinition::getMetricCode, metricCodes)
                            .eq(MetricDefinition::getStatus, "ACTIVE"));
            Map<String, MetricDefinition> activeByCode = new HashMap<>();
            for (MetricDefinition m : metricDefs) {
                MetricDefinition prev = activeByCode.putIfAbsent(m.getMetricCode(), m);
                if (prev != null) {   // 同 code 多行 ACTIVE = 数据异常，发布兜底拒绝
                    throw new IllegalArgumentException(
                            "metric 存在多个 ACTIVE 版本，数据异常: " + m.getMetricCode());
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
            Map<String, String> dataTypeMap = activeByCode.values().stream()
                    .collect(Collectors.toMap(MetricDefinition::getMetricCode, MetricDefinition::getDataType));
            resolvedAst = AstDataTypeResolver.resolve(ast, dataTypeMap);

            // ↓↓↓ 现有 4.6 节安全校验【原样保留，重写时勿丢】：MetricSafetyValidator(SQL 时间函数/拼接拒绝)
            //      + metricResourceCatalog 资源名注册校验。把 metricDefs 替换为 activeByCode.values() 的 List 即可。
            java.util.Set<String> dsNames = metricResourceCatalog != null
                    ? metricResourceCatalog.datasourceNames() : null;
            java.util.Set<String> epNames = metricResourceCatalog != null
                    ? metricResourceCatalog.endpointNames() : null;
            new MetricSafetyValidator(objectMapper)
                    .validate(new java.util.ArrayList<>(activeByCode.values()), dsNames, epNames);
        }
```
其中把原 `List<String> metricDeps = MetricDependencyCollector.collect(ast);` 改名为 `List<String> metricCodes = MetricDependencyCollector.collect(ast);`（collector 不变，仍收集 code）。
`newRv.setMetricDependencies(toJson(metricDeps));` —— `toJson` 现写 `List<MetricDependency>`，序列化为对象数组（Jackson 默认）。
`RuleVersionSnapshot(...)` 构造尾参 `metricDeps` 现为 `List<MetricDependency>`，类型已匹配 Task 3。
顶部 import `com.sstlfsj.rule.kernel.api.model.MetricDependency;`。

- [ ] **Step 4: 跑测试确认通过**

Run: `$MVN -pl rule-config-svc -am test -Dtest=PublishServiceTest`
Expected: 通过。

- [ ] **Step 5: commit**

```bash
git add rule-config-svc/.../publish/PublishService.java rule-config-svc/src/test/.../PublishServiceTest.java
git commit -m "feat(config): 发布期冻结被引用 metric 的当前 ACTIVE 版本号进 metric_dependencies(B6)"
```

---

## Task 7: metric 写服务 + 升版

补 metric 创建/更新写入口；更新带 `breakingChange` → INSERT 新版本行（旧行 SUPERSEDED）同事务。

**Files:**
- Create: `rule-config-svc/.../api/service/MetricWriteService.java`
- Create: `rule-config-svc/.../internal/service/MetricWriteServiceImpl.java`
- Test: `rule-config-svc/.../internal/service/MetricWriteServiceImplTest.java`（单测，mock mapper）+ 集成测试覆盖升版事务

- [ ] **Step 1: 写失败测试**

`MetricWriteServiceImplTest`（mock `MetricDefinitionMapper`）：
- `create` → `insert` 一次，version=1，status=ACTIVE。
- `update(breakingChange=false)` → 对当前 ACTIVE 行 `updateById`，version 不变。
- `update(breakingChange=true)` → 先把旧 ACTIVE 行 `update status=SUPERSEDED`，再 `insert` 新行 version=旧+1 status=ACTIVE。

- [ ] **Step 2: 跑确认失败**

Run: `$MVN -pl rule-config-svc test -Dtest=MetricWriteServiceImplTest`
Expected: 编译失败（类不存在）。

- [ ] **Step 3: 写接口 + 实现**

`MetricWriteService.java`（api 包）：
```java
package com.sstlfsj.rule.config.api.service;

/** Metric 注册/更新/升版写服务（10-api-contract §3 /api/v1/metrics）。 */
public interface MetricWriteService {

    /** 注册新 metric（version=1, status=ACTIVE）。返回新行 id。 */
    Long create(Long tenantId, MetricWriteCommand cmd, String actorId);

    /**
     * 更新 metric。breakingChange=true 时语义不兼容：旧 ACTIVE 行转 SUPERSEDED + 插入新版本行；
     * false 时原地更新当前 ACTIVE 行。返回当前生效行的 version。
     */
    int update(Long tenantId, String metricCode, MetricWriteCommand cmd, boolean breakingChange, String actorId);

    /** metric 写入参数。 */
    record MetricWriteCommand(String name, String sourceType, String dataType,
                              String paramsJson, Integer cacheTtlSeconds, boolean allowProvided) {}
}
```
`MetricWriteServiceImpl.java`（internal 包，`@Service @Transactional`）：
```java
    @Override
    public Long create(Long tenantId, MetricWriteCommand cmd, String actorId) {
        MetricDefinition m = new MetricDefinition();
        m.setTenantId(tenantId);
        m.setMetricCode(/* code 从 cmd? */);     // 见下注
        m.setVersion(1);
        m.setName(cmd.name());
        m.setSourceType(cmd.sourceType());
        m.setDataType(cmd.dataType());
        m.setParams(cmd.paramsJson() == null ? "{}" : cmd.paramsJson());
        m.setCacheTtlSeconds(cmd.cacheTtlSeconds() == null ? 60 : cmd.cacheTtlSeconds());
        m.setAllowProvided(cmd.allowProvided());
        m.setStatus("ACTIVE");
        m.setCreatedBy(actorId);
        m.setCreatedAt(LocalDateTime.now());
        mapper.insert(m);
        return m.getId();
    }

    @Override
    public int update(Long tenantId, String metricCode, MetricWriteCommand cmd,
                      boolean breakingChange, String actorId) {
        MetricDefinition active = mapper.selectOne(new LambdaQueryWrapper<MetricDefinition>()
                .eq(MetricDefinition::getTenantId, tenantId)
                .eq(MetricDefinition::getMetricCode, metricCode)
                .eq(MetricDefinition::getStatus, "ACTIVE"));
        if (active == null) throw new IllegalArgumentException("metric 不存在或无 ACTIVE 版本: " + metricCode);
        if (!breakingChange) {
            active.setName(cmd.name());
            active.setSourceType(cmd.sourceType());
            active.setDataType(cmd.dataType());
            active.setParams(cmd.paramsJson() == null ? "{}" : cmd.paramsJson());
            active.setCacheTtlSeconds(cmd.cacheTtlSeconds() == null ? 60 : cmd.cacheTtlSeconds());
            active.setAllowProvided(cmd.allowProvided());
            active.setUpdatedBy(actorId);
            mapper.updateById(active);
            return active.getVersion() == null ? 1 : active.getVersion();
        }
        int newVersion = (active.getVersion() == null ? 1 : active.getVersion()) + 1;
        active.setStatus("SUPERSEDED");
        active.setUpdatedBy(actorId);
        mapper.updateById(active);
        MetricDefinition next = new MetricDefinition();
        next.setTenantId(tenantId);
        next.setMetricCode(metricCode);
        next.setVersion(newVersion);
        next.setName(cmd.name());
        next.setSourceType(cmd.sourceType());
        next.setDataType(cmd.dataType());
        next.setParams(cmd.paramsJson() == null ? "{}" : cmd.paramsJson());
        next.setCacheTtlSeconds(cmd.cacheTtlSeconds() == null ? 60 : cmd.cacheTtlSeconds());
        next.setAllowProvided(cmd.allowProvided());
        next.setStatus("ACTIVE");
        next.setCreatedBy(actorId);
        next.setCreatedAt(LocalDateTime.now());
        mapper.insert(next);
        return newVersion;
    }
```
> **注**：`metricCode` 在 create 时从命令传入——给 `MetricWriteCommand` 加 `String metricCode` 字段，或把 `create(tenantId, metricCode, cmd, actor)`。执行者择一，保持与 Controller 端点签名一致（见 Task 8）。审计写 `audit_log`（action=CREATE/UPDATE，target_type=metric_definition）与 `PublishService` 既有风格一致，纳入本实现。

- [ ] **Step 4: 集成测试覆盖升版事务**

新增 `MetricVersioningIntegrationTest`（Testcontainers，仿 `MetadataServiceIntegrationTest` 骨架）：create → update(breakingChange=true) → 断言库内同 (tenant,code) 有 v1(SUPERSEDED) + v2(ACTIVE) 两行，且 UK `uk_tenant_code_version` 不冲突。

Run: `$MVN -pl rule-config-svc -am test -Dtest=MetricWriteServiceImplTest,MetricVersioningIntegrationTest`
Expected: 通过。

- [ ] **Step 5: commit**

```bash
git add rule-config-svc/.../api/service/MetricWriteService.java \
        rule-config-svc/.../internal/service/MetricWriteServiceImpl.java rule-config-svc/src/test/...
git commit -m "feat(config): metric 写服务 + 显式语义变更升版(B6)"
```

---

## Task 8: 影响面查询 + MetricController 端点

**Files:**
- Modify: `rule-config-svc/.../api/service/MetricWriteService.java`（加影响面方法）+ impl
- Create: `rule-api/.../web/config/MetricController.java`
- Test: impl 单测 + controller 测试（仿 `RuleController` 测试风格）

- [ ] **Step 1: 写失败测试**

impl 测试：`findReferencingRules(tenant, "account.age", 1)` → 扫 ACTIVE `rule_version`，解析 `metric_dependencies`，返回含 `{account.age,1}` 的规则清单（ruleDefinitionId/code/name/ruleVersionId）与 count。

- [ ] **Step 2: 跑确认失败**

Run: `$MVN -pl rule-config-svc test -Dtest=MetricWriteServiceImplTest`
Expected: 失败（方法不存在）。

- [ ] **Step 3: 实现影响面查询**

`MetricWriteService` 加：
```java
    /** 查询引用某 (metricCode, version) 的所有 ACTIVE 规则（运营升版前评估影响面）。 */
    java.util.List<RuleRef> findReferencingRules(Long tenantId, String metricCode, int metricVersion);

    record RuleRef(Long ruleDefinitionId, String ruleCode, String ruleName, Long ruleVersionId) {}
```
impl：取该 tenant 全部 ACTIVE `rule_version`（JOIN rule_definition 拿 code/name），用 `objectMapper.readValue(metricDependencies, List<MetricDependency>)` 解析，filter 含目标 (code,version)。注意 config 侧需能反序列化 `MetricDependency`——用全局注入的 `ObjectMapper`（memory: Spring 组件注入全局 Bean）。

- [ ] **Step 4: MetricController 端点**

`rule-api/.../web/config/MetricController.java`，仿 `RuleController` 的 `ApiResponse` + `X-Actor-Id` header 风格：
```java
@RestController
@RequestMapping("/api/v1/metrics")
public class MetricController {
    private final MetricWriteService service;
    // 构造注入

    /** 注册 metric。 */
    @PostMapping
    public ApiResponse<Long> create(@RequestParam Long tenantId,
                                    @RequestHeader("X-Actor-Id") String actorId,
                                    @RequestBody MetricWriteService.MetricWriteCommand cmd) { ... }

    /** 更新 metric；breakingChange=true 触发升版。 */
    @PutMapping("/{metricCode}")
    public ApiResponse<Integer> update(@PathVariable String metricCode,
                                       @RequestParam Long tenantId,
                                       @RequestParam(defaultValue = "false") boolean breakingChange,
                                       @RequestHeader("X-Actor-Id") String actorId,
                                       @RequestBody MetricWriteService.MetricWriteCommand cmd) { ... }

    /** 影响面：引用某版本的规则清单。 */
    @GetMapping("/{metricCode}/versions/{version}/impact")
    public ApiResponse<List<MetricWriteService.RuleRef>> impact(@PathVariable String metricCode,
                                                                @PathVariable int version,
                                                                @RequestParam Long tenantId) { ... }
}
```
> 按 `RuleController` 实际的 `ApiResponse` 静态工厂（如 `ApiResponse.ok(...)`）与包路径补全 import。

- [ ] **Step 5: 跑测试**

Run: `$MVN -pl rule-config-svc -am test` 和 `$MVN -pl rule-api -am test`
Expected: 通过。

- [ ] **Step 6: commit**

```bash
git add rule-config-svc/.../MetricWriteService.java rule-config-svc/.../MetricWriteServiceImpl.java \
        rule-api/.../web/config/MetricController.java rule-config-svc/src/test/... rule-api/src/test/...
git commit -m "feat(api): metric 写/升版/影响面查询端点 /api/v1/metrics(B6)"
```

---

## Task 9: SDK 下发口径 + MetadataServiceImpl 解析

`metric_dependencies` 解析从字符串数组改对象数组取 code；SDK 下发的 metric 定义按被引用的 (code,version) 并集，且带 version。

**Files:**
- Modify: `rule-config-svc/.../internal/service/MetadataServiceImpl.java`
- Test: `MetadataServiceIntegrationTest`（更新 seed 的 metric_dependencies 为对象数组）

- [ ] **Step 1: 改 MetadataServiceImpl 解析**

`parseStringList` 替换为解析 `MetricDependency` 取 code：
```java
    private Set<String> /* in collectRequiredMetricCodes */ ...
            codes.addAll(parseDepCodes(rv.getMetricDependencies()));
    ...
    private List<String> parseDepCodes(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<MetricDependency> deps = objectMapper.readValue(json, new TypeReference<>() {});
            return deps.stream().map(MetricDependency::metricCode).toList();
        } catch (Exception e) {
            return List.of();
        }
    }
```
`toDescriptor`：`MetricDescriptor` 构造加 version —— `m.getVersion() == null ? 1 : m.getVersion()`。
`MetricMeta`/`getSceneMetadata`：若仅展示，可不带 version（本批次 UI 不在范围）；保持现状即可。

- [ ] **Step 2: SDK 下发须含 SUPERSEDED 旧版定义（高风险，独立 Step）**

**问题**：`listMetricDefinitions` DECLARED 模式当前按 code 并集过滤 **ACTIVE** 定义。版本化后，存量规则快照可能绑定 **SUPERSEDED** 旧版 `(code, oldVersion)`；若 SDK 只拿到 ACTIVE 版本，评估期 `resolve(code, oldVersion)` 返回 null → `METRIC_FETCH_FAIL`（静默错误）。

**改法**：DECLARED 模式把 `collectRequiredMetricCodes` 升级为 `collectRequiredDeps`，收集候选 `rule_version.metric_dependencies` 的 `(code, version)` 并集；按精确 `(code, version)` 查 `metric_definition`（**不限 status，含 SUPERSEDED**）下发，每个 `MetricDescriptor` 带其 `version`。ALL 模式仍只下发 ACTIVE（新规则只能绑 ACTIVE）。

写测试：集成测试 seed 一个 metric `risk.score` 有 v1(SUPERSEDED) + v2(ACTIVE) 两行，一条规则 `metric_dependencies` 绑 `{risk.score, 1}` → 断言 `listMetricDefinitions(tenant, [scene])` 返回的定义含 `metricVersion=1` 的那一行（SUPERSEDED 也下发）。

Run: `$MVN -pl rule-config-svc -am test -Dtest=MetadataServiceImplTest`
Expected: 新测试失败 → 实现后通过。

- [ ] **Step 3: 更新集成测试 seed + 断言**

`MetadataServiceIntegrationTest.ruleVersion(...)` 的 `metricDepsJson` 由 `"[\"risk.score\"]"` 改为 `"[{\"metricCode\":\"risk.score\",\"metricVersion\":1}]"`，其余断言（按 metricCode）不变。

Run: `$MVN -pl rule-config-svc -am test -Dtest=MetadataServiceIntegrationTest`
Expected: 通过。

- [ ] **Step 4: commit**

```bash
git add rule-config-svc/.../MetadataServiceImpl.java rule-config-svc/src/test/...
git commit -m "feat(config): metric_dependencies 对象数组解析 + SDK 按版本下发(含 SUPERSEDED)定义(B6)"
```

---

## Task 10: examples + 文档同步

**Files:**
- Modify: `docs/examples/**/rules/*.json`（6 个，见下）
- Modify: `docs/01-concepts.md`（§3.9 metricVersion 行）
- Modify: `docs/05-storage.md`（metric_definition DDL + rule_version.metric_dependencies 注释）
- Modify: `docs/08-evolution.md`（§2.2 标记已实装 + 修正第四条措辞）
- Modify: `docs/10-api-contract.md`（/api/v1/metrics 端点 + metric_dependencies schema + §8.7 SDK 下发口径）
- Modify: `.claude/agents/rule-engine-reviewer.md`（"刻意设计"清单第 6 条 metricVersion 占位，B6 后过期需更新）

- [ ] **Step 1: 升级 6 个 examples JSON**

把每个文件的 `metricDependencies: ["code", ...]` 改为对象数组，version 一律 `1`。文件清单：
- `docs/examples/risk-control/new-account-large-transfer/rules/block-new-account.json`
- `docs/examples/risk-control/ticket-creation/rules/rule-transfer-review.json`
- `docs/examples/risk-control/user-register/rules/rule-register-risk.json`
- `docs/examples/patterns/time-conditions/rules/rule-metric-window.json`
- `docs/examples/patterns/time-conditions/rules/rule-occurred-at.json`
- `docs/examples/patterns/time-conditions/rules/rule-time-window.json`

示例（block-new-account.json 尾部）：
```json
  "metricDependencies": [
    { "metricCode": "user.account.age.days", "metricVersion": 1 },
    { "metricCode": "user.kyc.level", "metricVersion": 1 },
    { "metricCode": "user.transfer.count.7d", "metricVersion": 1 },
    { "metricCode": "user.transfer.dest.trust.score", "metricVersion": 1 }
  ]
```

- [ ] **Step 2: 文档同步**

- `01-concepts.md §3.9` 的 `metricVersion` 行：从"概念占位（v1 DDL 无此列）"更新为"已实装（B6）：`metric_definition.version`，规则发布期冻结 (metricCode, metricVersion) 绑定，详见 08-evolution §2.2"。
- `05-storage.md`：`metric_definition` DDL 补 `version INT NOT NULL DEFAULT 1` 列、status ENUM 加 `SUPERSEDED`、UK 改为 `(tenant_id, metric_code, version)`；`rule_version.metric_dependencies` 注释从"AST 引用的 metricCode 列表"改为"AST 引用的 (metricCode, metricVersion) 对象数组（发布期冻结当前 ACTIVE 版本）"。
- `08-evolution.md §2.2`：在末尾加"**已实装（B6 / 2026-06-06，档1）**"清单，简述 DDL/发布/评估/写服务/影响面 API 落地点。**并修正第四条演进方向措辞**——原文"评估期 `EvalContext` 按 `(metricCode, metricVersion)` 拉取"暗示 EvalContext 二元键；改为"评估期按规则绑定版本 `resolve(code, version)` 解析定义；档1 `EvalContext` 仍按 code 索引单值、过渡期取最高版本；完整 per-rule 版本投影（EvalContext 二元键）为档2 留后续"。同时更新 §五接收锚点表 §3.9 行状态。
- `10-api-contract.md`：补 `POST /api/v1/metrics`、`PUT /api/v1/metrics/{metricCode}?breakingChange=`、`GET /api/v1/metrics/{metricCode}/versions/{version}/impact` 契约，把 `metric_dependencies` schema 标注为对象数组 `[{metricCode, metricVersion}]`；**§8.7 SDK 下发口径**补注"DECLARED 模式按被引用 (code,version) 并集下发，含 SUPERSEDED 旧版定义，每项带 metricVersion"。
- `.claude/agents/rule-engine-reviewer.md`：**更新"刻意设计"清单第 6 条**——B6 后 `metricVersion` 不再是"v1 DDL 无此列"的占位，改为"`metric_definition.version` 列已实装（B6）；规则发布期冻结 (metricCode, metricVersion)，`metric_dependencies` 为对象数组"。否则该 agent 会继续按过期规则跳过 metricVersion 相关审查，造成漏审。

- [ ] **Step 3: 跑文档自洽性检查**

改 `docs/**` 后用 `doc-consistency-review` skill 扫 `00-decisions` / `01-concepts` / `08-evolution` / `10-api-contract` 自洽性（CLAUDE.md 文档纪律）；改完用 `rule-engine-reviewer` agent 审"代码 ↔ 文档对齐"。

- [ ] **Step 4: 全量回归**

Run: `$MVN test`（全模块）
Expected: 全绿。

- [ ] **Step 5: commit**

```bash
git add docs/
git commit -m "docs(B6): examples metric_dependencies 升对象数组 + §2.2/§3.9/§10 同步(B6 已实装)"
```

---

## 依赖与执行顺序

```
T1(类型) ─┬─> T3(快照契约) ──> T5(评估按版本解析)
          ├─> T2(DDL/实体) ──> T6(发布期) ──> T8(影响面/端点)
          └─> T4(SPI解析) ───> T5
                                       T2 ──> T7(写服务) ──> T8
T3 + T6 ──────────────> T9(下发口径) ──> T10(examples+docs)
```
- **T4 与 T5 建议连续执行、合并验证**（T4 改 SPI 签名会让 eval-svc 暂不可编译，T5 修好 `EvalContextAssembler` 调用点后一并跑 `-pl rule-eval-svc -am test`）。档 1 下 T5 仅改 `EvalContextAssembler`，不动 `EvalEngine`/`EvalContext`。
- 每个 Task 提交前跑该模块 `-pl <module> -am test` 全绿（CLAUDE.md 测试纪律，不得 `-DskipTests` 绕过）。

## 已知限制（本批次不做，留后续）

- **过渡期弱隔离（档 1 的核心取舍）**：同一 Scene 内 ruleA 绑 v1、ruleB 绑 v2 同为候选时，按 code 取**最高版本**解析定义并取数一次共享——绑旧版的存量规则在该罕见窗口会暂时看到新版语义。稳态完全正确；真出现高频混版本且业务不能容忍时，再升级到档 2（per-rule 运行时投影，重构 `EvalEngine`/`EvalContext`）。
- metric `DISABLE` 被 ACTIVE 规则引用版本的拦截校验（评估期按精确版本仍可解析，不阻断）。
- 前端规则编辑器/运营 UI 渲染影响面（仅后端 API）。
- 发布期"重新发布以升级到新版本"的批量工具（运营逐条重发，对齐 D19 否决批量原子发布）。

---

## Self-Review

**Spec 覆盖（对照 08-evolution §2.2 五条演进方向）：**
1. `metric_definition` 加 version + 升版=INSERT 新行/旧行 SUPERSEDED → T2(DDL) + T7(写服务升版)。✅
2. `metric_dependencies` 升对象数组 → T1(类型) + T3(契约) + T6(发布写入) + T9(解析) + T10(examples)。✅
3. 发布期校验 (code,version) 存在且 ACTIVE → T6（恰好一个 ACTIVE 校验）。✅
4. 评估期按绑定版本解析定义、不取最新 → T4(SPI/DbResolver findByVersion) + T5(按 code 取最高版本 `resolve(code,version)`，档 1)。✅
5. 运营影响面查询 → T8(findReferencingRules + impact 端点)。✅

**类型一致性：** `MetricDependency(String metricCode, int metricVersion)` 全程一致；`MetricDescriptor` 字段序 `(metricCode, metricVersion, sourceType, dataType, allowProvided, cacheTtlSeconds, params)` 在 T1/T4/T9 构造点一致；`resolve(tenantId, code, version)` 在 SPI/Db/SDK 三实现一致。档 1 不引入 `Versioned`/`projectFor`/`withMetrics`，`EvalEngine`/`EvalContext` 零改动。

**占位扫描：** T5 测试的 `RuleEvent` 构造、T7 `create` 的 `metricCode` 来源、T8 Controller 的 `ApiResponse` 工厂标注了"按现有真实签名补全"——这些是依赖既有代码约定的接合点，已指明确切参照对象（`EvalContextAssemblerTest` 事件构造 / `RuleController` 风格），非逻辑占位。
