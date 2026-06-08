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
| 1 | `collectTrace` 载体 | **放 `EvalContext`**(与 `now` 同类的逐次执行参数);不动 `RuleVersionExecutor` SPI 签名 |
| 2 | 范围 | **AST_BOOLEAN 合并+`NodeTraceSink`**;**Scorecard 加 `if(collectTrace)` 守卫**;**DecisionTree/Table 不动**(本就 0 trace) |
| 3 | dry-run | **强制 `collectTrace=true`**,无视全局 flag |
| - | 全局来源 | 复用 `engine.rule.trace.enabled`(默认 true);writer 与收集同一开关,语义一致 |

## 4. 架构 / 数据流

```
EvalServiceImpl
  ├─ 普通 evaluate → evalEngine.evaluateWithContext(event, candidates, now)        [collectTrace = traceEnabled]
  └─ dry-run       → evalEngine.evaluateWithContext(event,[snap],strategy,now,true) [collectTrace = true 强制]
        ↓
EvalEngine(持 traceEnabled，构造期由 EvalAutoConfiguration 注入)
  → contextAssembler.assemble(event, passed, now, collectTrace)
        ↓
EvalContext(新增 final boolean collectTrace)
        ↓
executor.execute(snap, ctx)  // SPI 签名不变
  ├─ InterpretedExecutor(合并后):sink = ctx.collectTrace() ? Collecting : Noop；遍历 emit per-node；EvalResult.nodeTrace = sink.collected()
  └─ ScorecardExecutor:if (ctx.collectTrace()) 才建 NodeTrace，否则 List.of()
```

## 5. 组件改造

### 5.1 `EvalContext`(kernel api.model)— 加字段
- 新增 `private final boolean collectTrace;`,构造器加该参数,加 `boolean collectTrace()` 访问器。
- greenfield:直接改构造器签名(不加兼容重载)。波及:`EvalContextAssembler`、构造 EvalContext 的测试桩。

### 5.2 `EvalContextAssembler`(kernel)— 传递
- `assemble(event, passed, now)` → `assemble(event, passed, now, boolean collectTrace)`,把 collectTrace 设进 EvalContext。

### 5.3 `EvalEngine`(kernel)— 决定 collectTrace
- 构造器加 `boolean traceEnabled`(全局默认)。
- 核心 `evaluateWithContext(event, candidates, strategy, now, boolean collectTrace)`:新增 collectTrace 形参,调 `assemble(..., collectTrace)`。
- 便捷 `evaluateWithContext(event, candidates, now)`:resolve strategy 后以 `collectTrace = traceEnabled` 调核心(普通路径)。
- 这是引擎内部方法,非 SPI;唯一调用方 EvalServiceImpl。

### 5.4 `NodeTraceSink`(kernel internal.evaluator)— 新接口 + 两实现
```java
interface NodeTraceSink {
    void accept(NodeTrace trace);
    List<NodeTrace> collected();   // 收集型返回累积；Noop 返回 List.of()
}
```
- `CollectingNodeTraceSink`:内部 ArrayList,accept 追加,collected 返回(不可变视图)。
- `NoopNodeTraceSink`:单例,accept 空操作,collected 返回 `List.of()`——**零分配**。

### 5.5 合并执行器(kernel internal.evaluator)
- `InterpretedExecutor` 吸收 `TracingInterpretedExecutor` 的遍历,改为遍历时对每节点 `sink.accept(buildTrace(...))`;`execute` 开头按 `ctx.collectTrace()` 选 Collecting / Noop sink;`EvalResult.nodeTrace = sink.collected()`。
- **删除 `TracingInterpretedExecutor`**。
- 约束:`collectTrace=true` 时输出的 NodeTrace 必须与旧 `TracingInterpretedExecutor` **逐字段一致**(dry-run 依赖);短路语义(AND/OR/XOR)不变——只 trace 实际求值到的节点。

### 5.6 `ScorecardExecutor`(kernel)— 守卫
- 其建 NodeTrace 处包 `if (ctx.collectTrace())`;false 时 `EvalResult.nodeTrace = List.of()`,score/decision 不变。

### 5.7 `EvalEngine.evaluateAllCandidates` — 无需特判
- 执行器在 off 时返回空 nodeTrace,`allTraces.addAll(empty)` 自然为空,引擎不分支。(分配:空 ArrayList 仍建,可忽略;真正省的是 per-node NodeTrace 对象,在执行器侧已省。)

### 5.8 `EvalAutoConfiguration`(rule-eval-svc)— 装配
- `ruleVersionExecutor` @Bean 改注册合并后的 `InterpretedExecutor`(取代 `TracingInterpretedExecutor`)为 @Primary AST_BOOLEAN。
- `evalEngine` @Bean 注入 `@Value("${engine.rule.trace.enabled:true}") boolean traceEnabled` 传入 EvalEngine 构造器。
- `evalContextAssembler` 不变(assemble 多参由 EvalEngine 调用时传)。

## 6. 错误处理 / 一致性

- **行为等价**:hit/miss、finalDecision、score、errorCode 与现状完全一致;唯一差异是 `collectTrace=false` 时 nodeTrace 为空。
- dry-run 永远 `collectTrace=true` → 所有走 AST_BOOLEAN / Scorecard 的 dry-run 仍出完整 trace。
- DecisionTree/Table:本就空 trace,行为不变(其 dry-run 空 trace 缺口见 §8)。
- 全局 flag 与 TraceWriter 同源:`trace.enabled=false` 时既不收集也不写(Noop writer),不会"收集了没人写"或"想写没收集"。

## 7. 测试

- `NodeTraceSink`:Collecting 累积 + collected 不可变;Noop 空操作 + collected()==List.of()。
- 合并 `InterpretedExecutor`:
  - `collectTrace=true` → nodeTrace 与旧 TracingInterpretedExecutor 断言一致(迁移原 `TracingInterpretedExecutorTest`)。
  - `collectTrace=false` → nodeTrace 空、hit/miss 与 true 时一致(同输入同结果)。
  - 短路:AND 首 false / OR 首 true 时,只 trace 已求值节点(true 模式断言)。
- `ScorecardExecutor`:`collectTrace=false` → 空 trace、score/decision 不变。
- `EvalEngine`:collectTrace 流经;`evaluateWithContext(...,true)` 强制收集;便捷重载用 traceEnabled。
- `EvalContextAssembler`:assemble 设入 collectTrace。
- 端到端(eval-svc):`trace.enabled=false` 下 PULL evaluate 的 EvalResult.nodeTrace 空;**dry-run 仍非空**(强制开的回归)。
- 回归:kernel + eval-svc 全量绿。

## 8. Backlog(本设计外,单列)

- **DecisionTree / DecisionTable 的 dry-run trace 缺失**:两执行器恒返回空 nodeTrace,dry-run 看不到 if 分支/命中行轨迹。这是既有缺口(非本设计引入),方向与本设计相反(补 trace vs 跳 trace)。将来要 dry-run 全 kind trace 时单独设计。

## 9. native / 风险

- 纯 kernel Java,无新反射/序列化点,native 无新增 hints。
- 主要风险:合并执行器后 trace 输出与旧实现不一致 → §7 用迁移自旧 `TracingInterpretedExecutorTest` 的断言兜底。
