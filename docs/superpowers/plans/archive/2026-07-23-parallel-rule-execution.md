# 规则评估并行求值 — 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** EvalEngine 支持 StructuredTaskScope + VirtualThread 并发执行候选规则，与 SceneExecutionStrategy 正交，默认 SEQUENTIAL。

**Architecture:** 新增 `ExecutionMode` 枚举(SEQUENTIAL/PARALLEL)正交于 `SceneExecutionStrategy`。`ParallelEvaluator`(package-private)用 `StructuredTaskScope.ShutdownOnFailure` fork/join 虚拟线程，复用现有 `evaluateAllCandidates` 的汇聚逻辑。EvalContext 已不可变，零锁。

**Tech Stack:** JDK 25 StructuredTaskScope(final API) + VirtualThread,零新依赖

## Global Constraints

- 不改 `RuleVersionExecutor` SPI、`EvalContext`、`SceneExecutionStrategy` 枚举
- 不改 `DecisionSynthesizer`(决策合成),`resolveRuleDecisions` 原样复用
- 默认 SEQUENTIAL——现有场景零影响
- 不接配置层(scene schema/API/UI)——本次只落引擎侧
- EvalContext 构造时 `Map.copyOf(metrics)` 已保证不可变,不加锁

## Files

| 文件 | 动作 | 负责 |
|---|---|---|
| `rule-kernel/.../api/model/ExecutionMode.java` | 创建 | 枚举(3 个值) |
| `rule-kernel/.../internal/engine/ParallelEvaluator.java` | 创建 | fork/join + 汇聚 + 批式 |
| `rule-kernel/.../internal/engine/EvalEngine.java` | 修改 | 加 mode 字段 + switch 分支 |
| `rule-kernel/.../internal/index/SceneRuleIndex.java` | 修改 | 加 getMode/setMode |
| `rule-kernel/src/test/.../EvalEngineParallelTest.java` | 创建 | 并行测试 |

---

### Task 1: ExecutionMode 枚举 + SceneRuleIndex

**Files:**
- Create: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/ExecutionMode.java`
- Modify: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/index/SceneRuleIndex.java`

**Interfaces:**
- Produces: `ExecutionMode { SEQUENTIAL, PARALLEL }`; `SceneRuleIndex.getMode(t,s)` / `SceneRuleIndex.setMode(t,s,mode)`

- [ ] **Step 1: 写 ExecutionMode 测试**

```bash
# TDD: 先确认编译通过——枚举本身不需要测行为
```

- [ ] **Step 2: 创建 ExecutionMode.java**

```java
package com.sstlfsj.rule.kernel.api.model;

/**
 * 规则执行模式：决定候选规则如何被评估。
 * 与 {@link SceneExecutionStrategy} 正交——策略管"哪些命中算赢"，模式管"怎么跑"。
 * 默认 {@link #SEQUENTIAL}。
 */
public enum ExecutionMode {
    /** 逐条串行执行（现状，默认）。 */
    SEQUENTIAL,
    /** StructuredTaskScope + VirtualThread 并发执行。 */
    PARALLEL
}
```

- [ ] **Step 3: SceneRuleIndex 加 mode 存取**

```java
// SceneRuleIndex.java 加字段
private final Map<String, ExecutionMode> modes = new ConcurrentHashMap<>();

// 加方法（仿 getStrategy/setStrategy 模式）
public ExecutionMode getMode(String tenantId, String sceneCode) {
    return modes.getOrDefault(tenantId + ":" + sceneCode, ExecutionMode.SEQUENTIAL);
}

public void setMode(String tenantId, String sceneCode, ExecutionMode mode) {
    modes.put(tenantId + ":" + sceneCode, mode);
}
```

- [ ] **Step 4: remove() 也清 modes**

```java
// remove() 方法内加一行:
modes.remove(tenantId + ":" + sceneCode);
```

