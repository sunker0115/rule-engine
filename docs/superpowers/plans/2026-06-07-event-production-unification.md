# 统一 RuleEvent 产生路径 + Job 注解化收尾 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: 用 superpowers:subagent-driven-development（推荐）或 executing-plans 逐 Task 实现。Steps 用 checkbox（`- [ ]`）跟踪。

**Goal:** 让 HTTP / Job / SDK 三条路径统一经 `RuleEvent.builder` 构造，`source`（渠道）由注入入口权威设置，`mode`(PUSH/PULL) 由 EvalService 入口判定写 session，`evaluation_session` 拆 source/mode 两列；同时把 job 收尾为注解驱动（`@RuleJob` + `JobTarget`）。

**Architecture:** kernel 加 `EventSource` 枚举 + `RuleEvent.source` 字段 + Lombok `@Builder`（构造唯一入口）。eval-svc 把 `mode` 作为 `doEvaluate` 入参（acceptEvent=PUSH / evaluate=PULL），`session.source` 取自 `event.source()`、`session.mode` 取自入口。三路径在各自入口用 builder 补 source。job 主体查询方法返回 `List<JobTarget>`。

**Tech Stack:** Java 25、Spring Boot 4、Spring Modulith、MyBatis-Plus、Flyway、Lombok、JUnit5 + Mockito + Testcontainers。

**本机跑测试前置：** 先用 `mvn-env` skill 设 `$MVN`，再 `$MVN -pl <module> -am test`。每个 Task 提交前该模块测试全绿，不 `-DskipTests`。

**当前工作区前提：** 上一波 job 改动已在工作区未提交（`@RuleJob`/`RuleJobScanner`/`BeanMethodRegistry`/`BeanMethodSubjectQueryRunner`/`DemoFraudJob`、砍 SQL、去 createJob、半成品 `Subject`）。本计划 Task 5 把 `Subject` 重构为 `JobTarget` 并接入统一 builder，与统一改动一并提交。

---

## File Structure

**rule-kernel**
- 新建 `api/model/EventSource.java` — 渠道枚举。
- 改 `api/model/RuleEvent.java` — 加 `source` 字段 + `@Builder` + compact constructor 校验/缺省。
- 改 `pom.xml` — 引 Lombok（编译期）。

**rule-eval-svc**
- 改 `internal/service/EvalServiceImpl.java` — `doEvaluate` 加 `mode` 入参；source 取 `event.source()`。
- 改 `internal/session/EvalSessionWriter.java` — `insertPending` 接收 source(渠道)+mode。
- 改 `internal/domain/EvaluationSession.java` — 加 `mode` 字段。
- 新建迁移 `rule-config-svc/src/main/resources/db/migration/V1_8__session_source_mode.sql`。

**rule-api**
- 改 `web/api/EvalController.java` — 绑无 source 请求体 → builder 补 `source=HTTP`。
- 新建 `web/api/dto/EvalEventRequest.java` — HTTP 请求体（无 source）。

**rule-job-svc**
- 新建 `api/JobTarget.java` — 主体素材（subjectId + payload + providedMetrics）。
- 删 `api/Subject.java` + `internal/runner/PayloadTemplateRenderer.java`。
- 改 `internal/subject/{SubjectQueryRunner,BeanMethodRegistry,BeanMethodSubjectQueryRunner}.java` — 用 `JobTarget`。
- 改 `internal/runner/JobRunner.java` — 用 `JobTarget` + builder `source=JOB`。
- 改 `internal/example/DemoFraudJob.java` — 返回 `List<JobTarget>`。

**rule-sdk**
- 改 `RuleEngineClient.java` — 构造 RuleEvent 处补 `source=SDK`（或经 builder）。

**文档** — `01-concepts`、`05-storage`、`09-skeleton`、`00-decisions`(D49)。

---

## 任务依赖

