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

- **现状（全部实装）**：六种 kind 均已落地——`AST_BOOLEAN`（v1）/ `SCORECARD`（D12）/ `DECISION_TREE` / `DECISION_TABLE`（D42）/ `EXPRESSION_SCRIPT`（D66）/ `DECISION_FLOW`（D75，决策图编排层）；`EvalResult` 多态字段（`score / category / decision`）各 kind 按需填充。
- **驱动**：评分卡 / 决策树 / 决策表 / 表达式脚本 / 决策图编排类业务需求陆续到来，占位逐个填实。
- **迁移成本**：已完成（六种 kind 的 evaluator 全部落地）。

**设计原则**：D12 引入 `Rule.kind` 时是为评分卡 / 决策树 / 决策表 / 脚本类规则**预留 schema 占位**（v1 仅 `AST_BOOLEAN`）；此后占位已全部填实（SCORECARD D12 / DECISION_TREE·DECISION_TABLE D42 / EXPRESSION_SCRIPT D66 / DECISION_FLOW D75），同表多态 JSON 列承载，公共能力天然共享。

**各 kind 共享 Rule 的公共属性**：trigger / preGates（含 ROLLOUT 灰度）/ decisionBindings / version / Scene 治理都不变，多态只在"判定主体"内部——（注：actions 已迁移到 Decision，不再是 Rule 的直接字段，D27）

> 下表"判定主体字段"列只指示**形态**与**承载方式**，具体字段命名留待 v2 设计时定稿，避免占名误导后续设计。

| kind | 判定主体承载 | 输出字段 | 状态 |
|------|------------|---------|------|
| `AST_BOOLEAN` | sealed `RuleNode` AST 树（已在 v1 落地） | `EvalResult.satisfied` | 已实装（v1） |
| `SCORECARD` | JSON 列承载条件列表 + 各自 `weight` + 阈值带 | `EvalResult.score` | 已实装（D12） |
| `DECISION_TREE` | JSON 列承载嵌套 if/then/else 树 | `EvalResult.category` | 已实装（D42） |
| `DECISION_TABLE` | JSON 列承载输入列 + 输出列 + 行集合矩阵 | `EvalResult.decision` | 已实装（D42） |
| `EXPRESSION_SCRIPT` | 文本列承载脚本（CEL / Aviator / Groovy / JEXL / QLExpress / JsonLogic 六引擎） | 按脚本返回值多态填 | 已实装（D66） |
| `DECISION_FLOW` | JSON 列承载决策图 DAG（`flowGraph`：RuleRef / Switch / Transform / Output 节点）；图只编排、叶子经 RuleRef 复用现有 5 形态 | `finalDecision` / `hitDecisions`（Output 节点产出） | 已实装（D75） |

**`EvalResult` 是稳定多态**：PULL 模式调用方拿到的对象 shape 是 `{satisfied, score?, category?, decision?, trace}`——SCORECARD 多填 `score`（D12），DECISION_TREE 填 `category`（D42），DECISION_TABLE 填 `decision`（D42），PULL API 签名始终不变；节点 trace 跨 kind 统一，运营自助排障的能力 100% 复用。

**决策集不进 `Rule.kind` 枚举**：`Scene.executionStrategy` 是 Scene 字段，v1 已落地 `HIGHEST_PRIORITY`（D29）；`ALL_HITS` / `FIRST_HIT` 已实装（D41）。

**「决策流」拆两义（命名双义澄清，D75）**：
- ① **同步纯决策图编排**——一次评估内 `决策A → 按结果分支 → 决策B → 输出`，无状态、高频。作为第 6 种 kind `DECISION_FLOW` 进 `Rule.kind`，图只编排、叶子复用现有 5 形态（D75，已实装，详见 §2.4）。
- ② **有状态动作编排**——跨时间、要等待 / 人工 / 外部事件的 `Rule → Rule` 流程。由消费方 / 流程引擎承载（D60），在 Rule 层级之外。

本条早期表述「决策流不进 kind」特指 ②；① 是想清楚后的正解，归 kind。判据：要不要「等」——要等归流程引擎，一口气算完归 `DECISION_FLOW`。

**为什么不另起表**：评分卡 / 决策树仍需要 Rule 的全部公共属性（触发 / 准入 / 灰度 / 决策绑定 / 版本快照），独立表会复制 80% 的列且数据散布、跨形态报表困难；用 `kind` 字段 + 多态 JSON 列在同一张表里，公共能力天然共享。

