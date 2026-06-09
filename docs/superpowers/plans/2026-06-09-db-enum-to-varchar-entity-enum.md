# R10：剩余 DB ENUM 列 → VARCHAR + 实体真 enum（全模块）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把项目中**剩余的全部 MySQL `ENUM` 列**改为 `VARCHAR`，实体字段改为真 Java enum（按 `name()` 与列往返），契约边界保持 String，沿用 R9（V1_15）已验证的模式。

**Architecture:** 单一真相源在 Java enum；DB 列 `VARCHAR` 存枚举名；MyBatis-Plus 全局 `default-enum-type-handler: MybatisEnumTypeHandler` 按 `name()` 自动往返；出 VO/DTO/API 契约边界 `.name()` 转 String（对外契约不变）。封闭取值能复用 kernel 既有枚举（`SubjectType`/`RuleKind`/`EventSource`/`ValueSource`/`ActionResult.ActionStatus`）就复用，不重复造。所有 Flyway 迁移物理上都放在 `rule-config-svc/src/main/resources/db/migration/`（eval/job 测试经 `classpath:db/migration` 共享同一份）。

**Tech Stack:** Java 25 / Spring Boot 4 / MyBatis-Plus / Flyway / Testcontainers MySQL。

**执行纪律（强制，来自 CLAUDE.md）：**
- 在 `develop` 直接提交，**不 push / 不 merge / 不开 PR**。
- 每个 Task 提交前跑该模块全量测试（`$MVN -pl <module> -am test`），全绿才 commit；**跨模块改动必带 `-am`**。
- 全部 Task 完成后用**无 `-pl` 的 `$MVN clean test`** 兜底（只有 `clean` 才强制重编所有 test 类，规避 stale-jar 假象）。
- 不得 `-DskipTests` / `--no-verify` 绕过失败。
- 代码注释中文；测试方法名英文。
- mvn-env：本项目 Java 25，按 `mvn-env` skill 设置 `JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-25.jdk/Contents/Home`，`MVN=/Users/sunke/.m2/wrapper/dists/apache-maven-3.9.9-bin/4nf9hui3q3djbarqar9g711ggc/apache-maven-3.9.9/bin/mvn`。

---

## 列 → 枚举 总清单（18 列）

| 模块 | 表.列 | 目标枚举 | 新建/复用 | 迁移 |
|---|---|---|---|---|
| kernel | （`scene.subject_type` 用） | `SubjectType` 加 `CUSTOM` | 改既有 | — |
| config | `tenant.status` | `TenantStatus{ACTIVE,DISABLED}` | 新建 | V1_16 |
| config | `decision_definition.status` | `DecisionStatus{ACTIVE,DISABLED}` | 新建 | V1_16 |
| config | `scene.dominant_mode` | `DominantMode{PUSH,PULL,HYBRID}` | 新建 | V1_16 |
| config | `scene.decision_strategy` | `DecisionStrategy{HIGHEST_PRIORITY,ALL_HITS,FIRST_HIT}` | 新建 | V1_16 |
| config | `scene.subject_type` | kernel `SubjectType`(+CUSTOM) | 复用 | V1_16 |
| config | `rule_definition.kind` | kernel `RuleKind` | 复用 | V1_16 |
| config | `rule_version.kind` | kernel `RuleKind` | 复用 | V1_16 |
| config | `audit_log.actor_type` | `ActorType{USER,SYSTEM,JOB}` | 新建 | V1_16 |
| eval | `evaluation_session.source` | kernel `EventSource` | 复用 | V1_17 |
| eval | `evaluation_session.mode` | `EvalMode{PUSH,PULL}` | 新建 | V1_17 |
| eval | `evaluation_session.status` | `SessionStatus{PENDING,HIT,MISS,BLOCKED,ERROR,FAILED}` | 新建 | V1_17 |
| eval | `dry_run_session.status` | `SessionStatus`（复用上一个） | 复用 | V1_17 |
| eval | `action_execution.status` | kernel `ActionResult.ActionStatus` | 复用 | V1_17 |
| eval | `dry_run_session.trigger` | **纯 DDL→VARCHAR，无实体字段** | — | V1_17 |
| observability | `node_trace.value_source` | kernel `ValueSource` | 复用 | V1_18 |
| observability | `dry_run_node_trace.value_source` | kernel `ValueSource` | 复用 | V1_18 |
| job | `job_definition.status` | `JobStatus{ACTIVE,DISABLED}` | 新建 | V1_19 |
| job | `job_execution.status` | `JobExecutionStatus{RUNNING,SUCCESS,PARTIAL_FAIL,FAILED}` | 新建 | V1_19 |

