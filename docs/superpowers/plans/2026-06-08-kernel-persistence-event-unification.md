# 内核落库统一事件化 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把内核四条落库统一成「领域事件 → 单一投递缝 `DomainEventPublisher` → 各 persister」,事件自带 durability,进程内复用 Spring 异步事件,预留唯一 MQ 缝。

**Architecture:** 新增 `DomainEvent`/`Durability`/`DomainEventPublisher` 契约 + 进程内实现;3 领域事件(`AuditRecorded`/`ActionExecuted`/`DryRunRecorded`)各由专属 persister 消费;生产方(EvalServiceImpl、ActionDispatchService)只 `publish`,不内联落库;删除 `EvaluationEventPublisher` 与 `EvalSessionWriter`。

**Tech Stack:** Java 25 / Spring Boot 4(ApplicationEventPublisher + @EventListener)/ MyBatis-Plus / tools.jackson / JUnit5 + Mockito。

**Spec:** `docs/superpowers/specs/2026-06-08-kernel-persistence-event-unification-design.md`

---

## 环境前置(每 task 跑测试前)
```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-25.jdk/Contents/Home
export PATH=$JAVA_HOME/bin:$PATH
MVN=/Users/sunke/.m2/wrapper/dists/apache-maven-3.9.9-bin/4nf9hui3q3djbarqar9g711ggc/apache-maven-3.9.9/bin/mvn
cd /Users/sunke/dev/ai-project/rule-engine
```
> 全量回归务必带 `-am`(避免 reactor 旧 jar 假报失败,本 session 已踩过)。

## 文件清单
| 文件 | 动作 |
|---|---|
| `internal/event/DomainEvent.java` / `Durability.java` / `DomainEventPublisher.java` | 建(契约) |
| `internal/event/InProcessDomainEventPublisher.java` (+Test) | 建(进程内实现) |
| `internal/async/AuditRecorded.java` | 改(implements DomainEvent) |
| `internal/async/ActionExecuted.java` | 建(事件) |
| `internal/action/ActionExecutionPersister.java` (+Test) | 建(persister) |
| `internal/action/ActionDispatchService.java` (+Test) | 改(发 ActionExecuted 替内联 insert) |
| `internal/async/DryRunRecorded.java` | 建(事件) |
| `internal/async/DryRunPersister.java` (+Test) | 建(persister,吸收 EvalSessionWriter) |
| `internal/domain/DryRunSession.java` | 改(@TableId INPUT) |
| `internal/service/EvalServiceImpl.java` (+Tests) | 改(注入 DomainEventPublisher+ActionDeliveryChannel,publish-only) |
| `internal/async/EvaluationEventPublisher.java` (+Test) | 删 |
| `internal/session/EvalSessionWriter.java` (+Test) | 删 |
| `EvalAutoConfiguration.java` | 改(dispatch bean 注入 DomainEventPublisher) |

---

## Task 1: 契约 + 进程内实现

**Files:** Create `internal/event/{DomainEvent,Durability,DomainEventPublisher,InProcessDomainEventPublisher}.java`; Test `InProcessDomainEventPublisherTest.java`

- [ ] **Step 1: 写失败测试** `rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/event/InProcessDomainEventPublisherTest.java`
```java
package com.sstlfsj.rule.eval.internal.event;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import static org.mockito.Mockito.*;

class InProcessDomainEventPublisherTest {

    private record SampleEvent() implements DomainEvent {
        public Durability durability() { return Durability.BEST_EFFORT; }
    }

    @Test
    void publish_delegatesToApplicationEventPublisher() {
        ApplicationEventPublisher spring = mock(ApplicationEventPublisher.class);
        DomainEventPublisher pub = new InProcessDomainEventPublisher(spring);
        SampleEvent e = new SampleEvent();

        pub.publish(e);

        verify(spring).publishEvent(e);
    }
}
```

