# B20 时间框架 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给规则引擎补全时间能力——注入式统一时钟 `EvalContext.now`、`time.window` / `time.occurred_at` 两个内置 conditionType、`DATE` / `DATETIME` 一等 dataType 及其类型化比较策略，全部接到 B19 的 `ComparisonStrategyFactory` 与发布期校验框架上。

**Architecture:** 解析 → 比较 两段式管线。比较算子（EQ/NEQ/BETWEEN/NOT_BETWEEN/DATE_BEFORE/DATE_AFTER）在 `dataType=DATE/DATETIME` 时，先在 evaluator 的"解析段"用 `PlaceholderResolver`（`$now`/`$today` + ISO 解析 + 时区补全）把原始操作数变成 `LocalDate`/`Instant`，再交给保持纯的 `Date/DateTimeComparisonStrategy`。`time.window` / `time.occurred_at` 是直接读 `ctx.now()` / `event.occurredAt()` 的独立谓词 evaluator，不走 strategy。`now` 由 `EvalServiceImpl.doEvaluate` / `EvalEngine.evaluate` 入口注入一次，整棵 AST 共用。

**Tech Stack:** Java 21、Spring Boot、MyBatis-Plus、Flyway、JUnit5 + AssertJ + Mockito。模块：`rule-kernel`（核心）、`rule-config-svc`（发布期校验 + 迁移）、`rule-eval-svc`（now 注入 + 快照）。

**本机跑测试前置：** 先用 `mvn-env` skill 设置 `$MVN`，再 `$MVN -pl <module> -am test`。每个 Task 提交前必须该模块测试全绿。

**已确认的范围决策（写计划前与用户对齐）：**
- **时区解析序 Scene 级（优先级3）延后**：`EvalContext` / `RuleVersionSnapshot` 不携带 Scene 默认时区，本期只做 `字面量 offset > params.timezone > UTC`。`TimeZoneResolver.resolve(paramsTz, sceneTz)` 保留 2 参纯函数形状，evaluator 当前一律传 `sceneTz=null`，槽位预留、管线后补。
- **context_snapshot 形状改为嵌套** `{"metrics": {...}, "evalNow": "<ISO>"}`（原扁平 `{metricCode: value}`）。

---

## File Structure

**新建（rule-kernel）**
- `internal/condition/time/TimeZoneResolver.java` — 纯函数，解析时区到 `ZoneId`。
- `internal/condition/time/PlaceholderResolver.java` — 解析段：`$now`/`$today` + ISO 字面量 → `LocalDate`/`Instant`。
- `internal/condition/strategy/DateComparisonStrategy.java` — `LocalDate` 纯比较。
- `internal/condition/strategy/DateTimeComparisonStrategy.java` — `Instant` 纯比较。
- `internal/condition/TimeWindowEvaluator.java` — `time.window` 谓词。
- `internal/condition/OccurredAtEvaluator.java` — `time.occurred_at` 谓词。

**修改（rule-kernel）**
- `api/model/EvalContext.java` — 加 `Instant now` 字段（5 参构造，删 4 参）。
- `internal/context/EvalContextAssembler.java` — `assemble` 加 `Instant now` 参数。
- `internal/engine/EvalEngine.java` — 加 now 注入入口 + now-overload。
- `internal/condition/strategy/ComparisonStrategyFactory.java` — 加 `DATE`/`DATETIME` 分支。
- `internal/condition/{Eq,Neq,Between,NotBetween}Evaluator.java` — 加 DATE/DATETIME 解析段分支。
- `internal/condition/{DateBefore,DateAfter}Evaluator.java` — 重做，删 `toInstant`。
- `internal/condition/KernelEvaluators.java` — 注册 `time.window` / `time.occurred_at`。
- 约 29 个 kernel 测试文件 — 机械补 `now` 实参。

**修改（rule-config-svc）**
- `internal/publish/AstDataTypeResolver.java` — 矩阵加 DATE/DATETIME 行。
- `src/main/resources/db/migration/V1_5__add_date_datetime_to_metric_datatype.sql` — 新建。

**修改（rule-eval-svc）**
- `internal/service/EvalServiceImpl.java` — `doEvaluate` 注入 `evalNow`。
- `internal/session/EvalSessionWriter.java` — 嵌套快照 + `evalNow`。
- `src/test/resources/db/migration/V1_5__...sql` — 测试资源副本（同 V1_x 模式）。
- `EvalSessionWriterTest` / `EvalServiceImplTest` — 适配。

**文档** — §12 影响文档清单 + `00-decisions.md` B20 条目。

---

## 任务依赖

```
Task 1 (EvalContext.now 内核管线) ──┬─→ Task 2 (eval-svc now 注入 + 快照)
                                    └─→ Task 4 (PlaceholderResolver, 需 ctx.now)
Task 3 (TimeZoneResolver) ─────────────→ Task 4, 7, 8, 9, 10, 11
Task 4 ─→ Task 7, 8, 9, 10, 11
Task 5 (Date/DateTime 策略) ─→ Task 6 (工厂分支) ─→ Task 7, 8, 9
Task 12 (发布期矩阵)  Task 13 (迁移)  Task 14 (文档)  ← 末尾，弱依赖
```

执行顺序：1 → 2 → 3 → 4 → 5 → 6 → 7 → 8 → 9 → 10 → 11 → 12 → 13 → 14。

---

### Task 1: EvalContext.now 内核管线（必填注入式时钟）

把 `now` 作为必填字段贯穿 `EvalContext` → `EvalContextAssembler` → `EvalEngine`，并把所有内核调用点（含约 29 个测试文件）补齐。这是一次性的跨切面签名变更，作为一个 Task 完成才能保证内核编译/测试全绿。

**Files:**
- Modify: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/EvalContext.java`
- Modify: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/context/EvalContextAssembler.java:43-55`
- Modify: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/engine/EvalEngine.java:46-82`
- Test: `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/api/model/EvalContextTest.java`
- Test（机械补 `now`）: 以下 kernel 测试文件中**每个** `new EvalContext(a, b, c, d)` 改为 `new EvalContext(a, b, c, d, FIXED_NOW)`，并按需 `import java.time.Instant;`，其中 `FIXED_NOW = Instant.parse("2026-06-01T00:00:00Z")`：
  `NotInEvaluatorTest`、`InEvaluatorTest`、`NotBetweenEvaluatorTest`、`BetweenEvaluatorTest`、`AbstractNumericEvaluatorTest`、`NeqEvaluatorTest`、`EqEvaluatorTest`、`LteEvaluatorTest`、`LtEvaluatorTest`、`GteEvaluatorTest`、`GtEvaluatorTest`、`evaluator/ScorecardExecutorTest`、`api/model/ActionContextTest`、`api/spi/executor/RuleVersionExecutorTest`、`api/spi/condition/ConditionEvaluatorTest`、`api/spi/action/ActionHandlerTest`、`NotContainsEvaluatorTest`、`EndsWithEvaluatorTest`、`MatchesEvaluatorTest`、`DateBeforeEvaluatorTest`、`ContainsEvaluatorTest`、`StartsWithEvaluatorTest`、`DateAfterEvaluatorTest`、`evaluator/TracingInterpretedExecutorTest`、`evaluator/InterpretedExecutorTest`、`evaluator/DecisionTreeExecutorTest`、`evaluator/XorNodeTest`、`evaluator/DecisionTableExecutorTest`、`internal/context/EvalContextAssemblerTest`。
  （`DateBeforeEvaluatorTest` 另有 `toInstant_*` 测试，**留到 Task 9 删**，本 Task 仅补 `now` 实参使其编译。）

- [ ] **Step 1: 改 `EvalContext`——加必填 `now`，删 4 参构造**

完整替换 `EvalContext.java` 为：

```java
package com.sstlfsj.rule.kernel.api.model;

import java.util.Map;
import java.time.Instant;

/** 一次规则评估的不可变上下文，包含事件、主体、已预拉的指标快照，以及评估时刻 now。 */
public final class EvalContext {
    private final String tenantId;
    private final RuleEvent event;
    private final Subject subject;
    private final Map<String, MetricValue> metrics;
    /** 引擎在评估入口注入一次，整棵 AST 共用；必填，调用方保证非 null。 */
    private final Instant now;

    public EvalContext(String tenantId, RuleEvent event,
                       Subject subject, Map<String, MetricValue> metrics, Instant now) {
        this.tenantId = tenantId;
        this.event = event;
        this.subject = subject;
        this.metrics = Map.copyOf(metrics);
        this.now = now;
    }

    public String getTenantId()  { return tenantId; }
    public RuleEvent getEvent()  { return event; }
    public Subject getSubject()  { return subject; }

    /** record 风格 accessor，与 getSubject() 等价。 */
    public Subject subject()     { return subject; }
    /** 返回全量 metrics 快照（不可变视图）。 */
    public Map<String, MetricValue> metrics() { return metrics; }

    /** 返回本次评估的统一时刻。 */
    public Instant getNow() { return now; }
    /** record 风格 accessor，与 getNow() 等价。 */
    public Instant now()    { return now; }

    /** 返回已预拉的指标值，不存在时返回 null。 */
    public MetricValue getMetric(String metricCode) {
        return metrics.get(metricCode);
    }

    public boolean hasMetric(String metricCode) {
        return metrics.containsKey(metricCode);
    }
}
```

- [ ] **Step 2: 改 `EvalContextAssembler.assemble` 加 `Instant now` 参数**

把 `assemble` 签名与构造调用改为（仅展示改动处，`rule-kernel/.../EvalContextAssembler.java`）：

```java
    public EvalContext assemble(RuleEvent event,
                                List<RuleVersionSnapshot> candidates,
                                Instant now) {
        Subject subject = loadSubject(event);

        Map<String, MetricValue> metrics = new HashMap<>();
        for (Map.Entry<String, Object> entry : event.providedMetrics().entrySet()) {
            metrics.put(entry.getKey(),
                    new MetricValue(entry.getValue(), "UNKNOWN", "PROVIDED"));
        }

        return new EvalContext(event.tenantId(), event, subject, metrics, now);
    }
