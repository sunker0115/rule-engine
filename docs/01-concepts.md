# 01 — 概念词典与心智模型

> **位置定位**：所有后续文档的"共同语言"。这里只讲名词、关系、边界，**不讲实现**（实现在 02-runtime / 03-rule-expression / 04-extension）。
>
> **前置阅读**：[README](./README.md) §一定位与边界 + §二 D1-D30 决策表。
>
> **解决什么疑问**：
> - "Rule / Condition / Decision / EvalContext 到底指什么？"
> - "Scene 和 Tenant 有什么区别？Scene 为什么是 metric 的治理边界？"
> - "运营心智里的'条件分组'在数据模型里是什么？为什么不固化 Group 实体？"
> - "Metric 又是什么？为什么不直接在 Condition 里写 SQL？"
> - "引擎只出决策吗？命中后做什么归谁？"
>
> **阅读时长**：~20 分钟。

---

## 一、一句话名词清单

引擎有 **8 个一等概念**，背完就能听懂后续所有文档（D60 起引擎纯决策化，"命中后做什么"归消费方 / 流程引擎，引擎不再有 Action 概念）：

| 概念 | 一句话定义 | 谁来定义 / 配 |
|------|------------|---------------|
| **Tenant** | 数据与权限的最外层隔离单元（一个公司 / 一条业务线 / 一个 SaaS 租户） | 平台运维 |
| **Scene** | Tenant 内的业务域命名空间（如 `marketing.signup` / `risk.transfer`），兼任 **Matcher 路由键 + 数据源初始化锚点 + 使用模式声明（PUSH / PULL / HYBRID） + 元数据 schema 承载者（`payloadSchema` / `eventTypes` / `subjectType` / `defaultParams`，D13）**（D54 起 metric 治理白名单已移除：metric tenant 级可用） | 平台运维 |
| **RuleEvent** | 触发评估的"一次发生"：谁、在哪、做了什么（不可变 POJO） | 上游业务方推 |
| **Rule** | 一条规则定义：在什么条件下、对谁、满足后输出哪个 Decision；带版本、灰度、Pre-Gate；条件用 **AST 树**表达（v1 仅 `kind=AST_BOOLEAN`；D12 预留 SCORECARD / DECISION_TREE / DECISION_TABLE / EXPRESSION_SCRIPT 多态扩展位） | 业务运营 / 风控配置 |
| **Condition** | AST 叶子节点：一条原子判断（`age >= 18` / `近 7 天交易额 > 1000`），由 `conditionType` 路由到具体评估器 | 业务运营选类型 + 配参数 |
| **Metric** | 取数原子（按 `metricCode` 注册），同一指标可被多 Rule 共享、可缓存；**在 tenant 级对所有 scene 可用**（D54，无 scene 白名单） | 平台 + 业务方共同治理 |
| **EvalContext** | 一次评估的运行时上下文：指标快照 + 用户画像 + 业务身份（不可变 POJO） | 引擎在评估前现场构建 |
| **Decision** | Tenant 级输出定义：规则命中后输出的语义结论（REJECT / REVIEW / PASS 等）；带 `priority` 字段，多规则命中时 Scene 按 `decisionStrategy` 合成最终 Decision；引擎的**最终产物**——"命中后做什么"由消费方据此决策（D60） | 平台运维 |

> **两类支撑概念**（不是一等公民，但理解整体必须知道）：`ConditionEvaluator` / `MetricSource` —— 它们是上面 Condition / Metric 的"幕后执行者"，详见 §五边界辨析。注：`MetricSource` 是概念层名称；04-extension 中对应的 Java SPI 接口名为 `MetricSourceHandler`（与 `ConditionEvaluator` 命名风格保持一致）。
>
> **Job 不是一等公民**：定时类规则通过 `JobDefinition` + `Scheduler` 适配为"到点合成 RuleEvent → 注入标准评估链路"，不引入新概念。详见 §3.10。

**注：运营心智里的"条件分组"**在数据模型上不固化为独立实体，而是由 AST 的 `AndNode` / `OrNode` 携带可选 `displayLabel` 字段表达——前端按 label 渲染成"分组卡片"，后端只看 AST 逻辑结构。这样 90% 的"分组"成本是零（一个字段），剩下 10% 真正需要"独立分组实体 / 跨 Rule 复用 / 分组权限"的高级场景，留到 v2 视情况升级。详见 §五 Q2。

---

## 二、关系总览

```
Tenant ──── 1:N ────► Scene                Metric (tenant 级,经 metricCode 直接引用)
  │                    │                     ▲
  │ 1:N                │ 1:N                  │ 经 metricCode（D54:无 scene 级绑定）
  ▼                    ▼                     │
Decision           ┌────────────────────────────────────────────┐
(tenant 级,         │  Rule（心智概念，框内可视为一个整体）        │
 code+priority)    │                                             │
  ▲                │  RuleDefinition ◄── trigger ── RuleEvent    │
  │ N:1 (经        │       │                                     │
  │  RuleDecision  │   1:N │ current_version 指向                │
  │  Binding)      │       ▼                                     │
  └──────────────► │  RuleVersion (不可变发布快照, D6/D19)       │
                   │  持有: AST + preGates（含 ROLLOUT 灰度）    │
                   │       + decision_bindings                    │
                   └────────────────────────────────────────────┘
                            │
                        1:1 │ (RuleVersion 持有冻结的 AST)
                            ▼
                           AST
                            │ 叶子节点
                            ▼
                      ConditionNode ── consults ──► Metric
                            │
                            │ (整个 AST 求值为 true)
                            ▼
                   绑定的 Decision ──► decisionStrategy 合成 ──► finalDecision
                                                                      │
                            (PUSH/HYBRID) 异步评估 + 落库, 不派发动作  │
                            (PULL) 同步返回 EvalResult{finalDecision, hitDecisions, ...}
                                                                      ▼
                                            引擎产出决策 → 消费方 / 流程引擎据此执行 (D60)
```

文字版：

- 一个 **Tenant** 下有多个 **Scene** 和多个 **Decision**；Decision 是 Tenant 级，所有 Scene 共享同一套 Decision 词汇表；
- 一个 **Scene** 有多条 **Rule**；metric 在 tenant 级对所有 scene 可用（D54，无白名单绑定）；
- **Rule 在心智上是一个概念，实际拆为两层**：**RuleDefinition**（规则元数据 + `current_version` 指针）+ **RuleVersion**（一行版本快照）；**草稿即完整冻结快照（premise A，D56）**——`rule_version` 行在 createDraft（v1）/ newVersion（v_max+1）时即跑全套解析校验并冻结，DRAFT 与发布后是同一行（publish 原地翻 ACTIVE，不重解析、不增版本）；运行时评估锁定 ACTIVE RuleVersion，回滚 = newVersion 带 `fromVersionId`（克隆旧版本输入意图、按当前世界重解析产新 DRAFT，再显式发布，D6 / D17 / D19 / D56）；
- 一条 **RuleVersion** 冻结判定主体（v1 = `AST_BOOLEAN` 下的 `RuleNode` sealed 树，`resolvedAst` 含 dataType）+ 冻结的 `metric_dependencies` / `payload_dependencies` + `decision_bindings`（Rule 绑定的 Decision 快照，含 `name`；DDL 列名）；
- AST 内部由 `AndNode` / `OrNode` / `NotNode` 嵌套，叶子是 `ConditionNode`；
- **RuleEvent** 触发 Matcher 按 `(scene, eventType)` 倒排索引拿候选 **RuleVersion 快照**列表（D17 派生：`current_version` 在索引预热时已解析，运行时直达 RuleVersion，不再二次查 RuleDefinition）→ 引擎按需取 **Metric**（tenant 级注册的 ACTIVE metric）构建 **EvalContext**，喂给 ConditionNode 做判定；
- AST 求值为 true 后 → 取 Rule 绑定的 Decision → `decisionStrategy` 合成 `finalDecision`：**PUSH/HYBRID** 模式异步评估 + 落库 `evaluation_session` / trace（**不派发动作**，D60）；**PULL** 模式同步返回 `EvalResult{finalDecision, hitDecisions, ...}`，调用方自行决策。引擎只产出决策，"命中后做什么"归消费方 / 流程引擎。

---

## 三、名词全景

> **横切：核心配置表共享审计字段**（D14）
>
> 所有可由人编辑的配置对象（`tenant`、`scene`（DDL 落地表名，旧称 `scene_definition`）、`rule_definition`、`metric_definition`、`decision_definition`、`job_definition` 等）的表结构都横切包含以下审计字段，下方各章节字段表**默认不再重复列出**：
>
> | 字段 | 说明 |
> |------|------|
> | `created_by / created_at` | 创建审计字段 |
> | `updated_by / updated_at` | 最近修改审计字段 |
>
> 个别对象有专属审计字段（如 `rule_definition.published_by / published_at` 因只有 Rule 走发布流程），会在该对象字段表内单独列出。`actor` 信息来自上游网关 `X-Actor-Id` header（D14，引擎不维护用户表）。

### 3.1 Tenant（租户）

**是什么**：最外层数据 / 权限隔离边界。`tenant_id` 是所有表的第一个字段，索引前缀必须包含它（见 D3）。

**字段示例**：

| 字段 | 说明 |
|------|------|
| `code` | 对外业务标识（字符串，如 `acme-corp`；DDL 中 `tenant.code`，UNIQUE） |
| `name` | 显示名 |
| `is_default` | 是否默认租户（DDL 列名，单业务线启动时只有一个默认租户） |

> **注**：`tenant.id` 是 BIGINT 自增主键，关联表中的 `tenant_id` 列是指向 `tenant.id` 的外键（BIGINT），不是字符串 `code`。对外 API 请求体使用 `tenantId` 字段传字符串 code，引擎在接入层解析为内部 id。

**关键边界**：

- Tenant 不直接配规则，只做隔离；规则挂在 Tenant 下的 Scene 上。
- 跨 Tenant 的 **metric 定义 / conditionType 实现** 可以共享（平台资产），但规则、Scene 绑定数据严格隔离。

### 3.2 Scene（场景）

**是什么**：Tenant 内的业务域命名空间。四重身份：

1. **Matcher 路由键**：把 (tenant + scene + eventType) 当倒排索引主键，规则候选粗筛从千级降到十级（D8 性能预留）；
2. **资源可见性**（D54 收敛）：metric 在 tenant 级对所有 scene 可用（无 scene 级白名单）；scene 不再承担 metric 治理白名单职责；
3. **数据源初始化锚点**：Scene 有生命周期，启动时按绑定批量预热 MetricSource 资源（DB 连接池 / HTTP client / 缓存），卸载时反向清理；热路径上无懒加载判断；
4. **使用模式声明**：Scene 字段 `dominantMode` ∈ `{PUSH, PULL, HYBRID}`，决定 API 入口 / 前端 UI 行为 / 资源预热范围。

**例子**：

| Scene Code | 模式 | 含义 | 典型绑定 metric |
|------------|------|------|-----------------|
| `marketing.signup` | PUSH | 营销 - 注册激励 | `user.kycLevel` / `user.signupChannel` |
| `marketing.first-trade` | PUSH | 营销 - 首次交易激励 | `user.trade.count.7d` / `user.trade.sum.7d` |
| `risk.transfer` | PULL | 风控 - 转账拦截（同步返回放行/拦截） | `device.fingerprint.risk` / `user.profit.30d` |
| `ops.kyc-followup` | HYBRID | 运营 - KYC 后跟进（既评估打标也用于标签查询） | `user.kycLevel` / `user.country` |

**字段**：

| 字段 | 说明 |
|------|------|
| `scene_id` | 主键 |
| `tenant_id` | 归属租户 |
| `code` | 业务码（如 `marketing.signup`），租户内唯一 |
| `name` | 显示名 |
| `status` | `ACTIVE` / `DISABLED` |
| `description` | 给运营看的业务说明 |
| `dominantMode` | `PUSH` / `PULL` / `HYBRID`，决定 API 入口、UI 行为、预热范围 |
| `payloadSchema` | RuleEvent.payload 允许字段 + 类型 + required（JSON Schema 子集）。规则发布校验 + 事件接入校验 + 前端变量补全都依赖（D13） |
| `subjectType` | 业务主体类型枚举：`USER` / `ACCOUNT` / `DEVICE` / `ORDER` / `CUSTOM`；决定 EvalContext 构建时从哪张主体表取属性（v1 仅 `USER` 实装） |
| `defaultParams` | Scene 级缺省 JSON：`timezone` / `currency` / `defaultRateLimit` / `defaultCacheTtl` 等；规则不显式配置的参数回落到此处 |
| `eventTypes` | 该 Scene 允许的 eventType 白名单数组；事件接入按 (scene + eventType) 二元组校验，规则 trigger 下拉与 Job `eventTypeTemplate` 也按此过滤 |
| `decisionStrategy` | 多规则命中时的合成策略。v1 固定为 `HIGHEST_PRIORITY`（priority 最小者胜出），DDL 层 NOT NULL DEFAULT，PUSH/HYBRID Scene 强制生效（D29）；PULL Scene 不参与合成，配置了也忽略。v2 预留 `MAJORITY` / `CUSTOM_SPI` 扩展位（届时需 `ALTER TABLE MODIFY COLUMN`，非加列） |

