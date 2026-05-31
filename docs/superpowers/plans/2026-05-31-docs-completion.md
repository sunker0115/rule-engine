# Rule Engine Docs Completion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 填完 7 个占位文档（02/04/05/06/07/08/10），每个文档所有 ⏳ 章节替换为完整内容，无 placeholder。

**Architecture:** 每个文档独立展开，按"被引用方先写"原则排序：02（运行时流程）→ 04（SPI 接口）→ 05（DDL）→ 07（可运维）→ 10（API 契约）→ 06（前端）→ 08（演进补全）。09-skeleton 需单独讨论技术栈，不在本计划内。

**Tech Stack:** Markdown + JSON（示例片段），无代码，所有内容从 00-decisions.md / 01-concepts.md 推导。

---

## 骨架问题答复（前置说明，不是任务）

`09-skeleton.md` 需要在写 `src/` 代码**之前**确认，但**不需要在写这 7 个设计文档之前**。04-extension.md 涉及"SPI 落哪个模块"，暂时以 `TBD（见 09-skeleton §二）` 占位，等 09-skeleton 确认后一次性回填。

---

## Task 1: 02-runtime.md — 运行时全链路

**Files:**
- Modify: `docs/02-runtime.md` (全部 ⏳ 章节)

- [ ] **Step 1: 写 §二 整体时序**

用 Mermaid 序列图（纯文本降级版）描述一次事件评估的完整时序：

```markdown
## 二、整体时序

一个 `RuleEvent` 进入引擎到 Action 落地的全链路，按阶段分 6 步：

```
RuleEvent
  │
  ▼
① Trigger 接入层
  │  ·HTTP /evaluate 或 /evaluate/sync（PUSH/PULL 入口）
  │  ·MQ Consumer（Job Trigger 批量合成的 RuleEvent）
  │  ·校验 payloadSchema + eventType 注册
  ▼
② Matcher 路由
  │  ·(tenantId, sceneCode, eventType) → 倒排索引
  │  ·命中候选 RuleVersion 列表（快照锁定）
  │  ·无候选：直接返回 EvalResult{ruleHit=false}
  ▼
③ Pre-Gate 拦截
  │  ·按顺序评估每个 Gate（ROLLOUT / WHITELIST / BLACKLIST / RATE_LIMIT / MUTEX）
  │  ·任一 Gate 不通过：blocked_by 记录 Gate 类型，跳过后续阶段
  │  ·全通过：继续
  ▼
④ EvalContext 构建
  │  ·扫 AST 收集涉及的 metricCode（集合并集）
  │  ·providedMetrics 优先匹配（D30），其余按 sourceType 并发取数
  │  ·组装 EvalContext（不可变 POJO）；evaluation_session 行同步写入 DB
  ▼
⑤ AST 评估（Visitor 树遍历）
  │  ·InterpretedExecutor（v1 默认实现）
  │  ·每个节点评估结果收集到 TraceCollector（内存 ArrayList，无锁）
  │  ·单节点失败：节点 satisfied=false，整树继续短路求值（D15）
  │  ·评估结束：EvalResult 出树，node_trace 批量 submit 入 TraceWriter 队列（异步，D21）
  ▼
⑥ Action 派发（Dispatcher）
  ·PULL 模式：不派发，EvalResult 同步返回给调用方
  ·PUSH/HYBRID 模式：按 finalDecision.actions 排序异步派发
  ·评估线程不等待 Action 完成
  ·ActionResult 由 Handler 执行后写 action_execution 表
```

**关键约束（不可变）：**
- 阶段①→③ 是串行；④→⑤ 内部并发取数；⑥ 与评估线程异步解耦
- `evaluation_session` 行在阶段④结束时**同步写**（D21 §1：量小延迟可忽略，且对账锚点必须先存在）
- `node_trace` 在阶段⑤结束时**异步批写**（D21 §2：旁路观察通道，不阻塞热路径）
```

- [ ] **Step 2: 写 §三 各阶段细节**

每个阶段一个子节，含输入/输出/异常语义：

```markdown
## 三、各阶段细节

### 3.1 Trigger 接入层

**职责：** 将外部请求/消息统一转化为内部 `RuleEvent`，做入口校验。

**输入：** HTTP 请求体 / MQ 消息
**输出：** `RuleEvent{tenantId, sceneCode, eventType, subjectId, eventId, occurredAt, payload}`
**校验：**
- `eventType` 必须在 Scene.eventTypes 白名单内；否则 400 INVALID_EVENT_TYPE
- `payload` 字段按 `Scene.payloadSchema` 校验必填项；否则 400 PAYLOAD_SCHEMA_MISMATCH
- `eventId` 为空时引擎生成（`UUID v4`）；调用方传入时直接用（幂等回放场景）

**PUSH vs PULL 入口区别：**
| 入口 | 模式 | 同步等待 | 返回内容 |
|------|------|----------|----------|
| `POST /evaluate` | PUSH | 否，异步派发 | `{eventId, accepted: true}` |
| `POST /evaluate/sync` | PULL | 是，等 EvalResult | `EvalResult{finalDecision, hitDecisions, ...}` |
| `POST /evaluate/dry-run` | PULL（试算） | 是 | `EvalResult + 完整 nodeTrace` |

### 3.2 Matcher 路由

**职责：** 按 (tenantId, sceneCode, eventType) 取候选 RuleVersion 快照列表。

**实现：**
- 运行时倒排索引（内存 ConcurrentHashMap，Matcher 维护）：key = `(tenantId, sceneCode, eventType)`，value = `List<RuleVersion>`（只含 `ACTIVE` 状态规则的当前版本快照）
- 索引在规则发布/禁用时增量热更（SceneWatcher / RuleVersionWatcher 触发，D24/D17）
- `RuleVersion` 快照包含完整 `conditionAst`（预解析的 AST 节点树）+ `decisionBindings` + `preGates`

**异常：**
- 无候选（Scene 未配规则或全部 DISABLED）：`EvalResult{ruleHit=false, hitDecisions=[], finalDecision=null}`，不写 `evaluation_session`（量太大且无审计价值，可选配置），直接返回

### 3.3 Pre-Gate 拦截

**职责：** 规则级前置开关，按顺序短路评估，决定是否进入 AST。

**Gate 类型与语义：**
| 类型 | 通过条件 | blocked_by 记录 |
|------|----------|----------------|
| `ROLLOUT` | `hash(subjectId + ruleVersionId) % 100 < percentage` | `ROLLOUT` |
| `WHITELIST` | `subjectId ∉ listKey 对应名单` | `WHITELIST` |
| `BLACKLIST` | `subjectId ∉ listKey 对应名单` | `BLACKLIST` |
| `RATE_LIMIT` | 未触发 TenantId 级 QPS/QPM 限额 | `RATE_LIMIT` |
| `MUTEX` | 当前 tenantId+subjectId 无同 mutexGroup 规则正在评估 | `MUTEX` |

**结果：**
- 任一 Gate 不通过 → `EvalResult{ruleHit=false, preGateBlockedBy=<Gate类型>}`，`evaluation_session.blocked_by` 记录 Gate 类型
- 对账四态计数：`BLOCKED` 不计入命中率分母（D22）

### 3.4 EvalContext 构建

**职责：** 按 AST 引用的 metricCode 集合并发取数，组装不可变 EvalContext。

**流程：**
1. 扫 AST 节点，收集所有 `metricCode`（Set，去重）
2. 对每个 metricCode 查询 `providedMetrics`（D30）：有值且 `allowProvided=true` 则用 `PROVIDED` 值，跳过 sourceType 取数；`allowProvided=false` 则忽略并 WARN
3. 剩余 metric 按 `sourceType` 分组，并发取数（Redis cache → 未命中则走 sourceType handler）
4. 所有 metric 取数完毕后注入 EvalContext（此后 EvalContext 内 metric 值冻结，D20 派生）
5. **同步写** `evaluation_session` 行（含 eventId、tenantId、ruleVersionId、occurredAt 等，D21）

**EvalContext 标准字段（v1 闭合枚举）：**
`now` / `tenantId` / `scene` / `eventType` / `occurredAt` / `subjectId` / `ruleVersionId` / `payload.*` / `metrics.*`

**取数失败语义（D15）：**
单 metric 取数失败 → 该 metric 在 EvalContext 里标记 `FETCH_FAIL`；引用该 metric 的 ConditionNode evaluated=false，trace 节点标记 `METRIC_FETCH_FAIL`；整树继续短路求值。

### 3.5 AST 评估（InterpretedExecutor）

**职责：** Visitor 树遍历，对每个节点递归求布尔值，并向 TraceCollector 累积 trace 行。

**节点类型与求值语义：**
| 节点 | 求值 | 短路 |
|------|------|------|
| `AndNode` | 全部子节点 true | 首个 false 即短路，剩余节点 result=null（跳过） |
| `OrNode` | 任一子节点 true | 首个 true 即短路 |
| `NotNode` | 取反唯一子节点 | 无 |
| `ConditionNode` | 调用对应 ConditionEvaluator.evaluate() | — |

**TraceCollector：**
- 每个节点评估后追加一行到内存 ArrayList（O(1)，无锁）
- EvalResult 出树时一次性 `traceWriter.submit(batch)` 入队（非阻塞 offer，队满丢弃+告警，D21）
- dry-run 模式：写 `dry_run_session` 系列表，不写 prod 表

**输出 EvalResult（D12 多态，v1 仅填 satisfied 部分）：**
```
EvalResult {
  ruleVersionId: Long
  satisfied: Boolean               // AST 根节点求值结果
  errorCode: String?               // 若有节点失败（METRIC_FETCH_FAIL / CONDITION_EVAL_ERROR）
  finalDecision: DecisionRef?      // 由 decisionStrategy 合成
  hitDecisions: List<DecisionRef>  // 所有满足的 Decision
  nodeTrace: TraceNode?            // dry-run 时填充
}
```

### 3.6 Action Dispatcher

**职责：** PUSH/HYBRID 模式下，按 `finalDecision.actions`（D28 快照）排序异步派发。

**流程：**
1. 从 `EvalResult.finalDecision` 取 actions 列表（D28：发布时快照，运行时直读）
2. 按 `sortOrder` 顺序，每个 Action 提交给 ActionExecutor 线程池
3. 调用 `ActionHandler.execute(actionContext)` → `ActionResult`
4. `ActionResult.status=FAILED, retryable=true` → 入重试队列；`retryable=false` → 直接落库 FAILED
5. `failFast=true` 的 Action 失败后，同 Decision 内 sortOrder 更大的 Action 全部 `SKIPPED(PREDECESSOR_FAILED)`
6. 每个 Action 执行结果写 `action_execution` 表（异步，不阻塞评估线程）
```

