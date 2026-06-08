# 评估结果事件化异步持久化 + action 异步派发 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development 或 superpowers:executing-plans 逐任务实现。Steps 用 checkbox（`- [ ]`）跟踪。

**Goal:** 把评估后副作用（审计落库 + action 派发）从请求线程搬到事件驱动异步：审计 best-effort 内存异步（可丢、请求线程 0 DB 写），action 持久 outbox at-least-once（不可丢、handler 幂等），预留 Delivery 抽象作 MQ 缝。

**Architecture:** `EvalServiceImpl.doEvaluate` 算完即发事件并同步返回 EvalResult。`AuditRecorded`（内存 @Async）→ `AuditPersister` 批量落 session（单次终态 INSERT）；`DispatchActionsCommand`（命中且有 action 时，经 `ActionCommandChannel` 持久投递）→ `ActionDispatcher` 异步执行 handler。sessionId 由请求线程用 MyBatis-Plus `IdWorker` 生成。

**Tech Stack:** Spring Boot 4.0.6 / Spring Modulith 2.0.6（events-api 已在，本期加 events-jdbc 做 outbox）/ MyBatis-Plus `IdWorker`（snowflake）/ 虚拟线程异步队列（仿 `TraceWriterDbImpl`）/ JUnit5+Mockito+AssertJ + `@ApplicationModuleTest`/`@SpringBootTest`。

---

## 关键事实（实现前必读，均经核实）

1. **现状**：`EvalServiceImpl.doEvaluate`（rule-eval-svc/.../service/EvalServiceImpl.java:73）同步做 `sessionWriter.insertPending`(INSERT) → `evaluateWithContext` → `sessionWriter.updateFinal`(UPDATE) → `traceWriter.write`（已异步 enqueue）→ `actionDispatchService.dispatch`（同步：`findBySceneCode` SELECT + N 条 action_execution INSERT + 执行 handler）。压测证明同步 session 两写是吞吐墙。
2. **Modulith**：项目只有 `spring-modulith-events-api`（rule-eval-svc/pom.xml:27），**无持久化模块**。outbox 需加 `spring-modulith-events-jdbc` + `event_publication` 表。现有 Modulith 事件（`RuleIndexEventListener` 等）是内存型，审计内存事件沿用此风格。
3. **`@ApplicationModuleListener`** = `@Async`+`@Transactional(REQUIRES_NEW)`+`@TransactionalEventListener(AFTER_COMMIT)`，**要求发布发生在事务内**（事务提交时 event_publication 行落库，listener 后置异步执行，未完成项重启重投）。→ action 发布路径须 `@Transactional`（这就是"命中有 action 时的 1 次 outbox 写"）；审计内存事件走普通 `@Async @EventListener`，无需事务。
4. **id**：`com.baomidou.mybatisplus.core.toolkit.IdWorker.getId()` 生成 snowflake（MP 已在,无新依赖）。`EvaluationSession.id`(Long) 现为 DB 自增；改为请求线程生成、客户端赋值（`@TableId(type = IdType.INPUT)`，MySQL 自增列允许显式 id，**无需迁移**）。
5. **`EvaluationSessionMapper extends BaseMapper`**：`insert(entity)` 可用；两写合一 = 构造完整终态 `EvaluationSession`（含 id/status/finalDecision/hitDecisions/candidate/hit 计数/started/finished/contextSnapshot）单次 `insert`。`uk_tenant_event` 防重复行（best-effort 下 DuplicateKey 静默吞）。
6. **异步批量模板**：`rule-observability/.../trace/TraceWriterDbImpl.java`——`LinkedBlockingQueue`+虚拟线程+`drainTo` 批量+关机 flush。`AuditPersister` 照此实现。
7. **action 审计 + 派发**：现 `ActionDispatchService.dispatch(sessionId, tenantId, eventId, sceneCode, hitDecisions)`（rule-eval-svc/.../action/ActionDispatchService.java）整体逻辑搬进 `ActionDispatcher`，由持久事件触发；幂等责任在 handler（at-least-once）。
8. **miss 语义**：无候选短路 miss（`candidates.isEmpty()`）直接 return，**不发事件**（保留现状不落库）；有候选评估后 MISS/HIT/ERROR 才发 `AuditRecorded`。

