# 非 trace 模式跳过 NodeTrace 收集 + 合并解释执行器 设计

> Status: 设计待批准(2026-06-08)。压测 backlog #3 的(b)部分。生产默认 `engine.rule.trace.enabled=false`(查问题才开),但当前 evaluate 热路径**永远用 `TracingInterpretedExecutor` 建 NodeTrace 再被 NoopTraceWriter 丢弃**——纯浪费分配。本设计让非 trace 模式跳过收集,并顺带消除 `InterpretedExecutor` / `TracingInterpretedExecutor` 的遍历重复。greenfield,无生产数据,放手重构。

## 1. 动机

- `engine.rule.trace.enabled` 目前只切 `TraceWriter` 真/Noop(**要不要写库**),**没传进 kernel 引擎**;`TracingInterpretedExecutor` 恒建 NodeTrace。
- 压测 Track E 归因:trace 收集是 eval per-item 分配的一部分;生产 trace-off 是常态,这部分分配纯浪费(建了就被 Noop 写器丢)。
- 同时 `InterpretedExecutor`(非 tracing)与 `TracingInterpretedExecutor` 各实现一遍 AST 遍历(AND/OR/NOT/XOR/Condition),改语义要改两处——重复味道。

## 2. 目标 / 非目标

**目标:**
- 非 trace 模式下 AST_BOOLEAN / Scorecard 评估**不产生 NodeTrace 分配**。
- 合并 AST_BOOLEAN 的两个执行器为一份遍历(消重复)。
- dry-run **无视全局 flag 强制收集 trace**(调试始终可见)。

**非目标:**
- 给 DecisionTree / DecisionTable 补 dry-run trace(它们本就 0 trace,是**既有缺口**,方向相反,单列 backlog,不在本设计内)。
- trace 开关的运行时热切换(维持 `@ConditionalOnProperty` 重启级;investigation 场景翻属性重启即可)。
- 吞吐/p99 的量化验收(无 SLO;本设计是减分配的结构改进,非达标验收)。

## 3. 决策(已批准)

| # | 决策 | 选择 |
|---|---|---|
| 1 | `collectTrace` 载体 | **`ScopedValue<Boolean> COLLECT_TRACE`**(Java 25 结构化 ambient,VT 友好);**不动 `EvalContext`、不动 `RuleVersionExecutor` SPI**;执行器读 `COLLECT_TRACE.orElse(true)` |
| 2 | 范围 | **AST_BOOLEAN 合并成单遍历 + `collectTrace` 守卫**;**Scorecard 加 `if(collectTrace)` 守卫**;**DecisionTree/Table 不动**(本就 0 trace) |
| 3 | dry-run | **强制 `collectTrace=true`**,无视全局 flag |
| - | 全局来源 | 复用 `engine.rule.trace.enabled`(默认 true);writer 与收集同一开关,语义一致 |

> 注:NodeTrace 是树形、bottom-up 构建(AndNode trace 持子节点 trace),flat `NodeTraceSink` 不匹配——合并用"单遍历 + `collectTrace` 守卫住 trace 树构建",off 时不建任何 NodeTrace 节点。

## 4. 架构 / 数据流

```
EvalServiceImpl
  ├─ 普通 evaluate → evalEngine.evaluateWithContext(event, candidates, now)               [普通]
  └─ dry-run       → evalEngine.evaluateWithContext(event,[snap],strategy,now, collectTrace=true) [强制]
        ↓
EvalEngine(持 traceEnabled，构造期由 EvalAutoConfiguration 注入)
  collectTrace = isDryRun ? true : traceEnabled
  ScopedValue.where(COLLECT_TRACE, collectTrace).call(() -> 评估候选...)   // 绑定动态作用域
        ↓
executor.execute(snap, ctx)            // SPI 不变；EvalContext 不变
  ├─ InterpretedExecutor(合并后):COLLECT_TRACE.orElse(true) 决定遍历时建不建 NodeTrace 树；EvalResult.nodeTrace = 空 or 树
  └─ ScorecardExecutor:if (COLLECT_TRACE.orElse(true)) 才建 NodeTrace，否则 List.of()
```

## 5. 组件改造

### 5.1 `TraceScope`(kernel internal.engine 或 evaluator)— 新 holder
```java
public final class TraceScope {
    /** 本次评估是否收集 NodeTrace；未绑定时默认 true(= 现状"始终收集",直调执行器的测试无需感知)。 */
    public static final ScopedValue<Boolean> COLLECT = ScopedValue.newInstance();
    private TraceScope() {}
}
```
- 读取方一律 `TraceScope.COLLECT.orElse(true)`——未绑定(如直调执行器的单测)默认收集,行为同现状。
- **`EvalContext` 完全不动**(无字段、无构造器变更 → 零 churn,60 处构造点不碰);**`RuleVersionExecutor` SPI 不动**。