```
Task 1 (kernel: EventSource + RuleEvent.source + @Builder) ─→ 其余全部
Task 2 (eval: session 拆列 + V1_8) ─→ Task 3
Task 3 (eval: EvalServiceImpl mode 入参 + source) ─→ Task 4,5,7
Task 4 (HTTP) ─┐
Task 5 (Job: JobTarget) ─┼─→ Task 7 (文档 + 回归)
Task 6 (SDK) ─┘
```
顺序：1 → 2 → 3 → 4 → 5 → 6 → 7。

---

### Task 1: kernel — EventSource + RuleEvent.source + @Builder

**Files:**
- Create: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/EventSource.java`
- Modify: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/RuleEvent.java`
- Modify: `rule-kernel/pom.xml`（引 Lombok）
- Test: `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/api/model/RuleEventTest.java`

- [ ] **Step 1: 引 Lombok 到 kernel pom**

在 `rule-kernel/pom.xml` 的 `<dependencies>` 加（root dependencyManagement 已管 lombok 版本 + optional）：
```xml
<dependency>
    <groupId>org.projectlombok</groupId>
    <artifactId>lombok</artifactId>
</dependency>
```

- [ ] **Step 2: 写 EventSource 枚举**

```java
package com.sstlfsj.rule.kernel.api.model;

/** RuleEvent 渠道：事件从哪来。由注入入口权威设置，不信外部。 */
public enum EventSource {
    HTTP, MQ, JOB, SDK, REPLAY
}
```

- [ ] **Step 3: 写失败测试（RuleEvent builder + 校验 + 缺省）**

`RuleEventTest.java`:
```java
package com.sstlfsj.rule.kernel.api.model;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class RuleEventTest {
    @Test
    void builderMinimalFillsDefaults() {
        RuleEvent e = RuleEvent.builder()
                .tenantId("1").sceneCode("s").eventType("login")
                .subjectId("u1").eventId("evt-1").source(EventSource.HTTP)
                .build();
        assertThat(e.source()).isEqualTo(EventSource.HTTP);
        assertThat(e.occurredAt()).isNotNull();      // 缺省 now
        assertThat(e.payload()).isEmpty();            // 缺省空
        assertThat(e.providedMetrics()).isEmpty();
    }

    @Test
    void builderCarriesPayloadAndMetrics() {
        RuleEvent e = RuleEvent.builder()
                .tenantId("1").sceneCode("s").eventType("login")
                .subjectId("u1").eventId("evt-1").source(EventSource.JOB)
                .payload(Map.of("k", "v")).providedMetrics(Map.of("fts", 0.8))
                .occurredAt(Instant.now()).build();
        assertThat(e.payload()).containsEntry("k", "v");
        assertThat(e.providedMetrics()).containsEntry("fts", 0.8);
    }

    @Test
    void rejectsNullSource() {
        assertThatThrownBy(() -> RuleEvent.builder()
                .tenantId("1").sceneCode("s").eventType("login")
                .subjectId("u1").eventId("evt-1").source(null).build())
                .isInstanceOf(NullPointerException.class);
    }
}
```

- [ ] **Step 4: 跑测试看失败**

Run: `$MVN -pl rule-kernel test -Dtest=RuleEventTest`
Expected: 编译失败（RuleEvent 无 builder / 无 source）。

- [ ] **Step 5: 改 RuleEvent 加 source + @Builder**

```java
package com.sstlfsj.rule.kernel.api.model;

import lombok.Builder;
import java.time.Instant;
import java.util.Map;

/** 触发规则评估的业务事件，eventId 用于幂等校验；source 为渠道（入口权威设置）。 */
@Builder
public record RuleEvent(
        String tenantId,
        String sceneCode,
        String eventType,
        String subjectId,
        String eventId,
        Instant occurredAt,
        Map<String, Object> payload,
        Map<String, Object> providedMetrics,
        EventSource source
) {
    public RuleEvent {
        java.util.Objects.requireNonNull(source, "RuleEvent.source 不能为空");
        occurredAt = occurredAt == null ? Instant.now() : occurredAt;
        payload = payload == null ? Map.of() : Map.copyOf(payload);
        providedMetrics = providedMetrics == null ? Map.of() : Map.copyOf(providedMetrics);
    }
}
```

- [ ] **Step 6: 跑测试看通过**

