# 02 — 运行时全链路

> **位置定位**：本文档承载"一个 RuleEvent 进来到 Action 落地"的**全链路时序**——Trigger 接入 / Matcher 检索 / Pre-Gate 拦截 / EvalContext 装配 / AST 评估 / Action 派发各阶段衔接。
>
> **前置阅读**：[`README.md`](./README.md)、[`01-concepts.md`](./01-concepts.md)、[`00-decisions.md`](./00-decisions.md) D6 / D17 / D20 / D21
>
> **解决什么疑问**："事件进来后引擎内部依次发生了什么？""evaluation_session 在哪一步开始 / 结束？""metric 在哪一步预拉？Action 在哪一步派发？"
>
> **职责边界**——
> - ✅ 阶段时序 / 各阶段输入输出契约 / evaluation_session 生命周期 / 失败语义聚合
> - ❌ 不写 AST 节点字段（→ 03-rule-expression）、不写扩展接口签名（→ 04-extension）、不写表结构（→ 05-storage）、不写运维参数（→ 07-operability）、不写决策权衡（→ 00-decisions）

---

## 一、文档状态

| 章节 | 状态 |
|------|------|
| §二 整体时序 | ✅ 已展开 |
| §三 各阶段细节 | ✅ 已展开（Trigger / Matcher / Pre-Gate / EvalContext / Evaluator / Dispatcher） |
| §四 evaluation_session 生命周期 | ✅ 已展开 |
| §五 失败语义聚合 | ✅ 已展开（汇总 D15 单节点 / 单规则 / Action 失败的传播规则） |

---

## 二、整体时序

```
RuleEvent
  │
  ▼
① Trigger 接入层
  │  · HTTP /evaluate 或 /evaluate/sync（PUSH/PULL 入口）
  │  · MQ Consumer（Job Trigger 批量合成的 RuleEvent）
  │  · 校验 payloadSchema + eventType 注册
  ▼
② Matcher 路由
  │  · (tenantId, sceneCode, eventType) → 倒排索引
  │  · 命中候选 RuleVersion 列表（快照锁定）
  │  · 无候选：直接返回 EvalResult{ruleHit=false}
  ▼
③ Pre-Gate 拦截
  │  · 按顺序评估每个 Gate（ROLLOUT / WHITELIST / BLACKLIST / RATE_LIMIT / MUTEX）
  │  · 任一 Gate 不通过：blocked_by 记录 Gate 类型，跳过后续阶段
  │  · 全通过：继续
  ▼
④ EvalContext 构建
  │  · 扫 AST 收集涉及的 metricCode（集合并集）
  │  · providedMetrics 优先匹配（D30），其余按 sourceType 并发取数（D25）
  │  · 组装 EvalContext（不可变 POJO）；evaluation_session 行同步写入 DB
  ▼
⑤ AST 评估（Visitor 树遍历）
  │  · InterpretedExecutor（v1 默认实现）
  │  · 每个节点评估结果收集到 TraceCollector（内存 ArrayList，无锁）
  │  · 单节点失败：节点 satisfied=false，整树继续短路求值（D15）
  │  · 评估结束：EvalResult 出树，node_trace 批量 submit 入 TraceWriter 队列（异步，D21）
  ▼
⑥ Action 派发（Dispatcher）
  · PULL 模式：不派发，EvalResult 同步返回给调用方
  · PUSH/HYBRID 模式：按 finalDecision.actions 排序异步派发
  · 评估线程不等待 Action 完成
  · ActionResult 由 Handler 执行后写 action_execution 表
```

**关键约束**：

| 约束 | 说明 |
|------|------|
| 阶段①→③ 串行 | Trigger 校验 → Matcher 路由 → Pre-Gate 拦截依次串行，快速短路 |
| 阶段④内部并发 | Subject 加载与 metric 批拉 `CompletableFuture.allOf()` 并行（D25） |
| 阶段⑥与评估线程异步解耦 | Action 派发入队后评估线程即返回 EvalResult，不等待 Handler 完成（D20 §2） |
| `evaluation_session` 同步写 | 阶段④结束时同步写入（量小延迟可忽略，且对账需该行作锚 + 与 event_id DB uk 是 D11 幂等双兜底的下半层，D21） |
| `node_trace` 异步批写 | 阶段⑤结束时入 TraceWriter 队列，旁路观察通道，失败降级丢弃，不影响热路径（D21） |

