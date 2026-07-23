# Proposal: 规则评估并行求值

**Change**: `parallel-rule-execution`
**Status**: PROPOSED
**Date**: 2026-07-23

## Why

当前 EvalEngine 对候选规则快照按**串行**遍历逐条执行(`evaluate0` 的 for 循环),即使多条规则之间相互独立且共享同一个已预拉完成的 `EvalContext`。对含多重 `EXPRESSION_SCRIPT` 重算 / `DECISION_FLOW` 图的场景,串行求值构成吞吐瓶颈。

## What

在 `SceneExecutionStrategy` **不变**的前提下,新增正交维度 `ExecutionMode`(SEQUENTIAL / PARALLEL),默认 SEQUENTIAL 保持现有行为。PARALLEL 模式下,EvalEngine 用 `StructuredTaskScope` + VirtualThread 并发执行独立规则,决策合成逻辑不变。

### Goals
- EvalEngine 支持 PARALLEL 执行模式,ALL_HITS/HIGHEST_PRIORITY 全量并行、FIRST_HIT 批式并行(一批 N 条并行跑,取最高 priority 命中)
- 零新外部依赖(StructuredTaskScope 为 JDK 25 标准 API)
- 现有行为零影响(默认 SEQUENTIAL)
- 并发安全: EvalContext 已不可变,无需加锁保护共享状态

### Non-Goals
- 不改变 `RuleVersionExecutor` SPI、`EvalContext`、`DecisionSynthesizer`
- 不改变 `SceneExecutionStrategy` 枚举
- 不接配置层(scene schema / API / UI)——本次只落引擎侧
- 不做并行度调优(ForkJoinPool 大小、parallelism 参数),默认虚拟线程无限并行

## Impact

### 新增
- `kernel api/model/ExecutionMode` enum(SEQUENTIAL / PARALLEL)
- `kernel internal/engine/ParallelEvaluator` package-private 类
- `EvalEngine.evaluate0` 加并行分支 + `SceneRuleIndex` 暴露 `executionMode`

### 修改
- `EvalEngine` 构造器接收 `ExecutionMode`,switch 分支调 `ParallelEvaluator`

### 测试
- `EvalEngineParallelTest`: ALL_HITS 并行/误差聚合/虚拟线程起删/FIRST_HIT 批式并行/SEQUENTIAL 行为不变