Run: `$MVN -pl rule-kernel test -Dtest=RuleEventTest`
Expected: PASS（3 个）。

- [ ] **Step 7: 修复 kernel 内其余 new RuleEvent 编译点**

Run: `grep -rn "new RuleEvent(" rule-kernel/src --include="*.java" | grep -v target`
逐处改为 `RuleEvent.builder()...source(...).build()`（kernel 测试里的事件按语义补 source，一般 `EventSource.HTTP` 或测试无所谓的用 `HTTP`）。

- [ ] **Step 8: kernel 全量测试 + 提交**

Run: `$MVN -pl rule-kernel test`
Expected: 全绿。
```bash
git add rule-kernel/pom.xml rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/EventSource.java rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/RuleEvent.java rule-kernel/src/test/java/com/sstlfsj/rule/kernel/api/model/RuleEventTest.java
git commit -m "feat(kernel): RuleEvent 加 source 渠道字段 + @Builder（引 Lombok）"
```

---

### Task 2: eval-svc — session 拆 source/mode 列 + V1_8 迁移

**Files:**
- Create: `rule-config-svc/src/main/resources/db/migration/V1_8__session_source_mode.sql`
- Modify: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/domain/EvaluationSession.java`
- 复制迁移到 `rule-eval-svc/src/test/resources/db/migration/`（若该模块测试资源有迁移副本）

- [ ] **Step 1: 写 V1_8 迁移**

先看现有 evaluation_session 的 source 列定义：
Run: `grep -n "source" rule-config-svc/src/main/resources/db/migration/V1_0__init_schema.sql | head`

`V1_8__session_source_mode.sql`（greenfield 无数据，直接改列；下方 ENUM 以实际 V1_0 定义为准微调）：
```sql
-- 渠道(source) 与 模式(mode) 拆分：source 改为渠道枚举，新增 mode 列
ALTER TABLE evaluation_session
    MODIFY COLUMN source ENUM('HTTP','MQ','JOB','SDK','REPLAY') NOT NULL,
    ADD COLUMN mode ENUM('PUSH','PULL') NOT NULL DEFAULT 'PULL' AFTER source;
```

- [ ] **Step 2: EvaluationSession 实体加 mode**

在 `EvaluationSession.java` 加字段（Lombok @Getter/@Setter 实体）：
```java
private String mode;
```

- [ ] **Step 3: 编译 + 提交（迁移与实体先行，下一 Task 写入逻辑）**

Run: `$MVN -pl rule-eval-svc -am compile`
Expected: 通过。
```bash
git add rule-config-svc/src/main/resources/db/migration/V1_8__session_source_mode.sql rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/domain/EvaluationSession.java
git commit -m "feat(eval): evaluation_session 拆 source(渠道)/mode(模式) 列 + V1_8"
```

---

### Task 3: eval-svc — EvalServiceImpl mode 入参 + source 取 event.source

**Files:**
- Modify: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/service/EvalServiceImpl.java`
- Modify: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/session/EvalSessionWriter.java`
- Test: `rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/integration/EvalIntegrationTest.java`

- [ ] **Step 1: EvalSessionWriter.insertPending 接收渠道+mode**

把 `insertPending(RuleEvent event, int n, String source)` 改为 `insertPending(RuleEvent event, int n, String mode)`，`buildSession` 内 `s.setSource(event.source().name())`、`s.setMode(mode)`。即 source 取自 event，mode 由调用方传。

- [ ] **Step 2: EvalServiceImpl.doEvaluate 加 mode 入参**

```java
private EvalResult doEvaluate(RuleEvent event, String mode, boolean isDryRun, Long specificVersionId) {
    ...
    Long sessionId = sessionWriter.insertPending(event, candidates.size(), mode);  // mode 传入，source 从 event
    ...
}