**spike（Task 1）须验证（未核实）**：(a) `spring-modulith-events-jdbc` 在本 BOM 的坐标/版本;(b) event_publication 表 DDL（用 Flyway 迁移 or jdbc schema-init）;(c) `@ApplicationModuleListener` 在 Boot4+Modulith2.0.6 下：发布须在事务内、listener AFTER_COMMIT 异步执行、抛异常时 publication 未完成并可重投。

## 文件结构

```
rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/
├── async/
│   ├── AuditRecorded.java              (Task 2) 内存审计事件 record
│   ├── DispatchActionsCommand.java            (Task 4) 持久 action 事件 record
│   ├── EvaluationEventPublisher.java   (Task 2/4) 发布点（命中有 action 才发 DispatchActionsCommand）
│   ├── AuditPersister.java             (Task 3) 消费 AuditRecorded，批量落 session
│   ├── ActionCommandChannel.java      (Task 4) 投递抽象（MQ 缝）
│   ├── ModulithOutboxDeliveryChannel.java (Task 4) 本期实现
│   └── ActionDispatcher.java           (Task 4) 消费 DispatchActionsCommand，执行 handler
├── service/EvalServiceImpl.java        (Task 5) 改造：发事件、去同步写、snowflake id
└── domain/EvaluationSession.java       (Task 5) @TableId(type=INPUT)
rule-config-svc/src/main/resources/db/migration/V1_9__event_publication.sql (Task 1)
rule-eval-svc/pom.xml                   (Task 1) 加 events-jdbc
```

---

## Task 1: spike — Modulith 持久事件（outbox）基础

**目的**：打通"事务内发布持久事件 → `@ApplicationModuleListener` 异步消费 → 抛异常时 publication 未完成可重投"，定死未知点 (a)(b)(c)。**通则继续；两次试不通则停下报告，不 hack。**

**Files:**
- Modify: `rule-eval-svc/pom.xml`
- Create: `rule-config-svc/src/main/resources/db/migration/V1_9__event_publication.sql`
- Create: `rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/async/ModulithOutboxSpikeTest.java`

- [ ] **Step 1: 加 events-jdbc 依赖**

`rule-eval-svc/pom.xml` 在 `spring-modulith-events-api` 依赖后加：

```xml
        <dependency>
            <groupId>org.springframework.modulith</groupId>
            <artifactId>spring-modulith-events-jdbc</artifactId>
        </dependency>
```
> 版本由 spring-modulith BOM（2.0.6）管理，不写 version。若 BOM 未含，spike 时补 `<version>${spring-modulith.version}</version>`。

- [ ] **Step 2: event_publication 表迁移**

`V1_9__event_publication.sql`（Spring Modulith jdbc MySQL schema）：

```sql
CREATE TABLE IF NOT EXISTS event_publication (
    id               VARCHAR(36)  NOT NULL,
    listener_id      VARCHAR(512) NOT NULL,
    event_type       VARCHAR(512) NOT NULL,
    serialized_event TEXT         NOT NULL,
    publication_date  TIMESTAMP(6) NOT NULL,
    completion_date   TIMESTAMP(6) NULL,
    PRIMARY KEY (id),
    INDEX idx_event_publication_completion_date (completion_date),
    INDEX idx_event_publication_listener_id_completion (listener_id, completion_date)
);
```
> 关闭 Modulith 自动建表，交给 Flyway：`application.yml` 加 `spring.modulith.events.jdbc.schema-initialization.enabled=false`（默认即不建，确认即可）。

- [ ] **Step 3: 写 spike 测试（持久事件 + 消费 + 重投）**

`ModulithOutboxSpikeTest.java`：

```java
package com.sstlfsj.rule.eval.async;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** spike：验证 Modulith 持久事件在事务内发布、AFTER_COMMIT 异步消费。 */
@SpringBootTest
class ModulithOutboxSpikeTest {

    record SpikeEvent(String payload) {}

    @Component
    static class SpikePublisher {
        @Autowired ApplicationEventPublisher publisher;
        @Transactional
        void fire(String p) { publisher.publishEvent(new SpikeEvent(p)); }
    }

    @Component
    static class SpikeConsumer {
        static final CountDownLatch latch = new CountDownLatch(1);
        static volatile String received;
        @ApplicationModuleListener
        void on(SpikeEvent e) { received = e.payload(); latch.countDown(); }
    }

    @Autowired SpikePublisher publisher;

    @Test
    void persistentEventPublishedInTxAndConsumedAfterCommit() throws InterruptedException {
        publisher.fire("hello");
        assertThat(SpikeConsumer.latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(SpikeConsumer.received).isEqualTo("hello");
    }
}
```

