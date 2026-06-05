# 10 — 对外 API 契约

> **位置定位**：本文档承载 rule-engine 对调用方的**外部接口契约**——HTTP / RPC / SDK 签名 / 请求响应 DTO / errorCode + i18n 清单。前后端联调、对接方接入、SDK 升级都以本文档为准。
>
> **前置阅读**：[`01-concepts.md`](./01-concepts.md) §3.3 RuleEvent / §3.4 Rule（含 EvalResult 输出契约）/ §3.8 EvalContext（含标准字段）、[`04-extension.md`](./04-extension.md) §五 元数据契约
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
| 审计与查询 | `/api/v1/evaluation-sessions`，`/api/v1/rules/{id}/sessions` | 查 session / trace / action 执行；按规则查历史触发记录 |

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

**幂等**：相同 (tenantId, eventId) 重复提交返回 202 + 同样 eventId，不重复评估（D23）。

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
    "priority": 2,
    "fromRuleVersionId": 42
  },
  "hitDecisions": [{"code": "REVIEW", "name": "人工审核", "priority": 2, "fromRuleVersionId": 42}],
  "nodeTrace": null,
  "errorCode": null,
  "actionResults": []
}
```

> **注**：PULL Scene 的 `Decision.actions` 必须为空（发布校验拒绝），`actionResults` 始终为空数组；HYBRID Scene 的 Action **异步**派发（评估线程入队后即返回，不等待 Handler 完成，见 02-runtime §二约束），`actionResults` 为空数组（异步派发进行中时尚无结果）或含 `status=SUCCESS / FAILED / SKIPPED` 的记录（Handler 已执行完毕时）；`PENDING` 仅是 `action_execution` DB 过程态，不出现在 API 响应的 `ActionResult.status` 枚举中。

**超时建议**：调用方设 HTTP timeout ≥ 500ms（v1 P99 目标 < 500ms；风控高频场景 < 100ms 目标见 [`07-operability.md`](./07-operability.md) §七）。

**查 trace**：PULL 评估不在响应体直接返回 sessionId；调用方若需查 node_trace，通过 `GET /api/v1/evaluation-sessions?tenantId=&eventId={eventId}` 取对应 session，再调 §6.2 `GET /api/v1/evaluation-sessions/{sessionId}/trace`。

**失败语义（D15）**：`errorCode` 非 null 表示评估期有节点出错；调用方按 fail-secure（拒绝）或 fail-open（放行）自行决策，引擎不代为决定。

**`ruleHit=false` 三种情形**：
- **Pre-Gate 全部拦截**：`ruleHit=false`，`finalDecision=null`，`evaluation_session.status=BLOCKED`，`blocked_by` 含首个拦截门类型（evaluation_session 已落库）
- **无候选规则**：`ruleHit=false`，`finalDecision=null`，**evaluation_session 不落库**（Matcher 在阶段②短路返回，不进入 EvalContext 构建，见 02-runtime §3.2）
- **AST 不满足**：`ruleHit=false`，`finalDecision=null`，`evaluation_session.status=MISS`（AST 求值返回 false，evaluation_session 已落库）

调用方若需区分三种情形，查 `evaluation_session.status` + `blocked_by`；无候选规则时 evaluation_session 不存在，查询返回空。

### 3.3 dry-run 评估

```
POST /api/v1/rule/dry-run
```

**Request：** 同 3.1，额外可传 `ruleVersionId`（指定版本回放，null = 使用当前版本）。

**Response 200：** 同 3.2，额外包含 `nodeTrace` 字段；v1.5（D7）已全量实装 `dryRun()`，`actionResults` 返回真实预览 ActionResult（不实际派发）：
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
      "errorCode": "DRY_RUN_NOT_IMPLEMENTED"
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

v1.5（D7）已全量实装 `dryRun()`，`DRY_RUN_NOT_IMPLEMENTED` errorCode 不再产生。见 07-operability §四。

---

## 四、规则管理接口

### 4.1 创建规则草稿

> **已实装（v2）**：`POST /api/v1/rules`，返回 201 + `DraftCreatedResult`；重复 code 前置校验，Scene 不存在返回 400。

```
POST /api/v1/rules
```

**Request：**
```json
{
  "tenantId": "1",
  "sceneCode": "risk.transfer",
  "code": "rule-transfer-review",
  "name": "转账人工审核触发",
  "kind": "AST_BOOLEAN",
  "conditionAst": { "type": "AndNode", "children": [] },
  "decisionBindings": [{ "decisionCode": "REVIEW" }],
  "preGates": [{ "type": "ROLLOUT", "params": { "percentage": 100 } }],
  "triggerEventTypes": ["transfer.initiated"]
}
```

**请求体字段说明：**

| 字段            | 类型   | 必填 | 说明 |
|-----------------|--------|------|------|
| `tenantId`      | String | 是   | 数字字符串，对应 `tenant.id` 主键 |
| `sceneCode`     | String | 是   | 规则所属场景编码 |
| `code`          | String | 是   | 规则业务编码，同 tenantId + sceneCode 下唯一 |
| `name`          | String | 是   | 规则显示名称 |
| `kind`          | String | 否   | 规则类型，默认 `AST_BOOLEAN`；可选值：`AST_BOOLEAN` / `SCORECARD` / `DECISION_TREE` / `DECISION_TABLE` |
| `conditionAst`  | Object | 否   | 条件 AST 根节点，缺省存空 AST |
| `decisionBindings` | Array | 否 | 命中决策绑定列表，缺省空数组 |
| `preGates`      | Array  | 否   | 前置门列表，缺省空数组 |
| `triggerEventTypes` | Array | 否 | 触发事件类型白名单，缺省空数组 |

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
POST /api/v1/rules/{ruleDefinitionId}/disable
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

> **已实装（v2 第二阶段）**

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

**Response 200：** 扁平 trace 列表，按 `node_path` 字典序排列。向后兼容保留。

**嵌套树端点（推荐）**：`GET /api/v1/evaluation-sessions/{sessionId}/trace/tree?tenantId=`  
返回格式与 §3.3 dry-run `nodeTrace` 相同（嵌套结构），已实装（v2 第二阶段）。数据来源是 `node_trace` 表的扁平行，由 API 层按 `node_path` 重建树后返回。

### 6.3 查询 audit_log

```
GET /api/v1/audit-logs?tenantId=demo-tenant&targetType=rule_definition&targetId=1&from=2026-05-01T00:00:00Z
```

**Response 200：** 分页列表，含 `actor / actorType / action / targetType / targetId / beforeSnapshot / afterSnapshot / operatedAt`。

### 6.4 按规则查历史 evaluation_session

> 排障场景：运营在规则详情页快速看到"这条规则最近触发了哪些 session，结果如何"。

```
GET /api/v1/rules/{ruleDefinitionId}/sessions?tenantId=demo-tenant&status=HIT&limit=20&offset=0
```

**Path 参数：**

| 参数 | 说明 |
|------|------|
| `ruleDefinitionId` | 规则定义 ID（`rule_definition.id`） |

**Query 参数：**

| 参数 | 必填 | 说明 |
|------|------|------|
| `tenantId` | 是 | 租户 ID |
| `status` | 否 | 筛选终态：`HIT / MISS / BLOCKED / ERROR`；不传则返回全部终态 |
| `limit` | 否 | 每页条数，默认 20，最大 100 |
| `offset` | 否 | 偏移量，默认 0 |

**Response 200：**
```json
{
  "total": 135,
  "items": [
    {
      "sessionId": 10001,
      "eventId": "evt-001",
      "subjectId": "user-001",
      "status": "HIT",
      "finalDecision": "REVIEW",
      "evalDurationMs": 45,
      "startedAt": "2026-06-04T10:00:00.123+08:00",
      "ruleVersionId": 42
    }
  ]
}
```

**实现说明（供实现参考，不属于 API 契约）：**
- 查询路由：`node_trace.rule_version_id` IN（ruleDefinitionId 对应的所有 `rule_version.id`）→ 取 `evaluation_session_id` → JOIN `evaluation_session`；
- 排序：`evaluation_session.started_at DESC`；
- 响应 `ruleVersionId` 来自关联的 `node_trace.rule_version_id`（该 session 内该规则实际命中的版本）；
- `tenantId` 为必填，查询层校验，不支持跨租户查询；
- 索引说明见 [`05-storage.md`](./05-storage.md) 运营查询索引表。

---

## 七、errorCode 清单与 i18n

### 入口层错误（HTTP 4xx，不进入评估链路）

这些错误在评估链路启动前由入口层短路返回，**不写入 `EvalResult.errorCode`**：

| errorCode | HTTP 状态 | 含义 | 调用方建议 |
|-----------|-----------|------|-----------|
| `PAYLOAD_SCHEMA_MISMATCH` | 400 | payload 字段缺必填 / 类型错 | 修复请求体重试 |
| `INVALID_EVENT_TYPE` | 400 | eventType 不在 Scene 白名单内 | 确认 sceneCode + eventType |
| `SCENE_NOT_FOUND` | 404 | sceneCode 未注册或 DISABLED | 确认 tenantId + sceneCode |

### 评估期 errorCode（EvalResult.errorCode）

评估链路内部错误，以第一个失败节点的 errorCode 为准（D15）：

| errorCode | HTTP 状态 | 含义 | 调用方建议 |
|-----------|-----------|------|-----------|
| `METRIC_FETCH_FAIL` | 200 | 有节点 MetricSource 取数失败（D15） | 查 nodeTrace 定位失败节点；fail-secure → 拒绝，fail-open → 放行 |
| `CONDITION_EVAL_ERROR` | 200 | ConditionEvaluator 抛异常（D15） | 同上 |

### Action 执行 errorCode（ActionResult.errorCode）

| errorCode | retryable | 含义 |
|-----------|-----------|------|
| `TIMEOUT` | true | Handler execute() 超时（D18） |
| `EXTERNAL_SERVICE_ERROR` | true | 外部系统返回 5xx / 连接失败 |
| `BUSINESS_REJECTED` | false | 外部系统明确拒绝（如工单系统返回 400） |
| `PREDECESSOR_FAILED` | false | failFast 前置 Action 失败（D18） |
| `QUEUE_OVERFLOW` | true | Action Dispatcher 队列满，Action 已丢弃入重试队列（D20） |
| `HANDLER_EXCEPTION` | false | ActionHandler.execute() 抛出未捕获异常（D18） |
| `DRY_RUN_NOT_IMPLEMENTED` | false | ~~v1 占位~~；v1.5 已全量实装（D7），不再产生 |
| `NOT_SUPPORTED` | false | compensate() 不支持 |

### 发布期 errorCode（audit_log.after_snapshot.errorCode）

| errorCode | 含义 |
|-----------|------|
| `UNRESOLVED_VARIABLE` | conditionAst / pre_gates / payload 引用了未绑定的变量（metricCode、payload 字段、EvalContext 标准字段均在校验范围内） |
| `METRIC_NOT_BOUND` | metric 不在 Scene.scene_metric_binding 白名单内 |
| `ACTION_TYPE_NOT_BOUND` | actionType 不在 Scene.scene_action_binding 白名单内 |
| `DECISION_CODE_NOT_FOUND` | decisionBindings 引用了该 Rule 所属 Tenant 未定义的 Decision（Decision 是 Tenant 级实体，D26） |
| `ZOMBIE_PUBLISHING` | 后台清扫检测到 PUBLISHING 状态残留超时，强制修正为 PUBLISH_FAILED（D19） |
| `HANDLER_EXCEPTION` | 发布事务内未分类异常，`after_snapshot` 含 stackTrace 摘要 |

---

## 八、SDK 用法

### 8.1 规则来源模式总览

嵌入式 SDK（`rule-sdk`）支持四种规则来源，通过统一的 `RuleSource` SPI 装载到本地评估索引，`evaluate()` 路径始终零网络跳转：

| 模式 | Builder 入口 | 适用场景 |
|------|-------------|---------|
| **HTTP 轮询** | `serverUrl()` + `tenantId()` | 生产，规则由服务端管理，定时热更新 |
| **JSON 文件** | `ruleFile("classpath:rules.json")` | 离线、测试、规则随代码打包 |
| **代码 DSL** | `localSnapshot()` + `Condition` DSL | 单测、演示、CI 验证规则逻辑 |
| **注解模式** | `ruleSource(new AnnotationRuleSource(...))` | 规则与业务代码同类，IDE 静态检查全链路打通 |

多种来源可混用（如文件兜底 + HTTP 热更新），各来源独立写入同一索引。

---

### 8.2 HTTP 轮询模式

`SnapshotPoller` 后台定时拉取 `/api/v1/sdk/snapshots`：

```java
// 非 Spring 项目
try (RuleEngineClient client = RuleEngineClient.builder()
        .serverUrl("http://rule-engine:8080")
        .tenantId("1001")
        .fetchMode(FetchMode.DECLARED)
        .scenes("fraud", "payment")
        .pollInterval(Duration.ofSeconds(30))
        .build()) {
    EvalResult result = client.evaluate(event);
}
```

Spring Boot 项目通过 `application.yml` 自动装配，支持三种配置模式：

**HTTP 轮询模式**（生产）：

```yaml
rule:
  sdk:
    server-url: http://rule-engine:8080
    tenant-id: "1001"
    fetch-mode: DECLARED      # DECLARED（订阅指定场景）或 ALL（全量）
    scenes: fraud, payment    # fetch-mode=DECLARED 时有效
    poll-interval: 30s