@Override public boolean acceptEvent(RuleEvent event) { return dispatcher.submit(event); }
@Override public EvalResult evaluate(RuleEvent event) { return doEvaluate(event, "PULL", false, null); }
@Override public EvalResult dryRun(RuleEvent event, Long id) { return doEvaluate(event, "PULL", true, id); }
```
dispatcher 回调改为 PUSH：构造处 `new EvalActionDispatcher(10000, e -> doEvaluate(e, "PUSH", false, null))`（替掉 `this::evaluate`）。

- [ ] **Step 3: 改 EvalIntegrationTest 的事件构造 + 加 source/mode 断言**

`makeEvent` 改用 `RuleEvent.builder()...source(EventSource.HTTP).build()`；新增断言：`evaluate` 后 session.mode=PULL、source=HTTP；`acceptEvent` 后 session.mode=PUSH。

- [ ] **Step 4: 跑 eval-svc 测试**

Run: `$MVN -pl rule-eval-svc -am test`（Docker 在则含集成测试）
Expected: 全绿；session 正确记 source=event.source、mode=入口。

- [ ] **Step 5: 提交**

```bash
git add rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/service/EvalServiceImpl.java rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/session/EvalSessionWriter.java rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/integration/EvalIntegrationTest.java
git commit -m "feat(eval): source 取 event.source、mode 入口判定（PUSH/PULL）写 session"
```

---

### Task 4: HTTP — EvalController 经 builder 补 source=HTTP

**Files:**
- Create: `rule-api/src/main/java/com/sstlfsj/rule/web/api/dto/EvalEventRequest.java`
- Modify: `rule-api/src/main/java/com/sstlfsj/rule/web/api/EvalController.java`
- Test: `rule-api/src/test/java/com/sstlfsj/rule/web/api/EvalControllerTest.java`

- [ ] **Step 1: 写 EvalEventRequest（无 source）**

```java
package com.sstlfsj.rule.web.api.dto;

import java.time.Instant;
import java.util.Map;

/** HTTP 评估请求体；source 由 controller 权威设为 HTTP，不接收外部 source。 */
public record EvalEventRequest(
        String tenantId, String sceneCode, String eventType, String subjectId,
        String eventId, Instant occurredAt,
        Map<String, Object> payload, Map<String, Object> providedMetrics
) {}
```

- [ ] **Step 2: EvalController 用 builder 补 source=HTTP**

```java
private RuleEvent toEvent(EvalEventRequest r) {
    return RuleEvent.builder()
            .tenantId(r.tenantId()).sceneCode(r.sceneCode()).eventType(r.eventType())
            .subjectId(r.subjectId()).eventId(r.eventId()).occurredAt(r.occurredAt())
            .payload(r.payload()).providedMetrics(r.providedMetrics())
            .source(EventSource.HTTP)
            .build();
}
```
三端点 `@RequestBody EvalEventRequest req` → `toEvent(req)` → acceptEvent/evaluate/dryRun。

- [ ] **Step 3: 改 EvalControllerTest（请求体改 EvalEventRequest 形状，验证注入的 event.source=HTTP）**

用 ArgumentCaptor 捕获 `evalService.acceptEvent`，断言 `event.source()==EventSource.HTTP`。

- [ ] **Step 4: 跑 rule-api 测试 + 提交**

Run: `$MVN -pl rule-api -am test -Dtest=EvalControllerTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 全绿。
```bash
git add rule-api/src/main/java/com/sstlfsj/rule/web/api/dto/EvalEventRequest.java rule-api/src/main/java/com/sstlfsj/rule/web/api/EvalController.java rule-api/src/test/java/com/sstlfsj/rule/web/api/EvalControllerTest.java
git commit -m "feat(api): EvalController 经 builder 补 source=HTTP，不接收外部 source"
```

---

### Task 5: Job — Subject→JobTarget + JobRunner source=JOB + 删 PayloadTemplateRenderer

**Files:**
- Create: `rule-job-svc/src/main/java/com/sstlfsj/rule/job/api/JobTarget.java`
- Delete: `rule-job-svc/src/main/java/com/sstlfsj/rule/job/api/Subject.java`、`internal/runner/PayloadTemplateRenderer.java`、`src/test/.../SubjectTest.java`、`internal/runner/PayloadTemplateRendererTest.java`
- Modify: `internal/subject/SubjectQueryRunner.java`、`BeanMethodRegistry.java`、`BeanMethodSubjectQueryRunner.java`、`internal/runner/JobRunner.java`、`internal/example/DemoFraudJob.java`