**演进路径（已完成）**：六种 kind 的 evaluator 均已落地——SCORECARD 启用 `ConditionNode.weight`（D12）；DECISION_TREE / DECISION_TABLE 各自的内部 JSON 结构与 evaluator（D42）；EXPRESSION_SCRIPT 走 `ScriptExecutor` + 按 lang 路由的 `ExpressionEngine` SPI（D66）；DECISION_FLOW 走 `FlowExecutor`（第 6 个 `RuleVersionExecutor` 实现，从 input 顺边遍历、RuleRef 回调单规则求值入口，D75）；`Scene.executionStrategy` 配合决策集（D29/D41）。

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

- **现状（D77 已实装轻量路径）**：`rule_definition` 去 `scene_id` 改 `scene_code`（业务标识，消灭代理键翻译层，V1_41），`ruleCode` 为 tenant 级唯一；**DECISION_FLOW 的 RuleRefNode 已可跨 Scene 引用**（发布期按 tenant 级查被引规则并冻结其自身 sceneCode，D75 冻结机制不变）+ 反向血缘 `GET /admin/v1/rules/{code}/referencedBy`。
- **触发条件**：相同条件逻辑在多个 Scene 重复配置（如"账户开立 < 90 天"同时出现在 risk.transfer / risk.withdrawal / risk.payment），改动时需逐 Scene 同步，易漏、易产生版本漂移。
- **演进方向**：
  - **`RuleTemplate`**：参数化的规则骨架，变量用占位符，实例化时注入 Scene 专属参数值；发布时膨胀为普通 `rule_version`，评估期不感知模板，公共能力（灰度 / Pre-Gate / Action）完全复用；
  - **`RuleFragment`**：可被 `Rule.conditionAst` 引用的 AST 子树（逻辑提取为独立实体），发布时展开内联，语义等价于手动复制；
  - 两种形态适用场景不同：Template 适合大框架复用（同一规则结构不同参数）；Fragment 适合条件组复用（同一 AND/OR 子树在多处出现）；
  - 新增 `rule_template` / `rule_fragment` 表 + 引用关系表；`rule_version` 发布时展开并生成不可变快照，与 D6 快照语义一致；
  - 灰度：Template 实例化后继承普通 `rule_version` 的 ROLLOUT Gate 语义，不需要专门机制。
- **迁移成本**：中（DDL 零变更；核心改动集中在 config-svc 5 处 + 前端 3 处 + 新增反向血缘查询）。
- **依赖**：§2.10 规则模板市场依赖本节就位。
- **已实装（D77 + D74）**：
  - D77（`openspec/changes/cross-scene-rule-ref`）：`rule_definition` scene_id→scene_code + DECISION_FLOW RuleRefNode 跨 Scene 引用 + 反向血缘 + 前端 RuleRef 下拉 tenant 全量并按 sceneCode 分组
  - D74 模板系统 V2（2026-07-25）：platform 层 `rule_template`(身份)/`rule_template_version`(快照,不可变)/`rule_template_instantiation`(溯源) 三表；SlotKind 拆分（VALUE/METRIC_REF/DECISION_REF/RULE_REF）；SlotRefResolver SPI；实例化流水线（REF pass-through + binder 填 skeleton + PublishService.createDraft）；跨租户可见性（SYSTEM tenant 级模板 → STANDARD tenant 实例化）；核心表零模板列。**RuleTemplate 已覆盖**，RuleFragment（AST 子树提取复用）仍为后续演进。

### 2.4 规则间依赖与编排（来源 #3）

- **v1 现状**：引擎纯决策（D60），规则间不存在内置依赖与顺序保证；业务需要顺序走外部 MQ / 流程引擎编排。
- **触发条件**："先反欺诈再合规"类顺序依赖需求，或"Rule A 命中后才触发 Rule B"的流程依赖，目前只能通过外部编排，依赖关系对运营不可见，排障成本高。
- **演进方向（分两类，按「要不要等」分流）**：
  - ① **同步、一次评估内**的多步决策编排（`决策A → 按结果分支 → 决策B`），无状态、高频 → 第 6 种 kind `DECISION_FLOW` 在引擎内承载（D75，决策图编排层，已实装）；图只编排、叶子复用现有 5 形态，规则间依赖对运营可见（画布 + 血缘）。
  - ② **跨时间、有状态**的 `Rule → Rule` 流程（要等待 / 人工 / 外部事件）→ 接 Flowable / Camunda 流程引擎（D60），把"Rule 命中 → 触发下一 Rule"作为工作流节点而非 Rule 内能力。引擎仍保持单事件单评估，有状态编排交流程引擎。