> 已在前序完成、本次不动：`metric_definition.source_type/data_type`（V1_11 已 VARCHAR + `MetricEnums` 常量，SPI 开放集按 CLAUDE.md §6 用常量类不用 enum）、`rule_definition.status/rule_version.status/scene.status/metric_definition.status`（V1_15/V1_11 已转）。

---

## File Structure（落点）

**新建枚举：**
- `rule-config-svc/.../internal/domain/TenantStatus.java`
- `rule-config-svc/.../internal/domain/DecisionStatus.java`
- `rule-config-svc/.../internal/domain/DominantMode.java`
- `rule-config-svc/.../internal/domain/DecisionStrategy.java`
- `rule-config-svc/.../internal/domain/ActorType.java`
- `rule-eval-svc/.../internal/domain/SessionStatus.java`
- `rule-eval-svc/.../internal/domain/EvalMode.java`
- `rule-job-svc/.../internal/domain/JobStatus.java`
- `rule-job-svc/.../internal/domain/JobExecutionStatus.java`

**改既有枚举：** `rule-kernel/.../api/model/SubjectType.java`（加 `CUSTOM`）。

**改实体字段（String→enum）：** `Tenant`、`DecisionDefinition`、`SceneDef`、`RuleDefinition`、`RuleVersion`、`AuditLog`（config）；`EvaluationSession`、`ActionExecutionEntity`、`DryRunSession`（eval）；`NodeTraceEntity`、`DryRunNodeTraceEntity`（observability）；`JobDefinition`、`JobExecution`（job）。

**迁移（全部在 `rule-config-svc/src/main/resources/db/migration/`）：** `V1_16`、`V1_17`、`V1_18`、`V1_19`。

**测试配置：** `rule-eval-svc/src/test/resources/application-test.yml`、`rule-job-svc/src/test/resources/application-test.yml` 各加 `default-enum-type-handler`。observability 的 mapper 测试是纯反射/字符串断言、不连库，**无需改**（运行期在 rule-app 下已有该 handler）。config-svc 主/测配置已有。

---

## Task 1：kernel `SubjectType` 加 `CUSTOM`

**Files:**
- Modify: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/SubjectType.java`
- Test: `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/api/spi/subject/SubjectLoaderTest.java`（既有，验证编译）

- [ ] **Step 1：改枚举，加 CUSTOM**

`SubjectType.java` 改为：

```java
package com.sstlfsj.rule.kernel.api.model;

/** 规则评估的主体类型枚举（CUSTOM 对应 scene.subject_type 的开放主体）。 */
public enum SubjectType {
    USER, ACCOUNT, DEVICE, ORDER, CUSTOM
}
```

- [ ] **Step 2：跑 kernel 测试**

Run: `$MVN -pl rule-kernel -am test`
Expected: BUILD SUCCESS（kernel 纯 Java，新增枚举常量不破坏既有用例）。

- [ ] **Step 3：commit**

```bash
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/SubjectType.java
git commit -m "feat(kernel): SubjectType 补 CUSTOM（对齐 scene.subject_type 契约值）"
```

---

## Task 2：config 模块 8 列 → enum（迁移 V1_16）

**Files:**
- Create: 5 个新枚举（见下）
- Create: `rule-config-svc/src/main/resources/db/migration/V1_16__config_enum_columns_to_varchar.sql`
- Modify: `Tenant.java`、`DecisionDefinition.java`、`SceneDef.java`、`RuleDefinition.java`、`RuleVersion.java`、`AuditLog.java`，及其写入/读取边界（`SceneServiceImpl`、`RuleImportService`、`AuditLogWriter`、`PublishService`、相关 MapStruct convert）
- Test: 该模块既有全量测试（`MetadataServiceImplTest`、`MetadataServiceIntegrationTest`、各 `*DefTest`、`PublishServiceTest` 等）

- [ ] **Step 1：建迁移 V1_16**

`V1_16__config_enum_columns_to_varchar.sql`：

```sql
-- config 模块剩余 ENUM 列 → VARCHAR：取值真相源上移 app 层 Java enum（按 name 与列往返）。
-- DEFAULT 沿用 V1_0 原值；kind 沿用 kernel RuleKind 枚举名（tag()==name()）。
ALTER TABLE tenant
  MODIFY COLUMN status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE';

