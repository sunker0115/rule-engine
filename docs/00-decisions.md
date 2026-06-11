# 00 — 关键设计决策

> **位置定位**：README §二 列出了 30 条核心决策的精简表，本文件**展开每条的背景、选项、权衡、最终选择与 v1 落地范围**。
>
> **如何使用**：每条决策的"决定"行已落定，下方"v1 落地范围"/"v1 不做的"/"派生约束"是落地参考。新增决策追加 D22+ 即可，旧条目不再回填修改（变更走"新决策覆盖旧决策"+ README §七版本史登记）。

## 分组导读

| 分组 | 决策 | 关注点 |
|------|------|--------|
| **一、产品定位** | D1, D10, D60 | 引擎是什么、边界在哪、不做什么 |
| **二、核心数据模型** | D2, D3, D4, D5, D6, D11, D12, D13, D26, D27 | 概念边界、数据结构、核心协议 |
| **三、评估运行时与可靠性** | D14, D15, D16, D17, D20, D22, D23, D24, D25, D7, D8, D9, D18, D19, D21 | 引擎执行、热路径优化、发布运维、排障 |
| **四、精化与派生** | D28, D29, D30 | 主决策的细节推论、易踩坑处理 |

> 决策编号按历史追加顺序排列，阅读时建议按分组顺序（先一二、再三四、最后五）而非 D 编号顺序。

---

## 一、产品定位

## D1. 第一阶段场景定位 ⭐⭐⭐

**为什么重要**：场景决定性能目标、特性优先级、是否要引入流处理 / RETE / LLM 等重资产。

| 选项 | 说明 | 权衡 |
|------|------|------|
| ☐ A. 运营 / 营销 / 活动 | 中吞吐（1k QPS）、人工配置友好、可视化优先 | 覆盖最广、社区方案多；缺点是天花板低（高并发风控接不住） |
| ☐ B. 风控 / 反欺诈 | 高吞吐（10k+ QPS）、低延迟（<50ms）、行为序列匹配 | 技术含量高，但需要 CEP / 流处理 / 预编译，复杂度激增 |
| ☐ C. 通用平台（多场景） | 不预设场景，仅提供能力，业务方接入 | 灵活但设计复杂度最高，需要多租户/权限/SDK 等公民设施 |
| ☐ D. AI Agent 决策层 | 作为 LLM / Agent 的可控决策部分，条件可以是 LLM 判断 | 前沿但有较强不确定性，与传统规则引擎有质的差异 |

**推荐**：A（运营/营销/活动）作为第一阶段，向 C（通用平台）演进留好接口；明确不做 B 和 D，B/D 留到 v2/v3。

**你的决定**：A — v1 起步 A（运营/营销/活动），抽象按 B（风控）级别预留；演进优先级 B>A>C>D

---

## D2. 规则表达式语言 ⭐⭐⭐

**为什么重要**：决定整个架构起点。表达式是规则引擎的灵魂，选错了整个后端 + 前端 + 配置 UI 都跟着变。

| 选项 | 说明 | 权衡 |
|------|------|------|
| ☐ A. 自研 AST | JSON 序列化的 AST 树（`{op:"AND", children:[...]}`） | 可控、贴业务、前端可视化最友好；要自己实现类型系统、补全、错误提示 |
| ☐ B. CEL (Google) | `user.age >= 18 && trade.amount > 100` 这类字符串表达式 | 谷歌出品成熟、K8s/Envoy 在用、有 Java 实现；前端可视化难（表达式不是树） |
| ☐ C. JsonLogic / JSONata | JSON 原生（`{"and":[{">":["age",18]}, ...]}`） | 前端渲染最友好、多语言实现现成；类型能力弱，复杂逻辑可读性差 |
| ☐ D. 双轨（AST 内核 + 标准 DSL 输入输出） | AST 是唯一真相，CEL/JsonLogic 作为序列化格式可互转 | 最灵活；工作量最大、决策更复杂 |

**推荐**：A（自研 AST）。第一阶段聚焦运营营销，前端可视化是刚需，AST 直接对应 UI 树。等通用平台阶段再考虑双轨。

**你的决定**：A

---

## D3. 多租户模型 ⭐⭐⭐

**为什么重要**：影响 schema 设计、索引设计、API 契约。后期改是大动作。

| 选项 | 说明 | 权衡 |
|------|------|------|
| ☐ A. schema 起一等公民 | 所有表带 `tenant_id`，索引前缀含它，默认租户也是一个租户 | 后期拆 SaaS / 多业务线零重构；起步多一点字段 |
| ☐ B. 单租户起步，预留接口 | 表不带 tenant_id，但代码抽象层预留 | 起步快；后期改 schema 痛 |
| ☐ C. 不考虑多租户 | 明确只给一个业务线用 | 最简；如果业务发展超预期则要推倒重来 |

**推荐**：A。多租户成本在 schema 上极小（多一列 + 索引前缀），收益巨大。

**你的决定**：A

---

## D4. 动作协议 ⭐⭐

**为什么重要**：决定运营能不能自助配动作。

| 选项 | 说明 | 权衡 |
|------|------|------|
| ☐ A. 声明式优先 + SPI 兜底 | webhook / MQ.send / SQL.update / log 配置即开；复杂逻辑 Java SPI 实现 | 80% 场景零代码；声明式动作的 schema 设计要花心思 |
| ☐ B. Java SPI 为主 | 所有动作都是 Java 实现类 | 可控、类型安全；每加一种动作要发版 |
| ☐ C. 动作编排（BPMN / 工作流） | 动作可以顺序/并行/条件分支/延时等待 | 表达力最强；复杂度激增、配置 UI 难做 |

**推荐**：A。第一阶段。C 留到 v2 接入工作流引擎（如 Camunda / Flowable）。

**你的决定**：A

> 已被 D60 作废：动作协议是动作子系统的根决策；引擎纯决策化、动作子系统整体移除后，"声明式 vs SPI 动作"议题不复存在，编排（含 C 选项的工作流）交流程引擎（首选 Flowable）。

---

## D5. 触发模型 ⭐⭐

**为什么重要**：决定要不要引入流处理（Flink/Kafka Streams）。

| 选项 | 说明 | 权衡 |
|------|------|------|
| ☐ A. 单事件触发 | 一个事件进来评估一次规则，无状态 | 简单、覆盖 90% 营销/运营；不支持"5 分钟内 3 次"这类时间窗 |
| ☐ B. 单事件 + 简单聚合 | 单事件触发 + MetricSource 内部做 SQL 聚合 | 中等；用 SQL 聚合代替流处理，时间窗有限 |
| ☐ C. 引入 CEP（Flink CEP / Kafka Streams） | 真正的事件流处理 | 表达力最强、性能最好；引入重型依赖、运维成本高 |

**推荐**：B（单事件 + MetricSource 内 SQL 聚合覆盖 80% 时间窗场景），CEP 留到 v2。

**你的决定**：B

---

## D6. 版本与灰度 ⭐⭐

**为什么重要**：决定规则上线安全性，是否一等公民影响 schema。

| 选项 | 说明 | 权衡 |
|------|------|------|
| ☐ A. 一等公民 | 规则有版本号，发布即快照不可变；灰度按 % 放量、按用户标签命中 | 安全、可回滚、A/B 实验内置；schema 复杂度+++ |
| ☐ B. 版本化但不内置灰度 | 有版本号和回滚，灰度交给上游 ABTest 平台 | 折中；要和 ABTest 平台对接 |
| ☐ C. 不做版本 / 灰度 | 规则改了立即生效，错了就回滚配置 | 起步最快；事故风险大 |

**推荐**：A。但灰度命中算法可以先内置简单 hash % bucket，复杂分桶留到接 ABTest 平台。

**你的决定**：A

**v1.5 已修**：`RolloutPreGate` 支持 `gateParams.experimentId` 可选字段。存在时以 `hash(subjectId:experimentId)` 作种子，同实验内多规则版本共享分桶（A/B 互斥）；不存在时行为与 v1 完全一致（向后兼容，无 DDL 变更）。

---

## D7. Dry-run 试算 ⭐⭐

**为什么重要**：直接影响运营 / 产品的配置体验，定不定一等公民差很多。

| 选项 | 说明 | 权衡 |
|------|------|------|
| ☐ A. 一等公民 | 走完整评估链路，仅 ActionHandler 短路；前端有专门试算面板 | 体验最好；ActionHandler 需要全部支持 dryRun 标志 |
| ☐ B. 仅评估层试算 | 用户构造 mockEvent → 返回 AST 节点级 trace，不动 Action | 起步快；不能验证动作输出（如发什么短信文案） |
| ☐ C. 不做试算 | 上线即真实 | 简单；运营会很痛苦 |

**推荐**：A。但 v1 可以先做 B（评估层试算 + trace），动作层 dryRun 在 v1.5 补。

**你的决定**：A

**v1 落地范围**：
- 评估层 dry-run 一等公民：走完整评估链路（Matcher / Pre-Gate / Context / AST），节点 trace 落 `dry_run_session` 表（与 `evaluation_session` 隔离）；
- `ActionHandler` 接口已在签名内预留 `dryRun(ActionContext ctx)` 入口（实现签名见 04-extension §三）；
- **v1 未实装 handler 的兜底契约**：调用 `dryRun` 但 handler 未补齐时，由 Dispatcher 短路返回 `ActionResult { status=SKIPPED, errorCode=DRY_RUN_NOT_IMPLEMENTED }`——不抛异常、不阻塞试算面板渲染；
- v1.5 全量补齐后该 `errorCode` 不再产生（完整 `ActionResult.errorCode` 枚举见 [`01-concepts.md`](./01-concepts.md) §3.7）。

**v1 实装状态（已完成）**：`DryRunTraceWriter` SPI（`rule-kernel`）+ `DryRunTraceWriterDbImpl`（`rule-observability`，异步批写 `dry_run_node_trace` 表）+ `EvalServiceImpl` 按 `isDryRun` 路由到独立 SPI；stub `ActionHandler`（`BlockTransactionHandler` / `SendAlertHandler`）+ `ActionDispatchService` 同步派发并落库 `action_execution`（干跑不派发）。详见 [`docs/superpowers/plans/2026-06-03-eval-chain-completion.md`](./superpowers/plans/2026-06-03-eval-chain-completion.md)。v1.5 已实装：`BlockTransactionHandler.dryRun()` + `SendAlertHandler.dryRun()` 均 override 返回 `ActionResult.success()`；`DRY_RUN_NOT_IMPLEMENTED` errorCode 不再产生。

---

## D8. 性能目标 ⭐⭐

**为什么重要**：决定要不要引入 RETE / 预编译 / 索引化匹配。

| 选项 | 量级 | 实现 |
|------|------|------|
| ☐ A. 千级 QPS / <500ms | 规则数 100-1000 / 用户单事件 | 朴素遍历匹配 + Spring 注入即可 |
| ☐ B. 万级 QPS / <100ms | 规则数 1k-10k | 需要规则索引化 + 预编译 AST + 指标缓存 |
| ☐ C. 十万级 QPS / <10ms | 规则数 10k+ | RETE / 预编译字节码 / 全内存 / 分片 |

**推荐**：A。如果场景定位是运营/营销，A 完全够。B/C 是风控才需要。

**你的决定**：B A — 按优先级设计，后续可扩展

---

## D9. 持久化分层 ⭐

**为什么重要**：执行日志量级远大于规则定义，分库与否影响查询性能和运维。

| 选项 | 说明 | 权衡 |
|------|------|------|
| ☐ A. 全 RDBMS（MySQL） | 定义 + 执行日志 + trace 都在 MySQL | 最简单、运维一致；日志量大时查询慢、表膨胀 |
| ☐ B. 分层（定义 RDBMS + 日志列存/ES） | 定义 MySQL，执行日志走 ClickHouse / ES | 查询性能好、容量大；引入新组件、运维复杂 |
| ☐ C. 分层（定义 RDBMS + 日志冷热分级） | MySQL + 7 天热表 + 归档冷表 | 折中；归档逻辑要自己写 |

**推荐**：A 起步（明确数据保留 30 天），观测到瓶颈后切 B 或 C。

**你的决定**：A

---

## 二、核心数据模型

## D10. AI 评估节点 ⭐

**为什么重要**：决定是否预留 LLM 作为 Condition 的接口。

| 选项 | 说明 | 权衡 |
|------|------|------|
| ☐ A. 不考虑 | 引擎与 AI 解耦 | 最简单；如果未来要接入 LLM 要重新设计 |
| ☐ B. 预留接口 | `LLMConditionEvaluator` 作为一种 ConditionEvaluator 类型，v1 不实现 | 几乎零成本预留；不影响第一阶段 |
| ☐ C. 一等公民 | 内置 LLM 评估、prompt 模板、结果缓存 | 前沿；当前 LLM 不确定性大，不适合做核心决策 |

**推荐**：B。`ConditionEvaluator` 接口本就是开放的，预留接口几乎不要成本。

**你的决定**：B

---

## D11. Job 模式 + 调度器选型 ⭐⭐

**为什么重要**：除"事件驱动"外，业务还存在"定时扫表 / 周期回查"类规则需求（如"每日 00:30 给昨天有交易的用户结算积分"）。是否纳入一等公民、用什么调度器，决定了 schema、运维形态和后续扩展性。

**核心定位**：Job **不是独立的第四概念**，而是 Trigger 适配器 —— 调度器到点后查询主体集合，批量合成 `RuleEvent` 注入标准评估链路，下游 Matcher / Rule / Action 完全复用。

| 选项 | 说明 | 权衡 |
|------|------|------|
| ☐ A. Spring Scheduling + Redisson 锁 | 自己写 `@Scheduled` + 分布式锁；轻量 | 无管理后台、无失败重试、无 HA 调度面板，运维裸奔 |
| ☐ B. xxl-job | 业界成熟、有调度中心 UI、HA / 失败重试 / 路由策略齐全；Java 生态首选 | 多一个调度中心依赖；与业务耦合需通过 `JobHandler` 适配层隔离 |
| ☐ C. Quartz 集群 | 老牌、功能全；可纯库内嵌 | 调度元数据落业务库、UI 弱、运维体验差 |
| ☐ D. 云厂商托管（EventBridge / 阿里云定时） | 零运维 | 厂商绑定、跨云成本、本地开发不友好 |

**推荐**：**B + Scheduler 接口化预留替换**。
- 选 B：xxl-job 作为首个实现，对接 admin 控制台 + 执行器集群，满足生产可观测和 HA。
- 接口化：内部抽象 `Scheduler` 接口（注册 / 取消 / 触发 / 查询状态），`XxlJobScheduler` 仅为一种实现；未来若切换 Quartz / 云调度仅替换 Adapter，不动业务。
- Job 仅对 `dominantMode ∈ {PUSH, HYBRID}` 的 Scene 开放；PULL Scene 无意义（业务方主动调用就不需要定时触发）。
- `eventId = hash(jobRunId + subjectId)` 确保 Job 批量合成事件天然幂等，与 `record_no` 模式一致。

**你的决定**：B + Scheduler 接口化

---

## D12. Rule.kind 多态 + 输出类型预留 ⭐⭐

**为什么重要**：v1 的 Rule 是"AST 布尔判定 + Action"模型，但业务侧已经能预见的未来形态有 6 类——评分卡、决策树、决策表、决策流、决策集、表达式/脚本。其中**评分卡输出数值、决策树输出分类**，与"布尔满足"不兼容；如果 v1 不在 Rule schema 上预留 `kind` 字段和多态输出契约，后期引入会被迫加新表 + 改 EvalResult 接口，影响面巨大。

**6 类形态的支持评估**：

| 形态 | v1 模型 | 演进策略 |
|------|---------|---------|
| 评分卡（Scorecard） | 不支持（AST 是 boolean） | `Rule.kind = SCORECARD` + `ConditionNode.weight` + `EvalResult.score` |
| 决策树（Decision Tree） | 部分（多 Rule 优先级模拟） | `Rule.kind = DECISION_TREE` + `EvalResult.category` |
| 决策表（Decision Table） | 部分（拆多 Rule） | `Rule.kind = DECISION_TABLE`，内部 JSON 是矩阵 |
| 决策流（Decision Flow） | D4 已预留扩展点（v2 接 Camunda） | 不在 Rule 层级，是 Action 编排层 |
| 决策集（Decision Set） | 天然支持 | 增 `Scene.executionStrategy`（`ALL_HITS` / `FIRST_HIT` / `HIGHEST_PRIORITY`） |
| 表达式/脚本 | 支持 | 增 `expression.cel` / `expression.aviator` 类型的 ConditionEvaluator |

**v1 必须落地的 3 个 schema 占位**：

| 字段 | 位置 | v1 行为 | 引入版本 |
|------|------|--------|---------|
| `Rule.kind` | `rule_definition` 表新增列，枚举 `AST_BOOLEAN` / `SCORECARD` / `DECISION_TREE` / `DECISION_TABLE` / `EXPRESSION_SCRIPT` | v1 仅写 `AST_BOOLEAN`，其他枚举值发布拒绝 | v1（占位） |
| `EvalResult.output`（概念预留名） | 评估结果多态扩展位：`{score?, category?, decision?}`，v1 只填 `satisfied`，其余 null；落地时为 `EvalResult` 顶级字段而非独立 `output` 包装对象（见 `01-concepts.md §3.4`） | v1 只填 `satisfied`，其余字段 null | v1（接口） |
| `ConditionNode.weight` | AST JSON 节点可选字段 | v1 评估器忽略；SCORECARD kind 启用 | v1（JSON 字段） |

**为什么这 3 个占位现在做、后面不痛**：
- `Rule.kind`：MySQL 加列零成本；不加，未来要么新建 `scorecard_definition` 表（数据散布）、要么 alter table（停服或慢 DDL）；
- `EvalResult.output`：接口现在多态设计，调用方拿到的对象 shape 稳定；v1.5 引入 `score` 字段时无需改 PULL API 签名；
- `ConditionNode.weight`：AST 存的是 JSON，加字段就是加 key，零迁移；但需要在 §3.5 sealed `RuleNode` 文档锁定 schema，避免不同实现各自加字段污染。

**v1 不做的**：
- 不实现 SCORECARD / DECISION_TREE / DECISION_TABLE 的 evaluator —— 留到 v1.5 / v2，按业务实际需求驱动；
- 不实现 `executionStrategy` —— 决策集策略留到 08-evolution 路线图，v1 默认 `HIGHEST_PRIORITY`（D29 落定，覆盖本条原写的 `ALL_HITS`）；
- 不实现脚本沙箱 —— 表达式 evaluator 留到 v1.5。

**后续实装补记**：`SCORECARD` evaluator 已实装（D12 决策时）；`DECISION_TREE` / `DECISION_TABLE` evaluator 已实装（D42）；`ALL_HITS` / `FIRST_HIT` executionStrategy 已实装（D41）；`EXPRESSION_SCRIPT` 仍未实装。

**你的决定**：A（按上述 3 个占位落地）

---

## D13. Scene 元数据 schema（参数化配置） ⭐⭐

**为什么重要**：Scene 是 metric / action 白名单 + 数据源初始化锚点（D11 派生），但还缺**业务参数 schema**——下游已多处依赖却没具体落点：

- §3.3 RuleEvent 关键边界写"payload 实际可消费字段由 Scene 的元数据约束"，但元数据是哪个字段没定义；
- §五 Q9 写"扩展字段必须通过 metric 或 subject 扩展属性进入"，但 subject 是什么实体没定义；
- D7 dry-run 要构造 mockEvent，没 schema 前端给不出字段补全；
- 发布校验需要"规则引用的字段必须在 Scene 允许范围内"，没 schema 这条校验做不了。

**两类参数，本决策只覆盖第一类**：

| 类别 | 归属 | 决策位置 |
|------|------|---------|
| **A. Scene 自身的元数据 schema** | Scene 一次性配置，跨规则共享 | **本决策（D13）** |
| **B. ConditionType / ActionType / MetricType 的 `params` JSON schema** | 类型注册时附带，跨 Scene 复用 | 留到 [`04-extension.md`](./04-extension.md) 展开 |

**Scene 上要落的 4 个字段**：

| 字段 | 用途 | 前端展示 | 后端使用 |
|------|------|---------|---------|
| `payloadSchema` | RuleEvent.payload 允许的字段 + 类型 + 取值约束（JSON Schema 或简化 spec） | 配规则时变量下拉补全 / dry-run mockEvent 表单字段 | 规则发布校验：引用字段必须在 schema 内；事件接入校验：payload 不匹配拒收 |
| `subjectType` | 业务主体类型枚举：`USER` / `ACCOUNT` / `DEVICE` / `ORDER` / `CUSTOM` | 配规则时 subject.* 变量下拉 | Context 构建时知道从哪张主体表取属性 |
| `defaultParams` | Scene 级缺省：`timezone` / `currency` / `defaultRateLimit` / `defaultCacheTtl` 等 | 配规则时显示"继承自 Scene"占位 | 评估器读到规则不显式配的参数时回落到 Scene 默认 |
| `eventTypes` | 该 Scene 允许的 eventType 白名单 | 配规则 trigger 下拉过滤 / Job eventTypeTemplate 校验 | 事件接入按 (scene + eventType) 二元组校验，不在白名单拒收 |

**字段必要性区分**：
- `payloadSchema` + `eventTypes` —— **必须**（发布校验已在依赖，没了走不通）；
- `subjectType` + `defaultParams` —— **应该有**（不加就要在多处重复处理 / 硬编码默认）。

**为什么 v1 就要落，不能延后**：
- Scene 是表结构里的一行，加 4 个 JSON 列零成本；后期补则要 alter table + 数据迁移；
- v1 不实现 schema 的复杂校验也没关系——字段先占位，校验逻辑从"宽松"逐步收紧到"严格"。

**v1 不做的**：
- 不实现 `payloadSchema` 的完整 JSON Schema 子集（v1 支持字段名 + 基础类型 + required 即可，复杂 oneOf / $ref 留到 v2）；
- 不做 schema 演进（payloadSchema 升版本时如何兼容存量规则），留到 [`08-evolution.md`](./08-evolution.md) 路线图；
- `defaultParams` 的 key 集合 v1 仅约定 `timezone` / `currency`，其他 key 由业务方按需扩展，引擎不强加 schema。

**你的决定**：A（4 字段全落，v1 仅 payloadSchema + eventTypes 启用校验，subjectType 仅 USER 实装，defaultParams 仅 timezone/currency 起步）

---

## 三、评估运行时与可靠性

## D14. 权限与审计 ⭐⭐⭐

**为什么重要**：当前 13 条决策没有一字关于"谁能配 / 谁能发布 / 谁能看 trace"以及"操作审计"（与 evaluation 执行 trace 不是一回事——后者记录系统行为，前者记录人的行为）。一旦后期补，影响所有持久化对象的 schema。

**两类审计要区分**：
- **执行审计** = `evaluation_session` + `node_trace` + `action_execution`（已在 D7 / D6 / D4 覆盖）
- **操作审计** = 谁在何时改了什么规则 / Scene / 白名单（**本决策**）