- [ ] **Step 5: 编译验证**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-25.jdk/Contents/Home
export PATH=$JAVA_HOME/bin:$PATH
MVN=/Users/sunke/.m2/wrapper/dists/apache-maven-3.9.9-bin/4nf9hui3q3djbarqar9g711ggc/apache-maven-3.9.9/bin/mvn
$MVN -pl rule-kernel -am compile -q
```

- [ ] **Step 6: Commit**

```bash
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/ExecutionMode.java \
        rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/index/SceneRuleIndex.java
git commit -m "feat(kernel): ExecutionMode 枚举 + SceneRuleIndex mode 存取"
```

---

### Task 2: ParallelEvaluator

**Files:**
- Create: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/engine/ParallelEvaluator.java`
- Test: `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/engine/EvalEngineParallelTest.java`

**Interfaces:**
- Consumes: `ExecutionMode`(T1), `RuleVersionExecutor` SPI, `EvalContext`, `EvalResult`, `Decision`, `DECISION_PRECEDENCE`(EvalEngine 中的 comparator——需改为 package-private static)
- Produces: `evaluateAllParallel(List<RuleVersionSnapshot>, EvalContext, Function<RuleVersionSnapshot,RuleVersionExecutor>) → EvalResult`; `evaluateFirstHitBatched(List<RuleVersionSnapshot>, EvalContext, Function<RuleVersionSnapshot,RuleVersionExecutor>, int batchSize) → EvalResult`

- [ ] **Step 1: 写失败的测试（TDD）**

```java
// rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/engine/EvalEngineParallelTest.java
package com.sstlfsj.rule.kernel.internal.engine;

import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.kernel.api.model.ast.AndNode;
import com.sstlfsj.rule.kernel.api.spi.executor.RuleVersionExecutor;
import com.sstlfsj.rule.kernel.internal.context.EvalContextAssembler;
import com.sstlfsj.rule.kernel.internal.index.SceneRuleIndex;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EvalEngineParallelTest {

    private static final AndNode EMPTY_AND = new AndNode(List.of(), null, null);

    private static RuleEvent event(String t, String s) {
        return new RuleEvent(t, s, "E", "sub1", "evt-1",
                Instant.now(), Map.of(), Map.of(), EventSource.HTTP);
    }

    private static RuleVersionSnapshot snap(long id, String t, String s, String code, int pri) {
        return new RuleVersionSnapshot(id, s, t, EMPTY_AND, List.of(),
                List.of(new RuleVersionSnapshot.DecisionBinding(code, pri)),
                List.of(), "AST_BOOLEAN");
    }

    /** 返回命中的 executor */
    private static RuleVersionExecutor hit(String code) {
        return (snap, ctx) -> {
            Decision d = new Decision(code, "", 10, snap.ruleVersionId());
            return new EvalResult(true, d, List.of(d), List.of(), null, null, null, null);
        };
    }

    @Test
    void allHits_parallel_5rules_allHit() {
        SceneRuleIndex idx = new SceneRuleIndex();
        idx.setMode("t", "s", ExecutionMode.PARALLEL);
        idx.setStrategy("t", "s", SceneExecutionStrategy.ALL_HITS);
        idx.update("t", "s", "*", List.of(
                snap(1L,"t","s","A",1), snap(2L,"t","s","B",2),
                snap(3L,"t","s","C",3), snap(4L,"t","s","D",4),
                snap(5L,"t","s","E",5)));

        EvalEngine engine = new EvalEngine(idx,
                new EvalContextAssembler(List.of(), List.of()),
                Map.of(),
                Map.of("AST_BOOLEAN", hit("X")),
                ExecutionMode.PARALLEL,     // <<< 新构造器参数
                true);

        EvalResult r = engine.evaluate(event("t","s"));
        assertTrue(r.ruleHit());
        assertEquals(5, r.hitDecisions().size()); // 并行后全部命中
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
$MVN -pl rule-kernel -am test -Dtest='EvalEngineParallelTest' -Dsurefire.failIfNoSpecifiedTests=false
# Expected: 编译失败——ParallelEvaluator 不存在
```