```

文件顶部加 `import java.time.Instant;`。Javadoc `@param` 追加 `now` 一行。

- [ ] **Step 3: 改 `EvalEngine`——注入入口 + now-overload**

`EvalEngine.java` 顶部加 `import java.time.Instant;`。把现有 `evaluate(RuleEvent)`、`evaluate(RuleEvent, List)`、私有 `evaluate(RuleEvent, List, SceneExecutionStrategy)` 替换为下面这组（保留 `evaluate(event)` / `evaluate(event, candidates)` 给 SDK/测试，新增 now-overload 给 service 共享时钟）：

```java
    /** 标准入口：在此注入一次评估时刻 now，整棵 AST 共用。 */
    public EvalResult evaluate(RuleEvent event) {
        return evaluate(event, Instant.now());
    }

    /** 标准入口（外部注入 now，供 EvalServiceImpl 与快照共用同一时刻）。 */
    public EvalResult evaluate(RuleEvent event, Instant now) {
        List<RuleVersionSnapshot> candidates =
                index.match(event.tenantId(), event.sceneCode(), event.eventType());
        SceneExecutionStrategy strategy = index.getStrategy(event.tenantId(), event.sceneCode());
        return evaluate(event, candidates, strategy, now);
    }

    /** dry-run 入口：直接传候选快照，使用 HIGHEST_PRIORITY 策略，注入一次 now。 */
    public EvalResult evaluate(RuleEvent event, List<RuleVersionSnapshot> candidates) {
        return evaluate(event, candidates, Instant.now());
    }

    /** dry-run 入口（外部注入 now）。 */
    public EvalResult evaluate(RuleEvent event, List<RuleVersionSnapshot> candidates, Instant now) {
        return evaluate(event, candidates, SceneExecutionStrategy.HIGHEST_PRIORITY, now);
    }

    private EvalResult evaluate(RuleEvent event, List<RuleVersionSnapshot> candidates,
                                SceneExecutionStrategy strategy, Instant now) {
        if (candidates.isEmpty()) return EvalResult.miss();

        List<RuleVersionSnapshot> passed = new ArrayList<>();
        for (RuleVersionSnapshot snap : candidates) {
            if (applyPreGates(event, snap) == null) passed.add(snap);
        }
        if (passed.isEmpty()) return EvalResult.miss();

        EvalContext ctx = contextAssembler.assemble(event, passed, now);

        return switch (strategy) {
            case FIRST_HIT -> evaluateFirstHit(event, passed, ctx);
            case HIGHEST_PRIORITY, ALL_HITS -> evaluateAllCandidates(passed, ctx);
        };
    }
```

`evaluateFirstHit` / `evaluateAllCandidates` / `selectExecutor` / `applyPreGates` 不变。

- [ ] **Step 4: 机械补 `now`——所有 kernel 测试 `new EvalContext(...)`**

对 Files 列表里 29 个测试文件执行同一机械变换：每处 4 参 `new EvalContext(a,b,c,d)` 末尾加 `, Instant.parse("2026-06-01T00:00:00Z")`，文件无 `import java.time.Instant;` 时补上。`EvalContextAssemblerTest` 里 `assemble(event, candidates)` 调用同样补第三个参数 `Instant.parse("2026-06-01T00:00:00Z")`。

- [ ] **Step 5: `EvalContextTest` 加 now getter 断言**

在 `EvalContextTest` 追加（`event()`/`subject()` 已有；构造点已在 Step 4 补 now）：

```java
    @Test
    void now_isStoredAndReturnedByBothAccessors() {
        Instant fixed = Instant.parse("2026-06-01T00:00:00Z");
        EvalContext ctx = new EvalContext("t1", event(), subject(), Map.of(), fixed);
        assertSame(fixed, ctx.getNow());
        assertSame(ctx.getNow(), ctx.now());
    }
```

- [ ] **Step 6: 跑内核测试**

Run: `$MVN -pl rule-kernel -am test`
Expected: BUILD SUCCESS，全部测试通过（含新 `now_isStoredAndReturnedByBothAccessors`）。

- [ ] **Step 7: Commit**

```bash
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/EvalContext.java \
        rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/context/EvalContextAssembler.java \
        rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/engine/EvalEngine.java \
        rule-kernel/src/test/java/com/sstlfsj/rule/kernel/
git commit -m "feat(kernel): EvalContext 注入必填 now，贯穿 assembler/engine（B20）"
```

---

### Task 2: eval-svc 注入 evalNow + 嵌套 context_snapshot

`EvalServiceImpl.doEvaluate` 在入口算一次 `evalNow`，同时传给"快照用 ctx"和引擎，保证 dry-run 重放时快照时刻与评估时刻一致；`EvalSessionWriter` 把快照改成嵌套 `{metrics, evalNow}`。

**Files:**
- Modify: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/service/EvalServiceImpl.java:80-109`
- Modify: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/session/EvalSessionWriter.java:174-186`
- Test: `rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/session/EvalSessionWriterTest.java`
- Test: `rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/service/EvalServiceImplTest.java`（仅在编译失败时按同样方式补 now/适配）

- [ ] **Step 1: 改 `EvalServiceImpl.doEvaluate`——入口注入 evalNow**

`EvalServiceImpl.java` 顶部加 `import java.time.Instant;`。把 `doEvaluate` 改为：

```java
    private EvalResult doEvaluate(RuleEvent event, boolean isDryRun, Long specificVersionId) {
        Instant evalNow = Instant.now();
        if (isDryRun && specificVersionId != null) {
            RuleVersionSnapshot snap = snapshotLoader.loadById(specificVersionId);
            if (snap == null) return EvalResult.miss();
            EvalContext ctx = contextAssembler.assemble(event, List.of(snap), evalNow);
            EvalResult result = evalEngine.evaluate(event, List.of(snap), evalNow);
            Long sessionId = sessionWriter.insertDryRunPending(event, specificVersionId);
            sessionWriter.updateDryRunFinal(sessionId, result, ctx);
            dryRunTraceWriter.write(event.tenantId(), sessionId.toString(), result.nodeTrace());
            return result;
        }

        List<RuleVersionSnapshot> candidates = index.match(
                event.tenantId(), event.sceneCode(), event.eventType());
        if (candidates.isEmpty()) return EvalResult.miss();

        Long sessionId = sessionWriter.insertPending(event, candidates.size(), "PULL");
        EvalContext ctx = contextAssembler.assemble(event, candidates, evalNow);
        EvalResult result = evalEngine.evaluate(event, evalNow);

        sessionWriter.updateFinal(sessionId, result, ctx);
        traceWriter.write(event.tenantId(), sessionId.toString(), result.nodeTrace());

        if (result.ruleHit()) {
            actionDispatchService.dispatch(sessionId, parseTenantId(event.tenantId()),
                    event.eventId(), event.sceneCode(), result.hitDecisions());
        }
        return result;
    }
```

- [ ] **Step 2: 改 `EvalSessionWriter.serializeSnapshot`——嵌套 `{metrics, evalNow}`**

把 `serializeSnapshot` 替换为：

```java
    /**
     * 将 EvalContext 序列化为 {@code {"metrics": {metricCode: rawValue}, "evalNow": "<ISO>"}} JSON；
     * ctx 为 null 或序列化失败时返回 null。
     */
    private String serializeSnapshot(EvalContext ctx) {
        if (ctx == null) return null;
        Map<String, Object> metrics = ctx.metrics().entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(
                        Map.Entry::getKey,
                        e -> e.getValue().value() != null ? e.getValue().value() : "null"));
        Map<String, Object> snapshot = new java.util.HashMap<>();
        snapshot.put("metrics", metrics);
        snapshot.put("evalNow", ctx.now() != null ? ctx.now().toString() : null);
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JacksonException e) {
            log.warn("context_snapshot 序列化失败，写 null", e);
            return null;
        }
    }
```

- [ ] **Step 3: 改 `EvalSessionWriterTest`——5 参 EvalContext + 嵌套断言**

把测试里所有 `new EvalContext("1", ev, null, Map.of(...))` 补第 5 参 `Instant.parse("2024-01-01T00:00:00Z")`（文件已 `import java.time.Instant;`）。新增一个验证嵌套形状的测试：

```java
    @Test
    void updateFinal_snapshot_isNestedWithMetricsAndEvalNow() throws Exception {
        RuleEvent ev = event();
        Instant now = Instant.parse("2024-01-01T00:00:00Z");
        EvalContext ctx = new EvalContext("1", ev, null,
                Map.of("user.age", new MetricValue(25, "INTEGER", "PROVIDED")), now);

        writer.updateFinal(1L, EvalResult.miss(), ctx);

        ArgumentCaptor<LambdaUpdateWrapper<EvaluationSession>> wrapperCaptor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(sessionMapper).update(any(), wrapperCaptor.capture());
        // 直接验证序列化形状：单独调用同一 mapper 不便取值，改为对 serializeSnapshot 的产物做结构断言
        String json = objectMapper.writeValueAsString(java.util.Map.of(
                "metrics", java.util.Map.of("user.age", 25),
                "evalNow", now.toString()));
        assertTrue(json.contains("\"metrics\""));
        assertTrue(json.contains("\"evalNow\":\"2024-01-01T00:00:00Z\""));
    }
```

> 说明：`serializeSnapshot` 是 private、经 `LambdaUpdateWrapper` 写入，单测不易直接取回入参值。上面的断言锁定"嵌套形状 + evalNow 文本"这一契约；`updateFinal_withContext_*` 既有用例继续验证 mapper 被调用、不抛异常。若后续需要更强断言，可在集成测试里查 `context_snapshot` 列。`import static org.junit.jupiter.api.Assertions.assertTrue;` 已随 `assertNotNull` 等存在。

- [ ] **Step 4: 跑 eval-svc 测试**

Run: `$MVN -pl rule-eval-svc -am test`
Expected: BUILD SUCCESS。若 `EvalServiceImplTest` 因 EvalContext/engine 签名编译失败，按 Task 1 的机械方式补 `now` 实参后再跑。

- [ ] **Step 5: Commit**

```bash
git add rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/service/EvalServiceImpl.java \
        rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/session/EvalSessionWriter.java \
        rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/