| 选项 | 说明 | 权衡 |
|------|------|------|
| ☐ A. 占位字段 + 审计表 | 核心表加 `created_by / updated_by / published_by` VARCHAR；新增 `audit_log` 表；鉴权交给上游网关（JWT/SSO），引擎从 token 解析 actor | 公司身份系统统一管，引擎不抢饭碗；占位 0 成本 |
| ☐ B. 轻量 RBAC 内置 | 引入 user/role/permission 三表 + 预定义角色（viewer/editor/publisher/admin）+ Rule/Scene 上挂权限边界 | 开箱即用，与公司既有 SSO 重复造轮子 |
| ☐ C. 完全延后 | 连占位字段都不留 | 后期 alter table 痛 |

**决定**：**A**（已确认）

**v1 落地范围**：
- **审计字段**（所有核心表）：`created_by` / `created_at` / `updated_by` / `updated_at`；Rule 额外加 `published_by` / `published_at`。详细落点（横切原则、各表字段表如何省略横切字段）见 [`01-concepts.md`](./01-concepts.md) §三 顶部横切说明；
- **审计表 `audit_log`**：`tenant_id` / `actor` / `target_type`（RULE / SCENE / METRIC_BINDING / ACTION_BINDING / JOB / ...）/ `target_id` / `action`（CREATE / UPDATE / PUBLISH / PUBLISH_FAILED / ENABLE / DISABLE / DELETE）/ `before_snapshot` JSON / `after_snapshot` JSON / `operated_at` / `trace_id`；
- **actor 来源**：上游网关在请求头注入 `X-Actor-Id` / `X-Actor-Type`（USER / SYSTEM / JOB），引擎不验签——验签是网关职责；
- **跨租户管理员**：通过特殊 `tenant_id = "__platform__"` 的 actor 实现，业务约定，不入 schema。

**v1 不做的**：
- 用户表 / 角色表 / 权限表（交给公司身份系统）；
- 字段级 / 行级权限控制；
- 审计日志的篡改防护（hash chain / WORM 存储），留到合规阶段；
- **敏感数据加密 / 脱敏**：v1 不做。Context 里的 subject / payload、`evaluation_session` 快照、`audit_log` 的 before/after 快照**按原值落库**；GDPR / PII / 个人信息保护类合规要求由调用方在 payload 进入引擎前完成（脱敏 / 加密 / token 化）。后期接合规阶段时统一接入字段级加密 + 审计 hash chain，详见 [`08-evolution.md`](./08-evolution.md) §2.8 合规演进。

---

## D15. 评估失败语义 ⭐⭐⭐

**为什么重要**：MetricSource 超时、ConditionEvaluator 抛异常、AST 求值中断时怎么办——影响 `EvalResult` 契约（决定要不要加 error 字段）和 PULL API 签名。不现在表态，业务方各自定义"失败=true 还是 false"，规则间行为不一致。

| 选项 | 说明 | 权衡 |
|------|------|------|
| ☐ A. 默认整条规则降级 false + EvalResult.error 字段 + 节点 trace 标记 ERROR + 单独 ERROR 桶对账 | 保守语义，调用方明确可知，灰度对账可隔离 | 风控场景"宁错杀勿放过"需调用方显式判断 errorCode |
| ☐ B. 默认抛异常，调用方决策 | 最朴素，但 PUSH 模式必须 catch；规则间互相影响 | 协议不一致：PUSH 必须降级，PULL 抛是合理的 |
| ☐ C. 按 conditionType 可配 | 各评估器自带"失败时认为满足/不满足/抛异常"策略 | 最灵活，最难治理；不同规则行为不一致 |

**决定**：**A**

**v1 语义规范**：
- **失败粒度**：单个 `ConditionNode` 评估失败 → 该节点 satisfied=false，**整树继续按 AND/OR/NOT 短路语义求值**（其他节点正常评估）；
- **失败传播**：整树根节点求值结束后，若有任一节点失败 → `EvalResult.errorCode` 非空，`satisfied` 仍按布尔逻辑给真值（不强制改为 false，由 AND/OR 自然短路决定）；
- **规则间隔离**：单条 Rule 评估失败**不影响**同 (scene + eventType) 下其他 Rule；引擎逐条 try/catch；
- **EvalResult 多态字段扩展**（D12 已预留 output 多态，此处加 error 槽位）：

```
EvalResult {
    satisfied:       boolean
    score? category? decision?         // D12 各 kind 多态
    trace:           List<NodeTrace>
    errorCode?:      String            // METRIC_FETCH_FAIL / CONDITION_EVAL_ERROR（见 10-api-contract §七；PAYLOAD_SCHEMA_MISMATCH 是入口层 400，不进入评估链路）
    errorMessage?:   String
    failedNodeIds?:  List<String>      // 哪些 AST 节点失败
    partial?:        Boolean           // true=部分成功，false=完全失败
}
```

- **PUSH 模式**：默认安静失败，Action 不派发（满足且无 error 才派发）；trace 落 ERROR 状态，触发监控告警；
- **PULL 模式**：返回 EvalResult，`satisfied + errorCode` 同时给出，调用方策略：
  - 风控类业务约定"`errorCode` 非空时按拦截处理"（fail-secure）；
  - 营销类业务约定"`errorCode` 非空时按放行处理"（fail-open）；
  - 引擎不替调用方决策；
- **灰度对账**：评估结果分**四类**统计——`HIT`（AST 求值满足）/ `MISS`（通过 Pre-Gate 但 AST 求值不满足）/ `BLOCKED`（Pre-Gate 拦截，未进入 AST）/ `ERROR`（AST 评估异常）；`BLOCKED` 和 `ERROR` 均不计入命中率分母；`evaluation_session.blocked_by` 字段（`ROLLOUT / WHITELIST / BLACKLIST / RATE_LIMIT / MUTEX`）记录拦截 Gate 类型，可按类型下钻分析。

**v1 不做的**：
- 不做评估失败的自动重试（Action 重试已有，评估失败重试语义不清晰：重试可能加重 MetricSource 压力）；
- 不做"部分降级"（如 metric 取不到时用默认值继续）——这是 conditionType 的事，由各 evaluator 内部决定。

---

## D16. 链式触发与事件环 ⭐⭐

**为什么重要**：Action 能不能产生新事件注入引擎？目前协议没禁也没允许——v2 想加要改 `ActionHandler` 接口签名 + 所有现存 Handler。

| 选项 | 说明 | 权衡 |
|------|------|------|
| ☐ A. 显式禁止 | ActionHandler 接口不返回新事件；业务需要链式走外部 MQ + 上游重推 RuleEvent | 协议清晰，引擎无环；运营营销场景链式需求少；引擎边界小好维护 |
| ☐ B. 允许 + 限制 | ActionHandler 可返回 List<RuleEvent>，引擎按 maxDepth=3 注入；环检测基于 originEventId 链；灰度桶继承 | 灵活；但环检测/深度限制/桶继承/traceId 串联都复杂 |
| ☐ C. 模糊化 | 协议不表态，v2 再决 | v2 决定允许时所有 Handler 都要改 |

**决定**：**A**（已确认）

**v1 协议规范**：
- `ActionHandler.execute(ActionContext ctx)` 返回 `ActionResult { status, errorCode?, errorMessage?, retryable }`，**不返回 List<RuleEvent>**；
- ActionHandler **可以**调用外部 MQ / HTTP（这是 Action 的本职），但上游若要把这条 MQ 消息再翻译成 RuleEvent 推回引擎，是**业务方主动行为**，引擎不感知；
- `RuleEvent.source` 字段记录来源（`HTTP` / `MQ` / `JOB` / `SDK` / `REPLAY`），**不**记录"链式"标识；
- 由此推论：环检测、深度限制、子事件灰度桶继承都不存在——业务方要自己防环（如外部链路加 hop 计数）。

**v1 不做的**：
- 不做内置链式触发；
- 不做"Action 触发 → 等待结果 → 触发下一动作"的工作流编排（D4 已说明 v2 接 Camunda）。

> 已被 D60 作废：动作子系统整体移除，链式触发议题不复存在（引擎纯决策化，编排归流程引擎）。

---

## D17. 配置变更下发与运行时一致性 ⭐⭐

**为什么重要**：规则发布后多久生效、怎么生效、多实例间一致性如何——D6 只说了"版本快照不可变"，没说"切版本"如何同步到运行时。

| 选项 | 说明 | 权衡 |
|------|------|------|
| ☐ A. DB 短轮询 + 内存缓存（15s 默认） | 应用每 15s 拉变更（按 `updated_at` 增量），更新内存中的规则索引 | 简单可靠，依赖少；变更延迟最大 15s |
| ☐ B. MQ 推变更事件（Redis Pub/Sub 或 Kafka） | 发布即推，运行时订阅刷新 | 实时性好；依赖额外组件，重启时要全量补 |
| ☐ C. 配置中心（Nacos / Apollo） | 把规则当配置文件，由配置中心推 | 利用既有基础设施；规则量大时配置中心吃力 |

**决定**：**A + `RuleVersionWatcher` 接口预留可换**

**v1 落地范围**：
- **轮询粒度**：默认 15 秒，可配置 `engine.rule.matcher.cache-refresh-interval-seconds`；
- **增量查询**：按 `(tenant, scene, updated_at)` 三元组拉变更（不全表扫）；
- **运行时缓存**：内存中维护 `(scene, eventType) → List<RuleVersionSnapshot>` 倒排索引；
- **评估快照锁定**：每次 evaluation_session 开始时拍一份"当前候选规则版本快照"，整个 session 用同一快照——即使评估期间发生切版本，本次评估不受影响（D6 派生）；
- **接口预留**：`RuleVersionWatcher` 接口（`subscribe(callback) / pull(since) / status()`），`DbPollingRuleWatcher` 为 v1 实现；v2 可换 `MqRuleWatcher` / `NacosRuleWatcher`，业务侧零改动。
- **DISABLED 状态从倒排索引剔除**（D19 派生）：`PUBLISHED → DISABLED` 切换后，下次轮询周期内将该 RuleVersion 从内存 `(scene, eventType) → List<RuleVersionSnapshot>` 倒排索引中剔除；`DISABLED → PUBLISHED` 切换则按同一窗口重新入索引。`current_version` 指针在两态切换中**不变**（D19），索引剔除 / 回填只动运行时视图，不动 `rule_version` 表内容。生效窗口与 15s 最终一致语义对齐。

**多实例一致性**：
- **最终一致**，毫秒到 15 秒级窗口；同一用户在不同实例可能短暂拿到不同版本结果；
- **单 session 强一致 + 跨 session 最终一致是 by design**：单次 `evaluation_session` 内拍快照保版本一致（详见上文"评估快照锁定"），跨 session / 跨实例不保证强一致；新发布的规则版本在 ≤15s 内全实例收敛。这是 v1 接受的取舍——以 15s 窗口换取热路径零依赖（无需 MQ / 配置中心 / 双阶段提交）；
- **灰度桶稳定性**：bucket 算法基于 `(subjectId, ruleVersionId)` hash，**不**基于实例 ID——同一主体（subjectType 由 Scene 决定，v1 仅 USER）同一版本永远落同一桶；切版本时桶可能漂移（这是 D6 的固有语义，不是 D17 的新问题）；
- **强一致需求**（如金融拦截规则必须实例同步切版本）：v2 引入 MQ 推 + 双阶段提交时再考虑，v1 不支持。

**v1 不做的**：
- 不做主动 push（业务方调发布接口立即推到所有实例）——15s 窗口足够运营营销场景；
- 不做规则变更的"金丝雀实例"（一台实例先切，验证后全量切），留到 07-operability。

**Spring Modulith 单服务模式补充**（与 09-skeleton 模块拆分对齐）：
- v1 以 Spring Modulith 单服务部署时，`rule-config-svc` 模块在规则发布成功后通过 Spring `ApplicationEventPublisher` 发出 `RulePublishedEvent`；`rule-eval-svc` 模块以 `@ApplicationModuleListener` 订阅，立即触发倒排索引刷新——不再依赖 15s 轮询，变更在同一进程内近实时生效。
- `DbPollingRuleWatcher` 保留为 `RuleVersionWatcher` SPI 实现，但**仅供嵌入式 SDK 模式使用**（§2.14）：SDK jar 嵌入调用方进程，无共享 Spring 容器，无法收 Modulith 事件，只能轮询或接 MQ（`MqRuleWatcher`）。
- `SpringEventRuleVersionWatcher` 作为单服务模式的 v1 实现，由 `rule-eval-svc` 模块内部持有，不对外暴露为可替换 SPI（Spring 事件是框架内部机制，替换方向是切到 MQ 而非换 Watcher 实现）。
- "15s 最终一致"语义仍适用于 SDK 模式；单服务模式下一致性窗口降至毫秒级（同进程事件回调）。

---

## D18. Action 失败补偿语义 ⭐⭐

**为什么重要**：D15 规定了"评估失败"语义，但**单 Rule 内多 Action 的失败传播**是平级空白。一条 Rule 配 3 个 Action，第 2 个失败时第 3 个跑不跑？失败 Action 走重试还是补偿？不在 v1 表态，每个 ActionHandler 写法不一致，重试 / 监控 / 对账都对不上账。

| 选项 | 说明 | 权衡 |
|------|------|------|
| ☐ A. fail-fast（一条失败即停） | 任何 Action 失败 → 后续 Action 跳过；标记 SKIPPED | 简单；但单点失败放大影响面，发券失败导致打标也不做 |
| ☐ B. continue-on-error（默认隔离，可配 failFast） | 默认每个 Action 独立事务 / 独立重试 / 互不影响；Action 配置可声明 `failFast=true` 让后续 Action 跳过 | 默认隔离与 §3.7 "每个 Action 独立事务" 一致；个别强依赖场景仍可声明 failFast |
| ☐ C. per-Action 策略 + Saga | 每个 Action 配 onFailure: skip / retry / compensate / abort | 灵活但复杂；v1 用不到 |

**决定**：**B**（默认 continue-on-error；Action 级可声明 `failFast`）

**v1 语义规范**：
- **执行顺序**：同 Decision 内 Action 按 `sortOrder` 顺序串行执行（v1 不并行，D4 已说明 v2 接工作流引擎才考虑编排；D27 迁移后 Action 归属 Decision）；
- **单 Action 失败定义**：`ActionHandler.execute` 返回 `ActionResult.status = FAILED` 或抛出未捕获异常；引擎将异常转为 `ActionResult { status: FAILED, errorCode: HANDLER_EXCEPTION, retryable: false }`；
- **重试**：`retryable=true` 的失败入重试队列（独立调度，不阻塞同 Decision 内后续 Action）；`retryable=false` 直接落 `action_execution.status = FAILED`；
- **隔离默认**：除非显式 `Action.failFast = true`，单条 Action 失败 / 跳过 / 重试中 → 同 Decision 内后续 Action **继续正常执行**；
- **failFast 语义**：`failFast=true` 的 Action 失败后，**同一 Decision** 内 `sortOrder` 大于本 Action 的后续 Action 全部标记 `status=SKIPPED, errorCode=PREDECESSOR_FAILED`，不进入重试队列（D27 迁移后语义，Action 归属 Decision）；
- **Rule 状态独立**：单 Action 失败 **不影响** Rule 的 `EvalResult.satisfied`（评估已完成才会派发 Action，Action 是命中后行为）；
- **跨 Rule 隔离**：与 D15 一致——同 (scene + eventType) 下其他 Rule 的 Action 不受影响；
- **补偿**：`compensateActionType` **不自动触发**——补偿是 D4 的补偿流水线职责，由外部调度（如对账任务、手动回滚按钮）发起 `compensate(ActionContext ctx)` 调用；引擎不在 Action 失败时自动跑补偿；
- **对账三态**：与 D15 对齐，`action_execution` 按 `SUCCESS / FAILED / SKIPPED` 三类统计，SKIPPED 不计入失败率分母。

**v1 不做的**：
- 不做并行 Action / 编排（留到 v2 接工作流引擎）；
- 不做"Action 失败自动触发补偿"（补偿流水线由调用方按业务策略主动发起）；
- 不做 Saga 风格的全局事务回滚（动作语义本身就不是事务，是事件序列）。

> 已被 D60 作废：动作派发整体移除，多 Action 失败传播 / failFast / 补偿语义不复存在（引擎纯决策化）。

---

## D19. 规则发布事务性与回滚 ⭐⭐

**为什么重要**：D6 规定了"发布即版本快照不可变"，但**发布过程本身**的事务边界和失败回滚没说。一次发布要写 RuleDefinition 状态 + 新 version 记录 + audit_log，中间任一步骤失败状态机怎么走？多条规则批量发布要不要原子？不表态会出现 DB 残留 PUBLISHING 状态 / 版本号空洞 / 审计与状态不一致。

| 选项 | 说明 | 权衡 |
|------|------|------|
| ☐ A. 单条规则原子发布 + 批量逐条进度 | 单条发布在一个 DB 事务内完成（状态机迁移 + 新 version 行 + audit_log），失败落 `PUBLISH_FAILED` 待人工确认（详见下文 v1 落地范围）；批量发布前端拆成逐条请求，失败逐条暴露 | 简单可靠；批量进度 UI 易做 |
| ☐ B. 批量原子发布 | 一次 API 调用内多规则同事务发布 | 跨规则原子；事务大、锁范围广、长事务风险；批量超过 N 条时 DB 压力大 |
| ☐ C. 无事务保证 | 状态机分步推进，失败由人工修复 | 最简单实现；事故频发 |

**决定**：**A**（单条规则原子发布；批量由前端逐条提交）

**状态机摘要**（详见下方 v1 状态机扩展 + v1 落地范围）：

```
DRAFT ──发布──▶ PUBLISHING ──事务成功──▶ PUBLISHED
                  │                        │  │
                  │                        │  └──ENABLE/DISABLE──▶ DISABLED
                  │                        └◀──ENABLE────────────┘
                  └──事务失败/僵尸清扫──▶ PUBLISH_FAILED ──UI 显式确认──▶ DRAFT
```

**v1 状态机扩展**：
- `RuleDefinition.status` 枚举：`DRAFT` → `PUBLISHING` → `PUBLISHED` / `PUBLISH_FAILED`；新增 `DISABLED`（独立分支，与 `PUBLISHED` 之间双向切换，详见下文 v1 落地范围"DISABLED 与发布解耦"条目）；
- `PUBLISHING` 是**瞬时状态**：进入与退出在同一事务内，正常路径上不会被外部观察到；异常 crash / 超时残留时由后台清扫任务（默认 5min 扫一次）将超过 60s 的 `PUBLISHING` 重置为 `PUBLISH_FAILED`；
- `PUBLISH_FAILED` 是**待人工确认状态**：草稿数据仍在（事务回滚不动草稿内容），但状态不自动回 `DRAFT`——避免运营误以为"上次失败已自动恢复"。用户从 UI 点"重新编辑"显式确认后，状态由 `PUBLISH_FAILED → DRAFT`（单独审计一条 `action=UPDATE`），才能再次发起发布。

**v1 落地范围**：
- **发布事务**（单条规则）单次 DB 事务内完成：
  1. `rule_definition` 行：`status = DRAFT → PUBLISHING`（CAS 防并发发布）
  2. `rule_version` 表插入新版本快照行（`version` 单调递增，含完整 AST + decision_bindings + preGates + rollout 不可变冻结；`actions` 字段已由 D27 迁移到 Decision，以 `decision_bindings` JSON 快照形式存储）
  3. `rule_definition` 行：`status = PUBLISHED`、`current_version = N`、`published_by` / `published_at` 填充
  4. `audit_log` 表插入一条 `action=PUBLISH` 记录（before/after 快照）
  5. 事务提交
- **失败回滚**：任一步抛异常 → 整发布事务回滚（若已进入 PUBLISHING 则状态先自动还原 DRAFT）→ 应用层捕获异常后**另起一个短事务**：将 `rule_definition.status` 从 DRAFT 写为 `PUBLISH_FAILED`，并写一条 `audit_log` 记录 `action=PUBLISH_FAILED`（含 errorCode / stackTrace 摘要）；草稿内容不动，状态停在 `PUBLISH_FAILED` 等待运营从 UI 显式点"重新编辑"才回 DRAFT（与上文 PUBLISH_FAILED 定义一致）；
- **"回滚到旧版本" 不是覆盖**：不可变快照（D6）下，"回滚"= 用户在 UI 选择 `version=N-2` 的快照创建新草稿 → 走标准发布流程产出 `version=N+1`（内容等于 N-2），审计链完整可追溯；
- **批量发布**：v1 **不提供**批量原子 API，前端拆成单条逐次调用，进度按"5 / 12 成功"形式展示，失败明细单独列出；
- **DISABLED 与发布解耦**：`PUBLISHED → DISABLED` 为关停，`DISABLED → PUBLISHED` 为重新启用，均为独立单事务操作 + 一条对应 `audit_log`（`action=DISABLE` / `action=ENABLE`），不走 `PUBLISHING` 中转，不产生新 `rule_version` 行（`current_version` 指针不变）。
- **PUBLISHING 残留兜底**：进程在"事务回滚 → 写 PUBLISH_FAILED 的短事务"之间崩溃时，`rule_definition` 可能停留在 `PUBLISHING` 状态。引擎需后台清扫任务定期扫"`PUBLISHING` 且 `updated_at` 早于阈值"的行 → 视同发布失败，状态修正为 `PUBLISH_FAILED` + 追加 `audit_log` 记录 `action=PUBLISH_FAILED, after_snapshot.errorCode=ZOMBIE_PUBLISHING`。清扫频率与超时阈值默认值在 07-operability 给（建议阈值 ≥ 一次发布事务的 P99 ×10），不入决策层。

**v1 不做的**：
- 不做批量原子发布（事务跨度大 + DB 锁竞争，运营营销场景按需求频次也用不上）；
- 不做发布前的"自动 dry-run 全量校验"（dry-run 是 D7 范畴，是否纳入发布前置校验由 07-operability 决定）；
- 不做版本之间的 diff / 三方合并工具（前端如有需要在 06-frontend 单独设计）。

---

## D20. v1 高吞吐评估期落地范围 ⭐⭐⭐

**为什么重要**：D6 / D17 已确立"不可变快照 + 倒排索引"的架构基线，但**评估期的具体执行形态**没说——单次评估走什么路径、metric 怎么取数、Action 怎么派发、变量引用怎么验证。不表态会出现：评估线程被 Action 同步派发拖慢、N 条规则各自 N+1 拉 metric、AST 引用了拼错的变量名直到运行时才暴露。市场对比（Drools / CEL / Aviator / ice）表明这几条是中台规则引擎吞吐天花板的关键。

| 选项 | 说明 | 权衡 |
|------|------|------|
| ☐ A. v1 落 4 件套 + 1 件套 SPI 预留 | metric 批量预拉 / 异步 Dispatcher / 输入契约校验 / Watcher SPI 多态化 v1 完成；预编译 Predicate 仅留 SPI，v1.5 切换 | v1 吞吐立刻压住高峰；后续切换零侵入 |
| ☐ B. v1 仅落输入契约校验 | 最小改动 | 高吞吐撑不住，未来补救要回炉 |
| ☐ C. v1 一次性预编译 + 嵌入式 SDK | 一步到位 | 抽象未沉淀就上重武器，返工概率高 |

**决定**：**A**

**v1 落地范围**：

