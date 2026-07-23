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

接口按**受众**分三类前缀挂载（均为 v1 稳定版）：`/admin/v1/`（管理后台,人操作）、`/api/v1/`（业务调用方/前端触发评估）、`/sdk/v1/`（嵌入式 SDK 下发,机器拉取）。errorCode 与 HTTP 状态码对应关系见 §七。

| 类别 | 分组 | 路径前缀 | 主要场景 |
|------|------|---------|---------|
| api | 评估接口 | `/api/v1/rule/` | 业务方触发评估（PUSH/PULL/dry-run）+ 场景输入清单发现 |
| admin | 规则管理 | `/admin/v1/rules` | 创建草稿 / 改草稿 / 出新版本 / 发布 / 禁用 / 删草稿 / 查询规则；批量导出 / 导入 Bundle 文件（B7） |
| admin | Scene 管理 | `/admin/v1/scenes` | 创建 / 更新 / 禁用 Scene |
| admin | 指标管理 | `/admin/v1/metrics` | 注册 / 更新 / 禁用 Metric |
| admin | 元数据接口 | `/admin/v1/scenes/{sceneCode}/metadata` | 前端编辑器拉 ConditionType 枚举 + tenant 级 ACTIVE metric |
| admin | 审计与查询 | `/admin/v1/evaluation-sessions`，`/admin/v1/rules/{id}/sessions` | 查 session / trace；按规则查历史触发记录 |
| sdk | SDK 下发接口 | `/sdk/v1/snapshots`，`/sdk/v1/metric-definitions` | 嵌入式 SDK 拉规则快照 / metric 定义元数据（HTTP 模式，见 §8.7） |

**分页约定**：所有 admin 列表接口统一返回 `PageResponse{ items, total, page, size }`——`items` 为当页数据数组，`total` 总记录数，`page` 当前页码（**从 1 起**），`size` 每页条数。查询参数统一用 `page` / `size`。

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
  "payload": { "amount": 25000, "currency": "CNY" }
}
```

> **公开评估接口只收 `payload`（事件事实）**：`providedMetrics` 已从公开请求体移除（B-T7）。受治理 metric 全归引擎侧——按 `sourceType` 自取（取数），或经**非公开**路径注入（嵌入式 SDK 宿主 / Job 预算，内部 `RuleEvent` 仍持 `providedMetrics`，但 HTTP 调用方碰不到）。调用方传哪些 payload 字段由场景输入清单（§3.4）声明。

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
  "nodeTrace": [],
  "errorCode": null
}
```

> **注**：D60 起引擎纯决策化，响应只承载决策（`finalDecision` / `hitDecisions`），无 `actionResults` 字段；"命中后做什么"归消费方 / 流程引擎。PUSH/HYBRID Scene 异步评估完即落库，不派发动作。

**超时建议**：调用方设 HTTP timeout ≥ 500ms（v1 P99 目标 < 500ms；风控高频场景 < 100ms 目标见 [`07-operability.md`](./07-operability.md) §七）。

**查 trace**：PULL 评估不在响应体直接返回 sessionId；调用方若需查 node_trace，通过 `GET /admin/v1/evaluation-sessions?tenantId=&eventId={eventId}` 取对应 session，再调 §6.2 `GET /admin/v1/evaluation-sessions/{sessionId}/trace`。

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

**Request：** 同 3.1（event 体），并以 **`ruleVersionId` / `ruleId` 二选一必传**指定试跑目标（D56）：

| 参数 | 说明 |
|------|------|
| `ruleVersionId` | 试跑该**精确版本**（DRAFT 或已发布版本均可），用于"发布前预览这条草稿" |
| `ruleId` | 取该规则的**最新版本**（最高版本号，含未发布 DRAFT）试跑 |

- 两者都不传 → 400 `MISSING_DRYRUN_TARGET`（见 §七）。
- 两者都传时以 `ruleVersionId` 为准（精确版本优先）。
- dry-run 恒走"带版本单快照"分支：**不写 `evaluation_session`**，dry-run 痕迹按需落 `dry_run_session` / `dry_run_node_trace`（与正式评估隔离，D7）。

**Response 200：** 同 3.2，但 `nodeTrace` 字段填充真实节点路径（evaluate 时为空数组）；引擎纯决策化后只返回决策预览（`finalDecision` / `hitDecisions`），无 `actionResults`：
```json
{
  "eventId": "evt-dry-001",
  "ruleHit": true,
  "finalDecision": { "code": "REVIEW" },
  "nodeTrace": [
    {
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
  ]
}
```

