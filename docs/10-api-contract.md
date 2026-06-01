# 10 — 对外 API 契约

> **位置定位**：本文档承载 rule-engine 对调用方的**外部接口契约**——HTTP / RPC / SDK 签名 / 请求响应 DTO / errorCode + i18n 清单。前后端联调、对接方接入、SDK 升级都以本文档为准。
>
> **前置阅读**：[`01-concepts.md`](./01-concepts.md) §3.3 RuleEvent / §3.4 Rule（含 EvalResult 输出契约）/ §3.8 Context（含 EvalContext 标准字段）、[`04-extension.md`](./04-extension.md) §五 元数据契约
>
> **解决什么疑问**："调用方要传什么 / 收到什么？""有哪些错误码 / 怎么对应文案？""SDK 怎么用？""dry-run 接口签名是什么？""前端拉元数据走哪个接口？"
>
> **职责边界**——
> - ✅ 对外接口签名 / 请求响应 DTO / errorCode 清单 / SDK 用法 / 版本兼容策略
> - ❌ 不写内部 SPI 接口（→ 04-extension）、不写概念字段语义（→ 01-concepts 字段表，本文档只贴 API 字段命名 + JSON 类型）、不写运行时调度（→ 02-runtime）、不写表结构（→ 05-storage）

---

## 一、文档状态

| 章节 | 状态 |
|------|------|
| §二 接口分组总览 | ✅ |
| §三 评估接口 | ✅ |
| §四 规则管理接口 | ✅ |
| §五 元数据接口 | ✅ |
| §六 审计与查询接口 | ✅ |
| §七 errorCode 清单与 i18n | ✅ |
| §八 SDK 用法 | ✅ |
| §九 版本兼容策略 | ✅ |

---

## 二、接口分组总览

所有接口均挂载在 `/api/v1/` 前缀下（v1 稳定版）。errorCode 与 HTTP 状态码对应关系见 §七。

| 分组 | 路径前缀 | 主要场景 |
|------|---------|---------|
| 评估接口 | `/api/v1/rule/` | 业务方触发评估（PUSH/PULL/dry-run） |
| 规则管理 | `/api/v1/rules` | 创建 / 发布 / 禁用 / 查询规则 |
| Scene 管理 | `/api/v1/scenes` | 创建 / 更新 / 禁用 Scene |
| 指标管理 | `/api/v1/metrics` | 注册 / 更新 / 禁用 Metric |
| 元数据接口 | `/api/v1/scenes/{sceneCode}/metadata`，`/api/v1/scenes/{sceneCode}/provided-metrics` | 前端编辑器拉 ConditionType / ActionType 枚举；D30 allowProvided 发现 |
| 审计与查询 | `/api/v1/evaluation-sessions` | 查 session / trace / action 执行 |

---

## 三、评估接口

### 3.1 PUSH 评估（异步，Scene.dominantMode = PUSH / HYBRID）

```
POST /api/v1/rule/event
```

**Request：**
```json
{
  "tenantId": "demo-tenant",
  "sceneCode": "risk.transfer",
  "eventType": "transfer.initiated",
  "subjectId": "user-001",
  "eventId": "evt-001",
  "occurredAt": "2026-05-31T14:00:00+08:00",
  "payload": { "amount": 25000, "currency": "CNY" },
  "providedMetrics": {
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

**幂等**：相同 (tenantId, eventId) 重复提交返回 202 + 同样 eventId，不重复评估（D11）。

### 3.2 PULL 评估（同步，Scene.dominantMode = PULL / HYBRID）

```
POST /api/v1/rule/evaluate
```

**Request：** 同 3.1 PUSH 评估。

**Response 200（PULL Scene 示例）：**
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
  "actionResults": []
}
```

> **注**：PULL Scene 的 `Decision.actions` 必须为空（发布校验拒绝），`actionResults` 始终为空数组；HYBRID Scene 的 `actionResults` 含异步派发状态（status 可能为 `SUCCESS` / `FAILED` / `PENDING`，取决于 Action 是否已完成）。

**超时建议**：调用方设 HTTP timeout ≥ 500ms（v1 P99 目标 < 100ms，500ms 留有余量）。