1. **`EvalContext` metric 批量预拉**：
   - 发布期把 RuleVersion 静态依赖的 metric 集合算出来，落 `rule_version.metric_dependencies` 列（数组 JSON）；
   - Matcher 取到候选 RuleVersion 列表后，**先做集合并集 → 一次性批量拉取**（Redis mget / 指标服务 batch API）→ 注入 `EvalContext`；
   - 评估期 `MetricSource` 仅从 `EvalContext` 读，**禁止运行时再发起 metric 网络调用**；运行时若发现引用了未预拉的 metric → 落 `EvalResult.errorCode = METRIC_FETCH_FAIL`（D15）；
   - **预拉值在 `EvalContext` 内整次评估生命周期冻结**：注入 EvalContext 后即视为本次评估的不可变快照，**不再受 `Metric.cachePolicyDefault.ttl` 影响**——TTL 只决定"下次评估是否复用上次缓存"，不决定"本次评估内 metric 是否过期重取"。即使评估耗时跨越 TTL 边界，本次评估仍读初始预拉值（保评估一致性 + 与 D17 快照锁定语义对齐）；
   - 收益：把"评估期 N 次 round-trip"压成"匹配后 1 次 mget"，PUSH 模式下高吞吐杠杆最大。

2. **异步 Action Dispatcher**：
   - Visitor 求值结束后输出 `List<ActionInstance>` 入内部队列（v1 实现：内存有界 `BlockingQueue`；v1.x 可换 Disruptor RingBuffer）；
   - Dispatcher 多消费者线程池消费 + D18 的 retryable / failFast / continue-on-error 语义在 Dispatcher 内闭环；
   - **评估线程不等待 Action 派发完成**——`EvalResult` 在 Visitor 出树即返回，`action_execution` 由 Dispatcher 异步写；
   - PULL 模式语义保持：返回 `EvalResult` 即可，调用方知道 Action 派发是异步（与"评估即派发"的 PUSH 形态对齐）；
   - 队列满拒绝策略：丢弃 + 监控告警 + 该 Action 落 `ActionResult { status=FAILED, errorCode=QUEUE_OVERFLOW, retryable=true }`（**新增 `ActionResult.errorCode` 枚举值**，回填 [`01-concepts.md`](./01-concepts.md) §3.7 集中表）；
   - **重试上限归宿**：`retryable=true` 进入独立重试队列（D18），重试达上限仍失败 → `action_execution.status = FAILED` 终态，**引擎不再二次重试**；后续补偿由 D4 补偿流水线外部调度（D18 "补偿不自动触发"），引擎仅保留 FAILED 记录供对账。重试次数上限 / 退避策略默认值在 07-operability 给。**v1 不引入死信队列（DLQ）**——FAILED 状态本身即是终态游标，DLQ 在引入 MQ 后再考虑（留 [`08-evolution.md`](./08-evolution.md) §二 待补 anchor）。
   - **线程池粒度**：v1 全局单一消费者线程池（共享一个队列），不按 Scene / Rule 隔离；Scene 级线程池隔离 + 队列分片留 [`08-evolution.md`](./08-evolution.md) §2.13 同期讨论（与预编译切换一同评估）。队列容量上限 / 消费者数 / 拒绝告警阈值等运维参数在 07-operability 定默认值，不入决策层。
   - **进程重启数据丢失**：v1 内存队列在进程重启时未消费 ActionInstance 会丢失，依赖 D6 "评估即版本快照不可变" + 上游 RuleEvent 可重推完成补偿（PUSH 场景由业务侧上游重推，PULL 场景由调用方主动重试）。引入持久化队列（DB / MQ 落盘）留 [`08-evolution.md`](./08-evolution.md) §二 待补 anchor。

3. **输入引用闭合校验**：
   - 发布校验阶段（D19 事务前置步骤）扫描 AST 所有 `ConditionNode.params` 的变量引用名集合 `V`；
   - 校验 `V ⊆ Scene.payloadSchema 声明的字段名集合 ∪ Scene.metric 白名单 ∪ EvalContext 标准字段`；
   - 任一引用不在上述集合 → 发布拒绝，状态落 `PUBLISH_FAILED`，audit_log 写 `action=PUBLISH_FAILED` 记录，`after_snapshot` 携带 `errorCode=UNRESOLVED_VARIABLE`（与 §3.11 audit_log 关键边界 "错误诊断信息走 after_snapshot" 对齐）；
   - **是后续预编译切换的前提**——强类型契约确立后，编译期才能确定槽位偏移；
   - **`EvalContext` 标准字段**（v1 闭合枚举，由 02-runtime 维护）：`now` / `tenantId` / `scene` / `eventType` / `occurredAt` / `subjectId` / `ruleVersionId`。

4. **Watcher 多 backend SPI**：
   - D17 既有 `RuleVersionWatcher` 接口（`subscribe / pull / status`）固化为**正式 SPI**，禁止跨实现耦合内部状态；
   - v1 唯一实现 `DbPollingRuleWatcher`（默认 15s 轮询）；
   - SPI 契约要求实现方满足：变更通知最终一致 + 至多一次 callback 重复（消费方幂等）+ 启动期一次性全量拉；
   - 多 backend 切换（MQ / Nacos / ZK）演进详见 [`08-evolution.md`](./08-evolution.md) §2.14 嵌入式 SDK 模式。

5. **预编译 Predicate SPI 预留**：
   - `rule_version` 表加可选字段 `compiled_predicate_ref?: String`（v1 不填，v1.5 启用）；
   - 引入 `RuleVersionExecutor` SPI：`execute(RuleVersion, EvalContext) → EvalResult`，v1 默认实现 `InterpretedExecutor`（Visitor 树遍历）；
   - v1.5 加 `CompiledExecutor`（Janino / LambdaMetafactory 编译产物），由 `ExecutorRegistry` 按 RuleVersion 配置选择；
   - **接口先就位，切换不动调用方**——D6 不可变快照确保编译产物可与版本严格对应缓存；
   - 完整切换路径详见 [`08-evolution.md`](./08-evolution.md) §2.13 评估期预编译完全切换。

**v1 不做的**：
- 不做 alpha 节点共享（跨规则条件去重），v1 接受重复评估开销，演进路径并入 §2.13 讨论；
- 不做嵌入式 SDK 形态，v1 评估走中心服务 RPC，演进详见 §2.14；
- 不做 EXPRESSION 沙箱表达式叶子（需安全审计 + 函数白名单），v1 加新条件仍走 `ConditionType` 注册；
- 不做"评估期同步派发 Action 的 PUSH 模式"——异步派发是默认且唯一形态。

**派生约束**：
- `ActionResult.errorCode` 枚举追加 `QUEUE_OVERFLOW`，回填 [`01-concepts.md`](./01-concepts.md) §3.7 集中表；
- `rule_version.metric_dependencies` 字段 + `rule_version.compiled_predicate_ref?` 字段：05-storage DDL 待落；
- `RuleVersionExecutor` SPI 入 [`README.md`](./README.md) §四 抽象表（01-concepts §四 是"心智级时序"，SPI 不在该处展开）；
- `UNRESOLVED_VARIABLE` 是发布期 `audit_log` 的 errorCode，与运行期 `EvalResult.errorCode` / `ActionResult.errorCode` 不同维度，不入两个运行期枚举。

---

## D21. 评估观测数据异步写入 ⭐⭐

**为什么重要**：D7 确立了"评估过程透明、节点级 trace 落库可自助排障"，D9 确立了"全 MySQL 起步 + 30 天保留"，但**评估观测数据（`node_trace` / `evaluation_session` 等系统行为表）的写入路径**没说——同步事务写还是异步批写？失败如何降级？粗算一次评估命中 5-20 条规则、每规则 10-50 节点 → 单次评估 50-1000 行 trace；MySQL 批插 100 行约 3-10ms。若同步写直接吃掉风控级 P99 < 50ms 主流程预算的 10-40%，**延迟随业务复杂度线性恶化**——这是规则引擎吞吐天花板的典型陷阱。决策层不表态，实现者随手用 `@Async` 注解 / 评估期边求值边 insert，热路径会被慢慢拖垮。

| 选项 | 说明 | 权衡 |
|------|------|------|
| ☐ A. 同步事务写（与 `audit_log` 同款） | trace 写入与 `EvalResult` 返回同事务 | 强一致但热路径 P99 暴涨 10-40%，规则越复杂越糟；trace 不是审计无需强一致 |
| ☐ B. 异步批写（队列 + 消费者线程池 + batch insert） | 评估期内存累积 → session 结束入队列 → 消费者按条数 / 时间阈值 batch insert；与 D20 §2 异步 Dispatcher 同款抽象 | 热路径零阻塞；失败降级丢弃 + 告警；进程重启未刷盘丢失，可由上游重推 RuleEvent 重放 |
| ☐ C. 评估期直接走 MQ（Kafka / Pulsar） | trace 投递到 MQ，独立消费者写 DB | 跨进程不丢；但与 v1 "热路径零外部依赖" 冲突，v2 范畴 |
| ☐ D. 写本地日志 + 后台导入 | logback async appender + filebeat/logstash → DB | 多一个外部组件运维，与 D9 "全 MySQL 起步" 取向冲突 |

**决定**：**B**（异步批写，复用 D20 §2 队列模型）

**v1 落地范围**：

1. **`TraceWriter` 抽象（新增）**：
   - 与 `Dispatcher` 平级的写表通道抽象，统一承载 `node_trace` 写入（含 ConditionNode trace + Pre-Gate `PRE_GATE_BLOCKED` trace，两者走同一 Writer，不另起通道）；
   - 数据流：评估线程在 `EvalContext` 内开 `TraceCollector` 累积内存级 `TraceRow`（纯 `ArrayList`，无锁无阻塞）→ `EvalResult` 出树时一次性 `submit(batch)` 入队 → 消费者线程攒批 batch insert；
   - 队列：进程内有界 `BlockingQueue<TraceBatch>`（v1 实现 `ArrayBlockingQueue`），与 D20 §2 Action Dispatcher 队列**独立**（两路语义不同：trace 可丢、Action 不可丢；生命周期不同：trace 评估期内、Action 评估后）；
   - 攒批触发：条数阈值 `trace.batch.size` OR 时间阈值 `trace.flush.interval`（取早）；
   - **评估线程零阻塞**：`submit()` 是 O(1) 非阻塞 `offer`，队列满立即丢弃 + counter 告警，**不**阻塞主流程、**不**抛异常、**不**回写 `EvalResult.errorCode`（trace 是旁路观察通道，污染主流程语义即破坏 D15）。

2. **失败降级语义**（关键，与 D15 / D18 errorCode 体系区分）：
   - **队列满**：当批 trace 全部丢弃 + `meter.counter("trace.queue.overflow")` + `meter.counter("trace.rows.dropped")` + `log.warn`，不影响 EvalResult；
   - **DB batch insert 异常**：消费者捕获异常 + 重试 1 次 → 仍失败丢弃 + counter + `log.error`，trace 是观察数据多次重试无收益；
   - **消费者线程死亡**：`UncaughtExceptionHandler` 复活线程 + counter + alert；线程池全死降级为"提交即丢弃 + counter"；
   - **进程关闭**：`@PreDestroy` 触发 `shutdown` + drain + `awaitTermination(trace.shutdown.timeout)`，未刷盘 batch 丢弃（与 D20 §2 Action 队列同语义——可由上游重推 RuleEvent 重放）。

3. **与 `audit_log` 严格分离**（强一致性边界）：
   - `audit_log`（D14：人的行为）**必须同步事务写**（与 `rule_definition` / `rule_version` 同事务，D19）——审计要求强一致，发布成功但审计缺失 = 不可追溯，是 v1 红线；
   - `evaluation_session` 行（每次评估 1 行锚点）**v1 同步写**（量小延迟可忽略，且对账需该行作锚 + 与 `event_id` DB uk 是 D11 幂等双兜底的下半层）；异步化的三层角色解耦路径与 v1 不做前置准备的理由详见 [`08-evolution.md`](./08-evolution.md) §2.15；
   - `node_trace`（每次评估 50-1000 行）**异步批写**（本决策）；
   - `action_execution`（每次 Action 派发 1 行）由 D20 §2 Dispatcher 异步写，与本决策同款但**独立队列**。

4. **Pre-Gate trace 走同一 TraceWriter；dry-run trace 走独立 SPI**：
   - §3.14 Pre-Gate 失败节点（`nodeType=PRE_GATE_BLOCKED`）与 ConditionNode trace 走同一 `TraceWriter`，不另起通道；
   - dry-run 模式（D7）trace 走独立 `DryRunTraceWriter` SPI（与 `TraceWriter` 并列），写入 `dry_run_node_trace` 表，与 prod `node_trace` 完全隔离；`EvalServiceImpl` 在 `doEvaluate()` 末尾按 `isDryRun` 标记路由到对应 Writer，两路互不干扰；
   - 具体 `node_trace` 表的 schema 差异（如 Pre-Gate 不持有 `conditionType` / `actualValue`）由 [`05-storage.md`](./05-storage.md) §node_trace 表展开。

5. **运维参数留 07-operability**（决策层只声明参数 + 决定因素，**不列具体数字**，与 D20 §2 同款规范）：
   - `trace.queue.capacity`：决定因素 = 评估期峰值 batch 数 × 安全余量；监控 `queue.size` P99 占用率；
   - `trace.batch.size`：决定因素 = MySQL `max_allowed_packet` 上限 + 索引写放大 + 单 batch insert RT 目标；
   - `trace.flush.interval`：决定因素 = 业务能容忍的"trace 可见延迟" + 队列累积率；
   - `trace.consumers`：决定因素 = DB 连接池余量 + 单消费者吞吐；
   - `trace.retry.max`：决定因素 = trace 是观察数据多次重试无收益 → 取小值即可；
   - `trace.shutdown.timeout`：决定因素 = `@PreDestroy` drain 残留 batch 的目标 P99。
   - 具体默认值 / 监控阈值 / 告警规则统一由 [`07-operability.md`](./07-operability.md) 给。

**v1 不做的**：
- 不走 MQ（与"热路径零外部依赖"冲突，留 v2 / [`08-evolution.md`](./08-evolution.md) §二 待补 anchor）；
- 不做 trace 持久化队列（DB / 落盘 WAL）——trace 可重放（上游重推 RuleEvent 即可），持久化成本 > 收益；
- 不做"评估期边求值边 insert"——即便用独立线程池，每行一次 round-trip 仍是 N 倍延迟，违反"批写"基本盘；
- 不引入 LMAX Disruptor 环形队列——v1 量级 `BlockingQueue` 完全够，Disruptor 调试与依赖成本不划算（v1.x 性能压测出瓶颈再切换）；
- 不允许用 Spring `@Async` 注解承载 trace 写入——默认线程池配置不友好、无 backpressure、失败语义不清，与 D20 §2 队列模型重复。

**派生约束**：
- `TraceWriter` 入 [`README.md`](./README.md) §四 抽象表（与 Dispatcher / RuleVersionWatcher / RuleVersionExecutor 平级）；
- `01-concepts.md` §3.5 节点 trace 描述追加"异步批写（D21）"边界说明 + §3.14 Pre-Gate trace 落点追加"走同一 `TraceWriter`"；
- `05-storage.md` `node_trace` 表 DDL 需考虑批插友好（无昂贵唯一约束 / 选择合适 PK 顺序，详见 05-storage 展开时落）；
- `07-operability.md` 给上述 6 个参数的默认值 + 监控告警阈值。

---

## D22. Pre-Gate 拦截的对账状态 ⭐⭐

**为什么重要**：D15 定义了 `HIT / MISS / ERROR` 三态，但 Pre-Gate 拦截（灰度未命中 / 黑名单 / 频次超限 / 互斥）在语义上既不是"AST 求值不满足"（MISS），也不是"评估异常"（ERROR）——把它归入 MISS 导致报表无法区分"规则条件太严"和"灰度只放了 10%"，对应完全不同的优化动作。

| 选项 | 说明 | 权衡 |
|------|------|------|
| ☐ A. 引入第四态 `BLOCKED` | 四态：`HIT / MISS / BLOCKED / ERROR`；Pre-Gate 拦截 → `BLOCKED`，`evaluation_session.blocked_by` 记录拦截 Gate 类型 | 语义精确；所有用三态枚举的地方需扩容，但影响面可控（evaluation_session 表 + 对账报表） |
| ☐ B. MISS 内加 `miss_reason` 子字段 | 三态不变，`evaluation_session` 加 `miss_reason` 列（`AST_FALSE / PRE_GATE`） | 外部枚举稳定；但两字段表达一个语义，别扭 |
| ☐ C. 维持现状归 MISS | Pre-Gate 拦截靠 `node_trace.PRE_GATE_BLOCKED` 节点区分 | 最简；但运营无法在 evaluation_session 聚合粒度区分两种 MISS 原因 |

**决定**：**A（引入 BLOCKED 第四态）**

**v1 落地范围**：
- `evaluation_session.status` 枚举扩为四态：`HIT / MISS / BLOCKED / ERROR`；
- Pre-Gate 任一 Gate 拦截 → 写一条 `evaluation_session`，`status=BLOCKED`，`blocked_by` 列记录拦截 Gate 类型（`ROLLOUT / WHITELIST / BLACKLIST / RATE_LIMIT / MUTEX`）；
- **EvalResult 不变**：Pre-Gate 拦截时根本不进入 AST 评估，不产生 `EvalResult`；
- **对账分母**：命中率 = `HIT / (HIT + MISS)`；`BLOCKED` 和 `ERROR` 均不参与命中率分母，各自独立监控指标；
- **与 D15 区分**：D15 的 `ERROR` 是"进入 AST 评估后某节点异常"；`BLOCKED` 是"未进入 AST"，两者不同维度，不混用。

**派生约束**：
- `evaluation_session` 表加 `blocked_by` 列（`VARCHAR`，nullable，仅 `status=BLOCKED` 时有值）；
- `01-concepts.md` §3.14 Pre-Gate 关键边界"对账归 MISS 桶"修正为"对账归 BLOCKED 桶"；
- README §二 D15 决策表对账部分同步更新（已在 D15 v1 语义规范段更新）；
- `07-operability.md` 监控指标清单补 `rule_engine_eval_blocked_total{gate_type}` 指标（已落地）。

---

## D23. `evaluation_session` 幂等键语义 ⭐⭐

**为什么重要**：幂等键决定"同一事件能否被多次评估"，影响版本切换后的 Replay 可行性和异常事件重推策略。未明确表态会导致 Replay 场景和正常幂等语义产生分歧预期。

| 选项 | 说明 | 权衡 |
|------|------|------|
| ☐ A. `(tenant_id, event_id)` — 同一事件永远只评估一次 | Replay 须换新 eventId，版本切换后想重跑须上游重推 | 幂等最强，设计最简；Replay 对上游有要求，但符合 MQ 标准重推语义 |
| ☐ B. 加版本维度：`(tenant_id, event_id, rule_version_snapshot_hash)` | 同 eventId 可在不同规则版本下独立评估 | 支持低成本版本重跑；幂等键复杂，同 eventId 多行增加查询注意点 |
| ☐ C. 分表：Replay 走独立 `replay_session` 表 | 生产幂等干净，Replay 独立维护 | 主路径语义最干净；两套表需单独维护 |

**决定**：**A（`(tenant_id, event_id)` 维持单一 uk，Replay 上游换 eventId）**

**v1 落地范围**：
- `evaluation_session` uk = `(tenant_id, event_id)`，语义：同一业务事件（由业务方保证 eventId 全局唯一）永远只评估一次；
- **这是 by design**，不是缺陷：版本切换后同一历史事件不会被新版本规则重新评估；
- **Replay 语义**：`source=REPLAY` 仅作来源标记，不改幂等语义——Replay 场景业务方上游重推时必须生成新 eventId（MQ 重推即新消息，eventId 换新是标准做法）；
- **版本切换后测新规则**：走 dry-run 入口（传 mockEvent 指定 `ruleVersionId`），不走生产链路；
- v1 不引入 Replay 专用表或专用 API——v1 Replay 场景极低频，过度设计。

**v1 不做的**：
- 不为 Replay 单独建表或特殊处理；
- 不支持"同一 eventId 用新规则版本重新评估"的生产路径——这应该是运维操作，走 dry-run 或上游重推（换 eventId）。

---

## D24. Scene 变更热加载（`SceneWatcher` SPI）⭐⭐

**为什么重要**：D17 / D20 定义了 `RuleVersionWatcher` 监听规则版本变更，但 Scene 自身也有需要热加载的生命周期事件：新增 `scene_metric_binding` 要重新预热 MetricSource，修改 `payloadSchema` 要刷新发布校验器，`Scene.status DISABLED` 要从路由表摘除。`RuleVersionWatcher` 只看 `rule_version` 表，完全感知不到这些变更——如果 Scene 变更不热加载，运营新加一条 metric 绑定要重启应用，不符合"配置热加载"基调。

| 选项 | 说明 | 权衡 |
|------|------|------|
| ☐ A. 扩展 `RuleVersionWatcher` 为 `ConfigWatcher` | 一个 Watcher 监听所有配置变更（规则 + Scene + Binding） | 实现简单；但接口职责变宽，替换实现时颗粒度过粗 |
| ☐ B. 新增独立 `SceneWatcher` SPI，与 `RuleVersionWatcher` 平级 | 两个独立 SPI，各管各的变更域，各自触发对应热加载逻辑 | 职责清晰，可独立替换；多一个轮询任务，成本低 |
| ☐ C. Scene 变更重启生效 | 不做热加载，Scene 是低频变更对象 | 最简；但有停服窗口，运营体验差，与整体热加载基调不符 |

**决定**：**B（新增独立 `SceneWatcher` SPI）**

**v1 落地范围**：
- **`SceneWatcher` 接口**（与 `RuleVersionWatcher` 对称）：`subscribe(callback) / pull(since) / status()`；
- **v1 唯一实现**：`DbPollingSceneWatcher`，默认轮询间隔 30s（Scene 变更频率远低于规则，间隔可比 `RuleVersionWatcher` 的 15s 长）；可配 `engine.rule.scene.watch-interval-seconds`；
- **变更触发逻辑**：
  - `scene_metric_binding` 新增/删除 → 触发对应 Scene 的 MetricSource 预热/卸载；
  - `scene_action_binding` 新增/删除 → 触发对应 Scene 的 ActionHandler 资源预热/卸载（仅 PUSH/HYBRID Scene）；
  - `scene.status = DISABLED`（DDL 落地表名 `scene`；本 D 草稿曾称 `scene_definition`）→ 将该 Scene 从 Matcher 路由表摘除（拒绝新事件路由到此 Scene），但已进行中的评估 session 不中断；
  - `scene.status = ACTIVE`（重启用）→ 重新加入 Matcher 路由表 + 按绑定重新预热资源；
  - `scene` 其他字段变更（`payloadSchema` / `defaultParams` / `eventTypes`）→ 刷新内存中的 Scene 配置缓存；
- **SPI 契约与 `RuleVersionWatcher` 对齐**：变更通知最终一致 + 至多一次 callback 重复（消费方幂等）+ 启动期一次性全量拉；
- **多 backend 预留**：v2 可换 MQ 推 / Nacos，仅替换 `SceneWatcher` 实现，业务侧零改动。