metric 在 tenant 级对所有 scene 可用（D54，无 scene_metric_binding）。Scene 与 `JobDefinition` 一对多关联，PULL Scene 不允许配置 Job（发布拒绝 + UI 屏蔽）。详细 DDL 见 [05-storage](./05-storage.md)。

**关键边界**：

- **Scene 变更热加载**（D24）：Scene 配置（bindings / payloadSchema / status）变更后无需重启；单服务模式下由 Modulith `SceneChangedEvent` 触发（毫秒级）；嵌入式 SDK 模式下由 `SceneWatcher`（`DbPollingSceneWatcher`，默认 30s 轮询）触发（30s 最终一致）；Scene `DISABLED` → 从 Matcher 路由表摘除，已进行中的 session 不中断；bindings 变更 → 触发对应 MetricSource 资源重新预热/卸载。
- **同一 Scene 不能跨 Tenant**：`acme.marketing.signup` 和 `beta.marketing.signup` 是两个 Scene。
- **同一 Rule 属于唯一 Scene**：如果两个业务想要"看起来一样的规则"，请各自在自己 Scene 配一条。
- **发布时校验项**（D54）：规则发布前校验 AST 引用的全部 `metricCode` 有 ACTIVE metric（tenant 级）、规则绑定的 decisionCode 在 `decision_definition` 存在（DECISION_CODE_NOT_FOUND）。
- **`payloadSchema` / `eventTypes` 是发布与接入双校验项**（D13）：
  - 规则发布：trigger eventType 必须 ∈ `eventTypes`；AST 引用的 `event.payload.<field>` 必须 ∈ `payloadSchema`；
  - 事件接入：(scene + eventType) 不在白名单的事件拒收；payload 字段不符 schema 的事件拒收（v1 仅做"字段名 + 基础类型"校验，复杂约束留到 v2）；
  - 类型级 `params` JSON schema（ConditionType / MetricType 的参数校验）由各类型注册时附带，与 Scene `payloadSchema` 不在一层，详见 [04-extension](./04-extension.md)。
- **`defaultParams` 是回落值，不是覆盖值**：规则显式配的参数优先；规则没配时引擎读 `Scene.defaultParams`；都没配时引擎用硬编码默认。前端编辑器在该字段位显示"继承自 Scene：xxx"灰字占位。
- **`subjectType` v1 仅 `USER` 实装**：其他枚举值占位，发布时拒绝。EvalContext 构建按 subjectType 选主体表（USER → user_profile / ACCOUNT → account / 等）。

### 3.3 RuleEvent（规则事件）

**是什么**：触发器把外部世界的一次发生翻译成的标准化 POJO。所有触发源（MQ / HTTP / Cron / 内部 SDK）最终都产出 `RuleEvent`。

**字段（不可变）**：

| 字段 | 类型 | 说明 |
|------|------|------|
| `tenantId` | String | 必填 |
| `scene` | String | 必填，路由键（内部 POJO 字段名；API 层 JSON 字段名为 `sceneCode`，见 10-api-contract §三） |
| `eventType` | String | 业务事件类型，如 `user.signup` / `trade.completed` |
| `eventId` | String | 唯一 ID，用于幂等 |
| `subjectId` | String | 业务主体 ID（用户 ID / 账户 ID / 订单 ID） |
| `occurredAt` | Instant | 业务时间，不是引擎收到时间 |
| `payload` | Map<String, Object> | 业务原始数据快照 |
| `providedMetrics` | Map<String, Object> | 预提供的指标值；EvalContext 构建时优先采用、跳过 sourceType 取数（`allowProvided=true` 才生效，D30）。**仅内部链路可填**——公开评估请求体（`EvalEventRequest`）已移除该字段，只能由非公开路径（嵌入式 SDK 宿主 / Job）注入（见 §3.9a 输入契约） |
| `source` | Enum | 事件来源渠道：`HTTP` / `MQ` / `JOB` / `SDK` / `REPLAY`；由注入入口权威设置（D49），不带链式标识（D16） |

> **链路 ID（traceId）不在 RuleEvent 上**：链路追踪是横切的可观测性关注点，由 MDC / OTel 贯穿日志与 span（如 `ApiResponse` 从 `MDC.get("traceId")` 取），不作为领域事件字段重复携带。

**关键边界**：

- `RuleEvent` 是**纯数据**，不包含任何评估结果。
- `payload` 在线协议层是 schemaless 的 Map，但**实际可消费字段由 `Scene.payloadSchema` 约束**（D13）：事件接入校验 + 规则发布校验 + 前端编辑器变量补全都按它走。schema 之外的字段被静默丢弃（v1）或拒收（v2 严格模式）。
- `eventType` 必须 ∈ `Scene.eventTypes` 白名单，不在的事件直接拒收（D13）。
- 不允许在 RuleEvent 里塞"指标"——指标按需在 EvalContext 构建阶段取。
- **Job Trigger 同样产出标准 `RuleEvent`**：调度器到点经 `@RuleJob` 方法查 `JobTarget` 集合 → 经 `RuleEvent.builder` 批量合成（`source=JOB`、`eventId = hash(jobRunId + subjectId)`，payload/providedMetrics 由 JobTarget 携带）→ 注入标准评估链路；下游 Matcher / Rule 完全无感（详见 §3.10）。

### 3.4 Rule（规则）

**是什么**：业务想表达的"在什么条件下，对谁，满足后输出哪个 Decision"。

**核心字段**：

| 字段 | 说明 |
|------|------|
| `ruleId` | 规则 ID |
| `tenantId / scene` | 归属 |
| `name / description` | 给运营看 |
| `kind` | 规则形态枚举：`AST_BOOLEAN` / `SCORECARD` / `DECISION_TREE` / `DECISION_TABLE`（已实装）/ `EXPRESSION_SCRIPT`（未实装，留 v1.5）。发布校验按 kind 校验 AST schema（详见下方 **kind 多态边界**） |
| `triggerEventTypes` | 数组：哪些 eventType 触发本规则（如 `["trade.completed"]`） |
| `ast` | 单棵 `RuleNode` AST 树，整体求值为 boolean（当 `kind=AST_BOOLEAN` 时使用；其他 kind 用各自的 JSON 内部结构，与 ast 互斥） |
| `preGates` | 准入闸门列表（v1 仅灰度命中 ROLLOUT，D52） |
| `status` | `rule_definition` 状态机：`DRAFT` → `PUBLISHED`；`PUBLISHED ↔ DISABLED` 独立分支。版本行（`rule_version`）有自己的状态 `DRAFT` / `ACTIVE` / `SUPERSEDED`（D56）：createDraft/newVersion 产 DRAFT 行，publish 把最新 DRAFT 行**原地翻 ACTIVE**（不增版本）并 supersede 旧 ACTIVE，无需 INSERT 新行（D19/D56） |
| `current_version` | 指向当前生效 `rule_version` 行的**主键 id**（`BIGINT`，即 `rule_version.id`，而非业务版本序号 `rule_version.version`）。`PUBLISHED` / `DISABLED` 状态下有值；`DISABLED` 切换不变更 `current_version`，恢复 `PUBLISHED` 沿用同一版本。`rule_definition` 不冗余持有"最大版本号"——避免双写不一致（D19） |
| `published_by / published_at` | 发布审计字段（D14，仅 PUBLISHED 状态有值；通用 `created_by` / `updated_by` 见 §三 顶部横切说明） |

**灰度（ROLLOUT pre-gate）**（D6；实装见 [`08-evolution.md §2.16`](./08-evolution.md)）：灰度不是独立字段，而是 `preGates` 列表里 `gateType=ROLLOUT` 的一项，params 承载灰度配置（无独立 `rollout` 列，D43）：

| params 字段 | 类型 | 说明 |
|------|------|------|
| `percentage` | Int (0-100) | 百分比放量，命中条件 `bucket < percentage`，等价于桶区间 `[0, percentage)` |
| `bucketStart` / `bucketEnd` | Int (0-100) | 桶区间（成对），命中条件 `bucketStart <= bucket < bucketEnd`；用于 A/B 互斥 |
| `experimentId` | String? | 实验标识；同 `experimentId` 的规则共享分桶种子（一致分桶 / 互斥）；缺省种子退回 `ruleVersionId` |

桶号 `bucket = hash(subjectId, experimentId ?? ruleVersionId) % 100`（D6 派生）。`percentage` 与桶区间二选一（桶区间优先），都不配时该门全量放行。**按用户标签命中（`USER_TAG` / `HYBRID` / `tagConditions`）v1 未实装，留演进**（将来作为独立 pre-gate 类型落地，不复活 `rollout` 列）。稳定哈希是 D6 灰度桶稳定性的承诺，版本切换触发桶漂移是固有语义，不在配置层兜底。

**EvalResult 输出契约（多态，v1 仅填 `satisfied`）**：

```
EvalResult {
    satisfied:       boolean              // 所有 kind 都填；AST_BOOLEAN 表"整树求值结果"；API 层序列化为 ruleHit（见 10-api-contract §三）
    score?:          Number               // SCORECARD 启用
    category?:       String               // DECISION_TREE 启用
    decision?:       Map<String, Object>  // DECISION_TABLE 启用，输出列的命中行（与 finalDecision 正交）
    finalDecision?:  DecisionRef          // D26：合成后最终 Decision（Scene 配了 decisionStrategy 时填充）
    hitDecisions:    List<DecisionRef>    // D26：所有命中规则的 Decision 按 priority 排序（始终填充，无绑定时为空列表）
    trace:           List<NodeTrace>      // 节点级 trace（D7），所有 kind 都填
    errorCode?:      String               // D15：METRIC_FETCH_FAIL / CONDITION_EVAL_ERROR（见 10-api-contract §七；PAYLOAD_SCHEMA_MISMATCH 是入口层 400，不进入评估链路）
    errorMessage?:   String               // D15：人读错误信息
    failedNodeIds?:  List<String>         // D15：哪些 AST 节点失败
    partial?:        Boolean              // D15：true=部分成功，false=完全失败
}

DecisionRef {
    code:              String  // Decision.code
    name:              String  // Decision.name（快照，不随 Decision 改名变化）
    priority:          Int     // 合成排序依据
    fromRuleVersionId: Long    // 哪条 RuleVersion 产生了该 Decision
}
```

**kind 多态边界**：详见 [`08-evolution.md`](./08-evolution.md) §2.1 kind 多态。

**关键边界**：