**同步/异步边界**：

```
  评估线程（同步热路径）
  ──────────────────────────────────────────────────────
  ① Trigger → ② Matcher → ③ Pre-Gate → ④ EvalContext 构建
                                              │
                                          ④ 末尾：INSERT evaluation_session（同步）
                                              │
                                         ⑤ AST 评估
                                              │
                                        ⑤ 末尾：submit(node_trace batch)
                                              │         │
                                              │         └──▶ TraceWriter 队列（异步批写）
                                              │
                             PULL 模式：EvalResult ◀──── 同步返回
                             PUSH/HYBRID 模式：ActionInstance list 入队
                                              │
  ──────────────────────────────────────────────────────
  Dispatcher 线程池（异步）
  ──────────────────────────────────────────────────────
                               ActionHandler.execute()
                                    │
                              write action_execution（异步）
```

---

## 三、各阶段细节

### 3.1 Trigger 接入层

**输入**：HTTP 请求体 / MQ 消息

**输出**：`RuleEvent { tenantId, sceneCode, eventType, subjectId, eventId, occurredAt, payload }`

**核心动作**：
- 解析请求体，反序列化为 RuleEvent POJO；
- `eventId` 为空时引擎生成 UUID v4；
- 校验 `eventType` ∈ `Scene.eventTypes` 白名单，不在则返回 400 `INVALID_EVENT_TYPE`；
- 校验 `payload` 字段符合 `Scene.payloadSchema`（字段名 + 基础类型），不符则返回 400 `PAYLOAD_SCHEMA_MISMATCH`；
- 幂等上半层：`SET rule:session:{tenantId}:{eventId} 1 NX EX 3600`（Redis trySet），命中说明已处理过，直接返回缓存结果，不进入后续阶段。

**PUSH vs PULL vs dry-run 入口对比**：

| 入口 | 模式 | 同步等待结果 | 返回内容 |
|------|------|------------|---------|
| `POST /evaluate` | PUSH | 否 | `{ eventId, accepted: true }` |
| `POST /evaluate/sync` | PULL | 是 | `EvalResult { ... }` |
| `POST /evaluate/dry-run` | PULL（试算） | 是 | `EvalResult + nodeTrace` |

**异常语义**：
- 400 系列：schema 校验失败，不进入评估链路；
- 事件接入失败（MQ 反序列化异常）：消息不 ack，由 MQ 重投，引擎不进入评估链路。

### 3.2 Matcher 路由

**输入**：`RuleEvent`

**输出**：候选 `RuleVersion` 快照列表

**核心动作**：
- 按 `(tenantId, sceneCode, eventType)` 三元组查内存倒排索引（`ConcurrentHashMap`）；
- 倒排索引 value = `List<RuleVersion>`（仅含 `PUBLISHED` 状态规则的当前版本快照，`DISABLED` 规则已从索引中剔除）；
- 每个 `RuleVersion` 快照包含：完整预解析的 AST 节点树（`ast_snapshot`）+ `decision_bindings_snapshot`（含 Decision.actions）+ `pre_gates_snapshot` + `rollout_snapshot` + `metric_dependencies`；
- 索引在规则发布/禁用时增量热更（D17）：单服务模式由 Modulith `RulePublishedEvent` 触发（近实时）；嵌入式 SDK 模式由 `DbPollingRuleWatcher` 轮询触发（15s 最终一致）；Scene 变更同理（D24，单服务 `SceneChangedEvent` / SDK 模式 `DbPollingSceneWatcher` 30s）。

**异常语义**：
- 无候选（索引查不到匹配 RuleVersion）→ 直接返回 `EvalResult { ruleHit=false }`，不写 `evaluation_session`，不进入后续阶段。

### 3.3 Pre-Gate 拦截

