# 00 — 关键设计决策

> **位置定位**：README §二 列出了 21 条核心决策的精简表，本文件**展开每条的背景、选项、权衡、最终选择与 v1 落地范围**。
>
> **如何使用**：每条决策的"决定"行已落定，下方"v1 落地范围"/"v1 不做的"/"派生约束"是落地参考。新增决策追加 D22+ 即可，旧条目不再回填修改（变更走"新决策覆盖旧决策"+ README §七版本史登记）。

---

## D1. 第一阶段场景定位 ⭐⭐⭐

**为什么重要**：场景决定性能目标、特性优先级、是否要引入流处理 / RETE / LLM 等重资产。

| 选项 | 说明 | 权衡 |
|------|------|------|
| ☐ A. 运营 / 营销 / 活动 | 中吞吐（1k QPS）、人工配置友好、可视化优先 | 覆盖最广、社区方案多；缺点是天花板低（高并发风控接不住） |
| ☐ B. 风控 / 反欺诈 | 高吞吐（10k+ QPS）、低延迟（<50ms）、行为序列匹配 | 技术含量高，但需要 CEP / 流处理 / 预编译，复杂度激增 |
| ☐ C. 通用平台（多场景） | 不预设场景，仅提供能力，业务方接入 | 灵活但设计复杂度最高，需要多租户/权限/SDK 等公民设施 |
| ☐ D. AI Agent 决策层 | 作为 LLM / Agent 的可控决策部分，条件可以是 LLM 判断 | 前沿但有较强不确定性，与传统规则引擎有质的差异 |

**推荐**：A（运营/营销/活动）作为第一阶段，向 C（通用平台）演进留好接口；明确不做 B 和 D，B/D 留到 v2/v3。

**你的决定**：

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

**你的决定**：

---

## D3. 多租户模型 ⭐⭐⭐

**为什么重要**：影响 schema 设计、索引设计、API 契约。后期改是大动作。

| 选项 | 说明 | 权衡 |
|------|------|------|
| ☐ A. schema 起一等公民 | 所有表带 `tenant_id`，索引前缀含它，默认租户也是一个租户 | 后期拆 SaaS / 多业务线零重构；起步多一点字段 |
| ☐ B. 单租户起步，预留接口 | 表不带 tenant_id，但代码抽象层预留 | 起步快；后期改 schema 痛 |
| ☐ C. 不考虑多租户 | 明确只给一个业务线用 | 最简；如果业务发展超预期则要推倒重来 |

**推荐**：A。多租户成本在 schema 上极小（多一列 + 索引前缀），收益巨大。

**你的决定**：

---

## D4. 动作协议 ⭐⭐

**为什么重要**：决定运营能不能自助配动作。

| 选项 | 说明 | 权衡 |
|------|------|------|
| ☐ A. 声明式优先 + SPI 兜底 | webhook / MQ.send / SQL.update / log 配置即开；复杂逻辑 Java SPI 实现 | 80% 场景零代码；声明式动作的 schema 设计要花心思 |
| ☐ B. Java SPI 为主 | 所有动作都是 Java 实现类 | 可控、类型安全；每加一种动作要发版 |
| ☐ C. 动作编排（BPMN / 工作流） | 动作可以顺序/并行/条件分支/延时等待 | 表达力最强；复杂度激增、配置 UI 难做 |

**推荐**：A。第一阶段。C 留到 v2 接入工作流引擎（如 Camunda / Flowable）。

**你的决定**：

---

## D5. 触发模型 ⭐⭐

**为什么重要**：决定要不要引入流处理（Flink/Kafka Streams）。

| 选项 | 说明 | 权衡 |
|------|------|------|
| ☐ A. 单事件触发 | 一个事件进来评估一次规则，无状态 | 简单、覆盖 90% 营销/运营；不支持"5 分钟内 3 次"这类时间窗 |
| ☐ B. 单事件 + 简单聚合 | 单事件触发 + MetricSource 内部做 SQL 聚合 | 中等；用 SQL 聚合代替流处理，时间窗有限 |
| ☐ C. 引入 CEP（Flink CEP / Kafka Streams） | 真正的事件流处理 | 表达力最强、性能最好；引入重型依赖、运维成本高 |

**推荐**：B（单事件 + MetricSource 内 SQL 聚合覆盖 80% 时间窗场景），CEP 留到 v2。