> `valueSource` 取值：`PROVIDED`（调用方注入）/ `FETCHED`（取数，如上例）/ `PAYLOAD`（payload 直接引用节点，valueRef=PAYLOAD，见 03-rule-expression §2.6）。

v1.5（D7）已全量实装 `dryRun()`，`DRY_RUN_NOT_IMPLEMENTED` errorCode 不再产生。见 07-operability §四。

### 3.4 场景输入清单发现接口

> **已实装（B-T9）**：调用方据此精确知道"对该场景发事件要传哪些 payload 字段（名 + 类型 + 是否必填）"，照着填 §3.1 的 `payload`。

```
GET /api/v1/rule/scenes/{sceneCode}/input-manifest?tenantCode=loadtest[&eventType=login]
```

**Query 参数：**

| 参数 | 必填 | 说明 |
|------|------|------|
| `tenantCode` | 是 | 租户业务码（`tenant.code`） |
| `eventType` | 否 | 事件类型；传入则收窄到会被该 eventType 触发的规则，不传则取该场景全部 active 规则的并集 |

**口径**：返回该场景所有 status=ACTIVE 规则在发布期冻结的 `rule_version.payload_dependencies`（即 `valueRef=PAYLOAD` 的 ConditionNode 引用字段）的**并集**（同名去重，`dataType` / `required` 取 `Scene.payloadSchema` 声明）。

**Response 200：** `ApiResponse` 包装，清单在 `data.fields`：

```json
{
  "success": true,
  "data": {
    "fields": [
      { "name": "amount", "dataType": "DECIMAL", "required": true },
      { "name": "country", "dataType": "STRING", "required": true }
    ]
  }
}
```

> `dataType` 为 `Scene.payloadSchema` 的 `type` 映射后的基础类型：`number→DECIMAL` / `integer→LONG` / `string→STRING` / `boolean→BOOLEAN`（与评估期校验同一映射，见 §七 `INPUT_TYPE_MISMATCH`）。

---

## 四、规则管理接口

### 4.1 创建规则草稿

> **已实装（v2）**：`POST /admin/v1/rules`，返回 201 + `DraftCreatedResult`；重复 code 前置校验，Scene 不存在返回 400。

```
POST /admin/v1/rules
```