- **迁移成本**：① 已完成（加性新 kind，D75 已实装）；② 高（引入新组件、运维形态变化）。

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

- **v1 现状**：D74 模板系统 V2 已落地——platform 层 `rule_template` / `rule_template_version` / `rule_template_instantiation`，跨租户可见性（SYSTEM→STANDARD），实例化流水线。B7 导出/导入也已落地。模板市场的前置依赖（模板本身 + 跨环境搬运）已全部就位。
- **演进方向**：在现有模板子系统上叠加**发现/评分/分类层**——平台级模板仓库（按 kind/领域/热度浏览）+ 评分/评论 + 版本管理 + 跨租户分发。本质是模板的"应用商店"层，而非模板机制本身。
- **优先级**：低（v3 范畴，依赖实际多租户运营规模触发）。

### 2.11 外部系统集成契约标准化（来源 #17）

- **v1 现状**：`MetricSource` 支持外部 HTTP，但未定义统一接口契约；不同业务自行约定。
- **触发条件**：多业务团队各自实现 `EXTERNAL_HTTP` MetricSource，协议、错误码、超时行为各异，无法跨团队复用，联调成本高；新业务接入时无参考规范，接入周期长。
- **演进方向**：定义标准化指标获取协议（参考 OpenTelemetry / OpenFeature 模型），引入 `MetricFetcher` 通用 SDK + 协议测试套件。
- **迁移成本**：中。

### 2.12 Scene schema 演进（来源 D13；D69 收口）

- **现状（D69 落地）**：`Scene.payloadSchema`（`List<PayloadFieldSpec>`：name/type/required/enum/min/max/pattern）在 Scene 表上，是输入契约的单一真相源。type 受 `PayloadFieldType` 封闭集校验（创建/更新期 fail-fast）。**模型 2 冻结**：发布期把规则引用字段的完整约束冻进 `rule_version.payload_dependencies`，运行期 `PayloadInputValidator` 强制 required+类型+enum/min/max/pattern。
- **schema 变更兼容（已解决）**：D69 选模型 2——约束随规则发布冻结，运营事后改 scene payloadSchema **不影响已发布规则**（可复现）。故原"版本号 + 历史表 + 影响规则清单 + 灰度切换"那套**不再需要**：`scene.payload_schema_version` 列与 `scene_payload_schema_history` 表已于 `V1_30` 删除，scene 变更历史改走 `audit_log` 前后快照（`SceneSnapshot`）。
- **仍未做（v3+）**：AST payload 字段引用校验（ConditionNode.params 字段引用编码规范）；payloadSchema 嵌套对象 / JSON Schema 完整子集（oneOf/$ref）；`scene.default_params`（timezone/currency 等）接入评估（当前运营能设能存、但 eval 侧未加载，仅条件级 `params.timezone` 生效，见 D69 后续①）。

### 2.13 评估期预编译（纯编译版已落地 2026-06-13，见 D67）