**输入**：候选 `RuleVersion` 快照列表 + `RuleEvent`

**输出**：通过 Pre-Gate 的 `RuleVersion` 列表；或 `EvalResult { ruleHit=false, preGateBlockedBy=<Gate 类型> }`

**核心动作**：
- 对每条候选 RuleVersion，按固定顺序（ROLLOUT → WHITELIST/BLACKLIST → RATE_LIMIT → MUTEX）串行评估 `pre_gates_snapshot` 中声明的 Gate；
- 任一 Gate 不通过 → 该 RuleVersion **跳过 AST 评估**，trace 落 `node_trace`（节点类型 `PRE_GATE_BLOCKED`，走同一 `TraceWriter`，D21）；

**Gate 类型与通过条件**：

| Gate 类型 | 通过条件 | `blocked_by` 值 |
|-----------|---------|----------------|
| `ROLLOUT` | `hash(subjectId + ruleVersionId) % 100 < percentage` | `ROLLOUT` |
| `WHITELIST` | `subjectId` ∈ `listKey` 对应名单 | `WHITELIST` |
| `BLACKLIST` | `subjectId` ∉ `listKey` 对应名单 | `BLACKLIST` |
| `RATE_LIMIT` | 未触发 tenantId 级 QPS/QPM 限额 | `RATE_LIMIT` |
| `MUTEX` | 当前 `tenantId+subjectId` 无同 `mutexGroup` 规则正在评估 | `MUTEX` |

**结果语义**：
- 某条 RuleVersion 被任一 Gate 拦截 → 该 RuleVersion 不进入 EvalContext 构建与 AST 评估；
- 若**全部**候选 RuleVersion 均被 Pre-Gate 拦截 → 写 `evaluation_session { status=BLOCKED, blocked_by=<首个拦截 Gate 类型> }`，返回 `EvalResult { ruleHit=false }`；
- BLOCKED 对账不计入命中率分母（D22）。

> `blocked_by` 共 5 种枚举值（ROLLOUT / WHITELIST / BLACKLIST / RATE_LIMIT / MUTEX），与 Pre-Gate 类型一一对应。`00-decisions.md` D22 的枚举列举（"ROLLOUT / BLACKLIST / RATE_LIMIT / MUTEX"）是成文时遗漏了 WHITELIST，以本表为准。

**Gate 内部异常**（如 Redis 频次计数器超时）：默认 fail-closed——失败视为"未通过该 Gate"，宁可漏发不可错发；具体各 Gate 的 fail-open/closed 默认值由各实现声明（→ 04-extension）。

### 3.4 EvalContext 构建

**输入**：通过 Pre-Gate 的 `RuleVersion` 快照列表 + `RuleEvent`

**输出**：不可变 `EvalContext` + `evaluation_session` 行（同步写 DB）

**核心动作（5 步）**：

1. **收集 metricCode**：扫每条候选 RuleVersion 的 `metric_dependencies`，取并集；
2. **providedMetrics 优先匹配**（D30）：检查评估请求中 `providedMetrics` 字段，对每个 metric 先查 `providedMetrics`；有值且 `allowProvided=true` 则直接用，跳过 sourceType 取数；
3. **并发取数**（D25）：Subject 加载（`SubjectLoader.load()`）与剩余 metric 批拉（各 `MetricSource` 自管连接池/HTTP client）并行启动，`CompletableFuture.allOf()` 等待全部完成；
4. **组装 EvalContext**：将 Subject + metrics + RuleEvent + `now`（评估开始时间）+ traceId 封装为不可变 POJO；
5. **同步写 evaluation_session**（D21）：INSERT `evaluation_session { status=PENDING（中间状态）, tenant_id, event_id, scene, subject_id, ... }`，与 event_id DB uk 构成幂等双兜底下半层。

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

### 3.5 AST 评估（InterpretedExecutor）

**输入**：`EvalContext` + 候选 `RuleVersion`（含预解析 AST）

**输出**：`EvalResult` + node_trace batch（入 TraceWriter 队列，异步）