**Request：**
```json
{
  "tenantId": "1",
  "sceneCode": "risk.transfer",
  "code": "rule-transfer-review",
  "name": "转账人工审核触发",
  "kind": "AST_BOOLEAN",
  "body": { "type": "AstBody", "conditionAst": { "type": "AndNode", "children": [] } },
  "decisionBindings": [{ "decisionCode": "REVIEW" }],
  "preGates": [{ "gateType": "ROLLOUT", "params": { "percentage": 100 } }],
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
| `kind`          | String | 否   | 规则类型，默认 `AST_BOOLEAN`；可选值：`AST_BOOLEAN` / `SCORECARD` / `DECISION_TREE` / `DECISION_TABLE` / `EXPRESSION_SCRIPT` / `DECISION_FLOW` |
| `body`          | Object | 否   | 判定主体多态载体 `RuleBody`（三承载收敛，D76），`type` 判别按 kind 择一：<br>• **`AstBody`**（AST 系四 kind）`{ "type":"AstBody", "conditionAst": {…} }`——`conditionAst` 缺省存空 AST；ConditionNode 字段语义见 [`03-rule-expression.md`](./03-rule-expression.md) §2.4，`valueRef`（`METRIC`\|`PAYLOAD`，缺省 `METRIC`）：`PAYLOAD` 时 `metricCode` 为 payload 字段名（须在 `Scene.payloadSchema` 声明），详见 §2.6。<br>• **`ScriptBody`**（`EXPRESSION_SCRIPT`）`{ "type":"ScriptBody", "script": { "source":"<表达式>", "lang":"CEL" } }`——发布期：①语法编译；②`metrics.<code>` 须 ACTIVE、`payload.<field>` 须声明，据此冻依赖；③typed 类型检查。返回值派发：Boolean→命中、String→决策码（∈ `decisionBindings`）、Number→评分。详见 [`03-rule-expression.md`](./03-rule-expression.md)。<br>• **`FlowBody`**（`DECISION_FLOW`）`{ "type":"FlowBody", "flowGraph": { nodes, edges, inputNodeId }, "referencedSnapshots": {} }`——节点四种 `RuleRef`/`Switch`/`Transform`/`Output`（`type` 判别）；发布期：①图结构合法（有 input、无孤儿、Switch caseKey 一致、Output.decisionCode 存在）；②`RuleRef` 引用规则 ACTIVE 版本冻进 `referencedSnapshots`（无 ACTIVE 拒绝），metric/payload 依赖全图并集冻结；③环/死节点静态检测。详见 [`00-decisions.md`](./00-decisions.md) D75。<br>**发布期校验 `kind` 与 body 变体一致**（不符 `KIND_BODY_MISMATCH`，D76）。 |
| `decisionBindings` | Array | 否 | 命中决策绑定列表，缺省空数组 |
| `preGates`      | Array  | 否   | 前置门列表，缺省空数组；每项 `{ gateType, params }`。ROLLOUT 灰度门的 params 见下 |
| `triggerEventTypes` | Array | 否 | 触发事件类型白名单，缺省空数组 |

**ROLLOUT 灰度门 params 字段：**

| 字段          | 类型   | 必填 | 说明 |
|---------------|--------|------|------|
| `percentage`  | Integer | 二选一 | 百分比放量，`[0,100]`，命中条件 `bucket < percentage`，等价于区间 `[0, percentage)` |
| `bucketStart` | Integer | 二选一 | 桶区间下界（含），`[0,100]`，与 `bucketEnd` 成对出现；命中条件 `bucketStart <= bucket < bucketEnd` |
| `bucketEnd`   | Integer | 二选一 | 桶区间上界（不含），`(bucketStart,100]` |
| `experimentId`| String | 否   | 实验标识；同 `experimentId` 的多条规则共享分桶种子 `hash(subjectId:experimentId)`。缺省时种子退回 `hash(subjectId:ruleVersionId)`（各规则独立分桶） |

- `bucket = (murmur3_32(seed) & 0x7fffffff) % 100`，取值 `[0,99]`。
- `percentage` 与桶区间二选一（同时给时桶区间优先）；两者皆无时该门 fail-open（全量放行）。
- **一致分桶**：同 `experimentId` 的规则用相同区间 → 同一批 subject 在多条规则上稳定同选。
- **A/B 互斥**：同 `experimentId` 的规则用不相交区间（如 A `[0,50)`、B `[50,100)`）→ 每个 subject 恰好命中其一。
- **发布期校验**（单规则）：`percentage∈[0,100]`；桶区间 `0<=bucketStart<bucketEnd<=100` 且必须成对；`experimentId` 非空白。违反返回 400 `INVALID_ARGUMENT`。跨规则区间不重叠由运营自行保证。

互斥配置示例（两条规则同属 `exp-price-001`，平分流量）：
```json
// 规则 A
"preGates": [{ "gateType": "ROLLOUT", "params": { "experimentId": "exp-price-001", "bucketStart": 0, "bucketEnd": 50 } }]
// 规则 B
"preGates": [{ "gateType": "ROLLOUT", "params": { "experimentId": "exp-price-001", "bucketStart": 50, "bucketEnd": 100 } }]
```

**Response 201：**
```json
{ "ruleDefinitionId": 1, "ruleVersionId": 1, "version": 1, "status": "DRAFT" }
```

> **premise A（D56）**：创建即跑全套 `resolveAndValidate`（解析 + 硬校验：metric 须 ACTIVE、payload 字段须在 `Scene.payloadSchema` 声明、decision 须存在、kind 结构 + 算子×dataType 校验）。校验不过返回 400 `INVALID_ARGUMENT`（语义码携于 message）。落库的 DRAFT 行已是冻结快照（`resolvedAst` 含 dataType、`metricDependencies`/`payloadDependencies` 已冻、`decisionBindings` 含 `name`），与发布后内容一致。

### 4.1.1 编辑草稿（editDraft，D56）

> **已实装（D56）**：原地更新当前最新 DRAFT 行内容，**不增版本号**（同一 `ruleVersionId`、同一 `version`）。

```
PUT /admin/v1/rules/{ruleDefinitionId}/draft
```

**Request：** 字段同 §4.1（`body` / `decisionBindings` / `preGates` / `triggerEventTypes` / `kind` / `name`），整组覆盖当前 DRAFT 内容；`tenantId` / `sceneCode` / `code` 不可改。

- 同 §4.1 跑全套 `resolveAndValidate`（premise A），校验不过返回 400。
- 规则当前**无 DRAFT 版本**（仅有已发布版本）时返回 400 `INVALID_ARGUMENT`（须先 §4.1.2 出新版本再编辑）。

**Response 200：** 同 §4.1 结构（`ruleDefinitionId` / `ruleVersionId` / `version` / `status=DRAFT`，version 与编辑前一致）。

### 4.1.2 出新版本 / 回退（newVersion，D56）

> **已实装（D56）**：对已发布规则产出 `v_max+1` 的新 DRAFT。带 `fromVersionId` 即**回退**（克隆旧版本内容、按当前世界重解析）。

```
POST /admin/v1/rules/{ruleDefinitionId}/versions
```

**Request：**
```json
{ "fromVersionId": 100 }
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `fromVersionId` | Long | 否 | 缺省：以当前最新版本内容为基底出新版本草稿；传入：**回退**——克隆该 `rule_version` 的输入意图，按**当前世界**（metric/decision/payload 现状）重新 `resolveAndValidate` 产出新 DRAFT。激活仍走显式 §4.2 publish。 |