- [ ] **Step 3: 创建 ParallelEvaluator.java**

```java
package com.sstlfsj.rule.kernel.internal.engine;

import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.kernel.api.spi.executor.RuleVersionExecutor;

import java.util.*;
import java.util.concurrent.StructuredTaskScope;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * StructuredTaskScope + VirtualThread 并行评估器。
 * 共享同一 EvalContext(不可变，只读)，零锁。
 * 汇聚逻辑复用 {@link EvalEngine#evaluateAllCandidates} 的语义。
 */
final class ParallelEvaluator {

    private ParallelEvaluator() {}

    /** ALL_HITS / HIGHEST_PRIORITY：全量并行执行，收集全部结果后合成。 */
    static EvalResult evaluateAllParallel(
            List<RuleVersionSnapshot> passed, EvalContext ctx,
            Function<RuleVersionSnapshot, RuleVersionExecutor> executorFn) {

        List<Supplier<EvalResult>> tasks = new ArrayList<>();
        try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
            for (var snap : passed) {
                tasks.add(scope.fork(() -> executorFn.apply(snap).execute(snap, ctx)));
            }
            scope.join();
            scope.throwIfFailed();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new EvalResult(false, null, List.of(), List.of(),
                    EvalErrorCode.CONDITION_EVAL_ERROR.name(), null, null, null);
        }

        return synthesizeResults(tasks.stream().map(Supplier::get).toList());
    }

    /** FIRST_HIT：批式并行——一批 N 条并行跑，取最高 priority 命中者；全不中跑下一批。 */
    static EvalResult evaluateFirstHitBatched(
            List<RuleVersionSnapshot> sorted, EvalContext ctx,
            Function<RuleVersionSnapshot, RuleVersionExecutor> executorFn,
            int batchSize) {
        // sorted 已按 FIRST_HIT_ORDER 排好（EvalEngine 负责排序，此处只消耗）
        for (int i = 0; i < sorted.size(); i += batchSize) {
            var batch = sorted.subList(i, Math.min(i + batchSize, sorted.size()));
            // 一批内并行跑，取最高 priority 命中者
            EvalResult best = evaluateBatchAndPickBest(batch, ctx, executorFn);
            if (best != null && best.ruleHit()) return best;
        }
        return EvalResult.miss();
    }

    /** 一批内并行执行，取最高 priority 命中的结果；全不中返回 null。 */
    private static EvalResult evaluateBatchAndPickBest(
            List<RuleVersionSnapshot> batch, EvalContext ctx,
            Function<RuleVersionSnapshot, RuleVersionExecutor> executorFn) {

        List<Supplier<EvalResult>> tasks = new ArrayList<>();
        try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
            for (var snap : batch) {
                tasks.add(scope.fork(() -> executorFn.apply(snap).execute(snap, ctx)));
            }
            scope.join();
            scope.throwIfFailed();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new EvalResult(false, null, List.of(), List.of(),
                    EvalErrorCode.CONDITION_EVAL_ERROR.name(), null, null, null);
        }

        // 收集命中结果，取 priority 最高者
        return tasks.stream()
                .map(Supplier::get)
                .filter(r -> r.ruleHit() && r.errorCode() == null)
                .max(Comparator.comparingInt(r -> r.finalDecision() != null
                        ? r.finalDecision().priority() : 0))
                .orElse(null);
    }

    /**
     * 汇聚多条规则的并行结果：复用现有 evaluateAllCandidates 的语义——
     * 收集全部 hitDecisions + allTraces + 首个 errorCode + max score。
     */
    private static EvalResult synthesizeResults(List<EvalResult> results) {
        List<Decision> hitDecisions = new ArrayList<>();
        List<NodeTrace> allTraces = new ArrayList<>();
        String errorCode = null;
        Double aggregatedScore = null;

        for (EvalResult r : results) {
            // 单规则抛异常：由 StructuredTaskScope.throwIfFailed() 在前置步骤捕获，
            // 走到这里的都是正常返回的 EvalResult
            allTraces.addAll(r.nodeTrace());
            if (r.ruleHit()) {
                hitDecisions.addAll(r.hitDecisions());
            }
            if (r.errorCode() != null && errorCode == null) errorCode = r.errorCode();
            if (r.score() != null) {
                aggregatedScore = aggregatedScore == null ? r.score()
                        : Math.max(aggregatedScore, r.score());
            }
        }

        Decision finalDecision = hitDecisions.isEmpty() ? null
                : Collections.max(hitDecisions, EvalEngine.decisionPrecedence());

        return new EvalResult(
                !hitDecisions.isEmpty(),
                finalDecision,
                Collections.unmodifiableList(hitDecisions),
                Collections.unmodifiableList(allTraces),
                errorCode,
                aggregatedScore,
                finalDecision != null ? finalDecision.category() : null,
                null
        );
    }
}
```