```

**JSON 文件模式**（离线/测试）：

```yaml
rule:
  sdk:
    rule-files:
      - classpath:rules/fraud.json
      - classpath:rules/payment.json
```

**混用**（文件兜底 + HTTP 热更新）：`server-url` 与 `rule-files` 同时配置时两路来源均装载，规则写入同一索引。

```java
@Autowired RuleEngineClient client;
EvalResult result = client.evaluate(event);
```

**`@ConditionType` Bean 自动扫描**：实现 `ConditionEvaluator` 接口并标注 `@ConditionType` 的 Spring Bean，AutoConfiguration 启动时自动收集注册，无需手动 `addEvaluator()`：

```java
@Component
@ConditionType("BLACKLIST_HIT")
public class BlacklistEvaluator implements ConditionEvaluator {
    @Override
    public boolean evaluate(ConditionNode node, EvalContext ctx) {
        List<?> list = (List<?>) node.params().get("list");
        var mv = ctx.metrics().get(node.metricCode());
        return mv != null && list.contains(mv.value());
    }
}
```

**Listener Bean 注入**：容器中存在 `EvalResultListener` 或 `EvalSessionListener` Bean 时自动注入：

```java
@Component
public class AuditListener implements EvalResultListener {
    @Override
    public void onResult(RuleEvent event, EvalResult result) {
        // 写审计日志、打点等
    }
}
```

---

### 8.3 JSON 文件模式

规则以 JSON 文件随代码打包，适合离线 / 测试环境，文件格式与服务端 `GET /api/v1/sdk/snapshots` 响应 `data` 数组一致，可直接从服务端导出后存为文件：

```java
try (RuleEngineClient client = RuleEngineClient.builder()
        .ruleFile("classpath:rules/fraud.json")
        .build()) {
    EvalResult result = client.evaluate(event);
}
```

文件结构示例（`src/main/resources/rules/fraud.json`）：

```json
[
  {
    "ruleVersionId": 1,
    "sceneCode": "fraud",
    "tenantId": "t1",
    "kind": "AST_BOOLEAN",
    "triggerEventTypes": ["TRANSACTION"],
    "decisionBindings": [{"decisionCode": "BLOCK", "priority": 100}],
    "preGates": [],
    "conditionAst": {
      "type": "AndNode",
      "children": [
        { "type": "ConditionNode", "conditionType": "GT", "metricCode": "amount",
          "params": {"threshold": 1000}, "weight": 0.0 }
      ]
    }
  }
]
```

---

### 8.4 代码 DSL 模式

不连接服务端，通过 `Condition` DSL 构造规则，适合单测 / 演示 / CI 验证：

```java
// 规则：amount > 1000 AND country IN ["CN", "HK"]
RuleVersionSnapshot snap = RuleVersionSnapshot.builder()
        .ruleVersionId(1L)
        .tenantId("t1")
        .sceneCode("fraud")
        .conditionAst(
            Condition.gt("amount", 1000)
                     .and(Condition.in("country", "CN", "HK"))
                     .toAst()
        )
        .addTriggerEventType("TRANSACTION")
        .addDecisionBinding("BLOCK", 100)
        .build();