- **前置约束**：规则当前**不得存在未发布 DRAFT**（先发布或删掉在途草稿）→ 否则 400 `INVALID_ARGUMENT`。
- 新版本 `version = v_max + 1`，`status=DRAFT`，内容为冻结快照（premise A）。

**Response 201：** 同 §4.1 结构（新 `ruleVersionId`，`version=v_max+1`，`status=DRAFT`）。

### 4.2 发布规则

```
POST /admin/v1/rules/{ruleDefinitionId}/publish
```

**语义（D56）**：publish **退化为激活**——把当前最新 DRAFT 行**原地翻 ACTIVE**（version 不变、不重解析，草稿写入期已是冻结快照），supersede 旧 ACTIVE 行，发 `RulePublishedEvent` 触发 eval 侧索引热更。发布落库的 `triggerEventTypes` 是草稿自己声明的值（保证"dry-run 预览 == 发布"），不再覆盖成 scene 全集。版本号只在 §4.1 createDraft（v1）/ §4.1.2 newVersion（+1）产生，publish 不增。

**响应**：发布成功 200，返回新激活的 `RuleVersionSnapshot`；当前无 DRAFT 可发布返回 400；草稿写入期校验已前移，publish 不再做解析校验（premise A 下校验失败的草稿无法落库）。

### 4.3 禁用 / 启用规则

```
POST /admin/v1/rules/{ruleDefinitionId}/disable
POST /admin/v1/rules/{ruleDefinitionId}/enable
```

disable 与 enable 是 `PUBLISHED ↔ DISABLED` 两个**单向**操作（D19 解耦切换，独立单事务、不增 `rule_version`、`current_version` 指针不变）：

- **disable**：仅 `PUBLISHED → DISABLED`，其它源态（DRAFT 等）返回 400；效果 `rule_definition.status = DISABLED`，Matcher 倒排索引热摘除（≤15s 全实例收敛，D17），审计 `action=DISABLE`。
- **enable**：仅 `DISABLED → PUBLISHED`，其它源态返回 400；指回原 `current_version`，索引热加回，审计 `action=ENABLE`。

### 4.3.1 删除规则（deleteRule，D56）

> **已实装（D56）**：仅删**从未发布过**的规则（无 ACTIVE/SUPERSEDED 版本），级联删 `rule_definition` + 其全部 `rule_version`。

```
DELETE /admin/v1/rules/{ruleDefinitionId}
```

- 规则存在任一 ACTIVE / SUPERSEDED 版本（曾上线）→ 拒绝（返回 400），只能 §4.3 disable。
- 级联范围**只 `rule_version` + `rule_definition`**；dry-run 痕迹（`dry_run_session`/`dry_run_node_trace`，按 ruleVersionId 关联）视同审计历史**不级联删**，靠 TTL 退休（D56 / D7）。被引用的 metric/decision/scene 不受影响。

**Response 200：** 删除成功。

### 4.3.2 删除草稿版本（deleteDraftVersion，D56）

> **已实装（D56）**：仅删指定 **DRAFT** 版本行（如放弃在途草稿）。

```
DELETE /admin/v1/rules/{ruleDefinitionId}/versions/{versionId}
```

- 目标版本须属该规则且 `status=DRAFT`；碰 ACTIVE / SUPERSEDED 版本一律拒绝（返回 400，只能 disable）。
- 不触碰 `rule_definition` 行与其它版本。

**Response 200：** 删除成功。

### 4.4 查询规则列表

```
GET /admin/v1/rules?tenantId=demo-tenant&sceneCode=risk.transfer&status=PUBLISHED
```

**Response 200：** `PageResponse`（见 §二分页约定），`items` 元素含 `ruleDefinitionId / code / name / status / currentVersion / publishedAt`。

### 4.5 注册 Metric（B6）

```
POST /admin/v1/metrics?tenantId={tenantId}&metricCode={metricCode}
```

**Headers：**

| Header | 必填 | 说明 |
|--------|------|------|
| `X-Actor-Id` | 是 | 操作人标识，写入 audit_log（D14） |

**Request body：**
```json
{
  "name": "KYC 等级",
  "sourceType": "ATTRIBUTE",
  "dataType": "LONG",
  "params": { "table": "user_profile", "column": "kyc_level" },
  "cacheTtlSeconds": 60,
  "allowProvided": true
}
```