**Spring Modulith 单服务模式补充**（与 D17 保持对称）：
- `rule-config-svc` 模块在 Scene 配置变更（metric binding / action binding / status / payloadSchema）落库后发出对应 Modulith 事件（如 `SceneChangedEvent`）；`rule-eval-svc` 以 `@ApplicationModuleListener` 订阅，按变更类型触发对应热加载逻辑（与上方"变更触发逻辑"一一对应）——不再依赖 30s 轮询。
- `DbPollingSceneWatcher` 保留为 `SceneWatcher` SPI 实现，**仅供嵌入式 SDK 模式使用**（§2.14），与 D17 的 `DbPollingRuleWatcher` 归属相同。
- 单服务模式下 Scene 变更生效窗口降至毫秒级；"30s 最终一致"语义仅适用于 SDK 模式。

**派生约束**：
- `SceneWatcher` 入 README §四 抽象表（与 `RuleVersionWatcher` 平级）；
- `01-concepts.md` §3.2 Scene 关键边界补"Scene 变更由 `SceneWatcher` 热加载，30s 最终一致"；
- `09-skeleton.md` SPI 落点章节补 `SceneWatcher` 接口位置。

---

## D25. Context 构建并发模型 ⭐⭐

**为什么重要**：D20 §1 要求"metric 批量预拉后注入 EvalContext"，§3.13 Subject 要从 `user_profile` 加载，但**具体并发形态**没说——Subject 加载和 metric 批拉是串行还是并行？metric 并发内部用什么并发原语？某一个 metric 或 Subject 加载失败时怎么处理？不表态会出现：每个人实现一套并发风格、失败语义散乱、Metric 超时拖住整个 Context 构建。

| 选项 | 说明 | 权衡 |
|------|------|------|
| ☐ A. 串行取数（for-loop） | Subject → metric-1 → metric-2 → … 顺序执行 | 最简单；N 个 metric 延迟线性叠加，高吞吐场景不可接受 |
| ☐ B. `CompletableFuture` 并行 + 共享线程池 | Subject 加载与全部 metric 取数 `CompletableFuture.allOf()` 并行 | 并发效率最高；线程池共享所有请求，不同 Scene 可能互相抢池 |
| ☐ C. `CompletableFuture` 并行 + 各 MetricSource 自管线程 | `CompletableFuture` 包装，MetricSource 实现自管连接池 / 执行器（JDBC 连接池 / HTTP client 各自线程），引擎侧只 `allOf().join()` 等待 | 隔离性最好；各 Source 资源互不干扰；引擎侧不引入共享线程池（v1 避免参数调优）——延迟来自最慢那条 IO |

**决定**：**C**（`CompletableFuture.allOf()` 并行 + 各 MetricSource 自管执行资源；Subject 加载与 metric 并行启动）

**v1 落地范围**：

1. **Context 构建启动顺序**：
   - 按本次评估候选 RuleVersion 集合取 `metric_dependencies` 并集，同时从 RuleEvent 读 `subjectId`；
   - **Subject 加载与 metric 批拉并行启动**：各自包装为 `CompletableFuture`，不互相等待；
   - `CompletableFuture.allOf(subjectFuture, ...metricFutures).join()` 等待全部完成（或超时抛出）；
   - Subject 加载通过 `SubjectLoader` SPI（v1 唯一实现：`UserProfileLoader`，按 `subjectId` 查 `user_profile` 表）执行；
   - 每个 metric 通过对应 `MetricSource` 实现自管连接池/HTTP client 并发取数；引擎只拿 `Future<Value>` 集合 `allOf()` 等。

2. **局部失败语义**（关键）：
   - **Subject 加载失败**（主体不存在 / user_profile 超时）→ 整个 Context 构建失败，该 Rule 落 `EvalResult.errorCode = METRIC_FETCH_FAIL`（D15 归一，Subject 是 Context 必要字段）；
   - **单个 metric 加载失败**（timeout / 熔断 / 反序列化异常）→ 仅该 metric 标记失败，其他 metric 结果仍有效；评估期若某 ConditionNode 引用了失败 metric → 该节点落 `EvalResult.errorCode = METRIC_FETCH_FAIL`（D15），其余节点按正常路径；
   - **整体等待超时**（`allOf().join(timeout)`）→ 超时时已完成的 metric 有效，未完成的视同失败；timeout 默认值在 [`07-operability.md`](./07-operability.md) §Context 构建超时 给。

3. **`SubjectLoader` SPI**（新增）：
   - 接口：`SubjectLoader`：`load(subjectId, subjectType, RuleEvent) → Subject`；
   - v1 唯一实现：`UserProfileLoader`（`subjectType=USER`，查 `user_profile` 表）；
   - 未来 `ACCOUNT / DEVICE / ORDER` 类型扩展只加新实现，引擎核心不变；
   - 加载超时 / 主体不存在的处理由实现方按 D15 规范向上抛受检异常（引擎捕获后归一为 `METRIC_FETCH_FAIL`）；
   - `SubjectLoader` 注册到 `SubjectLoaderRegistry`（按 `subjectType` 路由，等同 `MetricRegistry` 同款模式）。

4. **并发安全要求**：
   - `EvalContext` 构建完成即不可变（只读，线程安全）；
   - `MetricSource.fetch()` 必须线程安全（多线程同时从不同 RuleVersion 的 Context 并发调用）；
   - `SubjectLoader.load()` 必须线程安全（同款要求）；
   - 以上是 SPI 实现方义务，在 [`04-extension.md`](./04-extension.md) §MetricSource 实现指南 + §SubjectLoader 实现指南 展开。

**v1 不做的**：
- 不做 Scene 级别的 Context 构建线程池隔离（留 [`08-evolution.md`](./08-evolution.md) §2.13 一并评估）；
- 不做"partial metric" 策略（失败 metric 的 ConditionNode 从 AST 求值中剔除再继续）——v1 严格走 D15 METRIC_FETCH_FAIL，不做局部降级（降级语义复杂，正确性风险 > 可用性收益）；
- 不做 Subject 属性的 payload 补充（D13 明定 payload 走 `event.payload.*` 路径，不补充 `subject.attributes`）。

**派生约束**：
- `SubjectLoader` 入 README §四 抽象表（与 MetricSource / MetricRegistry 平级）；
- `01-concepts.md` §3.13 Subject 关键边界补"SubjectLoader SPI（D25），v1 实现 UserProfileLoader"；
- `04-extension.md` 新增 §SubjectLoader 实现指南；
- 07-operability 补 `context.build.timeout` 默认值 + Subject 加载失败 alert 阈值。

---

## 二、核心数据模型（续）

## D26. Decision 实体 + 多规则命中合成策略 ⭐⭐

**为什么重要**：风控场景的核心输出是"决策码"（REJECT/REVIEW/PASS），而非简单的 `satisfied=true/false`。多条规则同时命中时，需要定义"谁的决策胜出"。不表态导致每个业务方自己在调用层做合成逻辑，引擎失去对决策语义的控制力；且 `EvalResult.decision?`（D12 占位）是为 `DECISION_TABLE` kind 的表格输出预留的，与"Tenant 级 Decision 实体 + 优先级合成"语义不同。

| 选项 | 说明 | 权衡 |
|------|------|------|
| ☐ A. Rule 直挂 `decisionCode` 字段 | Rule 命中后直接输出一个 decisionCode 字符串 | 最简单；但无法支持 score 区间 → Decision 映射（SCORECARD 场景必要）；Decision 无独立管理入口 |
| ☐ B. 独立 `RuleDecisionBinding` 关联表 | Rule 通过绑定表关联 Decision，支持可选 score 区间；多对多松耦合 | 灵活、支持评分卡场景；发布时快照化进 `rule_version.decision_bindings`（DDL 落地列名，无 `_snapshot` 后缀），保证不可变性（D6） |
| ☐ C. Decision 挂载 Action | Action 挂在 Decision 上，Rule 只出 Decision，所有 REJECT 触发同一批 Action | 配置量少；但失去 Rule 级别差异化 Action 能力；与现有 Rule→Action 设计摩擦大，v1 不适合 |

**决定**：**B**（独立 `RuleDecisionBinding` 关联表）+ Decision 为 **Tenant 级**一等实体 + Scene 声明 `decisionStrategy`（v1 仅 `HIGHEST_PRIORITY`）+ Decision 与 Action **正交**

**v1 落地范围**：

1. **Decision 实体（Tenant 级）**：
   - 字段：`tenant_id` / `code`（Tenant 内唯一）/ `name` / `priority`（数值越小优先级越高，如 REJECT=1, REVIEW=2, PASS=100）/ `description`；
   - Tenant 内 `code` 全局唯一约束；`priority` 值由业务方自定，引擎只按数值排序；
   - 横切标准审计字段（D14）；
   - v1 不做 Decision 的层级 / 分类标签（如"拒绝类 / 通过类"）——priority 数值已足够合成排序，分类标签留 [`08-evolution.md`](./08-evolution.md) §演进。

2. **RuleDecisionBinding（Rule 与 Decision 的关联，版本快照化）**：
   - 字段：`rule_id` / `decision_code` / `score_range_min?` / `score_range_max?`（仅 `Rule.kind=SCORECARD` + `EvalResult.score` 在区间内时生效；v1 `AST_BOOLEAN` kind 直接绑一个 decisionCode，score 区间留空）；
   - 一条 Rule 可绑多个 Decision（按 score 区间划分），最常见是 1:1；
   - 发布时随 `rule_version` 整体快照化（存入 `rule_version.decision_bindings`，DDL 落地列名无 `_snapshot` 后缀），保证不可变性（D6）；
   - v1 `AST_BOOLEAN` kind 命中后直接取唯一绑定的 decisionCode；score 区间匹配在 D12 SCORECARD kind 实现时启用。

3. **Scene.decisionStrategy（多规则命中合成）**：
   - v1 仅实现 `HIGHEST_PRIORITY`：取所有命中规则对应 Decision 中 `priority` 值最小者作为最终决策；**已被 D29 覆盖**：DDL NOT NULL DEFAULT 'HIGHEST_PRIORITY'，PUSH/HYBRID Scene 缺省等价此值，不再是可选字段（D29 决定保留运行时缺省语义，DDL 层已加 DEFAULT）；
   - v2 补充 `MAJORITY`（多数命中）/ `CUSTOM_SPI`（自定义合成器 SPI）；
   - PULL Scene 不参与合成，配置了也忽略。

4. **EvalResult 新增字段**：
   - `finalDecision?: DecisionRef`：合成后最终 Decision（Scene 配了 `decisionStrategy` 时填充）；
   - `hitDecisions: List<DecisionRef>`：所有命中规则的 Decision 按 priority 排序（始终填充）；
   - `DecisionRef = { code: String, name: String, priority: Int, fromRuleVersionId: Long }`；
   - 原 `EvalResult.decision?`（D12 `DECISION_TABLE` kind 的表格输出）语义正交，不冲突，继续保留。

5. **Decision 与 Action 的归属**：**已被 D27 覆盖**，见 D27。

**v1 不做的**：
- Decision 分类标签（拒绝类 / 通过类 / 其他），priority 数值已足够；
- `MAJORITY` / `CUSTOM_SPI` 合成策略；
- 分数区间 → Decision 映射的 v1 实现（SCORECARD kind v1 不实装，score 区间字段只建 schema 占位）。

**派生约束**：
- Decision + RuleDecisionBinding 入 README §四 抽象表；
- `01-concepts.md` §一 新增 Decision 一等公民 + §3.19 Decision + §3.20 RuleDecisionBinding；
- `01-concepts.md` §3.4 EvalResult 结构追加 `finalDecision` / `hitDecisions` / `DecisionRef`；
- `05-storage.md` 新增 `decision_definition` + `rule_decision_binding` 表 DDL；
- `10-api-contract.md` 对外 API 的 `EvalResult` DTO 追加两字段。

---

## D27. Action 归属从 Rule 迁移到 Decision ⭐⭐⭐

**为什么重要**：D26 建立了 Decision 实体后，"Action 挂在 Rule 上还是 Decision 上"成为需要显式落定的决策。两种模型的数据流和配置模式截然不同：Rule→Action 模型下每条规则独立配置动作，同样输出 REJECT 的 100 条规则需要各自配一遍"发通知"；Decision→Action 模型下同一决策码的所有命中共享一套动作配置，减少重复但失去规则级差异化能力。

| 选项 | 说明 | 权衡 |
|------|------|------|
| ☐ A. Action 挂 Rule（现状） | Rule 命中 → 派发 Rule.actions；D26 Decision 仅作输出标签 | 规则级差异化 Action 能力最强；但相同 Decision 的 100 条规则各自配置 Action，重复度高；不符合"风控场景统一处置"直觉 |
| ☐ B. Action 挂 Decision（完全替代） | Rule 命中 → 合成 finalDecision → 派发 finalDecision.actions；Rule 不再持有 actions 字段 | 配置集中、同一决策行为一致；Rule 层职责收窄为"判定条件"；PULL Scene Decision 不配 Action 约束继续生效 |
| ☐ C. 两层并存（Decision 公共 + Rule 差异化） | Decision 配公共 Action，Rule 再配差异化 Action，两层叠加派发 | 最灵活；合并语义复杂（排序、failFast 跨层传播、幂等键如何构造、去重如何做）；v1 实现成本高 |

**决定**：**B**（Action 完全迁移到 Decision，Rule 不再持有 actions 字段；PULL Scene 的 Decision 不配 Action，约束不变）

**v1 落地范围**：

1. **数据模型变更**：
   - `rule_definition` / `rule_version` **移除** `actions` / `actions_snapshot` 字段；
   - `decision_definition` **新增** `actions` 字段（Action 列表，JSON，与原 Rule.actions 结构相同）；
   - Action 列表随 Decision 整体参与 `rule_version.decision_bindings`（DDL 落地列名；已含 decision_code，actions 嵌入其中）—— 保证发布快照不可变（D6）；
   - `action_execution` 幂等键从 `(tenantId, eventId, ruleVersionId, actionId)` 变更为 `(tenantId, eventId, decisionCode, actionId)`（同一 event + 同一决策码下每个 actionId 只执行一次）。

2. **评估流程变更**（Action Dispatcher 入口前移）：
   ```
   旧：Rule AST true → Rule.actions → Dispatcher
   新：Rule AST true → 绑定 Decision → decisionStrategy 合成 finalDecision
                        → finalDecision.actions → Dispatcher
   ```
   - 仅 `finalDecision`（合成后的最终 Decision）的 actions 被派发；`hitDecisions` 里其他 Decision 的 actions **不派发**（避免多规则命中时重复执行）；
   - Scene 未配 `decisionStrategy` 时无 `finalDecision`，此时：若有多个 hitDecisions，取 priority 最小者派发（退化为 `HIGHEST_PRIORITY`）；若无 hitDecisions，无 Action 派发；
   - PULL Scene：`finalDecision` 填充并返回调用方，Action 不派发（Decision.actions 在 PULL Scene 下必须为空，发布校验拒绝）。

3. **Scene 白名单治理迁移**：
   - `scene_action_binding` 仍由 Scene 维护 actionType 白名单；
   - 校验时机：**Rule 发布时**，引擎根据 Rule 绑定的 Decision，检查 `Decision.actions` 内的所有 `actionType` 都在本 Scene 的 `scene_action_binding` 内——Decision 是 Tenant 级，但 Rule 是 Scene 级，Rule 发布时隐式引入了"本 Scene 使用该 Decision 的动作白名单校验"；
   - PULL Scene 发布时校验：Rule 绑定的 Decision 的 `actions` 必须为空，否则发布拒绝；
   - 前端配规则时：Decision 的 actionType 下拉项按当前 Scene 的 action binding 过滤（同原 Rule.actions 编辑行为）。

4. **幂等语义**：
   - 新幂等键 `(tenantId, eventId, decisionCode, actionId)` 语义：同一事件 + 同一决策码下每个动作只执行一次；
   - 多规则命中同一 Decision 时（`hitDecisions` 里出现同一 decisionCode），幂等键天然去重，不会重复派发；
   - `action_execution` 表新增 `decision_code` 列（替换 `rule_version_id`），DDL 见 [`05-storage.md`](./05-storage.md)。

**v1 不做的**：
- Decision 级 failFast 跨 Decision（单个 Decision 内 failFast 语义与原 Rule 内一致，D18 约束不变）；
- 多 Decision 之间的 Action 编排（顺序/并行），v1 finalDecision 只有一个，无跨 Decision 编排诉求；
- Rule 级差异化 Action（Options C，按需在 v2 以"Rule override actions"形式演进）。

**派生约束**：
- `RuleDefinition` / `RuleVersion` 移除 `actions` / `actions_snapshot`；
- `Decision` 新增 `actions` 字段；Action 快照随 Rule 发布序列化进 `rule_version.decision_bindings`（DDL 落地列名，无 `_snapshot` 后缀）；
- `action_execution` 幂等键列 `rule_version_id` → `decision_code`；
- `01-concepts.md` §3.7 Action 关键边界更新（归属迁移 + 幂等键变更）+ §3.19 Decision 字段表追加 `actions`；
- `README §四` 抽象表 `RuleDefinition` / `RuleVersion` / `ActionExecution` 行同步；
- `04-extension.md` §ActionHandler 实现指南中"注册到 Scene"的描述随白名单校验时机迁移更新。

> **DDL 命名注**：本文档正文已统一使用 DDL 落地列名 `decision_bindings`（无 `_snapshot` 后缀）；概念期草稿曾用 `decision_bindings_snapshot`，两者指同一物理列，见 [`05-storage.md`](./05-storage.md) `rule_version` 表。

> 已被 D60 作废：动作子系统整体移除，Action 不再属于任何实体；Decision 仅承载决策码 / priority / name，`Decision.actions` / `DecisionBinding.actions` 字段已删，PULL-scene action 发布校验随之退役。

---

## 四、精化与派生

## D28. Decision.actions 变更生效时机 ⭐⭐

**背景**：D27 将 Action 迁移到 Decision.actions，但 Decision.actions 在 Rule 发布时随 `rule_version.decision_bindings`（DDL 落地列名，概念期有时称 `decision_bindings_snapshot`）一同快照化——修改 Decision.actions 后，已发布的 Rule 版本仍使用旧快照，新配置不会自动生效。运营容易误以为"改了 Decision 就生效了"。

| 选项 | 说明 | 权衡 |
|------|------|------|
| A. 当前方案（快照 + UI 提示） | Decision.actions 按 Rule 发布时机快照；修改 Decision.actions 后，平台 UI 列出所有引用该 Decision 的已发布规则，提示"需重新发布才能生效" | 设计不变，实现简单；运营理解快照语义后无歧义 |
| B. Decision 独立版本化 | Decision 引入 `(code, version)` 二层结构，Rule 快照绑定 `(decisionCode, decisionVersion)`；可选"跟随最新版"或"锁定版本" | 去除了使用歧义，但 Decision 变成二层，运营心智更重；演进成本高 |

**你的决定**：A

**v1 落地范围**：
- Decision.actions 修改后引擎侧无感知，已发布 Rule 继续使用快照；
- 平台 UI 在修改 Decision.actions 保存时，查询所有 `rule_version.decision_bindings`（DDL 落地列名，无 `_snapshot` 后缀）引用该 Decision 的已发布规则，弹出提示列表："以下 N 条规则使用旧快照，需重新发布才能使用最新 actions"；
- 提示仅为运营警示，不阻断保存操作。

**v1 不做的**：
- Decision 版本化（选项 B）；
- 引擎侧主动推送"快照已过期"通知。

**派生约束**：
- `01-concepts.md` §3.19 Decision 关键边界已补充此说明；
- `06-frontend.md` 需在 Decision 编辑页补充 UI 提示逻辑（留后续前端设计阶段落地）。

> 已被 D60 作废：`Decision.actions` 字段已删，"动作变更生效时机"议题不复存在（引擎纯决策化）。

---

## D29. PUSH/HYBRID Scene 的 decisionStrategy 默认值 ⭐⭐

**背景**：`Scene.decisionStrategy` 当前为可选字段，未配置时 `finalDecision` 为空，actions 不派发。PUSH 模式下漏配静默失效无报错，排查成本高。

| 选项 | 说明 | 权衡 |
|------|------|------|
| A. 保持可选，文档提示 | Scene 不配则 finalDecision 为空，靠运营文档和排查指南规避 | 灵活但易踩坑，静默失效排查成本高 |
| B. PUSH/HYBRID Scene 默认 `HIGHEST_PRIORITY` | Scene 不显式配 decisionStrategy 时，引擎对 PUSH/HYBRID Scene 自动按 `HIGHEST_PRIORITY` 合成；PULL Scene 不参与合成，不受影响 | 消灭静默失效类问题；HIGHEST_PRIORITY 覆盖绝大多数业务场景，极少需要其他策略 |

**你的决定**：B

**v1 落地范围**：
- Scene 字段 `decisionStrategy`：DDL 层 NOT NULL DEFAULT 'HIGHEST_PRIORITY'，API 层可不传（引擎用 DDL DEFAULT）；引擎对 PUSH/HYBRID Scene 缺省逻辑：DB 值即 `HIGHEST_PRIORITY`，评估时不产生 null；
- PULL Scene `decisionStrategy` 保持无意义（PULL 不合成 finalDecision 也不派发 Action），配置了也忽略；
- 前端 UI 在 PUSH/HYBRID Scene 编辑页将 `decisionStrategy` 下拉默认选中 `HIGHEST_PRIORITY`，显示"（默认）"标注。

**v1 不做的**：
- 强制要求 PUSH/HYBRID Scene 必须显式配 `decisionStrategy`（过于严格，影响现有 Scene 兼容）；
- `MAJORITY` / `CUSTOM_SPI` 策略（D26 留 v2）。

**派生约束**：
- `01-concepts.md` §3.20 RuleDecisionBinding 已包含 `decisionStrategy` 三值表及 PUSH/HYBRID Scene 缺省等价 HIGHEST_PRIORITY 的说明；
- `02-runtime.md` 评估链路实现需按此默认逻辑处理。

---

## D30. providedMetrics — 业务方随评估携带指标值 ⭐⭐

**背景**：部分场景（如用户注册、设备换绑）中，业务方本身就是数据源头，引擎再绕回去查 DB 或调外部服务既浪费又引入延迟。需要一种机制让业务方在触发评估时直接携带已知的指标值，引擎在 EvalContext 构建时优先使用这些值，跳过 sourceType 取数。

**场景分类**：

| 场景 | 数据上报 | 触发评估 | 说明 |
|------|---------|---------|------|
| 用户注册 | 是（设备分、IP 信誉、KYC 初始值） | 是（同步拿风控结论） | 业务方是数据源头 |
| 大额转账 | 否 | 是（引擎自己取） | 历史数据在库，引擎直接查 |
| KYC 升级 | 是（新 kyc_level） | 否（走 `PUT /subjects/{id}/attributes`） | 属性更新，后续评估才用 |
| 设备换绑 | 是（新设备信息） | 是（同步检查异常） | 同注册场景 |
| 批量用户打标 | 是（外部计算的风险分） | 否 | 后台任务写入，不实时触发 |

**选项**：

