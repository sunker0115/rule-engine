# 08 — 演进路线图

> **位置定位**：本文档承载所有"v1 不做、未来做"的演进项与决策时间线。
>
> **前置阅读**：[`README.md`](./README.md)、[`00-decisions.md`](./00-decisions.md)
>
> **解决什么疑问**："为什么 v1 没做 X？""未来什么时候做 X？""X 是怎么决定不做的？"

---

## 一、文档状态

| 章节 | 状态 |
|------|------|
| §二 演进锚点（roadmap） | ✅ |
| §三 决策时间线 | ✅ |
| §四 已否决方案 | ✅ |

---

## 二、演进锚点（roadmap）

> **维护原则**：每条 anchor 由"来源决策 / 触发条件 / v1 现状 / 演进方向"四要素组成。当 v1 文档（01-concepts / README）中出现 `详见 08-evolution.md §XXX` 的链接时，必须在本节有对应锚点。

### 2.1 kind 多态（来源 D12）

- **v1 现状**：`Rule.kind` 字段就位，仅实现 `AST_BOOLEAN`，其他枚举值发布拒绝；`EvalResult` 多态字段（`score / category / decision`）就位但 v1 仅填 `satisfied`。
- **触发条件**：业务侧出现评分卡 / 决策树 / 决策表 / 表达式脚本类需求。
- **迁移成本**：低（schema 占位已就绪，只补 evaluator + UI 编辑器）。

**设计原则**：D12 引入 `Rule.kind` 是为评分卡 / 决策树 / 决策表 / 脚本类规则演进**预留 schema 占位**，不是 v1 要实现的功能。

**各 kind 共享 Rule 的公共属性**：trigger / preGates（含 ROLLOUT 灰度）/ decisionBindings / version / Scene 治理都不变，多态只在"判定主体"内部——（注：actions 已迁移到 Decision，不再是 Rule 的直接字段，D27）

> 下表"判定主体字段"列只指示**形态**与**承载方式**，具体字段命名留待 v2 设计时定稿，避免占名误导后续设计。

| kind | 判定主体承载 | 输出字段 | 状态 |
|------|------------|---------|------|
| `AST_BOOLEAN` | sealed `RuleNode` AST 树（已在 v1 落地） | `EvalResult.satisfied` | 已实装（v1） |
| `SCORECARD` | JSON 列承载条件列表 + 各自 `weight` + 阈值带 | `EvalResult.score` | 已实装（D12） |
| `DECISION_TREE` | JSON 列承载嵌套 if/then/else 树 | `EvalResult.category` | 已实装（D42） |
| `DECISION_TABLE` | JSON 列承载输入列 + 输出列 + 行集合矩阵 | `EvalResult.decision` | 已实装（D42） |
| `EXPRESSION_SCRIPT` | 文本列承载 CEL / Aviator 脚本 | 按脚本返回值多态填 | 未实装（留 v1.5） |

**`EvalResult` 是稳定多态**：PULL 模式调用方拿到的对象 shape 是 `{satisfied, score?, category?, decision?, trace}`——SCORECARD 多填 `score`（D12），DECISION_TREE 填 `category`（D42），DECISION_TABLE 填 `decision`（D42），PULL API 签名始终不变；节点 trace 跨 kind 统一，运营自助排障的能力 100% 复用。

**决策集 / 决策流不进 `Rule.kind` 枚举**：`Scene.executionStrategy` 是 Scene 字段，v1 已落地 `HIGHEST_PRIORITY`（D29）；`ALL_HITS` / `FIRST_HIT` 已实装（D41）。命中后的编排（决策流）由消费方 / 流程引擎承载（D60），在 Rule 层级之外。

**为什么不另起表**：评分卡 / 决策树仍需要 Rule 的全部公共属性（触发 / 准入 / 灰度 / 决策绑定 / 版本快照），独立表会复制 80% 的列且数据散布、跨形态报表困难；用 `kind` 字段 + 多态 JSON 列在同一张表里，公共能力天然共享。

**演进路径**：按需逐个实现 evaluator——SCORECARD 启用 `ConditionNode.weight`；DECISION_TREE / DECISION_TABLE / EXPRESSION_SCRIPT 各自的内部 JSON 结构与 evaluator；`Scene.executionStrategy` 配合决策集。

### 2.2 Metric 版本化（来源 #2 占位）

- **v1 现状**：`metric_definition.metric_code` 是指标唯一标识，`rule_version.metric_dependencies`（JSON 列）只存 `metricCode` 字符串，不带版本号；metric 元数据更新是原地覆盖，无历史版本记录。`Metric.metricVersion` 字段就位但固定值 1，暂未启用版本语义。
- **触发条件**：metric 语义发生不兼容变更时（换算口径、时区基准、SQL 逻辑调整）——存量规则若继续跑旧定义会静默得到错误结果，但引擎无法感知，只能靠业务侧发现异常。
- **演进方向**：
  - `metric_definition` 加 `version INT` 列，发布新语义 = INSERT 新行（旧行不删，status 改为 SUPERSEDED）；
  - `rule_version.metric_dependencies` 从 `["metricCode"]` 升级为 `[{"metricCode":"xxx","metricVersion":1}]`；
  - 发布期校验：被引用的 `(metricCode, metricVersion)` 必须存在且为 ACTIVE 状态；
  - 评估期：按规则绑定版本 `resolve(metricCode, metricVersion)` 解析 metric 定义，不再默认取最新版；档1 `EvalContext` 仍按 metricCode 索引单值、过渡期同 code 取最高版本，完整 per-rule 版本投影（EvalContext 二元键）为档2 留后续；
  - 运营 UI：展示"修改此 metric 版本后受影响的规则数"，上线前可提前评估影响范围；
  - 与 §2.12（payloadSchema 版本化）平行演进，schema 版本和 metric 版本同期落地可降低迁移成本。
- **迁移成本**：中（`metric_definition` 表结构变更 + `rule_version` JSON schema 升级 + 发布期 / 评估期解析逻辑 + UI 影响面展示）。

**已实装（B6 / 2026-06-06，档1）**：