ALTER TABLE scene
  MODIFY COLUMN dominant_mode     VARCHAR(16) NOT NULL,
  MODIFY COLUMN decision_strategy VARCHAR(32) NOT NULL DEFAULT 'HIGHEST_PRIORITY',
  MODIFY COLUMN subject_type      VARCHAR(16) NOT NULL DEFAULT 'USER';

ALTER TABLE decision_definition
  MODIFY COLUMN status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE';

ALTER TABLE rule_definition
  MODIFY COLUMN kind VARCHAR(32) NOT NULL DEFAULT 'AST_BOOLEAN';

ALTER TABLE rule_version
  MODIFY COLUMN kind VARCHAR(32) NOT NULL DEFAULT 'AST_BOOLEAN';

ALTER TABLE audit_log
  MODIFY COLUMN actor_type VARCHAR(16) NOT NULL DEFAULT 'USER';
```

- [ ] **Step 2：建 5 个新枚举**

`TenantStatus.java`：
```java
package com.sstlfsj.rule.config.internal.domain;

/** tenant.status 取值。 */
public enum TenantStatus { ACTIVE, DISABLED }
```

`DecisionStatus.java`：
```java
package com.sstlfsj.rule.config.internal.domain;

/** decision_definition.status 取值。 */
public enum DecisionStatus { ACTIVE, DISABLED }
```

`DominantMode.java`：
```java
package com.sstlfsj.rule.config.internal.domain;

/** scene.dominant_mode 取值：PUSH=异步派发 / PULL=同步返回 / HYBRID=两者。 */
public enum DominantMode { PUSH, PULL, HYBRID }
```

`DecisionStrategy.java`：
```java
package com.sstlfsj.rule.config.internal.domain;

/** scene.decision_strategy 取值（D 决策聚合策略）。 */
public enum DecisionStrategy { HIGHEST_PRIORITY, ALL_HITS, FIRST_HIT }
```

`ActorType.java`：
```java
package com.sstlfsj.rule.config.internal.domain;

