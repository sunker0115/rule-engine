# 评估流水线归一：EvalEngine 成为唯一编排者，EvalServiceImpl 只消费

## 背景与根因

评估流水线是 `match → pre-gate → assemble → execute`。当前**有两个编排者**：

- `EvalEngine`（`rule-kernel/.../internal/engine/EvalEngine.java`）完整跑这条线，是其本职（"纯计算编排器，无副作用"）。
- `EvalServiceImpl`（`rule-eval-svc/.../internal/service/EvalServiceImpl.java`）为了拿两个中间产物——候选数（写 PENDING session）和 `EvalContext`（写 context_snapshot）——**绕过 engine、直接注入并调用 `SceneRuleIndex` 和 `EvalContextAssembler`，把 match + assemble 又跑了一遍**。

证据：`EvalServiceImpl` 构造器 8 个依赖里，`SceneRuleIndex index`（仅用于 `:94` 的 `index.match`）和 `EvalContextAssembler contextAssembler`（用于 `:86`/`:99` 的 `assemble`）**正是 `EvalEngine` 已持有并编排的内部协作者**（`EvalAutoConfiguration:172` 构造 EvalEngine 时已注入这两者）。

后果：
1. **性能**：`assemble` 每次评估跑两遍 → 每个 metric 的 SQL/HTTP 取数（`EvalContextAssembler.fetchConcurrently`）发两次、`loadSubject` 调两次（仅 `cacheTtlSeconds>0` 的 metric 第二遍被 `MetricCache` 兜住）。
2. **正确性**：service 那次 assemble 用全部 candidates，engine 内部用过完 Pre-Gate 的 passed candidates → 持久化的 `context_snapshot` 与真正驱动评估的上下文可能不一致。

> 这不是调用顺序的小 bug，是**边界画错**：service 不该重新实现 engine 的流水线步骤。

## 目标 / 非目标

**目标**
- `EvalEngine` 成为评估流水线（含 index 访问）的唯一拥有者，对外暴露其自然阶段。
- `EvalServiceImpl` 只做：组合 engine 阶段 + 副作用（session / trace / action）。退掉 `SceneRuleIndex` 与 `EvalContextAssembler` 两个注入，依赖 8 → 6。
- 每次评估只组装一次 `EvalContext`；持久化快照 == 评估所用上下文。
- 不破坏 SDK 本地模式（`RuleEngineClient.evaluate` 走 `EvalEngine.evaluate(event)`）。

**非目标**
- 不动 session 两段式写（PENDING→final）与 trace 同步写——那是 backlog **B14（触发式）**。正因 PENDING 在评估前写、需要候选数，`match` 才作为独立公共阶段保留（廉价内存操作，非重复）。
- 不动策略语义、Pre-Gate、executor 选择、`FIRST_HIT` 每次重排。

## 设计

### 1. 新增聚合返回 record

`rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/EvalOutcome.java`（与 `EvalResult`/`EvalContext` 同包）：

```java
/** 引擎一次评估的聚合输出：结果 + 组装好的上下文（供调用方复用做快照，避免二次取数）。 */
public record EvalOutcome(EvalResult result, EvalContext context) {
}
```

`context` 可为 null：候选为空 / Pre-Gate 全拦截早返回 miss 时未组装 ctx。`updateFinal` / `updateDryRunFinal` 已支持 `ctx==null`。

### 2. EvalEngine 公共 API 收敛

保留 / 新增：
- `EvalResult evaluate(RuleEvent event)` —— SDK 便捷入口，**签名不变**。改为 `return evaluateWithContext(event, match(event), Instant.now()).result();`
- `List<RuleVersionSnapshot> match(RuleEvent event)` —— **新**公共阶段，委托 `index.match(tenantId, sceneCode, eventType)`。
- `EvalOutcome evaluateWithContext(RuleEvent event, List<RuleVersionSnapshot> candidates, Instant now)` —— **新**，PULL 用；内部 `strategy = index.getStrategy(...)` 后委托下一个。
- `EvalOutcome evaluateWithContext(RuleEvent event, List<RuleVersionSnapshot> candidates, SceneExecutionStrategy strategy, Instant now)` —— **核心**（由现私有 `evaluate(event, candidates, strategy, now)` `:64-81` 升为 public 并改返回 `EvalOutcome`）：组装 ctx 一次，按 strategy 执行；早返回处返回 `new EvalOutcome(EvalResult.miss(), null)`，命中/未命中返回 `new EvalOutcome(result, ctx)`。

