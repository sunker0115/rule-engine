# 02 — 运行时全链路

> **位置定位**：本文档承载"一个 RuleEvent 进来到决策产出"的**全链路时序**——Trigger 接入 / Matcher 检索 / Pre-Gate 拦截 / EvalContext 装配 / AST 评估 / 决策合成各阶段衔接。
>
> **前置阅读**：[`README.md`](./README.md)、[`01-concepts.md`](./01-concepts.md)、[`00-decisions.md`](./00-decisions.md) D6 / D17 / D20 / D21 / D60
>
> **解决什么疑问**："事件进来后引擎内部依次发生了什么？""evaluation_session 在哪一步开始 / 结束？""metric 在哪一步预拉？决策在哪一步合成？"
>
> **职责边界**——
> - ✅ 阶段时序 / 各阶段输入输出契约 / evaluation_session 生命周期 / 失败语义聚合
> - ❌ 不写 AST 节点字段（→ 03-rule-expression）、不写扩展接口签名（→ 04-extension）、不写表结构（→ 05-storage）、不写运维参数（→ 07-operability）、不写决策权衡（→ 00-decisions）

---

## 一、文档状态

| 章节 | 状态 |
|------|------|
| §二 整体时序 | ✅ 已展开 |
| §三 各阶段细节 | ✅ 已展开（Trigger / Matcher / Pre-Gate / EvalContext / Evaluator / 决策合成） |
| §四 evaluation_session 生命周期 | ✅ 已展开 |
| §五 失败语义聚合 | ✅ 已展开（汇总 D15 单节点 / 单规则 失败的传播规则） |

---

## 二、整体时序

```
RuleEvent
  │
  ▼
① Trigger 接入层
  │  · HTTP /api/v1/rule/event（PUSH）或 /api/v1/rule/evaluate（PULL）
  │  · MQ Consumer（Job Trigger 批量合成的 RuleEvent）
  │  · 校验 payloadSchema + eventType 注册
  ▼
② Matcher 路由
  │  · (tenantId, sceneCode, eventType) → 倒排索引
  │  · 命中候选 RuleVersion 列表（快照锁定）
  │  · 无候选：直接返回 EvalResult{satisfied=false}（API 字段名 ruleHit）
  ▼
③ Pre-Gate 拦截
  │  · 评估 Pre-Gate（v1 仅 ROLLOUT；未注册 gateType → fail-closed 拦截）
  │  · 任一 Gate 不通过：blocked_by 记录 Gate 类型，跳过后续阶段
  │  · 全通过：继续
  ▼
④ EvalContext 构建
  │  · 扫 AST 收集涉及的 metricCode（集合并集）
  │  · payload 字段直接注入（valueRef=PAYLOAD）；metric 按 provided（SDK/Job 注入）优先 → 其余按 sourceType 并发取数（D25/D55）
  │  · 组装 EvalContext（不可变 POJO）；此阶段不写 evaluation_session
  ▼
⑤ 规则判定主体评估（按 kind 路由执行器）
  │  · AST_BOOLEAN/SCORECARD/DECISION_TREE/DECISION_TABLE → InterpretedExecutor（Visitor 树遍历）
  │  · EXPRESSION_SCRIPT → ScriptExecutor（六引擎表达式求值，见 04-extension §三）
  │  · DECISION_FLOW → FlowExecutor（DAG 图遍历：RuleRef/Switch/Transform/Output，见 §3.5b）
  │  · 按 Scene.executionMode 决定候选规则执行方式（见 §3.5a）：
  │    SEQUENTIAL（默认）：逐条串行
  │    PARALLEL：ParallelEvaluator 并发（VirtualThread，ALL_HITS/HIGHEST_PRIORITY 全量并行 / FIRST_HIT 批式并行）
  │  · 每个节点评估结果收集到 TraceCollector（内存 ArrayList，无锁）
  │  · 单节点失败：节点 satisfied=false，整树继续短路求值（D15）
  │  · 评估结束：EvalResult 出树，node_trace 批量 submit 入 TraceWriter 队列（异步，D21）
  ▼
⑥ 决策合成（decisionStrategy）
  · 命中规则绑定的 Decision 经 decisionStrategy 合成 finalDecision（D26/D29）
  · PULL：EvalResult{finalDecision, hitDecisions, ...} 同步返回调用方
  · PUSH/HYBRID：异步评估完成后发布审计事件，不派发动作（D60）
  · "命中后做什么"归消费方 / 流程引擎
```