> `tenantId` / `metricCode` 通过 **query param** 传入；`params` 是结构依 `sourceType` 而异的 **JSON 对象**，服务端序列化后存库。

**Response 201：**
```json
{ "success": true, "data": 1 }
```

`data` 为新插入行的 id（裸 Long）。

### 4.6 更新 / 升版 Metric（B6）

```
PUT /admin/v1/metrics/{metricCode}?tenantId={tenantId}&breakingChange=false
```

**Headers：** 同 §4.5（`X-Actor-Id` 必填）。

- `breakingChange=false`（默认）：原地更新当前 ACTIVE 版本的 name / params / cacheTtlSeconds / allowProvided，不产生新版本行。
- `breakingChange=true`：INSERT 新版本行（version 递增），旧行 status 改为 `SUPERSEDED`；已发布规则仍绑定旧版本，不受影响。
- **`sourceType` / `dataType` 变更视为 `breakingChange=true`（强制）**：即使请求参数传 `breakingChange=false`，只要 sourceType 或 dataType 与当前 ACTIVE 行不同，实现层自动走升版路径（D6/B6 冻结语义）。

**Request body：** 同 §4.5 body，省略 `metricCode`（来自路径）。

**Response 200：**
```json
{ "success": true, "data": 2 }
```

`data` 为当前生效行的 version（裸 Integer）。

### 4.7 影响面查询（B6）

```
GET /admin/v1/metrics/{metricCode}/versions/{version}/impact?tenantId={tenantId}
```

**Response 200：**
```json
{
  "success": true,
  "data": {
    "metricCode": "user.kyc.level",
    "metricVersion": 1,
    "affectedRules": [
      {
        "ruleDefinitionId": 10,
        "ruleCode": "block-new-account",
        "ruleName": "封禁新账户",
        "sceneCode": "risk.transfer",
        "status": "ACTIVE"
      }
    ],
    "affectedRuleCount": 1
  }
}
```

**口径**：收集所有在 `rule_version.metric_dependencies` 中绑定了该 `(metricCode, version)` 的当前 **ACTIVE rule_version** 对应的规则（按 `rv.status=ACTIVE` 收集，不按 `rule_definition.status` 过滤，口径对齐 eval 侧加载逻辑）。因此 `rule_definition.status=DISABLED` 但其 `rule_version.status=ACTIVE` 的规则仍会出现在结果中，`status` 字段反映 `rule_definition.status` 实际值。

---

### 4.8 批量导出规则 Bundle（B7）

```
GET /admin/v1/rules/export?tenantId={tenantId}&ruleIds={id,id}&sceneId={sceneId}
```

按条件批量导出规则的当前 ACTIVE 版本为自包含 JSON Bundle，**以文件下载形式返回**（`Content-Type: application/json` + `Content-Disposition: attachment; filename="rule-bundle-{tenantId}-{ts}.json"`），供跨环境 / 跨租户迁移、Incident 复现。选取优先级：`ruleIds` 非空 → 按 id 列表；否则 `sceneId` 非空 → 该场景全部；否则 → 该租户全部。对每条仅导当前 ACTIVE 版本，无 ACTIVE 版本者跳过；最终无可导出规则时返回 `INVALID_ARGUMENT`（JSON 错误体）。导出入参用 `sceneId`；Bundle 内 `rules[].sceneCode` 用 code，跨环境按 code 关联。

**Response 200**：Bundle JSON 文件（attachment），内容为多规则 Bundle：

```json
{
  "bundleVersion": 1,
  "exportedAt": "2026-06-06T10:00:00Z",
  "sourceTenantId": "1",
  "rules": [
    {
      "code": "rule.night.transfer", "name": "夜间大额转账", "kind": "AST_BOOLEAN",
      "sceneCode": "risk.transfer",
      "body": "{...}", "decisionBindings": "[...]", "preGates": "[]",
      "triggerEventTypes": "[\"transfer\"]",
      "metricDependencies": [{"metricCode": "account.age", "metricVersion": 1}]
    }
  ],
  "scenes": [{"code": "risk.transfer", "name": "...", "...": "..."}],
  "metricDefinitions": [{"metricCode": "account.age", "version": 1, "...": "..."}],
  "decisionDefinitions": [{"code": "BLOCK", "name": "...", "priority": 1}]
}
```

> 所有 JSON 列（body / decisionBindings / preGates / triggerEventTypes / payloadSchema / eventTypes / defaultParams）以**原始 JSON 字符串**无损搬运（`body` 为多态 RuleBody，含 type 判别，D76）。`decisionDefinitions[]` 承载 `decisionBindings` 引用的 tenant 级 decision（D26）。

