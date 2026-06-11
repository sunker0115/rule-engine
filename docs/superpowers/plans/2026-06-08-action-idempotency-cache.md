# Action 幂等缓存(claim-before-execute) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让重复 eventId 不再重复执行 action handler——确定化 actionId + 进程内 Caffeine 缓存做 claim-before-execute 同步去重,失败释放允许重发重试,DB `uk_idempotency` 留行级 backstop。

**Architecture:** 新增 `ActionIdempotencyGuard` 接口(Redis/durable 升级缝)+ 进程内 `CaffeineActionIdempotencyGuard`(TTL 配置化);`ActionDispatchService` 把随机 `actionId` 改为确定的 `binding.actionType()`(由 schema `uk_scene_action` 保证唯一),并在执行 handler 前 `claim`、`FAILED` 时 `release`、claim 失败则 skip。缓存去重在异步派发消费者里,不碰请求热路径。

**Tech Stack:** Java 25 / Spring Boot 4 / Caffeine / MyBatis-Plus / JUnit 5 + Mockito / Maven。

**Spec:** `docs/superpowers/specs/2026-06-08-action-idempotency-cache-design.md`

---

## 环境前置(每个 task 跑测试前)

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-25.jdk/Contents/Home
export PATH=$JAVA_HOME/bin:$PATH
MVN=/Users/sunke/.m2/wrapper/dists/apache-maven-3.9.9-bin/4nf9hui3q3djbarqar9g711ggc/apache-maven-3.9.9/bin/mvn
cd /Users/sunke/dev/ai-project/rule-engine
```

模块测试命令模板：`$MVN -pl rule-eval-svc -am test -Dtest='ClassName' -Dsurefire.failIfNoSpecifiedTests=false`

## 文件清单

| 文件 | 职责 | 动作 |
|---|---|---|
| `rule-eval-svc/.../internal/action/ActionIdempotencyProperties.java` | TTL/maxSize 配置 | 建 |
| `rule-eval-svc/.../internal/action/ActionIdempotencyGuard.java` | 占坑接口(Redis 缝) | 建 |
| `rule-eval-svc/.../internal/action/CaffeineActionIdempotencyGuard.java` | 进程内 Caffeine 实现 | 建 |
| `rule-eval-svc/.../internal/action/CaffeineActionIdempotencyGuardTest.java` | guard 单测 | 建 |
| `rule-eval-svc/.../internal/action/ActionDispatchService.java` | 确定 actionId + claim/release | 改 |
| `rule-eval-svc/.../internal/action/ActionDispatchServiceTest.java` | 派发单测 | 改 |
| `rule-eval-svc/.../EvalAutoConfiguration.java` | 启用 properties + 注入 guard | 改 |

> **Native 说明(非本计划任务)**：本 guard 的 Caffeine 配置(`maximumSize` + `expireAfterWrite`)与 `CaffeineMetricCache`(变长 expireAfter)不同,native 下可能生成不同的缓存类、需单独反射 hints(同 `CaffeineNativeHints`/SSMSA 模式)。JVM 路径(默认 + 本计划全部测试)无需。native 验证经一次 `native:compile` 确定类名后补注册,留作 native 回归的后续项,不在本计划内。

---

## Task 1: `ActionIdempotencyProperties` 配置

**Files:**
- Create: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/action/ActionIdempotencyProperties.java`

- [ ] **Step 1: 实现(无独立测试,值在 Task 4 经 guard 装配验证)**

```java
package com.sstlfsj.rule.eval.internal.action;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** action 幂等去重缓存配置。 */
@Getter
@Setter
@ConfigurationProperties(prefix = "engine.rule.action.idempotency")
public class ActionIdempotencyProperties {
    /** 去重窗口 TTL 秒（默认 600）；超过窗口的重复 eventId 不再去重。 */
    private long ttlSeconds = 600;
    /** 缓存最大键数（默认 100000）。 */
    private long maxSize = 100_000;
}
```

- [ ] **Step 2: 编译确认**

