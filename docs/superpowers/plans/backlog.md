# 待实现功能 Backlog

整理自文档中所有"未实装 / 留到 v1.5 / 留到 v2 / 演进方向"标注。
按**执行性质**分组：主动推进序列 → 大件能力 → 触发式 → v3 远期；组内按建议执行顺序排列。

> 主动推进序列**已落地移除**：B6 Metric 版本化、B7 规则导出/导入、B1 EXPRESSION_SCRIPT evaluator、B5 预编译执行器、**B31 规则集静态分析（2026-06-18）**。"治理与效果"已落地第一件（B31），序列剩 B32 决策效果闭环 / B33 血缘（08-evo §2.27–2.28）。

---

## 一、主动推进序列（按此顺序直接做）

| 序 | # | 功能 | 来源 | 预计改动范围 | 备注 |
|---|---|------|------|------------|------|
| 1 | B32 | **决策效果闭环 / 规则有效性度量** | 08-evo §2.27 | `decision_outcome` 表（关联 sessionId/eventId + 结果标签）+ 标签回灌 API（`POST /admin/v1/decision-outcomes`）+ 按规则 / Decision 聚合 TP/FP/precision/recall + 漂移 | 把引擎从"决策工具"变"风控平台"；建在 `evaluation_session` 上；**标签语义是业务侧职责**，引擎只提供接入位 + 聚合 |
| 2 | B33 | **规则↔指标血缘与变更影响分析** | 08-evo §2.28 | `LineageIndex` 从快照抽 metricCode/decisionCode 引用建索引（挂发布事件增量更新，复用 D17 热更）+ 双向查询 API（metric→规则 / Decision→规则） | 低成本；改 metric 口径 / 下线 Decision 前看炸点；与 B31 共享"读快照抽结构"底座（已随 B31 落地，可复用 `internal/analysis` 读快照范式） |

> **已落地（从主动推进序列移除）**：
> - B31 规则集静态分析 / 冲突检测（2026-06-18，08-evo §2.26 已实装块）：7 类检查（不一致/死规则/冲突/重叠/覆盖缺口/**冗余**/未分析），`ConditionSpace` 区间推理 + 6 detector + `RuleSetAnalyzer`，hit-policy-aware、零误报；`RuleAnalysisService`（**草稿优先**）+ `GET /admin/v1/scenes/{code}/analysis` + 前端左栏摘要条入口。覆盖 AST_BOOLEAN/决策树/决策表（合取语义），**故意不做**评分卡/脚本/决策树跨树/DMN 完备性。**与原计划偏差**：前端落在左栏摘要条+抽屉（非右栏）；新增规则内冗余检测；FIRST_HIT 等优先级保守降级。
> - B1 EXPRESSION_SCRIPT evaluator（`ScriptExecutor` + `ExpressionEngine` SPI；引擎扩到 6 个 cel/aviator/qlexpress/jsonlogic/jexl/groovy。**对象池前提被取代**——SPI 走线程安全单例 + 按源码哈希缓存编译产物，无需 commons-pool2 池化非线程安全 ScriptEngine）。
> - B5 预编译执行器 `CompiledExecutor`（08-evo §2.13 / D67，2026-06-13；纯编译版 + `PrecompileMode`/`CompiledPredicateEvictor`；alpha 节点共享为可选 add-on）。
> - B7 规则导出 / 导入（2026-06-06，08-evo §2.9 + 10-api-contract §4.8–4.9）；**解锁 B15**（模板市场依赖 B7 + B11）。
> - B6 Metric 版本化（档1，2026-06-06，08-evo §2.2）。

---

## 二、大件能力（单独立项，规模大，按业务需求拉入）