- DDL：`metric_definition` 加 `version INT NOT NULL DEFAULT 1` 列；`status` ENUM 扩展 `SUPERSEDED`；唯一键从 `(tenant_id, metric_code)` 改为 `(tenant_id, metric_code, version)`（Flyway V1_6）；
- `rule_version.metric_dependencies` 升为对象数组 `[{"metricCode":"xxx","metricVersion":1}]`，发布期静态收集并冻结当前 ACTIVE 版本号；
- 发布期冻结：被引用 metric 的当前 ACTIVE 版本号在发布时写入快照；
- 评估期按版本解析：`resolve(metricCode, metricVersion)` 按绑定版本取定义；档1 `EvalContext` 仍按 metricCode 单值索引，同 code 取最高版本，完整 per-rule 版本投影（二元键）为档2 后续；
- metric 写服务（注册 `POST /admin/v1/metrics`）+ 升版（`PUT /admin/v1/metrics/{metricCode}?breakingChange=`）；
- 影响面查询 API：`GET /admin/v1/metrics/{metricCode}/versions/{version}/impact?tenantId=`；
- SDK 下发：DECLARED 模式含被引用的 SUPERSEDED 旧版定义，每项 `MetricDescriptor` 带 `metricVersion`。

### 2.3 跨 Scene 规则复用（来源 #1）

- **v1 现状**：Rule 属于唯一 Scene，没有跨 Scene 复用机制；相似规则需在多个 Scene 重复配置。
- **触发条件**：相同条件逻辑在多个 Scene 重复配置（如"账户开立 < 90 天"同时出现在 risk.transfer / risk.withdrawal / risk.payment），改动时需逐 Scene 同步，易漏、易产生版本漂移。
- **演进方向**：
  - **`RuleTemplate`**：参数化的规则骨架，变量用占位符，实例化时注入 Scene 专属参数值；发布时膨胀为普通 `rule_version`，评估期不感知模板，公共能力（灰度 / Pre-Gate / Action）完全复用；
  - **`RuleFragment`**：可被 `Rule.conditionAst` 引用的 AST 子树（逻辑提取为独立实体），发布时展开内联，语义等价于手动复制；
  - 两种形态适用场景不同：Template 适合大框架复用（同一规则结构不同参数）；Fragment 适合条件组复用（同一 AND/OR 子树在多处出现）；
  - 新增 `rule_template` / `rule_fragment` 表 + 引用关系表；`rule_version` 发布时展开并生成不可变快照，与 D6 快照语义一致；
  - 灰度：Template 实例化后继承普通 `rule_version` 的 ROLLOUT Gate 语义，不需要专门机制。
- **迁移成本**：高（schema 变更 + 发布事务展开逻辑 + dry-run 兼容 + 灰度桶继承 + UI）。
- **依赖**：§2.10 规则模板市场依赖本节就位。

### 2.4 规则间依赖与编排（来源 #3）

- **v1 现状**：引擎纯决策（D60），规则间不存在内置依赖与顺序保证；业务需要顺序走外部 MQ / 流程引擎编排。
- **触发条件**："先反欺诈再合规"类顺序依赖需求，或"Rule A 命中后才触发 Rule B"的流程依赖，目前只能通过外部编排，依赖关系对运营不可见，排障成本高。
- **演进方向**：接 Flowable / Camunda 流程引擎（D60），把"Rule 命中 → 触发下一 Rule"作为工作流节点而非 Rule 内能力。引擎仍保持单事件单评估，流程引擎做编排。
- **迁移成本**：高（引入新组件、运维形态变化）。

### 2.5 节点级 trace 冷热分级（来源 #5，并入 [`05-storage.md`](./05-storage.md) TODO）

- **v1 现状**：D9 决定全 MySQL 起步，数据保留 30 天；`node_trace` 表与 `evaluation_session` 表同库；写入路径走 `TraceWriter` 异步批写（D21）——本演进锚点是**存储分层**（冷热分级 / 列存），与 D21 的**写入路径**（同步 vs 异步）正交，二者独立演进。
- **触发条件**：观测到 trace 表膨胀影响查询性能 / 存储成本不可控。
- **演进方向**：热表保留 7 天 + 冷归档表（按月分区）+ 可选 ClickHouse / ES 列存；存储与查询接口隔离，业务侧零改动。
- **接收内容**：本锚点在 05-storage 展开时迁入"§冷热分级" 章节。

### 2.6 监控指标体系（来源 #10）

- **v1 现状**：顶层架构旁路提到 `Metric Aggregator → Prometheus`，v1 已在 `07-operability.md` 完整定义指标清单与告警阈值。
- **触发条件**：无（已完成）。
- **演进方向**：已迁入 [`07-operability.md §六 Prometheus 指标清单`](./07-operability.md) 与 [`§七 告警阈值`](./07-operability.md)，包含评估耗时分位、命中率、ERROR 率、trace 队列深度等核心指标及建议告警阈值。

### 2.7 灰度发布的验证与回退（来源 #12）

- **v1 现状**：D6 已定义灰度按 % 放量 + 按用户标签命中，hash bucket 算法稳定（基于 `(subjectId, ruleVersionId)`）。灰度验证流程与回退操作已在 `07-operability.md` 完整定义。
- **触发条件**：无（已完成）。
- **演进方向**：已迁入 [`07-operability.md §五 灰度`](./07-operability.md)，涵盖灰度算法、验证流程（5% → 全量）、回退操作（调 percentage 回 0 或 DISABLE 规则）。更高级的自动放量 / 自动回退（按 SLO 推进）为 v2 范畴，届时回写本节。

### 2.8 合规演进（来源 D14 v1 不做的"敏感数据"占位）

- **v1 现状**：审计快照 / Context / payload 按原值落库；GDPR / PII 由调用方在 payload 进入引擎前完成脱敏 / 加密 / token 化。`audit_log` 不做篡改防护。
- **演进方向**：
  - **字段级加密**：sensitive 字段在 Scene `payloadSchema` 标记后，引擎写入持久层前自动加密；读取按权限解密；
  - **审计 hash chain**：`audit_log` 加 `prev_hash` 列，链式不可篡改；与 WORM 存储或区块链化扩展二选一；
  - **数据保留与右遗忘**：GDPR 配套——按主体 ID 物理清除 `evaluation_session` / `node_trace` / `audit_log` 中对应数据，需要分库分表的清除工具。