### 4.9 批量导入规则 Bundle（B7）

```
POST /admin/v1/rules/import?tenantId={tenantId}
```

header `X-Actor-Id`；**`multipart/form-data` 上传 Bundle JSON 文件（字段名 `file`）**。幂等批量导入到目标租户：Scene / metric / decision 缺失则建、已存在跳过；规则逐条落为 DRAFT 版本（同 code 已存在则追加草稿版本，不覆盖已发布版本）。`SQL_AGGREGATE` 类缺失 metric 不自动创建，列入 `metricsRequiringReview`。文件解析失败返回 `INVALID_ARGUMENT`。

> **依赖完整性约定**：
> - **Scene**：Bundle 的 `scenes[]` 须包含全部规则引用的 Scene。"缺失则建"的主语是**目标环境**——目标无同 code Scene 时从 Bundle 重建，已有则跳过；若 Bundle 未携带且目标也无该 Scene，返回 `INVALID_ARGUMENT`。
> - **Metric**：upsert 以 **metricCode** 为键（忽略 version）——目标已有同 code 即列入 `metricsSkippedExisting`，**不保证精确版本一致**；版本不匹配不在导入期拦截，而由后续发布期"被引用 metric 无 ACTIVE 版本"校验兜底。

**Response 200**：`ApiResponse<RuleImportResult>`：

```json
{
  "success": true,
  "data": {
    "rules": [
      {"ruleDefinitionId": 10, "ruleVersionId": 100, "version": 1,
       "code": "rule.night.transfer", "sceneCode": "risk.transfer", "ruleAlreadyExisted": false}
    ],
    "scenesCreated": ["risk.transfer"], "scenesSkippedExisting": [],
    "metricsCreated": ["account.age"], "metricsSkippedExisting": [], "metricsRequiringReview": [],
    "decisionsCreated": ["BLOCK"], "decisionsSkippedExisting": []
  }
}
```

> 权限：v1 沿用 `X-Actor-Id`，§2.9 设想的 EXPORT / PUBLISH 权限校验留 TODO。

### 4.10 查询规则详情

```
GET /admin/v1/rules/{ruleDefinitionId}?tenantId=demo-tenant
```

**Response 200：** `RuleDetailVO`，含 `ruleDefinitionId / code / name / status / kind / sceneCode / body / decisionBindings / currentVersionId`。`body`（多态 RuleBody，含 type 判别，D76）与 `decisionBindings`（数组）取自当前 ACTIVE 版本并反序列化为结构化 JSON，供前端编辑回填；无 ACTIVE 版本时两者为 null。

### 4.11 查询 Metric 定义列表

```
GET /admin/v1/metrics?tenantId=demo-tenant
```

**Response 200：** `MetricDescriptor` 数组，每项含 `metricCode / metricVersion / sourceType / dataType / allowProvided / cacheTtlSeconds / params`（与 §8.7 SDK 下发的 MetricDescriptor 同构）。

---

## 五、元数据接口

### 5.1 拉 Scene 元数据（前端编辑器）

```
GET /admin/v1/scenes/{sceneCode}/metadata?tenantId=demo-tenant
```

**Response：** 见 `04-extension.md §五` 元数据契约（`conditionTypes` / `availableMetrics` 两段）。

### 5.2 查询 Scene 列表

```
GET /admin/v1/scenes?tenantId=demo-tenant
```

**Response 200：** `SceneListItem` 数组，每项含 `id / sceneCode / name / dominantMode / subjectType / status`，供前端场景选择器 / 列表页。

---

## 六、审计与查询接口

### 6.1 查询 evaluation_session

```
GET /admin/v1/evaluation-sessions?tenantId=demo-tenant&sceneCode=risk.transfer&subjectId=user-001&from=2026-05-01T00:00:00Z&to=2026-06-01T00:00:00Z
```

**Response 200：** `PageResponse`（见 §二分页约定），`items` 元素含 `sessionId / eventId / status / finalDecision / startedAt / evalDurationMs`。

### 6.2 查询 node_trace

```
GET /admin/v1/evaluation-sessions/{sessionId}/trace?tenantId=demo-tenant
```

**Response 200：** 扁平 trace 列表，按 `node_path` 字典序排列。向后兼容保留。

**嵌套树端点（推荐）**：`GET /admin/v1/evaluation-sessions/{sessionId}/trace/tree?tenantId=`  
返回格式与 §3.3 dry-run `nodeTrace` 相同（数组,每元素为一棵嵌套树），已实装（v2 第二阶段）。数据来源是 `node_trace` 表的扁平行，由 API 层按 `node_path` 重建树后返回。

