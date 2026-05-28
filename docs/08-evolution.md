# 08 — 演进路线图（部分展开）

> **位置定位**：本文档承载所有"v1 不做、未来做"的演进项与决策时间线。当前**部分展开**——已识别的演进锚点中 §2.1 / §2.12 / §2.13 / §2.14 / §2.15 已写细节；§三 决策时间线、§四 已否决方案仍待展开。
>
> **前置阅读**：[`README.md`](./README.md)、[`00-decisions.md`](./00-decisions.md)
>
> **解决什么疑问**："为什么 v1 没做 X？""未来什么时候做 X？""X 是怎么决定不做的？"

---

## 一、文档状态

| 章节 | 状态 |
|------|------|
| §二 演进锚点（roadmap） | ⏳ 部分展开（§2.1 / §2.12 / §2.13 / §2.14 / §2.15 已详细展开；§2.2–§2.11 四要素就位、路线概要级） |
| §三 决策时间线 | ⏳ 未展开（建议从 README §七版本史 + `00-decisions.md` 汇总） |
| §四 已否决方案 | ⏳ 未展开 |

---

## 二、演进锚点（roadmap）

> **维护原则**：每条 anchor 由"来源决策 / 触发条件 / v1 现状 / 演进方向"四要素组成。当 v1 文档（01-concepts / README）中出现 `详见 08-evolution.md §XXX` 的链接时，必须在本节有对应锚点。

### 2.1 kind 多态（来源 D12）

- **v1 现状**：`Rule.kind` 字段就位，仅实现 `AST_BOOLEAN`，其他枚举值发布拒绝；`EvalResult` 多态字段（`score / category / decision`）就位但 v1 仅填 `satisfied`。
- **触发条件**：业务侧出现评分卡 / 决策树 / 决策表 / 表达式脚本类需求。
- **迁移成本**：低（schema 占位已就绪，只补 evaluator + UI 编辑器）。

**设计原则**：D12 引入 `Rule.kind` 是为评分卡 / 决策树 / 决策表 / 脚本类规则演进**预留 schema 占位**，不是 v1 要实现的功能。

**各 kind 共享 Rule 的公共属性**：trigger / preGates / actions / version / rollout / Scene 治理都不变，多态只在"判定主体"内部——

> 下表"判定主体字段"列只指示**形态**与**承载方式**，具体字段命名留待 v2 设计时定稿，避免占名误导后续设计。

| kind | 判定主体承载 | 输出字段 | 状态 |
|------|------------|---------|------|
| `AST_BOOLEAN` | sealed `RuleNode` AST 树（已在 v1 落地） | `EvalResult.satisfied` | v1 唯一实现 |
| `SCORECARD` | JSON 列承载条件列表 + 各自 `weight` + 阈值带 | `EvalResult.score` | 待实现 |
| `DECISION_TREE` | JSON 列承载嵌套 if/then/else 树 | `EvalResult.category` | 待实现 |
| `DECISION_TABLE` | JSON 列承载输入列 + 输出列 + 行集合矩阵 | `EvalResult.decision` | 待实现 |
| `EXPRESSION_SCRIPT` | 文本列承载 CEL / Aviator 脚本 | 按脚本返回值多态填 | 待实现 |

**`EvalResult` 是稳定多态**：v1 PULL 模式调用方拿到的对象 shape 是 `{satisfied, score?, category?, decision?, trace}`，v1.5 引入 SCORECARD 时多填一个 `score` 字段，PULL API 签名不变；节点 trace 跨 kind 统一，运营自助排障的能力 100% 复用。

**决策集 / 决策流不进 `Rule.kind` 枚举**：`Scene.executionStrategy`（决策集策略，v2 引入 `ALL_HITS` / `FIRST_HIT` / `HIGHEST_PRIORITY`）是 Scene 字段；Action 编排（决策流）由 D4 工作流引擎扩展点承载，两者都是 Rule 层级之外。

**为什么不另起表**：评分卡 / 决策树仍需要 Rule 的全部公共属性（触发 / 准入 / 灰度 / Action / 版本快照），独立表会复制 80% 的列且数据散布、跨形态报表困难；用 `kind` 字段 + 多态 JSON 列在同一张表里，公共能力天然共享。

**演进路径**：按需逐个实现 evaluator——SCORECARD 启用 `ConditionNode.weight`；DECISION_TREE / DECISION_TABLE / EXPRESSION_SCRIPT 各自的内部 JSON 结构与 evaluator；`Scene.executionStrategy` 配合决策集。

### 2.2 Metric 版本化（来源 #2 占位）