- **Rule 是版本化的**：版本快照在 createDraft（v1）/ newVersion（v_max+1）时产生并冻结（premise A，D56），进行中的评估锁定 ACTIVE 快照版本（D6）。
- **Rule 不直接含 Condition**：Condition 是 AST 的叶子节点（`ConditionNode`），不能脱离 AST 存在。
- **Rule 内表达任意复杂逻辑**全靠 AST：`AndNode` / `OrNode` / `NotNode` 任意嵌套，没有"层数"限制。
- **AST 节点上的 `displayLabel`** 是给运营 UI 看的分组标题，后端评估时忽略它，只看逻辑结构。
- **已实装 `kind`**：`AST_BOOLEAN`（v1）/ `SCORECARD`（D12）/ `DECISION_TREE` / `DECISION_TABLE`（D42）；`EXPRESSION_SCRIPT` 未实装（留 v1.5）。发布校验按 kind 校验 AST schema；演进说明详见 [`08-evolution.md`](./08-evolution.md) §2.1 kind 多态。
- **评估失败单节点降级，整树继续短路求值**（D15）：单个 `ConditionNode` 失败 → 该节点 satisfied=false，其他节点正常评估；整树评估完毕后若有失败节点，`EvalResult.errorCode` 非空。规则间隔离：单条 Rule 失败不影响同 (scene + eventType) 下其他 Rule。PUSH 默认安静失败；PULL 返回 `{satisfied, errorCode}`，调用方按 fail-secure / fail-open 决策。对账四态：`HIT / MISS / BLOCKED / ERROR`（D22）。
- **运行时锁定快照版本**（D17 派生）：evaluation_session 开始时拍当前候选规则版本快照，整 session 用同一快照——即使中途发生 publish 切版本，本次评估不受影响。索引热更：单服务模式由 Modulith `RulePublishedEvent` 触发（毫秒级）；嵌入式 SDK 模式由 `DbPollingRuleWatcher`（默认 15s 轮询）触发（15s 最终一致）。
- **草稿即冻结快照，校验前移到草稿写入期**（premise A，D56）：createDraft / editDraft / newVersion 时即跑全套 `resolveAndValidate`（metric 须 ACTIVE、payload 字段须在 `Scene.payloadSchema` 声明、decision 须存在、kind 结构 + 算子×dataType 校验），不过即拒（400），不落库非法草稿。落库的 DRAFT 行已是完整冻结快照（`resolvedAst` 含 dataType、`metric_dependencies`/`payload_dependencies` 已冻、`decision_bindings` 含 `name`、`trigger_event_types` 是草稿自己声明的值）。**publish 退化为激活**：把最新 DRAFT 行原地翻 ACTIVE（不增版本、不重解析），supersede 旧 ACTIVE，单事务内迁状态 + 写 audit_log + 发 `RulePublishedEvent`；保证"dry-run 预览 == 发布"。版本号只在 createDraft（v1）/ newVersion（v_max+1）产生，editDraft 原地、publish 激活都不增。批量发布由前端拆成逐条调用，v1 不提供批量原子 API。
- **"回滚到旧版本" = newVersion 带 `fromVersionId`**（D19/D56）：克隆指定旧版本的输入意图、按当前世界重新解析冻结产出 `v_max+1` DRAFT，再显式 publish 激活；不可变快照永不覆盖。
- **删草稿边界**（D56）：deleteRule 仅删从未发布过的规则（无 ACTIVE/SUPERSEDED），级联删 `rule_definition` + 全部 `rule_version`；deleteDraftVersion 仅删 DRAFT 版本行；碰 ACTIVE/SUPERSEDED 一律拒（只能 disable）。级联范围只 `rule_version`/`rule_definition`，dry-run 痕迹（`dry_run_session`/`dry_run_node_trace`）视同审计历史不级联删，靠 TTL 退休。
- **DISABLED 状态从倒排索引剔除**（D17 + D19 派生）：`PUBLISHED → DISABLED` 切换后，下一次索引热更（单服务模式：事件触发，毫秒级；SDK 模式：15s 轮询）将该 RuleVersion 从内存 `(scene, eventType) → List<RuleVersionSnapshot>` 倒排索引中剔除；`DISABLED → PUBLISHED` 切换则按同一窗口重新入索引。`current_version` 指针在切换过程中**不变**（D19）——索引剔除/回填只动运行时视图，不动 `rule_version` 表内容。

### 3.5 AST 与"分组心智"

引擎用 **sealed `RuleNode`** 表达任意布尔结构：

```
sealed RuleNode {
    AndNode { children: List<RuleNode>,  displayLabel?: String,  weight?: Number }  // weight: D12 SCORECARD kind 专用，v1 忽略
    OrNode  { children: List<RuleNode>,  displayLabel?: String,  weight?: Number }  // weight: D12 SCORECARD kind 专用，v1 忽略
    NotNode { child:    RuleNode }
    ConditionNode {
        conditionType: String,
        params:        Map<String, Object>,
        metricCode?:   String,
        displayLabel?: String,
        weight?:       Number  // D12 SCORECARD kind 专用，v1 忽略
    }
}
```

**为什么不固化 RuleGroup 独立实体**：

- 90% 运营心智里的"分组"只是**视觉折叠 + 命名**，AST 节点上一个 `displayLabel` 字段即可承载；
- AST 任意嵌套 AND/OR/NOT，比"两层 Group + Group 内固定关系"表达力强；
- v2 真要做"独立分组实体 / 跨 Rule 复用 / 分组权限"再升级，AST `displayLabel` 升级到独立表零损耗（详见 §五 Q2）。

**节点级 trace 仍然按 AST 节点维度落库**：每个节点（不管 And / Or / Not / Condition）的 `satisfied` + `actualValue` 都写 `node_trace` 表，运营自助排障靠这套数据（见 D7）。`node_trace` 表同时容纳 Pre-Gate 失败节点（节点类型 `PRE_GATE_BLOCKED`，详见 §3.14），不另起独立表；具体列字段差异（如 Pre-Gate 不持有 conditionType / actualValue）由 [`05-storage.md`](./05-storage.md) §node_trace 表 展开。**写入模式异步批写**（D21）：评估期 trace 在 `EvalContext` 内存累积 → `EvalResult` 出树时一次性入 `TraceWriter` 队列 → 消费者线程池按条数 / 时间阈值 batch insert；失败降级丢弃 + counter 告警，**不**阻塞热路径、**不**回写 `EvalResult.errorCode`（trace 是旁路观察通道）。与 `audit_log`（D14：人的行为，必须同步事务）严格分离。

### 3.6 Condition（条件，AST 叶子节点）

**是什么**：AST 的叶子节点，一条原子判断。由 `conditionType` 决定语义，参数化配置。

**典型 conditionType**（算子码本身即 conditionType，内置清单见 [`03-rule-expression.md`](./03-rule-expression.md) §三）：

| conditionType | 例子 |
|---------------|------|
| `EQ` | `metric:user.kycLevel == 2` |
| `BETWEEN` | `metric:user.age ∈ [18, 65)` |
| `GTE` | `metric:user.trade.sum.7d >= 1000` |
| `EQ`（`valueRef=PAYLOAD`） | `payload:currency == "USD"` |
| `time.window` | `now in [9:00, 22:00] timezone Asia/Shanghai` |

**节点字段**（持久化在 AST JSON 内，不另存表）：

| 字段 | 说明 |
|------|------|
| `conditionType` | 路由键，决定用哪个 `ConditionEvaluator` 实现 |
| `params` | JSON 参数（依 conditionType 而异） |
| `metricCode` | 当 conditionType 涉及指标时，引用一个 Metric（须为 tenant 级已注册的 ACTIVE metric，D54） |
| `displayLabel` | 给运营 UI 看的别名 |
| `weight` | 可选数值，v1 评估器**忽略**该字段；`Rule.kind = SCORECARD` 时启用，表示该条件命中对总分的贡献（D12 预留） |

**关键边界**：

- **Condition 不取数**：取数是 Metric / EvalContext 的职责；Condition 只判断**已经在 EvalContext 里的数据**。
- **同一 conditionType 跨 Rule 复用**：一个 `@ConditionType("geo.within_country")` 注解的自定义 Evaluator 是平台资产，可被任意 Rule 引用。
- **Condition 是 AST 中的叶子，不能脱离 Rule 单独存在**：增删 Condition 即修改 AST，走 Rule 版本升级流程。

### 3.7 Action（动作子系统，已移除，D60）

> **已移除**：D60 起引擎纯决策化，删除整个动作子系统——`ActionHandler` SPI / `@ActionType` 注解 / `ActionContext` / `ActionResult`、`Decision.actions` / `DecisionBinding.actions`、动作派发与 `action_execution` 落库、`decision_definition.actions` 列（迁移 V1_27）。引擎只产出 **Decision**（`evaluate()` 返回 `EvalResult{finalDecision, hitDecisions, ...}`，SDK 侧 `EvalResultListener` 决策输出钩子）；"命中后做什么"（发券 / 拦截 / 通知 / 调外部系统）归**消费方 / 流程引擎**（对标 OPA 的 PEP、Camunda 的 BPMN，预期搭档 Flowable，以 Service/HTTP Task 把本引擎当决策节点调用）。PUSH 模式仍异步评估 + 落库 `evaluation_session` / trace，**仅去掉动作派发**。

### 3.8 EvalContext（评估上下文）

**是什么**：一次评估的运行时只读快照。**只为这一次评估存在**，评估完即丢弃。

**结构**：

```
EvalContext {
    tenantId: String                 // 租户 ID
    event:    RuleEvent              // 原始事件
    subject:  Subject                // 业务主体（用户 / 账户 / 设备）的属性快照
    metrics:  Map<metricCode, Value> // 本次评估涉及的指标快照
    now:      Instant                // 评估开始时间（统一时钟）— 已实装（B20）：由 EvalServiceImpl.doEvaluate / EvalEngine.evaluate 入口注入一次（单个 Instant.now()），整棵 AST 共用同一个 now，保证跨规则时钟一致性；不存在默认 Instant.now() 重载（禁止）
}
```

> **`traceId` / `dryRun` 不是 EvalContext 字段**：链路 ID 走 MDC / OTel 横切层（见 §3.3）；dry-run 是引擎内部路由标志，由 `EvalServiceImpl.doEvaluate` 单独以 `isDryRun` 入参传递（true 时 TraceWriter 写 `dry_run_session` 系列表而非 prod 表，D7/D21），不进 EvalContext。

**AST 条件表达式的内置可寻址路径**（D20 §3 闭合枚举——发布期输入引用闭合校验的根路径表）：

这 7 条路径是 ConditionNode 表达式可以**按名字直接引用**的内置字段，来自 `RuleEvent` + 引擎注入，不需要注册 Metric。与 `Scene.payloadSchema` 字段集合、tenant 级已注册 metric**三者并集**构成发布期允许的完整引用范围。

| 引用路径 | 类型 | 来源 | 语义 |
|----------|------|------|------|
| `now` | `Instant` | 引擎注入 | 评估开始时间（统一时钟，跨规则一致） |
| `tenantId` | `String` | 上游 + RuleEvent | 租户 ID，多租户隔离主键（D3） |
| `scene` | `String` | RuleEvent | 业务域命名空间（Matcher 路由键） |
| `eventType` | `String` | RuleEvent | 事件类型（Matcher 倒排索引第二级） |
| `occurredAt` | `Instant` | RuleEvent | 业务事件发生时间（可早于 `now`）；发布期闭合校验路径名为 `occurredAt`，是 `EvalContext.event.occurredAt` 的扁平化别名，两种写法等价 |
| `subjectId` | `String` | RuleEvent | 业务主体 ID（用户 / 账户 / 设备 / 订单），灰度桶 hash 输入 |
| `ruleVersionId` | `Long` | Matcher 锁定 | 当前评估的 RuleVersion id，与 `subjectId` 一同作为灰度桶稳定性输入（D6） |

> 新增内置路径需走决策（影响所有已发布 RuleVersion 的校验集合）。`subject` / `metrics` 属于运行时填充体，不参与发布期闭合校验。

**关键边界**：

- **EvalContext 是不可变的**：Evaluator 不能修改 EvalContext；评估过程对上下文只读。
- **EvalContext 按需构建**：扫 AST 收集涉及的 `metricCode`，并发取数，组成本次 EvalContext；没引用的指标不取（D5 派生）。
- **EvalContext ≠ 数据库快照**：EvalContext 里的 `subject` 可能比 DB 新（如某个属性是从事件 payload 补的），以 EvalContext 为准。
- **`dryRun` 不参与 AST 引用**：`dryRun` 是引擎内部路由标志，不在 D20 §3 闭合校验的可寻址路径表中，ConditionNode 表达式不能引用它；仅 TraceWriter / Dispatcher 内部按此标记路由写入目标（D7 / D21）。
- **`providedMetrics` 优先于 sourceType 取数**（D30）：内部 `RuleEvent` 持有 `providedMetrics` 时（非公开链路注入——SDK 宿主 / Job；**公开请求体已移除该字段，D55**，见 §3.9a），EvalContext 构建阶段对每个 metric 先查 `providedMetrics`；有值且 `allowProvided=true` 则直接用，跳过 sourceType 取数；`allowProvided=false` 的 key 即使传了也忽略（WARN 日志）。`providedMetrics` 的值只活在本次评估，不持久化。trace 记录每个 metric 的 `valueSource: PROVIDED | FETCHED`。

### 3.9 Metric（指标）

**是什么**：被命名、被注册的取数原子。规则 AST 通过 `metricCode` 引用它，不内嵌 SQL / 不嵌代码。

**例子**：