**关键约束**：

| 约束 | 说明 |
|------|------|
| 阶段①→③ 串行 | Trigger 校验 → Matcher 路由 → Pre-Gate 拦截依次串行，快速短路 |
| 阶段④内部并发 | Subject 加载与 metric 批拉 `CompletableFuture.allOf()` 并行（D25） |
| 引擎纯决策化 | 引擎只产出决策，不派发动作（D60）；"命中后做什么"归消费方 / 流程引擎 |
| `evaluation_session` 异步批写 | 评估完成后发布事件并非阻塞入队，消费者插入终态；失败可丢，不阻塞评估响应 |
| `node_trace` 异步批写 | 阶段⑤结束时入 TraceWriter 队列，旁路观察通道，失败降级丢弃，不影响热路径（D21） |

**同步/异步边界**：

```
评估线程：Trigger → Matcher → Pre-Gate → EvalContext → 求值 → 决策合成
                                                           │
                                                发布 AuditRecordedEvent
                                                           │
                                  AuditPersister 非阻塞入队 → 返回 EvalResult
                                                           │
异步消费者：终态 evaluation_session 批写 → node_trace 旁路入队 → 异步批写
```

PUSH 入口先返回受理结果，后续由评估线程执行同一计算与审计事件发布流程。审计和 trace 均为 best-effort，不保证在响应返回前落库；dry-run 在单版本分支直接返回，不发布生产审计事件。

---

## 三、各阶段细节

### 3.1 Trigger 接入层

**输入**：HTTP 请求体 / MQ 消息

**输出**：`RuleEvent { tenantId, scene, eventType, subjectId, eventId, occurredAt, payload }`（内部 POJO 字段名；API 层 `sceneCode` 映射到此处的 `scene`）

**核心动作**：
- 解析请求体，反序列化为 RuleEvent POJO；
- `eventId` 为空时引擎生成 UUID v4；
- 校验 `eventType` ∈ `Scene.eventTypes` 白名单，不在则返回 400 `INVALID_EVENT_TYPE`；
- 校验 `payload` 字段符合 `Scene.payloadSchema`（字段名 + 基础类型），不符则返回 400 `PAYLOAD_SCHEMA_MISMATCH`；
- 当前入口不做 Redis 请求去重或结果缓存；数据库唯一键仅约束异步审计记录（见 §四）。

**PUSH vs PULL vs dry-run 入口对比**：

| 入口 | 模式 | 同步等待结果 | 返回内容 |
|------|------|------------|---------|
| `POST /api/v1/rule/event` | PUSH | 否 | `{ eventId, accepted: true }` |
| `POST /api/v1/rule/evaluate` | PULL | 是 | `EvalResult { ... }` |
| `POST /api/v1/rule/dry-run` | PULL（试算） | 是 | `EvalResult + nodeTrace` |

> **dry-run 试跑目标二选一必传**（D56）：`ruleVersionId`（精确版本）/ `ruleId`（取最新版本，含 DRAFT）二选一，都不传 → 400 `MISSING_DRYRUN_TARGET`。dry-run 结构上恒走"带版本单快照"分支，**不写 `evaluation_session` 或任何专用历史表**，结果和节点 trace 只随响应返回。premise A 下草稿即冻结快照，故 dry-run 试跑的 DRAFT 与发布后内容一致。

**异常语义**：
- 400 系列：schema 校验失败，不进入评估链路；
- 事件接入失败（MQ 反序列化异常）：消息不 ack，由 MQ 重投，引擎不进入评估链路。