### 5.2 `EvalEngine`(kernel)— 绑定 ScopedValue
- 构造器加 `boolean traceEnabled`(全局默认,EvalAutoConfiguration 注入)。
- 核心 `evaluateWithContext(event, candidates, strategy, now, boolean collectTrace)`:新增 collectTrace 形参;在调用执行器前用 `ScopedValue.where(TraceScope.COLLECT, collectTrace).call(() -> evaluateAllCandidates/FirstHit(...))` 包住整个候选评估。
- 便捷 `evaluateWithContext(event, candidates, now)`:resolve strategy 后以 `collectTrace = traceEnabled` 调核心(普通路径);dry-run 由 EvalServiceImpl 传 `collectTrace=true`。
- `assemble(...)` **不变**(EvalContext 不带 flag)。

### 5.3 合并执行器(kernel internal.evaluator)— 单遍历 + collectTrace 守卫
- `InterpretedExecutor` 吸收 `TracingInterpretedExecutor` 的遍历为一份;`execute` 读 `boolean collect = TraceScope.COLLECT.orElse(true)`。
- 遍历计算 boolean 结果;**仅当 `collect`** 才构建 NodeTrace 树(传 childTraces 列表向上汇聚,同旧 Tracing 逻辑);`collect=false` 时不建任何 NodeTrace 节点,`EvalResult.nodeTrace = List.of()`。
- **删除 `TracingInterpretedExecutor`**。
- 约束:`collect=true` 时输出的 NodeTrace 必须与旧 `TracingInterpretedExecutor` **逐字段一致**(dry-run 依赖);短路语义(AND/OR/XOR)不变——只 trace 实际求值到的节点。
- 注:NodeTrace 树形 bottom-up 构建,不用 flat sink;用 `if (collect)` 守卫 trace 树构建即可。

### 5.4 `ScorecardExecutor`(kernel)— 守卫
- 其建 NodeTrace 处包 `if (TraceScope.COLLECT.orElse(true))`;false 时 `EvalResult.nodeTrace = List.of()`,score/decision 不变。

### 5.5 `EvalEngine.evaluateAllCandidates` — 无需特判
- 执行器在 off 时返回空 nodeTrace,`allTraces.addAll(empty)` 自然为空,引擎不分支。(真正省的是 per-node NodeTrace 对象,在执行器侧已省。)

### 5.6 `EvalAutoConfiguration`(rule-eval-svc)— 装配
- `ruleVersionExecutor` @Bean 改注册合并后的 `InterpretedExecutor`(取代 `TracingInterpretedExecutor`)为 @Primary AST_BOOLEAN。
- `evalEngine` @Bean 注入 `@Value("${engine.rule.trace.enabled:true}") boolean traceEnabled` 传入 EvalEngine 构造器。
- `evalContextAssembler` 不变。

## 6. 错误处理 / 一致性

- **行为等价**:hit/miss、finalDecision、score、errorCode 与现状完全一致;唯一差异是 `collectTrace=false` 时 nodeTrace 为空。
- dry-run 永远 `collectTrace=true` → 所有走 AST_BOOLEAN / Scorecard 的 dry-run 仍出完整 trace。
- DecisionTree/Table:本就空 trace,行为不变(其 dry-run 空 trace 缺口见 §8)。
- 全局 flag 与 TraceWriter 同源:`trace.enabled=false` 时既不收集也不写(Noop writer),不会"收集了没人写"或"想写没收集"。

## 7. 测试

- 合并 `InterpretedExecutor`(测试用 `ScopedValue.where(TraceScope.COLLECT, x).run(...)` 包住 execute):
  - 绑 true(或不绑,默认 true)→ nodeTrace 与旧 TracingInterpretedExecutor 断言一致(迁移原 `TracingInterpretedExecutorTest`)。
  - 绑 false → nodeTrace 空、hit/miss 与 true 时一致(同输入同结果)。
  - 短路:AND 首 false / OR 首 true 时,只 trace 已求值节点(true 模式断言)。
- `ScorecardExecutor`:绑 false → 空 trace、score/decision 不变。
- `EvalEngine`:`evaluateWithContext(...,collectTrace=false)` → 结果 nodeTrace 空;`...,true` → 非空;便捷重载用 traceEnabled。
- `TraceScope.COLLECT.orElse(true)`:未绑定默认 true(直调执行器的既有测试无需改)。
- 端到端(eval-svc):`trace.enabled=false` 下 PULL evaluate 的 EvalResult.nodeTrace 空;**dry-run 仍非空**(强制开的回归)。
- 回归:kernel + eval-svc 全量绿。

## 8. Backlog(本设计外,单列)

- **DecisionTree / DecisionTable 的 dry-run trace 缺失**:两执行器恒返回空 nodeTrace,dry-run 看不到 if 分支/命中行轨迹。这是既有缺口(非本设计引入),方向与本设计相反(补 trace vs 跳 trace)。将来要 dry-run 全 kind trace 时单独设计。

## 9. native / 风险

- 纯 kernel Java,无新反射/序列化点,native 无新增 hints。`java.lang.ScopedValue` 是 JDK 25 finalized 的核心类、非反射,GraalVM 25 支持——纳入一次 native 冒烟确认(低风险)。
- 主要风险:合并执行器后 trace 输出与旧实现不一致 → §7 用迁移自旧 `TracingInterpretedExecutorTest` 的断言兜底。
- 次要:执行器读 `TraceScope.COLLECT.orElse(true)`,未绑定默认 true——保证直调执行器(不经 EvalEngine 绑定)的场景/测试不丢 trace。