Run: `$MVN -pl rule-eval-svc -am compile 2>&1 | grep -E 'BUILD|ERROR'`
Expected: `BUILD SUCCESS`。

- [ ] **Step 3: 提交**

```bash
git add rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/action/ActionIdempotencyProperties.java
git commit -m "feat(eval): ActionIdempotencyProperties(action 幂等 TTL/maxSize 配置)"
```

---

## Task 2: `ActionIdempotencyGuard` 接口 + `CaffeineActionIdempotencyGuard` 实现

**Files:**
- Create: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/action/ActionIdempotencyGuard.java`
- Create: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/action/CaffeineActionIdempotencyGuard.java`
- Test: `rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/action/CaffeineActionIdempotencyGuardTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.sstlfsj.rule.eval.internal.action;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CaffeineActionIdempotencyGuardTest {

    private CaffeineActionIdempotencyGuard guard() {
        return new CaffeineActionIdempotencyGuard(600, 100_000);
    }

    @Test
    void claim_firstTrue_secondFalse() {
        CaffeineActionIdempotencyGuard g = guard();
        assertThat(g.claim("k1")).isTrue();
        assertThat(g.claim("k1")).isFalse();   // TTL 内已占坑
    }

    @Test
    void release_allowsReclaim() {
        CaffeineActionIdempotencyGuard g = guard();
        assertThat(g.claim("k1")).isTrue();
        g.release("k1");
        assertThat(g.claim("k1")).isTrue();     // 释放后可重新占坑
    }

    @Test
    void differentKeys_independent() {
        CaffeineActionIdempotencyGuard g = guard();
        assertThat(g.claim("k1")).isTrue();
        assertThat(g.claim("k2")).isTrue();
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `$MVN -pl rule-eval-svc -am test -Dtest='CaffeineActionIdempotencyGuardTest' -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | grep -E 'ERROR|BUILD'`
Expected: 编译失败——`ActionIdempotencyGuard` / `CaffeineActionIdempotencyGuard` 不存在。

- [ ] **Step 3: 实现接口**

`ActionIdempotencyGuard.java`:
```java
package com.sstlfsj.rule.eval.internal.action;

/**
 * action 幂等占坑：claim-before-execute 同步去重。
 * 进程内实现 best-effort（重启/多实例不共享）；升级 Redis/durable 时换实现，派发方不动。
 */
public interface ActionIdempotencyGuard {

    /**
     * 原子占坑。
     *
     * @param key 幂等键
     * @return true=占到（可执行）；false=已被占（TTL 内已派发，跳过）
     */
    boolean claim(String key);

    /**
     * 释放占坑（handler 失败时调，允许后续重发重试）。
     *
     * @param key 幂等键
     */
    void release(String key);
}
```

- [ ] **Step 4: 实现 Caffeine 版**

`CaffeineActionIdempotencyGuard.java`:
```java
package com.sstlfsj.rule.eval.internal.action;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/** 进程内 Caffeine action 幂等占坑（best-effort：重启丢 / 多实例不共享，TTL 内去重）。 */
@Component
public class CaffeineActionIdempotencyGuard implements ActionIdempotencyGuard {

    private final Cache<String, Boolean> cache;

    public CaffeineActionIdempotencyGuard(long ttlSeconds, long maxSize) {
        this.cache = Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfterWrite(ttlSeconds, TimeUnit.SECONDS)
                .build();
    }

    @Autowired
    public CaffeineActionIdempotencyGuard(ActionIdempotencyProperties props) {
        this(props.getTtlSeconds(), props.getMaxSize());
    }

    @Override
    public boolean claim(String key) {
        // putIfAbsent 原子：返回 null 表示本次首占（可执行）
        return cache.asMap().putIfAbsent(key, Boolean.TRUE) == null;
    }