- **迁移成本**：高（涉及所有持久化对象）。

### 2.9 规则导出 / 导入（来源 #15）

- **v1 现状**：没有跨环境 / 跨租户的规则迁移工具；跨环境迁移需人工重建规则。
- **触发条件**：
  - 跨环境迁移（dev → staging → prod）时规则需人工重建，操作繁琐且易出错；
  - 租户入驻时需要批量导入模板规则；
  - 线上 Incident 排查需要在测试环境精确复现生产规则版本。
- **演进方向**：
  - **导出格式**：JSON Bundle = `{ruleVersion, metricDefinitions[], decisionDefinitions[], sceneSnapshot}` 自包含包，目标环境无需额外查询即可完整重建；
  - **导入校验**：decisionCode 存在性（目标环境 Tenant 是否已定义引用的 Decision，D54）+ metric 参数安全性（SQL 类型需人工审核标记）+ 版本号重映射（源环境主键 id 不照搬，按 `rule.code` 做 upsert）；
  - **幂等**：重复导入 = 新建草稿版本，不覆盖已发布版本；
  - **权限**：导出需 EXPORT 权限；导入需目标 Scene 的 PUBLISH 权限；
  - **不做**：跨租户实时同步（由 §2.10 规则模板市场解决）。
- **迁移成本**：中（独立工具链，不动核心引擎）。

**已实装（B7 / 2026-06-06）：**

- **载体**：HTTP 端点——`GET /admin/v1/rules/export?tenantId=&ruleIds=&sceneId=`（按条件批量导出 ACTIVE 版本为 **Bundle JSON 文件下载**，`Content-Disposition: attachment`）、`POST /admin/v1/rules/import`（**multipart 文件上传**，幂等批量导入）；Service 落 `rule-config-svc`（`RuleBundleService` → `RuleExportService` / `RuleImportService`，进出 `RuleBundle` 对象），Controller 落 `rule-api`（`RuleBundleController`，做对象↔文件转换）。无 DDL。
- **批量 + 多规则 Bundle**：导出选取优先级 ruleIds → sceneId → 整租户（入参用 sceneId，前端列表已有；Bundle 内 `RuleEntry.sceneCode` 仍用 code，跨环境按 code 关联）；Bundle 统一为多规则结构 `{bundleVersion, exportedAt, sourceTenantId, rules[], scenes[], metricDefinitions[], decisionDefinitions[]}`，单规则 = rules 长度 1 特例。所有 JSON 列按原始 JSON 字符串无损搬运。**实装含 `decisionDefinitions[]`**：rule 经 `RuleDecisionBinding` 引用 tenant 级 decision_definition，随包搬运才能真正自包含重建。
- **导入幂等**：Scene / metric / decision 按业务键整体 upsert（缺失则建、已存在跳过不覆盖）；规则逐条——code 不存在 → 新建 rule_definition(DRAFT) + rule_version(DRAFT v1)，已存在 → 仅追加 rule_version(DRAFT, maxVersion+1)，不动 rule_definition 状态/currentVersion。把导入草稿提升为 ACTIVE 走发布/回滚流程（D19，尚未实现）。
- **metric 安全**：`SQL_AGGREGATE` 类缺失 metric 不自动创建，列入 `metricsRequiringReview`，由运营人工审核后建；发布期 PublishService 的"被引用 metric 无 ACTIVE 版本"校验是安全网。
- **权限**：v1 沿用 `X-Actor-Id` header；EXPORT / PUBLISH 权限校验留 TODO（后续合规批次 §2.8）。
- **与本地调试格式正交**：B7 Bundle 专做跨环境 DB 迁移；本地 / 离线调试用 `GET /sdk/v1/snapshots`（§8.3），两者不打通。

### 2.10 规则模板市场（来源 #16）

- **v1 现状**：不考虑平台级规则模板共享。
- **演进方向**：依赖 2.3（跨 Scene 复用）与 2.9（导出 / 导入）就位后，再考虑平台级模板仓库 + 评分 + 版本管理 + 跨租户分发。
- **优先级**：低（v3 范畴）。

### 2.11 外部系统集成契约标准化（来源 #17）

- **v1 现状**：`MetricSource` 支持外部 HTTP，但未定义统一接口契约；不同业务自行约定。
- **触发条件**：多业务团队各自实现 `EXTERNAL_HTTP` MetricSource，协议、错误码、超时行为各异，无法跨团队复用，联调成本高；新业务接入时无参考规范，接入周期长。
- **演进方向**：定义标准化指标获取协议（参考 OpenTelemetry / OpenFeature 模型），引入 `MetricFetcher` 通用 SDK + 协议测试套件。
- **迁移成本**：中。

### 2.12 Scene schema 演进（来源 D13，v2 阶段已实装基础设施）

- **v1 现状**：`Scene.payloadSchema` 在 Scene 表上，发布期校验 RuleEvent.payload 字段合法性；变更 schema = 直接覆盖。
- **触发条件**：业务侧调整 payload 字段（新增 / 重命名 / 类型变更），存量规则可能引用了旧字段。
- **演进方向**：引入 `Scene.payloadSchemaVersion` + 历史版本表 `scene_payload_schema_history`；发布 RuleVersion 时锁定当时的 `(sceneId, payloadSchemaVersion)` 引用；schema 变更走"新版本号 + 影响规则清单 + 灰度切换"流程；与 D20 §3 输入闭合校验联动——校验集合按当时锁定的 schema 版本而非"最新"求解。
- **迁移成本**：中（schema 历史表 + 引用解析逻辑）。

- **v2 实装（2026-06-04）**：`PayloadFieldSpec` JSON Schema 完整子集（enum/min/max/pattern）、`scene_payload_schema_history` 历史表、`scene.payload_schema_version` 版本号字段已落地。Scene 创建/更新 API 现可持久化 payloadSchema，发布时 triggerEventTypes ⊆ Scene.eventTypes 校验已启用。AST payload 字段引用校验留到 v3（需约定 ConditionNode.params 的字段引用编码规范）。