- [ ] **Step 4: EvalEngine 暴露 DECISION_PRECEDENCE**

`DECISION_PRECEDENCE` 当前是 `private static final`,改为 `static final`(package-private):

```java
// EvalEngine.java L25: private → (package-private)
static final Comparator<Decision> DECISION_PRECEDENCE =
        Comparator.comparingInt(Decision::priority)
                .thenComparing(Decision::fromRuleVersionId,
                        Comparator.nullsFirst(Comparator.naturalOrder()));
```

- [ ] **Step 5: 编译验证**

```bash
$MVN -pl rule-kernel -am compile -q
```

- [ ] **Step 6: Commit**

```bash
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/engine/ParallelEvaluator.java \
        rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/engine/EvalEngine.java \
        rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/engine/EvalEngineParallelTest.java
git commit -m "feat(kernel): ParallelEvaluator — StructuredTaskScope 并行 fork/join + 汇聚"
```

---

### Task 3: EvalEngine 接并行分支

**Files:**
- Modify: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/engine/EvalEngine.java`
- Modify: `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/engine/EvalEngineParallelTest.java`

**Interfaces:**
- Consumes: `ExecutionMode`(T1), `ParallelEvaluator`(T2)
- Produces: 新构造器 `EvalEngine(index, assembler, preGates, executors, mode, traceEnabled)`,旧构造器默认 SEQUENTIAL

- [ ] **Step 1: EvalEngine 加 mode 字段 + 新构造器**

```java
// 加字段
private final ExecutionMode mode;

// 旧构造器保持兼容——默认 SEQUENTIAL
public EvalEngine(SceneRuleIndex index,
                  EvalContextAssembler contextAssembler,
                  Map<String, PreGate> preGates,
                  Map<String, RuleVersionExecutor> executors,
                  boolean traceEnabled) {
    this(index, contextAssembler, preGates, executors, ExecutionMode.SEQUENTIAL, traceEnabled);
}

// 新构造器——接受显式 ExecutionMode
public EvalEngine(SceneRuleIndex index,
                  EvalContextAssembler contextAssembler,
                  Map<String, PreGate> preGates,
                  Map<String, RuleVersionExecutor> executors,
                  ExecutionMode mode,
                  boolean traceEnabled) {
    this.index = index;
    this.contextAssembler = contextAssembler;
    this.preGates = Map.copyOf(preGates);
    this.executors = Map.copyOf(executors);
    this.mode = mode;
    this.traceEnabled = traceEnabled;
}
```

- [ ] **Step 2: evaluate0 加并行分支**

```java
// evaluate0 的 switch(strategy) 前插入 mode 判断:
EvalResult result;
try {
    result = ScopedValue.where(TraceScope.COLLECT, collectTrace).call(() -> {
        if (mode == ExecutionMode.PARALLEL) {
            return switch (strategy) {
                case FIRST_HIT -> {
                    List<RuleVersionSnapshot> sorted = new ArrayList<>(passed);
                    sorted.sort(FIRST_HIT_ORDER);
                    yield ParallelEvaluator.evaluateFirstHitBatched(
                            sorted, ctx, this::selectExecutor, sorted.size());
                }
                case HIGHEST_PRIORITY, ALL_HITS ->
                        ParallelEvaluator.evaluateAllParallel(passed, ctx, this::selectExecutor);
            };
        }
        // 串行路径不变
        return switch (strategy) {
            case FIRST_HIT -> evaluateFirstHit(event, passed, ctx);
            case HIGHEST_PRIORITY, ALL_HITS -> evaluateAllCandidates(passed, ctx);
        };
    });
} catch (RuntimeException e) {
    throw e;
} catch (Exception e) {
    throw new IllegalStateException(e);
}
```

- [ ] **Step 3: evaluateWithContext 也读 index.getMode()**

`evaluateWithContext(event, candidates, now)` 当前从 index 读 strategy,顺便读 mode:

```java
public EvalOutcome evaluateWithContext(RuleEvent event,
                                       List<RuleVersionSnapshot> candidates, Instant now) {
    SceneExecutionStrategy strategy = index.getStrategy(event.tenantId(), event.sceneCode());
    ExecutionMode mode = index.getMode(event.tenantId(), event.sceneCode()); // 新增
    return evaluateWithContext(event, candidates, strategy, mode, now);
}