/** audit_log.actor_type 取值：操作方类型。 */
public enum ActorType { USER, SYSTEM, JOB }
```

- [ ] **Step 3：改实体字段 String→enum**

- `Tenant.java`：`private String status;` → `private TenantStatus status;`
- `DecisionDefinition.java`：`private String status;` → `private DecisionStatus status;`
- `SceneDef.java`：`private String dominantMode;`→`private DominantMode dominantMode;`、`private String subjectType;`→`private com.sstlfsj.rule.kernel.api.model.SubjectType subjectType;`、`private String decisionStrategy;`→`private DecisionStrategy decisionStrategy;`
- `RuleDefinition.java`：`kind` String→`com.sstlfsj.rule.kernel.api.model.RuleKind`
- `RuleVersion.java`：`kind` String→`com.sstlfsj.rule.kernel.api.model.RuleKind`
- `AuditLog.java`：`actorType` String→`ActorType`

- [ ] **Step 4：改写入/读取边界**

写入侧（String 入参 → enum）：
- `SceneServiceImpl.createScene(...)`：API 契约入参仍是 `String dominantMode/subjectType/decisionStrategy`；构建 `SceneDef` 时解析：`scene.setDominantMode(DominantMode.valueOf(dominantMode))`、`scene.setSubjectType(SubjectType.valueOf(subjectType))`、`scene.setDecisionStrategy(DecisionStrategy.valueOf(decisionStrategy))`。更新场景同理。
- `RuleImportService`：从 `SceneSnapshot` 还原 `SceneDef` 处，同样 `valueOf` 解析三列。
- `RuleDefinition.draft(...)` / `RuleVersion.draftV1(...)` 静态工厂：`kind` 入参若为 String 改为接收 `RuleKind`，调用方（`PublishService`）传 `RuleKind` 而非 `.tag()` 字符串；若工厂已接 `RuleKind` 则只去掉多余 `.tag()`。
- `AuditLogWriter`：落 `AuditLog` 时 `log.setActorType(ActorType.valueOf(event.actorType()))`（`OperationAuditedEvent.actorType` 保持 String，恒为 `"USER"`）。

读取/契约出口（enum → String）：
- `SceneDetailDto`/`SceneListItem`/`SceneResponse` 的 `dominantMode/subjectType/decisionStrategy` 字段**保持 String**；MapStruct `SceneConvert` 会自动 `enum→String`（按 `name()`）。若 convert 是手写映射，显式 `.name()`。
- `RuleListItemVO`/`RuleDetailVO`/`RuleBundle` 等输出 `kind` 处保持 String，`.name()`（== `.tag()`）。
- `RuleBundle.SceneSnapshot` 等导出快照中的三列保持 String。

> 实施提示：编译期会精确报出所有需要 `.valueOf()` / `.name()` 的点，逐个按"契约边界 String、实体内部 enum"原则收敛即可。

- [ ] **Step 5：跑 config 模块全量测试**

Run: `$MVN -pl rule-config-svc -am test`
Expected: BUILD SUCCESS。重点关注 `MetadataServiceIntegrationTest`（Testcontainers 真 MySQL，验证 enum↔varchar 往返）、`SceneDefTest`/`MetricDefinitionTest` 等实体往返、`PublishServiceTest`。
- 若 `*DefTest` 因 setter 入参类型变化编译失败：把测试里 `setDominantMode("PUSH")` 改为 `setDominantMode(DominantMode.PUSH)` 等（测试方法名保持英文，注释中文）。

- [ ] **Step 6：commit**

```bash
git add rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/domain/TenantStatus.java \
        rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/domain/DecisionStatus.java \
        rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/domain/DominantMode.java \
        rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/domain/DecisionStrategy.java \
        rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/domain/ActorType.java \
        rule-config-svc/src/main/resources/db/migration/V1_16__config_enum_columns_to_varchar.sql \
        rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/domain/ \
        rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/service/ \
        rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/bundle/ \
        rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/event/ \
        rule-config-svc/src/test/
git commit -m "refactor(config): tenant/decision/scene/kind/actor_type 列 ENUM→VARCHAR + 实体真 enum（契约边界保持 String）"
```

---

## Task 3：eval 模块 6 列 → enum（迁移 V1_17）

**Files:**
- Create: `rule-eval-svc/.../internal/domain/SessionStatus.java`、`rule-eval-svc/.../internal/domain/EvalMode.java`
- Create: `rule-config-svc/src/main/resources/db/migration/V1_17__eval_enum_columns_to_varchar.sql`
- Modify: `EvaluationSession.java`、`DryRunSession.java`、`ActionExecutionEntity.java`、`AuditPersister.java`、`DryRunPersister.java`、`ActionExecutionPersister.java`、`AuditRecordedEvent.java`、`EvalServiceImpl.java`、`rule-eval-svc/src/test/resources/application-test.yml`
- Test: 该模块既有全量测试

- [ ] **Step 1：建迁移 V1_17**

`V1_17__eval_enum_columns_to_varchar.sql`：

```sql
-- eval 模块 ENUM 列 → VARCHAR：取值真相源上移 app 层 Java enum（按 name 与列往返）。
-- source 取值 == kernel EventSource；status 取值 == SessionStatus；mode == EvalMode；
-- action_execution.status 取值 == ActionResult.ActionStatus（写路径仅 SUCCESS/FAILED/SKIPPED，
-- 列保留 PENDING/RETRYING 容 DEFAULT 与未来写者）；dry_run_session.trigger 无实体字段，纯列改型。
ALTER TABLE evaluation_session
  MODIFY COLUMN source VARCHAR(16) NOT NULL DEFAULT 'HTTP',
  MODIFY COLUMN mode   VARCHAR(8)  NOT NULL DEFAULT 'PULL',
  MODIFY COLUMN status VARCHAR(16) NOT NULL DEFAULT 'PENDING';