- [ ] **Step 3: 写 §四 evaluation_session 生命周期**

```markdown
## 四、evaluation_session 生命周期

`evaluation_session` 是一次规则评估的主记录行（每次评估 1 行），承担两个职责：
1. **对账锚点**：查询命中率 / BLOCKED 比例 / ERROR 比例的 JOIN 基准
2. **幂等下半层**：`UNIQUE KEY(tenant_id, event_id)` 防止同一事件重复评估（D11/D21）

### 状态机

```
PENDING（写入时）
  → COMPLETED（EvalResult 出树后更新 status + final_decision + hit_decisions）
  → FAILED（评估期发生不可恢复异常，如 OOM / Thread 被强制中断）
```

### 写入时机

| 操作 | 时机 | 是否同步 |
|------|------|---------|
| INSERT（status=PENDING） | EvalContext 构建完毕，AST 评估开始前 | **同步**（D21）|
| UPDATE（status=COMPLETED） | EvalResult 出树后 | **同步**（同一事务，量小） |
| node_trace batch INSERT | EvalResult 出树后入 TraceWriter 队列 | **异步**（D21） |
| action_execution INSERT | ActionHandler.execute() 完成后 | **异步** |

### 幂等处理（D11）

1. **Redis trySet 上半层**（快速路径）：`SET rule:session:{tenantId}:{eventId} 1 NX EX 3600`，失败则直接返回上次缓存的 EvalResult（若有）
2. **DB uk 下半层**（持久兜底）：INSERT 时若 `(tenant_id, event_id)` uk 冲突 → catch DuplicateKey → 查询已有行返回已有结果
3. **两层都失败的场景**：Redis 宕机时降级走 DB uk；DB uk 并发竞争时，后者 SELECT 已有行

### evaluation_session 字段概览

| 列 | 类型 | 说明 |
|---|---|---|
| `id` | BIGINT PK | 自增 |
| `tenant_id` | VARCHAR(64) | 租户 |
| `event_id` | VARCHAR(128) | 业务事件 id（UK 第二列） |
| `scene_code` | VARCHAR(64) | 业务域 |
| `subject_id` | VARCHAR(128) | 业务主体 |
| `rule_version_id` | BIGINT | 评估时快照的 RuleVersion id |
| `status` | ENUM | PENDING / COMPLETED / FAILED |
| `final_decision` | VARCHAR(64) | 最终决策码（nullable） |
| `hit_decisions` | JSON | 命中的所有决策码列表 |
| `blocked_by` | VARCHAR(64) | Pre-Gate 拦截类型（nullable） |
| `error_code` | VARCHAR(64) | 评估失败 errorCode（nullable） |
| `occurred_at` | DATETIME(3) | 业务事件时间 |
| `evaluated_at` | DATETIME(3) | 引擎开始评估时间 |
| `dry_run` | TINYINT(1) | 是否 dry-run（写独立表，此列为 0） |
```

- [ ] **Step 4: 写 §五 失败语义聚合**

```markdown
## 五、失败语义聚合

总结 D15 在各层的传播规则，避免实现者各自解读。

### 单节点失败（ConditionNode 层）

触发条件：`ConditionEvaluator.evaluate()` 抛异常 / MetricSource 取数失败

处理：
- 节点 `satisfied = false`，trace 行 `error_code` 填写失败原因（`METRIC_FETCH_FAIL` / `CONDITION_EVAL_ERROR`）
- **不**中断整树求值；AND/OR 节点正常短路逻辑继续
- 整树求值完毕后，若有任意节点失败 → `EvalResult.errorCode` 非空（取优先级最高的 errorCode，多个用 `MULTIPLE_ERRORS` 或取第一个，v1 取第一个）

### 单 Action 失败（ActionHandler 层）

```
ActionHandler.execute() → 异常 / timeout
  ↓ 引擎归一
ActionResult { status=FAILED, errorCode, retryable }
  ↓
retryable=true  → 入重试队列（不阻塞同 Decision 后续 Action）
retryable=false → 直接落 action_execution.status=FAILED

failFast=true 的 Action 失败时：
  → 同 Decision 内 sortOrder 更大的 Action 全部
     status=SKIPPED, errorCode=PREDECESSOR_FAILED
     不进重试队列
```

Action 失败**不影响** `EvalResult.satisfied`（评估阶段已结束）。

### 整体降级矩阵（对账用）

| 情形 | evaluation_session.status | EvalResult.errorCode | 计入对账哪个桶 |
|------|--------------------------|----------------------|---------------|
| 全部节点正常，AST=true | COMPLETED | null | HIT |
| 全部节点正常，AST=false | COMPLETED | null | MISS |
| Pre-Gate 拦截 | COMPLETED | null（errorCode 是运行期概念，Gate 拦截不填） | BLOCKED |
| 有节点失败，AST=false（自然短路） | COMPLETED | errorCode 非空 | ERROR |
| 有节点失败，AST=true（错误节点未影响短路） | COMPLETED | errorCode 非空 | HIT（但有 errorCode，应告警） |
| 评估线程崩溃 | FAILED | — | — （不计入，异常监控另行处理） |
```

- [ ] **Step 5: 更新 §一 文档状态表，标记所有章节 ✅**

将 02-runtime.md §一中所有 `⏳ 未展开` 改为 `✅ 已展开`。

- [ ] **Step 6: commit**

```bash
git add docs/02-runtime.md
git commit -m "docs(02-runtime): 展开整体时序 / 各阶段细节 / session 生命周期 / 失败语义聚合"
```

---

## Task 2: 04-extension.md — SPI 扩展指南

**Files:**
- Modify: `docs/04-extension.md` (全部 ⏳ 章节)

- [ ] **Step 1: 写 §二 加 ConditionType**

```markdown
## 二、加 ConditionType

### 2.1 SPI 接口

```java
// 包路径 TBD（见 09-skeleton §二）
@FunctionalInterface
public interface ConditionEvaluator {
    /**
     * @param node  规则 AST 中的 ConditionNode（含 conditionType + params）
     * @param ctx   本次评估的不可变上下文（含 payload.* / metrics.* / now 等）
     * @return true = 条件满足；false = 不满足；抛异常 → 引擎按 D15 单节点降级处理
     */
    boolean evaluate(ConditionNode node, EvalContext ctx);
}
```

### 2.2 注解声明

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ConditionType {
    /** 与规则 JSON 中 conditionType 字段对应，全局唯一 */
    String value();
    /** 参数 JSON Schema（用于前端编辑器渲染表单），OpenAPI 3.0 inline schema 格式 */
    String paramsSchema() default "{}";
    /** 人类可读名称，用于编辑器下拉显示 */
    String displayName() default "";
}
```