### 6.3 查询 audit_log

```
GET /admin/v1/audit-logs?tenantId=demo-tenant&targetType=rule_definition&targetId=1&from=2026-05-01T00:00:00Z
```

**Response 200：** `PageResponse`（见 §二分页约定），`items` 元素含 `actor / actorType / action / targetType / targetId / beforeSnapshot / afterSnapshot / operatedAt`。

### 6.4 按规则查历史 evaluation_session

> 排障场景：运营在规则详情页快速看到"这条规则最近触发了哪些 session，结果如何"。

```
GET /admin/v1/rules/{ruleDefinitionId}/sessions?tenantId=demo-tenant&status=HIT&page=1&size=20
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
| `page` | 否 | 页码，从 1 起，默认 1 |
| `size` | 否 | 每页条数，默认 20，最大 100 |

**Response 200：**
```json
{
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
  ],
  "total": 135,
  "page": 1,
  "size": 20
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
| `MISSING_REQUIRED_INPUT` | 400 | 评估请求 `payload` 缺场景输入清单要求的必填字段（候选规则 `payload_dependencies` 并集中 `required=true` 的字段未传，B-T8） | 先查 §3.4 input-manifest 拿清单，补齐必填字段重试 |
| `INPUT_TYPE_MISMATCH` | 400 | 评估请求 `payload` 字段基础类型与清单声明不符（`number→DECIMAL` / `integer→LONG` / `string→STRING` / `boolean→BOOLEAN`，B-T8） | 按清单声明的 `dataType` 修正字段类型重试 |
| `INVALID_EVENT_TYPE` | 400 | eventType 不在 Scene 白名单内 | 确认 sceneCode + eventType |
| `SCENE_NOT_FOUND` | 404 | sceneCode 未注册或 DISABLED | 确认 tenantId + sceneCode |
| `MISSING_DRYRUN_TARGET` | 400 | `POST /api/v1/rule/dry-run` 未传 `ruleVersionId` 或 `ruleId`（二选一必传，D56） | 补传 `ruleVersionId`（精确版本）或 `ruleId`（取最新版本）重试 |

> `MISSING_REQUIRED_INPUT` / `INPUT_TYPE_MISMATCH` 经 `IllegalArgumentException → GlobalExceptionHandler → HTTP 400` 落地，与既有约定一致：**wire 层 `errorCode` 统一为 `INVALID_ARGUMENT`**，语义码（`MISSING_REQUIRED_INPUT` 等）携带在 message 前缀（同 `DECISION_CODE_NOT_FOUND` 等发布期语义码经 message 透出的方式）。两者管"调用期：调用方别漏传 / 错类型"，与发布期 `UNRESOLVED_VARIABLE`（管"授权期：规则别越界引用"）正交。

### 管理接口错误（HTTP 4xx）

§四 规则管理 / Scene / Metric 等写接口的参数校验失败统一返回 `INVALID_ARGUMENT`（400，由全局异常处理器映射 `IllegalArgumentException`）。

| errorCode | HTTP 状态 | 含义 | 调用方建议 |
|-----------|-----------|------|-----------|
| `INVALID_ARGUMENT` | 400 | 管理接口参数校验失败。B7 适用情形：导出无可导出的 ACTIVE 规则 / `sceneId` 对应 Scene 不存在；导入 Bundle 文件解析失败 / `rules` 为空 / 规则引用的 Scene 既不在 Bundle 也不在目标环境 | 按 message 修正请求后重试 |

### 评估期 errorCode（EvalResult.errorCode）

评估链路内部错误，以第一个失败节点的 errorCode 为准（D15）：

| errorCode | HTTP 状态 | 含义 | 调用方建议 |
|-----------|-----------|------|-----------|
| `METRIC_FETCH_FAIL` | 200 | 有节点 MetricSource 取数失败（D15） | 查 nodeTrace 定位失败节点；fail-secure → 拒绝，fail-open → 放行 |
| `CONDITION_EVAL_ERROR` | 200 | ConditionEvaluator 抛异常（D15） | 同上 |

> **Action 执行 errorCode 已移除（D60）**：引擎纯决策化，整个动作子系统及 `ActionResult.errorCode` 枚举一并删除；"命中后做什么"归消费方 / 流程引擎，其执行错误码由下游编排层自行定义。

### 发布期 errorCode（发布 API 错误响应）

发布是单 DB 原子事务（D19），任一步校验失败 → 整事务回滚、规则保持原态、不写审计；errorCode 随发布请求的 HTTP 400 错误响应返回：