### 3.2 Matcher 路由

**输入**：`RuleEvent`

**输出**：候选 `RuleVersion` 快照列表

**核心动作**：
- 按 `(tenantId, sceneCode, eventType)` 三元组查内存倒排索引（`ConcurrentHashMap`）；
- 倒排索引 value = `List<RuleVersion>`（仅含 `PUBLISHED` 状态规则的当前版本快照，`DISABLED` 规则已从索引中剔除）；
- 每个 `RuleVersion` 快照包含：判定主体多态载体 `body`（`RuleBody`：AstBody 内完整预解析 AST 树 / ScriptBody / FlowBody，D76）+ `decision_bindings`（含 Decision 的 code / name / priority）+ `pre_gates`（含 ROLLOUT 灰度）+ `metric_dependencies` + `triggerEventTypes`；
- 倒排索引分桶规则：`RuleVersion.triggerEventTypes` 为空时归入通配桶（key = `"*"`），非空时按实际值逐一建桶；查询时取精确桶与通配桶的并集（精确匹配优先，去重）；
- 索引在规则发布/禁用时增量热更（D17）：单服务模式由 Modulith `RulePublishedEvent` 触发（近实时）；嵌入式 SDK 模式由 `DbPollingRuleWatcher` 轮询触发（15s 最终一致）；Scene 变更同理（D24，单服务 `SceneChangedEvent` / SDK 模式 `DbPollingSceneWatcher` 30s）。

**异常语义**：
- 无候选（索引查不到匹配 RuleVersion）→ 直接返回 `EvalResult { satisfied=false }`（API 字段名 `ruleHit`），不写 `evaluation_session`，不进入后续阶段。同一 eventId 再次请求仍会执行候选匹配。

### 3.3 Pre-Gate 拦截

**输入**：候选 `RuleVersion` 快照列表 + `RuleEvent`

**输出**：通过 Pre-Gate 的 `RuleVersion` 列表；或产生 BLOCKED 审计事件材料并返回 `EvalResult { satisfied=false }`（API 字段名 `ruleHit`）

**核心动作**：
- 对每条候选 RuleVersion，串行评估 `pre_gates` 中声明的 Gate（v1 仅 `ROLLOUT`）；
- 任一 Gate 不通过 → 该 RuleVersion **跳过 AST 评估**，trace 落 `node_trace`（节点类型 `PRE_GATE_BLOCKED`，走同一 `TraceWriter`，D21）；
- **未注册的 gateType → fail-closed 拦截**（D52，运行期兜底；发布期已拒绝非 ROLLOUT 配置）。

**Gate 类型与通过条件**（Pre-Gate 收敛为仅 ROLLOUT，D52；黑白名单改走 BOOLEAN metric + condition，RATE_LIMIT/MUTEX 已移除）：

| Gate 类型 | 通过条件 | `blocked_by` 值 |
|-----------|---------|----------------|
| `ROLLOUT` | `hash(subjectId, experimentId ?? ruleVersionId) % 100` 落入命中区（percentage 或桶区间） | `ROLLOUT` |

**结果语义**：
- 某条 RuleVersion 被任一 Gate 拦截 → 该 RuleVersion 不进入 EvalContext 构建与 AST 评估；
- 若**全部**候选 RuleVersion 均被 Pre-Gate 拦截 → 评估结束后异步写 `evaluation_session { status=BLOCKED, blocked_by=<首个拦截 Gate 类型> }`，返回 `EvalResult { satisfied=false }`；
- BLOCKED 对账不计入命中率分母（D22）。

**Gate 内部异常**（如 Redis 频次计数器超时）：默认 fail-closed——失败视为"未通过该 Gate"，宁可漏发不可错发；具体各 Gate 的 fail-open/closed 默认值由各实现声明（→ 04-extension）。

### 3.4 EvalContext 构建