- **v1 现状**：`Metric.metricVersion` 字段就位，固定值 1；规则引用按 `metricCode` 不带版本号。
- **触发条件**：指标定义语义变更（如"近 7d 交易额"换算口径、时区基准变化）需要灰度切换，旧规则不应被静默影响。
- **演进方向**：发布新版本时 `metricVersion` 递增 + 历史版本并存；规则版本快照锁定引用的 `(metricCode, metricVersion)`；运营 UI 展示"指标变更影响的规则数"。
- **迁移成本**：中（需扩展规则与指标的多对多版本绑定表 + 评估期版本解析逻辑）。

### 2.3 跨 Scene 规则复用（来源 #1）

- **v1 现状**：Rule 属于唯一 Scene，没有跨 Scene 复用机制；相似规则需在多个 Scene 重复配置。
- **演进方向**：引入 `RuleTemplate`（参数化的规则模板）和 / 或 `RuleFragment`（可被多 Rule 引用的 AST 子树），运营自助实例化到不同 Scene。需新增模板表、引用关系表、发布时膨胀策略。
- **迁移成本**：高（影响 schema + 发布事务 + 试算 + 灰度桶继承）。

### 2.4 规则间依赖与编排（来源 #3）

- **v1 现状**：D16 显式禁止 Action 产引擎事件；规则间不存在内置依赖与顺序保证；业务需要顺序走外部 MQ 编排。
- **演进方向**：v2 接 Camunda / Flowable 工作流引擎（D4 已说明），把"Rule 命中 → 触发下一 Rule"作为工作流节点而非 Rule 内能力。引擎仍保持单事件单评估，工作流引擎做编排。
- **迁移成本**：高（引入新组件、运维形态变化）。

### 2.5 节点级 trace 冷热分级（来源 #5，并入 [`05-storage.md`](./05-storage.md) TODO）

- **v1 现状**：D9 决定全 MySQL 起步，数据保留 30 天；`node_trace` 表与 `evaluation_session` 表同库；写入路径走 `TraceWriter` 异步批写（D21）——本演进锚点是**存储分层**（冷热分级 / 列存），与 D21 的**写入路径**（同步 vs 异步）正交，二者独立演进。
- **触发条件**：观测到 trace 表膨胀影响查询性能 / 存储成本不可控。
- **演进方向**：热表保留 7 天 + 冷归档表（按月分区）+ 可选 ClickHouse / ES 列存；存储与查询接口隔离，业务侧零改动。
- **接收内容**：本锚点在 05-storage 展开时迁入"§冷热分级" 章节。

### 2.6 监控指标体系（来源 #10，并入 [`07-operability.md`](./07-operability.md) TODO）

- **v1 现状**：顶层架构旁路提到 `Metric Aggregator → Prometheus`，但具体监控指标清单未定义。
- **演进方向**：在 07-operability.md 展开核心指标清单——评估耗时分位、命中率、Action 成功率、ERROR 率、Job 延迟、规则版本切换次数、热加载延迟、审计写入耗时等；告警阈值与通知渠道一并定义。
- **接收内容**：本锚点在 07-operability 展开时迁入"§监控告警"。

### 2.7 灰度发布的验证与回退（来源 #12，并入 07-operability TODO）

- **v1 现状**：D6 已定义灰度按 % 放量 + 按用户标签命中，hash bucket 算法稳定（基于 `(subjectId, ruleVersionId)`）。
- **未覆盖**：灰度观察期 / 效果对比指标 / 自动放量与自动回退策略。
- **演进方向**：在 07-operability 展开"灰度运营"章节——SLI 监控 + 红蓝指标对比 + 自动放量按 SLO 推进 + 异常自动回退到上一版本。

### 2.8 合规演进（来源 D14 v1 不做的"敏感数据"占位）

- **v1 现状**：审计快照 / Context / payload 按原值落库；GDPR / PII 由调用方在 payload 进入引擎前完成脱敏 / 加密 / token 化。`audit_log` 不做篡改防护。
- **演进方向**：
  - **字段级加密**：sensitive 字段在 Scene `payloadSchema` 标记后，引擎写入持久层前自动加密；读取按权限解密；
  - **审计 hash chain**：`audit_log` 加 `prev_hash` 列，链式不可篡改；与 WORM 存储或区块链化扩展二选一；
  - **数据保留与右遗忘**：GDPR 配套——按主体 ID 物理清除 `evaluation_session` / `node_trace` / `action_execution` / `audit_log` 中对应数据，需要分库分表的清除工具。
- **迁移成本**：高（涉及所有持久化对象）。

### 2.9 规则导出 / 导入（来源 #15）

- **v1 现状**：没有跨环境 / 跨租户的规则迁移工具。
- **演进方向**：发布运营工具——按规则版本导出为可移植的 JSON Bundle（含规则定义 + 引用的 metric / actionType 元数据），目标环境导入时校验 Scene 兼容性 + metric 白名单匹配 + 版本号重映射。
- **迁移成本**：中（独立工具链，不动核心引擎）。