**失败语义（D15）**：`errorCode` 非 null 表示评估期有节点出错；调用方按 fail-secure（拒绝）或 fail-open（放行）自行决策，引擎不代为决定。

### 3.3 dry-run 评估

```
POST /api/v1/rule/dry-run
```

**Request：** 同 3.1，额外可传 `ruleVersionId`（指定版本回放，null = 使用当前版本）。

**Response 200：** 同 3.2，额外包含 `nodeTrace` 字段；`actionResults` 中所有 Action 显示 `SKIPPED`（不实际派发）：
```json
{
  "eventId": "evt-dry-001",
  "ruleHit": true,
  "finalDecision": { "code": "REVIEW" },
  "actionResults": [
    {
      "actionId": "act-review-ticket",
      "actionType": "ticket.create",
      "status": "SKIPPED",
      "errorCode": null
    }
  ],
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

若 handler 未实装 dryRun() 接口，对应 Action 显示 `status=SKIPPED, errorCode=DRY_RUN_NOT_IMPLEMENTED`（D7）。见 07-operability §四。

---

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
  "conditionAst": { "type": "AndNode", "children": [] },
  "decisionBindings": [{ "decisionCode": "REVIEW" }],
  "preGates": [{ "type": "ROLLOUT", "params": { "percentage": 100 } }],
  "triggerEventTypes": ["transfer.initiated"],
  "metricDependencies": ["user.account.age.days"]
}
```

**Response 201：**
```json
{ "ruleDefinitionId": 1, "ruleVersionId": 1, "version": 1, "status": "DRAFT" }
```

### 4.2 发布规则

```
POST /api/v1/rules/{ruleDefinitionId}/publish
```

**响应**：发布成功 200，返回新 `ruleVersion`；发布校验失败 422 + errorCode（`UNRESOLVED_VARIABLE` / `METRIC_NOT_BOUND` 等，见 §七）。

### 4.3 禁用规则

```
PATCH /api/v1/rules/{ruleDefinitionId}/disable
```

效果：`rule_definition.status = DISABLED`，Matcher 倒排索引热摘除（≤15s 全实例收敛，D17）。

### 4.4 查询规则列表

```
GET /api/v1/rules?tenantId=demo-tenant&sceneCode=risk.transfer&status=PUBLISHED
```

**Response 200：** 分页列表，含 `ruleDefinitionId / code / name / status / currentVersion / publishedAt`。

---

## 五、元数据接口

### 5.1 拉 Scene 元数据（前端编辑器）

```
GET /api/v1/scenes/{sceneCode}/metadata?tenantId=demo-tenant
```

**Response：** 见 `04-extension.md §五` 元数据契约（`conditionTypes` / `actionTypes` / `availableMetrics` 三段）。

### 5.2 D30 providedMetrics 发现接口

```
GET /api/v1/scenes/{sceneCode}/provided-metrics?tenantId=demo-tenant
```

**Response 200：**
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

---

## 六、审计与查询接口

### 6.1 查询 evaluation_session

```
GET /api/v1/evaluation-sessions?tenantId=demo-tenant&sceneCode=risk.transfer&subjectId=user-001&from=2026-05-01T00:00:00Z&to=2026-06-01T00:00:00Z
```

**Response 200：** 分页列表，含 `sessionId / eventId / status / finalDecision / startedAt / evalDurationMs`。

### 6.2 查询 node_trace

```
GET /api/v1/evaluation-sessions/{sessionId}/trace?tenantId=demo-tenant
```

**Response 200：** AST 节点树结构，每节点含 `type / result / actualValue / errorCode / valueSource`。

### 6.3 查询 audit_log

```
GET /api/v1/audit-logs?tenantId=demo-tenant&targetType=rule_definition&targetId=1&from=2026-05-01T00:00:00Z
```

**Response 200：** 分页列表，含 `actor / actorType / action / targetType / targetId / beforeSnapshot / afterSnapshot / operatedAt`。

---

## 七、errorCode 清单与 i18n

### 评估期 errorCode（EvalResult.errorCode）