**核心动作**：
- `InterpretedExecutor` 以 Visitor 模式递归遍历 AST；
- 每个节点评估后追加一行到 `TraceCollector`（内存 `ArrayList`，无锁，O(1) 追加）；
- EvalResult 出树时一次性 `traceWriter.submit(batch)`（非阻塞 offer，队满丢弃+告警，D21）。

**节点类型与求值语义**：

| 节点 | 求值逻辑 | 短路行为 |
|------|---------|---------|
| `AndNode` | 全部子节点 true | 首个 false 即短路，剩余 `result=null`（跳过，不遍历） |
| `OrNode` | 任一子节点 true | 首个 true 即短路，剩余跳过 |
| `NotNode` | 取反唯一子节点 | 无短路 |
| `ConditionNode` | 调用对应 `ConditionEvaluator.evaluate()` | 失败时 satisfied=false，整树继续（D15） |

**dry-run 模式**：写 `dry_run_session` 系列表，不写 prod `evaluation_session` / `node_trace` 表；TraceWriter 内部按 `EvalContext.dryRun` 标记路由到不同目标表（D7 / D21）。

**EvalResult 输出字段**（D12 多态，v1 仅填 satisfied 部分）：

```
EvalResult {
    satisfied:       boolean           // AST_BOOLEAN kind：整树求值结果
    finalDecision?:  DecisionRef       // D26：合成后最终 Decision（Scene 配了 decisionStrategy 时填充）
    hitDecisions:    List<DecisionRef> // D26：所有命中规则的 Decision 按 priority 排序
    trace:           List<NodeTrace>   // 节点级 trace（PULL/dry-run 同步返回；PUSH 仅异步落库）
    errorCode?:      String            // D15：非空表示有节点失败（METRIC_FETCH_FAIL / EVAL_TIMEOUT / ...）
    errorMessage?:   String
    failedNodeIds?:  List<String>
    partial?:        Boolean
}
```

### 3.6 Action Dispatcher

**输入**：`RuleVersion.decision_bindings_snapshot` 中 `finalDecision` 对应的 `decision_actions_snapshot`（D28：发布时已快照化，运行时直读，不再查 rule_definition）

**输出**：`action_execution` 行（异步写 DB）

**核心动作**：
- 从 `decision_bindings_snapshot` 取 `finalDecision.actions` 列表，按 `sortOrder` 顺序提交给 ActionExecutor 线程池（D20 §2）；
- **评估线程不等待 Action 完成**：提交入队即返回 `EvalResult`（PULL 模式同步返回；PUSH 模式调用方收到 accepted=true）；
- Handler 执行结果写 `action_execution` 表（异步，不阻塞评估线程）；
- 队列满 → `ActionResult { status=FAILED, errorCode=QUEUE_OVERFLOW, retryable=true }`（D20 §2）。

**失败与重试语义**（D18）：
- `retryable=true` → 入重试队列（独立调度，不阻塞同 Decision 后续 Action）；
- `retryable=false` → 直接落 `action_execution.status=FAILED`；
- `failFast=true` 的 Action 失败后，同 Decision 内 `sortOrder` 更大的 Action 全部 `status=SKIPPED, errorCode=PREDECESSOR_FAILED`，不进入重试队列。

**缺省 decisionStrategy**（D29）：PUSH/HYBRID Scene 未配 `decisionStrategy` 时，缺省等价 `HIGHEST_PRIORITY`；PULL Scene `decisionStrategy` 无效，`EvalResult` 直接返回调用方不做合成。

**PULL 模式**：`Scene.dominantMode=PULL` 时，Dispatcher 不派发任何 Action（Decision.actions 发布校验已要求为空，D27），`EvalResult` 同步返回给调用方。

---

## 四、evaluation_session 生命周期

`evaluation_session` 是一次规则评估的主记录行（每次评估 1 行），承担两个职责：

1. **对账锚点**：查询命中率 / BLOCKED 比例 / ERROR 比例的 JOIN 基准（D22）；
2. **幂等下半层**：`UNIQUE KEY(tenant_id, event_id)` 防止同一事件重复评估（D11 / D23）。

