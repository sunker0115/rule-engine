# Tasks: 规则评估并行求值

> 状态：**已完成**（2026-07-23 实装）。设计见 `design.md`，benchmark 数据见 `EvalEngineBenchmark.java`，演进锚点见 `08-evolution.md` §2.29。

## T1. ExecutionMode + SceneExecutionConfig ✅

- 新建 `rule-kernel/.../api/model/ExecutionMode.java`: `enum { SEQUENTIAL, PARALLEL }` — 已落
- `SceneRuleIndex` 加 `getMode(String tenantId, String sceneCode)` 从 `default_params.executionMode` 读取 — 已落
- 默认 SEQUENTIAL，`"PARALLEL"` 字符串匹配切换
- ~~SceneExecutionConfig record~~ — 未引入独立 record，通过 `SceneRuleIndex.getMode()` 从已有 `defaultParams` 解析，避免新增类型
- 测试: `ExecutionMode.valueOf("SEQUENTIAL")` / 默认值

## T2. ParallelEvaluator ✅

- 新建 `rule-kernel/.../internal/engine/ParallelEvaluator.java`(package-private) — 已落
  - `evaluateAllParallel(List<RuleVersionSnapshot>, EvalContext, executorFn) → EvalResult` — 已落
  - `evaluateFirstHitBatched(List<RuleVersionSnapshot>, EvalContext, executorFn, batchSize) → EvalResult` — 已落
  - `mergeResults(List<Future<EvalResult>>) → EvalResult`: 复用现有 `evaluateAllCandidates` 的汇聚逻辑(hitDecisions 收集/errorCode 合并/aggregatedScore) — 已落
- 测试（T4）: ALL_HITS 并行全命中 / 并行有 error / 空候选 / 并发度=候选数 — 已落

## T3. EvalEngine 并行分支 ✅

- `EvalEngine.evaluate0` 在 Pre-Gate 通过后、求值前加 `if (mode == PARALLEL)` switch 分支 — 已落
- 以 `this::selectExecutor` 作 executorFn，传入 `ParallelEvaluator`
- **实现调整**：`EvalEngine` 构造器不接收 `ExecutionMode`（与 proposal 不同），改为 `evaluate0` 方法参数传入——由上层调用方（`evaluateWithContext`）通过 `SceneRuleIndex.getMode()` 获取

## T4. 全量测试 + benchmark 闸 ✅

- `EvalEngineBenchmark.java`: PARALLEL vs SEQUENTIAL 单场景 5/20/50 规则 — 已落（含 ALL_HITS / FIRST_HIT，provided metric、无 I/O 纯 CPU 基线）
- `EvalEngineParallelTest` (13 tests, rule-kernel): — 已落（2026-07-25）
  - ALL_HITS PARALLEL 5 规则全命中 / 空候选 / 计数并行 = 候选数
  - FIRST_HIT 批式并行：全量一批全跑、选最佳命中；全 miss 返回 miss
  - HIGHEST_PRIORITY PARALLEL：多条命中选最高
  - 异常传播：executor 抛 RuntimeException → errorCode 捕获 + 其余成功结果仍收；executor 返回 errorCode → 合并
  - SEQUENTIAL 回归：ALL_HITS 不变 / FIRST_HIT 短路
  - SceneRuleIndex.defaultParams 集成：PARALLEL 字符串驱动 + 默认 SEQUENTIAL
  - fallback：unregistered kind → AST_BOOLEAN
- 含重脚本/决策图的混合场景 benchmark — 已落（2026-07-25，`EvalEngineMixedBenchmark`）
  - 20 条规则、0/5/10/20 条带模拟计算负载（10k 次算术迭代 ≈ 脚本求值/Flow 遍历）
  - **交叉点在 10/20 重规则：SEQUENTIAL 97,591ns → PARALLEL 75,194ns（1.30x）**
  - 纯轻量（0/20）：992ns → 41,256ns（**42x 负优化**，与 T4 纯 AST baseline 一致）
  - 全重（20/20）：192,486ns → 97,484ns（**1.97x**）