    @Override
    public void release(String key) {
        cache.invalidate(key);
    }
}
```

- [ ] **Step 5: 跑测试确认通过**

Run: `$MVN -pl rule-eval-svc -am test -Dtest='CaffeineActionIdempotencyGuardTest' -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | grep -E 'Tests run|BUILD'`
Expected: `Tests run: 3, Failures: 0, Errors: 0` + `BUILD SUCCESS`。

- [ ] **Step 6: 提交**

```bash
git add rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/action/ActionIdempotencyGuard.java \
        rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/action/CaffeineActionIdempotencyGuard.java \
        rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/action/CaffeineActionIdempotencyGuardTest.java
git commit -m "feat(eval): ActionIdempotencyGuard 缝 + Caffeine 进程内实现(claim/release)"
```

---

## Task 3: `ActionDispatchService` 确定 actionId + claim-before-execute

**Files:**
- Modify: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/action/ActionDispatchService.java`
- Test: `rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/action/ActionDispatchServiceTest.java`

- [ ] **Step 1: 改既有测试的构造 + 写新失败测试**

(1a) `ActionDispatchServiceTest.setUp` 增加 guard mock（默认 claim 放行），构造器加 guard 参数。把 `setUp()` 整体替换为：

```java
    private SceneActionBindingReadMapper bindingMapper;
    private ActionExecutionMapper executionMapper;
    private ActionHandler stubHandler;
    private ActionIdempotencyGuard guard;
    private ActionDispatchService service;

    @BeforeEach
    void setUp() {
        bindingMapper = mock(SceneActionBindingReadMapper.class);
        executionMapper = mock(ActionExecutionMapper.class);
        stubHandler = mock(ActionHandler.class);
        when(stubHandler.execute(any())).thenReturn(ActionResult.success("aid", "BLOCK_TRANSACTION"));
        guard = mock(ActionIdempotencyGuard.class);
        when(guard.claim(any())).thenReturn(true);   // 默认放行；去重用例单独 stub false

        service = new ActionDispatchService(
                Map.of("BLOCK_TRANSACTION", stubHandler),
                bindingMapper,
                executionMapper,
                guard);
    }
```

(1b) 追加三个新测试到 `ActionDispatchServiceTest`：

```java
    @Test
    void dispatch_actionId_isDeterministicActionType() {
        when(bindingMapper.findBySceneCode(1L, "fraud_check"))
                .thenReturn(List.of(new SceneActionBindingRow("BLOCK_TRANSACTION", null)));

        service.dispatch(42L, 1L, "evt-001", "fraud_check",
                List.of(new Decision("REJECT", "", 10, 1L)));

        // actionId 确定化 = actionType（替随机 UUID）
        verify(executionMapper).insert(argThat((ActionExecutionEntity e) ->
                "BLOCK_TRANSACTION".equals(e.getActionId())));
    }

    @Test
    void dispatch_claimRejected_skipsHandlerAndInsert() {
        when(bindingMapper.findBySceneCode(1L, "fraud_check"))
                .thenReturn(List.of(new SceneActionBindingRow("BLOCK_TRANSACTION", null)));
        when(guard.claim(any())).thenReturn(false);   // 已被占坑（重复 eventId）

        service.dispatch(42L, 1L, "evt-001", "fraud_check",
                List.of(new Decision("REJECT", "", 10, 1L)));

        verifyNoInteractions(stubHandler);
        verify(executionMapper, never()).insert(any(ActionExecutionEntity.class));
    }

    @Test
    void dispatch_handlerFailed_releasesClaimForRetry() {
        when(bindingMapper.findBySceneCode(1L, "fraud_check"))
                .thenReturn(List.of(new SceneActionBindingRow("BLOCK_TRANSACTION", null)));
        when(stubHandler.execute(any()))
                .thenReturn(ActionResult.failed("BLOCK_TRANSACTION", "BLOCK_TRANSACTION", "ERR", true));

        service.dispatch(42L, 1L, "evt-001", "fraud_check",
                List.of(new Decision("REJECT", "", 10, 1L)));

        // FAILED → 释放占坑，让后续重发能重试；仍写一行 FAILED 审计
        verify(guard).release(anyString());
        verify(executionMapper).insert(argThat((ActionExecutionEntity e) ->
                "FAILED".equals(e.getStatus())));
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `$MVN -pl rule-eval-svc -am test -Dtest='ActionDispatchServiceTest' -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | grep -E 'ERROR|Tests run|BUILD'`
Expected: 编译失败——4 参 `ActionDispatchService` 构造器与 `ActionIdempotencyGuard` 尚不存在于该服务。

- [ ] **Step 3: 改 `ActionDispatchService`**

(3a) 加字段 + 构造器参数：把字段区与构造器替换为：

```java
    private final Map<String, ActionHandler> handlers;
    private final SceneActionBindingReadMapper bindingMapper;
    private final ActionExecutionMapper executionMapper;
    private final ActionIdempotencyGuard idempotencyGuard;

    public ActionDispatchService(Map<String, ActionHandler> handlers,
                                 SceneActionBindingReadMapper bindingMapper,
                                 ActionExecutionMapper executionMapper,
                                 ActionIdempotencyGuard idempotencyGuard) {
        this.handlers = handlers;
        this.bindingMapper = bindingMapper;
        this.executionMapper = executionMapper;
        this.idempotencyGuard = idempotencyGuard;
    }