- [ ] **Step 4: 跑 spike**

```bash
$MVN -pl rule-eval-svc -am -Dtest=ModulithOutboxSpikeTest test
```

Expected：PASS——事件经 event_publication 持久并被 `@ApplicationModuleListener` 消费。
- 若编译/装配报缺 bean / 缺表 → 按报错补：events-jdbc 坐标(a)、表 DDL(b)、`@EnableScheduling`/`spring.modulith.events` 配置(c)。
- 若 listener 不触发 → 确认发布在 `@Transactional` 内（AFTER_COMMIT 需要提交）。
- **两次调整仍不通 → 停止，记录失败现象（缺哪个 bean/表/配置）+ 已尝试，向用户报告 spike 卡点，不继续后续 Task。**

- [ ] **Step 5: Commit（spike 通过才提交）**

```bash
git add rule-eval-svc/pom.xml rule-config-svc/src/main/resources/db/migration/V1_9__event_publication.sql rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/async/ModulithOutboxSpikeTest.java
git commit -m "spike(eval-async): Modulith 持久事件 outbox 基础打通（events-jdbc + event_publication）"
```

---

## Task 2: AuditRecorded 事件 + 发布点（内存）

**Files:**
- Create: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/async/AuditRecorded.java`
- Create: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/async/EvaluationEventPublisher.java`
- Test: `rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/async/EvaluationEventPublisherTest.java`

- [ ] **Step 1: 写失败测试**

`EvaluationEventPublisherTest.java`：

```java
package com.sstlfsj.rule.eval.async;

import com.sstlfsj.rule.eval.internal.async.AuditRecorded;
import com.sstlfsj.rule.eval.internal.async.EvaluationEventPublisher;
import com.sstlfsj.rule.kernel.api.model.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 验证审计事件总在评估后发布。 */
class EvaluationEventPublisherTest {

    @Test
    void publishesAuditRecorded() {
        ApplicationEventPublisher spring = mock(ApplicationEventPublisher.class);
        EvaluationEventPublisher pub = new EvaluationEventPublisher(spring);

        RuleEvent event = RuleEvent.builder().tenantId("1").sceneCode("s").eventType("t")
                .subjectId("u1").eventId("e1").source(EventSource.HTTP)
                .occurredAt(Instant.now()).build();
        EvalResult result = EvalResult.miss();

        pub.publishAudit(99L, event, "PULL", 3, result, null);

        verify(spring).publishEvent(any(AuditRecorded.class));
    }
}
```

- [ ] **Step 2: 运行测试,确认失败**

```bash
$MVN -pl rule-eval-svc -am -Dtest=EvaluationEventPublisherTest test
```
Expected：编译失败（类不存在）。

- [ ] **Step 3: 写 AuditRecorded + EvaluationEventPublisher（仅审计部分，action 在 Task 4 补）**

`AuditRecorded.java`：

```java
package com.sstlfsj.rule.eval.internal.async;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;

/** 审计领域事件（内存 best-effort）：评估完成的事实，供异步持久化 session/trace。 */
public record AuditRecorded(long sessionId, RuleEvent event, String mode,
                            int candidateCount, EvalResult result, EvalContext context) {}
```

`EvaluationEventPublisher.java`：

```java
package com.sstlfsj.rule.eval.internal.async;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/** 评估出站事件发布点：审计始终发；action 在命中且有绑定时发（Task 4 补 publishActions）。 */
@Component
public class EvaluationEventPublisher {

    private final ApplicationEventPublisher publisher;

    public EvaluationEventPublisher(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    /** 发布审计事件（内存 best-effort）。 */
    public void publishAudit(long sessionId, RuleEvent event, String mode,
                             int candidateCount, EvalResult result, EvalContext context) {
        publisher.publishEvent(new AuditRecorded(sessionId, event, mode, candidateCount, result, context));
    }
}
```

- [ ] **Step 4: 运行测试,确认通过**

```bash
$MVN -pl rule-eval-svc -am -Dtest=EvaluationEventPublisherTest test
```
Expected：PASS。