### 2.10 规则模板市场（来源 #16）

- **v1 现状**：不考虑平台级规则模板共享。
- **演进方向**：依赖 2.3（跨 Scene 复用）与 2.9（导出 / 导入）就位后，再考虑平台级模板仓库 + 评分 + 版本管理 + 跨租户分发。
- **优先级**：低（v3 范畴）。

### 2.11 外部系统集成契约标准化（来源 #17）

- **v1 现状**：`MetricSource` 支持外部 HTTP，但未定义统一接口契约；不同业务自行约定。
- **演进方向**：定义标准化指标获取协议（参考 OpenTelemetry / OpenFeature 模型），引入 `MetricFetcher` 通用 SDK + 协议测试套件。
- **迁移成本**：中。

### 2.12 Scene schema 演进（来源 D13 v1 不做的"payloadSchema 演进"）

- **v1 现状**：`Scene.payloadSchema` 在 Scene 表上，发布期校验 RuleEvent.payload 字段合法性；变更 schema = 直接覆盖。
- **触发条件**：业务侧调整 payload 字段（新增 / 重命名 / 类型变更），存量规则可能引用了旧字段。
- **演进方向**：引入 `Scene.payloadSchemaVersion` + 历史版本表 `scene_payload_schema_history`；发布 RuleVersion 时锁定当时的 `(sceneId, payloadSchemaVersion)` 引用；schema 变更走"新版本号 + 影响规则清单 + 灰度切换"流程；与 D20 §3 输入闭合校验联动——校验集合按当时锁定的 schema 版本而非"最新"求解。
- **迁移成本**：中（schema 历史表 + 引用解析逻辑）。

### 2.13 评估期预编译完全切换（来源 D20 v1 不做的"完整预编译"）

- **v1 现状**：D20 已落地 `RuleVersionExecutor` SPI + `InterpretedExecutor` 默认实现（Visitor 树遍历），`rule_version.compiled_predicate_ref` 字段预留为空。
- **触发条件**：PUSH 模式单机 TPS 触及 Visitor 模式的虚调用 + 多态分发瓶颈（参考量级 5–10 μs / 规则）。
- **演进方向（v1.5）**：
  - 引入 `CompiledExecutor`，发布期把 AST 编译为单一 `Predicate<EvalContext>` lambda（Janino 字节码 / LambdaMetafactory invokedynamic）；
  - 编译产物缓存在 `RuleVersionCache` 按版本 id 引用，发布 / DISABLE / ENABLE 触发 evict + recompile；
  - 与 D20 §3 输入契约校验联动——强类型变量引用闭合后才能确定编译槽位偏移；
  - `ExecutorRegistry` 按 RuleVersion 灰度配置切换两类执行器（"编译版先选少量规则灰度验证 → 全量切"）。
- **alpha 节点共享（跨 RuleVersion 条件去重）作为扩展讨论**：编译期可顺带做 ConditionNode hash 去重，**同 `EvalContext` 内同条件只算一次**，结果缓存在 `EvaluationSession.conditionResultCache`；与预编译切换同期落地为最佳，v1 阶段接受重复评估开销。
- **节点级 trace 兼容性**：trace 仍按 RuleVersion 的视图展开，底层求值是否共享对运营透明（D7 不变）。
- **迁移成本**：中（编译期 + 缓存 + 灰度切换）。
- **预期收益**：单条规则评估开销从 5–10 μs 降至 0.3–1 μs（基于 Aviator / Janino 公开 benchmark 量级）。

### 2.14 嵌入式 SDK 模式（来源 D20 v1 不做的"嵌入式 SDK"）

- **v1 现状**：评估走中心服务（PUSH / PULL / HYBRID 三模式都基于 RPC）；D17 / D20 §4 的 `RuleVersionWatcher` SPI 已为多 backend 预留，但 v1 仅 `DbPollingRuleWatcher` 一种实现。
- **触发条件**：业务方对评估 RPC 延迟敏感（如风控前置链路 P99 < 5ms）且自带运维能力，愿意承担 SDK 版本管控成本。
- **演进方向（v2 范畴）**：
  - 把 RuleVersion 缓存 + 评估引擎打包成 jar，业务方进程内嵌入直接评估，仅 Action 派发回写中心；
  - 配套 `MqRuleWatcher`（Kafka / Pulsar 变更主题）/ `NacosRuleWatcher` / `ZkRuleWatcher` 实现，把 D17 的"15s 最终一致窗口"压到 < 1s；
  - **保留中台严肃治理**：`RuleDefinition` + 不可变快照 + D14 审计 + D6 灰度桶一致性算法都不变，嵌入式 SDK 只是评估执行位置下沉；
  - 与 ice 项目嵌入式形态的差异：不允许业务方在 SDK 端写 / 改规则，配置只读拉取；Action 派发仍走中心（保留 D18 重试 / 补偿语义）。