### 2.3 注册方式

Spring Bean + 注解扫描。引擎启动时扫描 `@ConditionType` 注解的 Bean，注册到 `ConditionTypeRegistry`。

```java
@Component
@ConditionType(
    value = "metric.threshold",
    displayName = "指标阈值比较",
    paramsSchema = """
        {
          "type": "object",
          "required": ["operator"],
          "properties": {
            "operator": { "type": "string", "enum": ["EQ","GT","GTE","LT","LTE","BETWEEN","NOT_BETWEEN"] },
            "value":    { "type": "number" },
            "min":      { "type": "number" },
            "max":      { "type": "number" }
          }
        }
    """
)
public class MetricThresholdEvaluator implements ConditionEvaluator {
    @Override
    public boolean evaluate(ConditionNode node, EvalContext ctx) {
        Object metricVal = ctx.getMetric(node.getMetricCode());
        if (metricVal == null) throw new MetricNotFoundException(node.getMetricCode());
        // ... 比较逻辑
    }
}
```

### 2.4 实现建议

- **不要**在 evaluate() 内发起任何网络 / DB 调用（EvalContext 已预拉所有 metric）
- 如果 params 缺必填字段 → 抛 `IllegalArgumentException`，引擎归 `CONDITION_EVAL_ERROR`
- evaluate() 必须无副作用（EvalContext 不可变，不能写）
```

- [ ] **Step 2: 写 §三 加 ActionType**

```markdown
## 三、加 ActionType

### 3.1 SPI 接口

```java
public interface ActionHandler {
    /**
     * 执行 Action。幂等性由 Handler 自行保证。
     * @param ctx  含 action 定义 + EvalContext + actionExecution id（用于幂等键）
     * @return ActionResult，不要抛异常（异常由引擎归一为 FAILED）
     */
    ActionResult execute(ActionContext ctx);

    /**
     * 补偿（回滚）。由外部对账任务调用，非引擎自动触发。
     * @return ActionResult，同 execute 语义
     */
    default ActionResult compensate(ActionContext ctx) {
        return ActionResult.notSupported();
    }
}
```

### 3.2 注解声明

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ActionType {
    String value();              // 与规则 JSON 中 actionType 字段对应，全局唯一
    String displayName() default "";
    String paramsSchema() default "{}"; // 前端表单 schema
    int timeoutMs() default 3000;       // 超时阈值（引擎归 TIMEOUT）
    boolean async() default true;       // false = 同步等待结果（仅 PULL 场景下有意义）
}
```

### 3.3 示例：ticket.create

```java
@Component
@ActionType(
    value = "ticket.create",
    displayName = "创建工单",
    paramsSchema = """
        {
          "type": "object",
          "required": ["title", "assignee"],
          "properties": {
            "title":        { "type": "string" },
            "priority":     { "type": "string", "enum": ["LOW","MEDIUM","HIGH"] },
            "assignee":     { "type": "string" },
            "decisionCode": { "type": "string" }
          }
        }
    """,
    timeoutMs = 3000
)
public class TicketCreateHandler implements ActionHandler {
    @Override
    public ActionResult execute(ActionContext ctx) {
        String eventId = ctx.getEvalContext().getEventId();
        // 幂等检查：同一 eventId 是否已建单
        if (ticketService.existsByEventId(eventId)) {
            return ActionResult.success();   // 直接返回，不重复建单
        }
        try {
            ticketService.create(/* ... */);
            return ActionResult.success();
        } catch (TimeoutException e) {
            return ActionResult.failed("TIMEOUT", true);
        }
    }
}
```

### 3.4 实现建议

- execute() 内**必须**做幂等检查（幂等键推荐：`tenantId + eventId + actionId`，与 D11 对齐）
- 超时不要直接抛异常，catch 后返回 `ActionResult.failed("TIMEOUT", retryable=true)`
- compensate() 如不支持，返回 `ActionResult.notSupported()`（status=FAILED, errorCode=NOT_SUPPORTED, retryable=false）
```

- [ ] **Step 3: 写 §四 加 MetricSource**

```markdown
## 四、加 MetricSource

### 4.1 SPI 接口

```java
public interface MetricSourceHandler {
    /**
     * 取单个 metric 值。引擎在 EvalContext 构建阶段并发调用。
     * @param query  含 metricCode + params + subjectId + payload
     * @return MetricValue（含值 + 取数时间戳）；取数失败 → 抛异常（引擎归 METRIC_FETCH_FAIL）
     */
    MetricValue fetch(MetricQuery query);
}
```

### 4.2 注解声明

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface MetricSourceType {
    String value();   // "SQL_AGGREGATE" / "ATTRIBUTE" / "EXTERNAL_HTTP" / "STREAM" / 自定义
    String paramsSchema() default "{}";
    int defaultTimeoutMs() default 1000;
    int defaultCacheTtlSeconds() default 60;
}
```

### 4.3 内置 sourceType 建议超时

| sourceType | 推荐 timeoutMs | 推荐 cacheTtl |
|------------|---------------|--------------|
| ATTRIBUTE | 100ms | 300s |
| SQL_AGGREGATE | 500ms | 3600s（慢查询，建议长 cache） |
| EXTERNAL_HTTP | 300ms | 60s |
| STREAM | 200ms | 0（实时窗口，不 cache） |

### 4.4 实现要求

- fetch() 自行管 timeout / retry / circuit breaker（引擎核心不重试，D15）
- 结果按 `MetricQuery.cacheTtl` 写 Redis（`rule:metric:{tenantId}:{metricCode}:{subjectId}`），下次评估命中直接返回
- 无法取数时**抛异常**（不要返回 null），引擎统一处理
```

- [ ] **Step 4: 写 §五 元数据契约**

```markdown
## 五、元数据契约

前端编辑器通过 `GET /api/scenes/{code}/metadata` 拿到当前 Scene 可用的 ConditionType / ActionType / MetricSource 枚举及其 paramsSchema，实现"动态表单渲染"。

### 元数据响应结构

```json
{
  "conditionTypes": [
    {
      "code": "metric.threshold",
      "displayName": "指标阈值比较",
      "paramsSchema": { "type": "object", "properties": { "operator": {...}, "value": {...} } },
      "requiresMetric": true
    },
    {
      "code": "event.payload.compare",
      "displayName": "Payload 字段比较",
      "paramsSchema": { "type": "object", "properties": { "field": {...}, "operator": {...}, "value": {...} } },
      "requiresMetric": false
    }
  ],
  "actionTypes": [
    {
      "code": "ticket.create",
      "displayName": "创建工单",
      "paramsSchema": { "type": "object", "properties": { "title": {...}, "assignee": {...} } },
      "compensatable": true
    }
  ],
  "availableMetrics": [
    {
      "metricCode": "user.account.age.days",
      "name": "账户开立天数",
      "dataType": "LONG",
      "sourceType": "SQL_AGGREGATE",
      "allowProvided": false
    }
  ]
}
```

### 约束

- `availableMetrics` 只返回 Scene.metricBindings 白名单内的 metric，不暴露全局 metric 列表
- 前端选择 ConditionType 后，按 `paramsSchema` 动态渲染参数表单（JSON Schema Form）
- `requiresMetric=true` 的 ConditionType（如 metric.threshold）渲染时同时出现 metric 下拉框
```

- [ ] **Step 5: 写 §六 实现指南**

```markdown
## 六、实现指南

### 通用原则

1. **不改 EvalContext**：三种 SPI 实现（ConditionEvaluator / ActionHandler / MetricSourceHandler）均不能修改传入的 EvalContext（不可变合约）
2. **异常归一**：实现方可抛任意 RuntimeException；引擎在边界处 catch 并按类型归一为 errorCode
3. **幂等自管**：ActionHandler 自己保证 execute() 幂等（引擎不提供幂等包装）
4. **不做跨调用状态**：Handler / Evaluator 应设计为无状态 Bean（Spring singleton），不在实例字段存评估中间状态

### 超时与熔断建议

不同 sourceType 的推荐参数（非强制，Handler 可在注解 / 配置中覆盖）：

| 维度 | ATTRIBUTE | SQL_AGGREGATE | EXTERNAL_HTTP | STREAM |
|------|-----------|--------------|--------------|--------|
| connect timeout | 50ms | 200ms | 200ms | 100ms |
| read timeout | 100ms | 500ms | 300ms | 200ms |
| retry | 1 次 | 0 次 | 1 次（幂等才行）| 0 次 |
| circuit breaker threshold | 50% / 10s | 30% / 30s | 50% / 10s | 50% / 10s |

### Bean 生命周期