**状态机**：

```
 （幂等检查通过）
       │
       ├─ Pre-Gate 全部拦截 ──▶ 直接 INSERT status=BLOCKED（不经过 PENDING）
       │
       └─ Pre-Gate 通过，进入 EvalContext 构建
                │
                ▼
           PENDING（阶段④末尾 INSERT）
                │
      ┌─────────┼─────────┐
      ↓         ↓         ↓
  status=HIT  status=MISS  status=ERROR
 （AST=true） （AST=false） （有节点失败）

  若评估线程崩溃：
  PENDING ──▶ FAILED（不可恢复异常，异常监控另行处理）
```

> `PENDING` 是引擎内部中间状态，在 EvalContext 构建完毕、AST 评估开始前写入。BLOCKED 路径由 Pre-Gate 阶段（③）直接写入，不经过 PENDING。DB `status` 列存储 6 种值：`PENDING`（进行中）/ `HIT / MISS / BLOCKED / ERROR`（D22 四态，完成态）/ `FAILED`（崩溃态）。

**写入时机**：

| 操作 | 时机 | 是否同步 |
|------|------|---------|
| `INSERT (status=PENDING)` | EvalContext 构建完毕，AST 评估开始前 | **同步**（D21） |
| `UPDATE (status=HIT/MISS/ERROR)` | EvalResult 出树后，用实际四态值直接 UPDATE（不经过 COMPLETED 中间态） | **同步**（同一链路，D21） |
| `node_trace` batch INSERT | EvalResult 出树后入 TraceWriter 队列 | **异步**（D21） |
| `action_execution` INSERT | ActionHandler.execute() 完成后 | **异步**（D20 §2） |

**幂等处理**（D11 / D23）：

1. **上半层（Redis trySet）**：Trigger 接入时 `SET rule:session:{tenantId}:{eventId} 1 NX EX 3600`；成功则继续；失败（key 已存在）则直接返回上次缓存的 `EvalResult`，不再进入评估链路；
2. **下半层（DB uk）**：INSERT `evaluation_session` 时若 `(tenant_id, event_id)` uk 冲突 → catch `DuplicateKeyException` → 查询已有行返回已有结果；
3. **Redis 宕机降级**：上半层不可用时降级走 DB uk；DB uk 并发竞争时，后提交者 SELECT 已有行返回。

**evaluation_session 字段概览**：

| 列名 | 类型 | 说明 |
|------|------|------|
| `session_id` | BIGINT PK | 主键，雪花 |
| `tenant_id` | VARCHAR | 租户 ID |
| `event_id` | VARCHAR | 业务事件 ID；与 tenant_id 构成 UK（D23） |
| `scene_code` | VARCHAR | 场景码 |
| `event_type` | VARCHAR | 事件类型 |
| `subject_id` | VARCHAR | 主体 ID |
| `rule_version_id` | BIGINT | 评估时锁定的 RuleVersion（多规则命中时取优先级最高的一条，详见 05-storage） |
| `status` | VARCHAR | `PENDING`（进行中）/ `HIT / MISS / BLOCKED / ERROR`（D22 四态，完成态）/ `FAILED`（异常态） |
| `final_decision` | VARCHAR | 合成后 Decision.code（nullable；Scene 未配 decisionStrategy 且无 finalDecision 时为空） |
| `hit_decisions` | JSON | 所有命中规则的 Decision 列表（始终填充） |
| `blocked_by` | VARCHAR | nullable；仅 `status=BLOCKED` 时有值，记录首个拦截 Gate 类型 |
| `error_code` | VARCHAR | nullable；D15 EvalResult.errorCode；仅 `status=ERROR` 时有值 |
| `occurred_at` | DATETIME | 业务事件发生时间（来自 RuleEvent.occurredAt） |
| `evaluated_at` | DATETIME | 引擎完成评估时间 |

> DDL 完整定义见 [`05-storage.md`](./05-storage.md) §evaluation_session 表。

---

## 五、失败语义聚合

本节汇总 D15 在各层的传播规则，统一各阶段的失败处理口径。