**你的决定**：

---

## D6. 版本与灰度 ⭐⭐

**为什么重要**：决定规则上线安全性，是否一等公民影响 schema。

| 选项 | 说明 | 权衡 |
|------|------|------|
| ☐ A. 一等公民 | 规则有版本号，发布即快照不可变；灰度按 % 放量、按用户标签命中 | 安全、可回滚、A/B 实验内置；schema 复杂度+++ |
| ☐ B. 版本化但不内置灰度 | 有版本号和回滚，灰度交给上游 ABTest 平台 | 折中；要和 ABTest 平台对接 |
| ☐ C. 不做版本 / 灰度 | 规则改了立即生效，错了就回滚配置 | 起步最快；事故风险大 |

**推荐**：A。但灰度命中算法可以先内置简单 hash % bucket，复杂分桶留到接 ABTest 平台。

**你的决定**：

---

## D7. Dry-run 试算 ⭐⭐

**为什么重要**：直接影响运营 / 产品的配置体验，定不定一等公民差很多。

| 选项 | 说明 | 权衡 |
|------|------|------|
| ☐ A. 一等公民 | 走完整评估链路，仅 ActionHandler 短路；前端有专门试算面板 | 体验最好；ActionHandler 需要全部支持 dryRun 标志 |
| ☐ B. 仅评估层试算 | 用户构造 mockEvent → 返回 AST 节点级 trace，不动 Action | 起步快；不能验证动作输出（如发什么短信文案） |
| ☐ C. 不做试算 | 上线即真实 | 简单；运营会很痛苦 |

**推荐**：A。但 v1 可以先做 B（评估层试算 + trace），动作层 dryRun 在 v1.5 补。

**v1 落地范围**：
- 评估层 dry-run 一等公民：走完整评估链路（Matcher / Pre-Gate / Context / AST），节点 trace 落 `dry_run_session` 表（与 `evaluation_session` 隔离）；
- `ActionHandler` 接口已在签名内预留 `dryRun(action, context)` 入口；
- **v1 未实装 handler 的兜底契约**：调用 `dryRun` 但 handler 未补齐时，由 Dispatcher 短路返回 `ActionResult { status=SKIPPED, errorCode=DRY_RUN_NOT_IMPLEMENTED }`——不抛异常、不阻塞试算面板渲染；
- v1.5 全量补齐后该 `errorCode` 不再产生（完整 `ActionResult.errorCode` 枚举见 [`01-concepts.md`](./01-concepts.md) §3.7）。

**你的决定**：

---

## D8. 性能目标 ⭐⭐

**为什么重要**：决定要不要引入 RETE / 预编译 / 索引化匹配。

| 选项 | 量级 | 实现 |
|------|------|------|
| ☐ A. 千级 QPS / <500ms | 规则数 100-1000 / 用户单事件 | 朴素遍历匹配 + Spring 注入即可 |
| ☐ B. 万级 QPS / <100ms | 规则数 1k-10k | 需要规则索引化 + 预编译 AST + 指标缓存 |
| ☐ C. 十万级 QPS / <10ms | 规则数 10k+ | RETE / 预编译字节码 / 全内存 / 分片 |

**推荐**：A。如果场景定位是运营/营销，A 完全够。B/C 是风控才需要。

**你的决定**：

---

## D9. 持久化分层 ⭐

**为什么重要**：执行日志量级远大于规则定义，分库与否影响查询性能和运维。

| 选项 | 说明 | 权衡 |
|------|------|------|
| ☐ A. 全 RDBMS（MySQL） | 定义 + 执行日志 + trace 都在 MySQL | 最简单、运维一致；日志量大时查询慢、表膨胀 |
| ☐ B. 分层（定义 RDBMS + 日志列存/ES） | 定义 MySQL，执行日志走 ClickHouse / ES | 查询性能好、容量大；引入新组件、运维复杂 |
| ☐ C. 分层（定义 RDBMS + 日志冷热分级） | MySQL + 7 天热表 + 归档冷表 | 折中；归档逻辑要自己写 |

**推荐**：A 起步（明确数据保留 30 天），观测到瓶颈后切 B 或 C。

**你的决定**：

---

## D10. AI 评估节点 ⭐

**为什么重要**：决定是否预留 LLM 作为 Condition 的接口。