| # | 功能 | 来源 | 预计改动范围 | 备注 |
|---|------|------|------------|------|
| B8 | **CEP 复杂事件处理**（D5-C） | D5-C / 08-evo §2.1 | `rule-eval-svc` + Flink；频率/序列/聚合三模式；较大 | **CEP 计算由[《实时流式风控设计》](../specs/2026-06-17-realtime-streaming-risk-control-design.md)承载**（Flink 窗口/序列/聚合 = RT-M 多窗口 + 显著性检验）；与 B29 共用 Flink+Kafka+Redis infra，**合并立项**。届时归档旧 `d5c-cep.md`/`-design.md` |
| B29 | **特征预计算 / 物化特征层** | 08-evo §2.24 | `metric_definition` 加物化档 `sourceType` + 刷新策略列；`MetricSourceHandler` 增物化实现（读 KV）+ CDC 预计算 writer（独立于热路径）；分层取数（内部物化 / 三方实时）；降级语义归 D15 | **第三代「高性能」：营销千级→风控万级 QPS 路上第一道性能墙**（比 CEP 还早撞上，08-evo 自标）。**物化 + 服务由[《实时流式风控设计》](../specs/2026-06-17-realtime-streaming-risk-control-design.md)承载**（Flink→Redis 特征库→`STREAM` handler，引擎零改动按 sourceType 路由）；与 B25 Redis `MetricCache`、B8 CEP 同一套 infra。引新 KV/列存需与 D9「全 MySQL」专门决策 |
| B11 | **跨 Scene 规则复用**（RuleTemplate / RuleFragment） | 08-evo §2.3 | 新表 `rule_template` / `rule_fragment`；发布期展开逻辑；dry-run 兼容；UI | B14 异步化、B15 模板市场的前置依赖 |
| B12 | **规则间依赖与编排**（Camunda / Flowable 或自研轻量 Flow） | 08-evo §2.4 / trae R4 | 引入工作流引擎；运维形态变化 | 引擎本身不变（D60 已纯决策化），编排在**引擎之外的 Decision 消费侧**承载；**决策点**：trae R4 约 2000 行自研 FlowEngine（7 种节点 + JSON 驱动）可作为"轻量替代 Camunda"的评估基线，规模小时成本更低；trae `flow/` + `context/` 目录有完整参考实现 |

---

## 三、触发式（条件命中再做，别预排期）