### 5.1 单节点失败（ConditionNode 层）

**触发条件**：`ConditionEvaluator.evaluate()` 抛异常 / MetricSource 取数失败（D15 / D25）

**传播规则**：
- 节点 `satisfied=false`，trace 行 `error_code` 填写失败原因（`METRIC_FETCH_FAIL` / `EVALUATOR_EXCEPTION` / `EVAL_TIMEOUT`）；
- **不**中断整树求值；`AND`/`OR`/`NOT` 节点按正常短路逻辑继续，失败节点视为 false；
- 整树求值完毕后若有任意节点失败 → `EvalResult.errorCode` 非空（v1 取第一个失败节点的 errorCode）；`EvalResult.failedNodeIds` 填写所有失败节点 ID。

**规则间隔离**：单条 Rule 评估失败**不影响**同 `(scene + eventType)` 下其他候选 Rule 的评估；引擎逐条 try/catch。

### 5.2 单 Action 失败（ActionHandler 层）

**触发条件**：`ActionHandler.execute()` 抛异常 / 超时（D18）

**传播规则**：

```
ActionHandler.execute() → 异常 / timeout
  │ 引擎归一
  ▼
ActionResult { status=FAILED, errorCode, retryable }
  │
  ├─ retryable=true  → 入重试队列（不阻塞同 Decision 后续 Action）
  └─ retryable=false → 直接落 action_execution.status=FAILED

failFast=true 的 Action 失败时：
  → 同 Decision 内 sortOrder 更大的 Action 全部
     status=SKIPPED, errorCode=PREDECESSOR_FAILED
     不进重试队列
```

Action 失败**不影响** `EvalResult.satisfied`（评估阶段已结束，Action 是命中后行为）。

**补偿不自动触发**（D18）：引擎只记录 FAILED 状态，补偿由 D4 补偿流水线外部调度（对账任务 / 运营手动回滚按钮）发起 `ActionHandler.compensate(action, context)` 调用。

### 5.3 整体降级矩阵（对账用）

| 情形 | `evaluation_session.status` | `EvalResult.errorCode` | 计入对账桶 |
|------|----------------------------|----------------------|-----------|
| 全部节点正常，AST=true | `HIT` | null | HIT |
| 全部节点正常，AST=false | `MISS` | null | MISS |
| Pre-Gate 拦截，未进入 AST | `BLOCKED` | null | BLOCKED |
| 有节点失败，AST=false（自然短路） | `ERROR` | 非空 | ERROR |
| 有节点失败，AST=true（错误节点未影响短路结果） | `HIT`（但 errorCode 非空） | 非空 | HIT（应告警，结果可信度存疑） |
| 评估线程崩溃（session 写入后崩溃） | `FAILED` | — | 不计入，异常监控另行处理 |

**分母定义**（D22）：命中率 = `HIT / (HIT + MISS)`；`BLOCKED` 和 `ERROR` 均**不**计入命中率分母，各自独立监控指标。

### 5.4 PUSH vs PULL 的失败可见性

| 维度 | PUSH 模式 | PULL 模式 |
|------|-----------|----------|
| 评估节点失败 | 安静失败，Action 不派发（满足且无 error 才派发）；trace 落 ERROR 状态，触发监控告警 | 返回 `EvalResult { satisfied, errorCode }`，调用方策略自决（fail-secure / fail-open） |
| Action 失败 | 重试队列 / FAILED 终态，写 action_execution；通过监控告警可见 | N/A（PULL 无 Action） |
| 调用方职责 | 接受异步不透明；通过 evaluation_session / node_trace 后验排障 | 必须判断 errorCode 是否非空，按业务策略决策 |

---

## 六、维护原则

- 本文档只描述**运行时时序与契约**，不重复字段表（→ 01-concepts）、不贴 SQL（→ 05-storage）、不写参数默认值（→ 07-operability）。
- 新增运行时阶段（如未来 §2.13 评估期预编译切换）必须更新 §二 时序图 + §三 对应阶段。
- v1 同步路径变更或 v2 异步路径锚点更新时，§四 evaluation_session 章节同步回写。
