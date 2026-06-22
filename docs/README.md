# Rule Engine — 通用规则引擎设计总览

> **是什么**：一个独立的通用规则引擎产品。把"在什么条件下、对谁、做什么动作"这件事抽出来，让业务方通过**可视化配置**和**少量代码扩展**就能上线规则，无需为每个新场景重新写一套调度 / 评估 / 落库 / 审计代码。
>
> **不是什么**：不是某个已有项目的迁移或重构，不绑定任何具体业务领域。设计上可以为风控、营销、运营触达、活动奖励、AB 实验门控等场景服务。
>
> **状态**：草稿（2026-05-25 起）。30 条核心决策已落定，逐条权衡见 [`00-decisions.md`](./00-decisions.md)，详细演进见 [`08-evolution.md`](./08-evolution.md)。
>
> **设计基调**：场景与性能按**优先级演进**——第一阶段实现聚焦运营/营销/活动 + 千级 QPS 起步，但**核心抽象按风控级别预留扩展点**，避免后期推倒重来。

---

## 一、定位与边界

### 1.1 解决什么问题

业务方做"条件 → 动作"类需求时反复出现的痛点：

1. 每个新场景从零写一套调度 + 评估 + 落库 + 审计 + 重试，重复造轮子；
2. 规则硬编码在 Service 里，每改一次都要发版，运营无法自助；
3. 试算困难，规则上线后出错只能看日志反推，运营和研发互相甩锅；
4. 没有统一的执行记录 / 审计 / 灰度 / 回滚机制，事故复盘代价高。

### 1.2 演进路线（场景与性能均按优先级递进）

**场景优先级**：风控/反欺诈 > 运营/营销/活动 > 通用多场景平台 > AI Agent 决策层。

**性能优先级**：万级 QPS (<100ms) > 千级 QPS (<500ms)。

但**第一阶段实现起点**取每个维度的"较简单档位"：

- 场景：从**运营 / 营销 / 活动**类切入（中等吞吐、人工配置友好、可视化优先）；
- 性能：起步**千级 QPS / <500ms**（规则数 100~1000），朴素遍历匹配即可，不引入 RETE。

这一档位的共同特征：

- 触发源以"业务事件"为主（用户注册、交易、登录、入金等），单事件评估即可；
- 规则数 100~1000 条，遍历匹配 + 预编译 AST 缓存足够；
- 配置频率高（每周新增数十条），可视化和试算是刚需；
- 引擎只产出决策（命中规则 → Decision），"命中后做什么"（发券 / 发消息 / 调 webhook / 写入业务表）交给消费方 / 流程引擎（D60）。

### 1.3 抽象层必须预留的扩展点（不在 v1 实现，但接口必须留）

为支撑后续向风控级别演进，第一阶段的接口设计必须容纳：

- **复杂事件处理（CEP）** —— "5 分钟内连续 3 次失败"这类时间窗序列匹配。v1 用 MetricSource 内 SQL 聚合覆盖 80%，CEP 接 Flink / Kafka Streams 在 v2 接入，但 `RuleEvent` / `MetricSource` 协议必须允许"带窗口的指标"和"序列事件"作为输入；
- **规则索引化匹配 / 预编译字节码** —— v1 朴素遍历 + AST 解释执行；万级 QPS 时需要按 `tenant + scene + event.type` 倒排索引粗筛 + AST 预编译。`RuleMatcher` 接口要支持"批量候选规则"返回方式而非按需查询；
- **AI 评估节点** —— `ConditionEvaluator` 接口本就开放，预留 `LLMConditionEvaluator` 类型零成本，v3 接入。

**明确不预留**（场景外）：

- 不做实时 OLAP 类指标（如"全网过去 1 分钟平均价格"）—— 这不是规则引擎职责，由外部指标平台供给；
- 不做动作编排 / 执行 —— 引擎纯决策（D60），"命中后做什么"及其 SAGA / 强事务 / 补偿交给消费方 / 流程引擎（首选 Flowable），不内嵌进决策引擎。

---

## 二、核心设计决策（已落定，逐条权衡见 [`00-decisions.md`](./00-decisions.md)）

下表与 `00-decisions.md` 的 D1-D30（及后续关键决策 D55）对应；"选择"列是最终落定，"取舍"列概括为什么这么选。