// 新增重载
public EvalOutcome evaluateWithContext(RuleEvent event, List<RuleVersionSnapshot> candidates,
                                       SceneExecutionStrategy strategy, ExecutionMode mode, Instant now) {
    return evaluateWithContext(event, candidates, strategy, mode, now, traceEnabled);
}

public EvalOutcome evaluateWithContext(RuleEvent event, List<RuleVersionSnapshot> candidates,
                                       SceneExecutionStrategy strategy, ExecutionMode mode, Instant now,
                                       boolean collectTrace) {
    return evaluate0(event, candidates, strategy, mode, now, collectTrace, contextAssembler);
}
```

简化策略——evaluate0 改签收 mode 参数,不从 index 读(重放路径 mode 固定 SEQUENTIAL):

```java
private EvalOutcome evaluate0(RuleEvent event, List<RuleVersionSnapshot> candidates,
                              SceneExecutionStrategy strategy, ExecutionMode mode, Instant now,
                              boolean collectTrace, EvalContextAssembler assembler) {
    // ... pre-gate / context 不变 ...
    
    result = ScopedValue.where(TraceScope.COLLECT, collectTrace).call(() -> {
        if (mode == ExecutionMode.PARALLEL) {
            return switch (strategy) { /* 并行分支 */ };
        }
        return switch (strategy) { /* 串行分支 */ };
    });
}
```

replay 固定 SEQUENTIAL:

```java
// evaluateReplay 中
return evaluate0(replayEvent, candidates, strategy, ExecutionMode.SEQUENTIAL, evalNow, true, noFetch);
```

- [ ] **Step 4: 跑测试确认通过**

```bash
$MVN -pl rule-kernel -am test -Dtest='EvalEngineParallelTest' -Dsurefire.failIfNoSpecifiedTests=false
```

预期: 并行测试通过 + 旧测试不受影响

- [ ] **Step 5: Commit**

```bash
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/engine/EvalEngine.java
git commit -m "feat(kernel): EvalEngine 接 ExecutionMode + 并行分支"
```

---

### Task 4: 全量测试 + 全模块验证

- [ ] **Step 1: 补异常传播 + 批式测试**

在 `EvalEngineParallelTest.java` 追加：

```java
/** 总是抛异常的 executor */
private static RuleVersionExecutor errorExecutor() {
    return (snap, ctx) -> { throw new RuntimeException("boom"); };
}