- **已落地（纯编译版）**：`AstCompiler` 把 `AST_BOOLEAN` 布尔 AST（And/Or/Not/Xor/Condition）编译为嵌套 `Predicate<EvalContext>` 闭包；`CompiledExecutor`（包 `InterpretedExecutor`）按不可变 ruleVersionId 缓存（`RuleVersionCache`），**非 trace 快路径**直接 `predicate.test(ctx)`，开 trace / 灰度未命中 / 关开关时回落解释器。
- **编译技术**：闭包组合（非 Janino 字节码 / 非 LambdaMetafactory——零依赖、零类加载、可调试），组合节点编译期收数组、求值期下标循环避免 Iterator 分配。叶子经 `ConditionEvaluation.satisfiesBoolean`（布尔投影单一真相源，**解释器非 trace 路径与编译版共用**，编译版编译期绑定 evaluator）。
- **trace 兼容**：trace 永远走解释器（编译版只服务非 trace 快路径），故每条 RuleVersion 的 NodeTrace 仍逐行展开，切换前后 trace 逐行一致（D7 不变）天然成立。
- **灰度**：`engine.rule.eval.compiled-executor.*`（`enabled` / `rule-code-whitelist` / `on-compile-error`=FALLBACK\|FAIL），默认 `enabled=false` 逐字节等同解释器，`EvalEngine` 零改动；`CompiledPredicateEvictor` 监听 `RulePublishedEvent`/`SceneChangedEvent` 调 `evictAll`（键不可变免脏，纯内存卫生）。
- **实测收益（替代原预测）**：Phase 0（冻结 LONG）AST 求值亚微秒（50 条件 865ns），JIT 逃逸分析已使解释器非 trace 路径近零分配（72–120 B/op）——故原"5–10μs→0.3–1μs"预测与"零分配"目标均不成立。A/B 实测编译版速度收益温和（20–50 条件 ~10–17%，5 条件持平）、分配≤解释器（50 条件 72B vs 120B）。默认关，作为架构层可切换能力落地，待生产 profiling（高 QPS + 高条件数 + 巨态分派）达标再灰度开。
- **后续轮（不做）**：alpha/CSE 跨规则条件去重——本项目不可变独立快照(D6)无对象级 Condition 共享引用，AST_BOOLEAN 已预编译到纳秒级(D67)，建缓存键+HashMap 查找开销>重新求值，是负优化。与 trae 架构差异（对方 Rule 层组合共享引用）导致其合理在本项目不成立。

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

**已实装（d12-scorecard-evaluator Task 7）：`XorNode` 加入 sealed AST，语义为"有且仅有一个子条件满足"。**

- **能力**：`RuleNode` sealed class 含 `XorNode { children, displayLabel? }`；`InterpretedExecutor` / `TracingInterpretedExecutor` 遍历全部子节点（不短路）计数满足数，`count == 1` 则 `satisfied=true`；`AstJsonCodec` 映射 + 5 个单测（恰好一个/全部满足/全部不满足/空/两个满足）。覆盖"非此即彼"场景（"渠道恰好来自一个"等），免去 `(A AND NOT B) OR (B AND NOT A)` 的可读性负担。
- **边界**：`ConditionNode.weight` 对 XorNode 无意义（XOR 与权重不兼容），评估器忽略子节点 weight；trace 记录 XorNode `satisfied` + 各子节点结果；发布期输入闭合校验（D20 §3）对 XorNode 透明（只认 ConditionNode 变量引用）；无 DDL 变更（AST 存 JSON）。
- **来源**：trae `rule/strategy/RuleXorStrategy.java`，见 [`reference-projects.md`](./reference-projects.md) §2.1 R1 / [`specs/archive/2026-06-04-trae-reference-design.md`](./superpowers/specs/archive/2026-06-04-trae-reference-design.md) §三 R1。

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

### 2.24 特征预计算 / 物化特征层（来源 风控演进成熟度对照分析 — 第三代「高性能」）

- **v1 现状**：D5 定调"单事件 + MetricSource 内 SQL 聚合"；评估期 Context Builder 扫 AST 收集 `metricCode` → `MetricRegistry` 并发取数（D25），即**实时取数**。D20 把同一次评估内 N 个 metric 压成批量预拉（1 次 mget），但仍是评估期现取。`providedMetrics`（D30，公开侧已退场 D55，仅内部 SDK / Job 注入保留）是**调用方携值**，不是平台侧预计算。**无物化 / 预计算特征层**。
- **触发条件**：决策依赖特征数多、加工链长、含多个外部数据源时，评估期实时取数的 IO 等待吃掉延迟预算，撑不住风控秒级 / 万级 QPS（D8 下一档目标）。**这比 CEP（§2 预留窗口指标）更早撞上**——是"营销千级 QPS → 风控万级 QPS"路上的第一道性能墙。
- **演进方向（空间换时间）**：
  - `MetricSourceHandler` 之上加**物化档** `sourceType`：metric 值预计算后写 KV（持久化 Redis / Tair / 列存），评估期 O(1) 取，不走实时 SQL；
  - 刷新用 **CDC**（业务表 binlog → 重算受影响 metric）保证物化值准确率；触发时机 / 失效窗口可配；
  - **分层取数**：内部数据预计算物化 / 三方收费接口仍实时取（避免预计算后未用造成数据成本浪费）；
  - 可选 **Lambda 形态**：T-1 历史批处理 + 当日增量融合（全局去重统计的精度损失需按 metric 标注）；
  - 物化未命中 / 未完成时的降级语义（忽略 / 失败 / 回落实时算）按 metric 粒度配置，归 D15 失败语义体系。