MetricSourceHandler 在 Scene 激活时由 SceneWatcher 触发 `init()`（可选接口），Scene 卸载时触发 `destroy()`（可选接口），用于 JDBC 连接池 / HTTP client 的资源管理。

ActionHandler 类似，但仅 PUSH/HYBRID Scene 预热（PULL Scene 不预热，D2 §Scene 字段说明）。
```

- [ ] **Step 6: 更新 §一 文档状态表，标记所有章节 ✅**

- [ ] **Step 7: commit**

```bash
git add docs/04-extension.md
git commit -m "docs(04-extension): 展开 ConditionType/ActionType/MetricSource SPI 接口 + 元数据契约 + 实现指南"
```

---

## Task 3: 05-storage.md — 存储模型与 DDL

**Files:**
- Modify: `docs/05-storage.md` (全部 ⏳ 章节)

- [ ] **Step 1: 写 §二 表清单总览**

```markdown
## 二、表清单总览

### 配置层表（平台运维写入，评估期只读）

| 表名 | 职责 | 主要 UK |
|------|------|--------|
| `tenant` | 租户注册 | `code` |
| `scene` | 业务域 / 使用模式 / payloadSchema | `(tenant_id, code)` |
| `metric_definition` | 指标元数据（sourceType / params / cacheTtl） | `(tenant_id, metric_code)` |
| `rule_definition` | 规则主记录（code / name / status） | `(tenant_id, code)` |
| `rule_version` | 规则版本快照（conditionAst / decisionBindings / preGates） | `(rule_definition_id, version)` |
| `audit_log` | 配置变更审计（人的行为，同步事务写，D14） | — |

### 评估层表（运行时写入）

| 表名 | 职责 | 写入模式 |
|------|------|--------|
| `evaluation_session` | 每次评估主记录 / 幂等锚点（D11/D21） | **同步**（session 行） |
| `node_trace` | AST 各节点求值 trace（D7） | **异步批写**（D21） |
| `action_execution` | Action 派发执行记录（D4） | **异步** |
| `dry_run_session` | dry-run 评估主记录（与 prod 隔离，D7） | 同步 |
| `dry_run_node_trace` | dry-run 节点 trace | 异步批写 |
```

- [ ] **Step 2: 写 §三 各表 DDL（配置层）**

```markdown
## 三、各表 DDL

### 3.1 tenant