ALTER TABLE action_execution
  MODIFY COLUMN status VARCHAR(16) NOT NULL DEFAULT 'PENDING';

ALTER TABLE dry_run_session
  MODIFY COLUMN status    VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  MODIFY COLUMN `trigger` VARCHAR(16) NOT NULL DEFAULT 'API';
```

- [ ] **Step 2：建两个新枚举**

`SessionStatus.java`：
```java
package com.sstlfsj.rule.eval.internal.domain;

/** evaluation_session / dry_run_session 的 status 取值（D22 第四态含 BLOCKED）。 */
public enum SessionStatus { PENDING, HIT, MISS, BLOCKED, ERROR, FAILED }
```

`EvalMode.java`：
```java
package com.sstlfsj.rule.eval.internal.domain;

/** 评估模式：PUSH=异步派发路径 / PULL=同步返回路径。 */
public enum EvalMode { PUSH, PULL }
```

- [ ] **Step 3：改实体字段**

- `EvaluationSession.java`：`source` String→`com.sstlfsj.rule.kernel.api.model.EventSource`、`mode` String→`EvalMode`、`status` String→`SessionStatus`。
- `DryRunSession.java`：`status` String→`SessionStatus`。
- `ActionExecutionEntity.java`：`status` String→`com.sstlfsj.rule.kernel.api.model.ActionResult.ActionStatus`。

- [ ] **Step 4：穿 EvalMode 过 doEvaluate / AuditRecordedEvent**

- `AuditRecordedEvent.java`：record 第三参 `String mode` → `EvalMode mode`（Javadoc 同步）。
- `EvalServiceImpl.java`：
  - `doEvaluate(RuleEvent event, String mode, ...)` 签名 → `EvalMode mode`。
  - 三个调用点字面量替换：`doEvaluate(e, "PUSH", false, null)`→`EvalMode.PUSH`；两处 `doEvaluate(event, "PULL", ...)`→`EvalMode.PULL`。
  - `new AuditRecordedEvent(sessionId, event, mode, ...)` 现传 `EvalMode`，无需改动。

- [ ] **Step 5：改 persister set 点**

- `AuditPersister.toSession(...)`：
  - `s.setSource(ev.source().name())` → `s.setSource(ev.source())`（`ev.source()` 即 kernel `EventSource`）。
  - `s.setMode(e.mode())`（`e.mode()` 现为 `EvalMode`，直接传）。
  - status 三元表达式字面量改枚举：
    ```java
    s.setStatus(r.errorCode() != null ? SessionStatus.ERROR
            : e.blockedBy() != null ? SessionStatus.BLOCKED
            : (r.ruleHit() ? SessionStatus.HIT : SessionStatus.MISS));
    ```
- `DryRunPersister.accept(...)`：
  ```java
  s.setStatus(r.errorCode() != null ? SessionStatus.ERROR
          : (r.ruleHit() ? SessionStatus.HIT : SessionStatus.MISS));
  ```
- `ActionExecutionPersister.toEntity(...)`：`entity.setStatus(e.result().status().name())` → `entity.setStatus(e.result().status())`（`e.result().status()` 即 `ActionResult.ActionStatus`）。

- [ ] **Step 6：测试配置加 enum handler**

`rule-eval-svc/src/test/resources/application-test.yml` 的 `mybatis-plus.configuration` 下补：
```yaml
    default-enum-type-handler: com.baomidou.mybatisplus.core.handlers.MybatisEnumTypeHandler