- [ ] **Step 2: 跑测试确认失败(类不存在)**
`$MVN -pl rule-eval-svc -am test -Dtest='InProcessDomainEventPublisherTest' -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | grep -E 'ERROR|BUILD'`

- [ ] **Step 3: 实现**

`Durability.java`:
```java
package com.sstlfsj.rule.eval.internal.event;

/** 领域事件投递可靠性等级:进程内为路由元数据,MQ 决定 topic/ack。 */
public enum Durability { BEST_EFFORT, AT_LEAST_ONCE }
```
`DomainEvent.java`:
```java
package com.sstlfsj.rule.eval.internal.event;

/** 内核落库领域事件统一标记;实现者声明自身 durability。 */
public interface DomainEvent {
    Durability durability();
}
```
`DomainEventPublisher.java`:
```java
package com.sstlfsj.rule.eval.internal.event;

/** 领域事件唯一发布缝:进程内 / MQ 各一实现,发布方与 persister 不感知 transport。 */
public interface DomainEventPublisher {
    void publish(DomainEvent event);
}
```
`InProcessDomainEventPublisher.java`:
```java
package com.sstlfsj.rule.eval.internal.event;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/** 进程内实现:转 Spring 应用事件,由各 persister 的 @EventListener 消费。durability 当前为元数据。 */
@Component
public class InProcessDomainEventPublisher implements DomainEventPublisher {

    private final ApplicationEventPublisher publisher;

    public InProcessDomainEventPublisher(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    @Override
    public void publish(DomainEvent event) {
        publisher.publishEvent(event);
    }
}
```

- [ ] **Step 4: 跑测试确认通过**
`$MVN -pl rule-eval-svc -am test -Dtest='InProcessDomainEventPublisherTest' -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | grep -E 'Tests run|BUILD'` → `Tests run: 1 ... BUILD SUCCESS`

- [ ] **Step 5: 提交**
```bash
git add rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/event/ \
        rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/event/
git commit -m "feat(eval): DomainEvent/Durability/DomainEventPublisher 契约 + 进程内实现"
```

---

## Task 2: `AuditRecorded` 实现 `DomainEvent`

**Files:** Modify `internal/async/AuditRecorded.java`

- [ ] **Step 1: 实现(整体替换 record 声明,加 implements + durability)**
```java
package com.sstlfsj.rule.eval.internal.async;

import com.sstlfsj.rule.eval.internal.event.DomainEvent;
import com.sstlfsj.rule.eval.internal.event.Durability;
import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;

/**
 * 审计领域事件(best-effort):一次评估完成的事实,供异步持久化 evaluation_session + node_trace。
 */
public record AuditRecorded(long sessionId, RuleEvent event, String mode,
                            int candidateCount, EvalResult result, EvalContext context,
                            String blockedBy) implements DomainEvent {
    @Override
    public Durability durability() { return Durability.BEST_EFFORT; }
}
```
> 字段与原 record 完全一致,仅加 `implements DomainEvent` + `durability()`。AuditPersister 的 `@EventListener onAudit(AuditRecorded)` 不变。

- [ ] **Step 2: 编译 + 既有审计测试不破**
`$MVN -pl rule-eval-svc -am test -Dtest='AuditPersisterTest,EvaluationEventPublisherTest' -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | grep -E 'Tests run|BUILD'` → BUILD SUCCESS

- [ ] **Step 3: 提交**
```bash
git add rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/async/AuditRecorded.java
git commit -m "feat(eval): AuditRecorded 实现 DomainEvent(BEST_EFFORT)"
```

---

## Task 3: `ActionExecuted` 事件 + `ActionExecutionPersister` + dispatch 改发布(原子)

**Files:** Create `internal/async/ActionExecuted.java`, `internal/action/ActionExecutionPersister.java` (+Test); Modify `internal/action/ActionDispatchService.java` (+Test), `EvalAutoConfiguration.java`