| 选项 | 说明 | 权衡 |
|------|------|------|
| ☑ A. `providedMetrics` 字段 + Metric 级 `allowProvided` 标志 | 评估请求携带指标值；Metric 注册时声明是否允许外部覆盖 | 职责清晰；平台按 metric 粒度控制信任边界；不引入新存储 |
| ☐ B. 全部走 payload，条件层用 `event.payload.compare` | 把指标值塞进 payload 字段 | 最简，但 payload 是"本次事件上下文"语义，与指标语义混用，破坏类型闭合校验 |
| ☐ C. 评估后引擎持久化 `providedMetrics` 到自己的存储 | 一次上报后续评估复用 | 引擎承担业务数据存储职责，TTL/失效/写失败全部变成引擎问题，不做 |

**你的决定**：A

**v1 落地范围**：

1. **API 层**：`POST /api/v1/rule/event`（PUSH）或 `POST /api/v1/rule/evaluate`（PULL）请求体新增 `providedMetrics: Map<metricCode, value>` 可选字段（见 10-api-contract §三）。
2. **校验**：`providedMetrics` 的 key 必须是本 Scene 白名单内已注册的 metricCode（发布期已有类型闭合，运行期入口校验类型一致性）；非法 key 返回 400。
3. **EvalContext 构建优先级**：`providedMetrics` 中有值 → 跳过该 metric 的 sourceType 取数，直接用传入值；`allowProvided=false` 的 metric 即使传了也忽略（日志 WARN，不报错）。
4. **Metric 注册新增字段** `allowProvided`（`BOOLEAN`）：按 `sourceType` 给出推荐默认值，注册时无需每次手填——

   | sourceType | 推荐默认 | 理由 |
   |------------|---------|------|
   | `ATTRIBUTE` | `true` | 属性类由业务方维护，上报合理 |
   | `EXTERNAL_HTTP` | `true` | 业务方通常就是该服务的调用方，手里有值 |
   | `SQL_AGGREGATE` | `false` | 平台权威聚合，不应被覆盖 |
   | `STREAM` | `false` | 流计算结果是平台产物，同上 |

   需要保护权威性的指标（如黑名单命中、官方风控分）在例外情况下手动覆盖为 `false`；其余按上表默认，不强制每次填写。
5. **trace 记录来源**：每个 metric 的 trace 条目记录 `valueSource: PROVIDED | FETCHED`，方便排查。
6. **不做持久化**：`providedMetrics` 的值只活在本次评估 EvalContext 中，评估完即丢弃。业务方如需持久化，走自己的系统存储，引擎后续用 sourceType 正常取。

**不做（v1 明确排除）**：
- 引擎不持久化 `providedMetrics` 的值（`persistedMetricCodes` 不做）
- 不做 dry-run 时对 `providedMetrics` 值的额外限制（dry-run 本就不产生副作用）

**派生约束**：
- `01-concepts.md` §3.9 Metric 字段表需补充 `allowProvided` 字段说明（含 sourceType 推荐默认值）
- `01-concepts.md` §3.8 EvalContext 构建逻辑需说明 `providedMetrics` 优先级
- `10-api-contract.md` 需补充两处：
  - 评估接口请求体新增 `providedMetrics` 字段
  - 新增 `GET /admin/v1/scenes/{sceneCode}/provided-metrics` 发现接口，返回本 Scene 内 `allowProvided=true` 的 metric 列表（含 `metricCode / dataType / description`），供业务方接入时查询，响应可缓存（见 10-api-contract §5.2）

---

## D31. 前端技术栈

**为什么重要**：前端是规则引擎的主要操作界面，核心交互是 AST 可视化条件编辑器，技术栈选型直接决定开发路径。

| 选项 | 说明 | 权衡 |
|------|------|------|
| ☐ A. react-querybuilder + React | 条件规则构建器领域专用库，内置嵌套 AND/OR/NOT 编辑 | 与本项目 AST 数据结构天然匹配，定制成本低 |
| ☐ B. Vue 3 + Ant Design Vue | 通用方案，自行实现 AST 编辑器 | 灵活，但核心编辑器需从头写 |
| ☐ C. AMIS (百度低代码) | JSON schema 驱动，适合表单密集型后台 | AST 编辑器无内置支持，需大量逃逸，不适合 |

**决定**：✅ A — react-querybuilder + React 18

**技术栈**：

| 层 | 选择 |
|---|---|
| 框架 | React 18 |
| 条件编辑器 | react-querybuilder |
| UI 组件库 | Ant Design 5 |
| 构建工具 | Vite |
| 状态管理 | Zustand |

**v1 落地范围**：
- 前端工程放 `frontend/` 目录，与 `src/` 平级
- 覆盖核心操作：Scene/规则树浏览、AST 条件编辑、dry-run 执行与结果展示、审计日志查询
- 无登录/注册（D14），`X-Actor-Id` 由前端 header 传入

**不做（v1 明确排除）**：
- 不做权限管理 UI（无 RBAC，D14）
- 不做 SSR / Next.js（纯管理页面无需服务端渲染）
- 不引入 Redux（Zustand 够用）

**派生约束**：
- `09-skeleton.md` §九（前端工程结构）记录 `frontend/` 目录规划
- `06-frontend.md` 承载前端架构细节，本决策仅记技术栈选型

---

## D32. ArchUnit 版本与 rule-kernel 编译目标

**为什么重要**：rule-kernel 启用了 ArchUnit 架构约束测试，需确保 ArchUnit 能分析 rule-kernel 产出的字节码。

| 选项 | 说明 | 权衡 |
|------|------|------|
| ☐ A. 坚持 ArchUnit 1.3.x + Java 25 字节码 | 不修改版本 | ArchUnit 1.3 内置 ASM 9.6，Java 25 字节码（major 69）超出其解析上限，测试启动即崩溃 |
| ☐ B. 升级 ArchUnit 1.4.0 + maven.compiler.release=21 | 小版本升级 + 降编译目标 | 解决解析问题；rule-kernel 编译为 Java 21 字节码，与其余模块（Java 25）不一致 |
| ✅ C. 升级 ArchUnit 1.4.2 | 补丁升级 | 1.4.2 内置 ASM 9.8，原生支持 Java 25 字节码；无需 maven.compiler.release override，1.5 尚未发布 |

**决定**：✅ C — 升级 ArchUnit 1.4.0 → 1.4.2，移除 rule-kernel 的 `maven.compiler.release=21` override

**落地范围**：
- `pom.xml` `archunit.version` 从 1.4.0 改为 1.4.2
- `rule-kernel/pom.xml` 删除 `<maven.compiler.release>21</maven.compiler.release>` 及相关注释
- 全模块统一使用 Java 25 编译目标，ArchUnit 日志确认 `Detected Java version 25.0.3`，150 项测试全部通过

**变更原因**：实测 ArchUnit 1.4.2（而非预期的 1.5+）已内置 ASM 9.8，支持 Java 25 字节码；B 方案的模块字节码不一致问题因此得到完全消除。

---

## D33. 嵌入式 SDK 本地模式（代码定义规则，零网络）⭐⭐

**为什么重要**：D20 落地的 `RuleEngineClient` 强制要求 `serverUrl`，`SnapshotPoller` 靠 HTTP 拉规则。在以下场景中这是多余的负担：
- 单测 / CI：只想验证规则逻辑，不想起服务端；
- 演示 / 原型：规则写死在代码里，不需要动态下发；
- 完全离线部署：无法访问 rule-engine 服务，规则随业务代码打包。

**两种方案**：

| 方案 | 描述 | 权衡 |
|------|------|------|
| A. Builder 支持 `localSnapshot()`，不传 `serverUrl` 时跳过 SnapshotPoller | 直接往 SceneRuleIndex 里塞快照，evaluate() 路径不变 | 侵入 Builder，但用法自然；`RuleVersionSnapshot` 需要补 Builder 辅助方法 |
| B. 暴露 `SceneRuleIndex.update()` 让调用方手动管理 | 最小改动 | API 太低层，调用方要理解内部索引结构 |

**决定**：A — Builder 新增 `localSnapshot(RuleVersionSnapshot)` 方法，叠加调用；`build()` 时若未配 `serverUrl` 则不启动 `SnapshotPoller`，直接将所有本地快照写入 `SceneRuleIndex`。

**`RuleVersionSnapshot` 构造**：record 目前只有全参构造器，补一个内部 `Builder` 辅助类（不改 record 签名），用于本地模式的链式构造。

**不改的**：
- `evaluate()` 路径完全不变，本地 / 远程透明；
- `SnapshotPoller` 不变；
- `rule-eval-svc` 的服务端路径不变；
- HTTP 模式仍要求 `serverUrl` 必填。

**落地范围**（D33 实现计划）：
- `rule-kernel`：`RuleVersionSnapshot` 补 `Builder` 内部类；
- `rule-sdk`：`RuleEngineClient.Builder` 新增 `localSnapshot()` 方法，`build()` 判断是否启动 poller；
- 测试：`RuleEngineClientTest` 补本地模式覆盖（无 HTTP、直接评估）。

**已实装**（D33）：`RuleVersionSnapshot.Builder` + `RuleEngineClient.Builder.localSnapshot()` + 本地模式跳过 SnapshotPoller 逻辑 + 测试覆盖。

---

## D35. SDK `RuleSource` 抽象 + 四种规则来源模式 ⭐⭐⭐

**为什么重要**：D34 只解决了"代码直接传 snapshot"，但实际需要支持的场景更多——HTTP 轮询（生产）、JSON 文件（离线/测试）、代码 DSL（单测/演示）、注解扫描（声明式）。四种模式的差异只在"索引怎么填充"，底层 `EvalEngine` 完全一致。需要一个统一抽象避免 `RuleEngineClient` 膨胀成四套分支逻辑。

**核心抽象**：

```java
/** 规则来源 SPI：将规则装载到评估索引。 */
public interface RuleSource {
    void loadInto(SceneRuleIndex index);
}
```

**四种实现**：

| 模式 | 实现类 | 典型场景 |
|---|---|---|
| HTTP 轮询 | `PollingRuleSource`（含原 `SnapshotPoller` 逻辑） | 生产，规则由服务端管理 |
| JSON 文件 | `FileRuleSource.classpath("rules.json")` | 离线、测试、规则随代码打包 |
| 代码 DSL | `DslRuleSource`（由 `LocalBuilder` 构造） | 单测、演示 |
| 注解扫描 | `AnnotationRuleSource`（未来，D38 之后实现） | 声明式，`@RuleDef` 标注规则类 |

**`RuleEngineClient` 统一入口**：接受一个或多个 `RuleSource`，可混用：

```java
// 生产：HTTP 轮询
RuleEngineClient.builder()
    .tenantId("t1")
    .serverUrl("http://rule-engine:8080")
    .build();

// 文件离线
RuleEngineClient.builder()
    .tenantId("t1")
    .ruleFile("classpath:rules/fraud.json")
    .build();

// 本地 DSL
RuleEngineClient.local("t1")
    .rule().scene("fraud").on("TRANSACTION")
           .when(Condition.gt("amount", 1000))
           .decide("BLOCK", 100)
    .build();

// 混用（文件兜底 + HTTP 热更新）
RuleEngineClient.builder()
    .tenantId("t1")
    .ruleSource(FileRuleSource.classpath("rules/baseline.json"))
    .ruleSource(new PollingRuleSource(serverUrl, tenantId, ...))
    .build();
```

**`RuleSource` 不携带 evaluator**：规则数据与算子行为职责分离，evaluator 在 Client 级通过 `addEvaluator()` 注册（见 D37）。

**文件格式**：JSON，与服务端 `GET /sdk/v1/snapshots` 响应体 `data` 数组格式完全一致，可直接从服务端导出存为文件离线使用。不做 YAML（需额外依赖 `jackson-dataformat-yaml`），如有需求后续扩展。

**不改的**：`EvalEngine`、`SceneRuleIndex`、服务端任何模块。

**已实装**（D35）：`RuleSource` SPI + `PollingRuleSource` / `FileRuleSource` / `DslRuleSource` / `AnnotationRuleSource` 四种实现；`RuleEngineClient` 统一接受多 `RuleSource`，混用场景覆盖。

---

## D36. `Condition` DSL — 隐藏 AST 构造细节 ⭐⭐

**为什么重要**：`ConditionNode`（5 参构造）+ `AndNode`（3 参构造）对调用方完全暴露 AST 内部结构，手写门槛高，且参数顺序难记（尤其 `weight`、`displayLabel` 几乎每次都是 null/0）。代码 DSL 模式和注解模式都需要一个友好的条件表达层。

**设计**：`Condition` 是 `rule-sdk` 中的工厂 + Builder 类，最终生成 `AstNode`：

```java
// 叶子条件（内置算子）
Condition.gt("amount", 1000)           // amount > 1000
Condition.in("country", "CN", "HK")   // country IN [CN, HK]
Condition.between("age", 18, 65)
Condition.matches("email", ".*@corp\\.com")

// 逻辑组合
Condition.gt("amount", 1000).and(Condition.in("country", "CN", "HK"))
Condition.gt("amount", 1000).or(Condition.eq("vip", true))
Condition.gt("amount", 1000).not()

// 自定义算子（需配合 addEvaluator 注册）
Condition.of("BLACKLIST_HIT", "device_id", Map.of("list", blocklist))

// 恒真 / 恒假
Condition.always()
Condition.never()
```

**实现**：`Condition` 是 `rule-sdk` 的 wrapper，`toAst()` 方法生成对应 `AstNode`，`ConditionNode` 的 `displayLabel` 固定 null、`weight` 固定 0.0（纯 DSL 场景不需要这两个字段）。

**不改的**：`ConditionNode` / `AndNode` 等 record 定义不变，`Condition` 是叠加的便利层。

**已实装**（D36）：`Condition` 工厂类 + 全套内置算子（gt/lt/gte/lte/eq/neq/in/notIn/between/notBetween/contains/notContains/startsWith/endsWith/matches/dateBefore/dateAfter）+ 逻辑组合（and/or/not）+ `always()`/`never()`。

---

## D37. Evaluator 注册策略 — Client 级 `addEvaluator()`，不随 `RuleSource` 携带 ⭐⭐

**为什么重要**：自定义条件算子（如 `BLACKLIST_HIT`）是行为（代码），`RuleSource` 是数据（规则定义）。若把 evaluator 混入 `RuleSource`，会导致一个 `RuleSource` 实例携带可执行代码，破坏数据/行为分离，也使热重载复杂化（数据可以随时重新加载，行为不行）。

**决定**：evaluator 在 `RuleEngineClient` 级注册，所有 `RuleSource` 共享同一套 evaluator map：

```java
RuleEngineClient.builder()
    .serverUrl("...")
    .tenantId("t1")
    .addEvaluator("BLACKLIST_HIT", (node, ctx) -> {
        List<?> list = (List<?>) node.params().get("list");
        Object val = ctx.metrics().get(node.metricCode());
        return val != null && list.contains(val.value());
    })
    .build();
```

**实现**：`Builder` 新增 `extraEvaluators: Map<String, ConditionEvaluator>`，构建 `InterpretedExecutor` 时用 `KernelEvaluators.defaults()` 作底，`putAll(extraEvaluators)` 叠加（用户自定义可覆盖同名内置算子）。

**Spring 服务端已有路径**：`@ConditionType` Bean 扫描 + `KernelEvaluators.defaults()`，两套路径互不影响。

**未来注解扫描**：`RuleEngineClient.scanEvaluators("com.example")` 扫描 `@ConditionType` Bean 注册，是 `addEvaluator()` 的批量版，底层逻辑相同。

**已实装**（D37）：`RuleEngineClient.Builder.addEvaluator()` + 注册到 `KernelEvaluators` 自定义 map + 传递给 `EvalEngine`。

---

## D38. 注解精简 — 对齐架构现状 ⭐⭐

**背景**：三个注解（`@ActionType` / `@ConditionType` / `@MetricSourceType`）在 rule-kernel 骨架阶段建立，当时框架是 Spring Bean 扫描模式。D20 之后架构迭代，`KernelEvaluators.defaults()` 手工 Map 取代了注解扫描，部分注解的运行时语义已经悬空。

**各注解现状与决定**：

| 注解 | 当前实际使用 | 决定 |
|---|---|---|
| `@ActionType` | `EvalAutoConfiguration` 运行时读 `.value()` 映射 handler | **保留，现状合理** |
| `@ConditionType` | 仅测试中使用，无任何运行时扫描逻辑 | **精简**：去掉 `requiresMetric` 字段（无消费方）；定位调整为"前端 schema 元数据 + SDK `scanEvaluators()` 的注册标记"，不再暗示 Spring Bean 扫描 |
| `@MetricSourceType` | 仅测试中使用，无任何运行时扫描逻辑 | **精简**：去掉 `defaultTimeoutMs` / `defaultCacheTtlSeconds`（运维参数属于 07-operability，不该放注解里）；只保留 `value()` + `paramsSchema()`；或按需整体删除 |
| `@RuleDef`（新增） | 未来 SDK 注解模式使用 | **预留接口** `InlineRuleSpec`，注解本身等 D35 注解模式实装时再建 |

**`@ConditionType.requiresMetric` 删除原因**：字段含义模糊（是说"必须有 metricCode"还是"需要从 MetricSource 取数"？），且无任何运行时代码消费它。前端表单校验应由 `paramsSchema` 驱动，不需要额外字段。

**迁移影响**：`@ConditionType` 字段删减是 breaking change，但目前无任何生产代码使用该字段（仅测试），影响面极小。

**已实装**（D38）：`@ConditionType` 删除 `requiresMetric` 字段；`@MetricSourceType` 删除 `defaultTimeoutMs` / `defaultCacheTtlSeconds` 字段；`@RuleDef` 注解 + `InlineRuleSpec` 接口随 D40 一并落地。

---

## D39. Spring Boot Starter 补完 — 文件模式 + Bean 自动扫描 + Listener 注入 ⭐⭐⭐

**背景**：`rule-sdk-spring-boot-starter` v1 只支持 HTTP 轮询模式（`serverUrl` + `tenantId`），三块能力缺失：
1. 文件模式（`rule-files` 配置列表）；
2. `@ConditionType` Bean 自动扫描，省去手动 `addEvaluator()`；
3. `EvalResultListener` / `EvalSessionListener` Bean 自动注入。

**各项设计**：

**文件模式**：`SdkProperties` 新增 `ruleFiles: List<String>`，`AutoConfiguration` 遍历列表调 `builder.ruleFile()`。与 `serverUrl` 互斥校验已在 `RuleEngineClient.Builder.build()` 保障，starter 层不重复校验。

**`@ConditionType` Bean 自动扫描**：`AutoConfiguration` 注入 `ApplicationContext`，调 `ctx.getBeansWithAnnotation(ConditionType.class)`，遍历结果：若 Bean 实现 `ConditionEvaluator`，以注解 `value()` 为 key 调 `builder.addEvaluator()`；否则跳过（不报错）。这是 D37 `addEvaluator()` 的批量版，底层路径完全一致。

**Listener Bean 注入**：`AutoConfiguration` 注入 `Optional<EvalResultListener>` 和 `Optional<EvalSessionListener>`，存在则调 `builder.evalResultListener()` / `builder.evalSessionListener()`。

**不改的**：`RuleEngineClient` 内部逻辑、`RuleSource` SPI、`InterpretedExecutor`、`EvalEngine`。

**落地范围**：
- `rule-sdk-spring-boot-starter`：`SdkProperties.java`（已加 `ruleFiles`）、`RuleEngineClientAutoConfiguration.java`；
- `10-api-contract.md §8.2`：补 `rule-files`、`@ConditionType` Bean、Listener Bean 的 Spring 用法说明。

**已实装**（D39）：`SdkProperties.ruleFiles` 字段 + `@ConditionType` Bean 自动扫描 + `EvalResultListener`/`EvalSessionListener` 自动注入。

---

## D40. SDK 注解模式 — `@RuleDef` + `AnnotationRuleSource` ⭐⭐⭐

**背景**：D35 定义了 `RuleSource` SPI 四种模式，注解扫描模式（`AnnotationRuleSource`）当时标注"D38 之后实现"。现有三种模式（HTTP 轮询 / 文件 / DSL）都需要调用方手动构造 `RuleVersionSnapshot` 或写 JSON，注解模式的价值在于：**规则定义与业务代码同处一个 Java 类**，IDE 静态检查、重构、单测全链路打通；Spring 容器中零配置自动装载。

**核心设计**：

```
InlineRuleSpec（接口，rule-sdk）
  ├── condition(): Condition   ← 规则条件，由实现类返回
  └── 从 @RuleDef 注解读：tenantId / sceneCode / trigger / decisions
```

```java
@RuleDef(
    tenantId  = "t1",
    sceneCode = "fraud",
    trigger   = "TRANSACTION",
    decisions = {@Decision(code = "BLOCK", priority = 100)}
)
public class AmountFraudRule implements InlineRuleSpec {
    @Override
    public Condition condition() {
        return Condition.gt("amount", 1000)
                        .and(Condition.in("country", "CN", "HK"));
    }
}
```

**`AnnotationRuleSource`**：
- 接收 `List<InlineRuleSpec>` 构造（非 Spring 场景）
- `loadInto(index)` 遍历每个 spec：读 `@RuleDef` 元数据 + 调 `condition().toAst()` → 构建 `RuleVersionSnapshot` → 写入索引
- `ruleVersionId` 由 `@RuleDef.id()` 显式指定（必填，确保幂等稳定）；`tenantId` 可在 `@RuleDef` 指定，也可在 `RuleEngineClient.Builder.tenantId()` 统一设置（注解值优先）

**Spring 自动装配（D39 starter 内联）**：
- AutoConfiguration 收集容器内所有 `InlineRuleSpec` Bean → 构造 `AnnotationRuleSource` → `builder.ruleSource()`
- 与文件模式、HTTP 轮询模式完全正交，可同时使用

**不做的**：
- `@RuleDef` 不支持多 trigger（v1 一条规则一个 trigger 事件类型，与现有模型一致）
- 不做 classpath 包路径扫描（非 Spring 场景依赖调用方显式传入 spec 列表）
- `ruleVersionId` 不自动生成（调用方负责 id 稳定性，避免每次启动 id 不同导致索引不一致）

**落地范围**：
- `rule-kernel`：新增 `@RuleDef`、`@Decision` 注解（放 `api/annotation` 包）
- `rule-sdk`：新增 `InlineRuleSpec` 接口、`AnnotationRuleSource` 类
- `rule-sdk-spring-boot-starter`：D39 AutoConfiguration 内增加 `InlineRuleSpec` Bean 收集逻辑
- `10-api-contract.md §8.1`：更新模式总览表，补 §8.x 注解模式用法

**已实装**（D40）：`@RuleDef` + `@Decision` 注解 + `InlineRuleSpec` 接口 + `AnnotationRuleSource`（扫描类路径下带 `@RuleDef` 的 `InlineRuleSpec` 实现）+ Starter 自动收集注入。

> 更新（D59，2026-06-11）：D40 中“`ruleVersionId` 调用方经 `@RuleDef.id()` 显式指定”已废弃——`@RuleDef` 改用 `code` + `version`，代理键 `ruleVersionId` 由 `(tenant,scene,code)` 哈希派生。详见 D59。

---

## D41. `Scene.executionStrategy` 扩展 — ALL_HITS / FIRST_HIT ⭐⭐

**背景**：v1 仅实现 `HIGHEST_PRIORITY`（D29）：多规则命中时取最高优先级 Decision。08-evolution §2.1 已记录 v2 补全 `ALL_HITS` / `FIRST_HIT`，现在具备实现条件。