### 2.13 评估期预编译（纯编译版已落地 2026-06-13，见 D67）

- **已落地（纯编译版）**：`AstCompiler` 把 `AST_BOOLEAN` 布尔 AST（And/Or/Not/Xor/Condition）编译为嵌套 `Predicate<EvalContext>` 闭包；`CompiledExecutor`（包 `InterpretedExecutor`）按不可变 ruleVersionId 缓存（`RuleVersionCache`），**非 trace 快路径**直接 `predicate.test(ctx)`，开 trace / 灰度未命中 / 关开关时回落解释器。
- **编译技术**：闭包组合（非 Janino 字节码 / 非 LambdaMetafactory——零依赖、零类加载、可调试），组合节点编译期收数组、求值期下标循环避免 Iterator 分配。叶子经 `ConditionEvaluation.satisfiesBoolean`（布尔投影单一真相源，**解释器非 trace 路径与编译版共用**，编译版编译期绑定 evaluator）。
- **trace 兼容**：trace 永远走解释器（编译版只服务非 trace 快路径），故每条 RuleVersion 的 NodeTrace 仍逐行展开，切换前后 trace 逐行一致（D7 不变）天然成立。
- **灰度**：`engine.rule.eval.compiled-executor.*`（`enabled` / `rule-code-whitelist` / `on-compile-error`=FALLBACK\|FAIL），默认 `enabled=false` 逐字节等同解释器，`EvalEngine` 零改动；`CompiledPredicateEvictor` 监听 `RulePublishedEvent`/`SceneChangedEvent` 调 `evictAll`（键不可变免脏，纯内存卫生）。
- **实测收益（替代原预测）**：Phase 0（冻结 LONG）AST 求值亚微秒（50 条件 865ns），JIT 逃逸分析已使解释器非 trace 路径近零分配（72–120 B/op）——故原"5–10μs→0.3–1μs"预测与"零分配"目标均不成立。A/B 实测编译版速度收益温和（20–50 条件 ~10–17%，5 条件持平）、分配≤解释器（50 条件 72B vs 120B）。默认关，作为架构层可切换能力落地，待生产 profiling（高 QPS + 高条件数 + 巨态分派）达标再灰度开。
- **后续轮（未做）**：alpha/CSE 跨规则条件去重（同 `(sceneCode,eventType)` 下 ConditionNode hash 去重，同 `EvalContext` 内同条件只算一次）独立评估。`rule_version.compiled_predicate_ref` 列保持预留留空（lazy 编译，无需持久化编译产物引用）。

### 2.14 嵌入式 SDK 模式（来源 D20 v1 不做的"嵌入式 SDK"）

- **v1 现状**：评估走中心服务（PUSH / PULL / HYBRID 三模式都基于 RPC）；D17 / D20 §4 的 `RuleVersionWatcher` SPI 已为多 backend 预留，但 v1 仅 `DbPollingRuleWatcher` 一种实现。
- **触发条件**：业务方对评估 RPC 延迟敏感（如风控前置链路 P99 < 5ms）且自带运维能力，愿意承担 SDK 版本管控成本。
- **演进方向（v2 范畴）**：
  - 把 RuleVersion 缓存 + 评估引擎打包成 jar，业务方进程内嵌入直接评估，决策输出在本地返回，评估观测数据回写中心；
  - 配套 `MqRuleWatcher`（Kafka / Pulsar 变更主题）/ `NacosRuleWatcher` / `ZkRuleWatcher` 实现，把 D17 的"15s 最终一致窗口"压到 < 1s；
  - **保留中台严肃治理**：`RuleDefinition` + 不可变快照 + D14 审计 + D6 灰度桶一致性算法都不变，嵌入式 SDK 只是评估执行位置下沉；
  - 与 ice 项目嵌入式形态的差异：不允许业务方在 SDK 端写 / 改规则，配置只读拉取；引擎纯决策（D60），命中后的执行由嵌入方自行编排。
- **依赖**：需先在 v1.5 完成 §2.13 预编译切换以降低 SDK 体积与启动开销。
- **迁移成本**：高（SDK 版本管控 + 评估观测数据回写通道 + 多 backend Watcher 完整实现 + 跨实例灰度桶审计闭环）。
- **优先级**：中。

### 2.15 evaluation_session 异步化路径（来源 D21 派生 / 高吞吐讨论）

> **本节是触发条件达成后的重构方向，v1 阶段无任何专项准备动作，标准工程实践即可**——避免读者把"演进路径"误读为"v1 待办"。

- **v1 现状**：每次评估 1 行同步写，承担三层角色：①**幂等收口**（DB uk on `event_id`，与 Redis trySet 形成双兜底，D11 / §3.10）；②**对账分母**（HIT / MISS / BLOCKED / ERROR 四态统计源，D15 + D22）；③**外键时序**（`node_trace` 引用 `session_id`）。单行同步 insert 1–3 ms，对 D8 千级 QPS 目标是零头，故 D21 仅把 `node_trace`（50–1000 行 / 次）异步化，**`evaluation_session` 保持同步**。
- **触发条件**：
  - profile 显示 `evaluation_session` 同步 insert 进入热路径 P99；或
  - v2 整体演进路径触达——比如转 event sourcing（RuleEvent → MQ → 评估服务消费）时 `evaluation_session` 表的语义会被 MQ + 消费 offset 取代，本节方案废弃。