| 选项 | 说明 | 权衡 |
|------|------|------|
| ☐ A. 不考虑 | 引擎与 AI 解耦 | 最简单；如果未来要接入 LLM 要重新设计 |
| ☐ B. 预留接口 | `LLMConditionEvaluator` 作为一种 ConditionEvaluator 类型，v1 不实现 | 几乎零成本预留；不影响第一阶段 |
| ☐ C. 一等公民 | 内置 LLM 评估、prompt 模板、结果缓存 | 前沿；当前 LLM 不确定性大，不适合做核心决策 |

**推荐**：B。`ConditionEvaluator` 接口本就是开放的，预留接口几乎不要成本。

**你的决定**：

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
| `EvalResult.output` | 评估结果对象多态：`{satisfied, score?, category?, decision?}` | v1 只填 `satisfied`，其余字段 null | v1（接口） |
| `ConditionNode.weight` | AST JSON 节点可选字段 | v1 评估器忽略；SCORECARD kind 启用 | v1（JSON 字段） |

**为什么这 3 个占位现在做、后面不痛**：
- `Rule.kind`：MySQL 加列零成本；不加，未来要么新建 `scorecard_definition` 表（数据散布）、要么 alter table（停服或慢 DDL）；
- `EvalResult.output`：接口现在多态设计，调用方拿到的对象 shape 稳定；v1.5 引入 `score` 字段时无需改 PULL API 签名；
- `ConditionNode.weight`：AST 存的是 JSON，加字段就是加 key，零迁移；但需要在 §3.5 sealed `RuleNode` 文档锁定 schema，避免不同实现各自加字段污染。

**v1 不做的**：
- 不实现 SCORECARD / DECISION_TREE / DECISION_TABLE 的 evaluator —— 留到 v1.5 / v2，按业务实际需求驱动；
- 不实现 `executionStrategy` —— 决策集策略留到 08-evolution 路线图，v1 默认 `ALL_HITS`；
- 不实现脚本沙箱 —— 表达式 evaluator 留到 v1.5。

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
- **审计表 `audit_log`**：`tenant_id` / `actor` / `target_type`（RULE / SCENE / METRIC_BINDING / ACTION_BINDING / JOB / ...）/ `target_id` / `action`（CREATE / UPDATE / PUBLISH / PUBLISH_FAILED / ENABLE / DISABLE / DELETE）/ `before_snapshot` JSON / `after_snapshot` JSON / `occurred_at` / `trace_id`；
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
    errorCode?:      String            // EVAL_TIMEOUT / METRIC_FETCH_FAIL / EVALUATOR_EXCEPTION / SCHEMA_VIOLATION
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
- **灰度对账**：评估结果分三类统计——`HIT` / `MISS` / `ERROR`，ERROR 不计入命中率分母，单独看 ERROR 率告警。

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
- `ActionHandler.execute(action, context)` 返回 `ActionResult { status, errorCode?, errorMessage?, retryable }`，**不返回 List<RuleEvent>**；
- ActionHandler **可以**调用外部 MQ / HTTP（这是 Action 的本职），但上游若要把这条 MQ 消息再翻译成 RuleEvent 推回引擎，是**业务方主动行为**，引擎不感知；
- `RuleEvent.source` 字段记录来源（`HTTP` / `MQ` / `JOB` / `SDK` / `REPLAY`），**不**记录"链式"标识；
- 由此推论：环检测、深度限制、子事件灰度桶继承都不存在——业务方要自己防环（如外部链路加 hop 计数）。