- [ ] **Step 1: 写失败测试** `ActionExecutionPersisterTest.java`
```java
package com.sstlfsj.rule.eval.internal.action;

import com.sstlfsj.rule.eval.internal.async.ActionExecuted;
import com.sstlfsj.rule.eval.internal.domain.ActionExecutionEntity;
import com.sstlfsj.rule.eval.internal.repository.ActionExecutionMapper;
import com.sstlfsj.rule.kernel.api.model.ActionResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ActionExecutionPersisterTest {

    @Test
    void accept_insertsActionExecutionRow() {
        ActionExecutionMapper mapper = mock(ActionExecutionMapper.class);
        ActionExecutionPersister persister = new ActionExecutionPersister(mapper);
        ActionResult result = ActionResult.success("BLOCK_TRANSACTION", "BLOCK_TRANSACTION");

        persister.accept(new ActionExecuted(42L, 1L, "evt-1",
                "BLOCK_TRANSACTION", "BLOCK_TRANSACTION", "REJECT", result));

        ArgumentCaptor<ActionExecutionEntity> captor = ArgumentCaptor.forClass(ActionExecutionEntity.class);
        verify(mapper).insert(captor.capture());
        ActionExecutionEntity e = captor.getValue();
        assertThat(e.getActionId()).isEqualTo("BLOCK_TRANSACTION");
        assertThat(e.getStatus()).isEqualTo("SUCCESS");
        assertThat(e.getEventId()).isEqualTo("evt-1");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**
`$MVN -pl rule-eval-svc -am test -Dtest='ActionExecutionPersisterTest' -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | grep -E 'ERROR|BUILD'`

- [ ] **Step 3: 实现 `ActionExecuted` 事件**
```java
package com.sstlfsj.rule.eval.internal.async;

import com.sstlfsj.rule.eval.internal.event.DomainEvent;
import com.sstlfsj.rule.eval.internal.event.Durability;
import com.sstlfsj.rule.kernel.api.model.ActionResult;

/** action 执行完成事件(at-least-once):供异步落 action_execution。 */
public record ActionExecuted(long sessionId, long tenantId, String eventId, String actionId,
                             String actionType, String decisionCode, ActionResult result)
        implements DomainEvent {
    @Override
    public Durability durability() { return Durability.AT_LEAST_ONCE; }
}
```

- [ ] **Step 4: 实现 `ActionExecutionPersister`**(把 dispatch 的 insertExecution 逻辑搬来)
```java
package com.sstlfsj.rule.eval.internal.action;

import com.sstlfsj.rule.eval.internal.async.ActionExecuted;
import com.sstlfsj.rule.eval.internal.domain.ActionExecutionEntity;
import com.sstlfsj.rule.eval.internal.repository.ActionExecutionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/** 消费 ActionExecuted,落 action_execution(uk_idempotency 行级 backstop)。 */
@Component
public class ActionExecutionPersister {

    private static final Logger log = LoggerFactory.getLogger(ActionExecutionPersister.class);

    private final ActionExecutionMapper executionMapper;

    public ActionExecutionPersister(ActionExecutionMapper executionMapper) {
        this.executionMapper = executionMapper;
    }