- **演进方向**——三层角色各自解耦：

  | 角色 | v1 同步落点 | v2 异步落点 |
  |------|------------|------------|
  | 幂等收口 | Redis trySet + DB uk 双兜底 | 持久化 KV（持久化 Redis / Tair）为主，DB uk 降级为慢路径校验或删除 |
  | 对账分母 | `evaluation_session` 行计数 | MQ counter + 列存聚合（与 §2.5 同属"观测数据存储分级"演进方向，但对象不同——§2.5 是 node_trace 行级数据，本节是对账计数 / 列存聚合，两者可独立推进） |
  | 外键时序 | DB autoincrement + 同事务保证 | 评估线程预生成 `session_id`（Snowflake / UUID v7），父子表独立异步队列，反序到达靠查询时间窗口兜底 |

  异步写本身可复用 D21 `TraceWriter` 模型（队列 + 消费者池 + batch insert），但**独立队列**——`evaluation_session` 不可丢（对账锚），队列满策略要从"丢弃"改为"降级回同步写"或"落本地 WAL"。
- **v1 阶段为什么不做前置准备**：
  - **`session_id` 预生成（Snowflake / UUID）**：UUID 索引相对 autoincrement 是随机插入，B+ tree 页分裂 + 写放大，v1 量级反而拖慢；
  - **引入持久化 KV / Tair**：破 D9 "全 MySQL 起步" 红线，运维形态被未到时机的功能拖着走；
  - **抽象 `SessionWriter` SPI**：演进路径未定（v2 可能整体被 MQ / event sourcing 取代），SPI 成空跑负债——对比 D20 §5 `RuleVersionExecutor` SPI（v1.5 切预编译路径明确）才有预留价值；
  - **决策依据不足**：profile 数据缺失，瓶颈猜测大概率偏离实际（更可能是 Pre-Gate 频次计数器 / 解释执行 Visitor / metric round-trip 等更前置环节）。
- **v1 阶段隐含完成的工程卫生**（**不是为本演进做的提前设计**，本来就该做）：
  - `evaluation_session` 写入走 Repository 层（标准三层分层）—— v2 切换实现只动 Repository；
  - Prometheus counter 埋点（D20 监控体系 / §2.6 已覆盖）—— v2 切对账数据源时基线已具备。
- **迁移成本**：高（幂等基础设施切换 + 对账数据源切换 + 父子表时序重设计 + D9 红线松动）。
- **依赖与联动**：与 §2.5 trace 冷热分级（存储分层）正交可独立做，与 §2.14 嵌入式 SDK（评估下沉）同期评估更划算；若 v2 整体转 event sourcing，本节方案废弃。

### 2.16 灰度 A/B 实验互斥（来源 D6 v1 已知缺陷）

- **v1 现状**：灰度 hash 种子为 `hash(subjectId, ruleVersionId) % 100`，两条规则各自独立计算桶号——A/B 实验场景下同一用户可能同时命中两条规则，也可能都不命中，无法保证互斥。
- **触发条件**：业务方需要同一用户在同一实验组内仅命中互斥规则之一（典型：价格实验、权益实验）。
- **设计要点**：
  - **共享种子**：同一实验的规则共享同一 hash 种子 `hash(subjectId, experimentId ?? ruleVersionId) % 100`——同一 subject 在同实验的多条规则上算出**同一个 bucket**。`experimentId` 为空时退回 `ruleVersionId` 独立分桶，行为与 v1 完全一致（向后兼容）。
  - **两种命中模式**（共享种子是前提，命中判定决定语义）：
    - **一致分桶**：多条规则用**相同**区间（如都 `[0,50)`，即 `percentage=50`）→ 同一批人在所有规则上同时命中/同时不命中（人群稳定共选）。
    - **互斥**：多条规则用**不相交**区间（A `[0,50)`、B `[50,100)`）→ 每个 subject 恰好命中其一，实现真正的 A/B 互斥。
  - 不引入"实验"一等公民——`experimentId` 只是字符串标识，实验管理仍由上游 ABTest 平台负责；运营自行保证互斥规则的区间不相交（v1.5 仅做单规则校验，不查兄弟规则）。
  - 无 DDL 变更：`experimentId` / `percentage` / `bucketStart` / `bucketEnd` 均复用 ROLLOUT pre-gate 的 `params` 承载，`PreGateConfig.params` 为 `Map<String,Object>`，任意 key 透明穿透。
- **已实装**：
  - `RolloutPreGate.evaluate`（`rule-eval-svc`）：种子按 `gateParams.experimentId` 是否存在选择（`hash(subjectId:experimentId)` 或 `hash(subjectId:ruleVersionId)`），`& 0x7fffffff` 屏蔽符号位避免 `Integer.MIN_VALUE` 边界。命中判定：配 `bucketStart`/`bucketEnd` 时按 `bucketStart <= bucket < bucketEnd`（区间模式，优先）；否则按 `bucket < percentage`（百分比模式，等价于区间 `[0, percentage)`）；两者皆无则 fail-open。
  - 配置位置在 `rule_version.pre_gates` 列 ROLLOUT 项的 `params`，`pre_gates` JSON → `deserializePreGates` → 快照 `PreGateConfig.params` → `EvalEngine.applyPreGates` 透传至 `RolloutPreGate`，全程零 DDL。
  - 发布期校验（`PublishService.validatePreGateParams`，仅单规则）：`percentage∈[0,100]`、桶区间 `0<=bucketStart<bucketEnd<=100` 且成对出现、`experimentId` 非空白；越界抛 `IllegalArgumentException`（映射 `INVALID_ARGUMENT`）。
  - `RolloutPreGateTest` 覆盖区间互斥（同实验不相交区间 → 每 subject `a^b`）/ 一致分桶 / 百分比向后兼容；`PublishServiceTest` + `RolloutParamsTest` 覆盖发布期校验。
- **迁移成本**：低（已完成，仅 `RolloutPreGate` hash/命中逻辑 + 发布期校验，无 DDL）。

### 2.18 规则列表查询 API（来源 10-api-contract.md §4.4）

- **v1 现状**：`GET /admin/v1/rules` 在 `10-api-contract.md §4.4` 已定义契约，但 v1 不实现——`rule_definition` 表无 list 查询 Mapper，`RuleController` 无对应端点，`AuditService` list 方法为空骨架。
- **触发条件**：前端规则管理列表页需要展示规则列表（筛选 / 分页 / 按场景过滤）。
- **演进方向**：
  - `RuleDefinitionMapper` 补充 `findBySceneCode(tenantId, sceneCode, Pageable)` 查询；
  - `ConfigService` 暴露 `listRules(tenantId, sceneCode, page)` 接口；
  - `RuleController` 实现 `GET /admin/v1/rules?sceneCode=&page=&size=`，返回分页结果；
  - 响应体复用 `ApiResponse<Page<RuleVersionVO>>` 结构，无 DDL 变更。