**三种策略语义**：

| strategy | 行为 | 典型场景 |
|---|---|---|
| `HIGHEST_PRIORITY`（现有） | 命中规则中取优先级最高的 Decision | 互斥决策（拦截 > 审核 > 放行） |
| `ALL_HITS` | 返回所有命中规则的 Decision 列表，不去重 | 营销叠加（多券并发 / 多优惠并存） |
| `FIRST_HIT` | 按规则 `priority` 倒序，第一条命中即停止后续评估 | 短路优化（高优规则命中后不必再跑低优规则） |

**`EvalResult` 已有 `hitDecisions()` 列表**，`ALL_HITS` / `FIRST_HIT` 直接利用，不改 API 签名。`finalDecision()` 语义：
- `ALL_HITS`：优先级最高的命中 Decision（与 `HIGHEST_PRIORITY` 等价，保持 finalDecision 有意义）
- `FIRST_HIT`：唯一命中的 Decision

**`Scene.executionStrategy` 字段**：v1 已有列，类型为 VARCHAR，`HIGHEST_PRIORITY` 为默认值；新增 `ALL_HITS` / `FIRST_HIT` 枚举值，Flyway 不需要 DDL 改动（字符串列直接写新值）。

**EvalEngine 改动**：当前 `evaluate()` 遍历所有规则收集命中结果后统一合成；`FIRST_HIT` 策略下评估到第一条命中即短路返回，其余规则跳过（`HIGHEST_PRIORITY` / `ALL_HITS` 仍全量评估）。

**不做的**：`MAJORITY` / `CUSTOM_SPI` 策略留 v2（D26 已说明）。

**落地范围**：
- `rule-kernel`：`SceneExecutionStrategy` 枚举加 `ALL_HITS` / `FIRST_HIT`；`EvalEngine.evaluate()` 按策略分支
- `rule-config-svc`：`SceneMapper` / `SceneService` 校验新枚举值
- `rule-eval-svc`：`SceneRuleIndex` / `EvalContextAssembler` 透传 strategy 字段
- 测试：`EvalEngine` 三种策略各自独立测试

**已实装**（D41）：`SceneExecutionStrategy` 枚举含三值 + `EvalEngine` 按策略短路/全量分支 + 三策略独立测试。

---

## D42. `DECISION_TREE` / `DECISION_TABLE` evaluator ⭐⭐

**背景**：D12 预留 `Rule.kind` 多态，v1 实现 `AST_BOOLEAN` + `SCORECARD`（D12 evaluator 已落地）。`DECISION_TREE` 与 `DECISION_TABLE` 是下一优先级形态，适合运营 / 风控"多条件分支"场景。

**DECISION_TREE**：
- `conditionAst` 存储嵌套 if/then/else 树（重用 `AstNode` 的 sealed 体系，新增 `IfNode(condition, thenNode, elseNode)`）
- evaluator 递归求值：condition 命中走 thenNode，否则走 elseNode；叶子节点为 `DecisionLeafNode(decisionCode)`
- 输出写入 `EvalResult.category`（字段已在 08-evolution §2.1 预留）

**DECISION_TABLE**：
- `conditionAst` 存储 JSON 列：`{columns: [{metricCode, operator}], rows: [{conditions: [value], decisionCode}]}`
- evaluator 行优先匹配：第一条所有列条件满足的行胜出，输出 `EvalResult.decision`
- 默认 FIRST_HIT 行语义；行顺序即优先级

**与现有评估链路的关系**：`RuleVersionExecutor` SPI 已有，新增 `DecisionTreeExecutor` / `DecisionTableExecutor` 实现，注册进 `ExecutorRegistry`（`kind` → executor 映射），`EvalEngine` 零改动。

**不做的**：v1 不做 EXPRESSION_SCRIPT（CEL / Aviator 沙箱安全 + 性能代价，留 v1.5）。

**落地范围**：
- `rule-kernel`：`IfNode` / `DecisionLeafNode` AST 节点；`DecisionTreeExecutor` / `DecisionTableExecutor`；`EvalResult` 补 `category` / `decision` 字段
- `rule-kernel`：`AstJsonCodec` 注册新节点类型
- `rule-config-svc`：发布校验 kind=DECISION_TREE/TABLE 时的 AST schema 检查
- `10-api-contract.md`：补 DECISION_TREE / DECISION_TABLE 的请求 / 响应 schema

**已实装**（D42）：`IfNode` / `DecisionLeafNode` / `DecisionTableNode` AST 节点 + `AstJsonCodec` 映射 + `DecisionTreeExecutor` / `DecisionTableExecutor` + 注册进 `EvalAutoConfiguration` + 发布校验覆盖 DECISION_TREE/TABLE kind。

---

## D43. 灰度配置收口 pre_gates ROLLOUT，废弃 `rollout` 列 ⭐⭐

**背景**：D6 初版把灰度设计为 `rule_version.rollout` 独立列，富模型 `{type: PERCENTAGE/USER_TAG/HYBRID, percentage, tagConditions}`（按百分比 + 按用户标签命中）。但实现从未消费该列——灰度实际由 `pre_gates` 列 `gateType=ROLLOUT` 项的 params 承载，且只实装了 percentage 分桶；`rollout` 列 NOT NULL 但只写 `'{}'` 不读，USER_TAG/HYBRID/tagConditions 从未落地。文档 7 处仍按一等字段描述 rollout，与实现长期漂移（上文 §D19 v1 落地范围 step 2 的"含 rollout 冻结"为彼时记录，不追溯改）。

**决策**：以实现为准收口。
- 灰度统一由 `pre_gates` 的 ROLLOUT pre-gate 承载，params = `percentage` / `bucketStart` / `bucketEnd` / `experimentId`；
- 删除只写不读的 `rule_version.rollout` 列（Flyway `V1_4__drop_rollout.sql`）；
- `experimentId` 共享分桶种子 + 桶区间实现 A/B 一致分桶与互斥（详见 08-evolution §2.16）；发布期校验 ROLLOUT params（percentage∈[0,100]、桶区间成对且 `0<=start<end<=100`、experimentId 非空白）；
- **按用户标签命中（USER_TAG / HYBRID / tagConditions）留演进**：将来作为独立 pre-gate 类型落地，不复活 `rollout` 列。

**已实装**（D43）：`RolloutPreGate` 桶区间 + experimentId 种子；`PublishService.validatePreGateParams` 发布期校验；`V1_4__drop_rollout.sql` 删列 + 全部代码/文档引用对齐（01-concepts §3.4、02-runtime、05-storage、07-operability、08-evolution、README）。

---

## D44. B20 时间框架：EvalContext.now 注入 + DATE/DATETIME 一等 dataType + 时间条件内置 ⭐⭐

**背景**：v1 规则引擎缺乏对时间的原生感知——DATE_BEFORE/AFTER 已有文档但实现未完整落地，EvalContext 无统一时钟注入，dataType 枚举不含时间类型，时间类 conditionType（time.window / time.occurred_at）未注册，发布期矩阵不覆盖 DATE/DATETIME 组合。需一次性补齐。

**决策**：

1. **EvalContext.now 单次注入（统一时钟）**：`now: Instant` 在 `EvalServiceImpl.doEvaluate` / `EvalEngine.evaluate` 入口调用一次 `Instant.now()`，整棵 AST 共用同一个 `now`，保证跨规则时钟一致性。不存在默认 `Instant.now()` 重载（禁止），调用方必须传入 `now`；

2. **时区解析优先级（TimeZoneResolver）**：字面时区偏移（ISO-8601 带 `+HH:mm`）> `params.timezone` > UTC。Scene 级默认时区（优先级 3 对应 `sceneDefaultTimezone` 参数）当前**暂缓**——B20 调用方始终传 `null`，槽位已保留，由后续批次激活；

3. **DATE / DATETIME 作为一等 dataType**：
   - `DATE` 对应 `LocalDate`（日历日期，无时区）；`DATETIME` 对应 `Instant`/带时区偏移（时区相关）
   - 纯策略实现：`DateComparisonStrategy`（DATE）/ `DateTimeComparisonStrategy`（DATETIME），无 I/O、无副作用
   - 两阶段管线：**解析段**在 evaluator 侧将原始参数经 `PlaceholderResolver` + `TimeZoneResolver` 转为强类型；**策略段**做纯比较
   - 发布期矩阵（`AstDataTypeResolver`）更新：EQ/NEQ 允许集合 += DATE/DATETIME；BETWEEN/NOT_BETWEEN 允许集合 += DATE/DATETIME；DATE_BEFORE/DATE_AFTER 新增行（allowed={DATE,DATETIME}，拒绝其他 dataType）；GT/GTE/LT/LTE 仍仅限数值型
   - `metric_definition.data_type` ENUM 扩展为含 `DATE` / `DATETIME`（Flyway `V1_5__add_date_datetime_to_metric_datatype.sql`）；

4. **PlaceholderResolver（占位符解析）**：`"$now"` → `EvalContext.now`（`Instant`）；`"$today"` → `EvalContext.now` 投影到时区后的 `LocalDate`，仅在 DATE 语境有效；`time.occurred_at` 语境中使用 `"$today"` → `CONDITION_EVAL_ERROR`；无法识别的 `$x` 或解析失败 → null（不抛异常）；不支持相对时长表达式（`$now-P7D` 等，留 B21）；

5. **context_snapshot 嵌套结构**：`evaluation_session.context_snapshot` 由 v1 原平铺格式 `{metricCode: value}` 升级为嵌套格式 `{"metrics": {metricCode: value, ...}, "evalNow": "<ISO-8601 instant>"}`，其中 `evalNow` 记录本次评估注入的统一时钟值，用于 dry-run 重放时还原历史时间点；

6. **内置时间类 conditionType 注册**：`time.window`（基于 `EvalContext.now` 的时间窗口判断）和 `time.occurred_at`（基于 `event.occurredAt` 的时刻比较）在 `KernelEvaluators.defaults()` 注册，属于内置路径闭合集合（D20 §3），不经过 operator×dataType 矩阵检查，无需 `metricCode`。

**不做的（v1 范围外）**：相对时长算术（`$now-P7D`）；近 N 天滚动聚合 SQL 注入 `EvalContext.now` 作为 `:now` 绑定变量（B21 负责）；Scene 级默认时区激活；DB `NOW()` 注入替代。

**已实装**（D44 / B20）：`EvalContext.now` 单次注入 + `context_snapshot` 嵌套格式；`TimeZoneResolver`；`PlaceholderResolver`（$now/$today）；`DateComparisonStrategy` / `DateTimeComparisonStrategy`；EQ/NEQ/BETWEEN/NOT_BETWEEN 解析段分支（DATE/DATETIME）；DATE_BEFORE/DATE_AFTER 重做（删 `toInstant` 静态方法，接入两阶段管线）；`time.window` / `time.occurred_at` evaluator 注册；发布期矩阵 `AstDataTypeResolver` 更新；`V1_5__add_date_datetime_to_metric_datatype.sql`。

---

## D45. B21 FETCHED 取数层：命名句柄 + :now 绑定 + 失败降级 + provided 优先 + Resolver SPI ⭐⭐⭐

**背景**：v1 `EvalContextAssembler` 是空壳——只把 `providedMetrics` 塞进 metrics，注入的 `MetricSourceHandler` 从不被调用，引擎不取任何数。需让引擎真正具备按 metric `sourceType` 拉取指标值的能力，并锁死外部资源访问的安全姿态。

**决策**：

1. **取数管线接线**：`EvalContextAssembler.assemble(event, candidates, now)` 扫过 Pre-Gate 的候选 `metricDependencies` 并集 → provided 优先 → 查缓存 → 按 sourceType 路由 handler 并发 fetch（`CompletableFuture` + 专用 `fetchExecutor` + 全局超时）；延迟 = max 而非 sum（对齐 D25）。

2. **Metric 定义来源 = 数据源无关 SPI `MetricDefinitionResolver`**（非冻进快照）：服务端实现读 `metric_definition` 表（`DbMetricDefinitionResolver` + Caffeine 缓存），嵌入式 SDK 实现读下发缓存。`sourceType` / `datasource` / `cacheTtlSeconds` 是可热调的操作配置，区别于 B19 冻进 AST 的 `dataType`（类型契约）。

3. **`MetricQuery` 加 `now`**：assembler 绑 `EvalContext.now`；SQL 的 `:now` 取此字段（非 DB `NOW()`），保 dry-run 重放。纯算法不收 ctx、请求对象收 `now`（与 B19/B20 同源原则）。

4. **provided 优先（D30 落地）**：`providedMetrics` 有值且 `def.allowProvided=true` → 用（PROVIDED），跳过 fetch；`allowProvided=false` 即使传也忽略（WARN）；`resolver` 返回 null（运行时无定义）且无 provided → 置 `METRIC_FETCH_FAIL` 降级（无定义视为异常，引用节点不命中、整树继续）。

5. **失败降级（D15 落地）+ 条件求值门面三态**：单 metric 取数失败/超时/无 handler → `MetricValue.error(METRIC_FETCH_FAIL)`，引用节点不命中、整树继续。统一门面 `ConditionEvaluation` 返回三态（满足/不满足/不可判定），各执行器按语义落 ERROR：布尔路径标 `NodeTrace.errorCode` 整树继续；**评分卡整卡 ERROR 不出分（风控保守）**；决策树/表遇 ERROR 整规则 ERROR + miss（不静默走错分支）。

6. **SQL_AGGREGATE 范式**：命名参数（`:subjectId` / `:tenantId` / `:now` / `:payload.x` / `:params.x`），禁 `${}` 拼接、禁 DB 时间函数，窗口长度写 SQL 文本，结果首行首列按 dataType 强转；数据源走 **infra 注册的命名只读 DataSource**（账密在 secrets 不落表）。

7. **EXTERNAL_HTTP 范式**：infra 注册命名 HTTP 端点（baseURL + 鉴权 + 超时），metric 只引用「端点名 + path + jsonPath」，不写自由 URL、不嵌凭证（灭 SSRF）。

8. **缓存**：key = `tenant:metricCode:subjectId:stableHash(params)`；`ttl=0` 不缓存；v1 进程内 Caffeine（`MetricCache` SPI，内核不依赖 Caffeine）。

9. **发布期校验**：拒绝含 DB 时间函数或 `${}` 拼接的 SQL；metric 引用的 datasource/endpoint 名必须已注册（`MetricResourceCatalog` SPI 由 eval-svc 提供，纯 config 部署时跳过资源名校验）。

**前向兼容（嵌入式 SDK 取数 B2，见 `specs/2026-06-06-sdk-fetch-design.md`）**：① metric 定义是独立可下发配置，不冻进 `rule_version` 快照；② `MetricDefinitionResolver` 数据源无关（服务端读库 / 嵌入式读下发缓存共用）；③ `EvalContextAssembler` 富构造为服务端与 SDK 统一取数入口（旧 2 参构造保留为 providedMetrics-only 退化路径）；④ `MetricDescriptor` 为定义下发的序列化契约。

**不做的（v1 范围外）**：`STREAM` sourceType 实装（无 handler → 自动降级）；OAuth2 自动刷 token；Scene 级数据源白名单；相对 duration 运算；Redis 缓存（v1 Caffeine）。

**已实装**（D45 / B21）：`EvalContextAssembler` 取数管线重写；`MetricValue.errorCode` / `MetricQuery.now` / `RuleVersionSnapshot.metricDependencies`；`MetricDescriptor` + `MetricDefinitionResolver` / `MetricCache` SPI；`ConditionEvaluation` 门面三态 + 5 执行器 ERROR 语义；`DbMetricDefinitionResolver`（Caffeine）+ `CaffeineMetricCache` + `fetchExecutor`；`MetricDataSourceRegistry`（只读）+ `SqlAggregateMetricSourceHandler`；`HttpEndpointRegistry` + `ExternalHttpMetricSourceHandler`；`MetricResourceCatalog` SPI + `MetricSafetyValidator`（发布期）。

---

## D46. B23 嵌入式 SDK FETCHED 取数：定义独立下发 + 宿主注入 handler + 默认行为不变 ⭐⭐

**背景**：B21（D45）把取数管线建在数据源无关的 `MetricDefinitionResolver` / `MetricSourceHandler` / `MetricCache` SPI 上，但嵌入式 `RuleEngineClient` 仍用旧 2 参 `EvalContextAssembler` → 仅 providedMetrics 生效、不取数。设计见 `specs/2026-06-06-sdk-fetch-design.md`（设计冻结 2026-06-06，本条落地）。

**决策**：

1. **复用 B21 富构造编排，零重写**：注入 handler 时 SDK 用 6 参富构造装配 `EvalContextAssembler`（服务端与 SDK 同一入口）；未注入则退化旧 2 参 providedMetrics-only——**默认行为不变**。不改 B21 任何签名、不改 rule-kernel（SDK 侧自行按 `@MetricSourceType` 归类 handler）。SDK 侧富构造传 `fetchTimeoutMs=0L`，**不设全局取数超时**（区别于服务端 D45 的全局超时）——各 handler 超时由宿主自行控制（SDK 不内置 handler，连接/超时属宿主职责，对齐 D-C）。

2. **metric 定义独立下发，不进 `rule_version` 快照**：SDK 本地 `MetricDefinitionRegistry`（`tenantId:metricCode → MetricDescriptor`，HTTP 热更整体替换）+ `SnapshotMetricDefinitionResolver`（B21 resolver SPI 的嵌入式实现，读 registry）。

3. **定义来源对称于 `RuleSource`**：`MetricDefinitionSource` SPI —— `DslMetricDefinitionSource` / `FileMetricDefinitionSource`（本地追加 put）/ `PollingMetricDefinitionSource`（HTTP 全量 replace）。HTTP 模式独立 `MetricDefinitionPoller` 复用 `pollInterval` 热更，端点 `GET /sdk/v1/metric-definitions`（仅下发元数据，不含凭证）。

4. **handler 由宿主注入，SDK 不内置 SQL/HTTP handler**：SDK 跑宿主进程，凭证/连接池属宿主职责。`RuleEngineClient.Builder` 加注入入口：`metricSourceHandler` / `metricDefinitionResolver` / `metricCache` / `fetchExecutor` / `metricDefinitionSource` / `localMetric`。

5. **配置错误 fail-fast**：配置了取数项（定义来源 / resolver / cache / executor）但未注入 handler → `build()` 抛 `IllegalArgumentException`，不静默 no-op。

6. **服务端下发 scope 按 scenes 收紧**：`MetadataService.listMetricDefinitions` —— `scenes` 为空（`FetchMode.ALL`）返回租户全部 ACTIVE 定义；`scenes` 非空（`FetchMode.DECLARED`）只返回「这些 scenes 下 ACTIVE rule_version 的 `metricDependencies` 并集」内的定义（口径对齐快照下发 `rv.status=ACTIVE`，保证 SDK 拿到的规则引用的 metric 定义无遗漏；不需 `scene_metric_binding` 表）。SDK 侧 `?tenantId=&scenes=` wire 契约不变，零改动。

7. **starter 自动注入**：`RuleEngineClientAutoConfiguration` 用 `ObjectProvider` 收集 handler/resolver/cache/定义来源 Bean 注入 Builder；无 Bean → fetch 不启用。

**不做的**：SDK 内置 SQL_AGGREGATE / EXTERNAL_HTTP handler（永远宿主提供）；定义冻进 `rule_version` 快照；HTTP 模式「本地算不了回源服务端评估」（破坏零网络/本地决策定位）；宿主 handler 的连接池/凭证管理。

**已实装**（D46 / B23）：`MetricDefinitionRegistry` + `SnapshotMetricDefinitionResolver`；`MetricDefinitionSource` SPI + `Dsl`/`File`/`Polling` 三实现 + `MetricDefinitionPoller`；`RuleEngineClient.Builder` 取数注入入口 + 富构造装配 + `close()` 停 poller + 无 handler fail-fast；服务端 `MetadataService.listMetricDefinitions` + `SdkMetricDefinitionController`；starter `ObjectProvider` 自动注入。

---

## D55. 场景输入参数清单（范围 B）：公开评估只收 payload + 输入清单发现/校验 ⭐⭐⭐

**背景**：依赖范围 A（payload 直接引用，`valueRef` 已把 payload 事实 / metric 指标分开）之上，给"对场景发事件"的调用方一个精确可校验的输入契约——查一次该场景要传哪些事件事实（名 + 类型），照着传 `payload`，剩下引擎自己搞定。设计见 `specs/2026-06-10-scene-input-manifest-design.md`（brainstorming 冻结 2026-06-10，本条落地）。

**决策**：

1. **范围 = B，依赖 A**：B（场景输入清单）建在 A（payload 直接引用）之上，B 的发布期收集 / 评估期校验都以 A 的 `valueRef=PAYLOAD` 节点为输入。

2. **公开评估只收 `payload`，`providedMetrics` 从公开接口移除**：`EvalEventRequest`（rule-api）删 `providedMetrics`，公开 HTTP 调用方碰不到引擎内部 metric taxonomy；metric 100% 引擎侧（按 `sourceType` 自取）。**内部 `RuleEvent`（rule-kernel）保留 `providedMetrics` 字段**，供嵌入式 SDK 宿主注入 / Job 预算这条**非公开**路径用（`EvalController` 构造 `RuleEvent` 时不再从请求体填，恒空）。D30 的 `providedMetrics` 语义在公开侧退场，仅存于内部链路。

3. **清单来源 = 发布期快照**：每条规则发布时把引用的 payload 字段（`valueRef=PAYLOAD` 节点）连同从 `Scene.payloadSchema` 取的 `dataType` / `required` 冻结进 `rule_version.payload_dependencies`（`List<PayloadDependency> = [{name, dataType, required}]`），与 `metric_dependencies` 同套路、守 D6（快照不可变 + 评估零额外查询）。随 `RuleVersionSnapshot.payloadDependencies` 下发到评估侧。场景级输入清单 = 该场景所有 ACTIVE 规则快照清单的并集（同名去重）。DB 迁移 V1_24 加 `rule_version.payload_dependencies` JSON 列（typed，`Jackson3TypeHandler`）。

4. **评估期整体校验**：评估入口按候选快照（可按 eventType 收窄）的 `payloadDependencies` 并集校验请求 `payload`——必填缺失 → `MISSING_REQUIRED_INPUT`（**整体拒绝 400，不降级**）；基础类型不符（`number→DECIMAL` / `integer→LONG` / `string→STRING` / `boolean→BOOLEAN`）→ `INPUT_TYPE_MISMATCH`（400）；多塞的未被引用字段忽略。经 `IllegalArgumentException → HTTP 400`，wire `errorCode=INVALID_ARGUMENT` + 语义码携于 message 前缀。与发布期 `UNRESOLVED_VARIABLE` 正交（一个管授权期越界引用，一个管调用期漏传 / 错类型）。

5. **新发现接口 + 删 getProvidedMetrics**：新增 `GET /api/v1/rule/scenes/{sceneCode}/input-manifest?tenantCode=xxx[&eventType=xxx]` → `ApiResponse.data.fields = [{name, dataType, required}]`（该场景 active 规则引用的 payload 字段并集，带 eventType 收窄）。作废 `MetadataService.getProvidedMetrics` + 其端点 `GET /admin/v1/scenes/{sceneCode}/provided-metrics`（公开侧无 provided metric 概念了）；`getSceneMetadata`（`/metadata`，配置侧元数据）保留。