| # | 决策 | 选择 | 取舍 |
|---|------|------|------|
| D1 | **场景定位** | **第一阶段聚焦运营/营销/活动；抽象按风控/反欺诈级别预留**，演进路线 风控 > 营销 > 通用 > AI Agent | 按优先级演进，避免后期推倒重来。CEP / RETE / 预编译不在 v1 实现，但 `RuleMatcher` / `MetricSource` 接口必须容纳 |
| D2 | **规则表达式** | **自研 AST**（JSON 序列化，`{op:"AND", children:[...]}`） | 唯一真相、前端可视化最友好。CEL / JsonLogic 互转留到通用平台阶段（v2+）再考虑 |
| D3 | **多租户** | **schema 起一等公民**，`tenant_id` 贯穿所有表，索引前缀含它 | 多租户成本在 schema 上极小（多一列 + 索引前缀），收益巨大。默认租户也是一个租户 |
| D4 | **动作协议** | ~~声明式优先 + SPI 兜底~~ **已被 D60 作废**（引擎纯决策化，动作子系统整体移除；编排交流程引擎） | — |
| D5 | **触发模型** | **单事件 + MetricSource 内 SQL 聚合**覆盖 80% 时间窗场景 | v1 不引入 Flink。`RuleEvent` / `MetricSource` 协议允许后续接入序列事件和窗口指标 |
| D6 | **版本与灰度一等公民** | 规则有版本号，发布即快照不可变；灰度按 % 放量 + 按用户标签命中 | 安全、可回滚、A/B 实验内置。复杂分桶留到接 ABTest 平台时切外部，hash bucket 算法内置兜底 |
| D7 | **Dry-run 一等公民** | 走完整评估链路（Matcher / Pre-Gate / Context / AST），节点级 trace 落 `dry_run_session`；前端有专门试算面板 | 评估层试算 + 节点级 trace 与真实执行同源；引擎纯决策化后（D60）无动作层，dry-run 即决策输出预览 |
| D8 | **性能目标** | **千级 QPS / <500ms 起步**，万级 QPS / <100ms 为下一档目标 | 接口按万级 QPS 设计（候选规则倒排索引、AST 预编译、指标缓存接口）；实现按千级 QPS 起步 |
| D9 | **持久化分层** | **全 MySQL 起步**，数据保留 30 天 | 运维一致、起步最简。观测到日志表膨胀或查询慢，切冷热分级或引 ClickHouse / ES |
| D10 | **AI 评估节点** | **预留 `LLMConditionEvaluator` 接口**，v1 不实现 | `ConditionEvaluator` 接口本就开放，预留零成本；当前 LLM 不确定性大，不适合做核心决策 |
| D11 | **Job 模式 + 调度器** | **Job 作为 Trigger 适配器**（不是独立第四概念）；**xxl-job 作首个 Scheduler 实现，接口化预留可换** | 定时类规则共用标准评估链路（`eventId = hash(jobRunId + subjectId)` 幂等）；xxl-job 提供 HA / 重试 / admin UI，未来换 Quartz / 云调度仅替换 `Scheduler` Adapter。Job 仅对 PUSH / HYBRID Scene 开放 |
| D12 | **Rule.kind 多态 + 输出预留** | **五种 kind 均已实装**（`AST_BOOLEAN` v1 / `SCORECARD` D12 / `DECISION_TREE` / `DECISION_TABLE` D42 / `EXPRESSION_SCRIPT` D66）；`Rule.kind` / `EvalResult` 多态字段（顶级 `score?/category?/decision?`，无独立 `output` 包装层）/ `ConditionNode.weight` 由占位转为启用 | 评分卡 / 决策树 / 决策表 / 脚本类形态的演进锚点；现在加列零成本，后期加列要 alter / 改 API 签名。决策流由 D4 工作流引擎扩展点承载，决策集留到 08-evolution `Scene.executionStrategy` |
| D13 | **Scene 元数据 schema** | Scene 上落 **4 个字段**：`payloadSchema` / `subjectType` / `defaultParams` / `eventTypes`；类型级 `params` schema 留到 04-extension | 发布校验 / dry-run mockEvent / 事件接入校验都依赖；现在加 JSON 列零成本，后期补要 alter + 数据迁移。v1 仅 `payloadSchema` + `eventTypes` 启用校验，`subjectType` 仅 USER 实装 |
| D14 | **权限与审计** | **占位字段（`created_by` / `updated_by` / `published_by`）+ `audit_log` 表**；鉴权交上游网关（JWT/SSO），actor 由 header 注入 | 公司身份系统统一管，引擎不内置 RBAC；占位 0 成本，后期接入零阻力。跨租户管理员靠特殊 actor 约定，不入 schema |
| D15 | **评估失败语义** | **单节点失败 → 整树继续按短路求值** + `EvalResult.errorCode` 槽位 + 规则间隔离 + `HIT/MISS/BLOCKED/ERROR` **四态**对账（D22） | PUSH 默认安静失败，不影响决策输出；PULL 返回 `{satisfied, errorCode}`，调用方按业务策略（fail-secure / fail-open）决策 |
| D16 | **链式触发** | ~~显式禁止 Action 产引擎事件~~ **已被 D60 作废**（动作子系统整体移除，链式触发议题不复存在） | — |
| D17 | **配置热加载** | **DB 轮询 15s + 评估快照锁定** + `RuleVersionWatcher` 接口预留；多实例最终一致 | 评估开始时拍快照（D6 派生），session 内不受切版本影响。v2 可换 MQ 推 / 配置中心，业务侧零改动 |
| D18 | **Action 失败补偿语义** | ~~默认 continue-on-error + failFast + 补偿~~ **已被 D60 作废**（动作派发整体移除，失败传播 / 补偿语义不复存在） | — |
| D19 | **规则发布事务性** | **单条规则原子发布**（状态机 `DRAFT → PUBLISHED`，单 DB 原子事务完成状态机+新 version+audit_log；失败整事务回滚、规则保持原态、无中间态）；批量由前端逐条提交 | 不可变快照（D6）下回滚 = 用旧版本快照建新草稿走标准发布产新版本号；不做批量原子 API（事务跨度大）；不做发布前自动 dry-run（留 07-operability 决策） |
| D20 | **v1 高吞吐评估期落地范围** | metric 批量预拉 + 异步 Dispatcher + 输入闭合校验 + Watcher SPI 多态化（v1 落）；预编译 Predicate SPI 预留 v1.5 切换 | 评估线程不被同步副作用拖慢；N+1 metric round-trip 压成 1 次 mget；强类型契约为预编译铺路。alpha 共享 / 嵌入式 SDK / EXPRESSION 叶子留 [`08-evolution.md`](./08-evolution.md) §2.13 / §2.14 |
| D21 | **评估观测数据异步写入** | `TraceWriter` 异步批写（评估期内存累积 + session 结束入队列 + 消费者池 batch insert，复用 D20 §2 队列模型）；与 `audit_log` 同步事务严格分离 | 单次评估 50-1000 行 trace 同步写吃掉 P99 预算 10-40%；异步批写后热路径零阻塞，失败降级丢弃 + 告警，不影响 `EvalResult`；ConditionNode 与 Pre-Gate trace 同通道；运维参数（队列容量 / 批大小 / flush 间隔等）留 07-operability |
| D22 | **Pre-Gate 拦截对账状态** | 引入第四态 `BLOCKED`；四态：`HIT / MISS / BLOCKED / ERROR`；`evaluation_session.blocked_by` 记录拦截 Gate 类型 | Pre-Gate 拦截语义不同于 AST 求值不满足（MISS）；命中率分母仅 HIT+MISS |
| D23 | **`evaluation_session` 幂等键** | `(tenant_id, event_id)` 单一 UK，同一事件永远只评估一次（by design）；Replay 换新 eventId；版本切换后测新规则走 dry-run | 幂等最强、设计最简；Replay 符合 MQ 标准重推语义；v1 不引入 Replay 专用表 |
| D24 | **Scene 变更热加载** | 新增独立 `SceneWatcher` SPI；v1 实现 `DbPollingSceneWatcher`（30s 轮询）；与 `RuleVersionWatcher` 平级，职责独立 | Scene 变更频率低于规则；bindings 变更触发 MetricSource 资源预热/卸载，DISABLED 从 Matcher 路由表摘除 |
| D25 | **Context 构建并发模型** | `CompletableFuture.allOf()` 并行 + 各 MetricSource 自管执行资源；Subject 加载（`SubjectLoader` SPI，v1 `UserProfileLoader`）与 metric 并行；单 metric 失败归 D15 METRIC_FETCH_FAIL，Subject 失败整 Context 失败 | 避免引擎侧共享线程池调优负担；最慢 IO 决定整体等待时间，而非串行累加 |
| D26 | **Decision 实体 + 多规则命中合成策略** | Decision 为 Tenant 级一等实体（code + priority）；Rule 通过 `RuleDecisionBinding` 关联 Decision（支持可选 score 区间，v1 仅 `AST_BOOLEAN` 场景直接 1:1 绑定）；Scene 声明 `decisionStrategy`（v1 仅 `HIGHEST_PRIORITY`；D29 已落 DDL NOT NULL DEFAULT，PUSH/HYBRID 缺省见 D29）；`EvalResult` 新增 `finalDecision` + `hitDecisions`（D60：Decision 不再挂 action） | PULL 场景风控输出刚需；`HIGHEST_PRIORITY` 覆盖 95% 业务需求 |
| D27 | **Action 归属从 Rule 迁移到 Decision** | ~~Action 挂到 Decision~~ **已被 D60 作废**（动作子系统整体移除，Action 不再属于任何实体，`Decision.actions` 字段已删） | — |
| D28 | **Decision.actions 变更生效时机** | ~~快照生效时机~~ **已被 D60 作废**（`Decision.actions` 字段已删，议题不复存在） | — |
| D29 | **PUSH/HYBRID Scene decisionStrategy 默认值** | PUSH/HYBRID Scene 缺省等价 `HIGHEST_PRIORITY`，消灭决策策略缺省导致的合成歧义；PULL Scene 不参与合成 | `HIGHEST_PRIORITY` 覆盖绝大多数场景；消灭整类"漏配静默失效"问题，无配置成本 |
| D30 | **providedMetrics — 业务方随评估携带指标值** | 评估请求体新增 `providedMetrics` 字段；Metric 注册新增 `allowProvided` 标志（按 sourceType 给推荐默认值，详见 D30）；`PROVIDED` 值优先于 sourceType 取数；只活在本次评估，不持久化 | 消灭注册/换绑等场景的冗余取数；平台按 metric 粒度控制信任边界；引擎不承担业务数据存储职责。**注：公开侧 `providedMetrics` 已被 D55 退场（HTTP 评估只收 payload），仅内部 SDK/Job 注入链路保留** |
| D55 | **场景输入参数清单 — 公开评估只收 payload** | 公开评估接口移除 `providedMetrics`（HTTP 调用方只传事件 `payload`）；发布期冻结 `rule_version.payload_dependencies` 随快照下发；新增公开发现接口 `GET /api/v1/rule/scenes/{sceneCode}/input-manifest`；删除 `getProvidedMetrics` 服务/端点；评估期缺必填或类型不符返回 400（`MISSING_REQUIRED_INPUT` / `INPUT_TYPE_MISMATCH`） | 公开侧不暴露 provided metric 概念，输入边界单一（payload）；payload 依赖随快照冻结可发现可校验；信任边界收敛。详见 [`00-decisions.md`](./00-decisions.md) D55 |
| D60 | **规则引擎纯决策化，移除动作子系统** | 引擎收敛为**纯决策**：只产出 Decision（`finalDecision` / `hitDecisions`），不再有动作。删 `ActionHandler` SPI / `@ActionType` / `ActionContext` / `ActionResult` / `Decision.actions` / `action_execution` 表 / `SendAlertHandler` / 动作派发与配置；作废 D4 / D16 / D18 / D27 / D28 / D57。"命中后做什么"交给消费方 / 流程引擎（首选 Flowable，以 Service/HTTP Task 调本引擎决策节点） | 对标 OPA（策略出决策 + PEP 执行）/ Camunda（DMN 决策 + BPMN 编排），决策与编排彻底分层；引擎本职是"给定输入产出决策"，编排不内嵌。详见 [`00-decisions.md`](./00-decisions.md) D60 |