try (RuleEngineClient client = RuleEngineClient.builder()
        .localSnapshot(snap)
        .build()) {
    EvalResult result = client.evaluate(event);
}
```

---

### 8.5 Condition DSL 速查

`Condition` 类（`rule-sdk`）封装 AST 构造细节，`toAst()` 生成标准 `AstNode`：

```java
// 数值比较
Condition.gt("amount", 1000)          // amount > 1000
Condition.gte("amount", 1000)         // amount >= 1000
Condition.lt("score", 60)             // score < 60
Condition.lte("score", 60)            // score <= 60

// 相等判断
Condition.eq("status", "ACTIVE")      // status == ACTIVE
Condition.neq("status", "BLOCKED")    // status != BLOCKED

// 集合 / 区间
Condition.in("country", "CN", "HK")   // country IN [CN, HK]
Condition.notIn("country", "US")      // country NOT IN [US]
Condition.between("age", 18, 65)      // age BETWEEN [18, 65]

// 字符串
Condition.contains("name", "corp")    // name 包含 "corp"
Condition.matches("email", ".*@corp\\.com")  // 正则
Condition.startsWith("code", "VIP")
Condition.endsWith("code", "PRO")

// 自定义算子（需配合 addEvaluator 注册）
Condition.of("BLACKLIST_HIT", "device_id", Map.of("list", blocklist))