```

(3b) 把 `dispatch` 的双层循环体替换为（确定 actionId + claim/release）：

```java
        for (Decision decision : hitDecisions) {
            for (SceneActionBindingRow binding : bindings) {
                String actionId = binding.actionType();   // 确定化：schema uk_scene_action 保证 scene 内 actionType 唯一
                String key = tenantId + ":" + eventId + ":" + decision.code() + ":" + actionId;
                if (!idempotencyGuard.claim(key)) {
                    log.debug("action 幂等跳过 key={}", key);   // TTL 内已派发，跳过执行与落库
                    continue;
                }
                ActionResult result = executeHandler(actionId, binding, decision);
                if (result.status() == ActionResult.ActionStatus.FAILED) {
                    idempotencyGuard.release(key);   // 失败释放，让后续重发能重试
                }
                insertExecution(sessionId, tenantId, eventId, actionId,
                        binding.actionType(), decision.code(), result);
            }
        }
```

(3c) 删除 `import java.util.UUID;`（不再用随机 actionId）。

(3d) `insertExecution` 的 `catch` 区分 DuplicateKey（uk backstop，降 debug）与其他（warn）。把 catch 替换为：

```java
        try {
            executionMapper.insert(entity);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 行级 backstop：缓存漏掉的重复（重启/多实例）在此撞 uk_idempotency，预期内
            log.debug("action_execution 幂等行已存在(uk backstop)，actionId={}, eventId={}", actionId, eventId);
        } catch (Exception e) {
            log.warn("action_execution 写库失败，actionId={}, actionType={}: {}",
                    actionId, actionType, e.getMessage());
        }
```

- [ ] **Step 4: 跑测试确认通过**

Run: `$MVN -pl rule-eval-svc -am test -Dtest='ActionDispatchServiceTest' -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | grep -E 'Tests run|BUILD'`
Expected: `Tests run: 7, Failures: 0, Errors: 0` + `BUILD SUCCESS`（原 4 + 新 3）。

- [ ] **Step 5: 提交**

```bash
git add rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/action/ActionDispatchService.java \
        rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/action/ActionDispatchServiceTest.java
git commit -m "fix(eval): action 确定 actionId(=actionType) + claim-before-execute 幂等去重 + 失败释放 + uk backstop 降 debug"
```

---

## Task 4: `EvalAutoConfiguration` 启用 properties + 注入 guard

**Files:**
- Modify: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/EvalAutoConfiguration.java`

- [ ] **Step 1: 启用 ActionIdempotencyProperties**

把类头的 `@EnableConfigurationProperties(...)` 替换为同时启用两个 properties：

```java
@org.springframework.boot.context.properties.EnableConfigurationProperties({
        com.sstlfsj.rule.eval.internal.metric.sql.FetchResourceProperties.class,
        com.sstlfsj.rule.eval.internal.action.ActionIdempotencyProperties.class})
```