- **接口衔接面**：`metric_definition` 加物化档 `sourceType` + 刷新策略列；`MetricSourceHandler` 增物化实现（读 KV）+ 一套离线 / CDC 预计算 writer（独立于评估热路径）；`EvalContext` 取数侧透明（仍按 `metricCode` 索引，选 handler 由 `sourceType` 路由，**评估代码零改动**）。
- **迁移成本**：高（引入物化 KV / 列存为新存储组件，需与 D9 "全 MySQL 起步"基调专门决策——注意 metric 值不属 D9 引擎自有持久化范畴、D20 已含 Redis 取数后端；外加 CDC 管道 + 预计算 writer + metric 注册扩 `sourceType` + 降级语义）。
- **依赖与联动**：与 §2.2 metric 版本化正交（物化的是**值**，版本管的是**定义口径**，可叠加）；与 CEP（§2 预留窗口指标）同属"特征供给增强"，但 CEP 解时间窗序列、本锚点解高频实时取数性能，可独立推进。

### 2.25 what-if 批量回放 / 新规则陪跑（来源 风控演进成熟度对照分析 — 第三代「高可靠」；建立在 D70 之上）

- **现状（D70 已实装"忠实重放"）**：单条历史 `evaluation_session` 可**忠实重放**——锁当时规则版本 + 灌当时 payload / metric / evalNow + 跳过取数，重跑出与当时一致的 `EvalResult` + trace，**只读零副作用**（`POST /admin/v1/evaluation-sessions/{sessionId}/replay`）。捕获三件套（payload + `candidate_rule_version_ids` + `context_snapshot`）默认开。其中 **what-if**（历史输入 × 当前 / 新规则版本，而非当时版本）被 D70 明确列为**非目标、留后续**；**批量回放** D70 未涉及（按 `.../{sessionId}/replay` 单 session API 设计）。本锚点即补这两项。
- **触发条件**：风控规则上线前要评估"新规则版本在历史真实流量上的命中率 / 拦截率变化"，降低变更风险（对照分析第三代"流量回放与模型回溯 + 模型陪跑 / 平滑决策"刚需）。单 session 忠实重放只能验"当时跑得对不对"，答不了"换规则会怎样"。
- **演进方向（复用 D70 地基，改两处）**：
  - **what-if**：把 D70 `evaluateReplay` 的"锁当时版本"改为"取指定 / 当前版本"，输入仍灌历史冻结的 payload / metric（degraded `EvalContextAssembler` 回灌、绕过取数不变）——历史数据 × 新规则，得到反事实 `EvalResult`；
  - **批量**：按 `sceneCode` + 时间窗 + 采样率选一批历史 session，逐条 what-if 重放，聚合新旧决策 diff（命中率 / 拦截率 / 各 Decision 分布变化 + 逐条差异样本）；
  - **陪跑**：新版本不发布，仅以 what-if 批量回放产出效果报告，人工对比专家经验后再走标准发布（D19）；离线执行，不占评估热路径，不落新 session、不触发下游（延续 D70 只读零副作用）。
- **忠实度边界（继承 D70）**：subject 重载当前、metric 仅存 rawValue（丢 dataType / isError）；what-if 额外引入"新规则可能引用历史 session 未捕获的 metric"——此时缺值按 D15 降级标注，报告须**显式标这类不可比样本**，不静默填默认值。
- **接口衔接面**：新增批量回放任务入口（`POST /admin/v1/replay-batches`，异步任务 + 进度 / 报告查询）+ `BatchReplayService`（编排采样查询 + 逐条调 `evaluateReplay` 的 what-if 变体）；`EvalEngine.evaluateReplay` 抽出"版本来源"参数（当时 / 指定版本）；**不动 D70 捕获落库路径**。
- **迁移成本**：中（复用 D70 忠实重放核心 + dry-run 取版本逻辑；主要增量是批量任务编排、采样查询、diff 聚合报告，无新存储红线）。
- **依赖与联动**：**强依赖 D70**（忠实重放 + 三件套捕获默认开；无捕获的存量 session 不可回放，`REPLAY_NOT_REPRODUCIBLE`）；与 §2.24 特征预计算正交；模型节点（若落地，见对照分析第二梯队）落地后，本锚点天然支持"新模型陪跑"（模型分作为 metric 灌入回放）。