| metricCode | 含义 | 实现 |
|------------|------|------|
| `user.kycLevel` | 用户 KYC 等级 | 同步查 `user_profile` 表 |
| `user.trade.count.7d` | 用户近 7 天成交单数 | SQL 聚合 `trade_history` |
| `user.profit.30d` | 用户近 30 天净盈利 | 调外部指标平台 HTTP |
| `device.fingerprint.risk` | 设备指纹风险分 | 调风控服务 |

**字段（定义层，租户/全局级）**：

| 字段 | 说明 |
|------|------|
| `metricCode` | 全局唯一，命名 `<domain>.<entity>.<measure>[.<window>]` |
| `metricVersion` | 已实装（B6）：`metric_definition.version` 列；规则发布期冻结 (metricCode, metricVersion) 绑定，评估期按绑定版本解析定义，详见 [`08-evolution.md §2.2`](./08-evolution.md) |
| `tenantId` | 归属租户；`*` 表示平台级共享指标 |
| `sourceType` | 取数方式，见下方 sourceType 对比表 |
| `params` | 取数参数（结构依 sourceType 而异，见下方对比表） |
| `dataType` | `LONG` / `DOUBLE` / `STRING` / `BOOLEAN` / `LIST` / `DATE` / `DATETIME` — `DATE` 对应日历日期（`LocalDate`，无时区）；`DATETIME` 对应时刻（`Instant`/带时区偏移，时区相关）；已在 B20 作为一等 dataType 实装，发布期矩阵和运行期策略均支持（详见 `03-rule-expression.md` §3.4） |
| `cachePolicyDefault` | 默认缓存策略（TTL / 不缓存 / 评估范围内缓存）；实时性敏感场景配 `ttl=0` 强制每次取数 |
| `allowProvided` | 是否允许调用方通过 `providedMetrics` 覆盖本指标取数结果（D30）。按 `sourceType` 给推荐默认值：`ATTRIBUTE` / `EXTERNAL_HTTP` 建议创建时显式设为 `true`（业务方通常手里就有这个值）；`SQL_AGGREGATE` / `STREAM` 保持 `false`（平台权威计算，不应被覆盖）。DDL 列级 `DEFAULT 0` 是保守兜底，应用层 API 按 `sourceType` 写入正确值，不依赖列默认。例外情况手动覆盖；`false` 时引擎忽略 `providedMetrics` 中对应 key 并 WARN |

**sourceType 对比表**：

| sourceType | 取数方式 | 适用场景 | `params` 关键字段 | `cacheTtl` 建议 | `allowProvided` 默认 |
|------------|---------|---------|------------------|----------------|---------------------|
| `ATTRIBUTE` | 从主体属性表（`subject_attribute` 或业务库指定表/列）读单值 | KYC 等级、会员等级、账户状态等慢变属性 | `table`, `column` | 60–300s | `true` |
| `SQL_AGGREGATE` | 执行 SQL 聚合查询（命名参数 `:subjectId` / `:tenantId` / `:now` / `:payload.x` / `:params.x`；禁 DB 时间函数与 `${}` 拼接） | 近 N 天交易次数、累计金额、历史行为统计 | `datasource`（命名只读源）, `sql` | 3600s（聚合结果更新慢；见 04-extension §4.3） | `false` |
| `EXTERNAL_HTTP` | 引用**命名连接器**（D72：`ConnectorDescriptor` 声明 request 模板 / response 映射 / 鉴权 / 弹性 / 错误映射；descriptor 内 `endpointRef` 指向 infra 注册的命名端点，baseURL+凭证只在端点层，灭 SSRF），声明式从外部 HTTP 服务取一个值 | 设备指纹分、IP 信誉、第三方评分 | `connector`（`connector_definition.connector_code`）, `vars`（连接器入参，渲染模板里 `{vars.x}`） | 60s 左右 | `true` |
| `STREAM` | 从流处理平台（Flink / Kafka）读预聚合结果（v1 占位，v2 接入） | 实时 CEP 序列特征、滑动窗口计数 | `topic`, `keyExpr` | `0`（流结果已是最新） | `false` |

> `params` 完整字段 schema、`EXTERNAL_HTTP` 声明式连接器契约（descriptor 字段 + 响应映射 / 鉴权 / 错误归一带编号 Requirement C1–C5）、`STREAM` 适配协议见 [`04-extension.md`](./04-extension.md) §4.5 与 §MetricSource 实现指南。

**可见性**（D54）：metric 在 **tenant 级**对所有 scene 可用，无 scene 级绑定白名单（原 `scene_metric_binding` 表已移除）。

**关键边界**：

- **定义 tenant 级，全租户可见**：避免每个 Scene 重新发明"近 7 天交易额"；metric 是 tenant 级共享资产。
- **应用启动时预热 MetricSource**：JDBC 连接池、HTTP client、缓存初始化等在启动时完成；热路径上无懒加载判断。
- **同一指标可被多 Rule / 多 Condition 引用**，引擎按 evalSession 维度去重取数。
- **Metric 不做判断**，只输出值。"值是否满足阈值"是 Condition 的事。
- **指标定义是治理对象**：新增 metric 要走平台审批；metric 一经注册（ACTIVE）在 tenant 级对所有 scene 可用（D54，无 scene 级白名单审批环节）。
- **MetricRegistry 并发契约**：读路径必须 thread-safe 且不阻塞热路径；评估期内读到的快照保持稳定（评估期间发生 metric 变更不影响本次评估结果）。具体并发策略（不可变快照 / ConcurrentHashMap / copy-on-write 等）由实现层选择，详见 [`04-extension.md`](./04-extension.md) §MetricSource 实现指南。
- **缓存与实时性权衡**：`cachePolicyDefault` 对实时性敏感场景（风控阈值、额度校验）配 `ttl=0` 强制每次取数；可放宽场景（营销画像、近 30 天聚合）按业务可接受延迟配 `ttl>0`，由 Scene `cache_policy_override` 局部收紧；引擎不替业务选默认策略，由 metric 注册者声明。
- **预拉值评估期内冻结**（D20 §1 派生）：D20 metric 批量预拉后注入 `EvalContext` 即视为本次评估的不可变快照；评估期内**不**再受 `cachePolicyDefault.ttl` 影响，即使评估耗时跨过 TTL 边界，本次评估仍读初始预拉值。TTL 只作用于"下次评估是否复用上次缓存值"层面，与"本次评估内的取数稳定性"无关。
- **超时与异常归一**（D15 派生）：`MetricSource` 实现自管 timeout / retry / 熔断（不同 `sourceType` 合理默认不同：EXTERNAL_HTTP 短超时、SQL_AGGREGATE 中超时，建议值见 [`04-extension.md`](./04-extension.md) §MetricSource 实现指南）；任何取数异常（timeout / 熔断 / 连接拒绝 / 反序列化失败）统一归 D15 `METRIC_FETCH_FAIL`，引擎核心不重试。
- **业务共享常量建议建为只读 metric**：跨多条规则共享的业务阈值 / 配置值（如 VIP 门槛、风控分数线）**建议建为只读 metric**（`sourceType=ATTRIBUTE` 或固定返回值的 `EXTERNAL_HTTP`），复用 metric 的 `cachePolicy` / 版本化通道，不另设"常量库"一等概念。引擎不内置 urule 风格的 ConstantLibrary——若业务常量变更频次很低也可直接内联到 `ConditionNode.params`，二选一由业务方按变更频次自决。独立常量库一等概念的演进留 [`08-evolution.md`](./08-evolution.md) §四。
- **payload vs metric —— "指标身份"判据**：事件自带的事实（`amount` / `currency` 等）走 **payload 直接引用**（`ConditionNode.valueRef=PAYLOAD`，`metricCode` 复用为 payload 字段名，须在 `Scene.payloadSchema` 声明），不注册 metric、不进 `providedMetrics`；受治理指标（`user.risk.score` 等）走 **metric**。判别测试——任一为 yes 走 metric，全 no 走 payload：需要取数 / 需要权威保护（`allowProvided=false`）/ 跨规则复用同一定义 / 要版本化 / 要下发 SDK / 要影响面查询。`amount` 永远是 payload（这笔交易的事实）；`user.risk.score` 永远是 metric（哪怕本次值由上游 `providedMetrics` 注入，身份仍是受治理指标）。AST 写法 + 操作符见 [`03-rule-expression.md`](./03-rule-expression.md) §2.6。

### 3.9a 输入契约 / 场景输入清单

**是什么**：调用方对一个场景发评估时"该精确传哪些字段"的对外契约。三个相关概念的边界：

| 概念 | 定义 | 谁提供 |
|------|------|--------|
| **payload（事件事实）** | 一次事件自带的原始事实（`amount` / `country` 等），是**公开评估接口上唯一的业务输入**；规则用 `valueRef=PAYLOAD` 直接引用，须在 `Scene.payloadSchema` 声明 | 调用方随评估请求传 |
| **metric（受治理指标）** | 被命名注册的取数原子（§3.9），100% 引擎侧——按 `sourceType` 自取（取数），或经非公开路径（嵌入式 SDK 宿主 / Job）注入；**不再由公开评估调用方提供** | 引擎 |
| **场景输入清单** | 该场景所有 status=ACTIVE 规则引用的 payload 字段的**并集**（`{ name, dataType, required }`），是调用方导向的输入契约 | 引擎按发布期快照聚合 |

**关键边界**：

- **公开评估接口只收 payload**：`providedMetrics` 已从公开请求体（`EvalEventRequest`）移除；调用方碰不到引擎内部的 metric taxonomy。"上游注入受治理 metric"是非公开路径——内部 `RuleEvent` 仍持 `providedMetrics`（供 SDK 宿主 / Job 注入），但通用 HTTP 调用方填不进去（D30 的 `providedMetrics` 语义在公开侧退场，仅存于内部链路）。
- **清单来源 = 发布期快照**：发布时每条规则把引用的 payload 字段（`valueRef=PAYLOAD` 节点）连同从 `Scene.payloadSchema` 取的 `dataType` / `required` 冻结进 `rule_version.payload_dependencies`（与 `metric_dependencies` 同套路、守 D6 不可变 + 评估零额外查询），随 `RuleVersionSnapshot.payloadDependencies` 下发到评估侧。场景级清单 = 该场景 ACTIVE 规则快照清单的并集（同名去重）。
- **可发现 + 评估期校验**：调用方经 `GET /api/v1/rule/scenes/{sceneCode}/input-manifest`（10-api-contract §3.4）拿到清单；评估期按候选快照清单并集校验请求 payload——必填缺失 → `MISSING_REQUIRED_INPUT`（整体拒绝 400，不降级），类型不符 → `INPUT_TYPE_MISMATCH`（400），多塞的未被引用字段忽略。与发布期 `UNRESOLVED_VARIABLE`（规则别越界引用没声明的字段）正交：一个管授权期"规则别越界"，一个管调用期"调用方别漏传 / 错类型"。

### 3.10 Job（定时触发，不是一等公民）

**是什么**：把"定时类规则"接入引擎的 Trigger 适配器，**不引入第四个一等概念**。调度器到点后：

1. 按 `JobDefinition.subjectQuery`（`type=BEAN_METHOD`）反射调用 `@RuleJob` 业务方法，查出本批次 `JobTarget` 集合（每个含 subjectId + 可选 payload + 可选 providedMetrics）；
2. 经 `RuleEvent.builder` 为每个 `JobTarget` 合成 `RuleEvent`（`source=JOB`、`eventId = hash(jobRunId + subjectId)` 与 `record_no` 模式同构幂等，payload/providedMetrics 由 JobTarget 透传）；
3. 批量注入标准评估链路（Matcher → Pre-Gate → EvalContext 构建 → AST → Decision），下游完全无感。

**字段（JobDefinition）**：