| errorCode | HTTP 状态 | 含义 | 调用方建议 |
|-----------|-----------|------|-----------|
| `METRIC_FETCH_FAIL` | 200 | 有节点 MetricSource 取数失败（D15） | 查 nodeTrace 定位失败节点；fail-secure → 拒绝，fail-open → 放行 |
| `CONDITION_EVAL_ERROR` | 200 | ConditionEvaluator 抛异常（D15） | 同上 |
| `PAYLOAD_SCHEMA_MISMATCH` | 400 | payload 字段缺必填 / 类型错 | 修复请求体重试 |
| `INVALID_EVENT_TYPE` | 400 | eventType 不在 Scene 白名单内 | 确认 sceneCode + eventType |
| `SCENE_NOT_FOUND` | 404 | sceneCode 未注册或 DISABLED | 确认 tenantId + sceneCode |

### Action 执行 errorCode（ActionResult.errorCode）

| errorCode | retryable | 含义 |
|-----------|-----------|------|
| `TIMEOUT` | true | Handler execute() 超时（D18） |
| `EXTERNAL_SERVICE_ERROR` | true | 外部系统返回 5xx / 连接失败 |
| `BUSINESS_REJECTED` | false | 外部系统明确拒绝（如工单系统返回 400） |
| `PREDECESSOR_FAILED` | false | failFast 前置 Action 失败（D18） |
| `QUEUE_OVERFLOW` | true | Action Dispatcher 队列满，Action 已丢弃入重试队列（D20） |
| `HANDLER_EXCEPTION` | false | ActionHandler.execute() 抛出未捕获异常（D18） |
| `DRY_RUN_NOT_IMPLEMENTED` | false | handler 未实装 dryRun()，dry-run 时 Dispatcher 短路返回 SKIPPED（D7） |
| `NOT_SUPPORTED` | false | compensate() 不支持 |

### 发布期 errorCode（audit_log.after_snapshot.errorCode）

| errorCode | 含义 |
|-----------|------|
| `UNRESOLVED_VARIABLE` | conditionAst / pre_gates / payload 引用了未绑定的变量（metricCode、payload 字段、EvalContext 标准字段均在校验范围内） |
| `METRIC_NOT_BOUND` | metric 不在 Scene.scene_metric_binding 白名单内 |
| `ACTION_TYPE_NOT_BOUND` | actionType 不在 Scene.scene_action_binding 白名单内 |
| `DECISION_CODE_NOT_FOUND` | decisionBindings 引用了 Scene 未定义的 Decision |
| `ZOMBIE_PUBLISHING` | 后台清扫检测到 PUBLISHING 状态残留超时，强制修正为 PUBLISH_FAILED（D19） |
| `HANDLER_EXCEPTION` | 发布事务内未分类异常，`after_snapshot` 含 stackTrace 摘要 |

---

## 八、SDK 用法

v1 调用方通过 HTTP 直接调用本文档各接口，无独立 SDK 包装。

v2 嵌入式 SDK 模式详见 [`08-evolution.md`](./08-evolution.md) §2.14（评估引擎下沉到调用方进程，消除跨进程网络依赖）。

---

## 九、版本兼容策略

- API 路径前缀 `/api/v1/` —— 重大不兼容变更时升 `/api/v2/`
- Response 新增字段：向后兼容（调用方忽略未知字段）
- Response 删除字段 / 改类型：v1 → v2 迁移期，旧字段保留 ≥ 3 个月
- `errorCode` 枚举新增值：向后兼容（调用方按"未知错误"处理）
- `errorCode` 枚举删除值：视为重大变更，走 v2 升版

---

## 十、维护原则

- 本文档**唯一持有对外 API 字段命名**——01-concepts 字段表与本文档 API 字段命名必须保持一一对应（语义在 01-concepts，JSON 字段名在本表）。
- 新增对外接口必须在 §二 + 对应分组登记 + §七 errorCode 同步。
- API 变更走 §九 版本兼容策略，破坏性变更必须先在 [`README.md`](./README.md) §七 版本史登记。
- 前后端联调 / 接入方接入以本文档为契约依据，发现描述与实际不一致以本文档为准（实现要回头改）。