- [ ] **Step 1: 写 JobTarget（替代 Subject）**

```java
package com.sstlfsj.rule.job.api;

import java.util.Map;

/**
 * @RuleJob 业务查询方法的返回元素：合成 RuleEvent 的素材。
 *
 * @param subjectId       主体标识，不可空
 * @param payload         进 RuleEvent.payload，可空
 * @param providedMetrics 进 RuleEvent.providedMetrics（预提供值，引擎优先用），可空
 */
public record JobTarget(String subjectId, Map<String, Object> payload, Map<String, Object> providedMetrics) {
    public JobTarget {
        if (subjectId == null || subjectId.isBlank()) {
            throw new IllegalArgumentException("JobTarget.subjectId 不能为空");
        }
        payload = payload == null ? Map.of() : Map.copyOf(payload);
        providedMetrics = providedMetrics == null ? Map.of() : Map.copyOf(providedMetrics);
    }
    public static JobTarget of(String subjectId) { return new JobTarget(subjectId, Map.of(), Map.of()); }
    public static JobTarget of(String subjectId, Map<String, Object> payload) { return new JobTarget(subjectId, payload, Map.of()); }
    public JobTarget withProvidedMetrics(Map<String, Object> m) { return new JobTarget(subjectId, payload, m); }
}
```

- [ ] **Step 2: 删 Subject + PayloadTemplateRenderer + 其测试**

```bash
git rm rule-job-svc/src/main/java/com/sstlfsj/rule/job/api/Subject.java \
  rule-job-svc/src/main/java/com/sstlfsj/rule/job/internal/runner/PayloadTemplateRenderer.java \
  rule-job-svc/src/test/java/com/sstlfsj/rule/job/api/SubjectTest.java \
  rule-job-svc/src/test/java/com/sstlfsj/rule/job/internal/runner/PayloadTemplateRendererTest.java
```

- [ ] **Step 3: 链路类型 Subject→JobTarget**

- `SubjectQueryRunner.query` 返回 `List<JobTarget>`。
- `BeanMethodRegistry.invoke` 返回 `List<JobTarget>`。
- `BeanMethodSubjectQueryRunner.query` 返回 `List<JobTarget>`（其余不变）。

- [ ] **Step 4: JobRunner 用 JobTarget + builder source=JOB**

合成事件改：
```java
for (JobTarget target : targets) {
    String subjectId = target.subjectId();
    String eventId = EventIdHasher.hash(jobRunId, subjectId);
    RuleEvent event = RuleEvent.builder()
            .tenantId(tenantId).sceneCode(def.getSceneCode()).eventType(def.getEventType())
            .subjectId(subjectId).eventId(eventId)
            .payload(target.payload()).providedMetrics(target.providedMetrics())
            .source(EventSource.JOB)
            .build();
    if (evalService.acceptEvent(event)) success++; else { ... }
}
```
删去 `PayloadTemplateRenderer` 注入与 `render(...)` 调用。

- [ ] **Step 5: DemoFraudJob 返回 List<JobTarget>**

```java
@RuleJob(code = "demo-daily", cron = "0 0 3 * * *", tenant = "1",
        scene = "fraud_check", eventType = "login", name = "演示每日欺诈扫描")
public List<JobTarget> recentLoginUsers() {
    return List.of(JobTarget.of("user-001"), JobTarget.of("user-002"));
}
```

- [ ] **Step 6: 改 job 测试用 JobTarget**

- `BeanMethodRegistryTest`：Probe.users() 返回 `List<JobTarget>`，断言 `get(0).subjectId()`。
- `BeanMethodSubjectQueryRunnerTest`：mock `registry.invoke` 返回 `List<JobTarget>`。
- `JobRunnerTest`：`subjectQueryRunner.query` mock 返回 `List<JobTarget>`；ArgumentCaptor 断言合成事件 `source()==EventSource.JOB`、payload/providedMetrics 透传。
- `JobAnnotationIntegrationTest`：`AnnotatedFraudJob.subjects()` 返回 `List<JobTarget>`；断言 session.source=JOB。
- 新建 `JobTargetTest`：of/of+payload/withProvidedMetrics/空 id 拒绝。

