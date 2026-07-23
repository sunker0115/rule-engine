# Tasks: 规则评估并行求值

## T1. ExecutionMode + SceneExecutionConfig

- 新建 `rule-kernel/.../api/model/ExecutionMode.java`: `enum { SEQUENTIAL, PARALLEL }`
- 新建 `rule-kernel/.../api/model/SceneExecutionConfig.java`: record `ExecutionMode executionMode`(默认 SEQUENTIAL)
- `SceneRuleIndex` 加 `SceneExecutionConfig getConfig(String tenantId, String sceneCode)` default 方法(返回 SEQUENTIAL，子类 override)
- 测试: `ExecutionMode.valueOf("SEQUENTIAL")` / 默认值

## T2. ParallelEvaluator

- 新建 `rule-kernel/.../internal/engine/ParallelEvaluator.java`(package-private)
  - `evaluateAllParallel(List<RuleVersionSnapshot>, EvalContext, executorFn) → EvalResult`
  - `evaluateFirstHitBatched(List<RuleVersionSnapshot>, EvalContext, executorFn, batchSize) → EvalResult`
  - `synthesizeResults(List<EvalResult>) → EvalResult`: 复活现有 `evaluateAllCandidates` 的汇聚逻辑(hitDecisions 收集/errorCode 合并/aggregatedScore)
- 测试: ALL_HITS 并行全命中 / 并行有 error / 空候选 / 并发度=候选数

## T3. EvalEngine 并行分支

- `EvalEngine` 构造器加 `ExecutionMode mode` 参数(默认 SEQUENTIAL)
- `evaluate0` switch 前加 `if (mode == PARALLEL)` 分支
- `EvalEngine` 公共构造器 + `EvalEngineBuilder`(builder 模式)更新

## T4. 全量测试 + benchmark 闸

- `EvalEngineParallelTest`: 5 条规则 ALL_HITS PARALLEL → 全命中
- `EvalEngineParallelTest`: FIRST_HIT 批式并行 → 第一批命中即停
- `EvalEngineParallelTest`: PARALLEL 下一条抛异常 → 其余被取消、errorCode 传播
- `EvalEngineParallelTest`: SEQUENTIAL 行为不变(回归)
- A/B benchmark: `EvalEngineBenchmark.java` 加 PARALLEL vs SEQUENTIAL 单场景 10/50/100 规则