- **迁移成本**：低（纯查询，不涉及写路径，无状态变更）。
- **已实装（v2）**：`RuleListItemVO`（`rule-config-svc` api 包）+ `MybatisPlusConfig`（`PaginationInnerInterceptor` + `@ConditionalOnMissingBean`）+ `ConfigService.listRules` + `ConfigServiceImpl` 分页查询实现（先按 sceneCode 解析 sceneId，再 LambdaQueryWrapper 分页查 `rule_definition`）+ `RuleController GET /admin/v1/rules` 端点，返回 `ApiResponse<Page<RuleListItemVO>>`；无 DDL 变更。

### 2.19 审计查询 API（来源 10-api-contract.md §6.x）

- **v1 现状**：`GET /admin/v1/sessions`（evaluation_session）、`GET /admin/v1/traces`（node_trace）、`GET /admin/v1/audit-logs`（audit_log）在 `10-api-contract.md §6.x` 已定义契约；`rule-audit-svc` 有 `AuditService` 骨架但 Service 实现为空，v1 跳过。
- **触发条件**：运营 / 风控需要在控制台查询历史评估结果、节点 trace 详情、操作审计日志（排障、合规审计场景）。
- **演进方向**：
  - `AuditService` 补充 `querySession / queryTrace / queryAuditLog` 查询实现，走 `EvaluationSessionMapper` / `NodeTraceMapper` / `AuditLogMapper`；
  - `AuditController`（或在 `RuleController` 扩展）挂载对应端点，支持 tenantId + sceneCode + sessionId + 时间区间过滤；
  - `node_trace` 数据量大时配合 §2.5 冷热分级同步推进，避免全表扫描；
  - 查询路径与写路径完全隔离（只读 Mapper），不影响评估性能。
- **迁移成本**：中（需要补 Mapper 查询 + Service 实现 + Controller 端点 + 分页协议，但无 DDL 变更；`node_trace` 量大时需结合 §2.5 存储分层一起评估）。
- **已实装（v2）**：`rule-audit-svc` 内建 `EvalSessionRow` / `NodeTraceRow` / `AuditLogRow` 只读 entity + 对应三个 `@Mapper` 接口（Modulith 隔离，不引用其他模块 internal）；`AuditServiceImpl` 用 MyBatis-Plus 分页查询实现 `queryAuditLogs` / `queryEvalSessions` / `queryTrace`；`AuditController` 补全 `GET /admin/v1/evaluation-sessions/{sessionId}/trace` 扁平端点 + `/trace/tree` 树重建端点（按 `node_path` 点分路径重建嵌套 AST 树，`TraceTreeNode` record，详见 `10-api-contract.md §6.2`）。

### 2.21 XOR 逻辑节点（来源 trae 参考分析 R1）

- **v1 现状**：AST sealed `RuleNode` 支持 `AndNode / OrNode / NotNode / ConditionNode` 四种节点，不含 XOR（"有且仅有一个子条件满足"）。
- **触发条件**：运营配置出现"非此即彼"类场景——如"下列渠道恰好只来自一个"、"以下优惠类型恰好命中一种"，目前需用 `(A AND NOT B) OR (B AND NOT A)` 的组合规避，可读性差。
- **演进方向**：
  - `RuleNode` sealed class 增加 `XorNode { children: List<RuleNode>, displayLabel?: String }`；
  - `InterpretedExecutor` 补充 XOR 分支：遍历全部子节点（不短路），计数满足节点数，`count == 1` 则 `satisfied=true`；
  - `ConditionNode.weight` 对 XorNode 无意义（XOR 语义与权重不兼容），评估器忽略子节点 weight；
  - trace 层：XorNode 记录 `satisfied` + 各子节点 `satisfied` 结果，帮助运营理解"哪个子条件满足了"；
  - 前端 UI：条件分组卡片新增 XOR 选项（显示文案"有且仅有一个满足"）；
  - 无 DDL 变更（AST 存 JSON，加节点类型是 JSON key 变更）；
  - 发布期输入闭合校验（D20 §3）对 XorNode 透明——只关心 ConditionNode 的变量引用，不感知父节点类型。
- **参考来源**：trae `rule/strategy/RuleXorStrategy.java`，详见 [`docs/superpowers/specs/2026-06-04-trae-reference-design.md`](./specs/2026-06-04-trae-reference-design.md) §三 R1。
- **已实装（d12-scorecard-evaluator Task 7）**：`XorNode` sealed AST 节点 + `AstJsonCodec` 映射 + `InterpretedExecutor` / `TracingInterpretedExecutor` 全量遍历分支 + 5 个单测覆盖（恰好一个/全部满足/全部不满足/空/两个满足）。
- **迁移成本**：低（sealed class + evaluator + 前端编辑器，无 DDL，无 schema 迁移）。

### 2.22 基础设施层可观测性（OTLP + LGTM）