**取舍（已接受）**：每个被规则引用的 metric 必须引擎可解析（真实数据源 / 从 subject 取），本地"用 providedMetrics 喂 metric"的偷懒法挪到 SDK/dev 非公开路径——换来公开调用方契约的彻底干净。清单是发布期快照，改规则后清单随发布更新（已发布版本用旧快照，符合 D6），非即时。

**已实装**（D55 / 范围 B）：`PayloadDependency` record + `RuleVersionSnapshot.payloadDependencies` + `AstJsonCodec`/`SnapshotAssembler` 序列化；DB 迁移 V1_24 + 实体列；`PublishService` 冻结 payload 依赖；`RuleVersionReadMapper` 三查询补列；`EvalEventRequest` 删 providedMetrics + 评估期 payload 入参校验（`MISSING_REQUIRED_INPUT` / `INPUT_TYPE_MISMATCH`）；input-manifest 发现接口；删 `getProvidedMetrics` 端点；`RuleBundle` 导入导出贯穿 payloadDependencies；示例 high-risk-login 纯 payload 化。

---

## D56. 规则草稿/版本生命周期重构：premise A 草稿即冻结快照 + publish 退化为激活 + 删草稿边界 + dry-run 二选一 ⭐⭐⭐

**背景**：D6（版本与灰度）+ D19（规则发布事务性）确立了"快照不可变 + 回滚 = 用旧版本快照建新草稿"，但现状实现把生命周期压成了一次性链路（createDraft 存 raw 未解析草稿 → publish 时才解析 + INSERT 新 ACTIVE 行 → 之后只能 disable），且 dry-run 复用评估主链路会落 `evaluation_session` / 派发 action（副作用 bug）。本决策补全生命周期、把"草稿即完整冻结快照"立为 premise，并重设计 dry-run。设计见 `specs/2026-06-10-rule-draft-version-lifecycle-design.md`（设计冻结 2026-06-10，本条落地）。

**决策**：

1. **生命周期补全**：规则生命周期方法集补全为 createDraft / editDraft / newVersion / rollback / publish / deleteRule / deleteDraftVersion——
   - `createDraft`：建 v1 DRAFT 行。
   - `editDraft`：原地更新当前最新 DRAFT 行内容，**不增版本号**（同一 versionId、同一 version）。
   - `newVersion`：对已发布规则出 `v_max+1` DRAFT；要求当前**无未发布 DRAFT**（先发布或删掉在途草稿，避免多个并行草稿）。
   - `rollback`：= `newVersion` 带 `fromVersionId`——克隆指定旧版本的内容、按**当前世界重新解析**（metric/decision/payload 的现状），产出新 DRAFT；激活仍走显式 `publish`（不自动上线）。这是 D19"回滚 = 用旧版本快照建新草稿"的精确落地：克隆的是输入意图，冻结的是当前世界的解析结果。
   - `publish` / `deleteRule` / `deleteDraftVersion`：见第 3、4 条。

2. **premise A：草稿即完整冻结快照**——草稿在 **create / edit / newVersion 时**就跑全套 `resolveAndValidate`（解析 + 硬校验）：metric 必须 ACTIVE、payload 字段必须在 `scene.payloadSchema` 声明、decision 必须存在、kind 结构校验（SCORECARD 根/权重、DECISION_TREE 结构、DECISION_TABLE 行列一致）、算子×dataType 校验。**任一校验不过即拒绝建/改草稿**（抛 `IllegalArgumentException` → 400）。落库的 DRAFT 行已是冻结快照：`resolvedAst` 含 dataType、`metricDependencies`/`payloadDependencies` 已冻、`decisionBindings` 含 `name`/`actions`、`triggerEventTypes`/`preGates` 已规整。校验从发布期前移到草稿写入期，配置错误第一时间暴露，dry-run 试跑的就是发布后一模一样的快照。

3. **publish 退化为激活**：publish 不再 INSERT 新 ACTIVE 行、不再重解析——把当前最新 DRAFT 行**原地翻 ACTIVE**（version 不变），supersede 旧 ACTIVE 行，发 `RulePublishedEvent` 触发 eval 侧索引热更。**版本号只在 createDraft（v1）/ newVersion（v_max+1）产生**；editDraft 原地更新不增、publish 激活不增。这相对现状是最大行为变化（现状 publish 插一条新 version=max+1 的 ACTIVE 行并保留 DRAFT）。

4. **triggerEventTypes 冻结口径**：发布落库的 `triggerEventTypes` 是草稿**自己声明的值**（写入草稿时已校验 ⊆ `scene.eventTypes`），不再像现状那样 publish 时覆盖成 `scene.getEventTypes()` 全集——保证"预览（dry-run 草稿）的 == 发布的"。空 `triggerEventTypes` 仍归 eval 侧 `*` 通配桶。

5. **dry-run 重设计为 ruleId / ruleVersionId 二选一必传**：`POST /api/v1/rule/dry-run` 改为二者择一必传——传 `ruleVersionId` 试跑该精确版本；传 `ruleId` 取该规则**最新版本**（最高版本号，含 DRAFT）；两者都不传 → 400 `MISSING_DRYRUN_TARGET`。结构上恒走"带版本单快照"分支 → dry-run **不落 `evaluation_session`、不派发 action**（从结构上根除旧实现复用评估主链路带来的副作用 bug，而非靠 `isDryRun` 标志逐处门控）。dry-run 痕迹仍按需落 `dry_run_session` / `dry_run_node_trace`（D7 试算观测，与正式评估隔离）。

6. **删草稿边界**：
   - `deleteRule` 仅删**从未发布过**的规则（无 ACTIVE/SUPERSEDED 版本），级联删 `rule_definition` + 其全部 `rule_version`。
   - `deleteDraftVersion` 仅删 **DRAFT** 版本行。
   - 碰 ACTIVE / SUPERSEDED 版本一律**拒绝**（已上线/曾上线的版本只能 `disable`，保留审计与可回滚历史）。
   - **级联范围只 `rule_version`**：草稿出站引用（metric/decision/scene/payload）全是 `rule_version` 冻结快照内的值，删行即净；入站 dry-run 痕迹（`dry_run_session`/`dry_run_node_trace`，按 ruleVersionId 关联）**不级联删**——视同审计历史，靠 `SessionRetentionCleaner` TTL 退休（详见 spec §六）。资源（metric/decision/scene）无硬删除接口，删草稿不影响被引用实体。

**取舍（已接受）**：草稿写入期跑全套解析校验，建/改草稿比"raw 存草稿"重（多查 metric/decision/scene），换来"草稿即真实快照 + dry-run == 发布"的确定性与副作用根除；newVersion 要求无在途 DRAFT（单草稿约束），简化版本状态机，多并行草稿是伪需求。

**已实装**（D56）：`PublishService` 抽 `resolveAndValidate` + `ResolvedDraft` record；`publish` 改原地激活；新增 `editDraft`/`newVersion`/`deleteRule`/`deleteDraftVersion`；`RuleVersion.draftV1`（raw 草稿工厂）删除；`RuleVersionMapper` 补 `findByIdAndRule`/`hasNonDraftVersion`/`deleteByRuleDefinitionId`；`ConfigService` 接口扩展；`RuleController` 加 PUT `/draft`、POST `/versions`、DELETE `/{ruleId}`、DELETE `/{ruleId}/versions/{versionId}`；`EvalService.dryRun` 改签名 `(event, ruleId, ruleVersionId)` + 恒走带版本单快照分支（不落 session、不派发 action）；`RuleVersionReadMapper.latestVersionIdByRule`；`MISSING_DRYRUN_TARGET` 错误码。

---

## D59. 规则身份模型：逻辑键 `(tenant, sceneCode, code, version)` + 代理键 `ruleVersionId` 并存（Camunda 补充模式）⭐⭐⭐

**背景**：现状规则的唯一身份是代理键 `ruleVersionId`（一个无业务含义的 long），它贯穿存储主键 / 外键 / 评估去重 / 幂等。但代理键不可人读：trace / audit 里只能看到一串数字，排障时无法直接判断"这是哪条业务规则的第几版"；SDK 注解模式（D40）让开发者用 `@RuleDef` 按名声明规则，"名字"（业务码 + 版本）才是开发者心智里的身份，逼其手填代理键 `id` 既反直觉又易撞号。需要在不动代理键存储角色的前提下，补上一层人可读、可溯源、名字驱动的逻辑身份。

**决策**：

1. **逻辑键 + 代理键并存（supplement，不替换）**：规则的逻辑身份 = `(tenant, sceneCode, code, version)`——`code`（业务规则码，String）+ `version`（版本号，long）为业务自然键，配合 tenant / sceneCode 作用域。代理键 `ruleVersionId` 全部保留，继续承担存储主键 / 外键 / 评估去重 / 幂等键角色。两者**共存互补**，不是二选一。

2. **OSS 先例（Camunda 补充模式）**：Camunda 流程定义同时持有 `id`（代理 PK）+ `(key, version)`（逻辑键），K8s 对象同时有 `uid`（代理）+ `(namespace, name)`（自然键），Confluent Schema Registry 同时有 `id`（代理）+ `(subject, version)`（逻辑键）——业界惯例是**保留代理主键 + 反范式冗余自然键**，让人读路径与存储路径各取所需。本决策对齐该模式。

3. **承载链路**：`RuleVersionSnapshot` 补 `code`(String) + `version`(long)；`NodeTrace` 补 `ruleCode` + `ruleVersion`；`Decision` 补 `fromRuleCode` + `fromRuleVersion`——全部**与既有 `ruleVersionId` 字段并列保留**，不删代理键。配置读路径（`RuleVersionReadMapper`）JOIN `rule_definition.code` + `rule_version.version` 填进快照，`PublishService` 落库时一并写。trace / audit 表 `node_trace` / `dry_run_session` / `dry_run_node_trace` 加可空列 `rule_code` / `rule_version`（迁移 V1_26），由 trace writer 落库，admin trace 接口（`GET /admin/v1/evaluation-sessions/{id}/trace` 与 `/trace/tree`）响应 VO 透出。

4. **注解身份名字驱动**：`@RuleDef` 注解去掉 `long id()`，改为 `String code()`（逻辑身份）+ `long version() default 1`；`tenantId()` 默认 `""`（空 = 继承租户，但继承仅在 Spring starter 自动装配读 `rule.sdk.tenant-id`、或显式双参 `new AnnotationRuleSource(specs, tenant)` 时生效；非 Spring 单参 `new AnnotationRuleSource(specs)` 留空得空租户 `""`，builder 的 `tenantId` 不注入 rule source）。代理键 `ruleVersionId` 不再由开发者手填，而由 `AnnotationRuleSource` 按 `(tenant, scene, code)` 稳定哈希派生——名字是真相源，代理键是其确定性投影。

5. **范围拆分 阶段甲 / 阶段乙**：
   - **阶段甲（本次落地）**：核心模型（snapshot / trace / decision 携带 code+version）+ trace / audit 透出 + 注解身份改造。
   - **阶段乙（未来，未做）**：admin / config API 按 `(code, version)` 寻址（按业务码 + 版本号读写规则，而非按代理键 id）。本次**不做**，独立推进。

**显式不做**：**不移除代理键 PK**。代理键在存储 / 外键 / 去重 / 幂等上的角色不可被自然键取代（自然键多列、可空、跨表 JOIN 成本高），逻辑键只是补充人读与名字身份，二者职责正交。

**已实装**（阶段甲）：`RuleVersionSnapshot.code/version` + `NodeTrace.ruleCode/ruleVersion` + `Decision.fromRuleCode/fromRuleVersion`；`RuleVersionReadMapper` JOIN code/version、`PublishService` 落库；迁移 V1_26 加 `node_trace`/`dry_run_session`/`dry_run_node_trace` 的 `rule_code`/`rule_version` 可空列 + trace writer 落库 + admin trace 端点 VO 透出；`@RuleDef` 删 `id()`、加 `code()`/`version() default 1`、`tenantId() default ""`（空继承租户，仅 Spring starter 或显式双参构造时生效，单参非 Spring 留空得空租户）；`AnnotationRuleSource` 按 `(tenant,scene,code)` 稳定哈希派生 `ruleVersionId`；`Condition.of(conditionType, params)` 双参重载（无绑定 metric 的自定义算子）。

---

## D60. 规则引擎纯决策化，移除动作子系统 ⭐⭐⭐

**背景/理由**：对标业界"策略/决策与编排分层"惯例——OPA（Open Policy Agent）由策略引擎出**决策**，由 PEP（Policy Enforcement Point）执行；Camunda 以 DMN 出**决策**、BPMN 做**编排**。本引擎的本职是"给定输入产出决策"，"命中后做什么"（发券 / 拦截 / 通知 / 调外部系统）是**编排/执行层**职责，不应内嵌进决策引擎。动作子系统（ActionHandler SPI + 派发 + 落库 + 配置）一直是引擎里耦合最重、与"纯决策"定位最摩擦的部分（D16/D18/D27/D28/D53/D54/D57 的反复收敛即其代价）。本决策把引擎收敛为**纯决策**：引擎只产出 Decision，编排后续接开源流程引擎（首选 **Flowable**，以 Service Task / HTTP Task 把本引擎当一个决策节点调用），决策与编排彻底分层。

**范围（已删除，代码已落地）**：

1. **kernel SPI / 模型**：删 `ActionHandler` SPI、`@ActionType` 注解、`ActionContext`、`ActionResult`；删 `Decision.actions`、`EvalResult.actionResults`、`RuleVersionSnapshot.DecisionAction`、`DecisionBinding.actions`。
2. **eval-svc 派发**：删动作派发服务（dispatch service）/ `SendAlertHandler` / `ActionCommandChannel` / `action_execution` 落库持久化。（注：`EvalActionDispatcher` 实为 PUSH 评估队列，**保留**，与动作无关。）
3. **config / api 动作配置**：删 `decision_definition.actions`、`DecisionService` 的 actions 写入、`MetadataService.actionTypes` / `ActionTypeMeta`、bundle 导入导出的 actions 贯穿、`PublishService` 的 action 冻结，以及一处随之失效的 PULL-scene "Decision 不得挂 action" 发布校验（原系于 D27/D54）。
4. **DB（迁移 V1_27）**：drop `action_execution` 表 + `decision_definition.actions` 列。

**取代 / 作废的历史决策**（显式声明）：

- **D4（动作协议）** — 作废：动作协议是动作子系统的根决策（声明式优先 + SPI 兜底）；动作整体移除后，"运营如何配动作"议题不复存在，编排交流程引擎（D4 本就把工作流引擎接入留到 v2 接 Camunda / Flowable）。
- **D16（链式触发与事件环）** — 作废：链式触发是 ActionHandler 能否产生新事件的问题，动作子系统已删，议题消失。
- **D18（Action 失败补偿语义）** — 作废：多 Action 失败传播 / failFast / 补偿语义随动作派发一并移除。
- **D27（Action 归属从 Rule 迁移到 Decision）** — 作废：Action 不再属于任何实体，Decision 仅承载决策码 / priority / name。
- **D28（Decision.actions 变更生效时机）** — 作废：`Decision.actions` 字段已删，快照生效时机议题消失。
- **D57（删 BlockTransactionHandler，无通用阻断动作）** — 作废：其前提（保留 `SendAlertHandler` 等内置 ActionHandler）已不成立，整套 ActionHandler SPI 移除。
- 顺带退役 **D27/D54** 引入的 PULL-scene action 发布校验（见上"范围"第 3 条）。

> 说明：D53（Action 投递 best-effort 化）的 retry/补偿收敛已先期完成，本决策在其基础上整体移除动作子系统；D54 中 decision.actions 派发与两张 binding 表的收敛亦随本决策彻底退役 action 端。

**保留**：

- **决策输出**：`evaluate()` 返回 `EvalResult`（含 `finalDecision` / `hitDecisions`），SDK 侧 `EvalResultListener` / `EvalSessionListener` 决策输出钩子不变。
- **PULL / PUSH 双模**：PULL 返回决策；PUSH 仍异步评估 + 落库 `evaluation_session` / trace，**仅去掉动作派发**（评估完即止，不再 dispatch）。
- Decision 实体（Tenant 级，`code` / `priority` / `name`）+ `RuleDecisionBinding` + `decisionStrategy` 合成（D26/D29/D41）不变。

**行为现状**：引擎只产出决策；"命中后做什么"交给消费方 / 流程引擎（Flowable 为预期搭档，独立项目，以 Service/HTTP Task 调用本引擎决策节点）。

---

## 附：决策汇总表