**输入**：通过 Pre-Gate 的 `RuleVersion` 快照列表 + `RuleEvent`

**输出**：不可变 `EvalContext`；审计在完整评估结束后异步写入。

**核心动作（4 步）**（B21 已实装：`EvalContextAssembler` 接线 provided 优先 → 查缓存 → 按 `sourceType` 并发 fetch → 失败降级 `METRIC_FETCH_FAIL`；并注入 payload 字段）：

1. **收集 metricCode**：扫每条候选 RuleVersion 的 `metric_dependencies`，取并集；
2. **payload 注入 + metric provided 优先**：payload 字段（`valueRef=PAYLOAD`）由 `EvalContextAssembler` 直接注入值 map（`ValueSource.PAYLOAD`，`putIfAbsent` 让同名 metric/provided 优先）；metric 的 `providedMetrics` 来自 SDK/Job 非公开注入（**公开评估请求体已删该字段，D55**；内部 `RuleEvent` 仍持有），有值且 `allowProvided=true` 则直接用，跳过 sourceType 取数；
3. **并发取数**（D25）：Subject 加载（`SubjectLoader.load()`）与剩余 metric 批拉（各 `MetricSource` 自管连接池/HTTP client）并行启动，`CompletableFuture.allOf()` 等待全部完成；
4. **组装 EvalContext**：将 Subject + metrics + RuleEvent + `now`（评估开始时间）+ traceId 封装为不可变 POJO；

**EvalContext 标准字段**（v1 闭合枚举，发布期引用闭合校验根路径，D20 §3）：

| 引用路径 | 类型 | 来源 |
|----------|------|------|
| `now` | Instant | 引擎注入 |
| `tenantId` | String | RuleEvent |
| `scene` | String | RuleEvent |
| `eventType` | String | RuleEvent |
| `occurredAt` | Instant | RuleEvent |
| `subjectId` | String | RuleEvent |
| `ruleVersionId` | Long | Matcher 锁定 |

此外：`payload.*`（来自 `RuleEvent.payload`，字段受 `Scene.payloadSchema` 约束）和 `metrics.*`（来自批量预拉结果）均可在 ConditionNode 中引用。

**取数失败语义**（D15）：
- Subject 加载失败（主体不存在 / 超时）→ 整个 Context 构建失败，该 Rule 落 `EvalResult.errorCode = METRIC_FETCH_FAIL`；
- 单 metric 加载失败 → 该 metric 标记 `FETCH_FAIL`；引用该 metric 的 ConditionNode 在 AST 评估期 evaluated=false；整树继续短路求值（D15）；
- 全体等待超时（`allOf().join(timeout)`）→ 已完成的 metric 有效，未完成的视同失败，超时阈值在 07-operability 给。

### 3.5 规则判定主体评估（按 kind 路由执行器）

**输入**：`EvalContext` + 候选 `RuleVersionSnapshot`（含 `body` JSON → `RuleBody` 多态：`AstBody`/`ScriptBody`/`FlowBody`，D76）

**输出**：`EvalResult` + node_trace batch（入 TraceWriter 队列，异步）

**核心动作**：
- `EvalEngine.selectExecutor(snap)` 按 `snap.kind()` 路由到对应 `RuleVersionExecutor` 实现；
- 每种执行器的判定主体不同，但返回统一的 `EvalResult` + trace；
- 评估结束后 `node_trace` 批量 submit 入 TraceWriter 队列（非阻塞 offer，队满丢弃+告警，D21）。

**六种 kind 与执行器对应**：