// 逻辑组合（链式，同级 and/or 自动展平）
Condition.gt("amount", 1000).and(Condition.in("country", "CN", "HK"))
Condition.gt("amount", 1000).or(Condition.eq("vip", true))
Condition.eq("blocked", true).not()

// 恒真 / 恒假
Condition.always()   // 空 AND 节点，永远返回 true
Condition.never()    // 空 OR 节点，永远返回 false
```

---

### 8.6 自定义算子（addEvaluator）

注册自定义 `ConditionEvaluator`，叠加在内置算子之上（同名自定义可覆盖内置）：

```java
try (RuleEngineClient client = RuleEngineClient.builder()
        .localSnapshot(snap)
        .addEvaluator("BLACKLIST_HIT", (node, ctx) -> {
            List<?> list = (List<?>) node.params().get("list");
            Object val = ctx.providedMetrics().get(node.metricCode());
            return val != null && list.contains(val);
        })
        .build()) {
    EvalResult result = client.evaluate(event);
}
```

Spring 项目中，`@ConditionType` 标注的 Bean 由 `AutoConfiguration` 自动收集注入，无需手动 `addEvaluator()`。

---

### 8.7 快照拉取端点

`SnapshotPoller`（HTTP 轮询模式）内部调用的服务端接口：

```
GET /api/v1/sdk/snapshots
  ?tenantId=1001
  &scenes=fraud,payment   # 可选；不传则返回该租户所有 ACTIVE 快照
  &since=1717200000000    # 可选；预留增量拉取（v1 忽略，全量返回）