| # | 决策 | 你的选择 | 备注              |
|---|------|------|-----------------|
| D1 | 第一阶段场景定位 | A | v1 起步 A（运营/营销/活动），抽象按 B（风控）级别预留；演进优先级 B>A>C>D |
| D2 | 规则表达式语言 | A    |                 |
| D3 | 多租户模型 | A    |                 |
| D4 | 动作协议 | A    |                 |
| D5 | 触发模型 | B    |                 |
| D6 | 版本与灰度 | A    |                 |
| D7 | Dry-run 试算 | A    |                 |
| D8 | 性能目标 | BA   | 按优先级设计，后续可扩展    |
| D9 | 持久化分层 | A    |                 |
| D10 | AI 评估节点 | B    |                 |
| D11 | Job 模式 + 调度器 | B    | Scheduler 接口化，xxl-job 首个实现，后续可换 |
| D12 | Rule.kind + 输出多态预留 | A    | 评分卡/决策树/决策表/脚本演进的 schema 占位，v1 仅实现 AST_BOOLEAN |
| D13 | Scene 元数据 schema | A    | 4 字段：payloadSchema / subjectType / defaultParams / eventTypes；类型级 params schema 留到 04-extension |
| D14 | 权限与审计 | A    | 占位字段 + audit_log 表；不内置 RBAC，鉴权交上游网关 |
| D15 | 评估失败语义 | A    | 单节点降级 false + 整树继续 + EvalResult.errorCode 槽位；规则间隔离；四态对账：HIT / MISS / BLOCKED / ERROR |
| D16 | 链式触发 | A    | 显式禁止 Action 产引擎事件；ActionHandler 不返回事件；业务自走外部 MQ 链式 |
| D17 | 配置热加载 | A    | DB 轮询 15s + 评估快照锁定 + RuleVersionWatcher 接口预留 |
| D18 | Action 失败补偿语义 | B    | 默认 continue-on-error，Action 级可声明 failFast；单 Action 失败不影响 **Decision** 内其他 Action（D27 迁移后语义）；补偿不自动触发由外部调度 |
| D19 | 规则发布事务性 | A    | 单条规则原子发布（状态机：DRAFT → PUBLISHING → PUBLISHED / PUBLISH_FAILED）；批量发布前端逐条提交；回滚 = 用旧版本快照建新草稿 |
| D20 | v1 高吞吐评估期落地范围 | A    | metric 批量预拉 + 异步 Dispatcher + 输入闭合校验 + Watcher SPI 多态化；预编译 Predicate SPI 预留 v1.5 切换；alpha 共享 / 嵌入式 SDK / EXPRESSION 叶子留 08-evolution |
| D21 | 评估观测数据异步写入 | B    | `TraceWriter` 异步批写（队列 + 消费者池 + batch insert，复用 D20 §2 模型）；与 `audit_log` 同步事务严格分离；失败降级丢弃 + 告警，不影响 EvalResult；ConditionNode 与 Pre-Gate trace 同通道；运维参数留 07-operability |
| D22 | Pre-Gate 拦截对账状态 | A    | 引入第四态 `BLOCKED`；四态：`HIT / MISS / BLOCKED / ERROR`；Pre-Gate 拦截 → BLOCKED；`evaluation_session.blocked_by` 记录拦截 Gate 类型；命中率分母仅含 HIT+MISS |
| D23 | `evaluation_session` 幂等键语义 | A    | `(tenant_id, event_id)` 永远只评估一次，by design；Replay 换新 eventId；版本切换后测新规则走 dry-run |
| D24 | Scene 变更热加载 | B    | 新增独立 `SceneWatcher` SPI；v1 实现 `DbPollingSceneWatcher`（30s 轮询）；与 `RuleVersionWatcher` 平级，职责独立 |
| D25 | Context 构建并发模型 | C    | `CompletableFuture.allOf()` 并行 + 各 MetricSource 自管执行资源；Subject 加载与 metric 并行；单 metric 失败归 D15 METRIC_FETCH_FAIL，Subject 失败整 Context 失败；`SubjectLoader` SPI（v1 实现 `UserProfileLoader`） |
| D26 | Decision 实体 + 多规则命中合成策略 | B    | Decision 为 Tenant 级一等实体；`RuleDecisionBinding` 关联表（支持可选 score 区间）；Scene 声明 `decisionStrategy`（v1 仅 `HIGHEST_PRIORITY`）；Action 归属见 D27 |
| D27 | Action 归属从 Rule 迁移到 Decision | B    | Action 完全挂到 Decision，Rule 不再持 actions 字段；finalDecision.actions 被派发；幂等键变更为 `(tenantId, eventId, decisionCode, actionId)`；PULL Scene Decision 不配 Action 约束不变 |
| D28 | Decision.actions 变更生效时机 | A    | 快照语义不变；UI 在修改 Decision.actions 时提示引用该 Decision 的已发布规则需重新发布 |
| D29 | PUSH/HYBRID Scene 的 decisionStrategy 默认值 | B    | PUSH/HYBRID Scene 缺省等价 `HIGHEST_PRIORITY`，消灭 actions 静默不派发问题；PULL Scene 不参与合成 |
| D30 | providedMetrics — 业务方随评估携带指标值 | A    | 评估请求携带 `providedMetrics`；Metric 级 `allowProvided` 控制是否可被覆盖；只活在本次评估，不持久化 |
| D31 | 前端技术栈 | A    | React 18 + react-querybuilder + Ant Design 5 + Vite + Zustand；前端工程放 `frontend/` 目录，与 `src/` 平级 |
| D32 | ArchUnit 版本与 rule-kernel 编译目标 | B（临时） | ArchUnit 1.4.0 + rule-kernel maven.compiler.release=21；升级至 ArchUnit 1.5+ 后需删除 override |
| D33 | Modulith verify() 不适用于多 JAR 共享库 | A | rule-kernel 是跨模块共享库（SPI+模型），Modulith 在多 JAR 结构下将其视为 Modulith 模块导致 exposed 检查误报；骨架阶段跳过 verify()，架构边界由 ArchUnit（KernelArchTest）保证；等 v2 业务实现时视 Modulith 版本再评估是否启用 verify() |
| D34 | 嵌入式 SDK 本地模式（代码定义规则，零网络） | A | `RuleEngineClient.Builder.localSnapshot()` 直接写入本地索引，不启动 SnapshotPoller；`RuleVersionSnapshot` 补 Builder 辅助类；适用单测/演示/离线部署 |
| D39 | Spring Boot Starter 补完 | A | 文件模式（`rule-files`）+ `@ConditionType` Bean 自动扫描 + `EvalResultListener`/`EvalSessionListener` Bean 注入；三项均委托现有 Builder API，starter 零额外逻辑 |
| D40 | SDK 注解模式（`@RuleDef`） | A | `InlineRuleSpec` 接口 + `@RuleDef/@Decision` 注解 + `AnnotationRuleSource`；Spring Starter 自动收集 Bean；`ruleVersionId` 调用方显式指定保证幂等 |
| D41 | `executionStrategy` 扩展 | A | 新增 `ALL_HITS`（全部命中）/ `FIRST_HIT`（短路）；`EvalResult.hitDecisions()` 直接复用；Flyway 无 DDL 改动 |
| D42 | `DECISION_TREE` / `DECISION_TABLE` evaluator | A | 新增 `IfNode`/`DecisionLeafNode` AST 节点；独立 Executor SPI 实现；`EvalResult` 补 `category`/`decision` 字段；`EXPRESSION_SCRIPT` 留 v1.5 |
| D43 | 灰度收口 pre_gates ROLLOUT，废弃 `rollout` 列 | A | 灰度由 ROLLOUT pre-gate 承载（percentage/bucketStart/bucketEnd/experimentId）；`V1_4` 删 `rollout` 列；桶区间+experimentId 实现一致分桶/互斥 + 发布期校验；USER_TAG/HYBRID 标签命中留演进 |
| D44 | B20 时间框架：EvalContext.now 注入 + DATE/DATETIME 一等 dataType + 时间条件内置 | A | now 单次注入+单时钟约束；TimeZoneResolver（字面偏移>params.timezone>UTC，Scene级暂缓）；DATE/DATETIME 纯策略+两阶段管线；PlaceholderResolver($now/$today，不含相对时长)；context_snapshot 嵌套格式；time.window/time.occurred_at 注册；V1_5 扩展 ENUM |
| D47 | D11 Job 模式落地 | A | 独立模块 `rule-job-svc`；`Scheduler` SPI 精简为 `schedule/unschedule`（cron→Runnable），手动触发/运行记录查询等管理能力上移 `JobService`；首个实现 `ThreadPoolSchedulerAdapter`（进程内 `ThreadPoolTaskScheduler`+`CronTrigger`，单实例，多实例需选主/xxl-job，作已知限制）；subjectQuery 首期仅 `type=SQL`（MyBatis `@Select` 跑配置化只读 SQL，EXTERNAL_HTTP/METRIC_RESULT 后续）；`job_definition` 以 `scene_code` 关联（非 scene_id），对齐 RuleEvent/SceneService 口径；注入点 `EvalService.acceptEvent`（PUSH 语义）；`eventId=murmur3(jobRunId+":"+subjectId)` 复用 `evaluation_session(tenant_id,event_id)` uk 幂等；迁移 V1_7 |
| D48 | Job 改 `@RuleJob` 注解驱动、取数砍 SQL | A | 修订 D47 的 subjectQuery 方案：Job 定义由 `@RuleJob` 注解驱动——开发者在 Spring Bean 方法标注（code/cron/tenant/scene/eventType），方法体即自定义主体查询（返回含 subjectId 行，如查近期登录用户）；启动期 `RuleJobScanner` 扫描 upsert `job_definition`（`subjectQuery.type=BEAN_METHOD`, `ref=<bean>#<method>`）+ 注册调度。砍掉 SQL 类型（主体多在业务库、job DataSource 够不着，`SqlSubjectQuerySource`/`SubjectQueryMapper` 删）+ 砍按 type 分发抽象（仅 BEAN_METHOD，YAGNI，多 type 再加回）。去掉 createJob API（subjectQuery 是代码 ref，运营配不了），`/admin/v1/jobs` 仅管理类（list/get/enable/disable/trigger/executions），PULL 校验保留 `enableJob`；规则仍由运营事先经 Scene/Rule API 配，Job 只定时触发评估 |
| D49 | 统一 RuleEvent 产生：渠道(source)/模式(mode) 拆分 + builder 构造 | A | `RuleEvent` 加 `source`（EventSource 渠道）+ Lombok `@Builder(toBuilder)`；评估 `mode`(PUSH/PULL) 由 EvalService 入口判定（acceptEvent=PUSH / evaluate·dryRun=PULL）写 session；`evaluation_session` 拆 `source`(渠道 ENUM HTTP/MQ/JOB/SDK/REPLAY)/`mode`(PUSH/PULL) 两列（V1_8），原 source(PUSH/PULL/REPLAY) 语义并入 mode。`source` 由注入入口权威设置、不信外部 JSON（HTTP→controller、Job→JobRunner、SDK→client 各自钉死）；三路径统一经 `RuleEvent.builder` 构造，无散落 `new RuleEvent`。Job 主体类型 `Subject`→`JobTarget`（携 payload/providedMetrics，删 PayloadTemplateRenderer 占位符渲染）。kernel 首次引 Lombok（编译期注解处理器，不破坏运行时零依赖 / GraalVM Native） |
| D50 | scene_action_binding 写 API + 移除 rate_limit_override + 接 default_params | A | 新增 binding 写 API（config-svc `SceneActionBindingService.replace` 整组覆盖 + `/admin/v1/scenes/{sceneCode}/action-bindings` GET/PUT），写后发 `SceneChangedEvent`（active=场景真实状态）闭合 `SceneActionBindingIndex` 失效缺口。**移除 `rate_limit_override`**（V1_14 DROP COLUMN）：action 级频控无消费方且冗余——Job 注入端已控速率（§5.4 rateLimit），实时 PUSH 是业务速率，限流共享下游需分布式且更该贴 ActionHandler 内部（引擎保持下游无关）；真需限流走 handler 内部或上分布式。**接 default_params**：`SceneActionBindingIndex` 装载时解析 `default_params` JSON→`Map<String,Object>` 缓存，`ActionDispatchService` 传入 `ActionContext.params`（04-extension §3.4「以 default_params 为底」）。边界 JSON 字段统一 `Map<String,Object>`（禁 JSON String/裸 Object）；DTO↔service 转换走 MapStruct convert 包 |
| D52 | Pre-Gate 收敛为仅 ROLLOUT | A | Pre-Gate 最终只保留 `ROLLOUT`（无状态 murmur3 分桶，无依赖）。**移除 `RATE_LIMIT`/`MUTEX`**：二者有状态（计数窗口/并发锁）需引擎持有分布式状态（Redis），打破"无状态评估"假设；greenfield 阶段不引该架构依赖，且当前是静默 fail-open 的伪能力，砍掉，未来真做分布式状态时单独设计。**黑白名单转 BOOLEAN metric + condition**（不再是 pre-gate）：① 时序——pre-gate 在 EvalContext 装配**之前**，metric 在 Context 阶段才取数，pre-gate 拿不到 metric；② 语义——按 subjectId 查名单判成员本就是 metric 的活，该走 metric 治理（取数/缓存/版本/allowProvided/影响面）。落地形态：名单 metric（`sourceType=SQL_AGGREGATE`、`dataType=BOOLEAN`、`SELECT EXISTS(... WHERE list_key=:listKey AND subject_id=:subjectId)`）+ 规则 `EQ(in_blacklist,true)`，复用现有 metric 机制无需写 pre-gate 代码。**堵 fail-open**：`EvalEngine.applyPreGates` 改 fail-closed（未注册 gateType 视为拦截）；发布期校验 `pre_gates[].gateType` 必须有注册 PreGate 实现（现仅 ROLLOUT 合法），配已砍/未实装 gate 一律发布拒绝。**对账语义取舍**：黑白名单拦截从 `BLOCKED`（pre-gate）变为 `MISS`（condition 不满足），`blocked_by` 合法值收敛为仅 `ROLLOUT`，要区分靠 node_trace 看具体 condition；失去"白名单用户直接跳过评估"的短路优化（白名单用户也进评估，多取一个 BOOLEAN metric，开销可控）。D22 四态/BLOCKED 桶语义不变（ROLLOUT 拦截仍归 BLOCKED） |
| D53 | Action 投递 best-effort 化（砍应用层 retry/补偿） | A | Action 命中后投递语义钉死为 **best-effort fire-and-forget**：命中 → 派发 → 进程内队列异步跑 handler → 落 `action_execution`；队列满/进程重启会丢，不重试、不补偿、不保证投递。**砍应用层 retry/补偿建模**（与未来方案确定不复用、非预留）：DB（V1_21）drop `action_execution` 列 `retryable`/`retry_count`/`compensated`/`compensated_at`/`compensated_by` + 索引 `idx_status_retryable`；SPI 删 `ActionHandler.compensate()` 默认方法（保留 `execute`/`dryRun`）；删 `QUEUE_OVERFLOW` errorCode 承诺。**砍进程内幂等缓存**：删 `ActionIdempotencyGuard`/`CaffeineActionIdempotencyGuard`/`ActionIdempotencyProperties` + `ActionDispatchService` 的 claim/release——重复防护降级为落库 `uk_idempotency`（ON DUPLICATE KEY 吞重），不防"handler 被重复执行"（best-effort 接受，未来 MQ 消费端再做幂等）。**真实化 `SendAlertHandler`**：`SEND_ALERT` 由 stub 改真实 HTTP webhook（`engine.rule.action.send-alert.*` 可配 URL/短超时，POST 告警载荷；2xx=SUCCESS/非2xx/失败=FAILED 不重试/无 URL=SKIPPED），dryRun 仅预览；`BlockTransactionHandler` 留 stub。**队列满可观测**：`InProcessAsyncCommandChannel` 队列满保留丢弃，但加累计丢弃计数 + WARN（不静默）。保留 `action_execution` 主表 + `uk_idempotency` + `dryRun` SPI + `ActionResult.retryable` 字段（kernel record，未删，仅不再驱动重试队列）。**未来方向钉死**：可靠投递=MQ（at-least-once 由 MQ 保证，不在应用层做重试表/重发）；业务补偿=saga/补偿事务（不复用本次删的 `compensate()` SPI / `compensated` 列）。D18/D20 历史条目记当时设计，本决策收敛覆盖其中 retry/补偿/QUEUE_OVERFLOW 部分（failFast 多 action 失败传播语义不变，仍保留） |
| D54 | 配置闭环 B 轮：补齐 D27 + 砍两张 binding 表 | A | **补齐 D27（decision.actions 接进派发，实装）**：`DecisionBinding` 快照扩 `name`/`actions`（发布期从 `decision_definition` 冻结，方案甲——守 D6 不可变 + 评估零额外查询，改 decision 需重发生效）；`Decision` 加 `actions` 字段；`EvalEngine.resolveRuleDecisions` + Tree/Table executor 从 binding 读 `name`/`actions`（**修 `finalDecision.name` 永远空串真 bug**）；`ActionDispatchService` 改读 **finalDecision.actions** 派发（不再 `hitDecisions × scene_action_binding` 笛卡尔积），`DispatchActionsCommand` 携带 `finalDecision`；发布期新增 **DECISION_CODE_NOT_FOUND** 校验（rule 绑定的 decisionCode 必须在 `decision_definition` 存在）；新增 **decision tenant 级写 API**（`/admin/v1/decisions` CRUD + actions + 审计）。**触发源单一性**：action 触发唯一来源 = decision（tenant 级，与 scene 无关），否 D27 当年的 C 方案（两层并存）。**决策二 砍 metric binding**：drop `scene_metric_binding`（V1_22），metric 在 tenant 级对所有 scene 可用。**决策三 砍 scene_action_binding 整表**（V1_23）：action 端到端 tenant 级、与 scene 无关，该表退化为纯白名单后是 action 最后残留的 scene 耦合，鸡肋；连同 **D50 写 API 作废**、`scene_action_binding.default_params` 移除。actionType 合法性降级为**运行期 NO_HANDLER skip**（与 D53 best-effort 一致），不在发布期校验，`ACTION_TYPE_NOT_BOUND` errorCode 删除。D26/D27/D50 历史条目记当时设计，本决策收敛覆盖（D27 由"待实装"转"已实装"；D50 作废）。取舍：放弃 scene 级 action 差异化（伪需求，走不同 decision 解决）+ scene 级 metric/action 治理白名单 |
| D51 | 剩余 DB ENUM 列全面 VARCHAR 化（R10） | A | 承 V1_11（metric source_type/data_type/status）、V1_15（rule_definition/rule_version/scene status）后，将剩余全部 MySQL `ENUM` 列改 `VARCHAR`：tenant.status、scene.dominant_mode/decision_strategy/subject_type、decision_definition.status、rule_definition.kind/rule_version.kind、audit_log.actor_type（V1_16）；evaluation_session.source/mode/status、action_execution.status、dry_run_session.status/trigger（V1_17）；node_trace/dry_run_node_trace.value_source（V1_18）；job_definition.status/job_execution.status（V1_19）。取值真相源统一在 app 层 Java enum，按 `name()` 与列往返（MyBatis-Plus 全局 `MybatisEnumTypeHandler`）；契约边界 `.name()` 保持 String。封闭取值复用 kernel `SubjectType`(+CUSTOM)/`RuleKind`/`EventSource`/`ValueSource`/`ActionResult.ActionStatus`，新建 `TenantStatus`/`DecisionStatus`/`DominantMode`/`DecisionStrategy`/`ActorType`/`SessionStatus`/`EvalMode`/`JobStatus`/`JobExecutionStatus`。`dry_run_session.trigger` 无实体字段（纯列改型）。理由同 V1_11：ENUM 加值需 ALTER+双重定义，VARCHAR 后单一真相源、增删枚举项零迁移风险。至此 DB 不再保留 MySQL ENUM 列 |
| D55 | 场景输入参数清单（范围 B）：公开评估只收 payload + 输入清单发现/校验 | A | 依赖范围 A（payload 直接引用）。**公开评估只收 `payload`**：`EvalEventRequest` 删 `providedMetrics`，metric 全归引擎；内部 `RuleEvent` 保留 `providedMetrics` 供 SDK/Job 非公开注入。**清单来源 = 发布期快照**：规则发布冻结引用的 `valueRef=PAYLOAD` 字段进 `rule_version.payload_dependencies`（`[{name,dataType,required}]`，V1_24 加列），随 `RuleVersionSnapshot.payloadDependencies` 下发；场景级清单 = 该场景 ACTIVE 规则清单并集。**评估期整体校验**：缺必填 → `MISSING_REQUIRED_INPUT`（拒绝 400 不降级），类型不符 → `INPUT_TYPE_MISMATCH`（400），多塞忽略；经 `IllegalArgumentException → 400`，wire `errorCode=INVALID_ARGUMENT` + 语义码携于 message；与发布期 `UNRESOLVED_VARIABLE` 正交。**新发现接口** `GET /api/v1/rule/scenes/{sceneCode}/input-manifest`（`ApiResponse.data.fields`）；**删 `getProvidedMetrics`** 端点（`/admin/v1/scenes/{sceneCode}/provided-metrics`），`/metadata` 保留。设计见 `specs/2026-06-10-scene-input-manifest-design.md` |
| D56 | 规则草稿/版本生命周期重构：premise A 草稿即冻结快照 + publish 退化为激活 + 删草稿边界 + dry-run 二选一 | A | 落地 D6/D19。**生命周期补全**：createDraft(v1 DRAFT)/editDraft(原地更新最新 DRAFT，不增版本)/newVersion(已发布出 v_max+1 DRAFT，要求无在途 DRAFT)/rollback(=newVersion 带 fromVersionId，克隆旧版本按当前世界重解析→DRAFT，激活仍走 publish)/publish/deleteRule/deleteDraftVersion。**premise A 草稿即冻结快照**：草稿在 create/edit/newVersion 时即跑全套 `resolveAndValidate`（metric 须 ACTIVE、payload 须在 scene.payloadSchema 声明、decision 须存在、kind 结构 + 算子×dataType 校验），不过即拒；落库 DRAFT 已含 resolvedAst(dataType)/metric·payloadDependencies/decisionBindings(name·actions)。**publish 退化为激活**：把最新 DRAFT 行原地翻 ACTIVE（不增版本、不重解析），supersede 旧 ACTIVE，发 `RulePublishedEvent`；版本号只在 createDraft(v1)/newVersion(+1) 产生。**triggerEventTypes 冻结草稿声明值**（已校验 ⊆ scene.eventTypes，不再覆盖成 scene 全集），保「预览==发布」。**dry-run 二选一必传**：`POST /api/v1/rule/dry-run` 传 ruleVersionId(精确版本)/ruleId(取最新版本含 DRAFT) 二选一，都不传→400 `MISSING_DRYRUN_TARGET`；结构上恒走带版本单快照分支→不落 `evaluation_session`、不派发 action（根除副作用 bug）。**删草稿边界**：deleteRule 仅删从未发布规则(无 ACTIVE/SUPERSEDED)级联 rule_definition+全部 rule_version；deleteDraftVersion 仅删 DRAFT；碰 ACTIVE/SUPERSEDED 一律拒(只能 disable)；级联范围只 rule_version，dry-run 痕迹不级联删（TTL 退休）。覆盖 D19「回滚=旧快照建草稿」为精确生命周期；设计见 `specs/2026-06-10-rule-draft-version-lifecycle-design.md` |
| D57 | 删除 `BlockTransactionHandler`，`BLOCK_TRANSACTION` 改由嵌入方 SPI 实现 | A | 通用规则引擎无通用"阻断交易"机制（怎么拦取决于宿主交易系统）；且 action 是评估后**异步 best-effort 派发**，真正的同步拒绝是 decision 结果（REJECT）——内置 stub 无条件返回 SUCCESS 是谎报"已拦截"，比 `NO_HANDLER` SKIPPED 更糟。删 `BlockTransactionHandler` + 单测；引擎内置 `ActionHandler` 仅保留 `SendAlertHandler`（HTTP webhook，足够通用可内置）。`BLOCK_TRANSACTION` 仍是**合法可配置 actionType**（SPI 开放），由嵌入方经 `ActionHandler` SPI 自行实现真实阻断；未配 handler 时派发落 `NO_HANDLER` SKIPPED（优雅，不崩）。覆盖 D7 v1.5「`BlockTransactionHandler.dryRun` 实装」中该 handler 部分（`SendAlertHandler` 不变）。bundle/manifest 把 `BLOCK_TRANSACTION` 当样例字符串透传的测试不受影响。**已被 D60 作废**：整套 ActionHandler SPI（含 `SendAlertHandler`）移除，引擎纯决策化，"命中后做什么"归消费方 / 流程引擎 |
| D59 | 规则身份模型：逻辑键 `(tenant, sceneCode, code, version)` + 代理键 `ruleVersionId` 并存 | A | Camunda 补充模式（逻辑键 + 代理键共存，代理键留作存储/外键/去重/幂等）：`RuleVersionSnapshot` 补 code+version、`NodeTrace` 补 ruleCode+ruleVersion、`Decision` 补 fromRuleCode+fromRuleVersion；trace/audit 表加 `rule_code`/`rule_version`（V1_26）+ admin trace 透出；`@RuleDef` 删 `id()` 改 `code()`+`version()`，`tenantId() default ""` 继承 client 租户，`AnnotationRuleSource` 按 (tenant,scene,code) 哈希派生 ruleVersionId。阶段甲（核心/trace/注解，本次）vs 阶段乙（admin API 按 code+version 寻址，未做）；显式不移除代理键 PK |
| D60 | 规则引擎纯决策化，移除动作子系统 | A | 对标 OPA（决策/PEP 执行）/ Camunda DMN+BPMN（决策与编排分层），编排后续接 Flowable（以 Service/HTTP Task 调本引擎决策节点）。删 `ActionHandler`/`@ActionType`/`ActionContext`/`ActionResult`、`Decision.actions`/`EvalResult.actionResults`/`DecisionBinding.actions`/`DecisionAction`、eval-svc 动作派发 + `action_execution` 落库、config/api 动作配置、`decision_definition.actions` 列（V1_27）。**取代/作废 D4/D16/D18/D27/D28/D57**，顺带退役 D27/D54 的 PULL-scene action 发布校验。保留：决策输出（`evaluate()` + SDK `EvalResultListener`/`EvalSessionListener`）+ PULL/PUSH 双模（PUSH 去派发，仍评估+落库）。引擎只出决策，"命中后做什么"归消费方 / 流程引擎 |
| D58 | 不做 SDK 直连 DB 轮询；删孤儿 watcher SPI + `rule-kernel-polling` 模块 | A | SDK 嵌入式的定位是**零网络本地评估、不读库**。若让嵌入方直连引擎 DB:① 把宿主与引擎**内部表 schema**(`rule_version` JSON 列等)死耦合,schema 一改所有嵌入方全崩;② 既然有 DB 直连能力,不如直接用全套引擎——与 SDK 存在意义矛盾。要通讯,**HTTP 轮询**(`/sdk/v1/snapshots`,`PollingRuleSource`/`SnapshotPoller`)已实现并验证(`SdkTradingScenario`),覆盖"嵌入式保鲜"。故:**不实现 DB 直连轮询**;删一直**零生产装配的孤儿 SPI** `RuleVersionWatcher`/`SceneWatcher`(rule-kernel)+ 仅有的两个 stub 实现 `DbPollingRuleWatcher`/`DbPollingSceneWatcher` + 整个 `rule-kernel-polling` 模块(父 pom `modules`/`dependencyManagement` 同步摘除)。SDK 规则来源保持 HTTP 轮询 / 文件 / DSL / 注解四种。覆盖 `99-functional-test-coverage` 原 ⚪「DB 轮询 watcher(SDK v2)」占位项 |
| D61 | SDK Easy Rules 风格注解规则(`@Condition`/`@Fact`/`@Metric` + 决策事件) | A | SDK 注解模式(D40)加糖,**仅嵌入式 SDK(同进程)**:规则 POJO 用 `@Condition` 单布尔方法表达条件(多重逻辑写方法体内,数据已注入),`@Fact` 注入 payload/元数据、`@Metric` 注入 metric;`AnnotatedRuleScanner` 扫描后包成**不透明 `ConditionEvaluator`** + 建快照(`RuleVersionSnapshot.builder`)+ 对 `@Condition` 的 `@Metric` 参数 `addMetricDependency` 驱动预拉,经现有 `SceneRuleIndex`/`EvalEngine` 评估。**`@Metric` 两角色**:标 `@Condition`=声明(驱动预拉)+取值,标 `@OnDecision`=仅取值(同 context 查,查不到 null,不驱动预拉——一次评估一 context 且 condition 必先于 OnDecision,无需二次取数)。**"命中后做什么"按 decision code 解耦、不挂规则**:甲 `DecisionFiredEvent`+`@EventListener`、乙 `@OnDecision`+`@Fact` 注入,由 `DecisionDispatcher` 按 `hitDecisions` 驱动(跟评估策略,不写死);动作异常吞+记日志续跑,条件异常按引擎算子语义(不命中)。**严格遵守 D60**:引擎不执行动作,动作全在消费方(SDK 进程内),仅在 SDK/starter 层加开发体验,不回退纯决策化。**取舍**:`@Condition` 黑盒化丧失 AST 内省/可视化/**服务端下发**(要这些走 DSL,两条腿并存,且 Builder 现禁本地+serverUrl 混用);metric 必须 `@Metric` 显式声明才预拉。`rule-sdk` 保持纯 Java,Spring 装配(bean 收集/事件桥接)全在 starter。设计见 `specs/2026-06-12-annotation-rule-easyrules-style-design.md` |

> README §二决策表 + §四抽象表已按本表落定；01-concepts §三各章节关键边界已对齐。新增决策追加 D22+ 后回填本表 + README §二 + 相关概念关键边界。