@Test
void parallel_errorPropagation_returnsErrorCode() {
    SceneRuleIndex idx = new SceneRuleIndex();
    idx.setMode("t", "s", ExecutionMode.PARALLEL);
    idx.setStrategy("t", "s", SceneExecutionStrategy.ALL_HITS);
    idx.update("t", "s", "*", List.of(
            snap(1L,"t","s","A",1),
            snap(2L,"t","s","B",2)));

    EvalEngine engine = new EvalEngine(idx,
            new EvalContextAssembler(List.of(), List.of()),
            Map.of(),
            Map.of("AST_BOOLEAN", errorExecutor()),
            ExecutionMode.PARALLEL, true);

    EvalResult r = engine.evaluate(event("t","s"));
    // StructuredTaskScope 会传播异常 → evaluate0 捕获 → CONDITION_EVAL_ERROR
    assertFalse(r.ruleHit());
    assertEquals(EvalErrorCode.CONDITION_EVAL_ERROR.name(), r.errorCode());
}

@Test
void parallel_firstHit_batchPicksHighestPriority() {
    SceneRuleIndex idx = new SceneRuleIndex();
    idx.setMode("t", "s", ExecutionMode.PARALLEL);
    idx.setStrategy("t", "s", SceneExecutionStrategy.FIRST_HIT);
    idx.update("t", "s", "*", List.of(
            snap(1L,"t","s","LOW",5),
            snap(2L,"t","s","HIGH",20)));

    EvalEngine engine = new EvalEngine(idx,
            new EvalContextAssembler(List.of(), List.of()),
            Map.of(),
            Map.of("AST_BOOLEAN", hit("X")),
            ExecutionMode.PARALLEL, true);

    EvalResult r = engine.evaluate(event("t","s"));
    assertTrue(r.ruleHit());
    // 两条都命中，取最高 priority 的 binding 决策
    assertEquals("HIGH", r.finalDecision().code());
}

@Test
void sequential_default_unchanged() {
    SceneRuleIndex idx = new SceneRuleIndex(); // 默认 SEQUENTIAL
    idx.setStrategy("t", "s", SceneExecutionStrategy.ALL_HITS);
    idx.update("t", "s", "*", List.of(
            snap(1L,"t","s","A",1), snap(2L,"t","s","B",2)));

    EvalEngine engine = new EvalEngine(idx,
            new EvalContextAssembler(List.of(), List.of()),
            Map.of(),
            Map.of("AST_BOOLEAN", hit("X")),
            true); // 旧构造器 → 默认 SEQUENTIAL

    EvalResult r = engine.evaluate(event("t","s"));
    assertTrue(r.ruleHit());
    assertEquals(2, r.hitDecisions().size());
}
```

- [ ] **Step 2: 跑 kernel 全量测试**

```bash
$MVN -pl rule-kernel -am test -Dsurefire.failIfNoSpecifiedTests=false
```

- [ ] **Step 3: 确保 EvalEngine 旧测试不受影响（构造函数兼容）**

旧测试全用 5 参构造器，对应 SEQUENTIAL 模式——应全绿。

- [ ] **Step 4: 全量 clean test**

```bash
$MVN clean test -Dtest='!ScheduledTaskAnnotationIntegrationTest' -Dsurefire.failIfNoSpecifiedTests=false
```

- [ ] **Step 5: Commit**

```bash
git add rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/engine/EvalEngineParallelTest.java
git commit -m "test(kernel): 补并行异常传播/批式/FIRST_HIT + 全量回归通过"
```

---

### Task 5: 更新文档（08-evolution + 台账）

- [ ] **Step 1: 08-evolution §2.29 标记已实现**

```bash
# 编辑 docs/08-evolution.md，§2.29 加一行：
# **已实现（2026-07-23）**：`ExecutionMode.PARALLEL` + `ParallelEvaluator`（StructuredTaskScope + VirtualThread）。
# OpenSpec change parallel-rule-execution。
```

- [ ] **Step 2: 台账 §3.1 + §3.2.4 同步**

```bash
# reference-projects.md: gengine 并行求值从「待定」→「已吸收」
# 落点改为: ExecutionMode + ParallelEvaluator + EvalEngine 并行分支
```

- [ ] **Step 3: Commit**

```bash
git add docs/08-evolution.md docs/reference-projects.md
git commit -m "docs: §2.29 并行求值已实现 + 台账同步"
```
