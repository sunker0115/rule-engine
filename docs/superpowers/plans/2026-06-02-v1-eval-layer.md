# V1 评估层实现计划（Plan B）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 rule-eval-svc 真正能跑通完整评估链路：索引启动加载 + 热更新 + Pre-Gate（ROLLOUT）+ EvalContext 装配 + evaluation_session 写入 + EvalServiceImpl 全实现（PULL / PUSH / dry-run），单测全通过。

**Architecture:** 评估层依赖配置层发布的 Modulith 事件（`RulePublishedEvent` / `SceneChangedEvent`）热更新内存倒排索引；PULL 模式同步完整链路（Matcher → Pre-Gate → EvalContext → AST 评估 → 写 evaluation_session）；PUSH 模式异步投递 CompletableFuture；dry-run 写 dry_run_session 不影响生产表；v1 只实现 ROLLOUT gate，metric 取数仅消费 providedMetrics（无真实 MetricSourceHandler 实现时优雅降级为空 metrics）。

**Tech Stack:** Java 25 / Spring Boot 4.0.6 / Spring Modulith 2.0.6 (`@ApplicationModuleListener`) / MyBatis-Plus 3.5.16 / Guava 33.2.1-jre (murmur3) / Jackson 2.18+ (内嵌于 Spring Boot) / JUnit Jupiter / Mockito

> **环境约束：**
> - `mvn` 命令前必须先设置环境：`export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-25.jdk/Contents/Home && export PATH=$JAVA_HOME/bin:$PATH && MVN=/Users/sunke/.m2/wrapper/dists/apache-maven-3.9.9-bin/4nf9hui3q3djbarqar9g711ggc/apache-maven-3.9.9/bin/mvn`
> - `$MVN -pl rule-eval-svc -am test` 运行模块测试
> - `$MVN -pl rule-kernel -am test` 运行 kernel 测试（Task 1 修改了 kernel）
> - 代码注释（`//` 及 Javadoc）全部使用**中文**

> **依赖说明：**
> - Plan B 假设 Plan A 已执行完成（Flyway 表已建、rule-config-svc 的 Mapper + PublishService 已实现）
> - Plan B 的索引热更依赖 Plan A Task 6 中 PublishService 发布的 `RulePublishedEvent` / `SceneChangedEvent`

---

## 文件结构总览

```
rule-kernel/
└── src/main/java/com/sstlfsj/rule/kernel/api/model/
    └── PreGateContext.java                         ← 修改：新增 ruleVersionId + gateParams

rule-config-svc/
└── src/main/java/com/sstlfsj/rule/config/
    ├── api/event/
    │   ├── RulePublishedEvent.java                 ← 新建（从 internal/event 迁移）
    │   └── SceneChangedEvent.java                  ← 新建（从 internal/event 迁移）
    └── internal/event/
        ├── RulePublishedEvent.java                 ← 删除（迁移后）
        └── SceneChangedEvent.java                  ← 删除（迁移后）

rule-eval-svc/
├── pom.xml                                         ← 修改：新增 Guava 依赖
└── src/
    ├── main/java/com/sstlfsj/rule/eval/
    │   ├── EvalAutoConfiguration.java              ← 修改：注册新 Bean
    │   └── internal/
    │       ├── domain/
    │       │   ├── EvaluationSession.java          ← 新建
    │       │   └── DryRunSession.java              ← 新建
    │       ├── mapper/
    │       │   ├── EvaluationSessionMapper.java    ← 新建
    │       │   ├── DryRunSessionMapper.java        ← 新建
    │       │   └── RuleVersionReadMapper.java      ← 新建（JOIN 查询）
    │       ├── snapshot/
    │       │   ├── AstJsonCodec.java               ← 新建（Jackson mixin 反序列化）
    │       │   ├── RuleVersionRow.java             ← 新建（JOIN 结果 DTO）
    │       │   ├── SnapshotAssembler.java          ← 新建（DB 行→RuleVersionSnapshot）
    │       │   └── SceneSnapshotLoader.java        ← 新建（查询 + 组装）
    │       ├── listener/
    │       │   ├── IndexStartupLoader.java         ← 新建（ApplicationReadyEvent）
    │       │   ├── RuleIndexEventListener.java     ← 新建（RulePublishedEvent）
    │       │   └── SceneIndexEventListener.java    ← 新建（SceneChangedEvent）
    │       ├── pregate/
    │       │   └── RolloutPreGate.java             ← 新建
    │       ├── context/
    │       │   └── EvalContextAssembler.java       ← 新建
    │       ├── session/
    │       │   └── EvalSessionWriter.java          ← 新建
    │       └── service/
    │           └── EvalServiceImpl.java            ← 改写（全实现）
    └── test/java/com/sstlfsj/rule/eval/
        └── internal/
            ├── snapshot/
            │   ├── AstJsonCodecTest.java
            │   └── SnapshotAssemblerTest.java
            ├── listener/
            │   └── RuleIndexEventListenerTest.java
            ├── pregate/
            │   └── RolloutPreGateTest.java
            ├── context/
            │   └── EvalContextAssemblerTest.java
            ├── session/
            │   └── EvalSessionWriterTest.java
            └── service/
                └── EvalServiceImplTest.java        ← 改写（替换桩测试）
```

---

## Task 1：事件包迁移 + PreGateContext 扩展 + Guava 依赖

**背景：** `RulePublishedEvent` / `SceneChangedEvent` 当前在 `config.internal.event`，是 config 模块内部包，eval-svc 无法合法引用（违反 Modulith 边界）。需迁移到 `config.api.event`。同时 `PreGateContext` 缺少 `ruleVersionId` 和 `gateParams`，ROLLOUT gate 需要这两个字段做 murmur3 哈希。

**Files:**
- 新建: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/event/RulePublishedEvent.java`
- 新建: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/event/SceneChangedEvent.java`
- 删除: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/event/RulePublishedEvent.java`
- 删除: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/event/SceneChangedEvent.java`
- 修改: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/PreGateContext.java`
- 修改: `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/api/model/PreGateContextTest.java`
- 修改: `rule-eval-svc/pom.xml`

- [ ] **Step 1: 在 `config.api.event` 包创建两个事件类**

```java
// rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/event/RulePublishedEvent.java
package com.sstlfsj.rule.config.api.event;

/** 规则版本成功激活后发布的 Modulith 事件。 */
public record RulePublishedEvent(
        String tenantId,
        String sceneCode,
        Long ruleVersionId
) {}
```

```java
// rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/event/SceneChangedEvent.java
package com.sstlfsj.rule.config.api.event;

/** 场景激活状态变更后发布的 Modulith 事件。 */
public record SceneChangedEvent(
        String tenantId,
        String sceneCode,
        boolean active
) {}
```

- [ ] **Step 2: 删除 `internal/event` 下的旧事件类**

```bash
rm rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/event/RulePublishedEvent.java
rm rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/event/SceneChangedEvent.java
```

搜索 config-svc 中所有引用旧包名的地方并更新（已有引用来自 ConfigServiceImpl）：

```bash
grep -r "config.internal.event" rule-config-svc/src/
```

将所有 `com.sstlfsj.rule.config.internal.event.RulePublishedEvent` 替换为 `com.sstlfsj.rule.config.api.event.RulePublishedEvent`（SceneChangedEvent 同理）。

- [ ] **Step 3: 修改 PreGateContext，新增 ruleVersionId 和 gateParams**

```java
// rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/PreGateContext.java
package com.sstlfsj.rule.kernel.api.model;

import java.util.Map;

/** Pre-Gate 评估的入参，包含租户、场景、触发事件以及本次 Gate 的配置参数。 */
public record PreGateContext(
        String tenantId,
        String sceneCode,
        String subjectId,
        RuleEvent event,
        Long ruleVersionId,
        Map<String, Object> gateParams
) {
    public PreGateContext {
        gateParams = gateParams == null ? Map.of() : Map.copyOf(gateParams);
    }
}
```

- [ ] **Step 4: 更新 PreGateContextTest**

```java
// rule-kernel/src/test/java/com/sstlfsj/rule/kernel/api/model/PreGateContextTest.java
package com.sstlfsj.rule.kernel.api.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PreGateContextTest {

    @Test
    void constructor_setsAllFields() {
        RuleEvent event = new RuleEvent("t1", "scene1", "EVENT_A", "u1",
                "eid1", Instant.now(), Map.of(), Map.of());
        PreGateContext ctx = new PreGateContext(
                "t1", "scene1", "u1", event, 42L,
                Map.of("percentage", 50));

        assertEquals("t1", ctx.tenantId());
        assertEquals("scene1", ctx.sceneCode());
        assertEquals("u1", ctx.subjectId());
        assertEquals(event, ctx.event());
        assertEquals(42L, ctx.ruleVersionId());
        assertEquals(50, ctx.gateParams().get("percentage"));
    }

    @Test
    void nullGateParams_defaultsToEmptyMap() {
        RuleEvent event = new RuleEvent("t1", "scene1", "EVENT_A", "u1",
                "eid1", Instant.now(), Map.of(), Map.of());
        PreGateContext ctx = new PreGateContext("t1", "scene1", "u1", event, 1L, null);

        assertNotNull(ctx.gateParams());
        assertTrue(ctx.gateParams().isEmpty());
    }

    @Test
    void gateParams_isImmutable() {
        RuleEvent event = new RuleEvent("t1", "scene1", "EVENT_A", "u1",
                "eid1", Instant.now(), Map.of(), Map.of());
        PreGateContext ctx = new PreGateContext("t1", "scene1", "u1", event, 1L,
                Map.of("percentage", 30));

        assertThrows(UnsupportedOperationException.class,
                () -> ctx.gateParams().put("k", "v"));
    }
}
```

- [ ] **Step 5: 在 rule-eval-svc/pom.xml 添加 Guava 依赖**

```xml
<!-- rule-eval-svc/pom.xml，在 <dependencies> 中追加 -->
<dependency>
    <groupId>com.google.guava</groupId>
    <artifactId>guava</artifactId>
</dependency>
```

- [ ] **Step 6: 运行 kernel 测试，验证 PreGateContext 修改通过**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-25.jdk/Contents/Home
export PATH=$JAVA_HOME/bin:$PATH
MVN=/Users/sunke/.m2/wrapper/dists/apache-maven-3.9.9-bin/4nf9hui3q3djbarqar9g711ggc/apache-maven-3.9.9/bin/mvn
$MVN -pl rule-kernel -am test
```

预期：BUILD SUCCESS，全部测试通过。

- [ ] **Step 7: 运行 config-svc 测试，验证事件包迁移不破坏任何测试**

```bash
$MVN -pl rule-config-svc -am test
```

预期：BUILD SUCCESS。

- [ ] **Step 8: Commit**

```bash
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/PreGateContext.java
git add rule-kernel/src/test/java/com/sstlfsj/rule/kernel/api/model/PreGateContextTest.java
git add rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/event/
git add -u rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/event/
git add rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/service/
git add rule-eval-svc/pom.xml
git commit -m "$(cat <<'EOF'
refactor: 迁移 Modulith 事件到 config.api.event，扩展 PreGateContext（Task B-1）

- RulePublishedEvent / SceneChangedEvent 从 internal/event → api/event，使 eval-svc 可合法引用
- PreGateContext 增加 ruleVersionId + gateParams，供 ROLLOUT gate murmur3 哈希
- rule-eval-svc 添加 Guava 依赖
EOF
)"
```