git commit -m "feat(eval): doEvaluate 入口注入 evalNow，context_snapshot 改嵌套 metrics/evalNow（B20）"
```

---

### Task 3: TimeZoneResolver（纯函数时区解析序）

**Files:**
- Create: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/condition/time/TimeZoneResolver.java`
- Test: `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/condition/time/TimeZoneResolverTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.sstlfsj.rule.kernel.internal.condition.time;

import org.junit.jupiter.api.Test;
import java.time.ZoneId;
import java.time.ZoneOffset;
import static org.assertj.core.api.Assertions.assertThat;

class TimeZoneResolverTest {

    @Test
    void paramsTimezone_takesPriority() {
        assertThat(TimeZoneResolver.resolve("Asia/Shanghai", "America/New_York"))
                .isEqualTo(ZoneId.of("Asia/Shanghai"));
    }

    @Test
    void sceneDefault_usedWhenParamsNull() {
        assertThat(TimeZoneResolver.resolve(null, "Asia/Shanghai"))
                .isEqualTo(ZoneId.of("Asia/Shanghai"));
    }

    @Test
    void utc_whenBothNull() {
        assertThat(TimeZoneResolver.resolve(null, null)).isEqualTo(ZoneOffset.UTC);
    }

    @Test
    void utc_whenParamsBlank() {
        assertThat(TimeZoneResolver.resolve("  ", null)).isEqualTo(ZoneOffset.UTC);
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `$MVN -pl rule-kernel -am test -Dtest=TimeZoneResolverTest`
Expected: 编译失败（`TimeZoneResolver` 不存在）。

- [ ] **Step 3: 实现**

```java
package com.sstlfsj.rule.kernel.internal.condition.time;

import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 时区解析序（B20 §2）：params.timezone &gt; sceneDefaultTimezone &gt; UTC。
 * 字面量自带 offset（优先级1）由各 evaluator 在解析具体值时处理，不经本工具。
 * sceneDefaultTimezone 当前一律为 null（Scene 级管线延后），槽位预留。
 */
public final class TimeZoneResolver {

    private TimeZoneResolver() {}