    @EventListener
    public void accept(ActionExecuted e) {
        ActionExecutionEntity entity = new ActionExecutionEntity();
        entity.setEvaluationSessionId(e.sessionId());
        entity.setTenantId(e.tenantId());
        entity.setEventId(e.eventId());
        entity.setActionId(e.actionId());
        entity.setActionType(e.actionType());
        entity.setDecisionCode(e.decisionCode());
        entity.setStatus(e.result().status().name());
        entity.setErrorCode(e.result().errorCode());
        entity.setRetryable(e.result().retryable());
        entity.setRetryCount(0);
        entity.setCompensated(false);
        entity.setExecutedAt(LocalDateTime.now());
        entity.setCreatedAt(LocalDateTime.now());
        try {
            executionMapper.insert(entity);
        } catch (DuplicateKeyException ex) {
            log.debug("action_execution 幂等行已存在(uk backstop), actionId={}, eventId={}",
                    e.actionId(), e.eventId());
        } catch (Exception ex) {
            log.warn("action_execution 写库失败, actionId={}, actionType={}: {}",
                    e.actionId(), e.actionType(), ex.getMessage());
        }
    }
}
```

- [ ] **Step 5: 改 `ActionDispatchService`** —— 注入 `DomainEventPublisher`,`dispatch` 用 publish 替内联 insert;删 `insertExecution` 方法、`executionMapper` 字段、`ActionExecutionMapper`/`ActionExecutionEntity`/`LocalDateTime` import。
  - 字段+构造器:把 `executionMapper` 换成 `DomainEventPublisher eventPublisher`(其余 handlers/bindingMapper/idempotencyGuard 不变)。
  - dispatch 命中分支的 `insertExecution(...)` 替为:
  ```java
                ActionResult result = executeHandler(actionId, binding, decision);
                if (result.status() == ActionResult.ActionStatus.FAILED) {
                    idempotencyGuard.release(key);
                }
                eventPublisher.publish(new com.sstlfsj.rule.eval.internal.async.ActionExecuted(
                        sessionId, tenantId, eventId, actionId, binding.actionType(),
                        decision.code(), result));
  ```
  - 删除整个 `insertExecution(...)` 私有方法。

- [ ] **Step 6: 改 `EvalAutoConfiguration.actionDispatchService` @Bean** —— 参数 `ActionExecutionMapper executionMapper` 换为 `DomainEventPublisher eventPublisher`,`new ActionDispatchService(handlerMap, bindingMapper, eventPublisher, idempotencyGuard)`(顺序与新构造器一致)。

- [ ] **Step 7: 改 `ActionDispatchServiceTest`** —— setUp 把 `executionMapper` mock 换为 `DomainEventPublisher eventPublisher = mock(...)`,构造器对应改;断言从「verify executionMapper.insert(...)」改为「verify eventPublisher.publish(ActionExecuted with ...)」。三处既有断言(actionId 确定化、claimRejected 不 publish、handlerFailed release)同步改为基于 publish。具体:
  - `dispatch_withBinding_*` / `dispatch_actionId_*`:`verify(eventPublisher).publish(argThat((Object o) -> o instanceof ActionExecuted ae && "BLOCK_TRANSACTION".equals(ae.actionId()) && "SUCCESS".equals(ae.result().status().name())))`。
  - `dispatch_emptyBindings_*`:`verifyNoInteractions(eventPublisher)`(及 stubHandler)。
  - `dispatch_handlerNotRegistered_*`:验 publish 的 ActionExecuted result.status()==SKIPPED。
  - `dispatch_claimRejected_*`:`verify(eventPublisher, never()).publish(any())`。
  - `dispatch_handlerFailed_*`:`verify(guard).release(anyString())` + publish 的 result FAILED。
  - 删 `dispatch_insertException_*`(insert 已不在 dispatch;落库异常由 persister 隔离,移到 ActionExecutionPersisterTest 若需)。

- [ ] **Step 8: 跑测试**
`$MVN -pl rule-eval-svc -am test -Dtest='ActionExecutionPersisterTest,ActionDispatchServiceTest' -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | grep -E 'Tests run|BUILD'` → 全绿

- [ ] **Step 9: 提交**
```bash
git add rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/async/ActionExecuted.java \
        rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/action/ActionExecutionPersister.java \
        rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/action/ActionDispatchService.java \
        rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/EvalAutoConfiguration.java \
        rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/action/
git commit -m "refactor(eval): action_execution 落库事件化(ActionExecuted + ActionExecutionPersister),dispatch 只发布"
```

---

## Task 4: `DryRunRecorded` 事件 + `DryRunPersister`(消费侧先就位)

**Files:** Create `internal/async/DryRunRecorded.java`, `internal/async/DryRunPersister.java` (+Test); Modify `internal/domain/DryRunSession.java`

- [ ] **Step 1: 写失败测试** `DryRunPersisterTest.java`
```java
package com.sstlfsj.rule.eval.internal.async;