```

响应格式与 `ApiResponse<List<RuleVersionSnapshot>>` 一致（见 §一），`data` 数组即为 JSON 文件模式的合法输入。

---

### 8.8 RuleEvent 构造

```java
RuleEvent event = new RuleEvent(
        tenantId,       // String，与规则快照的 tenantId 一致
        sceneCode,      // String，如 "fraud"
        eventType,      // String，如 "TRANSACTION"
        subjectId,      // String，业务主体唯一标识
        eventId,        // String，业务幂等 ID（建议 UUID）
        Instant.now(),  // occurredAt
        payload,        // Map<String, Object>，事件 payload
        providedMetrics // Map<String, Object>，预计算指标（可为 null）
);
```

`providedMetrics` 的 key 须与规则中 `ConditionNode.metricCode` 完全一致，引擎直接从此 Map 取值与 `params` 中的阈值比较。

---

### 8.9 注解模式（`@RuleDef`）

规则定义与业务代码同处一个 Java 类，适合单测 / CI 验证 / 离线部署，IDE 静态检查全链路打通：

```java
@RuleDef(
    id        = 1L,
    tenantId  = "t1",
    sceneCode = "fraud",
    trigger   = "TRANSACTION",
    decisions = @DecisionBinding(code = "BLOCK", priority = 100)
)
public class AmountFraudRule implements InlineRuleSpec {
    @Override
    public Condition condition() {
        return Condition.gt("amount", 1000)
                        .and(Condition.in("country", "CN", "HK"));
    }
}
```

**非 Spring 场景**：手动传入列表：

```java
try (RuleEngineClient client = RuleEngineClient.builder()
        .ruleSource(new AnnotationRuleSource(List.of(new AmountFraudRule())))
        .build()) {
    EvalResult result = client.evaluate(event);
}
```

**Spring Boot 场景**：`@Component` + Starter 自动装配，`@Autowired RuleEngineClient` 直接使用，无需任何额外配置。

**`@RuleDef.id` 必须稳定**：调用方负责为每个规则类指定唯一且不变的 `id`，用于 `AnnotationRuleSource` 幂等写入索引（重复 `loadInto()` 不产生重复规则）。

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