| kind | 判定主体（RuleBody 变体） | 执行器 | 求值方式 |
|------|--------------------------|--------|---------|
| `AST_BOOLEAN` | `AstBody(conditionAst)` | `InterpretedExecutor` | Visitor 递归遍历 AST 树 |
| `SCORECARD` | `AstBody(conditionAst)` | `InterpretedExecutor` | Visitor + weight 加权评分 |
| `DECISION_TREE` | `AstBody(conditionAst)` | `InterpretedExecutor` | Visitor + 分支路径选择 |
| `DECISION_TABLE` | `AstBody(conditionAst)` | `InterpretedExecutor` | Visitor + 行列匹配 |
| `EXPRESSION_SCRIPT` | `ScriptBody(script)` | `ScriptExecutor` | 六引擎表达式求值（见 04-extension §三） |
| `DECISION_FLOW` | `FlowBody(flowGraph, referencedSnapshots)` | `FlowExecutor` | DAG 图遍历（见 §3.5b） |

**AST 系四种 kind 的节点求值语义**（`InterpretedExecutor`）：

| 节点 | 求值逻辑 | 短路行为 |
|------|---------|---------|
| `AndNode` | 全部子节点 true | 首个 false 即短路，剩余跳过 |
| `OrNode` | 任一子节点 true | 首个 true 即短路，剩余跳过 |
| `NotNode` | 取反唯一子节点 | 无短路 |
| `ConditionNode` | 调用对应 `ConditionEvaluator.evaluate()` | 失败时 satisfied=false，整树继续（D15） |

**dry-run 模式**：恒走"带版本单快照"分支（按 `ruleVersionId` / `ruleId` 解析出的单个版本试跑），**结构上不进生产 session 持久化链路**（D56）。结果和节点 trace 直接返回，不写 `evaluation_session`、`node_trace` 或专用 dry-run 表。

**EvalResult 输出字段**（D12 多态，六种 kind 按需填）：

```
EvalResult {
    satisfied:       boolean           // AST_BOOLEAN：整树求值结果
    score?:          Number            // SCORECARD：加权评分
    category?:       String            // DECISION_TREE：分支类别
    decision?:       Map<String,Any>   // DECISION_TABLE：匹配行输出
    finalDecision?:  DecisionRef       // D26：合成后最终 Decision
    hitDecisions:    List<DecisionRef> // D26：所有命中规则的 Decision
    trace:           List<NodeTrace>   // 节点级 trace
    errorCode?:      String            // D15：非空表示有节点失败
    errorMessage?:   String
    failedNodeIds?:  List<String>
    partial?:        Boolean
}
```

### 3.5a 候选规则执行模式（ExecutionMode）

候选规则（经 Matcher + Pre-Gate 后）在评估阶段的执行方式由 Scene 级 `executionMode` 控制，与 `decisionStrategy` 正交：

| 模式 | 行为 | 适用 |
|------|------|------|
| `SEQUENTIAL`（默认） | 逐条串行执行候选规则，与 v1 行为一致 | 所有场景 |
| `PARALLEL` | `ParallelEvaluator` 用 `Executors.newVirtualThreadPerTaskExecutor()` 并发执行，零新依赖（JDK 25） | 含多重脚本/决策图场景 |

**PARALLEL 模式下的两种策略**：

| 决策策略 | 并行行为 |
|---------|---------|
| `ALL_HITS` / `HIGHEST_PRIORITY` | 全量候选规则并行 fork，join 后 `mergeResults` 收集全部 hitDecisions + 首个 errorCode + max score |
| `FIRST_HIT` | 批式并行：候选按 priority 排序后一批 N 条（默认全部候选）并行跑，取最高 priority 命中；全不中跑下一批 |

**并发安全**：`EvalContext` 不可变（防御性拷贝），各规则返回独立 `EvalResult`，零共享可变状态，无需加锁。一条规则抛异常时 errorCode 被捕获，其余规则结果仍正常收集。

**适用面（重要）**：纯 AST_BOOLEAN 场景 PARALLEL 负优化 ~42x（虚拟线程调度开销 > 纳秒级求值）。仅在场景含多条重规则（`EXPRESSION_SCRIPT` / `DECISION_FLOW`）且候选数 ≥ 3 时建议开启。配置经 `scene.default_params.executionMode` 热更生效。详细 benchmark 见 08-evolution §2.29。

### 3.5b DECISION_FLOW 评估（FlowExecutor）