| 字段 | 说明 |
|------|------|
| `id` | 主键（DDL 列名；概念层有时称 `jobId`） |
| `tenantId / sceneCode` | 归属（DDL 列 `tenant_id` / `scene_code`，关联 scene.code，与 RuleEvent.sceneCode / SceneService 口径一致；PULL Scene 拒绝绑定） |
| `name` | 给运营看的名称 |
| `cronExpression` | 标准 cron 表达式（DDL 列名 `cron_expression`）；时区可选——cron 自带时区时以 cron 为准（如 `CRON_TZ=Asia/Shanghai 0 30 0 * * *`），未指定时回落 `Scene.defaultParams.timezone`，仍未配回落引擎默认（`UTC`） |
| `subjectQuery` | 主体集合查询配置 JSON（DDL 单列）。`type=BEAN_METHOD`（首期，`@RuleJob` 注解的业务查询方法，`ref=<bean>#<method>`，主体由方法返回）/ `EXTERNAL_HTTP` / `METRIC_RESULT`（后续）。详见下方「Job 定义方式」 |
| `eventType` | 合成 RuleEvent 时使用的 eventType（DDL 列名 `event_type`；概念层有时称 `eventTypeTemplate`） |
| `payloadTemplate` | DDL 遗留列，D49 后不再使用——payload 改由 `@RuleJob` 方法返回的 `JobTarget.payload` 直接携带，不再做占位符模板渲染 |
| `concurrency` | 单次运行并发 fan-out 上限（调度器运行时配置，非 DDL 独立列，存于 `subject_query` JSON 或外部配置） |
| `rateLimit` | 注入引擎事件速率上限（保护下游；同 `concurrency` 为运行时配置） |
| `status` | `ACTIVE` / `DISABLED`（DDL ENUM；概念层有时写 `PAUSED`，DDL 对应值为 `DISABLED`） |

**Job 定义方式（D48 / D49）**：Job 由 `@RuleJob` 注解定义——开发者在 Spring Bean 方法上标注（`code`/`cron`/`tenant`/`scene`/`eventType`），方法体即自定义主体查询，返回 `List<JobTarget>`（每个 `JobTarget` 含 `subjectId` + 可选 `payload` + 可选 `providedMetrics`，如「查 10 分钟前登录的用户并带上其评分」）。启动期 `RuleJobScanner` 扫描注解 → upsert 到 `job_definition`（`subjectQuery.type=BEAN_METHOD`，`ref=<bean>#<method>`）→ 注册调度。触发时 `JobRunner` 经 `RuleEvent.builder` 把每个 `JobTarget` 合成事件（`source=JOB`、payload/providedMetrics 透传、`eventId=hash(jobRunId+subjectId)`）注入 `acceptEvent`。运营 API（`/admin/v1/jobs`）只做管理（列表 / 详情 / 启用 / 禁用 / 手动触发 / 执行记录），**不含创建**——"查哪些主体"是业务代码逻辑，不由运营在 UI 配；规则本身仍由运营事先经 Scene / Rule API 配好，Job 只定时触发对其评估。

**字段（JobExecution，每次运行的记录）**：

| 字段 | 说明 |
|------|------|
| `id` | 主键（DDL 列名；概念层有时称 `jobRunId`，指同一字段） |
| `jobDefinitionId` | 归属 Job（DDL 列名 `job_definition_id`） |
| `triggerAt` | 调度器触发时间（DDL 列名 `trigger_at`） |
| `finishedAt` | 完成时间（DDL 列名 `finished_at`，nullable） |
| `subjectCount` | 查询到的主体总数 |
| `successCount` | 成功注入评估链路的主体数（DDL 列名 `success_count`） |
| `errorCount` | 失败数（DDL 列名 `error_count`） |
| `status` | `RUNNING` / `SUCCESS` / `PARTIAL_FAIL` / `FAILED`（DDL ENUM 值，`PARTIAL_FAIL` 无 ED 后缀） |
| `errorSummary` | 错误明细摘要（DDL 列名 `error_summary`） |

**调度器接口（`Scheduler` SPI，rule-kernel）**：

```
interface Scheduler {
    void schedule(String jobCode, String cronExpression, Runnable task);  // 注册 cron 周期任务
    void unschedule(String jobCode);                                       // 撤销
}
```

SPI 仅保留「注册 / 撤销 cron 任务」最小职责（cron → Runnable），不耦合 JobDefinition 业务模型。早期设计草案在 `Scheduler` 上画的 `register/unregister/triggerOnce/status/recentRuns` 等业务方法（D47 落地时）按下表重新落位——能力不丢，只是从 SPI 方法变为 `JobService` 方法或内部机制：

| 原草案 SPI 方法 | 落位 |
|------|------|
| `register(JobDefinition)` / `unregister(jobId)` | 内部 `JobScheduleManager`（启动期 RuleJobScanner / enableJob 注册、disableJob 撤销，自动调 `schedule/unschedule`） |
| `triggerOnce(jobId)` | `JobService.triggerOnce(tenantId, jobId)`（手动触发一次，不经调度器） |
| `recentRuns(jobId, limit)` | `JobService.recentExecutions(tenantId, jobId, limit)` |
| `status(jobId)` | `JobService.getJob()` 返回的 `status`（ACTIVE/DISABLED）；进程内单实例下调度运行态≈配置态，未单独暴露，多实例时再补 |

`ThreadPoolSchedulerAdapter`（进程内 `ThreadPoolTaskScheduler` + `CronTrigger`，单实例）是 v1 首个实现；多实例部署会重复触发，属已知限制，需 HA 时替换为选主或外部调度（xxl-job），业务侧 `JobDefinition` / `JobExecution` 不变。

**关键边界**：

- **Job 不增加抽象层**：Job 只是 Trigger 适配器，业务层规则、AST、Decision 模型完全不变。
- **仅 PUSH / HYBRID Scene 可绑定 Job**：PULL Scene 是同步业务调用语义，定时触发没意义；发布拒绝 + UI 屏蔽。
- **幂等 = Redis trySet + DB uk 双兜底**：`eventId = hash(jobRunId + subjectId)` 落 `evaluation_session` 的 eventId 列，`evaluation_session(tenant_id, event_id)` 维持 DB unique key 作为"下半层"兜底（Redis trySet 是上半层快速路径，DB uk 是持久化最终校验，D11 / D21 均依赖此双层结构）；重跑同一 jobRun 不会重复评估。具体 DDL 详见 [`05-storage.md`](./05-storage.md)。
- **rateLimit 是注入端控制**：调度器按 `rateLimit` 缓冲注入，下游 Matcher / 评估链路不需要再做令牌桶。
- **Job 与规则灰度独立**：Job 负责"对谁发事件"，规则的 ROLLOUT 灰度仍按命中算法决定"对哪些主体最终命中"——Job 不要做灰度抽样，二级灰度逻辑只会让排障复杂。
- **灰度桶计算时机**（D6 + D11 派生）：所有 RuleEvent（含 Job 合成）的灰度桶在**引擎 Pre-Gate 阶段**按 `hash(subjectId, experimentId ?? ruleVersionId)` 算（D6）；Job 端只负责合成 RuleEvent 注入，**不**在调度器端预算桶 / 预筛主体——预算桶会让 Job 与具体 RuleVersion 形成隐式耦合，违反"Job 不要做灰度抽样"约束。

### 3.11 AuditLog（操作审计，不是一等公民）

**是什么**：D14 引入的"人的行为"记录表，与 `evaluation_session` / `node_trace`（系统行为）严格分离。所有对 Rule / Scene / Metric / Decision / Job 等配置对象的 CREATE / UPDATE / PUBLISH / PUBLISH_FAILED / ENABLE / DISABLE / DELETE 操作落 `audit_log` 表（D19：发布事务回滚后单独追加 `PUBLISH_FAILED` 记录）。

**字段**：

| 字段 | 说明 |
|------|------|
| `audit_id` | 主键 |
| `tenant_id` | 归属租户（跨租户管理员用特殊 `__platform__`） |
| `actor` | 操作人标识（用户 ID / 系统标识 / Job 标识），由上游网关 `X-Actor-Id` header 注入 |
| `actor_type` | `USER` / `SYSTEM` / `JOB` |
| `target_type` | 操作对象类型：`RULE` / `SCENE` / `METRIC` / `DECISION` / `JOB` / ... |
| `target_id` | 对象 ID |
| `action` | 动作：`CREATE` / `UPDATE` / `PUBLISH` / `PUBLISH_FAILED` / `ENABLE` / `DISABLE` / `DELETE` / `IMPORT`（D19：发布事务回滚后单独追加 PUBLISH_FAILED 记录；B7：Bundle 导入逐条落草稿记 IMPORT） |
| `before_snapshot` | 变更前的 JSON 全量快照（DELETE / UPDATE 时填） |
| `after_snapshot` | 变更后的 JSON 全量快照（CREATE / UPDATE / PUBLISH 时填）；`PUBLISH_FAILED` 时填错误诊断 JSON（含 `errorCode` / `stackTrace` 摘要，详见下方关键边界） |
| `operated_at` | 操作时间（DDL 列名；与 `evaluation_session.occurred_at` 含义不同，后者是业务事件时间） |
| `trace_id` | 关联请求链路 ID |

**关键边界**：

- **鉴权交给上游网关**：引擎不验签、不维护用户表 / 角色表 / 权限表（D14）。actor 信息来自请求头 `X-Actor-Id` / `X-Actor-Type`；上游网关已通过 JWT / SSO 验证过身份，引擎信任 header。
- **审计写入与业务操作同事务**：保证"操作成功 → 审计必有记录"；审计写失败回滚业务变更。
- **审计表只增不改**：DELETE 操作只删 `target` 对象本身，audit_log 行永远保留（合规需要）。
- **不做篡改防护**（hash chain / WORM 存储）：v1 信任 DB；高合规场景留到 v2。
- **跨租户管理员**：使用 `tenant_id = "__platform__"` 的特殊 actor 实现，业务约定，不入 schema。
- **错误诊断信息走 `after_snapshot`**：`PUBLISH_FAILED` 记录的 `errorCode` / `stackTrace` 摘要（D19）以及发布期校验错误码（D20 §3 `UNRESOLVED_VARIABLE` 等）统一放入 `after_snapshot` JSON 字段，不为此另加表列；查询时按 JSON path 取（具体 path 由 05-storage 定义）。**已知 `after_snapshot.errorCode` 值（v1，发布期校验错误码）**：

| errorCode | 含义 |
|-----------|------|
| `UNRESOLVED_VARIABLE` | D20 §3 输入引用闭合校验失败：conditionAst 引用了未在 Schema / tenant 级已注册 metric / EvalContext 标准字段中声明的变量名 |
| `DECISION_CODE_NOT_FOUND` | Rule 的 `decisionBindings` 引用了 Scene 所属 Tenant 未定义的 Decision code |
| `ZOMBIE_PUBLISHING` | D19 PUBLISHING 残留清扫修正：后台清扫检测到 PUBLISHING 状态超过阈值，强制修正为 PUBLISH_FAILED |
| `HANDLER_EXCEPTION` | 发布事务内未分类异常，`after_snapshot` 含 stackTrace 摘要 |

新增码需在本清单回填——与运行期 `EvalResult.errorCode` 枚举互不相交（D20 派生约束）。

### 3.12 RuleVersion（规则版本快照，不是一等公民）

**是什么**：D6 / D17 / D19 / D56 共同依赖的"规则版本快照"，承载完整冻结副本——运行时评估、灰度桶计算、回滚都基于这张表。`rule_definition` 表持规则元数据 + 指向当前生效版本（`current_version`）。**草稿即冻结快照（premise A，D56）**：`rule_version` 行在 createDraft（v1）/ newVersion（v_max+1）时即跑全套解析校验并冻结；publish 不 INSERT 新行，只把最新 DRAFT 行原地翻 ACTIVE（不重解析、不增版本）。

**字段**：