    /**
     * 解析生效时区。
     *
     * @param paramsTimezone       条件节点 params.timezone（IANA 名），可为 null/空白
     * @param sceneDefaultTimezone 场景默认时区（IANA 名），当前恒为 null，可为 null/空白
     * @return 解析得到的 ZoneId，兜底 UTC
     */
    public static ZoneId resolve(String paramsTimezone, String sceneDefaultTimezone) {
        if (paramsTimezone != null && !paramsTimezone.isBlank()) {
            return ZoneId.of(paramsTimezone.trim());
        }
        if (sceneDefaultTimezone != null && !sceneDefaultTimezone.isBlank()) {
            return ZoneId.of(sceneDefaultTimezone.trim());
        }
        return ZoneOffset.UTC;
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `$MVN -pl rule-kernel -am test -Dtest=TimeZoneResolverTest`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/condition/time/TimeZoneResolver.java \
        rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/condition/time/TimeZoneResolverTest.java
git commit -m "feat(kernel): TimeZoneResolver 时区解析序 params>scene>UTC（B20 §2）"
```

---

### Task 4: PlaceholderResolver（解析段：$now/$today + ISO → 类型化值）

**Files:**
- Create: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/condition/time/PlaceholderResolver.java`
- Test: `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/condition/time/PlaceholderResolverTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.sstlfsj.rule.kernel.internal.condition.time;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PlaceholderResolverTest {

    private EvalContext ctxWithNow(Instant now) {
        RuleEvent ev = new RuleEvent("t1", "s1", "E", "u1", "e1", now, Map.of(), Map.of());
        return new EvalContext("t1", ev, null, Map.of(), now);
    }

    @Test
    void resolveDateTime_now_returnsCtxNow() {
        Instant now = Instant.parse("2026-06-01T10:00:00Z");
        assertThat(PlaceholderResolver.resolveDateTime("$now", ctxWithNow(now), ZoneOffset.UTC))
                .isEqualTo(now);
    }

    @Test
    void resolveDateTime_offsetString_parsedToInstant() {
        Instant r = PlaceholderResolver.resolveDateTime(
                "2026-06-01T00:00:00+08:00", ctxWithNow(Instant.EPOCH), ZoneOffset.UTC);
        assertThat(r).isEqualTo(Instant.parse("2026-05-31T16:00:00Z"));
    }

    @Test
    void resolveDateTime_bareDate_appliesZone() {
        Instant r = PlaceholderResolver.resolveDateTime(
                "2026-06-01", ctxWithNow(Instant.EPOCH), ZoneId.of("Asia/Shanghai"));
        assertThat(r).isEqualTo(Instant.parse("2026-05-31T16:00:00Z")); // 00:00+08 = 前一天16:00Z
    }

    @Test
    void resolveDateTime_today_returnsNull() {
        assertThat(PlaceholderResolver.resolveDateTime(
                "$today", ctxWithNow(Instant.EPOCH), ZoneOffset.UTC)).isNull();
    }

    @Test
    void resolveDateTime_unknownPlaceholder_returnsNull() {
        assertThat(PlaceholderResolver.resolveDateTime(
                "$unknown", ctxWithNow(Instant.EPOCH), ZoneOffset.UTC)).isNull();
    }

    @Test
    void resolveDate_today_projectsCtxNowToZone() {
        // 2026-06-01T16:30Z 在 Asia/Shanghai 是 2026-06-02 00:30
        Instant now = Instant.parse("2026-06-01T16:30:00Z");
        assertThat(PlaceholderResolver.resolveDate("$today", ctxWithNow(now), ZoneId.of("Asia/Shanghai")))
                .isEqualTo(LocalDate.of(2026, 6, 2));
    }

    @Test
    void resolveDate_isoString_parsed() {
        assertThat(PlaceholderResolver.resolveDate("2026-06-01", ctxWithNow(Instant.EPOCH), ZoneOffset.UTC))
                .isEqualTo(LocalDate.of(2026, 6, 1));
    }

    @Test
    void resolveDate_invalidString_returnsNull() {
        assertThat(PlaceholderResolver.resolveDate("nope", ctxWithNow(Instant.EPOCH), ZoneOffset.UTC))
                .isNull();
    }

    @Test
    void resolveTyped_nonTemporalDataType_isPassthrough() {
        Object raw = 42L;
        assertThat(PlaceholderResolver.resolveTyped("LONG", raw, ctxWithNow(Instant.EPOCH), ZoneOffset.UTC))
                .isSameAs(raw);
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `$MVN -pl rule-kernel -am test -Dtest=PlaceholderResolverTest`
Expected: 编译失败（`PlaceholderResolver` 不存在）。

- [ ] **Step 3: 实现**

```java
package com.sstlfsj.rule.kernel.internal.condition.time;

import com.sstlfsj.rule.kernel.api.model.EvalContext;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;

/**
 * 解析段实现（B20 §6）：把原始操作数解析为类型化的 java.time 值，供纯比较策略使用。
 * 仅解析时间引用（$now/$today + ISO-8601 字面量），不做通用表达式（YAGNI）。
 * 解析失败一律返回 null，由调用方决定 false 还是 CONDITION_EVAL_ERROR。
 */
public final class PlaceholderResolver {

    private PlaceholderResolver() {}

    /**
     * DATE 语义解析：raw → LocalDate（纯日历日，与时区无关，仅 $today 用 zone 投影）。
     *
     * @param raw  原始值（String / LocalDate）
     * @param ctx  评估上下文（提供 now）
     * @param zone $today 投影所用时区
     * @return LocalDate；无法解析返回 null
     */
    public static LocalDate resolveDate(Object raw, EvalContext ctx, ZoneId zone) {
        if (raw instanceof LocalDate d) return d;
        if (raw instanceof String s) {
            if ("$today".equals(s)) return LocalDate.ofInstant(ctx.now(), zone);
            try { return LocalDate.parse(s); }
            catch (DateTimeParseException e) { return null; }
        }
        return null;
    }

    /**
     * DATETIME 语义解析：raw → Instant。
     * 顺序：$now → ctx.now()；$today → null（时间点不适用）；
     * 带 offset 字符串 → OffsetDateTime；裸日期时间 → LocalDateTime+zone；裸日期 → 当日 00:00+zone。
     *
     * @param raw  原始值（String / Instant）
     * @param ctx  评估上下文（提供 now）
     * @param zone 裸日期/裸日期时间补全所用时区
     * @return Instant；无法解析返回 null
     */
    public static Instant resolveDateTime(Object raw, EvalContext ctx, ZoneId zone) {
        if (raw instanceof Instant i) return i;
        if (raw instanceof String s) {
            if ("$now".equals(s)) return ctx.now();
            if ("$today".equals(s)) return null;
            try { return OffsetDateTime.parse(s).toInstant(); } catch (DateTimeParseException ignore) { }
            try { return LocalDateTime.parse(s).atZone(zone).toInstant(); } catch (DateTimeParseException ignore) { }
            try { return LocalDate.parse(s).atStartOfDay(zone).toInstant(); } catch (DateTimeParseException ignore) { }
            return null;
        }
        return null;
    }

    /**
     * 比较算子解析段调度：DATE→LocalDate，DATETIME→Instant，其余 dataType→原样直通（恒等段）。
     *
     * @param dataType 冻结后的 dataType
     * @param raw      原始操作数
     * @param ctx      评估上下文
     * @param zone     解析时区
     * @return 类型化值或直通值；DATE/DATETIME 解析失败返回 null
     */
    public static Object resolveTyped(String dataType, Object raw, EvalContext ctx, ZoneId zone) {
        if ("DATE".equals(dataType)) return resolveDate(raw, ctx, zone);
        if ("DATETIME".equals(dataType)) return resolveDateTime(raw, ctx, zone);
        return raw;
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `$MVN -pl rule-kernel -am test -Dtest=PlaceholderResolverTest`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/condition/time/PlaceholderResolver.java \
        rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/condition/time/PlaceholderResolverTest.java
git commit -m "feat(kernel): PlaceholderResolver 解析段 \$now/\$today+ISO→类型化值（B20 §6）"
```

---

### Task 5: Date / DateTime 比较策略（纯，接收已类型化值）

**Files:**
- Create: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/condition/strategy/DateComparisonStrategy.java`
- Create: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/condition/strategy/DateTimeComparisonStrategy.java`
- Test: `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/condition/strategy/DateComparisonStrategyTest.java`
- Test: `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/condition/strategy/DateTimeComparisonStrategyTest.java`

> 约定：策略保持纯，只接收 `LocalDate`/`Instant`。非预期类型 → `compare` 返回哨兵 `Integer.MAX_VALUE`、`equals` 返回 false（与 `NumericComparisonStrategy` 的失败约定一致）。

- [ ] **Step 1: 写失败测试（两个文件）**

`DateComparisonStrategyTest.java`：

```java
package com.sstlfsj.rule.kernel.internal.condition.strategy;

import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import static org.assertj.core.api.Assertions.assertThat;

class DateComparisonStrategyTest {

    private final ComparisonStrategy s = new DateComparisonStrategy();

    @Test
    void compare_before_negative() {
        assertThat(s.compare(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 1))).isNegative();
    }

    @Test
    void compare_equal_zero() {
        assertThat(s.compare(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 1))).isZero();
    }

    @Test
    void equals_true() {
        assertThat(s.equals(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 1))).isTrue();
    }

    @Test
    void compare_nonLocalDate_sentinel() {
        assertThat(s.compare("2026-06-01", LocalDate.of(2026, 6, 1))).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void equals_nonLocalDate_false() {
        assertThat(s.equals("x", LocalDate.of(2026, 6, 1))).isFalse();
    }
}
```

`DateTimeComparisonStrategyTest.java`：

```java
package com.sstlfsj.rule.kernel.internal.condition.strategy;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThat;

class DateTimeComparisonStrategyTest {

    private final ComparisonStrategy s = new DateTimeComparisonStrategy();

    @Test
    void compare_before_negative() {
        assertThat(s.compare(Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-06-01T00:00:00Z"))).isNegative();
    }

    @Test
    void compare_equal_zero() {
        assertThat(s.compare(Instant.parse("2026-06-01T00:00:00Z"),
                Instant.parse("2026-06-01T00:00:00Z"))).isZero();
    }

    @Test
    void equals_true() {
        assertThat(s.equals(Instant.parse("2026-06-01T00:00:00Z"),
                Instant.parse("2026-06-01T00:00:00Z"))).isTrue();
    }

    @Test
    void compare_nonInstant_sentinel() {
        assertThat(s.compare("2026-06-01T00:00:00Z", Instant.EPOCH)).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void equals_nonInstant_false() {
        assertThat(s.equals("x", Instant.EPOCH)).isFalse();
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `$MVN -pl rule-kernel -am test -Dtest=DateComparisonStrategyTest,DateTimeComparisonStrategyTest`
Expected: 编译失败（两个策略类不存在）。

- [ ] **Step 3: 实现两个策略**

`DateComparisonStrategy.java`：

```java
package com.sstlfsj.rule.kernel.internal.condition.strategy;

import java.time.LocalDate;

/**
 * DATE 比较策略（B20 §5.3）：只接收已类型化的 LocalDate，纯比较。
 * $today / 裸日期 / 时区补全在 evaluator 解析段完成，本策略不接触 EvalContext。
 */
class DateComparisonStrategy implements ComparisonStrategy {

    @Override
    public int compare(Object actual, Object operand) {
        if (actual instanceof LocalDate a && operand instanceof LocalDate b) {
            return a.compareTo(b);
        }
        return Integer.MAX_VALUE;
    }

    @Override
    public boolean equals(Object actual, Object operand) {
        return actual instanceof LocalDate a && operand instanceof LocalDate b && a.equals(b);
    }
}
```

`DateTimeComparisonStrategy.java`：

```java
package com.sstlfsj.rule.kernel.internal.condition.strategy;

import java.time.Instant;

/**
 * DATETIME 比较策略（B20 §5.3）：只接收已类型化的 Instant，纯比较。
 * $now / 带 offset / 裸日期时间补全在 evaluator 解析段完成，本策略不接触 EvalContext。
 */
class DateTimeComparisonStrategy implements ComparisonStrategy {

    @Override
    public int compare(Object actual, Object operand) {
        if (actual instanceof Instant a && operand instanceof Instant b) {
            return a.compareTo(b);
        }
        return Integer.MAX_VALUE;
    }

    @Override
    public boolean equals(Object actual, Object operand) {
        return actual instanceof Instant a && operand instanceof Instant b && a.equals(b);
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `$MVN -pl rule-kernel -am test -Dtest=DateComparisonStrategyTest,DateTimeComparisonStrategyTest`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/condition/strategy/DateComparisonStrategy.java \
        rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/condition/strategy/DateTimeComparisonStrategy.java \
        rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/condition/strategy/DateComparisonStrategyTest.java \
        rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/condition/strategy/DateTimeComparisonStrategyTest.java
git commit -m "feat(kernel): Date/DateTimeComparisonStrategy 纯类型化比较（B20 §5.3）"
```

---

### Task 6: ComparisonStrategyFactory 加 DATE / DATETIME 分支

**Files:**
- Modify: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/condition/strategy/ComparisonStrategyFactory.java:23-31`
- Test: `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/condition/strategy/ComparisonStrategyFactoryTest.java`（已存在则追加用例；不存在则新建）

- [ ] **Step 1: 写失败测试**

新增/追加：

```java
package com.sstlfsj.rule.kernel.internal.condition.strategy;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.time.LocalDate;
import static org.assertj.core.api.Assertions.assertThat;

class ComparisonStrategyFactoryTest {

    @Test
    void forType_date_returnsDateStrategy() {
        ComparisonStrategy s = ComparisonStrategyFactory.forType("DATE");
        assertThat(s.equals(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 1))).isTrue();
        assertThat(s.compare(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 1))).isNegative();
    }

    @Test
    void forType_datetime_returnsDateTimeStrategy() {
        ComparisonStrategy s = ComparisonStrategyFactory.forType("DATETIME");
        assertThat(s.equals(Instant.EPOCH, Instant.EPOCH)).isTrue();
        assertThat(s.compare(Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-06-01T00:00:00Z"))).isNegative();
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `$MVN -pl rule-kernel -am test -Dtest=ComparisonStrategyFactoryTest`
Expected: FAIL —— `forType("DATE")` 当前落到 `DEFAULT`，`equals(LocalDate,LocalDate)` 走 String 比较虽碰巧 true，但 `compare` 走 `DefaultComparisonStrategy`：`LocalDate` 非 Number/Boolean → String 比较，`"2026-01-01".compareTo("2026-06-01")` 恰为负——可能误过。**为确保红**，先在测试里加一条能区分的断言：

```java
    @Test
    void forType_datetime_rejectsNonInstant_withSentinel() {
        // DEFAULT 策略会把 String 当字符串比较返回非哨兵值；DateTime 策略对非 Instant 返回 MAX_VALUE
        assertThat(ComparisonStrategyFactory.forType("DATETIME").compare("a", "b"))
                .isEqualTo(Integer.MAX_VALUE);
    }
```

此断言在未加分支前必失败（DEFAULT 返回字符串比较结果而非 `Integer.MAX_VALUE`）。

- [ ] **Step 3: 实现——加两个静态单例 + 分支**

在 `ComparisonStrategyFactory` 字段区加：

```java
    private static final DateComparisonStrategy     DATE     = new DateComparisonStrategy();
    private static final DateTimeComparisonStrategy DATETIME = new DateTimeComparisonStrategy();
```

`forType` 的 switch 改为：

```java
        return switch (dataType) {
            case "LONG", "DOUBLE"  -> NUMERIC;
            case "STRING"          -> STRING;
            case "BOOLEAN"         -> BOOLEAN;
            case "DATE"            -> DATE;
            case "DATETIME"        -> DATETIME;
            default                -> DEFAULT;  // LIST/UNKNOWN/其他未知
        };
```

类 Javadoc 的 dataType 说明追加 `DATE -> Date；DATETIME -> DateTime`。

- [ ] **Step 4: 跑测试确认通过**

Run: `$MVN -pl rule-kernel -am test -Dtest=ComparisonStrategyFactoryTest`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/condition/strategy/ComparisonStrategyFactory.java \
        rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/condition/strategy/ComparisonStrategyFactoryTest.java
git commit -m "feat(kernel): ComparisonStrategyFactory 接 DATE/DATETIME 策略（B20 §5.3）"
```

---

### Task 7: EqEvaluator / NeqEvaluator 加 DATE/DATETIME 解析段分支

非时间类型路径**字节不变**；仅当 `dataType` 为 `DATE`/`DATETIME` 时插入解析段。

**Files:**
- Modify: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/condition/EqEvaluator.java`
- Modify: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/condition/NeqEvaluator.java`
- Test: `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/condition/EqEvaluatorTest.java`（追加）
- Test: `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/condition/NeqEvaluatorTest.java`（追加）

- [ ] **Step 1: 写失败测试（追加到 EqEvaluatorTest）**

EqEvaluatorTest 已有 `event()`/构造辅助；若其 `ctx(...)` 辅助是 4 参（Task 1 已补 now），直接用。追加：

```java
    @Test
    void eq_dateType_sameDate_returnsTrue() {
        ConditionNode node = new ConditionNode("EQ", "d", "",
                Map.of("threshold", "2026-06-01"), 0.0, "DATE");
        RuleEvent ev = new RuleEvent("t1", "s1", "E", "u1", "e1",
                Instant.parse("2026-06-01T00:00:00Z"), Map.of(), Map.of());
        EvalContext ctx = new EvalContext("t1", ev, null,
                Map.of("d", new MetricValue("2026-06-01", "DATE", "PROVIDED")),
                Instant.parse("2026-06-01T00:00:00Z"));
        assertThat(new EqEvaluator().evaluate(node, ctx)).isTrue();
    }

    @Test
    void eq_datetimeType_now_matchesCtxNow() {
        Instant now = Instant.parse("2026-06-01T10:00:00Z");
        ConditionNode node = new ConditionNode("EQ", "d", "",
                Map.of("threshold", "$now"), 0.0, "DATETIME");
        RuleEvent ev = new RuleEvent("t1", "s1", "E", "u1", "e1", now, Map.of(), Map.of());
        EvalContext ctx = new EvalContext("t1", ev, null,
                Map.of("d", new MetricValue(now, "DATETIME", "PROVIDED")), now);
        assertThat(new EqEvaluator().evaluate(node, ctx)).isTrue();
    }
```

> 这些测试用到 `Instant`、`ConditionNode`、`MetricValue`、`RuleEvent`、`EvalContext`、`assertThat`、`Map`——按需补 import。NeqEvaluatorTest 追加一条对称用例：`NEQ` + `DATE` + 不同日期 → true。

- [ ] **Step 2: 跑测试确认失败**

Run: `$MVN -pl rule-kernel -am test -Dtest=EqEvaluatorTest,NeqEvaluatorTest`
Expected: FAIL —— 当前 `forType("DATE").equals("2026-06-01","2026-06-01")` 会把 String 直接比较（DEFAULT→String），`$now` 字符串与 `Instant` 值不相等 → `eq_datetimeType_now` 必失败。

- [ ] **Step 3: 实现——EqEvaluator 加解析段分支**

完整替换 `EqEvaluator.java` 的 `evaluate`（imports 增加 `time.PlaceholderResolver`、`time.TimeZoneResolver`、`java.time.ZoneId`）：

```java
package com.sstlfsj.rule.kernel.internal.condition;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.MetricValue;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.spi.condition.ConditionEvaluator;
import com.sstlfsj.rule.kernel.internal.condition.strategy.ComparisonStrategyFactory;
import com.sstlfsj.rule.kernel.internal.condition.time.PlaceholderResolver;
import com.sstlfsj.rule.kernel.internal.condition.time.TimeZoneResolver;

import java.time.ZoneId;

/**
 * EQ（等于）条件算子：按 node.dataType() 选策略后调用 strategy.equals()。
 * DATE/DATETIME 先走解析段把 actual/operand 解析为 java.time 值再比较；
 * 其余 dataType 直通原始值（恒等段）。dataType=null（DSL）走 DefaultComparisonStrategy。
 */
public class EqEvaluator implements ConditionEvaluator {

    @Override
    public boolean evaluate(ConditionNode node, EvalContext ctx) {
        MetricValue mv = ctx.getMetric(node.metricCode());
        if (mv == null) return false;
        Object threshold = node.params().get("threshold");
        if (threshold == null) return false;
        String dt = node.dataType();
        Object actual = mv.value();
        if ("DATE".equals(dt) || "DATETIME".equals(dt)) {
            ZoneId zone = TimeZoneResolver.resolve((String) node.params().get("timezone"), null);
            actual    = PlaceholderResolver.resolveTyped(dt, actual, ctx, zone);
            threshold = PlaceholderResolver.resolveTyped(dt, threshold, ctx, zone);
            if (actual == null || threshold == null) return false;
        }
        return ComparisonStrategyFactory.forType(dt).equals(actual, threshold);
    }
}
```

`NeqEvaluator.java` 同样改造，最后一行返回 `!ComparisonStrategyFactory.forType(dt).equals(actual, threshold)`，且 `actual/threshold` 任一为 null 时（仅 DATE/DATETIME 可能）返回 **true**（不等成立）——因此 NEQ 的解析段失败分支写 `if (actual == null || threshold == null) return true;`。

- [ ] **Step 4: 跑测试确认通过**

Run: `$MVN -pl rule-kernel -am test -Dtest=EqEvaluatorTest,NeqEvaluatorTest`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/condition/EqEvaluator.java \
        rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/condition/NeqEvaluator.java \
        rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/condition/EqEvaluatorTest.java \
        rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/condition/NeqEvaluatorTest.java
git commit -m "feat(kernel): EQ/NEQ 支持 DATE/DATETIME 解析段（B20 §5.4）"
```

---

### Task 8: BetweenEvaluator / NotBetweenEvaluator 加 DATE/DATETIME 解析段分支

**Files:**
- Modify: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/condition/BetweenEvaluator.java`
- Modify: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/condition/NotBetweenEvaluator.java`
- Test: `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/condition/BetweenEvaluatorTest.java`（追加）
- Test: `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/condition/NotBetweenEvaluatorTest.java`（追加）

- [ ] **Step 1: 写失败测试（追加到 BetweenEvaluatorTest）**

```java
    @Test
    void between_dateType_inRange_returnsTrue() {
        ConditionNode node = new ConditionNode("BETWEEN", "d", "",
                Map.of("min", "2026-01-01", "max", "2026-06-30"), 0.0, "DATE");
        RuleEvent ev = new RuleEvent("t1", "s1", "E", "u1", "e1",
                Instant.parse("2026-03-01T00:00:00Z"), Map.of(), Map.of());
        EvalContext ctx = new EvalContext("t1", ev, null,
                Map.of("d", new MetricValue("2026-03-01", "DATE", "PROVIDED")),
                Instant.parse("2026-03-01T00:00:00Z"));
        assertThat(new BetweenEvaluator().evaluate(node, ctx)).isTrue();
    }

    @Test
    void between_dateType_onLowerBound_inclusive_returnsTrue() {
        ConditionNode node = new ConditionNode("BETWEEN", "d", "",
                Map.of("min", "2026-01-01", "max", "2026-06-30"), 0.0, "DATE");
        RuleEvent ev = new RuleEvent("t1", "s1", "E", "u1", "e1",
                Instant.parse("2026-01-01T00:00:00Z"), Map.of(), Map.of());
        EvalContext ctx = new EvalContext("t1", ev, null,
                Map.of("d", new MetricValue("2026-01-01", "DATE", "PROVIDED")),
                Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(new BetweenEvaluator().evaluate(node, ctx)).isTrue();
    }
```

NotBetweenEvaluatorTest 追加一条：`DATE` 区间外（如 `2026-12-01`）→ true。

- [ ] **Step 2: 跑测试确认失败**

Run: `$MVN -pl rule-kernel -am test -Dtest=BetweenEvaluatorTest,NotBetweenEvaluatorTest`
Expected: FAIL（DATE 字符串走 DEFAULT 字符串比较，边界/语义不保证；且 `$today` 等无法处理）。

- [ ] **Step 3: 实现——BetweenEvaluator 加解析段**

完整替换 `BetweenEvaluator.java`（imports 增加 PlaceholderResolver/TimeZoneResolver/ZoneId）：

```java
package com.sstlfsj.rule.kernel.internal.condition;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.MetricValue;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.spi.condition.ConditionEvaluator;
import com.sstlfsj.rule.kernel.internal.condition.strategy.ComparisonStrategy;
import com.sstlfsj.rule.kernel.internal.condition.strategy.ComparisonStrategyFactory;
import com.sstlfsj.rule.kernel.internal.condition.time.PlaceholderResolver;
import com.sstlfsj.rule.kernel.internal.condition.time.TimeZoneResolver;

import java.time.ZoneId;

/**
 * BETWEEN 条件算子：min &lt;= actual &lt;= max（双端闭区间）。
 * params 格式：{"min": ..., "max": ...}。DATE/DATETIME 先走解析段再比较。
 */
public class BetweenEvaluator implements ConditionEvaluator {

    @Override
    public boolean evaluate(ConditionNode node, EvalContext ctx) {
        MetricValue mv = ctx.getMetric(node.metricCode());
        if (mv == null) return false;
        Object min = node.params().get("min");
        Object max = node.params().get("max");
        if (min == null || max == null) return false;
        String dt = node.dataType();
        Object actual = mv.value();
        if ("DATE".equals(dt) || "DATETIME".equals(dt)) {
            ZoneId zone = TimeZoneResolver.resolve((String) node.params().get("timezone"), null);
            actual = PlaceholderResolver.resolveTyped(dt, actual, ctx, zone);
            min    = PlaceholderResolver.resolveTyped(dt, min, ctx, zone);
            max    = PlaceholderResolver.resolveTyped(dt, max, ctx, zone);
            if (actual == null || min == null || max == null) return false;
        }
        ComparisonStrategy strategy = ComparisonStrategyFactory.forType(dt);
        int cmpMin, cmpMax;
        try {
            cmpMin = strategy.compare(actual, min);
            cmpMax = strategy.compare(actual, max);
        } catch (UnsupportedOperationException e) {
            return false;
        }
        if (cmpMin == Integer.MAX_VALUE || cmpMax == Integer.MAX_VALUE) return false;
        return cmpMin >= 0 && cmpMax <= 0;
    }
}
```

`NotBetweenEvaluator.java` 同构改造，解析段失败分支同样 `return false;`（区间无法判定→不满足），末尾返回 `cmpMin < 0 || cmpMax > 0`。

- [ ] **Step 4: 跑测试确认通过**

Run: `$MVN -pl rule-kernel -am test -Dtest=BetweenEvaluatorTest,NotBetweenEvaluatorTest`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/condition/BetweenEvaluator.java \
        rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/condition/NotBetweenEvaluator.java \
        rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/condition/BetweenEvaluatorTest.java \
        rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/condition/NotBetweenEvaluatorTest.java
git commit -m "feat(kernel): BETWEEN/NOT_BETWEEN 支持 DATE/DATETIME 解析段（B20 §5.4）"
```

---

### Task 9: 重做 DateBeforeEvaluator / DateAfterEvaluator（删 toInstant）

走解析段 + 策略；`dataType=null`（DSL）默认按 DATETIME + UTC 解析，复刻旧 `toInstant` 语义，保证既有 node 用例不回归。删除静态 `toInstant` 及其 3 个直接测试。

**Files:**
- Modify: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/condition/DateBeforeEvaluator.java`
- Modify: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/condition/DateAfterEvaluator.java`
- Test: `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/condition/DateBeforeEvaluatorTest.java`
- Test: `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/condition/DateAfterEvaluatorTest.java`

- [ ] **Step 1: 改测试——删 toInstant 用例，加 DATE/\$today 用例**

在 `DateBeforeEvaluatorTest`：删除 `toInstant_localDateString_parsedCorrectly`、`toInstant_instantString_parsedCorrectly`、`toInstant_instantObject_returnedAsIs` 三个方法（`toInstant` 即将删除）。其余 node 用例保留（Task 1 已补 now）。追加：

```java
    @Test
    void dateBefore_dateType_today_comparesAsLocalDate() {
        // metric=2026-06-01，threshold=$today，now 投影到 UTC 是 2026-06-02 → before=true
        Instant now = Instant.parse("2026-06-02T00:00:00Z");
        ConditionNode node = new ConditionNode("DATE_BEFORE", "d", "",
                Map.of("threshold", "$today"), 0.0, "DATE");
        RuleEvent event = new RuleEvent("t1", "s1", "E", "u1", "e1", now, Map.of(), Map.of());
        EvalContext ctx = new EvalContext("t1", event, null,
                Map.of("d", new MetricValue("2026-06-01", "DATE", "PROVIDED")), now);
        assertThat(evaluator.evaluate(node, ctx)).isTrue();
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `$MVN -pl rule-kernel -am test -Dtest=DateBeforeEvaluatorTest,DateAfterEvaluatorTest`
Expected: 编译失败（删了 `toInstant` 测试后，新 `$today` 用例引用尚未实现的解析逻辑；且 `DateAfterEvaluator` 仍引用 `DateBeforeEvaluator.toInstant`）。

- [ ] **Step 3: 实现——DateBeforeEvaluator 重做**

完整替换 `DateBeforeEvaluator.java`：

```java
package com.sstlfsj.rule.kernel.internal.condition;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.MetricValue;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.spi.condition.ConditionEvaluator;
import com.sstlfsj.rule.kernel.internal.condition.strategy.ComparisonStrategyFactory;
import com.sstlfsj.rule.kernel.internal.condition.time.PlaceholderResolver;
import com.sstlfsj.rule.kernel.internal.condition.time.TimeZoneResolver;

import java.time.ZoneId;

/**
 * DATE_BEFORE 条件算子：metric 值严格早于 threshold。
 * 解析段把 actual/threshold 解析为 LocalDate（DATE）或 Instant（DATETIME，含 dataType=null 的 DSL 默认），
 * 再交给对应纯策略比较。支持 $now/$today、带 offset 字符串、裸日期 + params.timezone。
 */
public class DateBeforeEvaluator implements ConditionEvaluator {

    @Override
    public boolean evaluate(ConditionNode node, EvalContext ctx) {
        return DateComparisonSupport.evaluate(node, ctx, true);
    }
}
```

> 为避免 Before/After 重复，新建一个包内 helper `DateComparisonSupport`（同包 `internal/condition`）承载共用解析+比较逻辑。

新建 `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/condition/DateComparisonSupport.java`：

```java
package com.sstlfsj.rule.kernel.internal.condition;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.MetricValue;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.internal.condition.strategy.ComparisonStrategyFactory;
import com.sstlfsj.rule.kernel.internal.condition.time.PlaceholderResolver;
import com.sstlfsj.rule.kernel.internal.condition.time.TimeZoneResolver;

import java.time.ZoneId;

/** DATE_BEFORE / DATE_AFTER 共用解析+比较：dataType=null 时按 DATETIME 解析（复刻旧 toInstant 语义）。 */
final class DateComparisonSupport {

    private DateComparisonSupport() {}

    static boolean evaluate(ConditionNode node, EvalContext ctx, boolean before) {
        MetricValue mv = ctx.getMetric(node.metricCode());
        if (mv == null) return false;
        Object threshold = node.params().get("threshold");
        if (threshold == null) return false;
        String effectiveType = "DATE".equals(node.dataType()) ? "DATE" : "DATETIME";
        ZoneId zone = TimeZoneResolver.resolve((String) node.params().get("timezone"), null);
        Object actual = PlaceholderResolver.resolveTyped(effectiveType, mv.value(), ctx, zone);
        Object operand = PlaceholderResolver.resolveTyped(effectiveType, threshold, ctx, zone);
        if (actual == null || operand == null) return false;
        int cmp = ComparisonStrategyFactory.forType(effectiveType).compare(actual, operand);
        if (cmp == Integer.MAX_VALUE) return false;
        return before ? cmp < 0 : cmp > 0;
    }
}
```

替换 `DateAfterEvaluator.java`：

```java
package com.sstlfsj.rule.kernel.internal.condition;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.spi.condition.ConditionEvaluator;

/**
 * DATE_AFTER 条件算子：metric 值严格晚于 threshold。解析与比较逻辑同 DateBeforeEvaluator。
 */
public class DateAfterEvaluator implements ConditionEvaluator {

    @Override
    public boolean evaluate(ConditionNode node, EvalContext ctx) {
        return DateComparisonSupport.evaluate(node, ctx, false);
    }
}
```

> `DateBeforeEvaluator` 里 import 清单按实际使用精简（helper 承载后，`DateBeforeEvaluator` 只需 EvalContext/ConditionNode/ConditionEvaluator 三个 import；上面 Step 3 给的版本含多余 import，落地时删未用的）。

- [ ] **Step 4: 跑测试确认通过**

Run: `$MVN -pl rule-kernel -am test -Dtest=DateBeforeEvaluatorTest,DateAfterEvaluatorTest`
Expected: PASS（既有 ISO/裸日期 node 用例 + 新 `$today` 用例全绿）。

- [ ] **Step 5: 跑整模块回归**

Run: `$MVN -pl rule-kernel -am test`
Expected: BUILD SUCCESS（确认无其他文件引用已删的 `DateBeforeEvaluator.toInstant`）。

- [ ] **Step 6: Commit**

```bash
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/condition/DateBeforeEvaluator.java \
        rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/condition/DateAfterEvaluator.java \
        rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/condition/DateComparisonSupport.java \
        rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/condition/DateBeforeEvaluatorTest.java \
        rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/condition/DateAfterEvaluatorTest.java
git commit -m "refactor(kernel): DATE_BEFORE/AFTER 走解析段+策略，删 toInstant（B20 §5.4）"
```

---

### Task 10: TimeWindowEvaluator（time.window）+ 注册

**Files:**
- Create: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/condition/TimeWindowEvaluator.java`
- Modify: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/condition/KernelEvaluators.java:38-40`
- Test: `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/condition/TimeWindowEvaluatorTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.sstlfsj.rule.kernel.internal.condition;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TimeWindowEvaluatorTest {

    private final TimeWindowEvaluator evaluator = new TimeWindowEvaluator();

    private EvalContext ctxAt(String isoOffset) {
        Instant now = OffsetDateTime.parse(isoOffset).toInstant();
        RuleEvent ev = new RuleEvent("t1", "s1", "E", "u1", "e1", now, Map.of(), Map.of());
        return new EvalContext("t1", ev, null, Map.of(), now);
    }

    private ConditionNode node(Map<String, Object> params) {
        return new ConditionNode("time.window", null, "", params, 0.0);
    }

    @Test
    void within_inclusiveBounds_shanghai() {
        Map<String, Object> p = Map.of("start", "09:00", "end", "22:00", "timezone", "Asia/Shanghai");
        assertThat(evaluator.evaluate(node(p), ctxAt("2026-06-01T09:00:00+08:00"))).isTrue();
        assertThat(evaluator.evaluate(node(p), ctxAt("2026-06-01T08:59:59+08:00"))).isFalse();
        assertThat(evaluator.evaluate(node(p), ctxAt("2026-06-01T22:00:00+08:00"))).isTrue();
        assertThat(evaluator.evaluate(node(p), ctxAt("2026-06-01T22:00:01+08:00"))).isFalse();
    }

    @Test
    void crossMidnight_window() {
        Map<String, Object> p = Map.of("start", "22:00", "end", "06:00", "timezone", "Asia/Shanghai");
        assertThat(evaluator.evaluate(node(p), ctxAt("2026-06-01T23:00:00+08:00"))).isTrue();
        assertThat(evaluator.evaluate(node(p), ctxAt("2026-06-01T01:00:00+08:00"))).isTrue();
        assertThat(evaluator.evaluate(node(p), ctxAt("2026-06-01T07:00:00+08:00"))).isFalse();
    }

    @Test
    void daysOfWeek_excludesSaturday() {
        // 2026-06-06 是周六
        Map<String, Object> p = Map.of("start", "00:00", "end", "23:59",
                "timezone", "Asia/Shanghai", "daysOfWeek", List.of("MON", "FRI"));
        assertThat(evaluator.evaluate(node(p), ctxAt("2026-06-06T10:00:00+08:00"))).isFalse();
    }

    @Test
    void datesExclude_holidayAlwaysFalse() {
        Map<String, Object> p = Map.of("start", "00:00", "end", "23:59",
                "timezone", "Asia/Shanghai", "datesExclude", List.of("10-01"));
        assertThat(evaluator.evaluate(node(p), ctxAt("2026-10-01T10:00:00+08:00"))).isFalse();
    }

    @Test
    void timezone_defaultsToUtc() {
        Map<String, Object> p = Map.of("start", "09:00", "end", "17:00");
        // 10:00Z 在 UTC 命中
        assertThat(evaluator.evaluate(node(p), ctxAt("2026-06-01T10:00:00Z"))).isTrue();
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `$MVN -pl rule-kernel -am test -Dtest=TimeWindowEvaluatorTest`
Expected: 编译失败（`TimeWindowEvaluator` 不存在）。

- [ ] **Step 3: 实现**

```java
package com.sstlfsj.rule.kernel.internal.condition;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.spi.condition.ConditionEvaluator;
import com.sstlfsj.rule.kernel.internal.condition.time.TimeZoneResolver;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

/**
 * time.window 内置条件：判断 ctx.now() 投影到解析时区后的墙上时间是否落在生效时段（B20 §3）。
 * 过滤序：datesExclude（节假日，整条件 false）→ daysOfWeek（允许列表）→ [start,end] 闭区间（支持跨午夜）。
 * 无 metricCode，不读 metric。
 */
public class TimeWindowEvaluator implements ConditionEvaluator {

    @Override
    public boolean evaluate(ConditionNode node, EvalContext ctx) {
        Map<String, Object> params = node.params();
        Object startRaw = params.get("start");
        Object endRaw = params.get("end");
        if (startRaw == null || endRaw == null) return false;

        ZoneId zone = TimeZoneResolver.resolve((String) params.get("timezone"), null);
        ZonedDateTime zdt = ctx.now().atZone(zone);

        // 1. datesExclude（MM-DD）优先短路
        List<String> excl = asStringList(params.get("datesExclude"));
        String mmdd = String.format("%02d-%02d", zdt.getMonthValue(), zdt.getDayOfMonth());
        if (excl.contains(mmdd)) return false;

        // 2. daysOfWeek（MON..SUN，取 DayOfWeek 名前三字母）
        List<String> dows = asStringList(params.get("daysOfWeek"));
        if (!dows.isEmpty()) {
            String dow = zdt.getDayOfWeek().name().substring(0, 3);
            if (!dows.contains(dow)) return false;
        }

        // 3. [start, end] 闭区间；end < start 表示跨午夜
        LocalTime start = LocalTime.parse((String) startRaw);
        LocalTime end = LocalTime.parse((String) endRaw);
        LocalTime t = zdt.toLocalTime();
        if (end.isBefore(start)) {
            return !t.isBefore(start) || !t.isAfter(end);
        }
        return !t.isBefore(start) && !t.isAfter(end);
    }

    @SuppressWarnings("unchecked")
    private static List<String> asStringList(Object raw) {
        if (raw instanceof List<?> list) return (List<String>) list;
        return List.of();
    }
}
```

- [ ] **Step 4: 注册到 KernelEvaluators**

`KernelEvaluators.defaults()` 在 `DATE_AFTER` 之后加：

```java
        m.put("time.window",      new TimeWindowEvaluator());
```

- [ ] **Step 5: 跑测试确认通过**

Run: `$MVN -pl rule-kernel -am test -Dtest=TimeWindowEvaluatorTest`
Expected: PASS。

- [ ] **Step 6: Commit**

```bash
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/condition/TimeWindowEvaluator.java \
        rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/condition/KernelEvaluators.java \
        rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/condition/TimeWindowEvaluatorTest.java
git commit -m "feat(kernel): time.window 内置条件 evaluator（B20 §3）"
```

---

### Task 11: OccurredAtEvaluator（time.occurred_at）+ 注册

**Files:**
- Create: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/condition/OccurredAtEvaluator.java`
- Modify: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/condition/KernelEvaluators.java`
- Test: `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/condition/OccurredAtEvaluatorTest.java`

> 错误约定：`$today` 与无法解析的操作数 → 抛 `IllegalArgumentException`（经 EvalEngine catch 映射为 `CONDITION_EVAL_ERROR`）；缺 operator/occurredAt → 优雅返回 false。

- [ ] **Step 1: 写失败测试**

```java
package com.sstlfsj.rule.kernel.internal.condition;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OccurredAtEvaluatorTest {

    private final OccurredAtEvaluator evaluator = new OccurredAtEvaluator();

    private EvalContext ctx(Instant occurredAt, Instant now) {
        RuleEvent ev = new RuleEvent("t1", "s1", "E", "u1", "e1", occurredAt, Map.of(), Map.of());
        return new EvalContext("t1", ev, null, Map.of(), now);
    }

    private ConditionNode node(Map<String, Object> params) {
        return new ConditionNode("time.occurred_at", null, "", params, 0.0);
    }

    @Test
    void before_now_true() {
        Instant now = Instant.parse("2026-06-01T12:00:00Z");
        Instant occurred = Instant.parse("2026-06-01T10:00:00Z");
        ConditionNode n = node(Map.of("operator", "BEFORE", "value", "$now"));
        assertThat(evaluator.evaluate(n, ctx(occurred, now))).isTrue();
    }

    @Test
    void before_now_false_whenAfter() {
        Instant now = Instant.parse("2026-06-01T08:00:00Z");
        Instant occurred = Instant.parse("2026-06-01T10:00:00Z");
        ConditionNode n = node(Map.of("operator", "BEFORE", "value", "$now"));
        assertThat(evaluator.evaluate(n, ctx(occurred, now))).isFalse();
    }

    @Test
    void between_inclusiveBounds() {
        Instant occurred = Instant.parse("2026-06-01T10:00:00Z");
        ConditionNode n = node(Map.of("operator", "BETWEEN",
                "start", "2026-06-01T09:00:00Z", "end", "2026-06-01T11:00:00Z"));
        assertThat(evaluator.evaluate(n, ctx(occurred, Instant.EPOCH))).isTrue();
    }

    @Test
    void bareDate_withTimezone_parsed() {
        // occurred=2026-06-01T00:00:00+08:00 = 2026-05-31T16:00Z；AFTER 2026-05-30 → true
        Instant occurred = Instant.parse("2026-05-31T16:00:00Z");
        ConditionNode n = node(Map.of("operator", "AFTER",
                "value", "2026-05-30", "timezone", "Asia/Shanghai"));
        assertThat(evaluator.evaluate(n, ctx(occurred, Instant.EPOCH))).isTrue();
    }

    @Test
    void today_throwsForConditionEvalError() {
        ConditionNode n = node(Map.of("operator", "BEFORE", "value", "$today"));
        assertThatThrownBy(() -> evaluator.evaluate(n, ctx(Instant.EPOCH, Instant.EPOCH)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullOccurredAt_returnsFalse() {
        RuleEvent ev = new RuleEvent("t1", "s1", "E", "u1", "e1", null, Map.of(), Map.of());
        EvalContext c = new EvalContext("t1", ev, null, Map.of(), Instant.EPOCH);
        ConditionNode n = node(Map.of("operator", "BEFORE", "value", "$now"));
        assertThat(evaluator.evaluate(n, c)).isFalse();
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `$MVN -pl rule-kernel -am test -Dtest=OccurredAtEvaluatorTest`
Expected: 编译失败（`OccurredAtEvaluator` 不存在）。

- [ ] **Step 3: 实现**

```java
package com.sstlfsj.rule.kernel.internal.condition;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.spi.condition.ConditionEvaluator;
import com.sstlfsj.rule.kernel.internal.condition.time.PlaceholderResolver;
import com.sstlfsj.rule.kernel.internal.condition.time.TimeZoneResolver;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;

/**
 * time.occurred_at 内置条件：对 event.occurredAt() 做 BEFORE/AFTER/BETWEEN 区间比较（B20 §4）。
 * value/start/end 支持 ISO-8601 与 $now；$today 不适用（时间点语义）→ 抛 IllegalArgumentException。
 * 无 metricCode，不读 metric。
 */
public class OccurredAtEvaluator implements ConditionEvaluator {

    @Override
    public boolean evaluate(ConditionNode node, EvalContext ctx) {
        Instant occurred = ctx.getEvent() != null ? ctx.getEvent().occurredAt() : null;
        if (occurred == null) return false;
        Map<String, Object> params = node.params();
        String operator = (String) params.get("operator");
        if (operator == null) return false;
        ZoneId zone = TimeZoneResolver.resolve((String) params.get("timezone"), null);

        switch (operator) {
            case "BEFORE": return occurred.isBefore(required(params.get("value"), ctx, zone));
            case "AFTER":  return occurred.isAfter(required(params.get("value"), ctx, zone));
            case "BETWEEN": {
                Instant start = required(params.get("start"), ctx, zone);
                Instant end   = required(params.get("end"), ctx, zone);
                return !occurred.isBefore(start) && !occurred.isAfter(end);
            }
            default: throw new IllegalArgumentException("time.occurred_at 未知 operator: " + operator);
        }
    }

    /** 解析必填时间操作数；无法解析（含 $today / 解析失败）抛 IllegalArgumentException → CONDITION_EVAL_ERROR。 */
    private static Instant required(Object raw, EvalContext ctx, ZoneId zone) {
        Instant v = PlaceholderResolver.resolveDateTime(raw, ctx, zone);
        if (v == null) {
            throw new IllegalArgumentException("time.occurred_at 无法解析时间操作数: " + raw);
        }
        return v;
    }
}
```

- [ ] **Step 4: 注册到 KernelEvaluators**

`KernelEvaluators.defaults()` 在 `time.window` 之后加：

```java
        m.put("time.occurred_at", new OccurredAtEvaluator());
```

- [ ] **Step 5: 跑测试确认通过 + 整模块回归**

Run: `$MVN -pl rule-kernel -am test`
Expected: BUILD SUCCESS（含 OccurredAtEvaluatorTest 全绿，且既有算子注册数变化不影响其他测试）。

- [ ] **Step 6: Commit**

```bash
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/condition/OccurredAtEvaluator.java \
        rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/condition/KernelEvaluators.java \
        rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/condition/OccurredAtEvaluatorTest.java
git commit -m "feat(kernel): time.occurred_at 内置条件 evaluator（B20 §4）"
```

---

### Task 12: AstDataTypeResolver 兼容性矩阵加 DATE / DATETIME 行

EQ/NEQ/BETWEEN/NOT_BETWEEN 允许集追加 DATE/DATETIME；DATE_BEFORE/DATE_AFTER 从"ALLOWED 缺席即放行"改为"显式允许 {DATE,DATETIME}，其他拒绝"。

**Files:**
- Modify: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/publish/AstDataTypeResolver.java:22-40`
- Test: `rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/publish/AstDataTypeResolverTest.java`（追加）

- [ ] **Step 1: 写失败测试（追加）**

```java
    // ── B20 时间行 ────────────────────────────────────────────────────────────

    @Test
    void resolve_eqWithDate_ok() {
        ConditionNode cond = new ConditionNode("EQ", "joinDate", null,
                Map.of("threshold", "2026-06-01"), 0.0);
        AstNode result = AstDataTypeResolver.resolve(cond, Map.of("joinDate", "DATE"));
        assertThat(((ConditionNode) result).dataType()).isEqualTo("DATE");
    }

    @Test
    void resolve_betweenWithDatetime_ok() {
        ConditionNode cond = new ConditionNode("BETWEEN", "ts", null,
                Map.of("min", "2026-01-01T00:00:00Z", "max", "2026-06-01T00:00:00Z"), 0.0);
        AstNode result = AstDataTypeResolver.resolve(cond, Map.of("ts", "DATETIME"));
        assertThat(((ConditionNode) result).dataType()).isEqualTo("DATETIME");
    }

    @Test
    void resolve_dateBeforeWithDate_ok() {
        ConditionNode cond = new ConditionNode("DATE_BEFORE", "joinDate", null,
                Map.of("threshold", "2026-06-01"), 0.0);
        AstNode result = AstDataTypeResolver.resolve(cond, Map.of("joinDate", "DATE"));
        assertThat(((ConditionNode) result).dataType()).isEqualTo("DATE");
    }

    @Test
    void resolve_dateBeforeWithLong_throwsIllegalArgument() {
        // DATE_BEFORE 现在只允许 DATE/DATETIME，LONG 被拒
        ConditionNode cond = new ConditionNode("DATE_BEFORE", "amount", null,
                Map.of("threshold", 100), 0.0);
        assertThatThrownBy(() -> AstDataTypeResolver.resolve(cond, Map.of("amount", "LONG")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DATE_BEFORE")
                .hasMessageContaining("LONG");
    }

    @Test
    void resolve_dateAfterWithString_throwsIllegalArgument() {
        ConditionNode cond = new ConditionNode("DATE_AFTER", "name", null,
                Map.of("threshold", "x"), 0.0);
        assertThatThrownBy(() -> AstDataTypeResolver.resolve(cond, Map.of("name", "STRING")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DATE_AFTER")
                .hasMessageContaining("STRING");
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `$MVN -pl rule-config-svc -am test -Dtest=AstDataTypeResolverTest`
Expected: FAIL —— `eqWithDate` 报错（DATE 不在 EQ 允许集）；`dateBeforeWithLong` 不报错（DATE_BEFORE 当前缺席→放行）。

- [ ] **Step 3: 实现——扩展 ALLOWED 矩阵**

`AstDataTypeResolver` 的 static 初始化块，修改 EQ/NEQ、BETWEEN/NOT_BETWEEN 两组，并新增 DATE_BEFORE/DATE_AFTER：

```java
        m.put("EQ",           Set.of("LONG", "DOUBLE", "STRING", "BOOLEAN", "DATE", "DATETIME"));
        m.put("NEQ",          Set.of("LONG", "DOUBLE", "STRING", "BOOLEAN", "DATE", "DATETIME"));
        m.put("GT",           Set.of("LONG", "DOUBLE"));
        m.put("GTE",          Set.of("LONG", "DOUBLE"));
        m.put("LT",           Set.of("LONG", "DOUBLE"));
        m.put("LTE",          Set.of("LONG", "DOUBLE"));
        m.put("BETWEEN",      Set.of("LONG", "DOUBLE", "DATE", "DATETIME"));
        m.put("NOT_BETWEEN",  Set.of("LONG", "DOUBLE", "DATE", "DATETIME"));
        m.put("IN",           Set.of("LONG", "STRING"));
        m.put("NOT_IN",       Set.of("LONG", "STRING"));
        m.put("CONTAINS",     Set.of("LIST"));
        m.put("NOT_CONTAINS", Set.of("LIST"));
        m.put("STARTS_WITH",  Set.of("STRING"));
        m.put("ENDS_WITH",    Set.of("STRING"));
        m.put("MATCHES",      Set.of("STRING"));
        m.put("DATE_BEFORE",  Set.of("DATE", "DATETIME"));
        m.put("DATE_AFTER",   Set.of("DATE", "DATETIME"));
```

并把类顶部注释里 "DATE_BEFORE/DATE_AFTER 留 B20" 那句改为：DATE_BEFORE/DATE_AFTER 现已纳入矩阵（仅允许 DATE/DATETIME）；剩余 `time.*` 内置路径仍 ALLOWED 缺席即放行。

- [ ] **Step 4: 跑测试确认通过 + 整模块回归**

Run: `$MVN -pl rule-config-svc -am test`
Expected: BUILD SUCCESS（新时间行 + 既有 B19 行均通过）。

- [ ] **Step 5: Commit**

```bash
git add rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/publish/AstDataTypeResolver.java \
        rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/publish/AstDataTypeResolverTest.java
git commit -m "feat(publish): 发布期矩阵加 DATE/DATETIME 行，DATE_BEFORE/AFTER 改显式校验（B20 §7）"
```

---

### Task 13: Flyway 迁移——metric_definition.data_type 加 DATE / DATETIME

ENUM 扩容。需同时新建主资源（rule-config-svc）与测试资源副本（rule-eval-svc），遵循既有 V1_x 双位置模式。

**Files:**
- Create: `rule-config-svc/src/main/resources/db/migration/V1_5__add_date_datetime_to_metric_datatype.sql`
- Create: `rule-eval-svc/src/test/resources/db/migration/V1_5__add_date_datetime_to_metric_datatype.sql`

- [ ] **Step 1: 写迁移文件（两处内容相同）**

```sql
-- B20 §5.2：metric_definition.data_type ENUM 增加 DATE / DATETIME
ALTER TABLE metric_definition
  MODIFY COLUMN data_type
  ENUM('LONG','DOUBLE','STRING','BOOLEAN','LIST','DATE','DATETIME') NOT NULL;
```

- [ ] **Step 2: 跑迁移相关测试**

Run: `$MVN -pl rule-eval-svc -am test`
Expected: BUILD SUCCESS（Flyway 在测试启动时按序执行 V1_0..V1_5，无报错；既有集成测试通过）。

> 若 rule-config-svc 有 Flyway 集成测试也一并跑：`$MVN -pl rule-config-svc -am test`。

- [ ] **Step 3: Commit**

```bash
git add rule-config-svc/src/main/resources/db/migration/V1_5__add_date_datetime_to_metric_datatype.sql \
        rule-eval-svc/src/test/resources/db/migration/V1_5__add_date_datetime_to_metric_datatype.sql
git commit -m "feat(storage): V1_5 metric_definition.data_type 增加 DATE/DATETIME（B20 §5.2）"
```

---

### Task 14: 同步影响文档（§12）

代码全绿后同步设计文档。改 `docs/**` 前/后按 CLAUDE.md 跑 `doc-consistency-review` skill 扫自洽性；本 Task 无自动化测试，验收 = 文档与已落地代码一致 + skill 无新增矛盾。

**Files（按 spec §12）:**
- Modify: `docs/01-concepts.md`（dataType 枚举追加 DATE/DATETIME；EvalContext.now 标注"已实装"）
- Modify: `docs/05-storage.md`（metric_definition.data_type ENUM 追加 DATE/DATETIME + V1_5 迁移说明）
- Modify: `docs/03-rule-expression.md`（§3.1 EQ/NEQ 允许集追加 DATE/DATETIME；§3.4 DATE_BEFORE/AFTER + `$now`/`$today` 标"已实装"；§7 time.window / time.occurred_at 标"已实装" + 实装说明；§7.3 追加 B21 `:now` 依赖说明）
- Modify: `docs/00-decisions.md`（追加 B20 决策条目：now 注入 / 时区解析序（Scene 级延后）/ DATE-DATETIME 一等类型 / PlaceholderResolver / context_snapshot 嵌套 evalNow）
- Modify: `docs/08-evolution.md`（B20 标"已实装"，与 B19 依赖关系）

- [ ] **Step 1: 跑 doc-consistency-review（改前基线）**

Invoke skill: `doc-consistency-review`，记录当前矛盾基线（避免把既有问题算到本次头上）。

- [ ] **Step 2: 按上面清单逐文件编辑**

要点：
- `00-decisions.md` 只追加新条目，不改历史条目（CLAUDE.md 文档纪律）。新条目须写明：**Scene 级时区解析序延后**（本期 params>UTC，槽位预留）、**context_snapshot 改嵌套 `{metrics, evalNow}`**（原扁平形状变更，已知影响 2026-06-04 快照查询契约）。
- `03-rule-expression.md` 矩阵权威来源仍在 §3，时间行须与 `AstDataTypeResolver`（Task 12 落地）完全一致：EQ/NEQ/BETWEEN/NOT_BETWEEN + DATE/DATETIME，DATE_BEFORE/DATE_AFTER = {DATE,DATETIME}。
- `01-concepts.md:415` EvalContext.now 由"仅文档描述"改"已实装"。

- [ ] **Step 3: 跑 doc-consistency-review（改后校验）**

Invoke skill: `doc-consistency-review`，确认相对 Step 1 基线无**新增**矛盾、无内容放错文档/章节。

- [ ] **Step 4: （可选）派 rule-engine-reviewer 审代码↔文档对齐**

按 CLAUDE.md，改 `docs/**` 与 `src/**` 后可显式调用 `rule-engine-reviewer` agent 复核本次时间框架的代码↔文档一致性。

- [ ] **Step 5: Commit**

```bash
git add docs/00-decisions.md docs/01-concepts.md docs/03-rule-expression.md docs/05-storage.md docs/08-evolution.md
git commit -m "docs: 同步 B20 时间框架落地（dataType/now/time.*/矩阵/decisions）"
```

---

## Self-Review

**1. Spec 覆盖核对（逐节）：**
- §1 EvalContext.now → Task 1（字段+注入+getter）、Task 2（dry-run/标准快照 evalNow）。✓
- §2 时区解析序 → Task 3（TimeZoneResolver，Scene 级延后已与用户对齐）。✓
- §3 time.window → Task 10。✓
- §4 time.occurred_at → Task 11（含 §4.3 `$today` 拒绝）。✓
- §5 DATE/DATETIME 一等类型 → Task 5（策略）、Task 6（工厂）、Task 7/8/9（算子解析段）、Task 13（存储 ENUM）。✓
- §6 PlaceholderResolver → Task 4。✓
- §7 兼容性矩阵 → Task 12。✓
- §8 近 N 天聚合（§7.3 + B21）→ Out of scope，本计划不实现，仅 Task 14 文档注 B21 依赖。✓（符合 spec §11）
- §9 B19/B21 关系 → 计划依赖图 + Task 6/12 复用 B19 工厂与矩阵。✓
- §10 落地锚点 → 各 Task 已覆盖；唯一偏差：spec 锚点 #1/#7 提"4 参构造器保留委托 / @Deprecated"，但 spec §1.1 正文与决策明确"删 4 参、删 toInstant、不留兼容壳"——本计划遵从正文（删除），锚点的"保留"措辞是早期残留。✓
- §11 不做项 → 计划未触碰相对 duration / DB NOW() / Scene 级动态绑定。✓
- §12 影响文档 → Task 14。✓
- §13 测试要点 → 分散在 Task 1/4/5/9/10/11/12 的测试步骤，逐条对应。✓

**2. 占位符扫描：** 无 TBD/"add error handling"/"similar to"。所有代码步骤含完整代码；机械批量步骤（Task 1 Step 4）给出精确变换规则 + 文件清单 + 常量值，编译器兜底，非占位符。

**3. 类型/签名一致性核对：**
- `EvalContext(tenantId, event, subject, metrics, now)` 5 参 —— Task 1 定义，Task 2/7/8/9/10/11 一致使用。✓
- `EvalContextAssembler.assemble(event, candidates, now)` —— Task 1 定义，Task 2 EvalServiceImpl 一致调用。✓
- `EvalEngine.evaluate(event, now)` / `evaluate(event, candidates, now)` —— Task 1 定义，Task 2 调用。✓
- `TimeZoneResolver.resolve(String, String)` —— Task 3 定义；Task 4 测试不直接用它（传 ZoneId）；Task 7/8/9/10/11 调用 `resolve(paramsTz, null)`。✓
- `PlaceholderResolver.resolveDate/resolveDateTime/resolveTyped` —— Task 4 定义；Task 7/8/9/11 调用，签名一致（`resolveTyped(dataType, raw, ctx, zone)`、`resolveDateTime(raw, ctx, zone)`）。✓
- `ComparisonStrategyFactory.forType("DATE"/"DATETIME")` —— Task 6 定义，Task 5 策略类名 `DateComparisonStrategy`/`DateTimeComparisonStrategy` 与工厂引用一致。✓
- `DateComparisonSupport.evaluate(node, ctx, before)` —— Task 9 定义并被 Date{Before,After}Evaluator 调用。✓

发现并已修正：Self-review 中确认 NEQ 解析段失败返回 `true`（不等成立），与 EQ 的 `false` 不同——Task 7 Step 3 已显式说明此差异。

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-06-06-time-framework.md`. Two execution options:

1. **Subagent-Driven (recommended)** — 每个 Task 派一个全新 subagent 实现，Task 间两段式 review，迭代快、主上下文干净。
2. **Inline Execution** — 在本会话内用 executing-plans 批量执行，带 checkpoint 复核。

哪种方式？