私有 `evaluateFirstHit` / `evaluateAllCandidates` / `applyPreGates` / `selectExecutor` 不变。

**移除**（greenfield 无兼容包袱，评估后无 prod 调用者）：`EvalResult evaluate(event, now)`（`:47`）、`EvalResult evaluate(event, candidates)`（`:55`）、`EvalResult evaluate(event, candidates, now)`（`:60`）。其测试用法迁移到 `evaluate(event)` / `evaluateWithContext(...)`。

> 命名取 `evaluate` = "只要结果"、`evaluateWithContext` = "结果 + 上下文"，避免同签名重载靠返回类型区分的歧义。

### 3. EvalServiceImpl 退依赖、消费 engine 阶段

- **构造器**：删除 `SceneRuleIndex index`、`EvalContextAssembler contextAssembler` 两个参数与字段；删对应 import（`SceneRuleIndex`、`EvalContextAssembler`）。`SceneExecutionStrategy` 在 `api.model.*` 通配 import 内。
- **PULL**：
  ```
  candidates = evalEngine.match(event);
  if (candidates.isEmpty()) return EvalResult.miss();
  sessionId = sessionWriter.insertPending(event, candidates.size(), "PULL");
  EvalOutcome outcome = evalEngine.evaluateWithContext(event, candidates, evalNow);
  sessionWriter.updateFinal(sessionId, outcome.result(), outcome.context());
  traceWriter.write(event.tenantId(), sessionId.toString(), outcome.result().nodeTrace());
  if (outcome.result().ruleHit()) actionDispatchService.dispatch(..., outcome.result().hitDecisions());
  return outcome.result();
  ```
- **dry-run**：
  ```
  snap = snapshotLoader.loadById(specificVersionId);
  if (snap == null) return EvalResult.miss();
  EvalOutcome outcome = evalEngine.evaluateWithContext(event, List.of(snap), HIGHEST_PRIORITY, evalNow);
  sessionId = sessionWriter.insertDryRunPending(event, specificVersionId);
  sessionWriter.updateDryRunFinal(sessionId, outcome.result(), outcome.context());
  dryRunTraceWriter.write(event.tenantId(), sessionId.toString(), outcome.result().nodeTrace());
  return outcome.result();
  ```

### 装配与影响面

- `EvalAutoConfiguration` 的 `evalEngine` Bean（`:172`）与 `evalContextAssembler` Bean 不变；`SceneRuleIndex`/`EvalContextAssembler` 仍是 Bean（engine 用）。EvalServiceImpl 构造器注入由 Spring 解析剩余 6 个依赖，**无需改 autoconfig**。
- `rule-sdk`：`RuleEngineClient.evaluate`（`:127`）走 `evalEngine.evaluate(event)`，签名不变，**不改**。

### 有意的行为变更

- dry-run 单快照被 Pre-Gate 拦截：旧逻辑仍写含 metrics 的快照，新逻辑写 null 快照（无评估发生）。边缘场景，更正确。
- PULL 快照内容从"全候选 metrics 并集"变为"Pre-Gate 通过后的 metrics 并集"——即一致性修复本身。

## 任务拆分

1. 新增 `EvalOutcome` record + `EvalOutcomeTest`。
2. 改 `EvalEngine`：新增 `match` + 两个 `evaluateWithContext`，`evaluate(event)` 改为薄包装，移除三个旧重载。
3. 改 `EvalEngineTest` / `EvalEngineStrategyTest`：迁移到新 API；补 `match()`、`evaluateWithContext` 返回 ctx（命中/有 passed 非空、空候选/全拦截为 null）的断言。
4. 改 `EvalServiceImpl`：退 `index`/`contextAssembler` 依赖，PULL + dry-run 消费 `outcome`。
5. 改 `EvalServiceImplTest`：去掉两个 `@Mock`，桩改为 `evalEngine.match(...)` + `evalEngine.evaluateWithContext(...)` 返回 `EvalOutcome`。
6. 跑 `EvalIntegrationTest`（真库）确认行为不变。

## 验证

```
$MVN -pl rule-kernel,rule-eval-svc,rule-sdk -am test
```

全绿即完成；`EvalIntegrationTest` 行为不变是关键验收点。