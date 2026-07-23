# Spec: 规则评估并行求值

## ADDED Requirements

### Requirement: 引擎支持并行执行模式

引擎 **SHALL** 支持 `ExecutionMode` 枚举(SEQUENTIAL/PARALLEL)，模式与 `SceneExecutionStrategy` 正交。PARALLEL 时 ALL_HITS/HIGHEST_PRIORITY 全量并行，FIRST_HIT 批式并行(一批内并行跑，命中即停)。

#### Scenario: ALL_HITS PARALLEL 全量并行求值

- **GIVEN** 场景有 5 条候选规则快照，执行模式为 PARALLEL，策略为 ALL_HITS
- **WHEN** EvalEngine 评估该场景
- **THEN** 5 条规则以 5 条独立 VirtualThread 并发执行
- **AND** 全部完成后收集 hitDecisions / allTraces / score
- **AND** 决策合成结果与同策略 SEQUENTIAL 一致

#### Scenario: FIRST_HIT PARALLEL 批式并行

- **GIVEN** 场景有 10 条候选规则(按 binding priority 降序)，执行模式 PARALLEL，策略 FIRST_HIT
- **WHEN** 第一批(前 10 条)并行执行，其中第 3 高 priority 规则命中
- **THEN** 返回该命中的决策，不跑后续批次

#### Scenario: PARALLEL 下一条规则抛异常

- **GIVEN** 5 条候选规则 PARALLEL 执行，第 3 条 execute() 抛 RuntimeException
- **WHEN** 引擎 fork 全部 5 条后 join
- **THEN** StructuredTaskScope.throwIfFailed() 传播异常
- **AND** ParallelEvaluator 捕获后返回 EvalResult 含 errorCode=CONDITION_EVAL_ERROR

#### Scenario: SEQUENTIAL 行为不变

- **GIVEN** 执行模式为 SEQUENTIAL(默认)
- **WHEN** EvalEngine 评估
- **THEN** 行为与现有串行逻辑完全一致(逐条 for 循环)

### Requirement: EvalContext 并发安全

EvalContext 构造时 `Map.copyOf(metrics)` 防御性拷贝，构造后不可变。PARALLEL 模式下多条规则 **SHALL** 安全共享同一 ctx 引用(只读不写，不加锁)。

#### Scenario: 并发共享 EvalContext

- **GIVEN** 3 条候选规则 PARALLEL 执行，共享同一 EvalContext 实例
- **WHEN** 每条规则读 ctx.metrics() / ctx.event() / ctx.subject()
- **THEN** 所有读操作不抛出 ConcurrentModificationException
- **AND** 各规则 EvalResult 内容互不污染

### Requirement: ParallelEvaluator 汇聚逻辑

`ParallelEvaluator.synthesizeResults` **SHALL** 复用现有 `evaluateAllCandidates` 的汇聚语义:收集全部 hitDecisions + allTraces + 首个 errorCode + max(aggregatedScore)。不新增决策合成逻辑。

#### Scenario: 汇聚多个命中的决策

- **GIVEN** 3 条规则并行执行，2 条各命中一条 Decision
- **WHEN** synthesizeResults 收集
- **THEN** hitDecisions 含 2 条 Decision，按 DECISION_PRECEDENCE 排序
- **AND** finalDecision = 最高 priority 的 Decision