| 字段 | 说明 |
|------|------|
| `version` | 单调递增 `Long`，`(rule_definition_id, version)` 唯一。仅 createDraft（v1）/ newVersion（v_max+1）产生新值；editDraft 原地改不增、publish 激活不增（D56） |
| `status` | 版本行状态：`DRAFT`（草稿，可 editDraft / deleteDraftVersion）/ `ACTIVE`（当前生效）/ `SUPERSEDED`（被新版本取代）。publish 把最新 DRAFT 翻 ACTIVE 并 supersede 旧 ACTIVE（D56） |
| `rule_definition_id` | 归属规则（FK → `rule_definition.id`）；按 Scene 查所有候选版本通过 JOIN rule_definition 实现，不冗余 scene_id |
| `trigger_event_types` | 冻结：草稿自己声明的 eventType 数组（写入草稿时已校验 ⊆ `Scene.eventTypes`；不再于 publish 时覆盖成 scene 全集，保「dry-run 预览 == 发布」，D56） |
| `condition_ast` | 冻结：完整 `resolvedAst` JSON（含解析出的 dataType）（DDL 列名；文档中有时以 `ast_snapshot` 称呼，指同一物理列） |
| `pre_gates` | 冻结：preGates 列表（含 ROLLOUT 灰度配置；无独立 `rollout` 列，D43）（DDL 列名；文档中有时以 `pre_gates_snapshot` 称呼） |
| `decision_bindings` | 冻结：Rule 绑定的 Decision 列表（含 `code` / `name` / `priority`，及 SCORECARD 的 score 区间）；DDL 列名；文档中有时以 `decision_bindings_snapshot` 称呼 |
| `kind` | 冻结：规则形态（v1 必为 `AST_BOOLEAN`） |
| `metric_dependencies` | 数组 JSON，本 RuleVersion 静态依赖的 metric 集合（D20 §1）。**草稿写入期**由 AST 静态分析 + metric ACTIVE 校验冻结（premise A，D56）；Matcher 取候选后做并集 + 批量预拉，注入 `EvalContext`，评估期禁止再发起 metric 网络调用 |
| `payload_dependencies` | 数组 JSON，本 RuleVersion 引用的 payload 字段集合 `[{name, dataType, required}]`（D55）。草稿写入期由 `valueRef=PAYLOAD` 节点 + `Scene.payloadSchema` 声明校验冻结（premise A，D56） |
| `compiled_predicate_ref?` | 可选字符串，编译产物引用键（D20 §5）。v1 留空；v1.5 启用，由 `CompiledExecutor` + `ExecutorRegistry` 按版本 id 检索编译产物 |
| `published_by / published_at` | 发布审计字段（D14） |

**关键边界**：

- **冻结快照**（D6 / D56）：ACTIVE / SUPERSEDED 行永不 UPDATE、永不 DELETE。DRAFT 行可经 editDraft 原地更新（同 version，premise A 下仍是完整冻结快照）、可经 deleteDraftVersion 删除；publish 把 DRAFT 翻 ACTIVE 后即不可变。
- **回滚不是覆盖**（D19 / D56）：回滚到 `version=N-2` = newVersion 带 `fromVersionId=<N-2 的 id>`，克隆 N-2 的输入意图、按当前世界重解析冻结产出 `version=N+1` DRAFT，再显式 publish 激活；审计链完整可追溯，旧行均原样保留。
- **删草稿边界**（D56）：deleteRule 仅删从未发布规则（无 ACTIVE/SUPERSEDED）级联全部 `rule_version`；deleteDraftVersion 仅删 DRAFT 行；碰 ACTIVE/SUPERSEDED 拒绝（只能 disable）。dry-run 痕迹（`dry_run_session`/`dry_run_node_trace`）不级联删，靠 TTL 退休。
- **运行时锁定**（D17 派生）：`evaluation_session` 开始时按 `(scene, eventType)` **倒排索引**拿当前候选 `rule_version` 列表（`current_version` 在索引预热时已解析）并拍快照，整 session 用同一组版本——即使中途切版本，本次评估不受影响。
- **灰度桶稳定性**（D6 派生）：`hash(subjectId, experimentId ?? ruleVersionId)` 计算桶号，每个不可变版本对应稳定的桶分布；版本切换会触发桶漂移，这是 D6 的固有语义。`subjectId` 由 Scene.subjectType 决定语义（v1 仅 USER 实装），与 §3.10 Job / §3.4 ROLLOUT 灰度口径一致。
- **与 `node_trace` 的关联**：`node_trace` 以 `session_id` 为外键，并记 `rule_version_id` 便于按版本对账 trace。

### 3.13 Subject（业务主体，运行时填充体）

**是什么**：EvalContext 中表示"这次评估针对的主体对象"——RuleEvent.subjectId 解出来的具体业务实体（用户 / 账户 / 设备 / 订单）+ 其属性快照。**不是一等公民**，结构由 Scene.subjectType 决定取数路径。

**结构（不可变 POJO，运行时构建）**：

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | String | 主体 ID，等于 `RuleEvent.subjectId` |
| `type` | Enum | 主体类型，等于 `Scene.subjectType`（`USER` / `ACCOUNT` / `DEVICE` / `ORDER` / `CUSTOM`） |
| `attributes` | Map<String, Object> | 主体属性快照（如 USER 主体的 `kycLevel` / `country` / `signupAt` 等） |

**v1 范围**（D13）：

- 仅 `type=USER` 实装；其他枚举值在 Scene 发布时拒绝；
- `attributes` 取数路径：按 `subjectId` 查 `user_profile` 表加载，**RuleEvent.payload 不补充 attributes**——payload 数据走 `event.payload.*` 引用路径，与 `subject.*` 严格分离（避免运营心智混乱、避免 payload 字段意外覆盖主体属性）；
- 缺失属性（user_profile 无该字段）→ 引用该属性的 ConditionNode 走 D15 `CONDITION_EVAL_ERROR` 失败语义，不静默兜底为 null。
- **主体不存在**（`SubjectLoader.load()` 返回 null 或抛出异常）→ 整 EvalContext 构建失败，归 `METRIC_FETCH_FAIL`（D25），不进入 ConditionNode 求值。

**关键边界**：

- **Subject 不可变**：与 EvalContext 同生命周期，评估期内 Evaluator 不能修改 attributes；
- **Subject ≠ user_profile 实时**：Subject 是评估开始瞬间从主体表读出的**快照副本**，即使评估过程中主体属性发生变化（如运营改了 KYC 等级），本次评估读到的仍是初始快照值；
- **SubjectLoader SPI**（D25）：Subject 取数由 `SubjectLoader.load(subjectId, subjectType, event) → Subject` 负责；v1 唯一实现 `UserProfileLoader`（`subjectType=USER`，查 `user_profile` 表）；与 metric 并行加载进 `EvalContext`（`CompletableFuture.allOf()`），Subject 加载失败整 EvalContext 失败（D15 `METRIC_FETCH_FAIL`）；跨 subjectType 扩展见 [`04-extension.md`](./04-extension.md) §SubjectLoader 实现指南；
- **AST 引用路径**：`subject.<attribute>`（如 `subject.kycLevel`）；具体 conditionType（如 `user.attribute.equals`）的参数路径解析在 [`03-rule-expression.md`](./03-rule-expression.md) 定义。

### 3.14 Pre-Gate（准入闸门，不是一等公民）

**是什么**：Rule 评估管线中"在进入 AST 评估前"的准入控制层，独立于 AST。Pre-Gate 是 `Rule.preGates` 字段持有的列表型配置，引擎按顺序串行执行；任一 Gate 不通过 → 该 Rule **直接跳过 AST 评估**，不进入 EvalResult.errorCode 维度（与 D15 评估失败不同维度——准入未通过是"该 Rule 本次不参与决策"，不是"评估出错"）。

**v1 类型清单**（与 README §三顶层架构 Pre-Gate Chain 对齐）—— Pre-Gate 收敛为仅 ROLLOUT（D52）：

| 类型 | 用途 | 状态影响 |
|------|------|---------|
| **灰度命中**（ROLLOUT） | 按 `preGates` 中 ROLLOUT 项 params 计算桶号是否落入命中区（D6） | 无状态纯只读判定，无副作用、无依赖 |

> **移除 RATE_LIMIT / MUTEX**（D52）：二者有状态（计数窗口 / 并发锁），需引擎持有分布式状态（Redis），打破"无状态评估"假设；greenfield 阶段不引该架构依赖。
> **黑白名单转 metric + condition**（D52）：按 subjectId 查名单判成员本是 metric 的活（取数/缓存/版本/影响面），且 pre-gate 在 EvalContext 装配**之前**、拿不到 metric。落地：名单 metric（`sourceType=SQL_AGGREGATE`、`dataType=BOOLEAN`）+ 规则条件 `EQ(in_blacklist, true)`，走条件层而非 Pre-Gate 层。

**关键边界**：

- **Pre-Gate 失败 ≠ 评估失败**：失败的 Rule 不进入 `EvalResult` 候选集合，**也不写 `evaluation_session` 的 ERROR 桶**；trace 落 `node_trace` 但节点类型为 `PRE_GATE_BLOCKED`（与 ConditionNode trace 区分），对账归 **`BLOCKED` 桶**（D22，第四态，独立于 `MISS`——`MISS` 是"通过 Pre-Gate 但 AST 求值不满足"，`BLOCKED` 是"Pre-Gate 拦截未进入 AST"）；`evaluation_session.blocked_by` 字段记录拦截 Gate 类型（D52 收敛后仅 `ROLLOUT`）；Pre-Gate trace 与 ConditionNode trace **走同一 `TraceWriter` 异步通道**（D21），不另起独立写入路径；
- **未注册 gateType → fail-closed**（D52）：运行期遇到未注册的 gateType 视为拦截（不静默放行）；发布期已校验 `pre_gates[].gateType` 仅 ROLLOUT 合法，配已砍/未实装 gate 一律发布拒绝；
- **Pre-Gate 与 AST 解耦**：Pre-Gate 不能引用 metric，也不能写 conditionType 自定义——只用 `Rule.preGates` 配置的内置类型；演进诉求（如"灰度按外部 AB 平台命中"）走 D6 留的接口替换，不在 Pre-Gate 层级开新类型；
- **dry-run 行为**：ROLLOUT 是纯只读 hash 判定，dry-run 与真实评估等价（无副作用差异）；
- **顶层架构图对齐**：README §三 `Pre-Gate Chain` 框就是本节落地。

### 3.15 EvaluationSession（评估会话，非一等公民）

**是什么**：一次 RuleEvent 处理的持久化记录，每次引擎受理 RuleEvent 产生 **1 行**；同时充当幂等守护的 DB 下半层（D23，Redis trySet 是上半层快速路径，DB uk 是持久化最终校验）。对应的 `node_trace` 行以 `session_id` 为锚。

**字段**：

| 字段 | 说明 |
|------|------|
| `id` | PK，雪花 BIGINT（概念上称 session_id） |
| `tenant_id` | 租户 ID |
| `event_id` | 业务事件 ID；与 `tenant_id` 构成 **DB 唯一键**（D23），防重复评估 |
| `scene_code` | 场景码（Matcher 路由键，DDL 列名 `scene_code`） |
| `event_type` | 事件类型 |
| `subject_id` | 主体 ID |
| `status` | `PENDING`（进行中）/ `HIT / MISS / BLOCKED / ERROR`（D22 四态终态）/ `FAILED`（引擎异常崩溃，未正常结束）；D22 四态是对账统计口径，`PENDING` / `FAILED` 是运行时中间态，不参与对账分母 |
| `blocked_by` | nullable；拦截 Gate 类型（D52 收敛后仅 `ROLLOUT`）；仅 `status=BLOCKED` 时有值，记录首个命中的拦截类型 |
| `error_code` | nullable；D15 `EvalResult.errorCode`；仅 `status=ERROR` 时有值 |
| `candidate_rule_count` | Matcher 命中的候选 RuleVersion 数量 |
| `hit_rule_count` | AST 求值满足（HIT）的 Rule 数量 |
| `source` | `HTTP / MQ / JOB / SDK / REPLAY`（D49）；记录**事件来源渠道**，取自 `RuleEvent.source`（由注入入口权威设置）；不改幂等语义（D23：`source=REPLAY` 仅作来源标记） |
| `mode` | `PUSH / PULL`（D49）；记录**评估模式**，由 EvalService 入口判定（`acceptEvent`=PUSH 异步 / `evaluate`·`dryRun`=PULL 同步）；与 `source` 渠道正交——Job 触发走 `acceptEvent` 故 mode=PUSH、source=JOB |
| `occurred_at` | 业务事件发生时间（来自 RuleEvent.occurredAt，非引擎收到时间） |
| `started_at` | 评估开始时间 |
| `finished_at` | 评估结束时间 |
| `eval_duration_ms` | 整 session 耗时（ms） |
| `context_snapshot` | nullable JSON；EvalContext 构建完成后的快照，已在 B20 升级为嵌套结构 `{"metrics": {metricCode: value, ...}, "evalNow": "<ISO-8601 instant>"}`，其中 `metrics` 为各指标取数结果，`evalNow` 为本次评估注入的 `EvalContext.now`（统一时钟）；用于 dry-run 重放时还原历史 metric 值及时间点，避免重放时取到当前新值；EvalContext 构建失败（`status=ERROR, errorCode=METRIC_FETCH_FAIL`）时为 null |

**`status` 聚合语义**（session 结束时由引擎按规则集合结果填充）：