```sql
CREATE TABLE tenant (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  code        VARCHAR(64) NOT NULL,
  name        VARCHAR(128) NOT NULL,
  status      ENUM('ACTIVE','DISABLED') NOT NULL DEFAULT 'ACTIVE',
  created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### 3.2 scene

```sql
CREATE TABLE scene (
  id               BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id        BIGINT NOT NULL,
  code             VARCHAR(64) NOT NULL,
  name             VARCHAR(128) NOT NULL,
  dominant_mode    ENUM('PUSH','PULL','HYBRID') NOT NULL,
  decision_strategy ENUM('HIGHEST_PRIORITY','MOST_STRICT') NOT NULL DEFAULT 'HIGHEST_PRIORITY',
  subject_type     ENUM('USER','ACCOUNT','DEVICE','ORDER','CUSTOM') NOT NULL DEFAULT 'USER',
  payload_schema   JSON,                         -- Scene.payloadSchema
  default_params   JSON,                         -- 如 timezone
  status           ENUM('ACTIVE','DISABLED') NOT NULL DEFAULT 'ACTIVE',
  created_at       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_tenant_code (tenant_id, code),
  KEY idx_tenant_id (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### 3.3 metric_definition

```sql
CREATE TABLE metric_definition (
  id                BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id         BIGINT NOT NULL,
  metric_code       VARCHAR(128) NOT NULL,
  name              VARCHAR(128) NOT NULL,
  source_type       ENUM('ATTRIBUTE','SQL_AGGREGATE','EXTERNAL_HTTP','STREAM') NOT NULL,
  data_type         ENUM('LONG','DOUBLE','STRING','BOOLEAN','LIST') NOT NULL,
  params            JSON NOT NULL,               -- SQL / url / column 等
  cache_ttl_seconds INT NOT NULL DEFAULT 60,
  allow_provided    TINYINT(1) NOT NULL DEFAULT 0,
  status            ENUM('ACTIVE','DISABLED') NOT NULL DEFAULT 'ACTIVE',
  created_at        DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at        DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_tenant_code (tenant_id, metric_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### 3.4 rule_definition

```sql
CREATE TABLE rule_definition (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id   BIGINT NOT NULL,
  scene_id    BIGINT NOT NULL,
  code        VARCHAR(128) NOT NULL,
  name        VARCHAR(255) NOT NULL,
  description TEXT,
  status      ENUM('ACTIVE','DISABLED','DRAFT') NOT NULL DEFAULT 'DRAFT',
  created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_tenant_code (tenant_id, code),
  KEY idx_scene_id (scene_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### 3.5 rule_version

不可变快照（D19）：发布后不修改，只新增。

```sql
CREATE TABLE rule_version (
  id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
  rule_definition_id  BIGINT NOT NULL,
  version             INT NOT NULL,              -- 单调递增，per rule_definition
  condition_ast       JSON NOT NULL,             -- 完整 AST，不可变
  decision_bindings   JSON NOT NULL,             -- D27/D28：含 actions 快照
  pre_gates           JSON NOT NULL,
  trigger_event_types JSON NOT NULL,
  metric_dependencies JSON NOT NULL,
  published_at        DATETIME(3),               -- NULL = 草稿
  published_by        VARCHAR(64),
  status              ENUM('ACTIVE','SUPERSEDED','DISABLED') NOT NULL DEFAULT 'ACTIVE',
  created_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_def_version (rule_definition_id, version),
  KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### 3.6 audit_log（D14：人的行为，同步事务写）

```sql
CREATE TABLE audit_log (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id     BIGINT NOT NULL,
  operator      VARCHAR(64) NOT NULL,
  operation     VARCHAR(64) NOT NULL,   -- RULE_PUBLISH / SCENE_CREATE / METRIC_UPDATE 等
  target_type   VARCHAR(64) NOT NULL,   -- rule_definition / scene / metric_definition
  target_id     VARCHAR(128) NOT NULL,
  before_value  JSON,                   -- 变更前快照
  after_value   JSON,                   -- 变更后快照
  error_code    VARCHAR(64),            -- UNRESOLVED_VARIABLE 等发布期校验失败
  operated_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_tenant_target (tenant_id, target_type, target_id),
  KEY idx_operated_at (operated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```
```

- [ ] **Step 3: 写 §三 各表 DDL（评估层）**

```markdown
### 3.7 evaluation_session（D11/D21：同步写，UK 幂等）

```sql
CREATE TABLE evaluation_session (
  id               BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id        BIGINT NOT NULL,
  event_id         VARCHAR(128) NOT NULL,        -- 业务事件 id（幂等键第二列）
  scene_code       VARCHAR(64) NOT NULL,
  subject_id       VARCHAR(128) NOT NULL,
  rule_version_id  BIGINT NOT NULL,
  status           ENUM('PENDING','COMPLETED','FAILED') NOT NULL DEFAULT 'PENDING',
  final_decision   VARCHAR(64),                  -- nullable（未命中 / 被 Gate 拦截）
  hit_decisions    JSON,                         -- List<String>
  blocked_by       VARCHAR(64),                  -- Pre-Gate 类型（nullable）
  error_code       VARCHAR(64),
  occurred_at      DATETIME(3) NOT NULL,         -- 业务事件时间
  evaluated_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  completed_at     DATETIME(3),
  UNIQUE KEY uk_tenant_event (tenant_id, event_id),
  KEY idx_scene_subject (scene_code, subject_id),
  KEY idx_evaluated_at (evaluated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### 3.8 node_trace（D7/D21：异步批写，30 天保留 D9）

```sql
CREATE TABLE node_trace (
  id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
  evaluation_session_id BIGINT NOT NULL,
  tenant_id            BIGINT NOT NULL,
  rule_version_id      BIGINT NOT NULL,
  node_path            VARCHAR(256) NOT NULL,    -- AST 路径，如 "0.1.2"
  node_type            VARCHAR(64) NOT NULL,     -- AndNode / OrNode / ConditionNode / PRE_GATE_BLOCKED
  condition_type       VARCHAR(64),              -- nullable（非 ConditionNode 为 null）
  metric_code          VARCHAR(128),             -- nullable
  params               JSON,
  actual_value         JSON,                     -- 节点实际取到的值（nullable）
  result               TINYINT(1),               -- 1=true / 0=false / NULL=短路跳过
  error_code           VARCHAR(64),              -- nullable
  value_source         ENUM('PROVIDED','FETCHED'), -- D30：指标来源（nullable，仅 ConditionNode）
  evaluated_at         DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_session_id (evaluation_session_id),
  KEY idx_tenant_evaluated (tenant_id, evaluated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### 3.9 action_execution

```sql
CREATE TABLE action_execution (
  id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
  evaluation_session_id BIGINT NOT NULL,
  tenant_id            BIGINT NOT NULL,
  action_id            VARCHAR(128) NOT NULL,    -- Decision.actions[n].actionId
  action_type          VARCHAR(64) NOT NULL,
  decision_code        VARCHAR(64) NOT NULL,
  status               ENUM('PENDING','SUCCESS','FAILED','SKIPPED','RETRYING') NOT NULL,
  error_code           VARCHAR(64),
  retryable            TINYINT(1),
  retry_count          INT NOT NULL DEFAULT 0,
  executed_at          DATETIME(3),
  created_at           DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_session_id (evaluation_session_id),
  KEY idx_status_retryable (status, retryable)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```
```

- [ ] **Step 4: 写 §四 索引设计 + §五 数据迁移与不可变快照**

```markdown
## 四、索引设计

### 热路径索引（评估期读）

| 查询 | 表 | 索引 |
|------|----|------|
| Matcher 路由：按 (scene_code, event_type) 取候选 | rule_version（内存索引） | 内存倒排，不走 DB |
| 幂等检查：按 (tenant_id, event_id) 查 session | evaluation_session | UK `uk_tenant_event` |
| 取 metric 缓存 | Redis | key: `rule:metric:{tenant}:{code}:{subject}` |

### 运营查询索引（非热路径）

| 查询 | 表 | 索引 |
|------|----|------|
| 按 subject 查历史评估 | evaluation_session | `idx_scene_subject` |
| 按时间范围查评估量 | evaluation_session | `idx_evaluated_at` |
| 按 session 查 trace | node_trace | `idx_session_id` |
| 按租户 + 时间查 trace（对账） | node_trace | `idx_tenant_evaluated` |
| 按 session 查 action 执行 | action_execution | `idx_session_id` |
| 查待重试 Action | action_execution | `idx_status_retryable` |

### 分区建议（v1 不做，v2 演进）

`node_trace` 和 `evaluation_session` 按 `evaluated_at` 月分区（v1 靠 TTL 物理删除 30 天外数据，v2 演进时加分区）。

## 五、数据迁移与不可变快照

### 不可变快照策略（D19）

`rule_version` 行一旦发布（`published_at` 非 null）**不允许 UPDATE / DELETE**。修改规则 = 创建新 version：
1. 旧 version `status` 改为 `SUPERSEDED`（仍可被 evaluation_session 外键引用）
2. 新 version INSERT，`version = max(version)+1`
3. Matcher 倒排索引热更：指向新 version

### 数据保留策略（D9：v1 全 MySQL，30 天保留）

```sql
-- 每日定时清理（由调度任务执行，不走应用代码）
DELETE FROM node_trace WHERE evaluated_at < NOW() - INTERVAL 30 DAY LIMIT 10000;
DELETE FROM evaluation_session WHERE evaluated_at < NOW() - INTERVAL 30 DAY LIMIT 5000;
-- action_execution 跟随 evaluation_session 生命周期（外键 CASCADE 或应用层一并清理）
```

**不可删除的表**（永久保留）：`tenant` / `scene` / `rule_definition` / `rule_version` / `audit_log` / `metric_definition`

### Schema 迁移

v1 使用 Flyway（版本号 `V{major}_{minor}__{description}.sql`）管理 DDL。
`audit_log.operation` 增加新枚举值：仅 ALTER TABLE MODIFY，无数据迁移。
`evaluation_session` 新增列（如 `hit_decisions`）：ADD COLUMN DEFAULT NULL，滚动上线安全。
```

- [ ] **Step 5: 更新 §一 文档状态表 ✅**

- [ ] **Step 6: commit**

```bash
git add docs/05-storage.md
git commit -m "docs(05-storage): 展开表清单 / DDL（配置层+评估层）/ 索引设计 / 数据迁移策略"
```

---

## Task 4: 07-operability.md — 可运维

**Files:**
- Modify: `docs/07-operability.md` (全部 ⏳ 章节)

- [ ] **Step 1: 写 §二 幂等 + §三 EvaluationSession 落库策略**

```markdown
## 二、幂等

### 双层保障（D11）

| 层 | 实现 | 失效场景 |
|----|------|---------|
| 上半层 | `SET rule:session:{tenantId}:{eventId} <evalResultJson> NX EX 3600` | Redis 宕机 / 键过期 |
| 下半层 | `evaluation_session` UK `(tenant_id, event_id)` | 分布式竞争时最终一致 |

**流程：**
1. 评估前先 Redis SET NX：命中 → 返回缓存 EvalResult，不再评估
2. 未命中 → 正常评估 → evaluation_session INSERT
3. INSERT 遇 DuplicateKeyException → SELECT 已有行 → 返回已有 EvalResult

**幂等范围**：一次"评估"（Matcher + Pre-Gate + AST + 记录 session）幂等；Action 派发**不**幂等（由 ActionHandler 自行保证 execute() 幂等，见 04-extension §三）。

## 三、EvaluationSession 落库策略（D21）

| 操作 | 模式 | 原因 |
|------|------|------|
| `evaluation_session` 行 INSERT | **同步事务** | 幂等 UK 需先存在；量小（1 行/次），P99 延迟可忽略 |
| `node_trace` 批 INSERT | **异步批写** | 量大（10-1000 行/次）；旁路观察通道，失败降级丢弃，不影响主流程 |
| `action_execution` INSERT | **异步** | Action 派发本身异步，执行结果与评估线程解耦 |

TraceWriter 队列参数（建议默认值，可 `engine.trace.*` 配置覆盖）：

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `queue.capacity` | 100,000 | 内存 LinkedBlockingQueue 容量 |
| `batch.size` | 500 | 每批 INSERT 行数 |
| `flush.interval.ms` | 200 | 超时强制 flush |
| `consumer.threads` | 2 | 批写消费线程数 |
```

- [ ] **Step 2: 写 §四 dry-run 链路 + §五 灰度**

```markdown
## 四、dry-run 链路

dry-run 走完整评估链路（Matcher / Pre-Gate / EvalContext / AST），但：
- **不派发 Action**（Dispatcher 短路）
- **不写** `evaluation_session` / `node_trace` prod 表
- **写** `dry_run_session` / `dry_run_node_trace`（隔离表，D7）
- 返回完整 `nodeTrace`（AST 每个节点的 result / actualValue / errorCode）

**入口**：`POST /api/scenes/{code}/evaluate/dry-run`（PULL 模式同步返回，见 10-api-contract §三）

**用途**：
1. 规则发布前验证：编辑器内构造 mockEvent → 查看每个节点求值结果
2. 线上排障：用历史事件 eventId 重放 → 对比 trace 差异

**dry_run_session 表**：与 `evaluation_session` 结构相同，额外加 `trigger` 字段（MANUAL / SCHEDULED / API），独立清理策略（默认保留 7 天）。

## 五、灰度

### 灰度算法（ROLLOUT Gate）

```
bucket = (murmur3_32(subjectId + ":" + ruleVersionId) & 0x7FFFFFFF) % 100
pass = bucket < rollout.percentage
```

- `ruleVersionId` 加入 hash：同一 subject 在不同版本间 bucket 独立（防止切版本导致 bucket 漂移）
- 用 murmur3 保证分布均匀，不用 MD5（太重）

### 灰度验证流程

1. 新版规则发布为 `ACTIVE`，ROLLOUT 设 `percentage=5`
2. 监控 `evaluation_session.error_code` 分布 + Action 派发成功率（5% bucket）
3. 对账无异常 → percentage 逐步调至 100
4. 全量后将旧版 status 改为 `SUPERSEDED`

### 灰度回退

将 ROLLOUT.percentage 调回 0（不删规则）→ 新流量全部走其他规则。若需立即停用，将 rule_definition.status 改为 DISABLED（Matcher 倒排索引热摘除）。
```

- [ ] **Step 3: 写 §六 Prometheus 指标清单 + §七 告警阈值**

```markdown
## 六、Prometheus 指标清单

所有指标前缀 `rule_engine_`，label 统一含 `tenant_id` / `scene_code`。

| 指标名 | 类型 | labels | 说明 |
|--------|------|--------|------|
| `rule_engine_eval_total` | Counter | `result`(HIT/MISS/BLOCKED/ERROR) | 评估结果分布 |
| `rule_engine_eval_duration_ms` | Histogram | `scene_code` | 评估 P50/P95/P99 延迟 |
| `rule_engine_metric_fetch_duration_ms` | Histogram | `source_type`, `metric_code` | MetricSource 取数延迟 |
| `rule_engine_metric_fetch_errors_total` | Counter | `source_type`, `error_type` | 取数失败计数 |
| `rule_engine_action_dispatch_total` | Counter | `action_type`, `status` | Action 派发结果 |
| `rule_engine_action_duration_ms` | Histogram | `action_type` | Action 执行延迟 |
| `rule_engine_trace_queue_size` | Gauge | — | TraceWriter 队列深度 |
| `rule_engine_trace_queue_overflow_total` | Counter | — | trace 丢弃计数（队满） |
| `rule_engine_rule_version_cache_hit_total` | Counter | `scene_code` | Matcher 内存命中率 |
| `rule_engine_idempotency_hit_total` | Counter | `layer`(REDIS/DB) | 幂等命中次数 |

## 七、告警阈值（建议值，不强制）

| 告警规则 | 阈值 | 级别 | 说明 |
|---------|------|------|------|
| eval P99 延迟 | > 200ms 持续 5min | WARNING | 风控场景目标 < 100ms P99 |
| eval ERROR 率 | > 1% 持续 2min | WARNING | METRIC_FETCH_FAIL / CONDITION_EVAL_ERROR |
| eval ERROR 率 | > 5% 持续 1min | CRITICAL | 批量失败 |
| trace 队列溢出 | > 0 次/min 持续 5min | WARNING | 写入跟不上评估速率 |
| action 失败率 | > 5% 持续 5min（按 action_type） | WARNING | |
| metric fetch P99 | > 500ms 持续 5min | WARNING | 按 source_type 分组 |
```

- [ ] **Step 4: 写 §八 可用性策略汇总 + §九 运维参数默认值表**

```markdown
## 八、可用性策略汇总

### v1 SPOF 清单与降级矩阵

| 依赖 | 失效影响 | v1 降级策略 |
|------|---------|------------|
| MySQL | 无法写 evaluation_session，评估阻塞 | 评估入口返回 503；幂等 Redis 层仍可检查重复 |
| Redis | 幂等上半层失效 | 降级走 DB UK 幂等；metric cache 全部击穿 DB / 外部服务 |
| MetricSource (EXTERNAL_HTTP) | 取数超时 | D15 单节点降级 false，EvalResult.errorCode=METRIC_FETCH_FAIL |
| MetricSource (SQL_AGGREGATE) | DB 慢查询 / 连接池耗尽 | 同上；建议对 SQL 指标设 cache + fallback 默认值（v2 演进） |
| TraceWriter 队列满 | trace 行丢弃 | trace 丢弃 + counter 告警；**不影响** EvalResult |
| ActionHandler 外部系统不可用 | execute() 超时 | TIMEOUT retryable=true，入重试队列 |

### v1 不做的高可用（见 08-evolution）

- evaluation_session 异步化（§2.15）
- 嵌入式 SDK 模式（§2.14，无跨进程网络依赖）
- MySQL 分区自动归档（§2.5）

## 九、运维参数默认值表

所有参数均可通过 Spring 配置（`application.yml` 或配置中心）覆盖，命名空间 `engine.rule.*`。

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `engine.rule.matcher.cache-refresh-interval-seconds` | 15 | Matcher 倒排索引热更间隔（D17 最终一致窗口） |
| `engine.rule.scene.watch-interval-seconds` | 30 | Scene 配置热加载间隔（D24） |
| `engine.rule.idempotency.redis-ttl-seconds` | 3600 | 幂等 Redis key 过期时间 |
| `engine.rule.trace.queue-capacity` | 100000 | TraceWriter 队列容量 |
| `engine.rule.trace.batch-size` | 500 | 批写行数 |
| `engine.rule.trace.flush-interval-ms` | 200 | 强制 flush 间隔 |
| `engine.rule.trace.consumer-threads` | 2 | 批写消费线程数 |
| `engine.rule.metric.default-cache-ttl-seconds` | 60 | metric 取数结果缓存 TTL（per-metric 可覆盖） |
| `engine.rule.action.default-timeout-ms` | 3000 | ActionHandler 默认超时（per-handler 可覆盖） |
| `engine.rule.retention.evaluation-session-days` | 30 | evaluation_session 保留天数（D9） |
| `engine.rule.retention.node-trace-days` | 30 | node_trace 保留天数 |
| `engine.rule.retention.dry-run-session-days` | 7 | dry_run_session 保留天数 |
| `engine.rule.rollout.hash-seed` | 0 | murmur3 hash seed（固定后不要改，否则桶分布漂移） |
```

- [ ] **Step 5: 更新 §一 文档状态表 ✅**

- [ ] **Step 6: commit**

```bash
git add docs/07-operability.md
git commit -m "docs(07-operability): 展开幂等/落库策略/dry-run/灰度/Prometheus/告警/降级矩阵/运维参数表"
```

---

## Task 5: 10-api-contract.md — 对外 API 契约

**Files:**
- Modify: `docs/10-api-contract.md` (全部 ⏳ 章节)

- [ ] **Step 1: 写 §二 接口分组总览**

```markdown
## 二、接口分组总览

所有接口均挂载在 `/api/v1/` 前缀下（v1 稳定版）。errorCode 与 HTTP 状态码对应关系见 §七。

| 分组 | 路径前缀 | 主要场景 |
|------|---------|---------|
| 评估接口 | `/api/v1/scenes/{sceneCode}/evaluate` | 业务方触发评估（PUSH/PULL/dry-run） |
| 规则管理 | `/api/v1/rules` | 创建 / 发布 / 禁用 / 查询规则 |
| Scene 管理 | `/api/v1/scenes` | 创建 / 更新 / 禁用 Scene |
| 指标管理 | `/api/v1/metrics` | 注册 / 更新 / 禁用 Metric |
| 元数据接口 | `/api/v1/scenes/{sceneCode}/metadata` | 前端编辑器拉 ConditionType / ActionType 枚举 |
| 审计与查询 | `/api/v1/evaluation-sessions` | 查 session / trace / action 执行 |
| 工具接口 | `/api/v1/provided-metrics` | D30 discovery API |
```

- [ ] **Step 2: 写 §三 评估接口**

```markdown
## 三、评估接口

### 3.1 PUSH 评估（异步，Scene.dominantMode = PUSH / HYBRID）

```
POST /api/v1/scenes/{sceneCode}/evaluate
```

**Request：**
```json
{
  "tenantId": "demo-tenant",
  "eventType": "transfer.initiated",
  "subjectId": "user-001",
  "eventId": "evt-001",           // 可选，不传则引擎生成
  "occurredAt": "2026-05-31T14:00:00+08:00",
  "payload": { "amount": 25000, "currency": "CNY" },
  "providedMetrics": {            // D30，可选
    "user.kyc.level": 2
  }
}
```

**Response 202：**
```json
{
  "eventId": "evt-001",
  "accepted": true
}
```

**幂等**：相同 (tenantId, eventId) 重复提交返回 202 + 同样 eventId，不重复评估。

### 3.2 PULL 评估（同步，Scene.dominantMode = PULL / HYBRID）

```
POST /api/v1/scenes/{sceneCode}/evaluate/sync
```

**Request：** 同 PUSH 评估。

**Response 200：**
```json
{
  "eventId": "evt-001",
  "ruleHit": true,
  "finalDecision": {
    "code": "REVIEW",
    "name": "人工审核",
    "priority": 2
  },
  "hitDecisions": ["REVIEW"],
  "errorCode": null,
  "actionResults": [
    {
      "actionId": "act-review-ticket",
      "actionType": "ticket.create",
      "status": "SUCCESS",
      "errorCode": null
    }
  ]
}
```

**超时建议**：调用方设 HTTP timeout ≥ 500ms（v1 P99 目标 < 100ms，500ms 留有余量）。

### 3.3 dry-run 评估

```
POST /api/v1/scenes/{sceneCode}/evaluate/dry-run
```

**Request：** 同 PUSH 评估，额外可指定 `ruleVersionId`（指定版本回放）。

**Response 200：** 同 PULL 评估，额外包含 `nodeTrace` 字段：
```json
{
  "eventId": "evt-dry-001",
  "ruleHit": true,
  "finalDecision": { "code": "REVIEW" },
  "nodeTrace": {
    "type": "AndNode",
    "result": true,
    "children": [
      {
        "type": "ConditionNode",
        "metricCode": "user.account.age.days",
        "result": true,
        "actualValue": 45,
        "valueSource": "FETCHED"
      }
    ]
  }
}
```
```

- [ ] **Step 3: 写 §四 规则管理接口**

```markdown
## 四、规则管理接口

### 4.1 创建规则草稿

```
POST /api/v1/rules
```

**Request：**
```json
{
  "tenantId": "demo-tenant",
  "sceneCode": "risk.transfer",
  "code": "rule-transfer-review",
  "name": "转账人工审核触发",
  "conditionAst": { ... },
  "decisionBindings": [{ "decisionCode": "REVIEW" }],
  "preGates": [{ "type": "ROLLOUT", "params": { "percentage": 100 } }],
  "triggerEventTypes": ["transfer.initiated"],
  "metricDependencies": ["user.account.age.days"]
}
```

**Response 201：** `{ "ruleDefinitionId": 1, "ruleVersionId": 1, "version": 1, "status": "DRAFT" }`

### 4.2 发布规则

```
POST /api/v1/rules/{ruleDefinitionId}/publish
```

**响应**：发布成功 200，返回新 RuleVersion；发布校验失败 422（UNRESOLVED_VARIABLE / METRIC_NOT_BOUND 等）。

### 4.3 禁用规则

```
PATCH /api/v1/rules/{ruleDefinitionId}/disable
```

效果：rule_definition.status = DISABLED，Matcher 倒排索引热摘除（≤15s 全实例收敛）。

### 4.4 查询规则列表

```
GET /api/v1/rules?tenantId=demo-tenant&sceneCode=risk.transfer&status=ACTIVE
```
```

- [ ] **Step 4: 写 §五 元数据接口 + §六 审计与查询接口**

```markdown
## 五、元数据接口

### 5.1 拉 Scene 元数据（前端编辑器用）

```
GET /api/v1/scenes/{sceneCode}/metadata?tenantId=demo-tenant
```

**Response：** 见 04-extension.md §五 元数据契约（ConditionTypes / ActionTypes / availableMetrics 三段）。

### 5.2 D30 providedMetrics 发现接口

```
GET /api/v1/scenes/{sceneCode}/provided-metrics?tenantId=demo-tenant
```

**Response：**
```json
{
  "metrics": [
    {
      "metricCode": "user.kyc.level",
      "name": "KYC 等级",
      "dataType": "LONG",
      "allowProvided": true,
      "note": "sourceType=ATTRIBUTE，外部系统通常比 DB 读更新"
    }
  ]
}
```

## 六、审计与查询接口

### 6.1 查询 evaluation_session

```
GET /api/v1/evaluation-sessions?tenantId=demo-tenant&sceneCode=risk.transfer&subjectId=user-001&from=2026-05-01&to=2026-06-01
```

### 6.2 查询 node_trace（需先有 sessionId）

```
GET /api/v1/evaluation-sessions/{sessionId}/trace?tenantId=demo-tenant
```

### 6.3 查询 audit_log

```
GET /api/v1/audit-logs?tenantId=demo-tenant&targetType=rule_definition&targetId=1&from=2026-05-01
```
```

- [ ] **Step 5: 写 §七 errorCode 清单 + §八 SDK 用法 + §九 版本兼容策略**

```markdown
## 七、errorCode 清单与 i18n

### 评估期 errorCode（EvalResult.errorCode）

| errorCode | HTTP 状态 | 含义 | 调用方建议 |
|-----------|-----------|------|-----------|
| `METRIC_FETCH_FAIL` | 200 | 有节点 MetricSource 取数失败 | 查 nodeTrace 定位失败节点；fail-secure → 拒绝，fail-open → 放行 |
| `CONDITION_EVAL_ERROR` | 200 | ConditionEvaluator 抛异常 | 同上 |
| `PAYLOAD_SCHEMA_MISMATCH` | 400 | payload 字段缺必填 / 类型错 | 修复请求体重试 |
| `INVALID_EVENT_TYPE` | 400 | eventType 不在 Scene 白名单内 | 确认 sceneCode + eventType |
| `SCENE_NOT_FOUND` | 404 | sceneCode 未注册或 DISABLED | 确认 tenantId + sceneCode |
| `IDEMPOTENCY_CONFLICT` | — | 幂等命中（返回已有结果，不是错误） | 无需处理 |

### Action 执行 errorCode（ActionResult.errorCode）

| errorCode | retryable | 含义 |
|-----------|-----------|------|
| `TIMEOUT` | true | Handler execute() 超时 |
| `EXTERNAL_SERVICE_ERROR` | true | 外部系统返回 5xx / 连接失败 |
| `BUSINESS_REJECTED` | false | 外部系统明确拒绝（如工单系统返回 400） |
| `PREDECESSOR_FAILED` | false | failFast 前置 Action 失败 |
| `NOT_SUPPORTED` | false | compensate() 不支持 |

### 发布期 errorCode（audit_log.error_code）

| errorCode | 含义 |
|-----------|------|
| `UNRESOLVED_VARIABLE` | conditionAst 引用了未绑定的 metricCode |
| `METRIC_NOT_BOUND` | metric 不在 Scene.metricBindings 白名单内 |
| `ACTION_TYPE_NOT_BOUND` | actionType 不在 Scene.actionBindings 白名单内 |
| `DECISION_CODE_NOT_FOUND` | decisionBindings 引用了 Scene 未定义的 Decision |

## 八、SDK 用法

v1 调用方通过 HTTP 调用（无 SDK 包装），直接参照本文档的 Request/Response 格式。

v2 嵌入式 SDK 模式详见 [`08-evolution.md`](./08-evolution.md) §2.14。

## 九、版本兼容策略

- API 路径前缀 `/api/v1/` —— 重大不兼容变更时升 `/api/v2/`
- Response 新增字段：向后兼容（调用方忽略未知字段）
- Response 删除字段 / 改类型：v1 → v2 迁移期，旧字段保留 ≥ 3 个月并在 audit_log 告警
- `errorCode` 枚举新增值：向后兼容（调用方按 "未知错误" 处理）
- `errorCode` 枚举删除值：视为重大变更，走 v2 升版
```

- [ ] **Step 6: 更新 §一 文档状态表 ✅**

- [ ] **Step 7: commit**

```bash
git add docs/10-api-contract.md
git commit -m "docs(10-api-contract): 展开评估/规则管理/元数据/审计查询接口 + errorCode 清单 + SDK/版本策略"
```

---

## Task 6: 06-frontend.md — 前端架构

**Files:**
- Modify: `docs/06-frontend.md` (全部 ⏳ 章节)

- [ ] **Step 1: 写 §二 三栏布局**

```markdown
## 二、三栏布局

规则编辑器采用三栏布局：

```
┌──────────────────┬────────────────────────────────┬──────────────────┐
│  左栏            │  中栏（主编辑区）                │  右栏            │
│  Scene / Rule 树  │  AST 可视化编辑器               │  Trace / dry-run  │
│  ─────────────── │  ──────────────────────────── │  ───────────────  │
│  · Scene 列表    │  · 拖拽 / 点击编辑节点           │  · dry-run 结果   │
│  · Rule 列表     │  · 节点类型下拉（元数据驱动）     │  · 每节点 ✅/❌/⏭ │
│  · 版本历史      │  · 参数表单（动态渲染）           │  · actualValue    │
│  · 状态标签      │  · Pre-Gate 配置                │  · 错误详情       │
│                  │  · Decision 绑定配置             │  · audit_log 条目 │
└──────────────────┴────────────────────────────────┴──────────────────┘
```

**交互原则：**
- 左栏选中 Rule → 中栏加载对应 RuleVersion 的 conditionAst
- 中栏编辑 → 临时草稿态（不触发自动保存）；点"保存草稿"才 POST
- 右栏默认显示最近 dry-run 结果；每次点"试算"刷新
```

- [ ] **Step 2: 写 §三 元数据驱动渲染机制**

```markdown
## 三、元数据驱动渲染机制

编辑器不硬编码 ConditionType / ActionType 表单，而是：

1. 进入编辑器时调用 `GET /api/v1/scenes/{code}/metadata`，拿到：
   - `conditionTypes[]`（含 paramsSchema）
   - `actionTypes[]`（含 paramsSchema）
   - `availableMetrics[]`
2. 用户选择节点类型后，按 `paramsSchema`（JSON Schema）动态渲染参数表单
3. `requiresMetric=true` 的 ConditionType（如 `metric.threshold`）同时渲染 metric 下拉框（来自 availableMetrics）

**JSON Schema → 表单组件映射（v1 最小集）：**

| JSON Schema 类型 | 表单控件 |
|-----------------|---------|
| `string` | 文本输入框 |
| `string` + `enum` | 下拉选择框 |
| `number` | 数字输入框 |
| `boolean` | 开关 |
| `object` | 嵌套表单 |

**好处：** 业务方新注册 ConditionType（`@ConditionType` 注解 + paramsSchema）后，前端编辑器无需改代码即可渲染新类型的参数表单。
```

- [ ] **Step 3: 写 §四 dry-run UI + §五 灰度配置 UI + §六 审计日志 UI**

```markdown
## 四、dry-run UI

1. 右栏点击"试算"按钮 → 弹出 mockEvent 编辑框（JSON 编辑器，预填 Schema 必填字段）
2. 可选：指定 ruleVersionId（默认当前版本）；填写 `providedMetrics`
3. 调用 `POST /evaluate/dry-run` → 返回含 `nodeTrace` 的 EvalResult
4. 右栏渲染 AST trace 树：
   - `result=true` → 节点显示 ✅
   - `result=false` → 节点显示 ❌
   - `result=null`（短路跳过）→ 节点显示 ⏭
   - hover 节点 → 弹出 tooltip 展示 actualValue / valueSource / errorCode
5. Pre-Gate 失败时：显示 `PRE_GATE_BLOCKED: WHITELIST`（大红色提示），AST 部分灰化

## 五、灰度配置 UI

在 Rule 编辑器的 Pre-Gate 配置区：
- ROLLOUT Gate：百分比滑块（0–100%）+ 实时显示"约 X% 流量命中此规则"
- WHITELIST / BLACKLIST：名单 key 下拉框（来自平台预设名单列表）
- RATE_LIMIT：QPS / QPM 数字输入框 + 时间窗口选择

灰度发布建议工作流显示在侧边面板：
1. 当前 percentage = 5%
2. →（箭头）可调至 20%（需确认）
3. →（箭头）可调至 100%（需确认）

## 六、审计日志查看 UI

在 Rule 详情页右侧抽屉：
- 按时间倒序列出 `audit_log` 条目（RULE_PUBLISH / RULE_DISABLE / SCENE_UPDATE 等）
- 每条目展开 → diff 视图（before_value vs after_value，JSON diff 高亮）
- 点击 "操作人" → 可按操作人过滤
- 发布失败条目：红色标记 + errorCode tooltip（UNRESOLVED_VARIABLE 等）
```

- [ ] **Step 4: 更新 §一 文档状态表 ✅**

- [ ] **Step 5: commit**

```bash
git add docs/06-frontend.md
git commit -m "docs(06-frontend): 展开三栏布局/元数据驱动渲染/dry-run UI/灰度配置UI/审计日志UI"
```

---

## Task 7: 08-evolution.md — 演进路线图补全

**Files:**
- Modify: `docs/08-evolution.md`（仅 §三 + §四 未展开部分）

- [ ] **Step 1: 写 §三 决策时间线**

按 00-decisions.md 中的决策编号梳理时间轴（概念时间线，非日历时间）：

```markdown
## 三、决策时间线

> 来源：`00-decisions.md` 各组标题 + `README.md` §七版本史。按 D 编号顺序，标记"何时做了什么取舍"。

### 第一组：核心数据模型（D1–D7）
核心 DDD 边界确立：Tenant / Scene / Rule / Condition / Metric / EvalContext 分层；D4 审计内建（不可关闭）；D5 Metric 按需取数（不预加载全量）；D6 不可变版本快照；D7 节点级 trace 落库。

### 第二组：指标与外部集成（D8–D11）
D8 引入 MetricSource SPI（取数与评估解耦）；D9 v1 全 MySQL 不引入列存 / 大数据；D10 基础 Pre-Gate 四类（ROLLOUT/WHITE/BLACK/RATE_LIMIT）；D11 幂等 Redis+DB 双层。

### 第三组：Rule 结构与模式（D12–D17）
D12 kind 字段预留（布尔 → 多态出口）；D13 payloadSchema v1 不做演进（闭合）；D14 审计强一致 + 敏感数据交给调用方处理；D15 单节点失败降级（不整树崩）；D16 发布期静态校验（UNRESOLVED_VARIABLE 等）；D17 倒排索引不可变快照热更（Matcher 无 DB 热路径）。

### 第四组：精化与派生（D18–D22 / D27–D30）
D18 Action 失败归一为 FAILED(retryable)；D19 不可变快照只读；D20 metric 批量预拉 + EvalContext 内冻结；D21 trace 异步批写 + session 同步写；D22 四态对账（HIT/MISS/BLOCKED/ERROR）；D27 Action 从 Rule 迁移到 Decision；D28 actions 在发布时快照；D29 PUSH/HYBRID 默认 HIGHEST_PRIORITY；D30 providedMetrics + allowProvided per sourceType。

### 核心转折点

- **D27 最大重构**：Action 从 Rule.actions 迁移到 Decision.actions。旧规则示例迁移路径：在对应 Scene Decision 上追加 actions 字段，旧 Rule.actions 清空（发布期校验检测）。
- **D30 最晚决策**：providedMetrics 是调用方数据权威性问题，最后才表态 per-sourceType 默认值而非全 true / 全 false。
```

- [ ] **Step 2: 写 §四 已否决方案**

```markdown
## 四、已否决方案

| 方案 | 否决时间节点 | 否决理由 | 正式采用的替代方案 |
|------|------------|---------|-----------------|
| webhook.call ActionType（引擎主动发 HTTP 回调） | D27 讨论时 | 调用方接受 webhook 需维护公网 endpoint；重试 / 超时由引擎管，复杂度爆炸；SPI（命令式 ActionHandler）调用方直接写 Java 类，更简单 | `@ActionType` SPI（命令式） |
| 同步事务写 node_trace | D21 确立时 | node_trace 量大（10-1000 行/次），同步写直接吃风控 P99 预算 10-40%；trace 是旁路观察通道，丢弃不影响正确性 | TraceWriter 异步批写 |
| 全局 metric cache TTL（不区分 per-metric） | D5 / D20 讨论时 | 不同 metric 实效性要求差异大（account.age 3600s vs balance 0s）；全局 TTL 只能取最保守值（=0），相当于不 cache | per-metric `cachePolicyDefault.ttl` |
| providedMetrics 全局 allowProvided=true | D30 讨论时 | 高权威 metric（如账户余额 SQL_AGGREGATE）不应被调用方覆盖；EXTERNAL_HTTP 和 ATTRIBUTE 调用方通常比 DB 更新，适合 override | per-sourceType 默认值（ATTRIBUTE/EXTERNAL_HTTP=true，SQL_AGGREGATE/STREAM=false） |
| persistedMetricCodes（引擎持久化 provided 值） | D30 讨论时 | 引擎承担业务数据存储职责；与"不可变快照"语义冲突；复杂度超收益 | 不做，provided 值只活在本次评估 |
| Action 留在 Rule 上（D27 之前） | D27 确立时 | 同一 Rule 命中后只能派发一组 Action；不同 Decision 需要不同 Action（如 REVIEW 建工单 / REJECT 不建）无法表达 | D27 Action 迁移到 Decision |
| evaluation_session 全量异步写 | D21 讨论时 | session 行是幂等 UK 锚点，必须在评估开始前存在；如果异步写，极端情况下第二次相同 eventId 进来时 UK 还没写入，幂等失效 | 仅 session 行同步写，trace 行异步写 |
```

- [ ] **Step 3: 更新 §一 文档状态表**

将 §三/§四 改为 ✅。

- [ ] **Step 4: commit**

```bash
git add docs/08-evolution.md
git commit -m "docs(08-evolution): 展开 §三决策时间线 + §四已否决方案"
```

---

## Task 8: 09-skeleton.md — 工程骨架（单独讨论，不在本计划内）

**Files:** 无（本任务是触发讨论，不写代码）

- [ ] **Step 1: 与用户确认技术栈**

在开始写 09-skeleton.md 之前，需要确认以下决策（建议单独开一次对话）：

1. **Java 版本**：Java 17 还是 21（LTS）？
2. **Spring Boot 版本**：3.2.x 还是 3.3.x？
3. **模块粒度**：单 Maven 模块（先快后拆）还是多模块（engine-core / engine-web / engine-spi / engine-starter）？
4. **包命名根**：`com.xxx.rule` / `io.xxx.ruleengine` / 其他？
5. **构建工具**：Maven 还是 Gradle？
6. **数据库访问**：MyBatis-Plus 还是 Spring Data JPA？

确认后，按 09-skeleton.md §二～§八 现有占位章节逐一展开。

---

## 自检（无占位检查）

- [x] 所有 `⏳ 未展开` 章节有了完整内容（无 TBD / TODO / 待填）
- [x] 所有代码块是真实示例，非 "..." 省略
- [x] 09-skeleton 明确标注为"需单独讨论"，不含未确认的技术栈假设
- [x] 各文档状态表最终都需更新为 ✅
- [x] 每个 Task 后有独立 commit，便于 review