| # | 功能 | 触发条件 | 来源 | 预计改动范围 / 剩余项 |
|---|------|---------|------|--------------------|
| B9 | **节点级 trace 冷热分级** | trace 表膨胀影响查询性能 | 08-evo §2.5 | `node_trace` 热表 7 天 + 冷归档按月分区；可选 ClickHouse / ES；查询接口不变 |
| B13 | **嵌入式 SDK 生产硬化**（核心已实装 D33–D40） | P99 < 5ms 场景 | 08-evo §2.14 | 仅剩：推送式 Watcher（Mq/Nacos/Zk，秒级生效，替代现有轮询）+ 跨实例灰度桶审计；进程内评估 SDK（`rule-sdk` + starter + `RuleSource`/`RuleVersionExecutor` SPI + 注解模式）已 done。原"Action 反向回写通道"随 D60 移除动作子系统已作废 |
| B14 | **`evaluation_session` 异步化路径** | profile 显示 session insert 进热路径 P99；依赖 B11 | 08-evo §2.15 | 幂等基础设施切换（持久化 KV）；对账数据源切换；父子表时序重设计 |
| B16 | **合规演进**（字段级加密 + 审计 hash chain + 数据右遗忘） | 高合规场景 | 08-evo §2.8 | 三项均未做：落库前字段级加密 / `audit_log` hash chain 防篡改 / 按 subject 物理右遗忘。**地基**：D71 敏感字段标记（`sensitive` + `SensitiveRefsResolver`，现仅 trace 展示脱敏，非落库加密）可复用为"加密哪些字段"的依据 |
| B18 | **Scene schema 自动放量 / 回退**（按 SLO 推进） | 自动化放量需求（v2 范畴） | 08-evo §2.7 | 灰度 v1 已完成；自动化放量是 v2 范畴 |
| B25 | **取数层 v2 增强**（B21 留 v2 子项） | 各子项独立按需触发 | D45 / B21 | 剩余子项：`STREAM` sourceType 实装（需流式状态 infra，随实时流式风控 / B29）；Redis `MetricCache` 实现（SPI 已抽象，纯实现，触发=多实例共享缓存）；metric `required` 字段分级（`MetricDescriptor`+assembler 降级区分 required→ERROR / optional→继续）。**已落地移除**：OAuth2 自动刷 token（`OAuth2ClientCredentialsAuth`/`AuthScheme`）、Scene 级数据源白名单（`MetricResourceCatalog`+`RegistryMetricResourceCatalog`），随 connector 标准化 2026-06-15 落地 |
| B26 | **装载期编译取数计划**（KieBase 式编译期-运行期分离） | profile 显示评估 **CPU-bound**：近无取数 I/O（全 providedMetrics）+ 大候选集 + 高 TPS | 本会话 JMH 压测（`rule-benchmark`, 2026-06-07） | 把 `collectChosenVersions`（候选并集取 max version）从每次评估提到 `SceneRuleIndex` 装载期（`update()` 是快照入索引唯一入口），运行期只查编译好的取数计划。JMH：版本合并在 50 候选时占 CPU 路径一半以上，但整条 CPU 评估仅 ~4µs（单次取数 I/O 的 0.4% 以下），故仅 CPU-bound 才值得。**FIRST_HIT 排序提前编译已被压测证伪（与 HIGHEST_PRIORITY 同量级），不做** |
| B27 | **eclipse-collections 热点替换**（窄场景） | 同 B26 的 CPU-bound 场景，且微秒级也要抠 | 本会话 JMH 压测（`rule-benchmark`, 2026-06-07） | **仅**替换两处装箱热点：match 去重 `HashSet<Long>`→`LongHashSet`、版本合并 `LinkedHashMap<String,Integer>`→`ObjectIntHashMap`。JMH 测得 1.5–3x，但绝对值 sub-µs，被 I/O 完全掩盖——**不全局替换**（加依赖 + 原始集合 API 心智负担，P99 看不见收益）。`rule-benchmark` 已留 A/B 基准可复测 |
| B28 | **Modulith 显式 `allowedDependencies` 边界加固** | 模块边界被无意打穿（如 eval 越过 config.api.event 伸进 internal）/ 模块数继续增长 | 本会话架构审查（2026-06-07） | 给各模块加 `@ApplicationModule(allowedDependencies=...)`，把"eval 仅依赖 config 的 `api.event`/`api.spi`"等现有边界**声明式锁死**，`ModulithStructureTest` 升级为依赖白名单校验。属"加固已有优点"（防腐），非重构；现边界已干净，故触发式 |
| B30 | **what-if 批量回放 / 新规则陪跑** | 上线前需评估新规则在历史真实流量上的命中率 / 拦截率变化 | 08-evo §2.25 | 复用 D70 忠实重放：`evaluateReplay`「锁当时版本」抽出「取指定 / 当前版本」形参 → 历史输入 × 新规则得反事实结果；按 sceneCode+时间窗+采样率批量回放 + 新旧决策 diff 聚合报告；`POST /admin/v1/replay-batches`（异步任务 + 进度/报告查询）。**强依赖 D70**（无捕获的存量 session 不可回放）；离线只读零副作用。第三代「高可靠」，风控规则迭代的安全网 |

> **已落地（从触发式移除）**：
> - B10 外部系统集成契约标准化 → connector 标准化落地（2026-06-15，`connector_definition` V1_34 + `ConnectorWriteService` + `DeclarativeHttpConnectorHandler` + `AuthScheme`；声明式 EXTERNAL_HTTP 协议，多团队零代码接入，08-evo §2.11）。
> - B24 Scene 级默认时区激活 → 已生效（`OccurredAtEvaluator` 等传 `ctx.sceneDefaultParams().get(TIMEZONE)`，Scene 默认参数经 `SceneRuleIndex` 注入 EvalContext，解析序 字面量>params>Scene>UTC 全通）。

---

## 四、v3 远期（不急）

| # | 功能 | 来源 | 备注 |
|---|------|------|------|
| B15 | **规则模板市场** | 08-evo §2.10 | 依赖 B11（跨 Scene 复用）+ B7（导出导入）；v3 范畴 |
| B17 | **payloadSchema 字段引用校验**（AST ConditionNode 引用 payload 字段） | 08-evo §2.12 | 需约定 ConditionNode.params 字段引用编码规范；留 v3 |