第 6 种 kind `DECISION_FLOW` 由 `FlowExecutor implements RuleVersionExecutor` 求值——它不遍历 AST 树，而遍历 **DAG 决策图**（`FlowBody.flowGraph`）。

**图结构**：节点 `FlowNode` sealed（`RuleRefNode` / `SwitchNode` / `TransformNode` / `OutputNode` 四变体）+ 边 `FlowEdge`（`from` / `to` / `caseKey`）。

**遍历流程**（`Walker.run()`）：

```
inputNodeId → while (cur != null) cur = step(node) → assemble()
```

| 节点类型 | 求值逻辑 | 下一步 |
|---------|---------|--------|
| `RuleRefNode` | 调被引规则的冻结快照（`FlowBody.referencedSnapshots`，发布期 D6 冻结）+ 走对应 kind executor 求值 | 按 `true`/`false` 选 Switch 出边 |
| `SwitchNode` | 求值表达式（六引擎之一，bindings = metrics/payload/subject/now/flow/params/上一步 hitDecisions） | 匹配 caseKey 的边；无匹配走 default（`caseKey=null`）；无 default 终止 |
| `TransformNode` | 求值表达式 → 写 `flowVars`（图内变量命名空间，不污染 EvalContext） | 唯一出边 |
| `OutputNode` | 产出 Decision（匹配 `decisionBindings` 中的 code）→ 进 `hitDecisions` | 终止或下一跳 |

**关键约束**：
- **同步无状态**：一次 Flow 评估内一次性走完，不挂起、不等外部事件
- **RuleRef 冻结快照**：发布期把被引规则的 ACTIVE 版本冻结进 `FlowBody.referencedSnapshots`（D6），评估期零 DB 查询
- **环检测**：发布期静态拒绝成环 flow（`FlowGraphValidator`），运行期兜底 `visited` set 断环
- **被引规则隔离**：传原始 `EvalContext`，不注入 flow 变量——被引规则行为独立于调用方
- **与 BPMN 分界**：FlowExecutor 是"一口气算完"的同步决策图；"要等"的编排交流程引擎（D60/D75 判据）

### 3.6 决策合成（decisionStrategy）

**输入**：命中规则（AST=true）绑定的 Decision 列表（`RuleVersion.decision_bindings` 在发布时已快照化，运行时直读，含 code / name / priority）

**输出**：`EvalResult { finalDecision, hitDecisions, ... }`

**核心动作**（D26/D29，引擎纯决策化，D60）：
- 把所有命中规则绑定的 Decision 收集为 `hitDecisions`（始终填充，按 priority 排序）；
- 按 Scene 的 `decisionStrategy` 合成 `finalDecision`（v1 仅 `HIGHEST_PRIORITY`，取 priority 最小者）；
- **不派发任何动作**：引擎到此产出决策即止，"命中后做什么"（发券 / 拦截 / 通知 / 调外部系统）归消费方 / 流程引擎（对标 OPA 的 PEP、Camunda 的 BPMN，预期搭档 Flowable）。

**缺省 decisionStrategy**（D29）：PUSH/HYBRID Scene 未配 `decisionStrategy` 时，缺省等价 `HIGHEST_PRIORITY`，不会因漏配导致 `finalDecision` 静默为空；PULL Scene 不参与合成（`finalDecision` 始终 null），`hitDecisions` 仍返回供调用方自行处理。

**PUSH vs PULL**：PUSH/HYBRID 模式异步评估完成后发布事件，由审计消费者批写 `evaluation_session`（含 `final_decision` / `hit_decisions`）；PULL 模式同步把 `EvalResult` 返回调用方。两种模式都不在引擎内执行副作用。

---

## 四、evaluation_session 生命周期

`evaluation_session` 是生产评估的异步审计结果，不是同步创建的评估执行锁。