- [ ] **Step 2: 给 actionDispatchService @Bean 注入 guard**

把 `actionDispatchService(...)` 方法签名与 `return` 替换为（增加 `ActionIdempotencyGuard guard` 参数）：

```java
    @Bean
    public ActionDispatchService actionDispatchService(
            @Autowired(required = false) List<ActionHandler> actionHandlers,
            SceneActionBindingReadMapper bindingMapper,
            ActionExecutionMapper executionMapper,
            com.sstlfsj.rule.eval.internal.action.ActionIdempotencyGuard idempotencyGuard) {
        Map<String, ActionHandler> handlerMap = new HashMap<>();
        if (actionHandlers != null) {
            for (ActionHandler handler : actionHandlers) {
                ActionType ann = handler.getClass().getAnnotation(ActionType.class);
                if (ann != null) {
                    handlerMap.put(ann.value(), handler);
                }
            }
        }
        return new ActionDispatchService(handlerMap, bindingMapper, executionMapper, idempotencyGuard);
    }
```

> `CaffeineActionIdempotencyGuard` 是 `@Component`（`@ComponentScan("com.sstlfsj.rule.eval.internal")` 已覆盖），作为 `ActionIdempotencyGuard` 注入。

- [ ] **Step 3: 编译 + 装配冒烟**

Run: `$MVN -pl rule-eval-svc -am test -Dtest='EvalAutoConfigurationTest' -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | grep -E 'Tests run|BUILD'`
Expected: `BUILD SUCCESS`（若 `EvalAutoConfigurationTest` 不覆盖该 bean，至少编译通过、不破坏既有装配测试）。

- [ ] **Step 4: 提交**

```bash
git add rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/EvalAutoConfiguration.java
git commit -m "feat(eval): 装配 ActionIdempotencyProperties + 注入 guard 到 ActionDispatchService"
```

---

## Task 5: 全量回归

**Files:** 无（验证）

- [ ] **Step 1: rule-eval-svc 全量**

Run: `$MVN -pl rule-eval-svc -am test 2>&1 | grep -E 'Tests run:.*Failures|BUILD' | grep -vE 'Time elapsed' | tail -6`
Expected: 全部 `Failures: 0, Errors: 0` + `BUILD SUCCESS`。

- [ ] **Step 2: 无新增提交**（纯验证；若失败回对应 Task 修复）

---

## Self-Review（计划自检）

**Spec 覆盖：**
- §3 ActionIdempotencyGuard 接口 → Task 2 ✓
- §3 CaffeineActionIdempotencyGuard + claim/release → Task 2 ✓
- §3 ActionIdempotencyProperties(ttlSeconds/maxSize) → Task 1 ✓
- §2/§4 actionId=binding.actionType() 确定化 → Task 3 ✓
- §4 claim-before-execute / continue on claim-false / FAILED release → Task 3 ✓
- §5 DB uk backstop(DuplicateKey 降 debug) → Task 3 Step 3d ✓
- §3 EvalAutoConfiguration 启用 properties + 注入 guard → Task 4 ✓
- §6 测试(guard claim/release、确定 actionId、去重 skip、FAILED release、既有更新) → Task 2/3 + Task 5 全量 ✓
- §7 非目标(Redis/durable、内部重试器、表结构)→ 未实现,符合 ✓

**类型一致性：** `ActionIdempotencyGuard.claim(String):boolean` / `release(String):void` 全 Task 统一;`ActionResult.ActionStatus.FAILED`(嵌套枚举,已核 ActionResult.java)与 `ActionResult.failed(actionId,actionType,errorCode,retryable)` 工厂一致;`ActionDispatchService` 4 参构造器 Task 3 定义、Task 4 调用、测试 setUp 使用一致;`ActionIdempotencyProperties.getTtlSeconds()/getMaxSize()`(Lombok)Task 1 定义、Task 2 使用一致。

**占位符：** 无 TBD/TODO;每个改代码步骤含完整代码。Native 说明为明确的「非本计划任务」后续项,非占位。