- [ ] **Step 5: Commit**

```bash
git add rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/async/AuditRecorded.java rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/async/EvaluationEventPublisher.java rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/async/EvaluationEventPublisherTest.java
git commit -m "feat(eval-async): AuditRecorded 事件 + 发布点（审计内存事件）"
```

---

## Task 3: AuditPersister（异步批量落 session）

**Files:**
- Create: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/async/AuditPersister.java`
- Test: `rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/async/AuditPersisterTest.java`

- [ ] **Step 1: 写失败测试（消费事件 → 单次终态 session INSERT）**

`AuditPersisterTest.java`：

```java
package com.sstlfsj.rule.eval.async;

import com.sstlfsj.rule.eval.internal.async.AuditPersister;
import com.sstlfsj.rule.eval.internal.async.AuditRecorded;
import com.sstlfsj.rule.eval.internal.domain.EvaluationSession;
import com.sstlfsj.rule.eval.internal.repository.EvaluationSessionMapper;
import com.sstlfsj.rule.kernel.api.model.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 验证审计事件被消费后单次 INSERT 终态 session（不再 PENDING→UPDATE）。 */
class AuditPersisterTest {

    @Test
    void insertsTerminalSessionOnce() throws Exception {
        EvaluationSessionMapper mapper = mock(EvaluationSessionMapper.class);
        AuditPersister persister = new AuditPersister(2000, 200, 50, mapper);
        persister.afterPropertiesSet();

        RuleEvent event = RuleEvent.builder().tenantId("1").sceneCode("s").eventType("t")
                .subjectId("u1").eventId("e1").source(EventSource.HTTP).occurredAt(Instant.now()).build();
        persister.onAudit(new AuditRecorded(42L, event, "PULL", 1, EvalResult.miss(), null));

        // 等待异步消费
        Thread.sleep(300);
        persister.destroy();

        var captor = org.mockito.ArgumentCaptor.forClass(EvaluationSession.class);
        verify(mapper, times(1)).insert(captor.capture());
        EvaluationSession s = captor.getValue();
        assertThat(s.getId()).isEqualTo(42L);
        assertThat(s.getStatus()).isEqualTo("MISS");
        assertThat(s.getTenantId()).isEqualTo(1L);
        verify(mapper, never()).markFinal(anyLong(), any(), any(), any(), any(), any(), any(), any());
    }
}
```

- [ ] **Step 2: 运行,确认失败**

```bash
$MVN -pl rule-eval-svc -am -Dtest=AuditPersisterTest test
```
Expected：编译失败。

- [ ] **Step 3: 写 AuditPersister（仿 TraceWriterDbImpl）**

`AuditPersister.java`：

```java
package com.sstlfsj.rule.eval.internal.async;

import com.sstlfsj.rule.eval.internal.domain.EvaluationSession;
import com.sstlfsj.rule.eval.internal.repository.EvaluationSessionMapper;
import com.sstlfsj.rule.kernel.api.model.Decision;
import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.stream.Collectors;

/** 异步批量落 evaluation_session（单次终态 INSERT）。best-effort：队列满丢弃，不阻塞评估。 */
@Component
public class AuditPersister implements InitializingBean, DisposableBean {

    private final int queueCapacity;
    private final int batchSize;
    private final long flushIntervalMs;
    private final EvaluationSessionMapper sessionMapper;

    private LinkedBlockingQueue<AuditRecorded> queue;
    private volatile boolean running = false;
    private Thread consumerThread;

    public AuditPersister(int queueCapacity, int batchSize, long flushIntervalMs,
                          EvaluationSessionMapper sessionMapper) {
        this.queueCapacity = queueCapacity;
        this.batchSize = batchSize;
        this.flushIntervalMs = flushIntervalMs;
        this.sessionMapper = sessionMapper;
    }

    public AuditPersister(EvaluationSessionMapper sessionMapper) {
        this(10000, 500, 200, sessionMapper);
    }

    @Override
    public void afterPropertiesSet() {
        queue = new LinkedBlockingQueue<>(queueCapacity);
        running = true;
        consumerThread = Thread.ofVirtual().name("audit-persister").start(this::consumeLoop);
    }

    /** 接审计事件，非阻塞入队（队列满丢弃，best-effort）。 */
    @Async
    @EventListener
    public void onAudit(AuditRecorded e) {
        queue.offer(e);
    }