> **派生约束**（由上述决策推出、值得单独标注的工程约定，详见 §六设计原则）：
>
> - 指标 (Metric) 是一等公民，独立注册 —— 由 D5 推出，规则 AST 只引用 `metricCode` 不内嵌 SQL；
> - 节点级 trace 一等公民 —— 由 D7 推出，AST 每节点 actualValue + satisfied 必落库；
> - 正反对称流水线 —— 任何带"撤销"语义的场景需要（发券后退券、加分后扣分），从 v1 就预留；
> - 不可变运行时契约 —— `RuleEvent` / `EvalContext` / `EvalResult` / `RuleNode` 全 `@Value`，跨层只传值。

---

## 三、顶层架构

```
┌──────────────────────────────────────────────────────────────────┐
│                       Trigger Sources                            │
│   MQ adapter / HTTP adapter / Job adapter / SDK push / Replay     │
│   全部翻译成 RuleEvent (@Value POJO, tenant + scene + payload)    │
│   (source 枚举: HTTP / MQ / JOB / SDK / REPLAY)                   │
└─────────────────────────────────┬────────────────────────────────┘
                                  ▼
┌──────────────────────────────────────────────────────────────────┐
│  Rule Matcher                                                    │
│  按 (scene, eventType) 倒排索引找候选 RuleVersion 快照 (D17)      │
│  (RuleDefinition.current_version 解析到不可变 rule_version 行)    │
└─────────────────────────────────┬────────────────────────────────┘
                                  ▼
┌──────────────────────────────────────────────────────────────────┐
│  Pre-Gate Chain  (准入闸门, 独立于 AST)                          │
│  灰度命中 ROLLOUT (v1 唯一, D52) — List<Gate>                    │
└─────────────────────────────────┬────────────────────────────────┘
                                  ▼
┌──────────────────────────────────────────────────────────────────┐
│  Context Builder  (按需取指标)                                   │
│  扫 AST 收集涉及的 metricCode → MetricRegistry 并发取数 → EvalContext │
└─────────────────────────────────┬────────────────────────────────┘
                                  ▼
┌──────────────────────────────────────────────────────────────────┐
│  AST Evaluator  (访问者模式, 纯函数)                             │
│   ├─ LogicNode (AND/OR/NOT) → 递归 + 短路                        │
│   ├─ ConditionNode → ConditionEvaluatorRegistry.route            │
│   └─ 每节点 actualValue + satisfied 落 trace                      │
└─────────────────────────────────┬────────────────────────────────┘
                                  ▼
┌──────────────────────────────────────────────────────────────────┐
│  Decision Output  (纯决策, D60)                                  │
│  合成 finalDecision + hitDecisions → EvalResult                  │
│  "命中后做什么"（发券 / 拦截 / 通知）交给消费方 / 流程引擎       │
│  （Flowable 为预期搭档，以 Service/HTTP Task 调本引擎决策节点）  │
└──────────────────────────────────────────────────────────────────┘

旁路:
  • System Audit           (系统行为: evaluation_session + node_trace)
  • Operation Audit        (人的行为: audit_log, D14, 与系统行为严格分离)
  • Idempotency Guard      (Redis trySet + DB uk 双兜底)
  • Versioning & Rollout   (规则版本快照 + 灰度命中)
  • Metric Aggregator      (规则命中率 / 延迟分位 → Prometheus)
```