| errorCode | 含义 |
|-----------|------|
| `UNRESOLVED_VARIABLE` | conditionAst / pre_gates / payload 引用了未绑定的变量（metricCode、payload 字段、EvalContext 标准字段均在校验范围内）。含 `valueRef=PAYLOAD` 的 ConditionNode 其 `metricCode`（payload 字段名）未在 `Scene.payloadSchema` 声明的情形——发布拒绝，message 指明该未声明字段 |
| `DECISION_CODE_NOT_FOUND` | decisionBindings 引用了该 Rule 所属 Tenant 未定义的 Decision（Decision 是 Tenant 级实体，D26/D54） |
| `HANDLER_EXCEPTION` | 发布事务内未分类异常，message 含异常摘要 |

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

`SnapshotPoller` 后台定时拉取 `/sdk/v1/snapshots`：

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

规则以 JSON 文件随代码打包，适合离线 / 测试环境，文件格式与服务端 `GET /sdk/v1/snapshots` 响应 `data` 数组一致，可直接从服务端导出后存为文件：

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
    "body": {
      "type": "AstBody",
      "conditionAst": {
        "type": "AndNode",
        "children": [
          { "type": "ConditionNode", "conditionType": "GT", "metricCode": "amount",
            "params": {"threshold": 1000}, "weight": 0.0 }
        ]
      }
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

// payload 直接引用（valueRef=PAYLOAD，metricCode 为 payload 字段名，须在 Scene.payloadSchema 声明）
// 与上方 metric 工厂一一对称，仅值来源不同；详见 03-rule-expression §2.6
Condition.payloadGt("amount", 1000)            // payload.amount > 1000
Condition.payloadGte("amount", 1000)
Condition.payloadLt("amount", 1000)
Condition.payloadLte("amount", 1000)
Condition.payloadEq("currency", "CNY")
Condition.payloadNeq("currency", "USD")
Condition.payloadIn("currency", "CNY", "HKD")
Condition.payloadBetween("amount", 100, 10000)

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

### 8.7 SDK 下发端点

`SnapshotPoller`（HTTP 轮询模式）内部调用的服务端接口：

```
GET /sdk/v1/snapshots
  ?tenantId=1001
  &scenes=fraud,payment   # 可选；不传则返回该租户所有 ACTIVE 快照
  &since=1717200000000    # 可选；预留增量拉取（v1 忽略，全量返回）
```

响应格式与 `ApiResponse<List<RuleVersionSnapshot>>` 一致（见 §一），`data` 数组即为 JSON 文件模式的合法输入。

`MetricDefinitionPoller`（HTTP 取数模式，D46 / B23）内部调用的 metric 定义下发接口——仅注入 handler 启用 fetch 时拉取：

```
GET /sdk/v1/metric-definitions
  ?tenantId=1001
  &scenes=fraud,payment   # 可选；不传（ALL 模式）返回该租户全部 ACTIVE 定义；
                          # 传入（DECLARED 模式）只返回这些 scenes 下 ACTIVE rule_version 的 metricDependencies 并集内的定义
```

响应格式为 `ApiResponse<List<MetricDescriptor>>`（见 §一），仅下发定义元数据（`sourceType`/`dataType`/`allowProvided`/`cacheTtlSeconds`/`params`/`metricVersion`），**不含凭证**——取数 handler 与凭证由宿主提供。`scenes` 过滤口径与快照下发一致（`rv.status=ACTIVE`），保证 SDK 评估这些 scenes 时引用的 metric 定义都已下发。**B6 补注**：DECLARED 模式按被引用 `(metricCode, metricVersion)` 并集下发，含 `SUPERSEDED` 旧版定义（被现存 ACTIVE 规则快照引用的版本必须下发），每项 `MetricDescriptor` 带 `metricVersion` 字段。`rule_version.metric_dependencies` 格式为对象数组 `[{metricCode, metricVersion}]`（B6），非字符串数组。

---

### 8.8 RuleEvent 构造

```java
RuleEvent event = new RuleEvent(
        tenantId,        // String，与规则快照的 tenantId 一致
        sceneCode,       // String，如 "fraud"
        eventType,       // String，如 "TRANSACTION"
        subjectId,       // String，业务主体唯一标识
        eventId,         // String，业务幂等 ID（建议 UUID）
        Instant.now(),   // occurredAt
        payload,         // Map<String, Object>，事件 payload
        providedMetrics, // Map<String, Object>，预计算指标（可为 null；仅非公开链路注入——SDK/Job，公开请求体无此字段，D55）
        source           // EventSource，事件来源：HTTP / MQ / JOB / SDK / REPLAY
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

- API 路径前缀按受众分 `/admin/v1/`、`/api/v1/`、`/sdk/v1/` —— 重大不兼容变更时各自升 v2
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