| 规则集合结果 | `status` |
|------------|---------|
| ≥1 条 Rule AST 求值为 true | `HIT` |
| 进入 AST 的 Rule ≥1 条且 ≥1 条 AST 评估出 ERROR（D15） | `ERROR` |
| 进入 AST 的 Rule ≥1 条且全部 AST false | `MISS` |
| 全部候选 Rule 均被 Pre-Gate 拦截，0 条进入 AST | `BLOCKED` |

> 优先级：`HIT > ERROR > MISS > BLOCKED`（只要有一条 HIT 就是 HIT；BLOCKED 只在无任何 Rule 进入 AST 时才用）。

**关键边界**：

- **同步写（D21）**：`evaluation_session` 行在 `EvalResult` 返回前同步写入；单行 INSERT，量小，延迟可忽略；
- **生产专用**：dry-run 场景写独立的 `dry_run_session` 表（§3.16），不污染生产幂等键；
- **与 `node_trace` 的关联**：`node_trace` 以 `session_id` 为外键关联，可从 session 横向拉出完整评估链路；
- **与 `rule_version` 的关联**：`node_trace` 记 `rule_version_id` 提供按版本对账路径（§3.12 派生）；
- **`context_snapshot` 写入时机**：EvalContext 构建成功后、进入 AST 评估前，由评估线程同步写入 `evaluation_session` 行（与 session INSERT 同事务，开销为一次 JSON 序列化）；构建失败时置 null，不阻塞 session 落库；
- **DDL**：见 [`05-storage.md`](./05-storage.md) §evaluation_session 表。

### 3.16 DryRunSession（试算会话，非一等公民）

**是什么**：dry-run 试算（D7）产生的独立会话记录，存放在 `dry_run_session` 系列表中，**不受生产幂等约束**——同一 eventId 可以重复 dry-run，不会与生产评估相互干扰。

**与 EvaluationSession 的关键差异**：

| 维度 | `evaluation_session`（生产） | `dry_run_session`（试算） |
|------|--------------------------|------------------------|
| 唯一键 | `(tenant_id, event_id)` UK | 无 UK 约束，同 eventId 可多次 dry-run |
| 关联 trace | `node_trace` | `dry_run_node_trace` |
| 计入统计报表 | 是（HIT/MISS/BLOCKED/ERROR 汇总） | 否 |
| 保留时长 | 30 天（D9） | 短于生产（由 07-operability 定，建议 7 天） |

**额外字段**（在 `evaluation_session` 基础上新增）：

| 字段 | 说明 |
|------|------|
| `rule_version_id` | NOT NULL；本次 dry-run 实际评估使用的 RuleVersion id（由 `target_rule_version_id` 或 `current_version` 解析得出） |
| `trigger` | `MANUAL` / `API`；dry-run 触发来源（MANUAL=运营从管理台手动发起，API=调用方通过接口触发） |
| `requested_by` | 发起 dry-run 的操作人 ID（来源 `X-Actor-Id` header，D14） |
| `target_rule_version_id` | nullable；调用方指定 dry-run 的目标 RuleVersion；未指定时使用 `current_version`（可提前预览未发布版本效果） |
| `context_snapshot` | 同 §3.15；dry-run 场景下为本次试算时真实取到的 metric 快照，方便运营对比"重放时 metric 值"与"当前 metric 值"的差异 |

**关键边界**：

- **dry-run 行为全定义在 §五 Q10**：本节只定义存储结构，不重复 Q10 的副作用 / 短路规则；
- **DDL**：见 [`05-storage.md`](./05-storage.md) §dry_run_session 表。

### 3.17 / 3.18 Action 子系统（已整体移除，D60）

> **已移除**：D60 起引擎纯决策化，整个动作子系统（动作派发、`action_execution` 落库、重试队列、补偿管线、`ActionHandler` SPI）均已删除。引擎只产出 Decision，"命中后做什么"归消费方 / 流程引擎（详见 §3.7）。可靠投递 / 业务补偿（at-least-once、saga）由下游编排层自行设计，不在引擎内承载。

### 3.19 Decision（决策定义，一等公民）

**是什么**：Tenant 级的输出语义定义——规则命中后"最终结论是什么"的字典表。风控场景的典型 Decision 是 REJECT / REVIEW / PASS，营销场景可以是 PREMIUM_OFFER / STANDARD_OFFER / NO_OFFER 等业务自定义码。

**为什么是一等公民**：引擎不限制规则产出只有 `satisfied=true/false`；风控和分层运营类场景需要在多规则命中时合成出一个**带语义的结论**，而非由调用方在外部硬编码合成逻辑。

**字段（持久化，Tenant 级）**：

| 字段 | 说明 |
|------|------|
| `tenant_id` | 归属租户 |
| `code` | Tenant 内唯一英文标识（如 `REJECT` / `REVIEW` / `PASS`）；业务侧稳定标识，不改 |
| `name` | 显示名（中文/英文均可，给运营/风控看） |
| `priority` | 合成优先级，数值越小优先级越高；如 REJECT=1, REVIEW=2, PASS=100；业务方自定，引擎只排序 |
| `description` | 给运营/风控看的业务说明 |

（横切标准审计字段，见 §三 顶部横切说明）

**关键边界**：

- **Tenant 级，非 Scene 级**：同一 Tenant 的所有 Scene 共享 Decision 词汇表；不同 Tenant 的 Decision 严格隔离；
- **Decision 是引擎最终产物**（D60）：引擎只产出 Decision（`finalDecision` / `hitDecisions`），"命中后做什么"归消费方 / 流程引擎；Decision 不再持有 actions 字段；
- **priority 只用于合成排序**：priority 数值本身不影响 Rule 是否命中（Pre-Gate 和 AST 求值结果不读 priority）；priority 只在 `HIGHEST_PRIORITY` 合成策略里生效；
- **Decision 不内置分类标签**：v1 不区分"拒绝类/通过类"，由 `priority` 数值隐式体现；分类标签留 [`08-evolution.md`](./08-evolution.md) §演进；
- **DDL**：见 [`05-storage.md`](./05-storage.md) §decision_definition 表。

### 3.20 RuleDecisionBinding（规则-决策绑定，非一等公民）

**是什么**：Rule 与 Decision 之间的松耦合关联记录——"这条规则命中后，输出哪个 Decision"。发布时随 `rule_version` 整体快照化，保证运行时不可变（D6）。

**为什么不把 decisionCode 直接放 Rule 字段上**：
- 支持 `SCORECARD` kind 下按 score 区间映射多个 Decision（如 score 0-30 → REJECT，31-60 → REVIEW，61-100 → PASS）；
- Rule 与 Decision 松耦合，多个 Rule 可以映射到同一个 Decision，Decision 改名不影响 Rule；
- 关联关系快照化进 `rule_version.decision_bindings` JSON 列（含各 Decision 绑定的 code / name / priority）。

**字段**：

| 字段 | 说明 |
|------|------|
| `rule_id` | 关联规则（DDL 列名 `rule_definition_id`，外键） |
| `decision_code` | 命中后输出的 Decision.code（概念层引用业务码；DDL 实现用 `decision_id` 外键关联 `decision_definition.id`，业务码通过 JOIN 取） |
| `score_range_min?` | 可选；仅 `Rule.kind=SCORECARD` 时生效，`EvalResult.score` ≥ 此值时匹配 |
| `score_range_max?` | 可选；仅 `Rule.kind=SCORECARD` 时生效，`EvalResult.score` < 此值时匹配（左闭右开） |

**运行时行为**：

- `AST_BOOLEAN` kind（v1 主场景）：一条 Rule 绑一个 Decision（1:1），score 区间字段为空；Rule AST 求值 true → 直接取该 Decision；
- `SCORECARD` kind（v2 启用）：一条 Rule 可绑多个 Decision（按区间），引擎取 `EvalResult.score` 匹配区间后取对应 Decision；无匹配区间 → 该 Rule 不贡献 Decision；
- 快照字段：发布时将当前绑定列表序列化为 `rule_version.decision_bindings` JSON 列（DDL 落地列名无 `_snapshot` 后缀）。

**Scene.decisionStrategy（多规则命中合成）**——属于 Scene 配置（§3.2），此处说明是因为合成行为与 RuleDecisionBinding 紧密相关；完整字段定义见 §3.2 Scene 字段表：

| 值 | 语义 | 状态 |
|----|------|------|
| `HIGHEST_PRIORITY` | 取所有命中规则对应 Decision 中 `priority` 值最小者为最终决策 | v1 实现 |
| `MAJORITY` | 多数命中的 Decision 胜出 | v2 |
| `CUSTOM_SPI` | 自定义合成器 SPI | v2 |

`Scene.decisionStrategy` DDL 层 NOT NULL DEFAULT 'HIGHEST_PRIORITY'（D29），逻辑上无需显式配置：**PUSH/HYBRID Scene 缺省即 `HIGHEST_PRIORITY`**，不会因漏配导致 `finalDecision` 静默为空；PULL Scene 不参与合成，配置了也忽略。Scene 显式配置可覆盖默认值；`hitDecisions` 列表在所有模式下始终填充，供调用方自行处理。

**关键边界**：

- **快照不可变**（D6）：RuleDecisionBinding 快照进 `rule_version` 后永不变更；修改绑定 = 修改 Rule 草稿 → 走标准发布产新 version；
- **DDL**：见 [`05-storage.md`](./05-storage.md) §rule_decision_binding 表。

---

## 四、心智级时序（一个事件进来后发生了什么）

不含代码，只讲"心智步骤"。代码级时序在 [02-runtime](./02-runtime.md)。

```
1. 业务系统发生：用户 U 在 2026-05-25 10:30 完成首次交易（USD 500）
        │
        ▼
2. Trigger 收到 MQ 消息 → 翻译成 RuleEvent
   {tenantId: acme, scene: marketing.first-trade, eventType: trade.completed,
    subjectId: U, occurredAt: 10:30, payload: {amount:500, currency:USD}, eventId: X1}
        │
        ▼
3. IdempotencyGuard 检查 eventId 是否处理过 → 首次，放行
        │
        ▼
4. Matcher 按 (tenant + scene + eventType) 倒排索引拿候选 RuleVersion 快照列表
   (D17 派生：运行时锁定不可变快照，current_version 在索引预热时已解析)
   → 命中 3 条："首单奖励" / "首单返现" / "新人引导消息"
        │
        ▼
5. 对每条候选 Rule 走 Pre-Gate（v1 仅 ROLLOUT，D52）
   - 灰度命中 (按 subjectId hash → bucket 0..99，本规则 < 20)
   （黑白名单/频次/互斥已不在 Pre-Gate：黑白名单转 BOOLEAN metric+condition，频次/互斥已移除）
   → 通过 2 条："首单奖励" / "新人引导消息"
        │
        ▼
6. 对通过 Pre-Gate 的 RuleVersion 集合，做 metric 批量预拉 (D20 §1)
   - 取每条 RuleVersion.metric_dependencies 并集
     → {user.kycLevel, user.trade.count.7d}
     (引用的 metric 都是 tenant 级已注册的 ACTIVE metric)
   - 一次性 mget / batch API 拉取 → 注入 EvalContext
   - 评估期 MetricSource 只从 EvalContext 读，禁止再发起 metric 网络调用
        │
        ▼
7. 对每条通过 Pre-Gate 的 Rule，AST Evaluator 递归求值
   - 根节点 AndNode (displayLabel: "全部满足")
     ├── AndNode (displayLabel: "基本条件") → kycLevel>=1 ✓ && country=US ✓ → 满足
     └── OrNode  (displayLabel: "金额门槛") → amount>=100 ✓ || count<5 ✓ → 满足
   → AST 整体 true，Rule 满足
   每个节点（Root / 子 AndNode / 子 OrNode / 各 ConditionNode）的 satisfied + actualValue 落 node_trace 表
   求值期若引用了未预拉的 metric → EvalResult.errorCode = METRIC_FETCH_FAIL (D15)
        │
        ▼
8. decisionStrategy 合成 finalDecision (D26/D29)
   - 取所有命中 Rule 绑定的 Decision 中 priority 最小者 → finalDecision
   - hitDecisions 列表始终填充，供调用方自行处理
        │
        ▼
9. 引擎产出决策（D60）
   - PULL：同步返回 EvalResult{finalDecision, hitDecisions, ...}，调用方决策
   - PUSH/HYBRID：异步评估 + 落库 evaluation_session / node_trace，不派发动作
   - "命中后做什么"（发券 / 拦截 / 通知）归消费方 / 流程引擎
```

**关键节奏**：