import com.sstlfsj.rule.eval.internal.repository.DryRunSessionMapper;
import com.sstlfsj.rule.eval.internal.domain.DryRunSession;
import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.EventSource;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import com.sstlfsj.rule.kernel.api.spi.trace.DryRunTraceWriter;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class DryRunPersisterTest {

    @Test
    void accept_insertsTerminalDryRunSessionAndTrace() {
        DryRunSessionMapper mapper = mock(DryRunSessionMapper.class);
        DryRunTraceWriter traceWriter = mock(DryRunTraceWriter.class);
        DryRunPersister persister = new DryRunPersister(mapper, traceWriter, JsonMapper.builder().build());
        RuleEvent ev = RuleEvent.builder().tenantId("1").sceneCode("s").eventType("t")
                .subjectId("u1").eventId("e1").source(EventSource.HTTP).occurredAt(Instant.now()).build();

        persister.accept(new DryRunRecorded(77L, ev, 99L, EvalResult.miss(), null));

        ArgumentCaptor<DryRunSession> captor = ArgumentCaptor.forClass(DryRunSession.class);
        verify(mapper).insert(captor.capture());
        DryRunSession s = captor.getValue();
        assertThat(s.getId()).isEqualTo(77L);
        assertThat(s.getStatus()).isEqualTo("MISS");
        assertThat(s.getRuleVersionId()).isEqualTo(99L);
        verify(traceWriter).write(eq("1"), eq("77"), any());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**
`$MVN -pl rule-eval-svc -am test -Dtest='DryRunPersisterTest' -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | grep -E 'ERROR|BUILD'`

- [ ] **Step 3: `DryRunSession` 改 @TableId(INPUT)**(支持 snowflake 客户端赋 id 单次终态 INSERT)
把 `@TableId(type = IdType.AUTO)` 改为 `@TableId(type = IdType.INPUT)`(import 已有 IdType)。

- [ ] **Step 4: 实现 `DryRunRecorded`**
```java
package com.sstlfsj.rule.eval.internal.async;

import com.sstlfsj.rule.eval.internal.event.DomainEvent;
import com.sstlfsj.rule.eval.internal.event.Durability;
import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;

/** dry-run 完成事件(best-effort):供异步落 dry_run_session + trace(单次终态)。 */
public record DryRunRecorded(long sessionId, RuleEvent event, Long ruleVersionId,
                             EvalResult result, EvalContext context) implements DomainEvent {
    @Override
    public Durability durability() { return Durability.BEST_EFFORT; }
}
```

- [ ] **Step 5: 实现 `DryRunPersister`**(吸收 EvalSessionWriter 的 dry-run + serializeSnapshot 逻辑)
```java
package com.sstlfsj.rule.eval.internal.async;

import com.sstlfsj.rule.eval.internal.domain.DryRunSession;
import com.sstlfsj.rule.eval.internal.repository.DryRunSessionMapper;
import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import com.sstlfsj.rule.kernel.api.spi.trace.DryRunTraceWriter;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/** 消费 DryRunRecorded,单次终态 INSERT dry_run_session + dry-run trace。 */
@Component
public class DryRunPersister {

    private static final Logger log = LoggerFactory.getLogger(DryRunPersister.class);

    private final DryRunSessionMapper dryRunMapper;
    private final DryRunTraceWriter traceWriter;
    private final ObjectMapper objectMapper;

    public DryRunPersister(DryRunSessionMapper dryRunMapper, DryRunTraceWriter traceWriter,
                           ObjectMapper objectMapper) {
        this.dryRunMapper = dryRunMapper;
        this.traceWriter = traceWriter;
        this.objectMapper = objectMapper;
    }

    @EventListener
    public void accept(DryRunRecorded e) {
        RuleEvent ev = e.event();
        EvalResult r = e.result();
        DryRunSession s = new DryRunSession();
        s.setId(e.sessionId());
        s.setTenantId(Long.valueOf(ev.tenantId()));
        s.setEventId(ev.eventId());
        s.setSceneCode(ev.sceneCode());
        s.setEventType(ev.eventType());
        s.setSubjectId(ev.subjectId());
        s.setRuleVersionId(e.ruleVersionId());
        s.setStatus(r.errorCode() != null ? "ERROR" : (r.ruleHit() ? "HIT" : "MISS"));
        s.setFinalDecision(r.finalDecision() != null ? r.finalDecision().code() : null);
        s.setErrorCode(r.errorCode());
        if (ev.occurredAt() != null) {
            s.setOccurredAt(LocalDateTime.ofInstant(ev.occurredAt(), ZoneId.systemDefault()));
        }
        LocalDateTime now = LocalDateTime.now();
        s.setStartedAt(now);
        s.setFinishedAt(now);
        s.setContextSnapshot(serializeSnapshot(e.context()));
        try {
            dryRunMapper.insert(s);
        } catch (Exception ex) {
            log.warn("dry_run_session 写库失败, sessionId={}: {}", e.sessionId(), ex.getMessage());
            return;
        }
        traceWriter.write(ev.tenantId(), String.valueOf(e.sessionId()), r.nodeTrace());
    }

    /** EvalContext → {"metrics":{code:rawValue}, "evalNow":"<ISO>"} JSON;null/失败返回 null。 */
    private String serializeSnapshot(EvalContext ctx) {
        if (ctx == null) return null;
        Map<String, Object> metrics = ctx.metrics().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey,
                        en -> en.getValue().value() != null ? en.getValue().value() : "null"));
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("metrics", metrics);
        snapshot.put("evalNow", ctx.now() != null ? ctx.now().toString() : null);
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JacksonException ex) {
            log.warn("dry-run context_snapshot 序列化失败,写 null", ex);
            return null;
        }
    }
}
```

- [ ] **Step 6: 跑测试确认通过**
`$MVN -pl rule-eval-svc -am test -Dtest='DryRunPersisterTest' -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | grep -E 'Tests run|BUILD'` → 绿

- [ ] **Step 7: 提交**
```bash
git add rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/async/DryRunRecorded.java \
        rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/async/DryRunPersister.java \
        rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/domain/DryRunSession.java \
        rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/async/DryRunPersisterTest.java
git commit -m "feat(eval): DryRunRecorded + DryRunPersister(单次终态,吸收 dry-run 落库逻辑)"
```

---

## Task 5: `EvalServiceImpl` 全量改发布 + 删 `EvaluationEventPublisher` / `EvalSessionWriter`(原子)

**Files:** Modify `internal/service/EvalServiceImpl.java` (+`EvalServiceImplTest`, `DoEvaluateEmitsEventsTest`); Delete `internal/async/EvaluationEventPublisher.java` (+Test), `internal/session/EvalSessionWriter.java` (+Test)

- [ ] **Step 1: 改 `EvalServiceImpl`**(注入 `DomainEventPublisher` + `ActionDeliveryChannel`,删 `EvalSessionWriter`/`DryRunTraceWriter`/`EvaluationEventPublisher`)
  - 字段/构造器改为:
  ```java
      private final EvalEngine evalEngine;
      private final SceneSnapshotLoader snapshotLoader;
      private final com.sstlfsj.rule.eval.internal.event.DomainEventPublisher eventPublisher;
      private final com.sstlfsj.rule.eval.internal.async.ActionDeliveryChannel actionDelivery;
      private final EvalActionDispatcher dispatcher;

      EvalServiceImpl(EvalEngine evalEngine, SceneSnapshotLoader snapshotLoader,
                      com.sstlfsj.rule.eval.internal.event.DomainEventPublisher eventPublisher,
                      com.sstlfsj.rule.eval.internal.async.ActionDeliveryChannel actionDelivery) {
          this.evalEngine = evalEngine;
          this.snapshotLoader = snapshotLoader;
          this.eventPublisher = eventPublisher;
          this.actionDelivery = actionDelivery;
          this.dispatcher = new EvalActionDispatcher(10000, e -> doEvaluate(e, "PUSH", false, null));
      }
  ```
  - dry-run 分支(替掉 sessionWriter/dryRunTraceWriter):
  ```java
          if (isDryRun && specificVersionId != null) {
              RuleVersionSnapshot snap = snapshotLoader.loadById(specificVersionId);
              if (snap == null) return EvalResult.miss();
              EvalOutcome outcome = evalEngine.evaluateWithContext(
                      event, List.of(snap), SceneExecutionStrategy.HIGHEST_PRIORITY, evalNow);
              long dryRunId = com.baomidou.mybatisplus.core.toolkit.IdWorker.getId();
              eventPublisher.publish(new com.sstlfsj.rule.eval.internal.async.DryRunRecorded(
                      dryRunId, event, specificVersionId, outcome.result(), outcome.context()));
              return outcome.result();
          }
  ```
  - 主路径(替掉 eventPublisher.publishAudit/publishActions):
  ```java
          eventPublisher.publish(new com.sstlfsj.rule.eval.internal.async.AuditRecorded(
                  sessionId, event, mode, candidates.size(), result, outcome.context(), outcome.blockedBy()));
          Long tid = parseTenantId(event.tenantId());
          if (tid != null && result.ruleHit() && !result.hitDecisions().isEmpty()) {
              actionDelivery.deliver(new com.sstlfsj.rule.eval.internal.async.DispatchActionsCommand(
                      sessionId, tid, event.eventId(), event.sceneCode(), result.hitDecisions()));
          }
          return result;
  ```
  - `parseTenantId` 私有方法保留。删去 `EvalSessionWriter`/`DryRunTraceWriter`/`EvaluationEventPublisher` 的 import。

- [ ] **Step 2: 删除 `EvaluationEventPublisher.java` + `EvaluationEventPublisherTest.java`**
```bash
git rm rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/async/EvaluationEventPublisher.java \
       rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/async/EvaluationEventPublisherTest.java
```

- [ ] **Step 3: 删除 `EvalSessionWriter.java` + `EvalSessionWriterTest.java`**(逻辑已入 DryRunPersister)
```bash
git rm rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/session/EvalSessionWriter.java \
       rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/session/EvalSessionWriterTest.java
```

- [ ] **Step 4: 改 `EvalServiceImplTest` + `DoEvaluateEmitsEventsTest`** —— 把对 `EvaluationEventPublisher`/`EvalSessionWriter` 的 mock/verify 改为 `DomainEventPublisher` + `ActionDeliveryChannel`:
  - 构造 `EvalServiceImpl` 改为 4 参 `(engine, snapshotLoader, mock(DomainEventPublisher), mock(ActionDeliveryChannel))`。
  - 主路径命中:`verify(eventPublisher).publish(argThat(o -> o instanceof AuditRecorded))` + `verify(actionDelivery).deliver(argThat(o -> o instanceof DispatchActionsCommand ar && ar.eventId().equals("e1")))`。
  - 评估 MISS(有候选):`verify(eventPublisher).publish(any(AuditRecorded.class))` + `verify(actionDelivery, never()).deliver(any())`。
  - BLOCKED:publish 的 AuditRecorded.blockedBy() == "ROLLOUT"。
  - 无候选:`verifyNoInteractions(eventPublisher, actionDelivery)`。
  - dry-run 用例:`verify(eventPublisher).publish(any(DryRunRecorded.class))`;删去对 sessionWriter.insertDryRunPending/never insertPending 的断言。

- [ ] **Step 5: 跑相关测试**
`$MVN -pl rule-eval-svc -am test -Dtest='EvalServiceImplTest,DoEvaluateEmitsEventsTest,EvalServiceImplScorecardTest' -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | grep -E 'Tests run|BUILD'` → 全绿

- [ ] **Step 6: 提交**
```bash
git add -A rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/service/EvalServiceImpl.java \
        rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/service/
git commit -m "refactor(eval): EvalServiceImpl 只经 DomainEventPublisher 发布(audit/dry-run)+ ActionDeliveryChannel(action),删 EvaluationEventPublisher/EvalSessionWriter"
```

---

## Task 6: 装配收口 + 全量回归 + native

**Files:** Modify `EvalAutoConfiguration.java`(若有遗留装配);验证

- [ ] **Step 1: 检查装配**——确认 `EvalServiceImpl`(@Service 自动注入 DomainEventPublisher + ActionDeliveryChannel)、`InProcessDomainEventPublisher`/`ActionExecutionPersister`/`DryRunPersister`(@Component 被 `@ComponentScan("com.sstlfsj.rule.eval.internal")` 扫到)均可装配。`actionDispatchService` @Bean 已在 Task 3 改注入 DomainEventPublisher。无遗漏则本步无改动。

- [ ] **Step 2: 全量回归**
`$MVN -pl rule-kernel,rule-eval-svc -am test 2>&1 | grep -E 'Tests run:.*Failures|BUILD' | grep -vE 'Time elapsed' | tail -6` → 全部 Failures:0,BUILD SUCCESS

- [ ] **Step 3: native 验证**(本 session 已知 record 经 Jackson 序列化在 native 需 hints)——`AuditRecorded`/`ActionExecuted`/`DryRunRecorded` 进程内不序列化(只 Spring 内存传递),native boot 不应受影响;但若 `EvalAutoConfiguration` 的 AOT 对新事件类有反射需求,经一次 `install + -Pnative -f rule-app/pom.xml native:compile + boot` 验证。期望:boot OK、PULL/dry-run 评估正常、`evaluation_session`/`action_execution`/`dry_run_session` 三表均落库。若 native 报某事件 record 反射缺失,按 `HitDecisionView` 模式加 `@RegisterReflectionForBinding`。

- [ ] **Step 4: 无新增提交**(纯验证;native 若需 hints 则单独补 commit)

---

## Self-Review

**Spec 覆盖:** §2 契约→Task1;§3 AuditRecorded→Task2、ActionExecuted→Task3、DryRunRecorded→Task4;§4 三 persister→Task1(publisher)/2/3/4;§5 生产方只发布+删 EvaluationEventPublisher/EvalSessionWriter→Task3(dispatch)+Task5(EvalServiceImpl);§6 进程内实现→Task1;§7 durability/uk backstop→Task3(ActionExecutionPersister)/Task2;§8 测试→各 Task + Task6 全量;§9 非目标(MQ/durable/表结构)未实现,符合。

**类型一致性:** `DomainEvent.durability():Durability`、`DomainEventPublisher.publish(DomainEvent)` 全程统一;`ActionExecuted(sessionId,tenantId,eventId,actionId,actionType,decisionCode,result)` Task3 定义并在 dispatch/persister/test 一致;`DryRunRecorded(sessionId,event,ruleVersionId,result,context)` Task4 定义并在 persister/EvalServiceImpl 一致;`ActionDispatchService` 新构造器 `(handlers,bindingMapper,DomainEventPublisher,idempotencyGuard)` Task3 定义、EvalAutoConfiguration 调用一致;`EvalServiceImpl` 新构造器 `(engine,snapshotLoader,DomainEventPublisher,ActionDeliveryChannel)` Task5。

**占位符:** 无 TBD;新文件含完整代码;修改步骤给出替换后代码或精确指令。Task6 Step3 native 为条件式验证(已知模式),非占位。

**原子性:** Task3(dispatch↔persister)、Task5(EvalServiceImpl 构造器 + 两处删除)各为不可拆的原子提交,避免中间态编译/上下文失败(本 session 已有教训)。