**v1 不做的**：
- 不做内置链式触发；
- 不做"Action 触发 → 等待结果 → 触发下一动作"的工作流编排（D4 已说明 v2 接 Camunda）。

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
- **轮询粒度**：默认 15 秒，可配置 `engine.rule.poll-interval`；
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
- **执行顺序**：单 Rule 内 Action 按 `sortOrder` 顺序串行执行（v1 不并行，D4 已说明 v2 接工作流引擎才考虑编排）；
- **单 Action 失败定义**：`ActionHandler.execute` 返回 `ActionResult.status = FAILED` 或抛出未捕获异常；引擎将异常转为 `ActionResult { status: FAILED, errorCode: HANDLER_EXCEPTION, retryable: false }`；
- **重试**：`retryable=true` 的失败入重试队列（独立调度，不阻塞同 Rule 后续 Action）；`retryable=false` 直接落 `action_execution.status = FAILED`；
- **隔离默认**：除非显式 `Action.failFast = true`，单条 Action 失败 / 跳过 / 重试中 → 同 Rule 后续 Action **继续正常执行**；
- **failFast 语义**：`failFast=true` 的 Action 失败后，**同一 Rule** 内 `sortOrder` 大于本 Action 的后续 Action 全部标记 `status=SKIPPED, errorCode=PREDECESSOR_FAILED`，不进入重试队列；
- **Rule 状态独立**：单 Action 失败 **不影响** Rule 的 `EvalResult.satisfied`（评估已完成才会派发 Action，Action 是命中后行为）；
- **跨 Rule 隔离**：与 D15 一致——同 (scene + eventType) 下其他 Rule 的 Action 不受影响；
- **补偿**：`compensateActionType` **不自动触发**——补偿是 D4 的补偿流水线职责，由外部调度（如对账任务、手动回滚按钮）发起 `compensate(action, context)` 调用；引擎不在 Action 失败时自动跑补偿；
- **对账三态**：与 D15 对齐，`action_execution` 按 `SUCCESS / FAILED / SKIPPED` 三类统计，SKIPPED 不计入失败率分母。

**v1 不做的**：
- 不做并行 Action / 编排（留到 v2 接工作流引擎）；
- 不做"Action 失败自动触发补偿"（补偿流水线由调用方按业务策略主动发起）；
- 不做 Saga 风格的全局事务回滚（动作语义本身就不是事务，是事件序列）。

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
  2. `rule_version` 表插入新版本快照行（`version` 单调递增，含完整 AST + actions + preGates + rollout 不可变冻结）
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

4. **Pre-Gate trace 走同一 TraceWriter**：
   - §3.14 Pre-Gate 失败节点（`nodeType=PRE_GATE_BLOCKED`）与 ConditionNode trace 走同一 `TraceWriter`，不另起通道；
   - dry-run 模式（D7）trace 同样走 `TraceWriter` 异步通道，但**写入目标表是独立的 `dry_run_session` 系列表**（与 prod `evaluation_session` / `node_trace` 隔离，D7 明定）——`TraceWriter` 内部按 `EvalContext.dryRun` 标记路由到不同表，**不**靠同表字段区分；
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

## 附：决策汇总表

| # | 决策 | 你的选择 | 备注              |
|---|------|------|-----------------|
| D1 | 第一阶段场景定位 | BACD | 按优先级设计，可以预留后续扩展 |
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
| D15 | 评估失败语义 | A    | 单节点降级 false + 整树继续 + EvalResult.errorCode 槽位；规则间隔离；ERROR 独立对账桶 |
| D16 | 链式触发 | A    | 显式禁止 Action 产引擎事件；ActionHandler 不返回事件；业务自走外部 MQ 链式 |
| D17 | 配置热加载 | A    | DB 轮询 15s + 评估快照锁定 + RuleVersionWatcher 接口预留 |
| D18 | Action 失败补偿语义 | B    | 默认 continue-on-error，Action 级可声明 failFast；单 Action 失败不影响 Rule 内其他 Action；补偿不自动触发由外部调度 |
| D19 | 规则发布事务性 | A    | 单条规则原子发布（状态机：DRAFT → PUBLISHING → PUBLISHED / PUBLISH_FAILED）；批量发布前端逐条提交；回滚 = 用旧版本快照建新草稿 |
| D20 | v1 高吞吐评估期落地范围 | A    | metric 批量预拉 + 异步 Dispatcher + 输入闭合校验 + Watcher SPI 多态化；预编译 Predicate SPI 预留 v1.5 切换；alpha 共享 / 嵌入式 SDK / EXPRESSION 叶子留 08-evolution |
| D21 | 评估观测数据异步写入 | B    | `TraceWriter` 异步批写（队列 + 消费者池 + batch insert，复用 D20 §2 模型）；与 `audit_log` 同步事务严格分离；失败降级丢弃 + 告警，不影响 EvalResult；ConditionNode 与 Pre-Gate trace 同通道；运维参数留 07-operability |

> README §二决策表 + §四抽象表已按本表落定；01-concepts §三各章节关键边界已对齐。新增决策追加 D22+ 后回填本表 + README §二 + 相关概念关键边界。