- **已实现**（D22，`rule-app` v1）。完整使用说明见 [`07-operability.md §十一`](./07-operability.md#十一基础设施可观测性opentelemetry--lgtm)。
- **实现要点**：
  - `rule-app/pom.xml` 加 `spring-boot-starter-opentelemetry` + `opentelemetry-logback-appender-1.0`；
  - `application.yml` 通过 `management.opentelemetry.{metrics,tracing,logging}.export.otlp.endpoint` 配置三信号推送，并排除 `OtlpMetricsExportAutoConfiguration`（避免 protobuf 版本冲突）；
  - `logback-spring.xml` 加 `OpenTelemetryAppender`，日志推 Loki，traceId 自动注入；
  - 本地 `docker-compose.yml` 加 `grafana/otel-lgtm:0.11.5`（Grafana + Loki + Tempo + Mimir 单镜像）；
  - `rule-observability`（业务 trace 层，`TraceWriterDbImpl` / `RuleMetrics`）不改动，两层可观测性并行。

### 2.23 B20 时间框架：DATE/DATETIME 一等 dataType + 统一时钟注入（来源 D44，已实装）

- **v1 现状（B20 前）**：`EvalContext` 有 `now: Instant` 槽位但未从入口统一注入；`dataType` 枚举不含时间类型；`DATE_BEFORE` / `DATE_AFTER` 文档有描述但实现未完整落地；`time.window` / `time.occurred_at` conditionType 未注册。
- **触发条件**：业务方需要时间窗口判断（营业时间生效）、日期型指标比较（账户创建时间、到期时间）及事件有效期判断。
- **设计要点（D44）**：
  - `EvalContext.now` 在 `EvalServiceImpl.doEvaluate` / `EvalEngine.evaluate` 入口调用一次 `Instant.now()`，整棵 AST 共用同一时钟（禁止默认 `Instant.now()` 重载）；
  - `DATE` / `DATETIME` 作为一等 `dataType`：`DateComparisonStrategy` / `DateTimeComparisonStrategy` 纯策略；两阶段管线（PlaceholderResolver + TimeZoneResolver → 策略）；
  - 发布期矩阵（`AstDataTypeResolver`）扩展：EQ/NEQ/BETWEEN/NOT_BETWEEN 允许集合 += DATE/DATETIME；DATE_BEFORE/DATE_AFTER 新增行；
  - `time.window` / `time.occurred_at` 在 `KernelEvaluators.defaults()` 注册，内置路径闭合集合，无 `metricCode`；
  - `context_snapshot` 升级为嵌套格式 `{"metrics": {...}, "evalNow": "<ISO>"}`；
  - `V1_5__add_date_datetime_to_metric_datatype.sql` 扩展 `metric_definition.data_type` ENUM；
  - Scene 级默认时区（`sceneDefaultTimezone`）槽位保留，B20 阶段暂缓（调用方传 null），由后续批次激活。
- **不做（B21+ 范畴）**：相对时长算术（`$now-P7D`）；`EvalContext.now` 注入 SQL_AGGREGATE 的 `:now` 绑定变量；Scene 级默认时区激活。
- **已实装（B20 / D44）**：见 D44 已实装清单。构建于 B19（AstDataTypeResolver 矩阵）之上。
- **迁移成本**：已完成（B20 批次 13 个 Task）。

### 2.20 规则草稿创建 API（来源 10-api-contract.md §4.1）

- **v1 现状**：`POST /admin/v1/rules` 在 `10-api-contract.md §4.1` 已定义契约，但 v1 仅留占位实现（返回 501 NOT_IMPLEMENTED）。
- **触发条件**：前端规则编辑器需要保存新规则草稿（AST + bindings + preGates），前端在 publish 前先 createDraft 获取 `ruleDefinitionId`。
- **演进方向**：实装完整的草稿写入路径，事务内插入 `rule_definition`（DRAFT）+ `rule_version`（DRAFT）+ `audit_log`（CREATE）；同 tenant+scene 下 code 唯一性前置校验；返回 201 + `{ruleDefinitionId, ruleVersionId, version, status}`。
- **迁移成本**：低（纯写路径，无 DDL 变更，无索引热更，无事件发布）。
- **已实装（v2）**：`DraftCreatedResult`（`rule-config-svc` api 包）+ `CreateRuleRequest` 字段更新（`sceneCode` + 4 个 `JsonNode` 字段）+ `ConfigService.createDraft` + `PublishService.createDraft`（事务、code 唯一性校验）+ `ConfigServiceImpl` 委托 + `RuleController POST /admin/v1/rules`（`@Valid` + 201）；无 DDL 变更。

---

## 三、决策时间线

> 来源：`00-decisions.md` 各组标题 + `README.md` §七 版本史。按 D 编号顺序，标记"何时做了什么取舍"。

### 第一组：核心数据模型（D1–D7）

核心 DDD 边界确立：Tenant / Scene / Rule / Condition / Metric / EvalContext 分层；D4 动作协议（声明式优先 + SPI 兜底）；D5 Metric 按需取数（不预加载全量）；D6 不可变版本快照（评估与配置解耦基础）；D7 Dry-run 一等公民（节点级 trace 落库，后续可运维性基础）。

### 第二组：指标与外部集成（D8–D11）

D8 性能目标（千级 QPS，决定是否引入 RETE / 预编译 / 索引化匹配）；D9 v1 全 MySQL 不引入列存 / 大数据；D10 AI 评估节点预留（LLMConditionEvaluator 占位，v1 不实现）；D11 Job 模式 + 调度器选型（定时扫表 / 周期回查类规则纳入一等公民）。

### 第三组：Rule 结构与模式（D12–D17）

D12 `kind` 字段预留（AST_BOOLEAN → 多态出口）；D13 payloadSchema v1 不做运行时强校验（闭合）；D14 审计强一致 + 敏感数据交给调用方处理；D15 单节点失败降级（不整树崩）；D16 链式触发显式禁止（ActionHandler 不返回新事件，业务链式走外部 MQ）；D17 倒排索引不可变快照热更（Matcher 无 DB 热路径，热更 ≤15s）。

### 第四组：精化与派生（D18–D22）

D18 Action 失败归一为 FAILED(retryable)，补偿不自动触发；D19 rule_version 不可变只读，rollback = 新草稿；D20 metric 批量预拉 + EvalContext 内冻结；D21 trace 异步批写 + session 同步写（双轨）；D22 四态对账（HIT/MISS/BLOCKED/ERROR）。

### 第五组：最终精化（D23–D30）

D23 幂等 Redis+DB 协议落定细节；D24 Scene 配置热加载（30s 间隔）；D25 Context 构建并发模型（CompletableFuture.allOf() 并行 + SubjectLoader SPI，主体加载与 metric 并行）；D26 Decision 实体（Tenant 级）+ 多规则命中合成（HIGHEST_PRIORITY）；D27 Action 从 Rule 迁移到 Decision（最大重构节点）；D28 actions 在发布时快照到 rule_version；D29 PUSH/HYBRID 默认 HIGHEST_PRIORITY；D30 providedMetrics + allowProvided per sourceType（最晚决策）。

### 核心转折点

| 时间节点 | 决策 | 影响 |
|---------|------|------|
| D6 确立 | 不可变版本快照 | 评估线程可安全持有 RuleVersion 引用；热更 = 新版本 + 索引切换，不是原地更新 |
| D21 确立 | trace 异步批写 / session 同步写 | 双轨写入架构基础；P99 延迟保障；trace 成旁路观察通道 |
| D27 确立 | Action 从 Rule 迁移到 Decision | 最大单次重构；同一 Decision 的所有命中共享 Action 配置 |
| D30 确立 | providedMetrics allowProvided per sourceType | 调用方数据权威性最晚才表态；per-sourceType 粒度是 D30 讨论时才浮现的需求 |
| D60 确立 | 规则引擎纯决策化，移除动作子系统 | 引擎收敛为纯决策（只产出 Decision）；动作子系统（ActionHandler SPI / 派发 / `action_execution` / 配置）整体移除，编排交流程引擎；作废 D4 / D16 / D18 / D27 / D28 / D57。上文 D4–D30 时间线中的动作相关条目为当时的历史记录，其结论已被 D60 取代 |

---

## 四、已否决方案

| 方案 | 否决时间节点 | 否决理由 | 正式采用的替代方案 |
|------|------------|---------|-----------------|
| webhook.call ActionType（引擎主动发 HTTP 回调） | D27 讨论时 | 调用方需维护公网 endpoint；重试 / 超时由引擎管，复杂度爆炸 | `@ActionType` SPI（命令式 ActionHandler） |
| 同步事务写 node_trace | D21 确立时 | 量大（10-1000 行/次），同步写直接吃风控 P99 预算；trace 是旁路观察通道，丢弃不影响正确性 | TraceWriter 异步批写 |
| 全局 metric cache TTL（不区分 per-metric） | D5 / D20 讨论时 | 不同 metric 实效性差异大（account.age 3600s vs balance 0s）；全局 TTL 只能取最保守值 = 不 cache | per-metric `cachePolicyDefault.ttl` |
| providedMetrics 全局 allowProvided=true | D30 讨论时 | 高权威 metric（如账户余额 SQL_AGGREGATE）不应被调用方覆盖 | per-sourceType 默认值（ATTRIBUTE / EXTERNAL_HTTP=true，SQL_AGGREGATE / STREAM=false） |
| persistedMetricCodes（引擎持久化 provided 值） | D30 讨论时 | 引擎承担业务数据存储职责；与不可变快照语义冲突 | 不做，provided 值只活在本次评估 |
| Action 留在 Rule 上（D27 之前） | D27 确立时 | 同一 Rule 命中只能派发一组 Action；不同 Decision 需要不同 Action 无法表达 | D27：Action 迁移到 Decision |
| evaluation_session 全量异步写 | D21 讨论时 | session 行是幂等 UK 锚点，必须在评估开始前存在；极端情况下异步写导致幂等失效 | 仅 session 行同步写，trace 行异步写 |
| 三层模型（Rule / RuleGroup / Condition） | 架构初期 | RuleGroup 概念冗余，树形 AST 已能表达 AND/OR 复合；两层更简洁 | 两层模型（Rule + ConditionNode AST） |
| 批量原子发布 API | D19 讨论时 | 跨规则原子事务复杂度高，且失败回滚语义不清晰 | 前端逐条提交，批量由调用层拆分 |
| 完全延后权限与审计 | D14 讨论时（选项 C） | 后期 ALTER TABLE 痛；合规要求不可等 | A：占位字段 + audit_log 表（D14） |
| urule 风格 FunctionLibrary（全局函数注册） | D20 §3 / D16 / §3.9 讨论时 | 与闭合校验、禁止副作用、metric 只读三条决策正面冲突 | `@ConditionType` SPI；高频自定义表达式走 D12 `kind=EXPRESSION_SCRIPT` |
| 独立 ConstantLibrary 一等概念 | 同上 | 与 FunctionLibrary 配套评估，无独立优先级；只读 metric 已覆盖需求 | 用只读 metric 替代（§3.9 关键边界） |

---

## 五、跨文档 TODO 接收锚点

> 本节列出 v1 文档中标记 "详见 08-evolution" 的所有 TODO，便于本文档展开时一次性迁移完毕。

| 来源 | TODO 内容 | 迁入目标 | 状态 |
|------|-----------|---------|------|
| [`01-concepts.md`](./01-concepts.md) §3.4 "kind 多态边界" 小节 | D12 演进说明（5 个 kind 的字段映射与共享属性） | §2.1 kind 多态 | ✅ 已迁入 |
| [`01-concepts.md`](./01-concepts.md) §3.9 `metricVersion` 字段 | Metric 版本化语义 | §2.2 Metric 版本化 | ✅ 已迁入；✅ 已实装 B6 |
| [`00-decisions.md`](./00-decisions.md) D14 v1 不做的"敏感数据" | 合规演进路径 | §2.8 合规演进 | ✅ 已迁入 |
| [`00-decisions.md`](./00-decisions.md) D13 v1 不做的"payloadSchema 演进" | schema 升版本如何兼容存量规则 | §2.12 Scene schema 演进 | ✅ 已迁入 |
| [`00-decisions.md`](./00-decisions.md) D20 v1 不做的"完整预编译 / alpha 共享" | Visitor 切预编译 lambda + 跨规则条件去重 | §2.13 评估期预编译完全切换 | ✅ 已迁入 |
| [`00-decisions.md`](./00-decisions.md) D20 v1 不做的"嵌入式 SDK" | 评估下沉到业务进程 + Watcher 多 backend 完整实现 | §2.14 嵌入式 SDK 模式 | ✅ 已迁入 |
| [`00-decisions.md`](./00-decisions.md) D21 派生（`evaluation_session` 同步写） | 三层角色解耦 + v1 不做前置准备的理由 | §2.15 evaluation_session 异步化路径 | ✅ 已迁入 |