1. 有候选规则时，评估线程预生成 session ID，完成求值后发布 `AuditRecordedEvent`。
2. `AuditPersister` 非阻塞入队；消费者批量插入最终的 `HIT / MISS / BLOCKED / ERROR`，不经历 `PENDING → 终态` 的数据库更新过程。
3. `(tenant_id, event_id)` 唯一键配合重复键空更新，避免重复审计行；它不阻止重复求值，也不会从异步写入回传已有结果。
4. session 批写后，消费者将 trace 交给独立的 `TraceWriter` 队列。两条写入路径均可能失败，不能将其视为同一原子事务。
5. 无候选、dry-run 不产生生产审计记录；进程退出、队满或数据库故障也可能导致缺行。返回评估结果不承诺持久化成功。

当前服务端没有实现 Redis 评估结果缓存/请求锁；重复事件的求值与业务执行去重由接入方负责。历史 D11/D21/D23 描述的同步幂等设计不能作为当前实现保证。

字段、状态聚合、快照开关与索引见 [01-concepts §3.15](./01-concepts.md#315-evaluationsession评估会话非一等公民) 和 [05-storage](./05-storage.md)。

---

## 五、失败语义聚合

本节汇总 D15 在各层的传播规则，统一各阶段的失败处理口径。

### 5.1 单节点失败（ConditionNode 层）

**触发条件**：`ConditionEvaluator.evaluate()` 抛异常 / MetricSource 取数失败（D15 / D25）

**传播规则**：
- 节点 `satisfied=false`，trace 行 `error_code` 填写失败原因（`METRIC_FETCH_FAIL` / `CONDITION_EVAL_ERROR`）；
- **不**中断整树求值；`AND`/`OR`/`NOT` 节点按正常短路逻辑继续，失败节点视为 false；
- 整树求值完毕后若有任意节点失败 → `EvalResult.errorCode` 非空（v1 取第一个失败节点的 errorCode）；`EvalResult.failedNodeIds` 填写所有失败节点 ID。

**规则间隔离**：单条 Rule 评估失败**不影响**同 `(scene + eventType)` 下其他候选 Rule 的评估；引擎逐条 try/catch。

### 5.2 整体降级矩阵（对账用）

| 情形 | `evaluation_session.status` | `EvalResult.errorCode` | 计入对账桶 |
|------|----------------------------|----------------------|-----------|
| 全部节点正常，AST=true | `HIT` | null | HIT |
| 全部节点正常，AST=false | `MISS` | null | MISS |
| Pre-Gate 拦截，未进入 AST | `BLOCKED` | null | BLOCKED |
| 有节点失败，AST=false（自然短路） | `ERROR` | 非空 | ERROR |
| 有节点失败，AST=true（错误节点未影响短路结果） | `HIT`（但 errorCode 非空） | 非空 | HIT（应告警，结果可信度存疑） |
| 评估线程崩溃（session 写入后崩溃） | `FAILED` | — | 不计入，异常监控另行处理 |

**分母定义**（D22）：命中率 = `HIT / (HIT + MISS)`；`BLOCKED` 和 `ERROR` 均**不**计入命中率分母，各自独立监控指标。

### 5.3 PUSH vs PULL 的失败可见性

| 维度 | PUSH 模式 | PULL 模式 |
|------|-----------|----------|
| 评估节点失败 | 安静失败；trace 落 ERROR 状态，触发监控告警 | 返回 `EvalResult { satisfied, errorCode }`，调用方策略自决（fail-secure / fail-open） |
| 调用方职责 | 接受异步不透明；通过 evaluation_session / node_trace 后验排障 | 必须判断 errorCode 是否非空，按业务策略决策 |

---

## 六、维护原则

- 本文档只描述**运行时时序与契约**，不重复字段表（→ 01-concepts）、不贴 SQL（→ 05-storage）、不写参数默认值（→ 07-operability）。
- 新增运行时阶段（如未来 §2.13 评估期预编译切换）必须更新 §二 时序图 + §三 对应阶段。
- 评估审计事件、异步写入或可靠性边界变更时，回写 §四 evaluation_session 章节。