### 2.26 规则集静态分析 / 冲突检测（来源 对照成熟决策平台分析 — 治理维度，Drools verifier / DMN 完备性校验同类能力）

- **v1 现状**：规则以**不可变 AST 快照**承载，命中走**倒排索引**匹配（D17），决策由 `decisionBindings` 绑定。具备做静态分析的全部素材（AST 结构 + 优先级 + Decision 绑定），但**无任何规则集级别的一致性/完备性分析**——运营无法得知一个 Scene 下规则是否有死规则、冲突、覆盖缺口。
- **触发条件**：单 Scene 规则数增长（风控场景尤甚），运营自己无法掌握"哪条规则还活着、哪两条打架、哪个 Decision 没人产出"，规则集腐化但无人察觉。这是"规则引擎"长成"规则**管理**平台"的分水岭。
- **演进方向（纯静态、离线、只读快照）**：
  - **死规则检测**：某规则被更高优先级规则在输入空间上完全覆盖，永远命中不到；
  - **冲突检测**：同一输入下两条规则产出语义冲突的 Decision（按 Decision 优先级/category 判定）；
  - **冗余/重叠**：条件空间高度重叠的规则提示合并；
  - **覆盖缺口**：某 Decision 无任何规则路径可产出它（绑定了但触达不到）；
  - 输出**分析报告**（按 Scene），非阻断——发布期可选挂"警告不拦截"。
- **接口衔接面**：新增离线 `RuleSetAnalyzer`（读 `rule_version` 快照 + AST + decisionBindings，纯计算）+ 报告查询 API（`GET /admin/v1/scenes/{sceneCode}/analysis`）；前端编辑器右栏可展示告警（复用 06-frontend 元数据驱动渲染）。**不碰评估热路径、零 DDL 红线、不引新基建。**
- **迁移成本**：中低（一个分析 service + 报告 API；难点在条件空间覆盖判定算法，可按 conditionType 能力分档——区间/枚举可精确判，开放表达式降级为"无法判定"）。
- **依赖与联动**：建在 D6 不可变快照 + D17 索引之上；与 §2.4 规则间依赖编排正交（那是运行时编排，本锚点是静态质量）；与 §2.27 决策效果闭环互补（静态分析看"逻辑自洽"，效果闭环看"实战准不准"）。

**已实装（B31 / 2026-06-18）：**

- **7 类检查**：① 不一致 incoherence（单规则条件矛盾，cube 为空集，ERROR）② 死规则/遮蔽 dead（被更高优先级规则在输入空间上完全覆盖）③ 冲突 conflict（输入相交、决策对立）④ 重叠 overlap（输入相交、决策相同，可合并，INFO）⑤ 覆盖缺口 coverageGap（绑定的 Decision 无任何规则路径产出）⑥ **冗余 redundancy（单规则内一条件被同组另一条件蕴含，可简化；AST_BOOLEAN 扁平 AND + 决策树 IfNode + 决策表行内复用同机制）** ⑦ 未分析 unanalyzable 灰名单。
- **算法**：`ConditionSpace`（区间/点集三态推理，几何=DMN Calvanese 2016 超矩形）+ 6 个 detector + `RuleSetAnalyzer` 编排；检查命名/语义对齐 **Drools Verifier**。**hit-policy-aware**：HIGHEST_PRIORITY/ALL_HITS/FIRST_HIT 各自语义与 `EvalEngine` 运行时"谁胜出"一致。**零误报**：任一维 UNKNOWN / 不可投影即降级，宁漏报不误报。
- **覆盖范围（精确推理仅这些）**：AST_BOOLEAN 顶层 AND-of-条件 + 决策表行内；**故意不做**：决策树**跨树**区间分析（整条进 unanalyzable）、评分卡（加权语义非合取）、表达式脚本（不透明）。**保守降级（漏报非误报）**：FIRST_HIT 等优先级 masking 因 tie-break(ruleVersionId) 分析期不可见而不报；DMN 全输入域"missing rule"完备性缺口推迟。
- **草稿优先**：分析取每条规则的 DRAFT 版本优先、否则 ACTIVE，反映"待发布编辑态"，支持发布前自查。
- **落点**：`rule-kernel` `internal/analysis`（算法）+ `api/analysis`（报告契约，纯 Java 无 Spring/Jackson）；`rule-config-svc` `RuleAnalysisService`（读快照编排，真 MySQL 集成测试）；`rule-api` `RuleAnalysisController`（`GET /admin/v1/scenes/{sceneCode}/analysis`）；前端**左栏摘要条（唯一入口，点击进抽屉）+ 抽屉分类展示 + 规则 badge**，非可分析 kind 隐藏入口。（注：本锚点原"接口衔接面"写"前端右栏展示"，实际落在左栏摘要条+抽屉，右栏保持节点属性不动——以此实装为准。）