```
（与 `rule-config-svc/src/test/resources/application-test.yml:15` 同样位置/同样值。）

- [ ] **Step 7：跑 eval 模块全量测试**

Run: `$MVN -pl rule-eval-svc -am test`
Expected: BUILD SUCCESS。关注涉及 `AuditPersister`/`DryRunPersister`/`ActionExecutionPersister`/`EvalServiceImpl` 的用例；若测试里断言 `setStatus("HIT")` 或构造 `AuditRecordedEvent(..., "PUSH", ...)`，改成枚举入参（方法名英文、注释中文）。

- [ ] **Step 8：commit**

```bash
git add rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/domain/SessionStatus.java \
        rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/domain/EvalMode.java \
        rule-config-svc/src/main/resources/db/migration/V1_17__eval_enum_columns_to_varchar.sql \
        rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/ \
        rule-eval-svc/src/test/resources/application-test.yml \
        rule-eval-svc/src/test/
git commit -m "refactor(eval): session/action_execution/dry_run 列 ENUM→VARCHAR + 实体真 enum（复用 kernel EventSource/ActionStatus）"
```

---

## Task 4：observability 2 列 → `ValueSource`（迁移 V1_18）

**Files:**
- Create: `rule-config-svc/src/main/resources/db/migration/V1_18__trace_value_source_to_varchar.sql`
- Modify: `NodeTraceEntity.java`、`DryRunNodeTraceEntity.java`、`TraceWriterDbImpl.java`、`DryRunTraceWriterDbImpl.java`
- Test: 该模块既有测试（纯反射/字符串断言，不连库）

- [ ] **Step 1：建迁移 V1_18**

`V1_18__trace_value_source_to_varchar.sql`：

```sql
-- node_trace / dry_run_node_trace 的 value_source ENUM → VARCHAR（可空，取值 == kernel ValueSource）。
ALTER TABLE node_trace
  MODIFY COLUMN value_source VARCHAR(16) NULL;

ALTER TABLE dry_run_node_trace
  MODIFY COLUMN value_source VARCHAR(16) NULL;
```

- [ ] **Step 2：改实体字段**

- `NodeTraceEntity.java`：`private String valueSource;` → `private com.sstlfsj.rule.kernel.api.model.ValueSource valueSource;`
- `DryRunNodeTraceEntity.java`：同上。

- [ ] **Step 3：对齐 set 点类型**

`TraceWriterDbImpl.java:120` 与 `DryRunTraceWriterDbImpl.java:120` 当前为 `entity.setValueSource(trace.valueSource())`。
**先确认** kernel `NodeTrace.valueSource()` 的返回类型：
- 若已是 `ValueSource` 枚举：保持 `entity.setValueSource(trace.valueSource())` 不变。
- 若是 `String`：改为 null 安全解析 `entity.setValueSource(trace.valueSource() == null ? null : ValueSource.valueOf(trace.valueSource()))`。

> `@Insert` 中 `#{e.valueSource}` 由 MyBatis enum handler 落库（运行期在 rule-app 下生效，按 `name()` 写 VARCHAR）。

- [ ] **Step 4：跑 observability 模块全量测试**

Run: `$MVN -pl rule-observability -am test`
Expected: BUILD SUCCESS。该模块 mapper 测试断言的是 `@Insert` SQL 字符串（含 `display_label`/`params`），与 `value_source` 字段类型无关；`NodeTraceEntityTest`/`DryRunNodeTraceEntityTest` 的 getter/setter 往返若用 `setValueSource("FETCHED")`，改为 `ValueSource.FETCHED`。

- [ ] **Step 5：commit**

```bash
git add rule-config-svc/src/main/resources/db/migration/V1_18__trace_value_source_to_varchar.sql \
        rule-observability/src/main/java/com/sstlfsj/rule/observability/internal/ \
        rule-observability/src/test/
git commit -m "refactor(observability): node_trace value_source ENUM→VARCHAR + 实体复用 kernel ValueSource"
```

---

## Task 5：job 模块 2 列 → enum（迁移 V1_19）