- [ ] **Step 7: 跑 job-svc 全量 + 提交**

Run: `$MVN -pl rule-job-svc -am test`
Expected: 全绿（含两个 Testcontainers 集成测试）。
```bash
git add rule-job-svc
git commit -m "feat(job): Subject→JobTarget（带 payload/providedMetrics）+ builder 补 source=JOB，删 PayloadTemplateRenderer"
```

---

### Task 6: SDK — RuleEngineClient 补 source=SDK

**Files:**
- Modify: `rule-sdk/src/main/java/com/sstlfsj/rule/sdk/RuleEngineClient.java`
- Test: `rule-sdk/src/test/java/...`（若有现成 client 测试，补 source 断言）

- [ ] **Step 1: 定位 RuleEngineClient 构造/接收 RuleEvent 处**

Run: `grep -n "RuleEvent\|evaluate" rule-sdk/src/main/java/com/sstlfsj/rule/sdk/RuleEngineClient.java`

- [ ] **Step 2: 入口处确保 source=SDK**

若 client 接收调用方传入的 RuleEvent，则在 `evaluate(event)` 入口用 builder 重建补 `source=EventSource.SDK`（或要求调用方经 client 提供的工厂）。本地 `evalEngine.evaluate` 不写 session，source 仅作渠道标识贯穿。

- [ ] **Step 3: 跑 sdk 测试 + 提交**

Run: `$MVN -pl rule-sdk -am test`
Expected: 全绿。
```bash
git add rule-sdk
git commit -m "feat(sdk): RuleEngineClient 构造事件补 source=SDK"
```

---

### Task 7: 文档对齐 + 全量回归

**Files:** `docs/01-concepts.md`、`docs/05-storage.md`、`docs/09-skeleton.md`、`docs/00-decisions.md`

- [ ] **Step 1: 文档**

- `01-concepts`：RuleEvent 字段表加 `source`（EventSource 渠道）；§3.10 JobDefinition.subjectQuery 已是 BEAN_METHOD（注解 Job 返回 JobTarget）。
- `05-storage`：evaluation_session 加 `source`(渠道 ENUM)/`mode`(PUSH/PULL) 列说明 + V1_8。
- `09-skeleton`：kernel 引 Lombok 注明（编译期，不破坏运行时零依赖/Native）；job 目录树 Subject→JobTarget、去 PayloadTemplateRenderer。
- `00-decisions`：追加 D49（见 spec 决策记录）。

- [ ] **Step 2: 跑 doc-consistency-review skill** 扫四文档自洽。

- [ ] **Step 3: 全量回归**

Run: `$MVN install -DskipTests -q && $MVN -pl rule-kernel,rule-eval-svc,rule-job-svc,rule-api test && $MVN -pl rule-app test -Dtest='KernelArchTest,ModulithStructureTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 全绿。

- [ ] **Step 4: 派 rule-engine-reviewer** 审代码↔文档对齐。

- [ ] **Step 5: 文档提交**

```bash
git add docs/01-concepts.md docs/05-storage.md docs/09-skeleton.md docs/00-decisions.md
git commit -m "docs: 统一 event 产生（RuleEvent.source/session source+mode/D49）+ job 注解化对齐"
```

---

## 验收

- 三路径 source 正确：HTTP→HTTP、Job→JOB、SDK→SDK；`evaluation_session.source` 记真实渠道、`mode` 记 PUSH/PULL（acceptEvent=PUSH、evaluate=PULL）。
- `RuleEvent` 统一经 builder 构造，无散落 `new RuleEvent`（除 builder 内部）。
- job 注解链路：`@RuleJob` 返回 `List<JobTarget>`（带 payload/providedMetrics）→ source=JOB → 真实评估产 session。
- 全量测试绿；文档与代码对齐。