### 2.27 决策效果闭环 / 规则有效性度量（来源 对照成熟决策平台分析 — 效果维度，FICO/Sapiens 规则绩效同类能力）

**效果闭环全链路已实装（B32 / 2026-06-19 标签回灌 + 聚合查询）：** ① `decision_outcome` 表 + 回灌 API（`OutcomeService.recordOutcomes` 幂等 upsert）+ `OUTCOME_INGESTION` 经 `SqlOutcomeSource` 自动拉标签；② `EffectivenessService` 按 RULE_VERSION/DECISION 维度聚合 TP/FP/FN → precision/recall + DAY/WEEK 时间分桶漂移 + fireRate；③ 前端 `EffectivenessPage`（tenant/scene/range/popularLabels/dimension/bucket 过滤 + 折线图 + 表格）。

- **v1 现状**：每次决策落 `evaluation_session`（D21）；可观测体系（§2.6 / §2.22）度量的是**系统级**指标（QPS / 延迟 / 错误率 / 命中率）。**无业务效果度量**——"判 HIGH 的交易事后是不是真欺诈"答不了，单条规则的查准/查全无从谈起。
- **触发条件**：风控/营销需要持续评估"规则抓得准不准"以迭代规则；监管/风控团队要求规则绩效可量化（误报率、漏报、随时间漂移）。系统级监控绿不代表业务有效。
- **演进方向（结果标签回灌 → 按规则聚合）**：
  - 新增**结果标签接入**：业务侧把决策的真实结局（fraud/not、转化/未转化）按 sessionId 或 eventId 回灌；
  - 按 **规则 / Decision 维度聚合** TP/FP/FN → precision / recall / 误报率，并按时间窗看**漂移**；
  - 衔接 §2.16 A/B 实验：对照组/实验组的效果差异度量；衔接 §2.25 陪跑：上线前预测 + 上线后实测对照。
- **接口衔接面**：新增 `decision_outcome` 表（sessionId/eventId 关联 + 标签 + 标签时刻）+ 标签回灌 API（`POST /admin/v1/decision-outcomes`）+ 效果聚合查询 API；聚合可走 §2.6 监控体系或独立报表。
- **迁移成本**：中（一张关联表 + 回灌 API + 聚合查询；标签延迟到达需按时间窗对账）。
- **边界纪律**：**标签语义（什么算欺诈/转化）是业务侧职责**，引擎只提供"标签接入位 + 按规则聚合"，不自定义业务判定——守住"引擎做决策不做业务判定"的线。
- **依赖与联动**：建在 `evaluation_session`（D21）之上；与 §2.25 what-if 陪跑强互补（陪跑答"换规则会怎样"，本锚点答"现规则准不准"）；模型节点落地后天然支持模型 vs 规则效果对比。

### 2.28 规则↔指标血缘与变更影响分析（来源 对照成熟决策平台分析 — 治理维度，数据血缘同类能力）

**已实装（B33 / 2026-06-18）：双向血缘 + 变更影响预检，沿用按需扫快照、零常驻索引。**

