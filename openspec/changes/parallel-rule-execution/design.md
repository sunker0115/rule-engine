# Design: 规则评估并行求值

## 架构决策

### D1. 并行是 ExecutionMode，不是 SceneExecutionStrategy 变体

并行与策略正交。策略决定"哪些命中算赢"(FIRST_HIT / ALL_HITS / HIGHEST_PRIORITY)，并行决定"候选规则怎么跑"(串行/并行)。不增殖 `SceneExecutionStrategy` 枚举，加一个正交的 `ExecutionMode`:

```java
public enum ExecutionMode {
    SEQUENTIAL,   // 逐条串行（现状，默认）
    PARALLEL;     // StructuredTaskScope + VirtualThread 并发
}
```

### D2. StructuredTaskScope + VirtualThread（JDK 25 标准 API）

| 对比项 | parallelStream | VirtualThread + StructuredTaskScope |
|---|---|---|
| 线程池 | ForkJoinPool.commonPool（共享、不可控） | 每次评估自建 VirtualThread，不抢全局池 |
| 超时 | 无 | `joinUntil(5s)` 内置 |
| 错误传播 | 手动 AppException 包装 | `throwIfFailed()` 一个线程出错→全部取消 + 传播 |
| Java 版本 | 一直有 | JDK 25 final API，无需 --enable-preview |
| 依赖 | 零 | 零（JDK 自带） |

### D3. EvalContext 只读 = 零锁

`EvalContext` 构造时 `Map.copyOf(metrics)` 防御性拷贝，构造后不可变。每条规则的 `execute(snap, ctx)` 返回独立 `EvalResult`，无共享可变状态。不需锁、原子操作或并发集合。

### D4. 策略 × 模式 = 四种并行行为

|| SEQUENTIAL（现状）| PARALLEL |
|---|---|---|
| **ALL_HITS / HIGHEST_PRIORITY** | 逐条串行，收集全部命中 | 全量并行 fork，join 后收集结果，同一套 synthesis |
| **FIRST_HIT** | 按 priority 逐个试，命中即停 | **批式并行**：一批 N 条并行跑，取最高 priority 命中；全不中跑下一批。N=场景候选数（默认全量一批） |

### D5. 默认 SEQUENTIAL——现有场景零影响

并行不是替换串行——是可选能力。`ExecutionMode` 默认 `SEQUENTIAL`，新能力 opt-in。本次只落引擎侧，**不接配置层**(scene schema/API/UI 不在范围)。

### D6. 虚拟线程调度开销可忽略

一次评估起 5-20 条虚拟线程 = 堆上 5-20 个 `VirtualThread` 对象分配。瓶颈顺序为:虚拟线程调度 << metric 预拉(已批量 mget) << 脚本求值/决策图遍历(重规则)。

### D7. 轻量规则负优化风险(Open Question，benchmark 闸验证)

虚拟线程的创建+调度开销(~µs)可能 > 轻量 AST_BOOLEAN 规则求值(~ns)。候选规则数少(< 5)或全部为轻量 AST 时，并行可能负优化。

**不在本 change 引入启发式阈值(最小并行候选数 / 纯 AST 判断)**——这些判断需要 benchmark 数据支撑，不应在设计阶段拍。D67 的 benchmark 闸范式在本条照做:PARALLEL 实现后 A/B benchmark 对比 10/50/100 条规则，**纯 AST_BOOLEAN vs 含 EXPRESSION_SCRIPT 混合**两种场景，用数据说话。

**原则**：并行是场景级 opt-in 能力——运营看得到 benchmark 数据后自己判断开不开。引擎不替运营做启发式。

## 实现概要

### 文件清单

| 文件 | 动作 | 说明 |
|---|---|---|
| `rule-kernel/.../api/model/ExecutionMode.java` | 新增 | `enum ExecutionMode { SEQUENTIAL, PARALLEL }` |
| `rule-kernel/.../internal/engine/ParallelEvaluator.java` | 新增 | package-private，fork/join/批式逻辑 |
| `rule-kernel/.../internal/engine/EvalEngine.java` | 修改 | expose `ExecutionMode`，switch 分支 |
| `rule-kernel/.../internal/index/SceneRuleIndex.java` | 修改 | 暴露 `executionMode` |
| `rule-kernel/src/test/.../EvalEngineParallelTest.java` | 新增 | 并行测试 |

### 核心流程(ALL_HITS PARALLEL)

```java
EvalResult r;
if (mode == PARALLEL && strategy != FIRST_HIT) {
    r = ParallelEvaluator.evaluateAllParallel(passed, ctx, this::selectExecutor);
} else if (mode == PARALLEL && strategy == FIRST_HIT) {
    r = ParallelEvaluator.evaluateFirstHitBatched(passed, ctx, this::selectExecutor, passed.size());
} else {
    r = switch (strategy) { /* 现有串行逻辑 */ };
}
```

`ParallelEvaluator.evaluateAllParallel`:

```java
static EvalResult evaluateAllParallel(
        List<RuleVersionSnapshot> passed, EvalContext ctx,
        Function<RuleVersionSnapshot, RuleVersionExecutor> executorFn) {

    List<Supplier<EvalResult>> tasks = new ArrayList<>();
    try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
        for (var snap : passed) {
            tasks.add(scope.fork(() -> executorFn.apply(snap).execute(snap, ctx)));
        }
        scope.join();                                    // 等所有 fork 完成
        scope.throwIfFailed();                           // 任一异常 → 传播
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return EvalResult.error(EvalErrorCode.CONDITION_EVAL_ERROR.name());
    }

    // 收集结果，合成决策（同现有 evaluateAllCandidates 的汇聚逻辑）
    return synthesizeResults(tasks.stream().map(Supplier::get).toList());
}
```

### 批式并行(FIRST_HIT)

```java
static EvalResult evaluateFirstHitBatched(
        List<RuleVersionSnapshot> sorted,  // 已按 priority 降序
        EvalContext ctx,
        Function<RuleVersionSnapshot, RuleVersionExecutor> executorFn,
        int batchSize) {

    for (int i = 0; i < sorted.size(); i += batchSize) {
        var batch = sorted.subList(i, Math.min(i + batchSize, sorted.size()));
        var result = evaluateBatchAndPickBest(batch, ctx, executorFn);
        if (result.ruleHit()) return result;  // 命中即返回，不跑后续批次
    }
    return EvalResult.miss();
}
```

## 与现有设计的联动

| 关联点 | 关系 |
|---|---|
| §2.29 场景内独立规则并行求值 | 本条即 §2.29 的实现 |
| D67 benchmark 闸范式 | 实现后 A/B benchmark 对比串行/并行吞吐 |
| gengine Concurrent/MixModel 参照 | 外显执行模式(gengine 多方法)→本项目收为 ExecutionMode(正交维度) |
| ice P_AND/P_ANY 并行节点 | 节点级并行→本项收为引擎级并行(不内嵌规则树) |