**Files:**
- Create: `rule-job-svc/.../internal/domain/JobStatus.java`、`rule-job-svc/.../internal/domain/JobExecutionStatus.java`
- Create: `rule-config-svc/src/main/resources/db/migration/V1_19__job_enum_columns_to_varchar.sql`
- Modify: `JobDefinition.java`、`JobExecution.java`、`JobServiceImpl.java`、`RuleJobScanner.java`、`JobRunner.java`、`rule-job-svc/src/test/resources/application-test.yml`
- Test: 该模块既有全量测试

- [ ] **Step 1：建迁移 V1_19**

`V1_19__job_enum_columns_to_varchar.sql`：

```sql
-- job_definition / job_execution 的 status ENUM → VARCHAR（取值真相源上移 app 层 Java enum）。
ALTER TABLE job_definition
  MODIFY COLUMN status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE';

ALTER TABLE job_execution
  MODIFY COLUMN status VARCHAR(16) NOT NULL DEFAULT 'RUNNING';
```

- [ ] **Step 2：建两个新枚举**

`JobStatus.java`：
```java
package com.sstlfsj.rule.job.internal.domain;

/** job_definition.status 取值。 */
public enum JobStatus { ACTIVE, DISABLED }
```

`JobExecutionStatus.java`：
```java
package com.sstlfsj.rule.job.internal.domain;

/** job_execution.status 取值：单次 Job 运行终态（PARTIAL_FAIL=部分主体失败）。 */
public enum JobExecutionStatus { RUNNING, SUCCESS, PARTIAL_FAIL, FAILED }
```

- [ ] **Step 3：改实体字段**

- `JobDefinition.java`：`private String status;` → `private JobStatus status;`
- `JobExecution.java`：`private String status;` → `private JobExecutionStatus status;`

- [ ] **Step 4：改写入点字面量 → enum**

- `JobServiceImpl.java`：`def.setStatus("ACTIVE")`→`JobStatus.ACTIVE`；`def.setStatus("DISABLED")`→`JobStatus.DISABLED`。
- `RuleJobScanner.java`：两处 `setStatus("ACTIVE")`→`JobStatus.ACTIVE`。
- `JobRunner.java`：
  - `exec.setStatus("RUNNING")`→`JobExecutionStatus.RUNNING`；
  - `exec.setStatus(counters[2] == 0 ? "SUCCESS" : (counters[1] > 0 ? "PARTIAL_FAIL" : "FAILED"))` →
    ```java
    exec.setStatus(counters[2] == 0 ? JobExecutionStatus.SUCCESS
            : (counters[1] > 0 ? JobExecutionStatus.PARTIAL_FAIL : JobExecutionStatus.FAILED));
    ```
  - `exec.setStatus("FAILED")`→`JobExecutionStatus.FAILED`。
- `JobExecutionVO`/对外 DTO 的 `status` 字段保持 String：映射处 `.name()`。

- [ ] **Step 5：测试配置加 enum handler**

`rule-job-svc/src/test/resources/application-test.yml` 的 `mybatis-plus.configuration` 下补：
```yaml
    default-enum-type-handler: com.baomidou.mybatisplus.core.handlers.MybatisEnumTypeHandler
```

- [ ] **Step 6：跑 job 模块全量测试**

Run: `$MVN -pl rule-job-svc -am test`
Expected: BUILD SUCCESS。若 `JobRunner`/`JobServiceImpl` 测试断言 `setStatus("RUNNING")` 或读 `getStatus()` 字符串，改为枚举（方法名英文、注释中文）。

- [ ] **Step 7：commit**

```bash
git add rule-job-svc/src/main/java/com/sstlfsj/rule/job/internal/domain/JobStatus.java \
        rule-job-svc/src/main/java/com/sstlfsj/rule/job/internal/domain/JobExecutionStatus.java \
        rule-config-svc/src/main/resources/db/migration/V1_19__job_enum_columns_to_varchar.sql \
        rule-job-svc/src/main/java/com/sstlfsj/rule/job/internal/ \
        rule-job-svc/src/test/resources/application-test.yml \
        rule-job-svc/src/test/
git commit -m "refactor(job): job_definition/job_execution status ENUM→VARCHAR + 实体真 enum"
```

---

## Task 6：文档对齐 + 决策日志 + 全量兜底