    private void consumeLoop() {
        while (running || !queue.isEmpty()) {
            try {
                Thread.sleep(flushIntervalMs);
                flushBatch();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void flushBatch() {
        List<AuditRecorded> batch = new ArrayList<>(batchSize);
        queue.drainTo(batch, batchSize);
        for (AuditRecorded e : batch) {
            try {
                sessionMapper.insert(toSession(e));
            } catch (DuplicateKeyException ignored) {
                // 幂等：相同 eventId 已落库，丢弃重复（best-effort）
            } catch (RuntimeException ignored) {
                // 审计可丢，不影响主流程
            }
        }
    }

    private EvaluationSession toSession(AuditRecorded e) {
        RuleEvent ev = e.event();
        EvalResult r = e.result();
        EvaluationSession s = new EvaluationSession();
        s.setId(e.sessionId());
        s.setTenantId(Long.valueOf(ev.tenantId()));
        s.setEventId(ev.eventId());
        s.setSceneCode(ev.sceneCode());
        s.setEventType(ev.eventType());
        s.setSubjectId(ev.subjectId());
        s.setSource(ev.source().name());
        s.setMode(e.mode());
        s.setStatus(r.errorCode() != null ? "ERROR" : (r.ruleHit() ? "HIT" : "MISS"));
        s.setFinalDecision(r.finalDecision() != null ? r.finalDecision().code() : null);
        s.setHitDecisions(r.hitDecisions().isEmpty() ? "[]"
                : r.hitDecisions().stream().map(Decision::code)
                    .collect(Collectors.joining("\",\"", "[\"", "\"]")));
        s.setErrorCode(r.errorCode());
        s.setCandidateRuleCount(e.candidateCount());
        s.setHitRuleCount(r.hitDecisions().size());
        if (ev.occurredAt() != null) {
            s.setOccurredAt(LocalDateTime.ofInstant(ev.occurredAt(), ZoneId.systemDefault()));
        }
        LocalDateTime now = LocalDateTime.now();
        s.setStartedAt(now);
        s.setFinishedAt(now);
        return s;
    }

    @Override
    public void destroy() {
        running = false;
        flushBatch();
        if (consumerThread != null) consumerThread.interrupt();
    }
}
```
> 注：`@Async` 需 `@EnableAsync`（rule-app 主类或 eval autoconfig 上确认/补）。trace 落库仍由现有 `TraceWriter`（已异步），Task 5 决定 trace.write 调用位置。

- [ ] **Step 4: 运行,确认通过**

```bash
$MVN -pl rule-eval-svc -am -Dtest=AuditPersisterTest test
```
Expected：PASS。

- [ ] **Step 5: Commit**

```bash
git add rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/async/AuditPersister.java rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/async/AuditPersisterTest.java
git commit -m "feat(eval-async): AuditPersister 异步批量落 session（单次终态 INSERT）"
```

---

## Task 4: DispatchActionsCommand + Delivery 抽象 + ActionDispatcher

**Files:**
- Create: `DispatchActionsCommand.java` / `ActionCommandChannel.java` / `ModulithOutboxDeliveryChannel.java` / `ActionDispatcher.java`（均 rule-eval-svc/.../internal/async/）
- Modify: `EvaluationEventPublisher.java`（加 `publishActions`）
- Test: `ActionDispatcherIdempotencyTest.java`

- [ ] **Step 1: 写事件 + 抽象 + 实现 + 消费者**

`DispatchActionsCommand.java`：

```java
package com.sstlfsj.rule.eval.internal.async;

import com.sstlfsj.rule.kernel.api.model.Decision;
import java.io.Serializable;
import java.util.List;

/** action 派发事件（持久 outbox，at-least-once）。Serializable 供 event_publication 序列化。 */
public record DispatchActionsCommand(long sessionId, long tenantId, String eventId,
                              String sceneCode, List<Decision> hitDecisions) implements Serializable {}
```

`ActionCommandChannel.java`：

```java
package com.sstlfsj.rule.eval.internal.async;

/** action 派发事件的可靠投递契约（at-least-once）。本期 Modulith outbox 实现，下期可换 MQ。 */
public interface ActionCommandChannel {
    void deliver(DispatchActionsCommand event);
}
```

`ModulithOutboxDeliveryChannel.java`：

```java
package com.sstlfsj.rule.eval.internal.async;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 本期实现：发布持久事件（Modulith event_publication outbox）。须在事务内发布。 */
@Component
public class ModulithOutboxDeliveryChannel implements ActionCommandChannel {

    private final ApplicationEventPublisher publisher;

    public ModulithOutboxDeliveryChannel(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    @Override
    @Transactional
    public void deliver(DispatchActionsCommand event) {
        publisher.publishEvent(event);
    }
}
```

`ActionDispatcher.java`（复用 ActionDispatchService 逻辑，持久事件触发）：

```java
package com.sstlfsj.rule.eval.internal.async;

import com.sstlfsj.rule.eval.internal.action.ActionDispatchService;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/** 消费持久 action 事件，异步派发（at-least-once；幂等责任在 handler）。 */
@Component
public class ActionDispatcher {

    private final ActionDispatchService dispatchService;

    public ActionDispatcher(ActionDispatchService dispatchService) {
        this.dispatchService = dispatchService;
    }

    @ApplicationModuleListener
    public void on(DispatchActionsCommand e) {
        dispatchService.dispatch(e.sessionId(), e.tenantId(), e.eventId(),
                e.sceneCode(), e.hitDecisions());
    }
}
```

`EvaluationEventPublisher` 加方法：

```java
    private final ActionCommandChannel actionDelivery;
    // 构造器注入 ActionCommandChannel（与 ApplicationEventPublisher 一起）

    /** 命中且有 action 绑定时发持久 action 事件。 */
    public void publishActions(long sessionId, long tenantId, String eventId, String sceneCode,
                               java.util.List<com.sstlfsj.rule.kernel.api.model.Decision> hitDecisions) {
        actionDelivery.deliver(new DispatchActionsCommand(sessionId, tenantId, eventId, sceneCode, hitDecisions));
    }
```
> 调整 `EvaluationEventPublisher` 构造器为 `(ApplicationEventPublisher, ActionCommandChannel)`，Task 2 的测试同步改 mock 两参。

- [ ] **Step 2: 写幂等测试**

`ActionDispatcherIdempotencyTest.java`：

```java
package com.sstlfsj.rule.eval.async;

import com.sstlfsj.rule.eval.internal.action.ActionDispatchService;
import com.sstlfsj.rule.eval.internal.async.ActionDispatcher;
import com.sstlfsj.rule.eval.internal.async.DispatchActionsCommand;
import com.sstlfsj.rule.kernel.api.model.Decision;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.*;

/** 验证 ActionDispatcher 委托 ActionDispatchService（at-least-once：重投即多次调用，幂等在 handler）。 */
class ActionDispatcherIdempotencyTest {

    @Test
    void delegatesToDispatchService() {
        ActionDispatchService svc = mock(ActionDispatchService.class);
        ActionDispatcher dispatcher = new ActionDispatcher(svc);
        DispatchActionsCommand e = new DispatchActionsCommand(7L, 1L, "e1", "s", List.of());

        dispatcher.on(e);
        dispatcher.on(e); // 重投

        verify(svc, times(2)).dispatch(7L, 1L, "e1", "s", List.of());
    }
}
```

- [ ] **Step 3: 运行测试,确认通过**

```bash
$MVN -pl rule-eval-svc -am -Dtest=ActionDispatcherIdempotencyTest,EvaluationEventPublisherTest test
```
Expected：PASS（EvaluationEventPublisherTest 已按两参构造器更新）。

- [ ] **Step 4: Commit**

```bash
git add rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/async/DispatchActionsCommand.java rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/async/ActionCommandChannel.java rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/async/ModulithOutboxDeliveryChannel.java rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/async/ActionDispatcher.java rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/async/EvaluationEventPublisher.java rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/async/ActionDispatcherIdempotencyTest.java rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/async/EvaluationEventPublisherTest.java
git commit -m "feat(eval-async): DispatchActionsCommand + Delivery 抽象 + ActionDispatcher（持久 at-least-once）"
```

---

## Task 5: 改造 EvalServiceImpl（发事件、去同步写、snowflake id）

**Files:**
- Modify: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/domain/EvaluationSession.java`（@TableId INPUT）
- Modify: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/service/EvalServiceImpl.java`
- Test: `rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/async/DoEvaluateEmitsEventsTest.java`

- [ ] **Step 1: EvaluationSession id 改客户端赋值**

`EvaluationSession.java` 的 `id` 字段注解改为（确认现有注解后替换）：

```java
    @com.baomidou.mybatisplus.annotation.TableId(type = com.baomidou.mybatisplus.annotation.IdType.INPUT)
    private Long id;
```

- [ ] **Step 2: 写失败测试（doEvaluate 发事件、不再同步写）**

`DoEvaluateEmitsEventsTest.java`：

```java
package com.sstlfsj.rule.eval.async;

import com.sstlfsj.rule.eval.internal.async.EvaluationEventPublisher;
import com.sstlfsj.rule.eval.internal.service.EvalServiceImpl;
import com.sstlfsj.rule.eval.internal.snapshot.SceneSnapshotLoader;
import com.sstlfsj.rule.eval.internal.action.ActionDispatchService;
import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.kernel.internal.engine.EvalEngine;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 验证命中评估发布审计 + action 事件，且不再走同步 ActionDispatchService.dispatch。 */
class DoEvaluateEmitsEventsTest {

    @Test
    void hitEvaluation_publishesAuditAndActions_noSyncDispatch() {
        EvalEngine engine = mock(EvalEngine.class);
        EvaluationEventPublisher publisher = mock(EvaluationEventPublisher.class);
        ActionDispatchService syncDispatch = mock(ActionDispatchService.class);

        RuleVersionSnapshot snap = mock(RuleVersionSnapshot.class);
        RuleEvent event = RuleEvent.builder().tenantId("1").sceneCode("s").eventType("t")
                .subjectId("u1").eventId("e1").source(EventSource.HTTP).occurredAt(Instant.now()).build();
        when(engine.match(event)).thenReturn(List.of(snap));
        Decision pass = new Decision("PASS", "", 1, 3L);
        EvalResult hit = EvalResult.builder().ruleHit(true).hitDecisions(List.of(pass))
                .finalDecision(pass).nodeTrace(List.of()).build();
        EvalOutcome outcome = new EvalOutcome(hit, null);
        when(engine.evaluateWithContext(eq(event), anyList(), any())).thenReturn(outcome);

        EvalServiceImpl svc = new EvalServiceImpl(engine, mock(SceneSnapshotLoader.class),
                publisher, syncDispatch);
        EvalResult result = svc.evaluate(event);

        // 同步返回结果
        org.assertj.core.api.Assertions.assertThat(result.ruleHit()).isTrue();
        // 发审计 + action 事件
        verify(publisher).publishAudit(anyLong(), eq(event), eq("PULL"), eq(1), eq(hit), any());
        verify(publisher).publishActions(anyLong(), eq(1L), eq("e1"), eq("s"), eq(List.of(pass)));
        // 不再同步派发
        verify(syncDispatch, never()).dispatch(anyLong(), anyLong(), any(), any(), anyList());
    }
}
```
> 注：`EvalServiceImpl` 构造器/字段按本测试期望调整（注入 `EvaluationEventPublisher`，去掉 `EvalSessionWriter`/`TraceWriter`/`DryRunTraceWriter` 同步依赖或仅保留 dry-run 用）。实际签名以现有代码 + 本测试为准对齐；dry-run 路径保持现状（其 trace 在响应里，不依赖异步）。

- [ ] **Step 3: 改造 doEvaluate**

`EvalServiceImpl.doEvaluate` 改为（保留 dry-run 分支不变；非 dry-run 主路径）：

```java
        List<RuleVersionSnapshot> candidates = evalEngine.match(event);
        if (candidates.isEmpty()) return EvalResult.miss();   // 无候选短路：不发事件（现状）

        long sessionId = com.baomidou.mybatisplus.core.toolkit.IdWorker.getId();
        EvalOutcome outcome = evalEngine.evaluateWithContext(event, candidates, evalNow);
        EvalResult result = outcome.result();

        eventPublisher.publishAudit(sessionId, event, mode, candidates.size(), result, outcome.context());
        if (result.ruleHit() && !result.hitDecisions().isEmpty()) {
            eventPublisher.publishActions(sessionId, parseTenantId(event.tenantId()),
                    event.eventId(), event.sceneCode(), result.hitDecisions());
        }
        return result;
```
> 删除 `sessionWriter.insertPending/updateFinal`、`traceWriter.write`、`actionDispatchService.dispatch` 的同步调用及对应字段/构造参数（trace 落库迁移：若要保留 node_trace，在 `AuditPersister` 消费 `AuditRecorded` 时调用注入的 `TraceWriter.write(tenantId, sessionId, result.nodeTrace())`——把 `TraceWriter` 注入 AuditPersister，Task 3 的事件已携带 result）。dry-run 分支（`isDryRun`）维持现有 `sessionWriter.insertDryRunPending/updateDryRunFinal/dryRunTraceWriter` 不动。

- [ ] **Step 4: 运行测试 + 模块全量**

```bash
$MVN -pl rule-eval-svc -am test
```
Expected：DoEvaluateEmitsEventsTest PASS；rule-eval-svc 既有测试全绿（如有依赖同步写的旧测试，按新异步契约调整断言——改为验证发事件而非验证 mapper.insert）。

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(eval-async): doEvaluate 改事件驱动（snowflake id + 去同步 session/action 写）"
```

---

## Task 6: 回归 + 压测复测

- [ ] **Step 1: rule-eval-svc + rule-app 全量回归**

```bash
$MVN -pl rule-eval-svc -am test
$MVN -pl rule-app -am test
```
Expected：全绿。集成测试（`EvalIntegrationTest`）若断言"评估后立刻查到 session"，因异步需改为**轮询等待最终一致**（`Awaitility` 或 sleep+重查）或验证发事件。

- [ ] **Step 2: 压测复测（同 load-test 3 臂）**

```bash
$MVN -pl rule-app -am -DskipTests package
$MVN -pl rule-app -Dtest=LoadTestSeeder#seed50 -Dgroups=loadtest -DfailIfNoTests=false test
# 基线 vs 改造后：java -jar ... ；k6 run load-test/k6/evaluate.js
```
Expected：**无 action 评估请求线程 0 DB 写**（Hikari active 远低于池上限 / pending=0）、吞吐冲破 600 单机墙；结果记入 `load-test/README.md` 新增"事件化后"行。

- [ ] **Step 3: Commit**

```bash
git add load-test/README.md
git commit -m "test(eval-async): 事件化后压测复测（吞吐 vs 基线）"
```

---

## Self-Review

**1. Spec coverage（对照 2026-06-08-eval-async-persistence-design.md）：**
- §3 请求线程纯计算同步返回 + 两事件 → Task 5 ✅
- §3 审计内存可丢 / action 持久不丢、持久写仅命中有 action → Task 2/3（内存）+ Task 4（持久,publishActions 条件）✅
- §4 ActionCommandChannel 抽象 + Modulith outbox 实现 → Task 4 ✅
- §5 at-least-once + handler 幂等 / 审计 best-effort / session 两写合一 / snowflake → Task 1(outbox)/3(单插)/4(幂等)/5(id) ✅
- §6 组件边界 / EvalEngine 不动 / 契约不变 → Task 5（仅改 doEvaluate）✅
- §7 测试（发布点/批量/幂等/at-least-once/端到端）+ 验收压测 → Task 2-6 ✅
- §8 非目标（MQ 实现/DLQ/强审计）→ 不含 ✅

**2. Placeholder scan：** Task 1 三未知点 (a)(b)(c) 是 spike 显式待解项（给了报错→动作）。`EvaluationSession` 现注解、`EvalServiceImpl` 现构造签名标注"以现有代码 + 测试为准对齐"——这两处需实现时读文件定签名，属合理（依赖现状）。trace 落库迁移位置在 Task 5 给了明确方案（注入 TraceWriter 到 AuditPersister）。无 TODO/空话。

**3. Type consistency：** `AuditRecorded(sessionId,event,mode,candidateCount,result,context)` Task 2 定义、Task 3 消费、Task 5 发布一致；`DispatchActionsCommand(sessionId,tenantId,eventId,sceneCode,hitDecisions)` Task 4 定义、Task 4/5 用一致；`EvaluationEventPublisher.publishAudit/publishActions` 签名跨 Task 2/4/5 一致；`ActionCommandChannel.deliver(DispatchActionsCommand)` Task 4 定义/实现/调用一致；`IdWorker.getId():long` 与 session id 客户端赋值一致。