- 第 4-5 步（Matcher + Pre-Gate）是"宽进窄出"的粗筛阶段，性能敏感；
- 第 6-7 步（metric 批量预拉 + AST 求值）是耗时大头，发布期算依赖 + 匹配后一次 mget 是性能关键（D20 §1）；
- 第 8-9 步（合成 finalDecision + 产出决策）是输出阶段；引擎到此结束，编排归下游。

---

## 五、边界辨析（FAQ）

### Q1: Rule vs Condition —— 谁包含谁？

Rule **包含** Condition（作为 AST 的叶子节点 `ConditionNode`）。Rule 是"完整业务表达"（含触发 / 准入 / 判定 / 版本 / 灰度），Condition 只是判定的最小原子。一个 Condition 不能独立存在，必须作为 AST 内的叶子节点存在；AST 必须挂在某 Rule 下。

### Q2: 运营心智里的"条件分组"在数据模型里是什么？

是 AST 的 `AndNode` / `OrNode` 携带的 `displayLabel` 字段。**不是独立实体**。

```
AndNode (displayLabel: "基本条件")           ← 渲染成卡片标题"基本条件"
  ├── ConditionNode (kycLevel >= 1)
  └── ConditionNode (country == "US")
AndNode (displayLabel: "金额门槛")
  └── OrNode
        ├── ConditionNode (amount >= 100)
        └── ConditionNode (count < 5)
```

前端按 `displayLabel` 渲染"分组卡片"；后端评估时**忽略该字段**，只看逻辑结构。这样：

- 90% 分组需求成本是零（一个字段）；
- 不限制嵌套深度（任意 AND/OR/NOT）；
- v2 真要做"独立分组实体 / 跨 Rule 复用 / 分组权限"时，把 `displayLabel` 字段升级到独立表，AST 节点改成引用即可，零数据损耗。

### Q3: Push 模式 vs Pull 模式怎么选？

看你**是否需要同步等决策结果**，以及**事件由谁推**（引擎只产出决策，"命中后做什么"两种模式都归消费方 / 流程引擎，D60）：

| 维度 | 选 PUSH | 选 PULL | 选 HYBRID |
|------|---------|---------|----------|
| 上游推事件，不关心同步结果 | ✓ | | |
| 上游需要拿"满足/不满足 / finalDecision"做决策 | | ✓ | ✓ |
| 评估结果需落库审计 + trace | ✓ | | ✓ |
| 期望响应时间 | 不关心（异步） | 必须低（<100ms 同步链路） | 视入口 |

**典型决策**：

- 营销 / 运营场景 → **PUSH**（业务方推事件，引擎异步评估 + 落库，下游据决策编排）；
- 风控 / 准入 / AB 实验 → **PULL**（业务方同步查规则结果，自己处理拦截或分流）；
- 用户标签 / 运营数据打标 → **HYBRID**（标签查询走 PULL，事件触发走 PUSH，同一 Scene 两边都用）。

**反模式**：

- ❌ 营销规则在调用方同步等 EvalResult 才能继续主流程 —— PUSH 异步评估即可，不必阻塞业务链路；
- ❌ 同一规则跨 PUSH / PULL 入口被双重消费 —— Scene 模式声明就是为避免这种含糊。

### Q4: Metric vs Condition —— 为什么不直接在 Condition 里写 SQL？

三个原因：

1. **复用**：同一 metric 被 10 条规则用，只配一次 / 取数一次；
2. **治理**：指标是平台资产，新增要审批，防止口径分裂（D54 后 metric tenant 级可用，无 scene 级白名单）；
3. **缓存与预热**：metric 取数可以加 evalSession 级缓存（同次评估内多 Condition 引用，只查一次）；Scene 启动时预热 MetricSource，热路径上无懒加载。

把 SQL 写进 Condition 等同于把数据访问层和判定层耦合，规则数稍多就会失控。

### Q5: 引擎只产出决策吗？"命中后做什么"归谁？

**是**。D60 起引擎纯决策化：`evaluate()` 只返回 `EvalResult{finalDecision, hitDecisions, ...}`，不再有 Action / ActionHandler 概念。"命中后做什么"（发券 / 拦截 / 通知 / 调外部系统）归**消费方 / 流程引擎**——对标 OPA（策略引擎出决策、PEP 执行）/ Camunda（DMN 出决策、BPMN 编排），预期搭档 Flowable，以 Service/HTTP Task 把本引擎当一个决策节点调用。

`ConditionEvaluator` / `MetricSource` 与 `ConditionNode` / `Metric` 的关系仍是"配置数据 vs 幕后代码"，与此对称。

### Q6: Tenant vs Scene —— 不能合并吗？

不能。Tenant 是**数据隔离**（不同租户绝不互相看见），Scene 是**业务域**（同租户内的命名空间 + Matcher 路由键 + 数据源初始化锚点 + 使用模式声明；D54 后不再承担 metric 白名单）。

- 一个 Tenant 下有多个 Scene（如 acme 公司既配营销规则又配风控规则）；
- 一个 Scene **不会**跨 Tenant（acme 的 `marketing.signup` 和 beta 的 `marketing.signup` 是两条 Scene）。

### Q7: 同一个用户在两个 Scene 下能否各触发一次？

**能**。Matcher 在 (tenant + scene + eventType) 三元组下找候选，两个 Scene 互不影响。

但**幂等键含 eventId**——同一条事件（同一个 eventId）只评估一次，不会因为命中两个 Scene 而处理两次。

### Q8: metric / decision 的引用与校验如何生效（D54 收敛后）？

**Metric**（tenant 级，对所有 scene 可用，无白名单）：

1. **规则发布时校验**：规则 AST 引用的所有 `metricCode` 必须是本 tenant 已注册的 ACTIVE metric，否则发布拒绝（`UNRESOLVED_VARIABLE`）；
2. **应用启动时预热**：启动加载 tenant 的 ACTIVE metric，批量初始化对应 MetricSource（连接池、HTTP client、缓存）；
3. **取数稳定性**：metric 预拉值在评估期内冻结（D20 §1），取数异常归 `METRIC_FETCH_FAIL`（D15）。

**Decision**（tenant 级，与 scene 无关）：

1. **规则发布时校验**：规则绑定的 decisionCode 必须在 `decision_definition` 存在（`DECISION_CODE_NOT_FOUND`）；
2. **运行时合成**：命中规则绑定的 Decision 经 `decisionStrategy` 合成 `finalDecision`，随 `EvalResult` 产出；
3. **前端 UI 下拉**：配规则页面的 decisionCode 下拉按本 tenant 已定义的 Decision 列表。

### Q9: EvalContext 里能放任意业务数据吗？

**不能任意**。EvalContext 的字段是约定的（`tenantId` / `event` / `subject` / `metrics` / `now`），扩展字段有明确入口（D13）：

- **事件维度的扩展**：通过 `RuleEvent.payload` 进入，但字段必须 ∈ `Scene.payloadSchema`；
- **主体维度的扩展**：通过 subject 属性进入，主体来源由 `Scene.subjectType` 决定（USER → user_profile / ACCOUNT → account 等）；
- **指标维度的扩展**：注册新 Metric（ACTIVE 即 tenant 级可用，D54，无需 scene 白名单）。

为什么这么收口：

1. Evaluator 知道能假设什么字段存在（schema 是显式契约）；
2. trace 落库时字段是稳定的；
3. dry-run 试算时能按 schema 构造完整 mockContext。

### Q10: dry-run 试算时这些概念有何不同？

dry-run 复用**全部**评估链路（Matcher / Pre-Gate / EvalContext 构建 / AST 评估 / trace 落库），产出与正式评估一致的决策预览（`finalDecision` / `hitDecisions`），只是 trace 落 `dry_run_session`、不落 `evaluation_session`。引擎纯决策化后无副作用入口（D60），dry-run 与正式评估的差异仅在落库目标。

**核心原则：判定执行，副作用不落**。dry-run 期望"看见会发生什么"，而不是"真的发生"，所以所有有副作用的环节都要透传 `dryRun=true` 标志并自行短路写副作用：

| 环节 | dry-run 行为 |
|------|--------------|
| **Pre-Gate 灰度命中**（ROLLOUT，v1 唯一 Pre-Gate） | 纯只读判定，无副作用差异（hash 算法稳定，不依赖状态） |
| **EvalContext 构建（取 metric）** | 真实取数（dry-run 期望看到真实指标值），但**走只读路径**，不触发预聚合写回 |
| **AST 评估 + 节点 trace** | 真实评估、真实节点 trace；trace 写入 `dry_run_session` 表，不进 `evaluation_session` |
| **决策合成（finalDecision / hitDecisions）** | 真实合成，预览结果随 dry-run 响应返回 |
| **审计 `audit_log`** | 不写入（dry-run 不是发布操作） |

**为什么 Pre-Gate 判定要执行**：运营试算的核心诉求之一就是"看见这个用户会不会被灰度门槛拦下"——如果 Pre-Gate 一律跳过，trace 就缺失了关键信息；正确做法是判定执行但不落副作用。

**dry-run 与正常评估几乎等价**（引擎纯决策化后无动作派发，D60）——区别仅在：1) trace 落 `dry_run_session` 表；2) 不落 `evaluation_session`。

详见 [07-operability](./07-operability.md) §试算面板。

---

## 六、命名约定

### 6.1 全局唯一标识符的格式

| 类型 | 格式 | 例子 |
|------|------|------|
| `tenantId` | `[a-z][a-z0-9-]*`，全小写、限连字符 | `acme-corp` |
| `scene` | `<domain>.<subdomain>`，最多两层 | `marketing.signup` |
| `eventType` | `<entity>.<verb>`，过去式 | `trade.completed` |
| `metricCode` | `<domain>.<entity>.<measure>[.<window>]` | `user.trade.sum.7d` |
| `conditionType` | 内置算子码为裸名（时间算子用点分），自定义建议 `<category>.<operation>` 避免冲突 | 内置 `GT` / `IN` / `BETWEEN` / `time.window`；自定义 `geo.within_country` |

### 6.2 ID 字段约定

- 所有持久化对象的主键都是 `<entity>_id`，类型 `BIGINT`（自增）或 `VARCHAR(64)`（UUID / 雪花），由 [05-storage](./05-storage.md) 统一定义；
- 业务侧用 `*_code` 表示稳定标识（如 `metric_code`），用 `*_id` 表示生命周期标识（如 `rule_id`）。

### 6.3 版本号

- Rule 版本号为单调递增 `Long`（不用 SemVer，太重），列名统一为 `version`（见 §3.12 `rule_version.version`）；
- 引用 Rule 的下游：`evaluation_session` / `node_trace` 以 `session_id` 关联；`node_trace` 记 `rule_version_id` 支持按版本对账。

---

## 七、词典速查

| 想找 | 看 |
|------|----|
| 字段 / DDL 细节（含 `scene`（旧称 `scene_definition`）/ `decision_definition` / `rule_definition` / `rule_version`） | [05-storage](./05-storage.md) |
| Rule 版本快照（不可变快照、回滚语义、运行时锁定） | §3.12 RuleVersion |
| AST 节点类型 / 操作符 / `displayLabel` 渲染 | [03-rule-expression](./03-rule-expression.md) |
| 加新的 ConditionEvaluator / MetricSource | [04-extension](./04-extension.md) |
| 定时类规则：`JobDefinition` 字段 / Scheduler 接口 / xxl-job 适配 | §3.10 + [02-runtime §3.1](./02-runtime.md)（Trigger 接入层 JobScheduler 条目） |
| 一个事件进来到决策产出的代码级时序 | [02-runtime](./02-runtime.md) |
| 前端怎么把这些概念画成 UI | [06-frontend](./06-frontend.md) |
| 幂等 / 灰度 / dry-run / 监控的运营细节 | [07-operability](./07-operability.md) |
| 生产评估持久化（字段表 / 四态 `status` 聚合） | §3.15 EvaluationSession |
| dry-run 试算会话（隔离表、可重复运行） | §3.16 DryRunSession |
| 引擎纯决策化，动作子系统移除（D60） | §3.7（已移除说明）+ [00-decisions D60](./00-decisions.md) |
| Tenant 级决策码定义（REJECT / REVIEW / PASS 等） | §3.19 Decision |
| Rule 与 Decision 的绑定关系 + 多规则命中合成策略 | §3.20 RuleDecisionBinding |
| 历史决策时间线 / 路线图 | [08-evolution](./08-evolution.md) |