- **依赖**：需先在 v1.5 完成 §2.13 预编译切换以降低 SDK 体积与启动开销。
- **迁移成本**：高（SDK 版本管控 + Action 反向回写通道 + 多 backend Watcher 完整实现 + 跨实例灰度桶审计闭环）。
- **优先级**：中。

### 2.15 evaluation_session 异步化路径（来源 D21 派生 / 高吞吐讨论）

> **本节是触发条件达成后的重构方向，v1 阶段无任何专项准备动作，标准工程实践即可**——避免读者把"演进路径"误读为"v1 待办"。

- **v1 现状**：每次评估 1 行同步写，承担三层角色：①**幂等收口**（DB uk on `event_id`，与 Redis trySet 形成双兜底，D11 / §3.10）；②**对账分母**（HIT / MISS / ERROR 三态统计源，D15）；③**外键时序**（`node_trace` / `action_execution` 引用 `session_id`）。单行同步 insert 1–3 ms，对 D8 千级 QPS 目标是零头，故 D21 仅把 `node_trace`（50–1000 行 / 次）异步化，**`evaluation_session` 保持同步**。
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

---

## 三、决策时间线（待展开）

> 展开本节时，建议从 [`README.md`](./README.md) §七 版本史 + [`00-decisions.md`](./00-decisions.md) D1-D21 汇总，按时间顺序整理"决策 → 派生约束 → 影响范围"。

⏳ 未展开。

---

## 四、已否决方案（待展开）

> 展开本节时归档：曾经评估但最终未采纳的方案，注明否决原因和替代方案。

⏳ 未展开。当前已知候选条目：

- 三层模型（Rule / RuleGroup / Condition）→ 回退为两层（README §七版本史已记录原因）；
- 批量原子发布 API（D19 否决）；
- Action 内置链式触发（D16 否决）；
- 完全延后权限与审计（D14 选项 C 否决）；
- urule 风格的全局 FunctionLibrary（"注册任意 Java 方法供规则调用"）→ 与 D20 §3 闭合校验、D16 禁止副作用、§3.9 metric 只读三条决策正面冲突；v1 用 `ConditionType` 扩展（[`04-extension.md`](./04-extension.md)）承载条件原子；将来若出现高频自定义表达式需求，走 D12 `kind=EXPRESSION_SCRIPT` 多态（§2.1）而非全局函数库；
- urule 风格的独立 ConstantLibrary 一等概念 → v1 用只读 metric 替代（§3.9 关键边界登记），复用 metric 治理 + 版本化通道；独立 ConstantLibrary 演进与 FunctionLibrary 配套评估，无独立优先级。

---

## 五、跨文档 TODO 接收锚点

> 本节列出 v1 文档中标记 "详见 08-evolution" 的所有 TODO，便于本文档展开时一次性迁移完毕。

| 来源 | TODO 内容 | 迁入目标 | 状态 |
|------|-----------|---------|------|
| [`01-concepts.md`](./01-concepts.md) §3.4 "kind 多态边界" 小节 | D12 演进说明（5 个 kind 的字段映射与共享属性） | §2.1 kind 多态 | ✅ 已迁入 |
| [`01-concepts.md`](./01-concepts.md) §3.9 `metricVersion` 字段 | Metric 版本化语义 | §2.2 Metric 版本化 | ✅ 已迁入 |
| [`00-decisions.md`](./00-decisions.md) D14 v1 不做的"敏感数据" | 合规演进路径 | §2.8 合规演进 | ✅ 已迁入 |
| [`00-decisions.md`](./00-decisions.md) D13 v1 不做的"payloadSchema 演进" | schema 升版本如何兼容存量规则 | §2.12 Scene schema 演进 | ✅ 已迁入 |
| [`00-decisions.md`](./00-decisions.md) D20 v1 不做的"完整预编译 / alpha 共享" | Visitor 切预编译 lambda + 跨规则条件去重 | §2.13 评估期预编译完全切换 | ✅ 已迁入 |
| [`00-decisions.md`](./00-decisions.md) D20 v1 不做的"嵌入式 SDK" | 评估下沉到业务进程 + Watcher 多 backend 完整实现 | §2.14 嵌入式 SDK 模式 | ✅ 已迁入 |
| [`00-decisions.md`](./00-decisions.md) D21 派生（`evaluation_session` 同步写） | 三层角色解耦 + v1 不做前置准备的理由 | §2.15 evaluation_session 异步化路径 | ✅ 已迁入 |