**Files:**
- Modify: `docs/00-decisions.md`（追加一条决策）
- Modify: `docs/05-storage.md`（受影响列的 DDL 由 `ENUM(...)` 改 `VARCHAR(...)`）
- Test: 全量 `clean test`

- [ ] **Step 1：追加决策日志（00-decisions.md，新条目，不改历史）**

追加一条（编号取当前最大 +1，如 `D51`）：
> **D51 剩余 DB ENUM 列全面 VARCHAR 化（R10）**：在 D（V1_11 metric）、V1_15（rule/scene status）之后，将剩余全部 `ENUM` 列（tenant/decision/scene.dominant_mode·decision_strategy·subject_type、rule_*.kind、audit_log.actor_type、evaluation_session·dry_run_session·action_execution 各状态/模式/来源、node_trace·dry_run_node_trace.value_source、job_definition·job_execution.status）改 `VARCHAR`，取值真相源统一在 app 层 Java enum，按 `name()` 与列往返；契约边界 `.name()` 保持 String。封闭取值复用 kernel `SubjectType`(+CUSTOM)/`RuleKind`/`EventSource`/`ValueSource`/`ActionResult.ActionStatus`。理由同 V1_11：ENUM 加值需 ALTER + 双重定义，VARCHAR 后单一真相源、增删枚举项零迁移风险。

- [ ] **Step 2：更新 05-storage.md DDL 描述**

把上述列在 `docs/05-storage.md` 中的 `ENUM(...)` 改为 `VARCHAR(n)`（与各迁移 SQL 的长度/DEFAULT 一致）。改前按 CLAUDE.md 文档纪律跑 `doc-consistency-review` skill 扫自洽。

- [ ] **Step 3：commit 文档**

```bash
git add docs/00-decisions.md docs/05-storage.md
git commit -m "docs(storage): 剩余 ENUM 列全面 VARCHAR 化（D51 + 05-storage DDL 对齐）"
```

- [ ] **Step 4：全量兜底（stale-jar 防线）**

Run: `$MVN clean test`（无 `-pl`，强制重编全部 test 类）
Expected: Reactor 全部模块 BUILD SUCCESS。
- 若出现 `NoSuchMethodError`/找不到枚举类：多为漏带 `-am` 的旧 jar 假象，`clean` 后应消失；仍报错则定位真实漏改点（通常是某模块 setter 入参类型未跟随）。

---

## Self-Review（计划自检）

**1. 列覆盖：** 18 列逐一映射到 Task（kernel CUSTOM=T1；config 8 列=T2；eval 6 列=T3；observability 2 列=T4；job 2 列=T5），与"总清单"表一一对应，无遗漏。已排除的列（metric source_type/data_type/status、rule/scene status）在计划开头注明属 V1_11/V1_15 既成。

**2. 占位扫描：** 每个枚举给了完整源码；每条迁移给了完整 SQL；每个 set/边界点给了具体文件+变换。唯一一处"先确认再二选一"是 T4 Step3 的 `trace.valueSource()` 返回类型——属持久层边界类型核对（两种走法都给了具体代码），非占位。

**3. 类型一致性：** `SessionStatus` 在 evaluation_session 与 dry_run_session 复用同一枚举（两列取值集合相同）；`EvalMode`/`EventSource` 区分清楚（mode=PUSH/PULL，source=HTTP/MQ/JOB/SDK/REPLAY）；`ActionResult.ActionStatus`(3 值) 与列允许的 5 值差异已在 SQL 注释说明（写路径只产 3 值，列容 DEFAULT/未来）；kind 用 kernel `RuleKind`（`tag()==name()`，迁移注释已记）。迁移版本 V1_16→V1_19 顺序与 Task 顺序一致，单一 Flyway 目录无冲突。

**4. 跨模块测试链路：** eval/job 测试经 `classpath:db/migration` 共享 config-svc 迁移（已验证 `flyway.locations: classpath:db/migration`）；observability 测试不连库（纯反射/字符串断言），故不需其测试配置改动；新增 enum handler 仅 eval/job 测试配置（config 已有、运行期 rule-app 已有）。