- **正向 metric→规则（早随 B6 实装）**：`MetricWriteService.findReferencingRules` + `GET /admin/v1/metrics/{code}/versions/{version}/impact`——按需扫该租户全部 ACTIVE `rule_version` 的 `metric_dependencies`，版本感知、读已提交 DB（强一致）。2026-06-18 补**版本无关批量计数** `GET /admin/v1/metrics/usage-counts`（一次扫聚合 `metricCode→引用规则数`，供列表徽标）。
- **反向 Decision→规则（2026-06-18 补齐）**：`DecisionService.findRulesProducingDecision` 扫 ACTIVE `rule_version` 的 `decision_bindings`，对称 `findReferencingRules` 范式（专用投影 `findActiveWithDecisionByRuleDefIds` 含 decision_bindings 列）；`GET /admin/v1/decisions/{code}/sources`（产出该 Decision 的规则，兼作下线影响预检）+ `GET /admin/v1/decisions/{code}`（详情）+ `GET /admin/v1/decisions/usage-counts`（批量计数）。
- **前端（复用 §2.26 B31 治理范式统一）**：「徽标 + 抽屉 + 可点定位卡片」统一 Decision/Metric 血缘呈现——列表「被 N 引用」徽标点开血缘抽屉、详情页「被引用规则」Tab（可下钻规则编辑器）、规则编辑器 metric/decision 旁挂反向血缘徽标；新增 **Decision 详情页**消除与 Metric 详情的信息架构不对称；**停用 Decision 前血缘拦截**（仍被 ACTIVE 规则产出则二次确认列出受影响规则）。
- **落点**：`rule-config-svc` `DecisionServiceImpl` / `MetricWriteServiceImpl`（按需扫，`@Transactional(readOnly=true)`）+ `rule-api` `DecisionController` / `MetricController`；前端 `components/lineage/`（抽屉/表格/hook）+ Decision/Metric 列表与详情 + 编辑器徽标。
- **与原设想偏差（重要）**：原"演进方向"拟建常驻 `LineageIndex`（从快照抽引用建索引、挂发布事件增量更新、复用 D17 热更）。实装前核实发现 metric→规则的按需扫 DB（`findReferencingRules`）**早已存在**，常驻索引对它是 over-engineering 且双轨重复；血缘是**冷路径**（治理查询，非评估热路径），按需扫强一致且足够。故**放弃 LineageIndex，沿用既有按需扫房规、只补反向方向 + 批量计数 + 前端统一**——无新增索引、零 DDL、不挂事件、不碰评估热路径。
- **已知缺口**：仅认发布期冻结的结构化引用——Decision 仅 `decision_bindings`、metric 仅 `metric_dependencies`；EXPRESSION_SCRIPT 脚本体内引用的 metric 不进 `metric_dependencies`，故脚本规则的 metric 血缘漏报（与脚本不透明一致）。
- **依赖与联动**：建在 D6 快照之上；与 §2.2 metric 版本化联动（版本变更的影响面）；与 §2.26 静态分析共享"读快照抽结构"的范式（但各自按需扫，未合并模块）。

---

### 2.29 场景内独立规则并行求值（来源 gengine 对照 — 见 [`reference-projects.md`](./reference-projects.md) §2.4）

**已实现（2026-07-23）。场景级 opt-in，默认 SEQUENTIAL 零影响。**

- **来源**：gengine 把"一个场景内多条独立规则并行求值"做成一等执行模型（Concurrent / DAG 层内并行）。本项目评估原为**同步串行**——`SceneRuleIndex` 命中的 N 条规则逐条 `execute`，由调研触发对照分析。
- **实现**：`ExecutionMode enum { SEQUENTIAL, PARALLEL }` + `ParallelEvaluator`（`Executors.newVirtualThreadPerTaskExecutor` fork/join，零新依赖）。模式与 `SceneExecutionStrategy` 正交（ALL_HITS 全量并行、FIRST_HIT 批式并行），汇聚逻辑复用 `evaluateAllCandidates` 语义。设计见 `openspec/changes/parallel-rule-execution/design.md`。
- **适用面窄（重要）**：metric 预拉已批量（D20），单条 AST_BOOLEAN 规则求值是纳秒级，虚拟线程创建+调度开销 ~µs。JMH benchmark 数据：
  - 纯 AST_BOOLEAN（20 条轻量规则）：PARALLEL 41,256ns vs SEQUENTIAL 992ns — **42x 负优化**
  - 混合场景（20 条中 10 条带模拟重计算 ≈ 脚本/Flow）：PARALLEL 75,194ns vs SEQUENTIAL 97,591ns — **1.30x 加速**（交叉点）
  - 全重（20 条全带计算负载）：PARALLEL 97,484ns vs SEQUENTIAL 192,486ns — **1.97x 加速**
  
  **仅在场景含多条重规则（`EXPRESSION_SCRIPT` 复杂脚本 / `DECISION_FLOW` 多阶段图）且候选数 ≥ 3 时，并行才可能带来真实收益**。运营看到 benchmark 数据后自行判断开不开——引擎不替运营做启发式阈值。
- **为何不进一步**：不做并行度/线程池参数暴露（JDK 虚拟线程无限并行已够）、不做节点级并行（ice P_AND 风格——D75 图层分支等价）、不做引擎侧自适应切换（启发式不可靠，opt-in 更透明）。

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