---

## 四、核心抽象（一句话清单）

> 下表略去**横切审计字段**（`created_by` / `created_at` / `updated_by` / `updated_at`，D14；可由人编辑的配置对象都横切包含），详见 [`01-concepts.md`](./01-concepts.md) §三 顶部横切说明。表内仅列出"职责相关"与"该抽象专属"的字段。

| 抽象 | 职责 | 不可变 |
|------|------|--------|
| `RuleEvent` | 触发器翻译后的纯 POJO 事件契约（含 tenant / scene / payload / occurredAt） | ✅ |
| `EvalContext` | 单次评估的指标快照 + 用户画像 + 业务身份 | ✅ |
| `Subject` | 业务主体快照：`{id, type, attributes}`，结构由 `Scene.subjectType` 决定；v1 仅 `USER` 实装（D13），未来扩展 `ACCOUNT / DEVICE / ORDER` 等仅扩 enum 不改契约（枚举定义见 01-concepts §3.2） | ✅ |
| `RuleNode` (sealed) | AST 节点：`AndNode` / `OrNode` / `NotNode` / `ConditionNode`；`AndNode` / `OrNode` 可携可选 `displayLabel`，用于前端"分组卡片"视觉渲染（数据模型不固化 Group 实体） | ✅ |
| `RuleDefinition` | 持久化规则定义（kind + trigger + ast + preGates + current_version + status）；`kind ∈ {AST_BOOLEAN, SCORECARD, DECISION_TREE, DECISION_TABLE, EXPRESSION_SCRIPT}`（五种均已实装）；`status ∈ {DRAFT, PUBLISHED, DISABLED}`（无中间态）；`current_version` 指向 `rule_version` 表中当前生效的不可变快照；发布是单条 DB 原子事务（`DRAFT → PUBLISHED`，失败整事务回滚保持原态，D19） | — |
| `RuleVersion` | 规则发布产生的不可变版本快照行（D6 + D19）：含 `(ruleId, version)` 主键 + AST/preGates（含 ROLLOUT 灰度）/**decision_bindings** 冻结副本；运行时按 `(scene, eventType)` **倒排索引**直接拿 RuleVersion 快照列表（D17：`current_version` 在索引预热时已解析，无运行时二次查询） | ✅ |
| `EvalResult` | 评估输出契约多态：`{satisfied, score?, category?, decision?, finalDecision?, hitDecisions, trace, errorCode?, errorMessage?, failedNodeIds?, partial?}`；D12 多态 + D15 失败槽位 + D26 Decision 合成输出 | ✅ |
| `Decision` | Tenant 级决策定义（D26 + D60）：`{tenant_id, code, name, priority, description}`；Tenant 内 `code` 唯一；`priority` 数值越小优先级越高（如 REJECT=1, REVIEW=2, PASS=100）；引擎纯决策化，Decision 仅承载决策码 / priority / name，"命中后做什么"交给消费方 / 流程引擎（D60） | — |
| `RuleDecisionBinding` | Rule 与 Decision 的关联（D26，版本快照化）：`{rule_id, decision_code, score_range_min?, score_range_max?}`；v1 `AST_BOOLEAN` kind 直接 1:1 绑定；score 区间在 D12 SCORECARD kind 时启用；发布时冻结进 `rule_version.decision_bindings`（DDL 落地列名，无 `_snapshot` 后缀） | — |
| `Scene` | Tenant 内的业务域命名空间 + Matcher 路由键 + 数据源初始化锚点 + 使用模式声明（PUSH / PULL / HYBRID）+ 元数据 schema（`payloadSchema` / `subjectType` / `defaultParams` / `eventTypes`）+ 决策合成策略（`decisionStrategy`，D26）。metric 治理白名单已移除（D54：metric tenant 级可用） | — |
| `SceneMetricBinding` | Scene 与 Metric 的可见性绑定，规则只能引用本 Scene 绑定的 metric | — |
| `MetricSource` | 按 `metricCode` 取指标，支持实时 / 预计算 / 外部指标平台（Java SPI 接口名 `MetricSourceHandler`，见 [`04-extension.md`](./04-extension.md) §四） | — |
| `MetricRegistry` | 注册中心，启动扫 `@MetricSourceType` 注解 + 数据库声明式指标；并发契约：读路径 thread-safe 且不阻塞热路径，评估期内快照稳定（具体并发策略——不可变快照 / ConcurrentHashMap / copy-on-write 等——由实现层选择） | — |
| `ConditionEvaluator` | 纯函数判定 `(ConditionNode node, EvalContext ctx) → boolean`；`actualValue` 由 AST Evaluator 从 EvalContext 提取后写入 node_trace，不在返回值中（见 [`04-extension.md`](./04-extension.md) §2.1） | — |
| `ConditionTypeRegistry` | 注册中心，启动扫 `@ConditionType` 注解 | — |
| `RuleEvalVisitor` | 遍历 AST，短路 + 节点级 trace | — |
| `EvaluationSession` | 一次评估的持久化记录（D23 幂等锚点）：1 行 per event；`status ∈ {HIT/MISS/BLOCKED/ERROR}`（D22 四态）；`(tenant_id, event_id)` DB uk；同步写（D21）；dry-run 写独立 `dry_run_session` 表 | — |
| `DryRunSession` | 试算评估的隔离记录（D7 + §3.16）：无 UK 约束，同 eventId 可重复 dry-run；不计入生产统计报表；保留期短于生产 | — |
| `Gate` | 准入闸门接口（v1 仅灰度命中 ROLLOUT，D52） | — |
| `IdempotencyGuard` | Redis trySet + DB uk 双兜底 | — |
| `ScheduledTask` | 通用调度任务配置：`task_type`（TRIGGER / OUTCOME_INGESTION）+ cron + typed `config`（TRIGGER 为 `TriggerConfig{sceneCode, eventType, subjectQuery}`），按 `TaskExecutor` SPI 分发 | — |
| `ScheduledTaskExecution` | 单次调度任务运行的记录（含 `taskRunId`、processedCount、success/error 计数、错误明细），TRIGGER 与 `EvaluationSession` 关联 | — |
| `Scheduler` | 调度器抽象接口：`schedule` / `unschedule`（cron→Runnable）；`XxlJobSchedulerAdapter` 为分布式实现、`ThreadPoolSchedulerAdapter` 为单机 dev 实现，未来可替换 PowerJob / 云调度 | — |
| `AuditLog` | 操作审计记录（D14）：`{tenant_id, actor, target_type, target_id, action, before_snapshot, after_snapshot, operated_at, trace_id}`；与 `evaluation_session` / `node_trace` 是不同维度（人的行为 vs 系统行为），严格分离 | ✅ |
| `RuleVersionWatcher` | 规则变更感知接口（D17 + D20 §4 固化为正式 SPI）：`subscribe(callback) / pull(since) / status`；契约要求实现方满足"最终一致 + 至多一次 callback 重复（消费方幂等）+ 启动期一次性全量拉"。v1 唯一实现 `DbPollingRuleWatcher`（默认 15s）；多 backend（MQ / Nacos / ZK）切换详见 [`08-evolution.md`](./08-evolution.md) §2.14 | — |
| `SceneWatcher` | Scene 配置变更感知接口（D24，与 `RuleVersionWatcher` 平级）：`subscribe(callback) / pull(since) / status`；监听 `scene`（DDL 落地表名，旧称 `scene_definition`）变更，触发 MetricSource 资源预热/卸载 + Matcher 路由表更新（D54 后无 scene_metric_binding）。v1 唯一实现 `DbPollingSceneWatcher`（默认 30s，Scene 变更频率低于规则）；SPI 契约与 `RuleVersionWatcher` 对齐 | — |
| `SubjectLoader` | 主体加载 SPI（D25）：`load(subjectId, subjectType, event) → Subject`；v1 唯一实现 `UserProfileLoader`（`subjectType=USER`，查 `user_profile` 表）；与 metric 并行加载进 `EvalContext` | — |
| `SubjectLoaderRegistry` | `SubjectLoader` 注册中心（D25）：按 `subjectType` 路由到对应实现；与 `MetricRegistry` 同款模式 | — |
| `RuleVersionExecutor` | 规则版本执行 SPI（D20 §5）：`execute(RuleVersion, EvalContext) → EvalResult`。v1 默认实现 `InterpretedExecutor`（Visitor 树遍历）；v1.5 引入 `CompiledExecutor`（Janino / LambdaMetafactory 编译产物），由 `ExecutorRegistry` 按 RuleVersion 灰度切换。`rule_version.compiled_predicate_ref` 列预留供编译产物引用 | — |
| `ExecutorRegistry` | `RuleVersionExecutor` 注册中心（D20 §5）：按 RuleVersion 选择 Executor 实现。v1 仅注册 `InterpretedExecutor`；v1.5 加 `CompiledExecutor` 并支持按版本灰度切换（兜底回退到 Interpreted）。注册时机与 `ConditionRegistry` / `MetricRegistry` 对齐 | — |
| `TraceWriter` | 评估观测数据异步写入通道（D21）：评估线程在 `EvalContext` 内开 `TraceCollector` 累积内存级 `TraceRow`（无锁），`EvalResult` 出树时一次性 `submit(batch)` 入内部有界队列，独立消费者线程池按条数 / 时间阈值 batch insert `node_trace`（含 ConditionNode trace + Pre-Gate `PRE_GATE_BLOCKED` trace，同通道）。与 D20 §2 Dispatcher 队列**独立**（语义与生命周期不同）。失败降级丢弃 + counter 告警，不阻塞热路径、不抛异常、不回写 `EvalResult.errorCode`。与 `audit_log` 同步事务严格分离（审计强一致是 v1 红线，trace 是观察数据） | — |

---

## 五、文档导航

**编排原则**：按**读者动机**分层，不按代码包；每篇独立可读 20 分钟，定位明确不重叠。

```
0  入口      → README                 我是谁 / 为什么存在 / 一句话架构 / 怎么读下去
1  概念词典  → 01-concepts            术语 + 心智模型(Event/Rule/Condition/Action/Context)
2  运行时    → 02-runtime             "从一个事件进来到动作落地"全链路 + 时序图
3  规则表达  → 03-rule-expression     AST 结构 / 操作符 / 短路 / 节点级 trace
4  扩展点    → 04-extension           我要加一个条件/动作/指标源 — 复制粘贴级指南
5  存储模型  → 05-storage             规则定义表 / 执行记录 / 元数据注册表 + DDL
6  前端架构  → 06-frontend            元数据驱动编辑器 / 三栏布局 / dry-run UI
7  可运维    → 07-operability         幂等 / 审计 / 试算 / 灰度 / 版本 / 监控告警
8  演进     → 08-evolution           落地路线 + 决策时间线 + 已否决方案
9  工程骨架 → 09-skeleton            Maven 模块 / 包结构 / SPI 落点 / 依赖方向 / 测试组织
10 API 契约 → 10-api-contract        对外接口签名 / DTO / errorCode + i18n（前后端共读）
   案例库    → examples/              端到端真实场景(风控/营销/活动…)
   归档     → archive/                历史 RFC / 被替换的方案
```

| 文档 | 解决什么疑问 | 状态 |
|------|--------------|------|
| **README** | 这是什么？我该读哪篇？ | ✅ |
| 01-concepts | "Rule / Condition / Action 到底指什么？" | ✅ |
| 02-runtime | "一个事件进来后，引擎内部发生了什么？" | ✅ |
| 03-rule-expression | "我能写多复杂的规则？怎么表达嵌套逻辑？" | ✅ |
| 04-extension | "我要加一个新条件 / 动作 / 指标，从哪下手？" | ✅ |
| 05-storage | "数据怎么存？我能直接查 SQL 看到什么？" | ✅ |
| 06-frontend | "运营怎么自助配规则？前端怎么和后端对齐？" | ✅ |
| 07-operability | "上线后出问题怎么排？怎么灰度？怎么试算？v1 可用性边界？" | ✅ |
| 08-evolution | "现在做到哪一步？为什么没选 XXX 方案？" | ✅ |
| 09-skeleton | "新代码放哪个模块/包？SPI 接口在哪？业务方依赖哪个 jar？测试怎么组织？" | ✅ |
| 10-api-contract | "调用方要传什么 / 收到什么？有哪些 errorCode？SDK 怎么用？" | ✅ |
| examples/ | "真实业务场景长什么样？" | ✅ README 就位（子案例待沉淀） |

**推荐阅读顺序**：

- **新人首次** → README → 01-concepts → 02-runtime → examples/ 任选一个
- **接入新场景** → 01-concepts → 04-extension → examples/
- **设计 / 评审** → README → 02-runtime → 03-rule-expression → 08-evolution
- **前端同学** → 01-concepts → 06-frontend → 04-extension §"元数据契约"
- **运维 / SRE** → 02-runtime → 07-operability → 05-storage §索引

**写作约定**：

- README ≤ 300 行，只承载导航与一句话决策；细节链到子文档。
- 子文档单文件聚焦单一主题；超过 800 行考虑拆分。
- 每篇开头三件套：**位置定位 / 前置阅读 / 解决什么疑问**。
- 改方向在 `08-evolution` 决策时间线追加一行，被替换的旧方案挪到 `archive/` 加 banner。

---

## 六、设计原则

1. **取数 vs 判定分层** —— `MetricSource` 纯 IO，`ConditionEvaluator` 纯函数。条件判断无副作用、不依赖 Spring，单测覆盖率天然高。
2. **不可变运行时契约** —— Event / Context / EvalResult / RuleNode 全 `@Value`。跨层只传值，无共享可变状态。
3. **注册中心 + 元数据驱动** —— 新增条件 / 指标 = 新建 Spring Bean + 对应注解（`@ConditionType` / `@MetricSourceType`）。前端 UI 由元数据派生，零前端发版。
4. **判定与编排解耦** —— 引擎只判定并产出决策（Decision），"命中后做什么"由消费方 / 流程引擎编排（D60）；中间通过 `EvalResult` POJO 衔接。
5. **Metric 一等公民** —— 指标独立注册（按 `metricCode`），规则 AST 只引用 `metricCode` 不内嵌 SQL；同一指标跨规则复用、Scene 级白名单治理、cache 与预热由 MetricSource 自管。
6. **节点级 trace 一等公民** —— AST 每个节点的求值过程必须可观测，作为产品基础能力而非可选项。
7. **试算与真实执行同源** —— Dry-run 走完整评估链路产出决策预览；保证试算结果和真实评估一致。
8. **多租户从 schema 起** —— `tenant_id` 是所有表的第一个字段，索引前缀必须包含它。

---

## 七、版本史

| 日期 | 改动 |
|------|------|
| 2026-05-25 | 初版总览。独立产品定位，不挂在 activity-reward-v2 之下。 |
| 2026-05-25 | D1-D10 决策落定（见 [`00-decisions.md`](./00-decisions.md)）。场景与性能按优先级演进：第一阶段聚焦运营/营销/活动 + 千级 QPS，抽象按风控级别预留。§一 / §二 同步更新。 |
| 2026-05-25 | 派生决策：Scene 作为 RuleEvent 必填字段（业务域命名空间，用于 Matcher 缩小搜索范围）；Rule/RuleGroup/Condition **三层模型**（RuleGroup 是独立实体，跨 Rule 可复用、可命名、可权限化）。§四 抽象表追加 `RuleGroup`。 |
| 2026-05-25 | **回退为两层模型**（Rule → AST → Condition）。原因：三层 → 两层有信息损耗（Group 实体降级为 AST `displayLabel` 字段），方向反向不可逆，重审后采纳更轻的方案。`AndNode` / `OrNode` 携可选 `displayLabel` 字段供前端渲染"分组卡片"，保留运营分组心智但不在数据模型固化 Group。 |
| 2026-05-25 | 派生决策：**Scene 是 metric 治理边界 + 数据源初始化锚点**。Metric 定义仍在租户/全局级，Scene 显式声明可见的 metric 集合（白名单）；Scene 启动时按绑定预热 MetricSource（连接池 / HTTP client / 缓存）。新增表 `scene`（DDL 落地表名，早期设计文档中曾称 `scene_definition`）+ `scene_metric_binding`。§四 抽象表追加 `Scene` / `SceneMetricBinding`。 |
| 2026-05-25 | 派生决策：**Action 可选 + Scene.dominantMode**。引擎支持两种使用模式——`PUSH`（异步触发，必配 Action）/ `PULL`（同步评估，返回 EvalResult，Action 通常为空）/ `HYBRID`（两者皆可）。Scene 声明 `dominantMode` 决定 API 入口与前端 UI 行为。同时对称引入 `scene_action_binding`（仅 PUSH / HYBRID Scene 需要），actionType 治理与 Metric 对齐，防止跨域越权。 |
| 2026-05-25 | **D11 落定：Job 模式 + xxl-job 调度器**。Job 作为 Trigger 适配器（不引入第四个一等公民），到点查询主体→批量合成 `RuleEvent` 注入标准评估链路。调度器抽象为 `Scheduler` 接口，xxl-job 为首个实现（HA / 重试 / admin UI），未来可替换。Job 仅对 PUSH / HYBRID Scene 开放，`eventId = hash(jobRunId + subjectId)` 与 `record_no` 模式一致幂等。§四 抽象表追加 `JobDefinition` / `JobExecution` / `Scheduler`。 |
| 2026-05-25 | **D12 落定：Rule.kind 多态 + 输出类型预留**。为评分卡 / 决策树 / 决策表 / 脚本等未来形态落 3 个 v1 占位：`Rule.kind` 枚举字段（v1 仅 `AST_BOOLEAN`）、`EvalResult.output` 多态（`{satisfied, score?, category?, decision?}`，v1 仅填 satisfied）、`ConditionNode.weight` 可选字段（v1 评估器忽略，SCORECARD kind 启用）。决策流由 D4 工作流引擎扩展点承载，决策集策略留到 08-evolution `Scene.executionStrategy`。§四 抽象表 `RuleDefinition` / `EvalResult` 行同步更新。 |
| 2026-05-25 | **D13 落定：Scene 元数据 schema**。Scene 上落 4 个 JSON 字段：`payloadSchema`（payload 允许字段+类型）/ `subjectType`（USER/ACCOUNT/DEVICE/ORDER/CUSTOM）/ `defaultParams`（timezone / currency / 默认 rateLimit / cacheTtl 等）/ `eventTypes`（允许的 eventType 白名单）。v1 仅启用 `payloadSchema` + `eventTypes` 的校验，`subjectType` 仅 USER 实装。类型级 `params` schema 留到 04-extension。 |
| 2026-05-25 | **D14 落定：权限与审计**。占位字段（`created_by` / `updated_by` / `published_by` + `_at`）+ 新增 `audit_log` 表（actor / target / action / before/after 快照）。不内置 RBAC，鉴权交上游网关（JWT/SSO），actor 由 header 注入。跨租户管理员靠特殊 actor 约定。§四 抽象表追加 `AuditLog`。 |
| 2026-05-25 | **D15 落定：评估失败语义**。单 ConditionNode 失败 → satisfied=false 但整树继续按 AND/OR/NOT 短路求值；`EvalResult` 加 `errorCode` / `errorMessage` / `failedNodeIds` / `partial` 槽位；规则间隔离（单条失败不影响 (scene+eventType) 下其他规则）；对账分 `HIT / MISS / ERROR` 三态（D22 追加 BLOCKED 为四态，见下行），ERROR 不计入命中率分母。PUSH 模式安静失败不派发 Action；PULL 模式返回 `{satisfied, errorCode}` 调用方按 fail-secure / fail-open 决策。§四 抽象表 `EvalResult` 行同步。 |
| 2026-05-25 | **D16 落定：链式触发**。显式禁止 Action 产引擎事件，`ActionHandler.execute` 返回 `ActionResult { status, errorCode?, errorMessage?, retryable }` 而非新事件列表；业务需要链式走外部 MQ + 上游重推 RuleEvent，引擎不感知。`RuleEvent.source` 记录来源（HTTP / MQ / JOB / SDK / REPLAY），不带链式标识。§四 抽象表 `ActionHandler` 行更新、追加 `ActionResult`。 |
| 2026-05-25 | **D17 落定：配置热加载**。DB 短轮询（默认 15s，可配 `engine.rule.matcher.cache-refresh-interval-seconds`）+ 内存倒排索引 `(scene, eventType) → List<RuleVersionSnapshot>`；evaluation_session 开始时拍快照，整 session 用同版本（D6 派生）；多实例最终一致，毫秒~15s 窗口；灰度桶基于 `(subjectId, ruleVersionId)` hash 不依赖实例。`RuleVersionWatcher` 接口预留，v2 可换 MQ / Nacos。§四 抽象表追加 `RuleVersionWatcher`。 |
| 2026-05-25 | **D18 落定：Action 失败补偿语义**。默认 continue-on-error，单 Action 失败不影响同 Rule 后续 Action 也不影响 `EvalResult.satisfied`；`retryable=true` 入重试队列，`retryable=false` 直接落 FAILED；Action 级可声明 `failFast=true`，失败后同 Rule 内 `sortOrder` 大于本 Action 的后续 Action 标 SKIPPED（D27 落定后语义已更新为同 Decision 内）；补偿不自动触发，由 D4 补偿流水线外部调度。§3.7 Action 关键边界 + §四 抽象表 ActionResult 行同步。 |
| 2026-05-25 | **D19 落定：规则发布事务性**。单条规则原子发布（状态机 `DRAFT → PUBLISHED`，失败整事务回滚保持原态、无中间态），单 DB 原子事务内完成状态机迁移 + `rule_version` 新版本快照行 + audit_log；批量发布由前端逐条提交，v1 不提供批量原子 API；回滚 = 用旧版本快照建新草稿走标准发布产新版本号，不可变快照永不覆盖。§3.4 Rule 字段表 status 枚举扩展 + §四 抽象表追加 `RuleVersion`。 |
| 2026-05-26 | **D20 落定：v1 高吞吐评估期落地范围**。市场对比（Drools / CEL / Aviator / ice）后定调四件套 + 一件预留：(1) `rule_version.metric_dependencies` 字段 + 匹配后批量预拉 metric；(2) 异步 Action Dispatcher（队列 + 多消费者，`ActionResult.errorCode` 追加 `QUEUE_OVERFLOW`）；(3) 发布期输入引用闭合校验（`UNRESOLVED_VARIABLE`）；(4) `RuleVersionWatcher` SPI 固化为正式 SPI；(5) `RuleVersionExecutor` SPI 预留 v1.5 切预编译。alpha 共享 / 嵌入式 SDK / EXPRESSION 叶子留 [`08-evolution.md`](./08-evolution.md) §2.13 / §2.14。§3.7 errorCode 集中表 + §四 抽象表追加 `RuleVersionExecutor` 行同步。 |
| 2026-05-26 | **v1 缺口补充（基于市场对比 + 评审反馈）**：(1) D20 §2 明示 v1 全局单一消费者线程池，Scene 级隔离留 §2.13；(2) D20 §1 + §3.9 metric 预拉值评估期内冻结，跨 TTL 边界仍读初始快照；(3) §3.8 补 EvalContext 7 个标准字段类型 + 语义表（D20 §3 闭合校验根集）；(4) §3.7 `ActionResult.errorCode` 追加 `TIMEOUT`；(5) §3.10 Job 关键边界明示灰度桶在引擎 Pre-Gate 计算（D6 + D11 派生）。 |
| 2026-05-26 | **v1 缺口补充（第二轮）**：(1) §3.4 Rule 字段表 `rollout` 行追加结构子表（`type` / `percentage` / `tagConditions`），明示桶号算法固定不开放自定义；(2) §3.4 Rule 关键边界追加 `DISABLED 状态从倒排索引剔除`（D17 + D19 派生），同步回填 D17 v1 落地范围；(3) §3.13 新增 Subject 章节（id / type / attributes 字段表 + v1 仅 USER + attributes 取数路径 + RuleEvent.payload 不补充约束）；(4) §3.14 新增 Pre-Gate 章节（4 类清单 + 执行顺序 + 失败语义 + 与 D15 区别 + dry-run 行为）。 |
| 2026-05-26 | **一致性修正**：(1) 灰度桶 hash 输入参数统一为 `subjectId`（D17 多实例一致性段 + §3.12 RuleVersion 关键边界两处由 `userId` 改为 `subjectId`，与 D13 subjectType 通用化对齐）；(2) §3.5 末尾追加"`node_trace` 表同时容纳 Pre-Gate 失败节点（节点类型 `PRE_GATE_BLOCKED`）"，对齐第二轮 §3.14 引入的 trace 落点描述。 |
| 2026-05-26 | **v1 取舍显化**：(1) D19 决策顶部追加发布状态机 ASCII 摘要（DRAFT→PUBLISHED + PUBLISHED↔DISABLED），统一散落各处的状态文字描述；(2) D20 §2 追加重试上限归宿（达上限 → FAILED 终态 + 走 D4 补偿，v1 不引入 DLQ）+ 进程重启数据丢失依赖上游可重推说明；(3) D17 多实例一致性段显式标注"单 session 强一致 + 跨 session 最终一致是 by design"，避免读者误以为是缺陷。 |
| 2026-05-26 | **urule "库"概念立场登记**：(1) §3.9 Metric 关键边界追加"业务共享常量建议建为只读 metric"——明示 v1 不另设 ConstantLibrary 一等概念，复用 metric 治理通道；(2) 08-evolution §四 已否决方案追加 urule 风格 FunctionLibrary（与 D20 §3 闭合校验 / D16 禁止副作用 / §3.9 metric 只读冲突）+ 独立 ConstantLibrary 条目，明示 v1 立场与未来演进路径（高频自定义函数走 D12 `kind=EXPRESSION_SCRIPT`）。 |
| 2026-05-26 | **D21 落定：评估观测数据异步写入**。`TraceWriter` 异步批写（评估期内存累积 → `EvalResult` 出树时 submit → `ArrayBlockingQueue` + 消费者池 + batch insert，复用 D20 §2 队列模型），与 `audit_log` 同步事务严格分离；队列满 / 入库失败降级丢弃 + counter 告警，**不**阻塞热路径、**不**回写 `EvalResult.errorCode`（trace 是旁路观察通道）；ConditionNode trace 与 Pre-Gate trace 走同一通道。`01-concepts.md §3.5 / §3.14` 关键边界 + `README §四` 抽象表 `TraceWriter` 行同步。 |
| 2026-05-25 | **占位声明**（不独立成 D 决策）：①  `Metric.metricVersion` 字段（v1 固定 1，语义变更走版本化）；②  `MetricRegistry` 并发契约（读路径 thread-safe 且不阻塞热路径，评估期内快照稳定；具体策略由实现层选择）；③  实时性敏感场景 `cachePolicyDefault.ttl=0` 原则。敏感数据加密 / 脱敏的 v1 范围已纳入 D14 决策详情（详见 [`00-decisions.md`](./00-decisions.md) D14 "v1 不做的"），不在本行重复声明。详见 [`08-evolution.md`](./08-evolution.md) §2.2 Metric 版本化 / §2.8 合规演进。 |
| 2026-05-30 | **D22-D24 落定（矛盾修正）**：(1) D22：Pre-Gate 拦截对账状态从"归 MISS"修正为独立 `BLOCKED` 第四态，`evaluation_session` 加 `blocked_by` 列，命中率分母仅含 HIT+MISS；(2) D23：`evaluation_session` 幂等键 `(tenant_id, event_id)` 语义显式落定为 by design，Replay 换 eventId，版本测试走 dry-run；(3) D24：新增 `SceneWatcher` SPI 与 `RuleVersionWatcher` 平级，v1 实现 `DbPollingSceneWatcher`（30s），承载 Scene 配置热加载。`README §四` 抽象表追加 `SceneWatcher`；`01-concepts §3.14` Pre-Gate 对账描述 + `§3.2` Scene 关键边界同步更新。 |
| 2026-05-31 | **D27 落定：Action 归属从 Rule 迁移到 Decision**。Rule 移除 `actions` 字段；Decision 新增 `actions` 字段；仅 `finalDecision.actions` 被派发；幂等键变更为 `(tenantId, eventId, decisionCode, actionId)`；PULL Scene Decision.actions 必须为空。`README §二` 决策表追加 D27；`§四` 抽象表 `RuleDefinition` / `RuleVersion` / `Decision` / `ActionExecution` 行同步；`01-concepts §3.4 Rule` / `§3.7 Action` / `§3.19 Decision` 同步更新。 |
| 2026-05-31 | **D28/D29 落定：Decision.actions 生效时机 + decisionStrategy 默认值**。D28：快照语义不变，UI 在修改 Decision.actions 时提示已发布规则需重新发布；D29：PUSH/HYBRID Scene 缺省 `decisionStrategy` 等价 `HIGHEST_PRIORITY`，消灭静默不派发问题。`00-decisions.md` 追加 D28 / D29；`README §二` 决策表同步；`01-concepts §3.19` 补充快照生效提示，`§3.20` decisionStrategy 说明更新。 |
| 2026-05-30 | **D26 落定：Decision 实体 + 多规则命中合成策略**。Decision 为 Tenant 级一等实体（code + priority）；Rule 通过 `RuleDecisionBinding` 关联 Decision（版本快照化，支持可选 score 区间）；Scene 声明 `decisionStrategy`（v1 仅 `HIGHEST_PRIORITY`）；`EvalResult` 新增 `finalDecision` + `hitDecisions` 字段；Decision 与 Action 正交。`README §二` 决策表追加 D26；`§四` 抽象表追加 `Decision` / `RuleDecisionBinding`，`EvalResult` 行同步；`01-concepts §一` 命名清单追加 Decision，`§3.4 EvalResult` 结构更新，新增 `§3.19 Decision` + `§3.20 RuleDecisionBinding`。 |
| 2026-05-30 | **缺失概念补全 + D25 落定**：(1) `01-concepts §3.15` 新增 `EvaluationSession` 字段表 + 四态 status 聚合语义（D22 落地）；(2) `§3.16` 新增 `DryRunSession` 独立存储结构（与生产 session 隔离、无 UK 约束、短保留期）；(3) `§3.17` 新增 Action 重试队列（独立于主派发队列，指数退避，进程重启丢失可重推恢复）；(4) `§3.18` 新增 Compensation Pipeline（引擎提供 `ActionHandler.compensate()` SPI 接入点，补偿不自动触发，由外部对账/手动操作发起）；(5) D25 落定：Context 构建并发模型，`CompletableFuture.allOf()` 并行 + 各 MetricSource 自管执行资源 + `SubjectLoader` SPI（v1 `UserProfileLoader`），`README §四` 抽象表追加 `SubjectLoader` / `SubjectLoaderRegistry`，`01-concepts §3.13` Subject 关键边界同步。|
| 2026-06-19 | **D73 落定：job_definition 重写为通用 `ScheduledTask` + `TaskExecutor` SPI 框架**。评估耦合的 `job_definition` 聚合（`scene_code`/`event_type`/`subject_query` 全 NOT NULL）重写为单表 `scheduled_task`（`task_type` 判别 + typed sealed `TaskConfig`，TRIGGER 的三字段下沉进 `TriggerConfig`）+ `scheduled_task_execution`（`processed_count` 通用计数）。`TaskExecutor` SPI 按 `TaskType`（TRIGGER / OUTCOME_INGESTION）路由，TRIGGER 为首个 executor。`@RuleJob`→`@TriggerTask`、`/admin/v1/jobs*`→`/admin/v1/scheduled-tasks*`、`JobRunner`→`TriggerExecutor`，迁移 V1_37。保留 `Scheduler` SPI / XXL 适配 / `SubjectQuery` / `EventIdHasher`。`00-decisions.md` 追加 D73；`README §四` 抽象表 `ScheduledTask` / `ScheduledTaskExecution` / `Scheduler` 行同步；`05-storage §3.10` DDL / `01-concepts §3.10` / `09-skeleton` 术语对齐。设计见 `specs/2026-06-19-distributed-ready-scheduling-and-propagation-design.md`。 |