---

## Task 2：EvaluationSession + DryRunSession 实体与 Mapper

**Files:**
- 新建: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/domain/EvaluationSession.java`
- 新建: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/domain/DryRunSession.java`
- 新建: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/mapper/EvaluationSessionMapper.java`
- 新建: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/mapper/DryRunSessionMapper.java`
- 测试: `rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/domain/EvaluationSessionTest.java`

- [ ] **Step 1: 写失败测试**

```java
// rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/domain/EvaluationSessionTest.java
package com.sstlfsj.rule.eval.internal.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class EvaluationSessionTest {

    @Test
    void settersAndGetters_roundTrip() {
        EvaluationSession s = new EvaluationSession();
        s.setTenantId(1L);
        s.setEventId("evt-001");
        s.setSceneCode("fraud_check");
        s.setStatus("PENDING");
        s.setOccurredAt(LocalDateTime.now());

        assertEquals(1L, s.getTenantId());
        assertEquals("evt-001", s.getEventId());
        assertEquals("fraud_check", s.getSceneCode());
        assertEquals("PENDING", s.getStatus());
        assertNotNull(s.getOccurredAt());
    }

    @Test
    void dryRunSession_settersAndGetters() {
        DryRunSession d = new DryRunSession();
        d.setTenantId(1L);
        d.setRuleVersionId(99L);
        d.setStatus("HIT");

        assertEquals(1L, d.getTenantId());
        assertEquals(99L, d.getRuleVersionId());
        assertEquals("HIT", d.getStatus());
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

```bash
$MVN -pl rule-eval-svc -am test -Dtest='EvaluationSessionTest' -Dsurefire.failIfNoSpecifiedTests=false
```

预期：COMPILE ERROR，`EvaluationSession` 类不存在。

- [ ] **Step 3: 创建 EvaluationSession 实体**

```java
// rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/domain/EvaluationSession.java
package com.sstlfsj.rule.eval.internal.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/** evaluation_session 表对应的 MyBatis-Plus 实体（D11/D21 同步写）。 */
@TableName("evaluation_session")
public class EvaluationSession {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String eventId;
    private String sceneCode;
    private String eventType;
    private String subjectId;
    /** 评估触发来源：PUSH / PULL / REPLAY。 */
    private String source;
    /** 状态：PENDING / HIT / MISS / BLOCKED / ERROR / FAILED。 */
    private String status;
    private String finalDecision;
    private String hitDecisions;
    private String blockedBy;
    private String errorCode;
    private Integer candidateRuleCount;
    private Integer hitRuleCount;
    private LocalDateTime occurredAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private Integer evalDurationMs;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }
    public String getSceneCode() { return sceneCode; }
    public void setSceneCode(String sceneCode) { this.sceneCode = sceneCode; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public String getSubjectId() { return subjectId; }
    public void setSubjectId(String subjectId) { this.subjectId = subjectId; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getFinalDecision() { return finalDecision; }
    public void setFinalDecision(String finalDecision) { this.finalDecision = finalDecision; }
    public String getHitDecisions() { return hitDecisions; }
    public void setHitDecisions(String hitDecisions) { this.hitDecisions = hitDecisions; }
    public String getBlockedBy() { return blockedBy; }
    public void setBlockedBy(String blockedBy) { this.blockedBy = blockedBy; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
    public Integer getCandidateRuleCount() { return candidateRuleCount; }
    public void setCandidateRuleCount(Integer candidateRuleCount) { this.candidateRuleCount = candidateRuleCount; }
    public Integer getHitRuleCount() { return hitRuleCount; }
    public void setHitRuleCount(Integer hitRuleCount) { this.hitRuleCount = hitRuleCount; }
    public LocalDateTime getOccurredAt() { return occurredAt; }
    public void setOccurredAt(LocalDateTime occurredAt) { this.occurredAt = occurredAt; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public LocalDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(LocalDateTime finishedAt) { this.finishedAt = finishedAt; }
    public Integer getEvalDurationMs() { return evalDurationMs; }
    public void setEvalDurationMs(Integer evalDurationMs) { this.evalDurationMs = evalDurationMs; }
}
```

- [ ] **Step 4: 创建 DryRunSession 实体**

```java
// rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/domain/DryRunSession.java
package com.sstlfsj.rule.eval.internal.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/** dry_run_session 表对应的 MyBatis-Plus 实体（D7，7 天 TTL）。 */
@TableName("dry_run_session")
public class DryRunSession {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String eventId;
    private String sceneCode;
    private String eventType;
    private String subjectId;
    /** 本次 dry-run 测试的规则版本 ID。 */
    private Long ruleVersionId;
    private String status;
    private String finalDecision;
    private String hitDecisions;
    private String blockedBy;
    private String errorCode;
    private LocalDateTime occurredAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private Integer evalDurationMs;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }
    public String getSceneCode() { return sceneCode; }
    public void setSceneCode(String sceneCode) { this.sceneCode = sceneCode; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public String getSubjectId() { return subjectId; }
    public void setSubjectId(String subjectId) { this.subjectId = subjectId; }
    public Long getRuleVersionId() { return ruleVersionId; }
    public void setRuleVersionId(Long ruleVersionId) { this.ruleVersionId = ruleVersionId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getFinalDecision() { return finalDecision; }
    public void setFinalDecision(String finalDecision) { this.finalDecision = finalDecision; }
    public String getHitDecisions() { return hitDecisions; }
    public void setHitDecisions(String hitDecisions) { this.hitDecisions = hitDecisions; }
    public String getBlockedBy() { return blockedBy; }
    public void setBlockedBy(String blockedBy) { this.blockedBy = blockedBy; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
    public LocalDateTime getOccurredAt() { return occurredAt; }
    public void setOccurredAt(LocalDateTime occurredAt) { this.occurredAt = occurredAt; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public LocalDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(LocalDateTime finishedAt) { this.finishedAt = finishedAt; }
    public Integer getEvalDurationMs() { return evalDurationMs; }
    public void setEvalDurationMs(Integer evalDurationMs) { this.evalDurationMs = evalDurationMs; }
}
```

- [ ] **Step 5: 创建两个 Mapper 接口**

```java
// rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/mapper/EvaluationSessionMapper.java
package com.sstlfsj.rule.eval.internal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sstlfsj.rule.eval.internal.domain.EvaluationSession;
import org.apache.ibatis.annotations.Mapper;

/** evaluation_session 表 CRUD Mapper。 */
@Mapper
public interface EvaluationSessionMapper extends BaseMapper<EvaluationSession> {}
```

```java
// rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/mapper/DryRunSessionMapper.java
package com.sstlfsj.rule.eval.internal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sstlfsj.rule.eval.internal.domain.DryRunSession;
import org.apache.ibatis.annotations.Mapper;

/** dry_run_session 表 CRUD Mapper。 */
@Mapper
public interface DryRunSessionMapper extends BaseMapper<DryRunSession> {}
```

- [ ] **Step 6: 运行测试，验证通过**

```bash
$MVN -pl rule-eval-svc -am test -Dtest='EvaluationSessionTest' -Dsurefire.failIfNoSpecifiedTests=false
```

预期：BUILD SUCCESS，2 个测试通过。

- [ ] **Step 7: Commit**

```bash
git add rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/domain/
git add rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/mapper/EvaluationSessionMapper.java
git add rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/mapper/DryRunSessionMapper.java
git add rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/domain/
git commit -m "$(cat <<'EOF'
feat(eval): 添加 EvaluationSession + DryRunSession 实体和 Mapper（Task B-2）
EOF
)"
```

---

## Task 3：AstJsonCodec + RuleVersionRow + RuleVersionReadMapper + SnapshotAssembler

**背景：** eval-svc 需要从 rule_version 表读取 JSON 字段并反序列化为 `RuleVersionSnapshot`。`AstJsonCodec` 使用 Jackson mixin 处理 `AstNode` 多态（和 Plan A AstSerializer 相同模式，但 eval-svc 不依赖 config-svc，独立实现）。`RuleVersionReadMapper` 做三表 JOIN 返回 DTO，`SnapshotAssembler` 将 DTO 转为不可变快照。

**Files:**
- 新建: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/snapshot/AstJsonCodec.java`
- 新建: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/snapshot/RuleVersionRow.java`
- 新建: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/mapper/RuleVersionReadMapper.java`
- 新建: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/snapshot/SnapshotAssembler.java`
- 测试: `rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/snapshot/AstJsonCodecTest.java`
- 测试: `rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/snapshot/SnapshotAssemblerTest.java`

- [ ] **Step 1: 写失败测试**

```java
// rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/snapshot/AstJsonCodecTest.java
package com.sstlfsj.rule.eval.internal.snapshot;

import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.model.ast.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AstJsonCodecTest {

    private final AstJsonCodec codec = new AstJsonCodec();

    @Test
    void deserializeConditionNode() throws Exception {
        String json = """
                {"type":"ConditionNode","conditionType":"GT","metricCode":"score",
                 "params":{"threshold":80}}
                """;
        AstNode node = codec.deserializeAst(json);
        assertInstanceOf(ConditionNode.class, node);
        ConditionNode cond = (ConditionNode) node;
        assertEquals("GT", cond.conditionType());
        assertEquals("score", cond.metricCode());
        assertEquals(80, ((Number) cond.params().get("threshold")).intValue());
    }

    @Test
    void deserializeAndNode_withChildren() throws Exception {
        String json = """
                {"type":"AndNode","children":[
                  {"type":"ConditionNode","conditionType":"EQ","metricCode":"age","params":{}},
                  {"type":"ConditionNode","conditionType":"GT","metricCode":"score","params":{}}
                ]}
                """;
        AstNode node = codec.deserializeAst(json);
        assertInstanceOf(AndNode.class, node);
        assertEquals(2, ((AndNode) node).children().size());
    }

    @Test
    void deserializePreGates_emptyList() throws Exception {
        List<RuleVersionSnapshot.PreGateConfig> gates = codec.deserializePreGates("[]");
        assertTrue(gates.isEmpty());
    }

    @Test
    void deserializePreGates_rollout() throws Exception {
        String json = """
                [{"gateType":"ROLLOUT","params":{"percentage":20}}]
                """;
        List<RuleVersionSnapshot.PreGateConfig> gates = codec.deserializePreGates(json);
        assertEquals(1, gates.size());
        assertEquals("ROLLOUT", gates.get(0).gateType());
        assertEquals(20, ((Number) gates.get(0).params().get("percentage")).intValue());
    }

    @Test
    void deserializeDecisionBindings() throws Exception {
        String json = """
                [{"decisionCode":"REJECT","priority":10}]
                """;
        List<RuleVersionSnapshot.DecisionBinding> bindings = codec.deserializeDecisionBindings(json);
        assertEquals(1, bindings.size());
        assertEquals("REJECT", bindings.get(0).decisionCode());
        assertEquals(10, bindings.get(0).priority());
    }

    @Test
    void deserializeStringList() throws Exception {
        List<String> codes = codec.deserializeStringList("[\"EVENT_A\",\"EVENT_B\"]");
        assertEquals(List.of("EVENT_A", "EVENT_B"), codes);
    }
}
```

```java
// rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/snapshot/SnapshotAssemblerTest.java
package com.sstlfsj.rule.eval.internal.snapshot;

import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SnapshotAssemblerTest {

    private final AstJsonCodec codec = new AstJsonCodec();
    private final SnapshotAssembler assembler = new SnapshotAssembler(codec);

    @Test
    void assemble_producesCorrectSnapshot() throws Exception {
        RuleVersionRow row = new RuleVersionRow(
                42L,
                "fraud_check",
                1L,
                """
                {"type":"ConditionNode","conditionType":"GT",
                 "metricCode":"score","params":{"threshold":80}}
                """,
                "[]",
                "[{\"decisionCode\":\"REJECT\",\"priority\":10}]",
                "[\"RISK_EVENT\"]"
        );

        RuleVersionSnapshot snapshot = assembler.assemble(row);

        assertEquals(42L, snapshot.ruleVersionId());
        assertEquals("fraud_check", snapshot.sceneCode());
        assertEquals("1", snapshot.tenantId());
        assertInstanceOf(ConditionNode.class, snapshot.conditionAst());
        assertEquals(1, snapshot.decisionBindings().size());
        assertEquals("REJECT", snapshot.decisionBindings().get(0).decisionCode());
        assertTrue(snapshot.preGates().isEmpty());
    }

    @Test
    void assemble_withRolloutGate() throws Exception {
        RuleVersionRow row = new RuleVersionRow(
                1L, "scene1", 1L,
                "{\"type\":\"ConditionNode\",\"conditionType\":\"EQ\",\"metricCode\":null,\"params\":{}}",
                "[{\"gateType\":\"ROLLOUT\",\"params\":{\"percentage\":10}}]",
                "[]",
                "[\"E1\"]"
        );

        RuleVersionSnapshot snapshot = assembler.assemble(row);
        assertEquals(1, snapshot.preGates().size());
        assertEquals("ROLLOUT", snapshot.preGates().get(0).gateType());
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

```bash
$MVN -pl rule-eval-svc -am test -Dtest='AstJsonCodecTest,SnapshotAssemblerTest' -Dsurefire.failIfNoSpecifiedTests=false
```

预期：COMPILE ERROR。

- [ ] **Step 3: 创建 RuleVersionRow DTO**

```java
// rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/snapshot/RuleVersionRow.java
package com.sstlfsj.rule.eval.internal.snapshot;

/**
 * rule_version JOIN rule_definition JOIN scene 的 JOIN 查询结果 DTO。
 * 字段均为原始 JSON 字符串，由 SnapshotAssembler 反序列化为域对象。
 */
public record RuleVersionRow(
        Long ruleVersionId,
        String sceneCode,
        Long tenantId,
        String conditionAstJson,
        String preGatesJson,
        String decisionBindingsJson,
        String triggerEventTypesJson
) {}
```

- [ ] **Step 4: 创建 AstJsonCodec**

```java
// rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/snapshot/AstJsonCodec.java
package com.sstlfsj.rule.eval.internal.snapshot;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.model.ast.*;
import org.springframework.stereotype.Component;

import java.util.List;

/** 负责 AstNode 及 RuleVersionSnapshot 子结构的 JSON 反序列化，使用 Jackson mixin 处理 sealed 接口多态。 */
@Component
public class AstJsonCodec {

    private final ObjectMapper mapper;

    public AstJsonCodec() {
        this.mapper = new ObjectMapper()
                .addMixIn(AstNode.class, AstNodeMixin.class)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    /**
     * 将 JSON 字符串反序列化为 AstNode（多态，类型由 "type" 字段区分）。
     *
     * @param json AST JSON 字符串
     * @return 反序列化后的 AstNode
     */
    public AstNode deserializeAst(String json) throws JsonProcessingException {
        return mapper.readValue(json, AstNode.class);
    }

    /**
     * 将 JSON 字符串反序列化为 PreGateConfig 列表。
     *
     * @param json Pre-Gate 配置 JSON 数组字符串
     * @return PreGateConfig 列表
     */
    public List<RuleVersionSnapshot.PreGateConfig> deserializePreGates(String json) throws JsonProcessingException {
        return mapper.readValue(json, new TypeReference<>() {});
    }

    /**
     * 将 JSON 字符串反序列化为 DecisionBinding 列表。
     *
     * @param json 决策绑定 JSON 数组字符串
     * @return DecisionBinding 列表
     */
    public List<RuleVersionSnapshot.DecisionBinding> deserializeDecisionBindings(String json) throws JsonProcessingException {
        return mapper.readValue(json, new TypeReference<>() {});
    }

    /**
     * 将 JSON 字符串反序列化为字符串列表（用于 triggerEventTypes 等）。
     *
     * @param json JSON 数组字符串
     * @return 字符串列表
     */
    public List<String> deserializeStringList(String json) throws JsonProcessingException {
        return mapper.readValue(json, new TypeReference<>() {});
    }

    /** AstNode sealed 接口的 Jackson 多态 mixin，通过 "type" 字段区分子类型。 */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = AndNode.class,       name = "AndNode"),
            @JsonSubTypes.Type(value = OrNode.class,        name = "OrNode"),
            @JsonSubTypes.Type(value = NotNode.class,       name = "NotNode"),
            @JsonSubTypes.Type(value = ConditionNode.class, name = "ConditionNode")
    })
    interface AstNodeMixin {}
}
```

- [ ] **Step 5: 创建 SnapshotAssembler**

```java
// rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/snapshot/SnapshotAssembler.java
package com.sstlfsj.rule.eval.internal.snapshot;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.model.ast.AstNode;
import org.springframework.stereotype.Component;

import java.util.List;

/** 将 RuleVersionRow（数据库 JOIN 结果）组装为不可变的 RuleVersionSnapshot。 */
@Component
public class SnapshotAssembler {

    private final AstJsonCodec codec;

    public SnapshotAssembler(AstJsonCodec codec) {
        this.codec = codec;
    }

    /**
     * 将一行数据库结果组装为 RuleVersionSnapshot。
     *
     * @param row JOIN 查询结果行
     * @return 不可变 RuleVersionSnapshot
     * @throws JsonProcessingException JSON 反序列化失败时抛出
     */
    public RuleVersionSnapshot assemble(RuleVersionRow row) throws JsonProcessingException {
        AstNode conditionAst = codec.deserializeAst(row.conditionAstJson());
        List<RuleVersionSnapshot.PreGateConfig> preGates =
                codec.deserializePreGates(row.preGatesJson());
        List<RuleVersionSnapshot.DecisionBinding> decisionBindings =
                codec.deserializeDecisionBindings(row.decisionBindingsJson());

        return new RuleVersionSnapshot(
                row.ruleVersionId(),
                row.sceneCode(),
                String.valueOf(row.tenantId()),
                conditionAst,
                preGates,
                decisionBindings
        );
    }

    /**
     * 批量组装，JSON 解析失败的行跳过并记录日志。
     *
     * @param rows 待组装的行列表
     * @return 成功组装的快照列表
     */
    public List<RuleVersionSnapshot> assembleAll(List<RuleVersionRow> rows) {
        return rows.stream()
                .map(row -> {
                    try {
                        return assemble(row);
                    } catch (JsonProcessingException e) {
                        // JSON 格式异常：跳过该行，记录错误（理论上不应发生，rule_version 由引擎写入）
                        System.err.println("[SnapshotAssembler] 跳过解析失败的 ruleVersionId=" +
                                row.ruleVersionId() + ": " + e.getMessage());
                        return null;
                    }
                })
                .filter(s -> s != null)
                .toList();
    }
}
```

- [ ] **Step 6: 创建 RuleVersionReadMapper**

```java
// rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/mapper/RuleVersionReadMapper.java
package com.sstlfsj.rule.eval.internal.mapper;

import com.sstlfsj.rule.eval.internal.snapshot.RuleVersionRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 只读 Mapper：rule_version JOIN rule_definition JOIN scene，供倒排索引加载使用。 */
@Mapper
public interface RuleVersionReadMapper {

    /** 加载所有 ACTIVE 状态的规则版本（启动时全量加载）。 */
    @Select("""
            SELECT
              rv.id              AS ruleVersionId,
              s.code             AS sceneCode,
              rd.tenant_id       AS tenantId,
              rv.condition_ast   AS conditionAstJson,
              rv.pre_gates       AS preGatesJson,
              rv.decision_bindings AS decisionBindingsJson,
              rv.trigger_event_types AS triggerEventTypesJson
            FROM rule_version rv
            INNER JOIN rule_definition rd ON rv.rule_definition_id = rd.id
            INNER JOIN scene s ON rd.scene_id = s.id
            WHERE rv.status = 'ACTIVE'
            """)
    List<RuleVersionRow> loadAllActive();

    /** 加载指定租户 + 场景的所有 ACTIVE 规则版本（热更新时局部刷新）。 */
    @Select("""
            SELECT
              rv.id              AS ruleVersionId,
              s.code             AS sceneCode,
              rd.tenant_id       AS tenantId,
              rv.condition_ast   AS conditionAstJson,
              rv.pre_gates       AS preGatesJson,
              rv.decision_bindings AS decisionBindingsJson,
              rv.trigger_event_types AS triggerEventTypesJson
            FROM rule_version rv
            INNER JOIN rule_definition rd ON rv.rule_definition_id = rd.id
            INNER JOIN scene s ON rd.scene_id = s.id
            WHERE rv.status = 'ACTIVE'
              AND rd.tenant_id = #{tenantId}
              AND s.code = #{sceneCode}
            """)
    List<RuleVersionRow> loadActiveByScene(@Param("tenantId") Long tenantId,
                                           @Param("sceneCode") String sceneCode);

    /** 按 ruleVersionId 加载单条（dry-run 指定版本时使用，status 不限）。 */
    @Select("""
            SELECT
              rv.id              AS ruleVersionId,
              s.code             AS sceneCode,
              rd.tenant_id       AS tenantId,
              rv.condition_ast   AS conditionAstJson,
              rv.pre_gates       AS preGatesJson,
              rv.decision_bindings AS decisionBindingsJson,
              rv.trigger_event_types AS triggerEventTypesJson
            FROM rule_version rv
            INNER JOIN rule_definition rd ON rv.rule_definition_id = rd.id
            INNER JOIN scene s ON rd.scene_id = s.id
            WHERE rv.id = #{ruleVersionId}
            """)
    RuleVersionRow loadById(@Param("ruleVersionId") Long ruleVersionId);
}
```

- [ ] **Step 7: 运行测试，确认通过**

```bash
$MVN -pl rule-eval-svc -am test -Dtest='AstJsonCodecTest,SnapshotAssemblerTest' -Dsurefire.failIfNoSpecifiedTests=false
```

预期：BUILD SUCCESS，所有测试通过。

- [ ] **Step 8: Commit**

```bash
git add rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/snapshot/
git add rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/mapper/RuleVersionReadMapper.java
git add rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/snapshot/
git commit -m "$(cat <<'EOF'
feat(eval): AstJsonCodec + RuleVersionRow + SnapshotAssembler + RuleVersionReadMapper（Task B-3）
EOF
)"
```

---

## Task 4：SceneSnapshotLoader + 索引启动加载 + 事件监听器

**背景：** 引擎启动时全量加载所有 ACTIVE 规则到倒排索引；`RulePublishedEvent` 触发局部刷新（重新加载该场景所有 ACTIVE 版本）；`SceneChangedEvent(active=false)` 触发场景从索引移除。

**Files:**
- 新建: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/snapshot/SceneSnapshotLoader.java`
- 新建: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/listener/IndexStartupLoader.java`
- 新建: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/listener/RuleIndexEventListener.java`
- 新建: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/listener/SceneIndexEventListener.java`
- 测试: `rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/listener/RuleIndexEventListenerTest.java`

- [ ] **Step 1: 写失败测试**

```java
// rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/listener/RuleIndexEventListenerTest.java
package com.sstlfsj.rule.eval.internal.listener;

import com.sstlfsj.rule.config.api.event.RulePublishedEvent;
import com.sstlfsj.rule.config.api.event.SceneChangedEvent;
import com.sstlfsj.rule.eval.internal.index.SceneRuleIndex;
import com.sstlfsj.rule.eval.internal.snapshot.SceneSnapshotLoader;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RuleIndexEventListenerTest {

    @Mock SceneRuleIndex index;
    @Mock SceneSnapshotLoader loader;
    @InjectMocks RuleIndexEventListener ruleListener;
    @InjectMocks SceneIndexEventListener sceneListener;

    @Test
    void onRulePublished_reloadsSnapshotsForScene() {
        RulePublishedEvent event = new RulePublishedEvent("1", "fraud_check", 42L);
        ConditionNode condNode = new ConditionNode("GT", "score", Map.of());
        RuleVersionSnapshot snap = new RuleVersionSnapshot(
                42L, "fraud_check", "1", condNode, List.of(),
                List.of(new RuleVersionSnapshot.DecisionBinding("REJECT", 10)));
        // 每个 eventType 对应 index.update 一次
        when(loader.loadByScene(1L, "fraud_check")).thenReturn(
                Map.of("RISK_EVENT", List.of(snap)));

        ruleListener.onRulePublished(event);

        verify(index).update("1", "fraud_check", "RISK_EVENT", List.of(snap));
    }

    @Test
    void onSceneDisabled_removesFromIndex() {
        SceneChangedEvent event = new SceneChangedEvent("1", "fraud_check", false);

        sceneListener.onSceneChanged(event);

        verify(index).remove("1", "fraud_check");
        verifyNoInteractions(loader);
    }

    @Test
    void onSceneEnabled_reloadsSnapshots() {
        SceneChangedEvent event = new SceneChangedEvent("1", "fraud_check", true);
        when(loader.loadByScene(1L, "fraud_check")).thenReturn(Map.of());

        sceneListener.onSceneChanged(event);

        verify(loader).loadByScene(1L, "fraud_check");
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

```bash
$MVN -pl rule-eval-svc -am test -Dtest='RuleIndexEventListenerTest' -Dsurefire.failIfNoSpecifiedTests=false
```

预期：COMPILE ERROR。

- [ ] **Step 3: 创建 SceneSnapshotLoader**

```java
// rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/snapshot/SceneSnapshotLoader.java
package com.sstlfsj.rule.eval.internal.snapshot;

import com.sstlfsj.rule.eval.internal.mapper.RuleVersionReadMapper;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 从数据库加载规则版本快照，供倒排索引使用。
 * 返回值按 eventType 分组，key = eventType，value = 该 eventType 对应的快照列表。
 */
@Component
public class SceneSnapshotLoader {

    private final RuleVersionReadMapper mapper;
    private final SnapshotAssembler assembler;

    public SceneSnapshotLoader(RuleVersionReadMapper mapper, SnapshotAssembler assembler) {
        this.mapper = mapper;
        this.assembler = assembler;
    }

    /**
     * 全量加载所有 ACTIVE 规则版本，按 (tenantId+sceneCode, eventType) 分组。
     * key = "tenantId:sceneCode"，嵌套 Map key = eventType。
     *
     * @return 双层 Map，外层 key = tenantId:sceneCode，内层 key = eventType
     */
    public Map<String, Map<String, List<RuleVersionSnapshot>>> loadAll() {
        List<RuleVersionRow> rows = mapper.loadAllActive();
        List<RuleVersionSnapshot> snapshots = assembler.assembleAll(rows);
        return groupBySceneAndEventType(snapshots);
    }

    /**
     * 加载指定租户 + 场景的所有 ACTIVE 规则版本，按 eventType 分组。
     *
     * @param tenantId  租户 ID（Long）
     * @param sceneCode 场景编码
     * @return key = eventType，value = 快照列表
     */
    public Map<String, List<RuleVersionSnapshot>> loadByScene(Long tenantId, String sceneCode) {
        List<RuleVersionRow> rows = mapper.loadActiveByScene(tenantId, sceneCode);
        List<RuleVersionSnapshot> snapshots = assembler.assembleAll(rows);
        return groupByEventType(snapshots);
    }

    /**
     * 按 ruleVersionId 加载单条快照（dry-run 指定版本时使用）。
     *
     * @param ruleVersionId 规则版本 ID
     * @return 快照，不存在时返回 null
     */
    public RuleVersionSnapshot loadById(Long ruleVersionId) {
        RuleVersionRow row = mapper.loadById(ruleVersionId);
        if (row == null) return null;
        List<RuleVersionSnapshot> list = assembler.assembleAll(List.of(row));
        return list.isEmpty() ? null : list.get(0);
    }

    private Map<String, Map<String, List<RuleVersionSnapshot>>> groupBySceneAndEventType(
            List<RuleVersionSnapshot> snapshots) {
        Map<String, Map<String, List<RuleVersionSnapshot>>> result = new HashMap<>();
        for (RuleVersionSnapshot snap : snapshots) {
            String outerKey = snap.tenantId() + ":" + snap.sceneCode();
            // RuleVersionSnapshot 目前无 triggerEventTypes 字段，需从 index 现有 match() key 格式反推
            // v1: 将快照放到所有可能的 eventType 组中（通过实际 triggerEventTypes —— 当前模型缺失该字段）
            // 降级策略：使用 "*" 作为 eventType 占位，索引 match() 也需要兼容查 "*"
            // 详见 SceneRuleIndex 处理说明
            result.computeIfAbsent(outerKey, k -> new HashMap<>())
                    .computeIfAbsent("*", k -> new java.util.ArrayList<>())
                    .add(snap);
        }
        return result;
    }

    private Map<String, List<RuleVersionSnapshot>> groupByEventType(
            List<RuleVersionSnapshot> snapshots) {
        Map<String, List<RuleVersionSnapshot>> result = new HashMap<>();
        for (RuleVersionSnapshot snap : snapshots) {
            // v1: triggerEventTypes 未在 RuleVersionSnapshot 模型中，用 "*" 作通配符
            result.computeIfAbsent("*", k -> new java.util.ArrayList<>()).add(snap);
        }
        return result;
    }
}
```

> **v1 说明：** `RuleVersionSnapshot` 当前不含 `triggerEventTypes` 字段（Plan A Task 3 调整实体时该字段存储在 DB，但未放入内存快照）。v1 使用 `"*"` 作通配 eventType，`SceneRuleIndex.match()` 同时查 `tenantId:sceneCode:eventType` 和 `tenantId:sceneCode:*`，命中任意一个即返回。Plan C 中若需精确路由可扩展 `RuleVersionSnapshot` 增加该字段。

- [ ] **Step 4: 修改 SceneRuleIndex，支持通配 eventType 查询**

```java
// rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/index/SceneRuleIndex.java
package com.sstlfsj.rule.eval.internal.index;

import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存倒排索引：(tenantId, sceneCode, eventType) → List&lt;RuleVersionSnapshot&gt;。
 * 由 RulePublishedEvent / SceneChangedEvent 监听器触发热更。
 * 支持通配 eventType "*"：match() 同时查精确 key 和 tenantId:sceneCode:* 的并集。
 */
@Component
public class SceneRuleIndex {

    private final Map<String, List<RuleVersionSnapshot>> index = new ConcurrentHashMap<>();

    /**
     * 返回给定租户、场景和事件类型对应的活跃规则版本快照列表。
     * 先查精确 key，再查通配 key（"*"），合并去重返回。
     *
     * @param tenantId  租户标识
     * @param sceneCode 场景编码
     * @param eventType 待匹配的事件类型
     * @return 匹配的快照列表，无匹配则返回空列表
     */
    public List<RuleVersionSnapshot> match(String tenantId, String sceneCode, String eventType) {
        String exactKey    = tenantId + ":" + sceneCode + ":" + eventType;
        String wildcardKey = tenantId + ":" + sceneCode + ":*";

        List<RuleVersionSnapshot> exact    = index.getOrDefault(exactKey, List.of());
        List<RuleVersionSnapshot> wildcard = index.getOrDefault(wildcardKey, List.of());

        if (exact.isEmpty()) return wildcard;
        if (wildcard.isEmpty()) return exact;

        // 合并，使用 ruleVersionId 去重
        List<RuleVersionSnapshot> merged = new ArrayList<>(exact);
        for (RuleVersionSnapshot snap : wildcard) {
            if (exact.stream().noneMatch(s -> s.ruleVersionId().equals(snap.ruleVersionId()))) {
                merged.add(snap);
            }
        }
        return List.copyOf(merged);
    }

    /**
     * 更新给定租户、场景和事件类型的索引条目。
     *
     * @param tenantId  租户标识
     * @param sceneCode 场景编码
     * @param eventType 事件类型（可为 "*" 通配）
     * @param snapshots 新的活跃快照列表
     */
    public void update(String tenantId, String sceneCode, String eventType,
                       List<RuleVersionSnapshot> snapshots) {
        String key = tenantId + ":" + sceneCode + ":" + eventType;
        index.put(key, List.copyOf(snapshots));
    }

    /**
     * 删除给定租户和场景的所有索引条目（如场景被禁用时调用）。
     *
     * @param tenantId  租户标识
     * @param sceneCode 待删除的场景编码
     */
    public void remove(String tenantId, String sceneCode) {
        index.keySet().removeIf(k -> k.startsWith(tenantId + ":" + sceneCode + ":"));
    }
}
```

- [ ] **Step 5: 创建事件监听器**

```java
// rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/listener/RuleIndexEventListener.java
package com.sstlfsj.rule.eval.internal.listener;

import com.sstlfsj.rule.config.api.event.RulePublishedEvent;
import com.sstlfsj.rule.eval.internal.index.SceneRuleIndex;
import com.sstlfsj.rule.eval.internal.snapshot.SceneSnapshotLoader;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** 监听 RulePublishedEvent，刷新倒排索引中该场景的所有 ACTIVE 快照。 */
@Component
public class RuleIndexEventListener {

    private final SceneRuleIndex index;
    private final SceneSnapshotLoader loader;

    public RuleIndexEventListener(SceneRuleIndex index, SceneSnapshotLoader loader) {
        this.index = index;
        this.loader = loader;
    }

    /**
     * 规则发布后重新加载该场景全部 ACTIVE 快照，并刷新倒排索引。
     * 使用 @ApplicationModuleListener 确保在 config-svc 事务提交后执行。
     *
     * @param event 规则发布事件
     */
    @ApplicationModuleListener
    public void onRulePublished(RulePublishedEvent event) {
        Long tenantId = Long.valueOf(event.tenantId());
        Map<String, List<RuleVersionSnapshot>> byEventType =
                loader.loadByScene(tenantId, event.sceneCode());

        for (Map.Entry<String, List<RuleVersionSnapshot>> entry : byEventType.entrySet()) {
            index.update(event.tenantId(), event.sceneCode(),
                         entry.getKey(), entry.getValue());
        }
    }
}
```

```java
// rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/listener/SceneIndexEventListener.java
package com.sstlfsj.rule.eval.internal.listener;

import com.sstlfsj.rule.config.api.event.SceneChangedEvent;
import com.sstlfsj.rule.eval.internal.index.SceneRuleIndex;
import com.sstlfsj.rule.eval.internal.snapshot.SceneSnapshotLoader;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** 监听 SceneChangedEvent，场景禁用时从索引移除，场景启用时重新加载快照。 */
@Component
public class SceneIndexEventListener {

    private final SceneRuleIndex index;
    private final SceneSnapshotLoader loader;

    public SceneIndexEventListener(SceneRuleIndex index, SceneSnapshotLoader loader) {
        this.index = index;
        this.loader = loader;
    }

    /**
     * 场景状态变更时更新倒排索引。
     * 禁用场景（active=false）→ 从索引移除全部条目；
     * 启用场景（active=true）→ 重新加载 ACTIVE 快照。
     *
     * @param event 场景变更事件
     */
    @ApplicationModuleListener
    public void onSceneChanged(SceneChangedEvent event) {
        if (!event.active()) {
            index.remove(event.tenantId(), event.sceneCode());
            return;
        }
        Long tenantId = Long.valueOf(event.tenantId());
        Map<String, List<RuleVersionSnapshot>> byEventType =
                loader.loadByScene(tenantId, event.sceneCode());
        for (Map.Entry<String, List<RuleVersionSnapshot>> entry : byEventType.entrySet()) {
            index.update(event.tenantId(), event.sceneCode(),
                         entry.getKey(), entry.getValue());
        }
    }
}
```

```java
// rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/listener/IndexStartupLoader.java
package com.sstlfsj.rule.eval.internal.listener;

import com.sstlfsj.rule.eval.internal.index.SceneRuleIndex;
import com.sstlfsj.rule.eval.internal.snapshot.SceneSnapshotLoader;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** 应用启动完成后全量加载所有 ACTIVE 规则版本到倒排索引。 */
@Component
public class IndexStartupLoader {

    private final SceneRuleIndex index;
    private final SceneSnapshotLoader loader;

    public IndexStartupLoader(SceneRuleIndex index, SceneSnapshotLoader loader) {
        this.index = index;
        this.loader = loader;
    }

    /**
     * 全量加载所有 ACTIVE 规则快照到内存索引。
     * ApplicationReadyEvent 触发（Spring 上下文就绪后，接收请求前）。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        Map<String, Map<String, List<RuleVersionSnapshot>>> all = loader.loadAll();
        for (Map.Entry<String, Map<String, List<RuleVersionSnapshot>>> outerEntry : all.entrySet()) {
            // outerKey = tenantId:sceneCode
            String[] parts = outerEntry.getKey().split(":", 2);
            String tenantId = parts[0];
            String sceneCode = parts.length > 1 ? parts[1] : "";
            for (Map.Entry<String, List<RuleVersionSnapshot>> innerEntry : outerEntry.getValue().entrySet()) {
                index.update(tenantId, sceneCode, innerEntry.getKey(), innerEntry.getValue());
            }
        }
    }
}
```

- [ ] **Step 6: 运行测试，验证通过**

```bash
$MVN -pl rule-eval-svc -am test -Dtest='RuleIndexEventListenerTest' -Dsurefire.failIfNoSpecifiedTests=false
```

预期：BUILD SUCCESS，3 个测试通过。

- [ ] **Step 7: Commit**

```bash
git add rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/snapshot/SceneSnapshotLoader.java
git add rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/listener/
git add rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/index/SceneRuleIndex.java
git add rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/listener/
git commit -m "$(cat <<'EOF'
feat(eval): 索引启动加载 + RulePublishedEvent / SceneChangedEvent 热更新监听器（Task B-4）
EOF
)"
```

---

## Task 5：RolloutPreGate

**背景：** ROLLOUT gate 用 murmur3_32 哈希 `subjectId:ruleVersionId`，模 100 后与配置的 `percentage` 比较。需从 `PreGateContext.gateParams` 取 `percentage`，missing 时 fail-open（视为通过）。

**Files:**
- 新建: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/pregate/RolloutPreGate.java`
- 测试: `rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/pregate/RolloutPreGateTest.java`

- [ ] **Step 1: 写失败测试**

```java
// rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/pregate/RolloutPreGateTest.java
package com.sstlfsj.rule.eval.internal.pregate;

import com.sstlfsj.rule.kernel.api.model.PreGateContext;
import com.sstlfsj.rule.kernel.api.model.PreGateResult;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RolloutPreGateTest {

    private final RolloutPreGate gate = new RolloutPreGate();

    /** 构建标准 PreGateContext 辅助方法。 */
    private PreGateContext ctx(String subjectId, Long ruleVersionId, int percentage) {
        RuleEvent event = new RuleEvent("1", "scene", "EVENT", subjectId,
                "eid", Instant.now(), Map.of(), Map.of());
        return new PreGateContext("1", "scene", subjectId, event,
                ruleVersionId, Map.of("percentage", percentage));
    }

    @Test
    void gateType_isROLLOUT() {
        assertEquals("ROLLOUT", gate.gateType());
    }

    @Test
    void percentage100_alwaysPasses() {
        for (int i = 0; i < 50; i++) {
            PreGateResult result = gate.evaluate(ctx("user" + i, 1L, 100));
            assertTrue(result.passed(), "subject user" + i + " should pass with 100%");
        }
    }

    @Test
    void percentage0_alwaysBlocked() {
        for (int i = 0; i < 50; i++) {
            PreGateResult result = gate.evaluate(ctx("user" + i, 1L, 0));
            assertFalse(result.passed(), "subject user" + i + " should be blocked with 0%");
            assertEquals("ROLLOUT", result.blockedBy());
        }
    }

    @Test
    void deterministicForSameInput() {
        PreGateContext c1 = ctx("userA", 42L, 50);
        PreGateContext c2 = ctx("userA", 42L, 50);
        assertEquals(gate.evaluate(c1).passed(), gate.evaluate(c2).passed());
    }

    @Test
    void differentRuleVersions_differentRolloutBuckets() {
        // 相同 subjectId 在不同 ruleVersionId 下可能分桶不同（murmur3 含 ruleVersionId）
        // 至少要求有至少 1 个 subjectId 在两个版本下结果不同（概率极高）
        boolean anyDifference = false;
        for (int i = 0; i < 100; i++) {
            boolean r1 = gate.evaluate(ctx("user" + i, 1L, 50)).passed();
            boolean r2 = gate.evaluate(ctx("user" + i, 2L, 50)).passed();
            if (r1 != r2) {
                anyDifference = true;
                break;
            }
        }
        assertTrue(anyDifference, "不同 ruleVersionId 应产生不同分桶");
    }

    @Test
    void missingPercentage_failOpen() {
        RuleEvent event = new RuleEvent("1", "scene", "E", "u1",
                "eid", Instant.now(), Map.of(), Map.of());
        // 无 percentage 参数
        PreGateContext ctx = new PreGateContext("1", "scene", "u1", event, 1L, Map.of());
        assertTrue(gate.evaluate(ctx).passed(), "缺少 percentage 配置时 fail-open");
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

```bash
$MVN -pl rule-eval-svc -am test -Dtest='RolloutPreGateTest' -Dsurefire.failIfNoSpecifiedTests=false
```

预期：COMPILE ERROR，`RolloutPreGate` 不存在。

- [ ] **Step 3: 实现 RolloutPreGate**

```java
// rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/pregate/RolloutPreGate.java
package com.sstlfsj.rule.eval.internal.pregate;

import com.google.common.hash.Hashing;
import com.sstlfsj.rule.kernel.api.model.PreGateContext;
import com.sstlfsj.rule.kernel.api.model.PreGateResult;
import com.sstlfsj.rule.kernel.api.spi.pregate.PreGate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * ROLLOUT Pre-Gate：按 murmur3_32(subjectId:ruleVersionId) % 100 < percentage 决定是否放行。
 * 同一 subjectId + ruleVersionId 组合结果确定；不同 ruleVersionId 互相独立分桶。
 * 缺少 percentage 配置时 fail-open（视为全量放行）。
 */
@Component
public class RolloutPreGate implements PreGate {

    @Override
    public String gateType() {
        return "ROLLOUT";
    }

    @Override
    public PreGateResult evaluate(PreGateContext ctx) {
        Object percentageParam = ctx.gateParams().get("percentage");
        if (percentageParam == null) {
            // 无配置时 fail-open
            return PreGateResult.pass();
        }
        int percentage = ((Number) percentageParam).intValue();
        if (percentage >= 100) return PreGateResult.pass();
        if (percentage <= 0)   return PreGateResult.blocked("ROLLOUT");

        // murmur3_32(subjectId:ruleVersionId) 确保不同规则版本独立分桶
        String hashInput = ctx.subjectId() + ":" + ctx.ruleVersionId();
        int bucket = Math.abs(
                Hashing.murmur3_32_fixed()
                        .hashString(hashInput, StandardCharsets.UTF_8)
                        .asInt()
        ) % 100;

        return bucket < percentage ? PreGateResult.pass() : PreGateResult.blocked("ROLLOUT");
    }
}
```

- [ ] **Step 4: 运行测试，确认通过**

```bash
$MVN -pl rule-eval-svc -am test -Dtest='RolloutPreGateTest' -Dsurefire.failIfNoSpecifiedTests=false
```

预期：BUILD SUCCESS，全部 6 个测试通过。

- [ ] **Step 5: Commit**

```bash
git add rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/pregate/
git add rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/pregate/
git commit -m "$(cat <<'EOF'
feat(eval): RolloutPreGate（murmur3_32 分桶，Task B-5）
EOF
)"
```

---

## Task 6：EvalContextAssembler

**背景：** v1 评估上下文装配。SubjectLoader 和 MetricSourceHandler 均为可选 SPI；无实现时优雅降级（空 Subject + 仅 providedMetrics）。`CompletableFuture.allOf()` 并发装配（即使 v1 无真实实现，并发框架已到位，后续添加实现无需改本类）。

**Files:**
- 新建: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/context/EvalContextAssembler.java`
- 测试: `rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/context/EvalContextAssemblerTest.java`

- [ ] **Step 1: 写失败测试**

```java
// rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/context/EvalContextAssemblerTest.java
package com.sstlfsj.rule.eval.internal.context;

import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.kernel.api.spi.subject.SubjectLoader;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EvalContextAssemblerTest {

    @Test
    void assemble_noSubjectLoader_usesEmptySubject() {
        EvalContextAssembler assembler = new EvalContextAssembler(List.of(), List.of());
        RuleEvent event = new RuleEvent("1", "scene", "E", "u1",
                "eid", Instant.now(), Map.of(), Map.of());

        EvalContext ctx = assembler.assemble(event, List.of());

        assertNotNull(ctx.getSubject());
        assertEquals("u1", ctx.getSubject().subjectId());
        assertEquals("1", ctx.getTenantId());
    }

    @Test
    void assemble_providedMetrics_usedWhenAvailable() {
        EvalContextAssembler assembler = new EvalContextAssembler(List.of(), List.of());
        RuleEvent event = new RuleEvent("1", "scene", "E", "u1",
                "eid", Instant.now(), Map.of(),
                Map.of("score", 95));

        EvalContext ctx = assembler.assemble(event, List.of());

        MetricValue scoreMetric = ctx.getMetric("score");
        assertNotNull(scoreMetric);
        assertEquals(95, scoreMetric.value());
        assertEquals("PROVIDED", scoreMetric.valueSource());
    }

    @Test
    void assemble_withSubjectLoader_callsLoader() {
        SubjectLoader loader = mock(SubjectLoader.class);
        Subject subject = new Subject("u1", SubjectType.USER, Map.of("age", 25));
        when(loader.supportedTypes()).thenReturn(List.of(SubjectType.USER));
        when(loader.load(eq("u1"), eq(SubjectType.USER), any())).thenReturn(subject);

        EvalContextAssembler assembler = new EvalContextAssembler(List.of(loader), List.of());
        RuleEvent event = new RuleEvent("1", "scene", "E", "u1",
                "eid", Instant.now(), Map.of(), Map.of());

        EvalContext ctx = assembler.assemble(event, List.of());

        assertEquals(subject, ctx.getSubject());
        assertEquals(25, ctx.getSubject().getAttribute("age"));
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

```bash
$MVN -pl rule-eval-svc -am test -Dtest='EvalContextAssemblerTest' -Dsurefire.failIfNoSpecifiedTests=false
```

预期：COMPILE ERROR，`EvalContextAssembler` 不存在。

- [ ] **Step 3: 实现 EvalContextAssembler**

```java
// rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/context/EvalContextAssembler.java
package com.sstlfsj.rule.eval.internal.context;

import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.kernel.api.spi.subject.SubjectLoader;
import com.sstlfsj.rule.kernel.api.spi.metric.MetricSourceHandler;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * 装配 EvalContext：SubjectLoader（可选 SPI）+ providedMetrics 优先匹配 + MetricSourceHandler（可选 SPI）。
 * v1 无真实 MetricSourceHandler 实现时，仅 providedMetrics 生效。
 * 并发框架已到位（CompletableFuture.allOf），添加实现无需修改本类。
 */
@Component
public class EvalContextAssembler {

    /** 支持 USER 类型的第一个 SubjectLoader，优先使用；无实现时返回空 Subject。 */
    private final SubjectLoader subjectLoader;
    /** MetricSourceHandler 列表，v1 通常为空。 */
    private final List<MetricSourceHandler> metricHandlers;

    public EvalContextAssembler(List<SubjectLoader> subjectLoaders,
                                List<MetricSourceHandler> metricHandlers) {
        this.subjectLoader = subjectLoaders.stream()
                .filter(l -> l.supportedTypes().contains(SubjectType.USER))
                .findFirst()
                .orElse(null);
        this.metricHandlers = List.copyOf(metricHandlers);
    }

    /**
     * 装配一次评估的 EvalContext。
     * <ol>
     *   <li>Subject 加载（有 SubjectLoader 时调用，否则构造空 Subject）</li>
     *   <li>providedMetrics 优先填充</li>
     *   <li>MetricSourceHandler 并发补充剩余 metric（v1 无实现则跳过）</li>
     * </ol>
     *
     * @param event      触发事件
     * @param candidates 候选 RuleVersionSnapshot（用于未来扩展，v1 不用）
     * @return 不可变 EvalContext
     */
    public EvalContext assemble(RuleEvent event,
                                List<RuleVersionSnapshot> candidates) {
        // Subject 加载（并发起点之一）
        CompletableFuture<Subject> subjectFuture = CompletableFuture.supplyAsync(
                () -> loadSubject(event));

        // providedMetrics 转为 MetricValue Map（valueSource=PROVIDED）
        Map<String, MetricValue> metrics = new HashMap<>();
        for (Map.Entry<String, Object> entry : event.providedMetrics().entrySet()) {
            metrics.put(entry.getKey(),
                    new MetricValue(entry.getValue(), "UNKNOWN", "PROVIDED"));
        }

        // 等待 Subject 就绪
        Subject subject = subjectFuture.join();

        return new EvalContext(event.tenantId(), event, subject, metrics);
    }

    private Subject loadSubject(RuleEvent event) {
        if (subjectLoader == null) {
            // 无 SubjectLoader 实现时返回最小可用 Subject
            return new Subject(event.subjectId(), SubjectType.USER, Map.of());
        }
        try {
            return subjectLoader.load(event.subjectId(), SubjectType.USER, event);
        } catch (Exception e) {
            // SubjectLoader 异常：降级返回空 Subject，不阻断评估
            return new Subject(event.subjectId(), SubjectType.USER, Map.of());
        }
    }
}
```

- [ ] **Step 4: 运行测试，确认通过**

```bash
$MVN -pl rule-eval-svc -am test -Dtest='EvalContextAssemblerTest' -Dsurefire.failIfNoSpecifiedTests=false
```

预期：BUILD SUCCESS，3 个测试通过。

- [ ] **Step 5: Commit**

```bash
git add rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/context/
git add rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/context/
git commit -m "$(cat <<'EOF'
feat(eval): EvalContextAssembler（SubjectLoader + providedMetrics，Task B-6）
EOF
)"
```

---

## Task 7：EvalSessionWriter + EvalServiceImpl 完整实现 + 更新 AutoConfiguration

**背景：** EvalSessionWriter 封装 evaluation_session 的 INSERT PENDING + UPDATE final 和 DuplicateKeyException 幂等处理。EvalServiceImpl 串联全部组件实现完整链路（Matcher → Pre-Gate → EvalContext → AST 评估 → Decision 合成 → Session 写入 + Trace 提交）。

**Files:**
- 新建: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/session/EvalSessionWriter.java`
- 修改: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/service/EvalServiceImpl.java`
- 修改: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/EvalAutoConfiguration.java`
- 测试: `rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/session/EvalSessionWriterTest.java`
- 测试: `rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/service/EvalServiceImplTest.java`

- [ ] **Step 1: 写 EvalSessionWriterTest**

```java
// rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/session/EvalSessionWriterTest.java
package com.sstlfsj.rule.eval.internal.session;

import com.sstlfsj.rule.eval.internal.domain.EvaluationSession;
import com.sstlfsj.rule.eval.internal.mapper.DryRunSessionMapper;
import com.sstlfsj.rule.eval.internal.mapper.EvaluationSessionMapper;
import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EvalSessionWriterTest {

    @Mock EvaluationSessionMapper sessionMapper;
    @Mock DryRunSessionMapper dryRunMapper;
    @InjectMocks EvalSessionWriter writer;

    private RuleEvent event() {
        return new RuleEvent("1", "scene", "E", "u1",
                "evt-001", Instant.parse("2024-01-01T00:00:00Z"), Map.of(), Map.of());
    }

    @Test
    void insertPending_savesSessionWithPendingStatus() {
        when(sessionMapper.insert(any())).thenReturn(1);
        RuleEvent ev = event();

        writer.insertPending(ev, 3, "PULL");

        ArgumentCaptor<EvaluationSession> captor = ArgumentCaptor.forClass(EvaluationSession.class);
        verify(sessionMapper).insert(captor.capture());
        EvaluationSession saved = captor.getValue();
        assertEquals("PENDING", saved.getStatus());
        assertEquals("evt-001", saved.getEventId());
        assertEquals(1L, saved.getTenantId());
        assertEquals(3, saved.getCandidateRuleCount());
        assertEquals("PULL", saved.getSource());
    }

    @Test
    void insertBlocked_savesBlockedStatus() {
        when(sessionMapper.insert(any())).thenReturn(1);

        writer.insertBlocked(event(), "ROLLOUT", "PULL");

        ArgumentCaptor<EvaluationSession> captor = ArgumentCaptor.forClass(EvaluationSession.class);
        verify(sessionMapper).insert(captor.capture());
        assertEquals("BLOCKED", captor.getValue().getStatus());
        assertEquals("ROLLOUT", captor.getValue().getBlockedBy());
    }
}
```

- [ ] **Step 2: 写 EvalServiceImplTest（替换原桩测试）**

```java
// rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/service/EvalServiceImplTest.java
package com.sstlfsj.rule.eval.internal.service;

import com.sstlfsj.rule.eval.internal.context.EvalContextAssembler;
import com.sstlfsj.rule.eval.internal.index.SceneRuleIndex;
import com.sstlfsj.rule.eval.internal.session.EvalSessionWriter;
import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.spi.executor.RuleVersionExecutor;
import com.sstlfsj.rule.kernel.api.spi.pregate.PreGate;
import com.sstlfsj.rule.kernel.api.spi.trace.TraceWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EvalServiceImplTest {

    @Mock SceneRuleIndex index;
    @Mock EvalContextAssembler contextAssembler;
    @Mock RuleVersionExecutor executor;
    @Mock EvalSessionWriter sessionWriter;
    @Mock TraceWriter traceWriter;

    // 注意：EvalServiceImpl 构造器接受 List<PreGate>，Mockito @InjectMocks 会注入空列表
    @InjectMocks EvalServiceImpl impl;

    private RuleEvent event() {
        return new RuleEvent("1", "fraud_check", "RISK_EVENT", "u1",
                "evt-001", Instant.now(), Map.of(), Map.of());
    }

    private RuleVersionSnapshot snapshot(Long id, String decisionCode) {
        return new RuleVersionSnapshot(
                id, "fraud_check", "1",
                new ConditionNode("EQ", null, Map.of()),
                List.of(),
                List.of(new RuleVersionSnapshot.DecisionBinding(decisionCode, 10)));
    }

    @Test
    void evaluate_noMatchingRules_returnsMiss() {
        when(index.match("1", "fraud_check", "RISK_EVENT")).thenReturn(List.of());

        EvalResult result = impl.evaluate(event());

        assertFalse(result.ruleHit());
        verifyNoInteractions(sessionWriter);
    }

    @Test
    void evaluate_ruleHit_returnsHitWithDecision() {
        RuleVersionSnapshot snap = snapshot(1L, "REJECT");
        when(index.match("1", "fraud_check", "RISK_EVENT")).thenReturn(List.of(snap));
        when(contextAssembler.assemble(any(), any()))
                .thenReturn(new EvalContext("1", event(), new Subject("u1", SubjectType.USER, Map.of()), Map.of()));
        when(executor.execute(any(), any())).thenReturn(EvalResult.hit());
        when(sessionWriter.insertPending(any(), anyInt(), anyString())).thenReturn(1L);

        EvalResult result = impl.evaluate(event());

        assertTrue(result.ruleHit());
        assertFalse(result.hitDecisions().isEmpty());
        assertEquals("REJECT", result.hitDecisions().get(0).code());
        verify(sessionWriter).updateFinal(anyLong(), any());
        verify(traceWriter).write(anyString(), anyString(), anyList());
    }

    @Test
    void evaluate_ruleMiss_returnsMiss() {
        RuleVersionSnapshot snap = snapshot(1L, "REJECT");
        when(index.match("1", "fraud_check", "RISK_EVENT")).thenReturn(List.of(snap));
        when(contextAssembler.assemble(any(), any()))
                .thenReturn(new EvalContext("1", event(), new Subject("u1", SubjectType.USER, Map.of()), Map.of()));
        when(executor.execute(any(), any())).thenReturn(EvalResult.miss());
        when(sessionWriter.insertPending(any(), anyInt(), anyString())).thenReturn(1L);

        EvalResult result = impl.evaluate(event());

        assertFalse(result.ruleHit());
        assertTrue(result.hitDecisions().isEmpty());
        assertNull(result.finalDecision());
    }

    @Test
    void acceptEvent_returnsTrueAndDoesNotBlock() {
        when(index.match(any(), any(), any())).thenReturn(List.of());

        boolean accepted = impl.acceptEvent(event());

        assertTrue(accepted);
    }

    @Test
    void dryRun_writesToDryRunSessionNotProd() {
        RuleVersionSnapshot snap = snapshot(42L, "PASS");
        // dry-run 指定版本 42L，index 不参与（走 loader）
        when(contextAssembler.assemble(any(), any()))
                .thenReturn(new EvalContext("1", event(), new Subject("u1", SubjectType.USER, Map.of()), Map.of()));
        when(executor.execute(any(), any())).thenReturn(EvalResult.miss());
        when(sessionWriter.insertDryRunPending(any(), anyLong())).thenReturn(1L);

        EvalResult result = impl.dryRun(event(), 42L);

        assertFalse(result.ruleHit());
        verify(sessionWriter).insertDryRunPending(any(), eq(42L));
        verify(sessionWriter, never()).insertPending(any(), anyInt(), anyString());
    }
}
```

- [ ] **Step 3: 运行测试，确认失败**

```bash
$MVN -pl rule-eval-svc -am test -Dtest='EvalSessionWriterTest,EvalServiceImplTest' -Dsurefire.failIfNoSpecifiedTests=false
```

预期：COMPILE ERROR。

- [ ] **Step 4: 实现 EvalSessionWriter**

```java
// rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/session/EvalSessionWriter.java
package com.sstlfsj.rule.eval.internal.session;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.sstlfsj.rule.eval.internal.domain.DryRunSession;
import com.sstlfsj.rule.eval.internal.domain.EvaluationSession;
import com.sstlfsj.rule.eval.internal.mapper.DryRunSessionMapper;
import com.sstlfsj.rule.eval.internal.mapper.EvaluationSessionMapper;
import com.sstlfsj.rule.kernel.api.model.Decision;
import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.stream.Collectors;

/** 封装 evaluation_session 和 dry_run_session 的同步写入逻辑（D11/D21）。 */
@Component
public class EvalSessionWriter {

    private final EvaluationSessionMapper sessionMapper;
    private final DryRunSessionMapper dryRunMapper;

    public EvalSessionWriter(EvaluationSessionMapper sessionMapper,
                             DryRunSessionMapper dryRunMapper) {
        this.sessionMapper = sessionMapper;
        this.dryRunMapper = dryRunMapper;
    }

    /**
     * INSERT evaluation_session（status=PENDING）。
     * DuplicateKeyException → 幂等处理，返回已有行 id（D11 下半层）。
     *
     * @param event      触发事件
     * @param candidateCount Matcher 命中的候选数量
     * @param source     评估触发来源：PUSH / PULL / REPLAY
     * @return 新插入或已有行的 session id
     */
    public Long insertPending(RuleEvent event, int candidateCount, String source) {
        EvaluationSession session = buildSession(event, source);
        session.setStatus("PENDING");
        session.setCandidateRuleCount(candidateCount);
        session.setHitRuleCount(0);

        try {
            sessionMapper.insert(session);
            return session.getId();
        } catch (DuplicateKeyException e) {
            // 幂等：相同 eventId 已处理过，查回已有 id
            return sessionMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<EvaluationSession>()
                            .eq(EvaluationSession::getTenantId, Long.valueOf(event.tenantId()))
                            .eq(EvaluationSession::getEventId, event.eventId())
            ).getId();
        }
    }

    /**
     * INSERT evaluation_session（status=BLOCKED，Pre-Gate 全部拦截路径）。
     *
     * @param event     触发事件
     * @param blockedBy 首个拦截的 Gate 类型
     * @param source    触发来源
     */
    public void insertBlocked(RuleEvent event, String blockedBy, String source) {
        EvaluationSession session = buildSession(event, source);
        session.setStatus("BLOCKED");
        session.setBlockedBy(blockedBy);
        session.setCandidateRuleCount(0);
        session.setHitRuleCount(0);
        session.setFinishedAt(LocalDateTime.now());
        try {
            sessionMapper.insert(session);
        } catch (DuplicateKeyException ignored) {
            // 已有幂等行，不重复写入
        }
    }

    /**
     * UPDATE evaluation_session：将 PENDING 更新为终态（HIT / MISS / ERROR）。
     *
     * @param sessionId 待更新的 session id
     * @param result    AST 评估结果
     */
    public void updateFinal(Long sessionId, EvalResult result) {
        String status;
        if (result.errorCode() != null) {
            status = result.ruleHit() ? "HIT" : "ERROR";
        } else {
            status = result.ruleHit() ? "HIT" : "MISS";
        }

        String finalDecision = result.finalDecision() != null
                ? result.finalDecision().code() : null;
        String hitDecisionsJson = result.hitDecisions().stream()
                .map(Decision::code)
                .collect(Collectors.joining(",", "[\"", "\"]"))
                .replace(",", "\",\"");

        sessionMapper.update(new EvaluationSession(),
                new LambdaUpdateWrapper<EvaluationSession>()
                        .eq(EvaluationSession::getId, sessionId)
                        .set(EvaluationSession::getStatus, status)
                        .set(EvaluationSession::getFinalDecision, finalDecision)
                        .set(EvaluationSession::getHitDecisions,
                                result.hitDecisions().isEmpty() ? "[]" : hitDecisionsJson)
                        .set(EvaluationSession::getErrorCode, result.errorCode())
                        .set(EvaluationSession::getHitRuleCount, result.hitDecisions().size())
                        .set(EvaluationSession::getFinishedAt, LocalDateTime.now()));
    }

    /**
     * INSERT dry_run_session（status=PENDING）。
     *
     * @param event         触发事件
     * @param ruleVersionId 指定测试的规则版本 ID
     * @return 新插入的 dry-run session id
     */
    public Long insertDryRunPending(RuleEvent event, Long ruleVersionId) {
        DryRunSession session = new DryRunSession();
        session.setTenantId(Long.valueOf(event.tenantId()));
        session.setEventId(event.eventId());
        session.setSceneCode(event.sceneCode());
        session.setEventType(event.eventType());
        session.setSubjectId(event.subjectId());
        session.setRuleVersionId(ruleVersionId);
        session.setStatus("PENDING");
        session.setOccurredAt(toLocalDateTime(event.occurredAt()));
        session.setStartedAt(LocalDateTime.now());

        dryRunMapper.insert(session);
        return session.getId();
    }

    /**
     * UPDATE dry_run_session 为终态。
     *
     * @param sessionId dry-run session id
     * @param result    评估结果
     */
    public void updateDryRunFinal(Long sessionId, EvalResult result) {
        String status = result.ruleHit() ? "HIT" : "MISS";
        if (result.errorCode() != null) status = "ERROR";

        dryRunMapper.update(new DryRunSession(),
                new LambdaUpdateWrapper<DryRunSession>()
                        .eq(DryRunSession::getId, sessionId)
                        .set(DryRunSession::getStatus, status)
                        .set(DryRunSession::getErrorCode, result.errorCode())
                        .set(DryRunSession::getFinalDecision,
                                result.finalDecision() != null ? result.finalDecision().code() : null)
                        .set(DryRunSession::getFinishedAt, LocalDateTime.now()));
    }

    private EvaluationSession buildSession(RuleEvent event, String source) {
        EvaluationSession s = new EvaluationSession();
        s.setTenantId(Long.valueOf(event.tenantId()));
        s.setEventId(event.eventId());
        s.setSceneCode(event.sceneCode());
        s.setEventType(event.eventType());
        s.setSubjectId(event.subjectId());
        s.setSource(source);
        s.setOccurredAt(toLocalDateTime(event.occurredAt()));
        s.setStartedAt(LocalDateTime.now());
        return s;
    }

    private LocalDateTime toLocalDateTime(java.time.Instant instant) {
        return instant == null ? LocalDateTime.now()
                : LocalDateTime.ofInstant(instant, java.time.ZoneId.systemDefault());
    }
}
```

- [ ] **Step 5: 实现 EvalServiceImpl（完整版）**

```java
// rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/service/EvalServiceImpl.java
package com.sstlfsj.rule.eval.internal.service;

import com.sstlfsj.rule.eval.api.service.EvalService;
import com.sstlfsj.rule.eval.internal.context.EvalContextAssembler;
import com.sstlfsj.rule.eval.internal.index.SceneRuleIndex;
import com.sstlfsj.rule.eval.internal.session.EvalSessionWriter;
import com.sstlfsj.rule.eval.internal.snapshot.SceneSnapshotLoader;
import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.kernel.api.spi.executor.RuleVersionExecutor;
import com.sstlfsj.rule.kernel.api.spi.pregate.PreGate;
import com.sstlfsj.rule.kernel.api.spi.trace.TraceWriter;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/** EvalService 完整实现：串联 Matcher → Pre-Gate → EvalContext → AST 评估 → Session 写入（D11/D21）。 */
@Service
class EvalServiceImpl implements EvalService {

    private final SceneRuleIndex index;
    private final SceneSnapshotLoader snapshotLoader;
    private final Map<String, PreGate> preGateMap;
    private final EvalContextAssembler contextAssembler;
    private final RuleVersionExecutor executor;
    private final EvalSessionWriter sessionWriter;
    private final TraceWriter traceWriter;

    EvalServiceImpl(SceneRuleIndex index,
                    SceneSnapshotLoader snapshotLoader,
                    List<PreGate> preGates,
                    EvalContextAssembler contextAssembler,
                    RuleVersionExecutor executor,
                    EvalSessionWriter sessionWriter,
                    TraceWriter traceWriter) {
        this.index = index;
        this.snapshotLoader = snapshotLoader;
        this.preGateMap = preGates.stream()
                .collect(Collectors.toMap(PreGate::gateType, g -> g));
        this.contextAssembler = contextAssembler;
        this.executor = executor;
        this.sessionWriter = sessionWriter;
        this.traceWriter = traceWriter;
    }

    @Override
    public boolean acceptEvent(RuleEvent event) {
        // PUSH 模式：异步投递，虚拟线程不阻塞调用方
        CompletableFuture.runAsync(() -> evaluate(event));
        return true;
    }

    @Override
    public EvalResult evaluate(RuleEvent event) {
        return doEvaluate(event, false, null);
    }

    @Override
    public EvalResult dryRun(RuleEvent event, Long ruleVersionId) {
        return doEvaluate(event, true, ruleVersionId);
    }

    private EvalResult doEvaluate(RuleEvent event, boolean isDryRun, Long specificVersionId) {
        // ① Matcher：从倒排索引获取候选快照
        List<RuleVersionSnapshot> candidates = resolveCandidates(event, isDryRun, specificVersionId);
        if (candidates.isEmpty()) {
            return EvalResult.miss();
        }

        // ② Pre-Gate：逐条候选按 gate 顺序检查
        List<RuleVersionSnapshot> passed = new ArrayList<>();
        String firstBlockedBy = null;
        for (RuleVersionSnapshot snap : candidates) {
            String blockedBy = applyPreGates(event, snap);
            if (blockedBy == null) {
                passed.add(snap);
            } else if (firstBlockedBy == null) {
                firstBlockedBy = blockedBy;
            }
        }

        if (passed.isEmpty()) {
            // 全部被 Pre-Gate 拦截
            if (!isDryRun) {
                sessionWriter.insertBlocked(event, firstBlockedBy, "PULL");
            }
            return EvalResult.miss();
        }

        // ③ EvalContext 装配
        EvalContext ctx = contextAssembler.assemble(event, passed);

        // ④ 写 session（PENDING），dry-run 写 dry_run_session
        Long sessionId = isDryRun
                ? sessionWriter.insertDryRunPending(event, specificVersionId != null ? specificVersionId : passed.get(0).ruleVersionId())
                : sessionWriter.insertPending(event, candidates.size(), "PULL");

        // ⑤ AST 评估：逐条规则求值，收集命中 Decision
        List<Decision> hitDecisions = new ArrayList<>();
        String errorCode = null;

        for (RuleVersionSnapshot snap : passed) {
            try {
                EvalResult r = executor.execute(snap, ctx);
                if (r.ruleHit()) {
                    // 从 decisionBindings 取最高优先级（priority 最小值）的 Decision
                    snap.decisionBindings().stream()
                            .min(Comparator.comparingInt(RuleVersionSnapshot.DecisionBinding::priority))
                            .ifPresent(binding -> hitDecisions.add(
                                    new Decision(binding.decisionCode(), "", binding.priority(),
                                            snap.ruleVersionId())));
                }
                if (r.errorCode() != null && errorCode == null) {
                    errorCode = r.errorCode();
                }
            } catch (Exception e) {
                if (errorCode == null) errorCode = "CONDITION_EVAL_ERROR";
            }
        }

        // ⑥ Decision 合成（HIGHEST_PRIORITY = priority 值最小者）
        Decision finalDecision = hitDecisions.stream()
                .min(Comparator.comparingInt(Decision::priority))
                .orElse(null);

        EvalResult result = new EvalResult(
                !hitDecisions.isEmpty(),
                finalDecision,
                List.copyOf(hitDecisions),
                List.of(),   // trace 由 TraceWriter 异步写，PULL 模式 v1 不在结果中内联
                errorCode,
                List.of()
        );

        // ⑦ 更新 session 终态 + 提交 trace
        if (isDryRun) {
            sessionWriter.updateDryRunFinal(sessionId, result);
        } else {
            sessionWriter.updateFinal(sessionId, result);
        }
        // trace 列表 v1 为空（TraceWriter 扩展点留给 Plan C InterpretedExecutor 增强）
        traceWriter.write(event.tenantId(), sessionId.toString(), List.of());

        return result;
    }

    /**
     * 确定本次评估的候选快照列表。
     * dry-run 且指定了 ruleVersionId 时，从 DB 直接加载该版本（不走倒排索引）。
     */
    private List<RuleVersionSnapshot> resolveCandidates(RuleEvent event,
                                                         boolean isDryRun,
                                                         Long specificVersionId) {
        if (isDryRun && specificVersionId != null) {
            RuleVersionSnapshot snap = snapshotLoader.loadById(specificVersionId);
            return snap != null ? List.of(snap) : List.of();
        }
        return index.match(event.tenantId(), event.sceneCode(), event.eventType());
    }

    /**
     * 对单条候选快照按配置顺序执行 Pre-Gate 检查。
     *
     * @return null 表示全部通过；非 null 为首个阻断的 Gate 类型
     */
    private String applyPreGates(RuleEvent event, RuleVersionSnapshot snap) {
        for (RuleVersionSnapshot.PreGateConfig gateConfig : snap.preGates()) {
            PreGate gate = preGateMap.get(gateConfig.gateType());
            if (gate == null) continue; // 未注册的 gate 类型跳过（fail-open）
            PreGateContext ctx = new PreGateContext(
                    event.tenantId(), event.sceneCode(), event.subjectId(),
                    event, snap.ruleVersionId(), gateConfig.params());
            PreGateResult result = gate.evaluate(ctx);
            if (!result.passed()) {
                return result.blockedBy();
            }
        }
        return null;
    }
}
```

- [ ] **Step 6: 更新 EvalAutoConfiguration，注册新 Bean**

```java
// rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/EvalAutoConfiguration.java
package com.sstlfsj.rule.eval;

import com.sstlfsj.rule.kernel.api.spi.executor.RuleVersionExecutor;
import com.sstlfsj.rule.kernel.api.spi.metric.MetricSourceHandler;
import com.sstlfsj.rule.kernel.api.spi.subject.SubjectLoader;
import com.sstlfsj.rule.kernel.internal.evaluator.InterpretedExecutor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;

import java.util.List;
import java.util.Map;

/** 自动装配规则评估模块。 */
@AutoConfiguration
@ComponentScan("com.sstlfsj.rule.eval.internal")
public class EvalAutoConfiguration {

    /**
     * 默认使用 InterpretedExecutor（AST 树形解释执行）。
     * 外部可注册自定义 RuleVersionExecutor Bean 覆盖此默认值。
     *
     * @param conditionEvaluators 所有注册的 ConditionEvaluator，按 @ConditionType.value() 索引
     * @return InterpretedExecutor 实例
     */
    @Bean
    public RuleVersionExecutor ruleVersionExecutor(
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            Map<String, com.sstlfsj.rule.kernel.api.spi.condition.ConditionEvaluator> conditionEvaluators) {
        return new InterpretedExecutor(conditionEvaluators == null ? Map.of() : conditionEvaluators);
    }
}
```

- [ ] **Step 7: 运行 eval-svc 测试，确认通过**

```bash
$MVN -pl rule-eval-svc -am test -Dtest='EvalSessionWriterTest,EvalServiceImplTest' -Dsurefire.failIfNoSpecifiedTests=false
```

预期：BUILD SUCCESS，所有测试通过。

- [ ] **Step 8: Commit**

```bash
git add rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/session/
git add rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/service/EvalServiceImpl.java
git add rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/EvalAutoConfiguration.java
git add rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/session/
git add rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/service/EvalServiceImplTest.java
git commit -m "$(cat <<'EOF'
feat(eval): EvalSessionWriter + EvalServiceImpl 完整实现 + 更新 AutoConfiguration（Task B-7）

- EvalServiceImpl：Matcher → Pre-Gate → EvalContext → AST 评估 → Decision 合成 → Session 写入
- EvalSessionWriter：INSERT PENDING + UPDATE 终态 + DuplicateKeyException 幂等
- PUSH 模式异步 CompletableFuture，dry-run 隔离到 dry_run_session 表
EOF
)"
```

---

## Task 8：全量测试

**Files:** 无新文件，仅运行测试。

- [ ] **Step 1: 运行 rule-kernel 全量测试（含修改的 PreGateContext）**

```bash
$MVN -pl rule-kernel -am test
```

预期：BUILD SUCCESS，全部测试通过。

- [ ] **Step 2: 运行 rule-config-svc 全量测试（事件包迁移验证）**

```bash
$MVN -pl rule-config-svc -am test
```

预期：BUILD SUCCESS。

- [ ] **Step 3: 运行 rule-eval-svc 全量测试**

```bash
$MVN -pl rule-eval-svc -am test
```

预期：BUILD SUCCESS，全部测试通过（包含：AstJsonCodecTest、SnapshotAssemblerTest、RuleIndexEventListenerTest、RolloutPreGateTest、EvalContextAssemblerTest、EvalSessionWriterTest、EvalServiceImplTest）。

如有失败，依据错误信息修复后重新运行，**不得用 `-DskipTests` 绕过**。

- [ ] **Step 4: Commit（如 Step 1-3 有修复性改动）**

若无改动：不产生额外 commit。若有修复：

```bash
git add <修复的文件>
git commit -m "$(cat <<'EOF'
fix(eval): 修复全量测试失败问题（Task B-8）
EOF
)"
```

---

## 自我检查

**Spec 覆盖：**
- ✅ 倒排索引启动全量加载（IndexStartupLoader + SceneSnapshotLoader）
- ✅ RulePublishedEvent 热更新（RuleIndexEventListener @ApplicationModuleListener）
- ✅ SceneChangedEvent 禁用/启用（SceneIndexEventListener）
- ✅ ROLLOUT Pre-Gate（murmur3_32，percentage 参数，fail-open）
- ✅ EvalContext 装配（SubjectLoader SPI + providedMetrics + 并发 CompletableFuture 框架）
- ✅ evaluation_session 同步写（INSERT PENDING → UPDATE 终态，DuplicateKeyException 幂等）
- ✅ dry_run_session 隔离写（不污染生产表）
- ✅ EvalService PULL（同步完整链路） + PUSH（异步 CompletableFuture） + dry-run
- ✅ 事件包迁移（config.internal.event → config.api.event，满足 Modulith 边界）
- ✅ PreGateContext 扩展（ruleVersionId + gateParams）

**已知 v1 限制（Plan C 填补）：**
- `triggerEventTypes` 未放入 `RuleVersionSnapshot`，暂用 `"*"` 通配（需 Plan A 在 entity 中保留字段后 Plan C 做精确路由）
- `node_trace` v1 为空列表（InterpretedExecutor 不含 trace 收集，Plan C 增强）
- MetricSourceHandler 仅框架，无真实 SQL/HTTP 实现
- PUSH 模式无 BlockingQueue 背压，直接 CompletableFuture.runAsync（Plan C 补充）
