# 待实现功能 Backlog

整理自文档中所有"未实装 / 留到 v1.5 / 留到 v2 / 演进方向"标注。
按**执行性质**分组：主动推进序列 → 大件能力 → 触发式 → v3 远期；组内按建议执行顺序排列。

> 主动推进序列**全部落地**：B6/B7/B1/B5/B31/B33/B32 均已实装，序列清空。

---

## 一、主动推进序列（已全部落地，序列清空）

> **已落地（从主动推进序列移除）**：
> - **B32 决策效果闭环 / 规则有效性度量（2026-06-19）**：`decision_outcome` 表(V1_36) + 同步回灌 API(`POST /admin/v1/decision-outcomes`) + `OUTCOME_INGESTION` executor(定时从 SQL 源增量拉标签) + JSON_TABLE 按需聚合(TP/FP/FN/precision/recall/fireRate/漂移) + 前端报表页(过滤/混淆矩阵/@ant-design/plots 漂移折线/回灌 Modal)。e2e 验证全通，价值门控已注明：聚合骨架就绪，等业务侧真实标签回灌才能看到真实 precision/recall。
> - **B33 规则↔指标血缘与变更影响分析（2026-06-19，08-evo §2.28 已实装块）**：反向 **Decision→规则**（`DecisionService.findRulesProducingDecision` 按需扫 ACTIVE rule_version 的 `decision_bindings`，专用投影 `findActiveWithDecisionByRuleDefIds`）+ metric 版本无关 `GET /metrics/{code}/sources`（对称 decision）+ 批量计数 `/decisions·metrics/usage-counts` + `GET /decisions/{code}` 详情。前端复用 §2.26 B31 治理范式统一两侧呈现：列表「被引用」徽标 + 血缘抽屉（按场景分组、可点定位下钻编辑器）+ **Decision 详情页**（消除与 Metric 不对称）+「被引用规则」Tab + 编辑器 metric/decision 反向徽标 + **停用 Decision 前血缘拦截**。**与原计划偏差（重要）**：metric→规则按需扫（`findReferencingRules`）早随 B6 已存在，常驻 `LineageIndex` 对它属 over-engineering 且双轨重复——**放弃 LineageIndex，沿用按需扫房规、零索引、零 DDL、不碰评估热路径**（血缘是冷的治理查询，读已提交 DB 强一致优先）。口径：仅认发布期冻结的 `decision_bindings` / `metric_dependencies`（EXPRESSION_SCRIPT 脚本体内 metric 引用漏报，与脚本不透明一致）。**顺带修的编辑器问题**：① 新版本 `tenantId` 错发 query param + 改克隆当前版本（原会报"tenantId 不能为 null"/ 建空白草稿）② 接入「丢弃草稿 / 删除规则」闭合版本生命周期（草稿三出口：发布/丢弃/继续编辑）③ 保存草稿后规则集分析自动重算（轻量 reanalyze，无需刷新）。
> - B31 规则集静态分析 / 冲突检测（2026-06-18，08-evo §2.26 已实装块）：7 类检查（不一致/死规则/冲突/重叠/覆盖缺口/**冗余**/未分析），`ConditionSpace` 区间推理 + 6 detector + `RuleSetAnalyzer`，hit-policy-aware、零误报；`RuleAnalysisService`（**草稿优先**）+ `GET /admin/v1/scenes/{code}/analysis` + 前端左栏摘要条入口。覆盖 AST_BOOLEAN/决策树/决策表（合取语义），**故意不做**评分卡/脚本/决策树跨树/DMN 完备性。**与原计划偏差**：前端落在左栏摘要条+抽屉（非右栏）；新增规则内冗余检测；FIRST_HIT 等优先级保守降级。
> - B1 EXPRESSION_SCRIPT evaluator（`ScriptExecutor` + `ExpressionEngine` SPI；引擎扩到 6 个 cel/aviator/qlexpress/jsonlogic/jexl/groovy。**对象池前提被取代**——SPI 走线程安全单例 + 按源码哈希缓存编译产物，无需 commons-pool2 池化非线程安全 ScriptEngine）。
> - B5 预编译执行器 `CompiledExecutor`（08-evo §2.13 / D67，2026-06-13；纯编译版 + `PrecompileMode`/`CompiledPredicateEvictor`；alpha 节点共享为可选 add-on）。
> - B7 规则导出 / 导入（2026-06-06，08-evo §2.9 + 10-api-contract §4.8–4.9）；**解锁 B15**（模板市场依赖 B7 + B11）。
> - B6 Metric 版本化（档1，2026-06-06，08-evo §2.2）。
> - B17 payloadSchema 字段引用严格校验（核心早随 D13 实装——`PublishService.freezePayloadDeps` 发布期拒绝引用未声明 payload 字段，含 ConditionNode + 决策表 PAYLOAD 列；2026-06-18 补 `UNRESOLVED_VARIABLE:` 语义前缀对齐错误码契约）。**严格契约**：无 payloadSchema 也拒 payload 引用（强制声明）。原"留 v3"判断有误，实为已实装。

---

## 二、大件能力（单独立项，规模大，按业务需求拉入）

| # | 功能 | 来源 | 预计改动范围 | 备注 |
|---|------|------|------------|------|
| B29 | **特征预计算 / 物化特征层（含 CEP）** | 08-evo §2.24 / §2.1 / D5-C | `metric_definition` 加物化档 `sourceType` + 刷新策略列；`MetricSourceHandler` 增物化实现（读 KV）+ CDC 预计算 writer（独立于热路径）；分层取数；降级语义归 D15 | **第三代「高性能」：营销千级→风控万级 QPS 路上第一道性能墙**。物化 + 服务 + **CEP（频率/序列/聚合，原 B8）** 统一由[《实时流式风控设计》](../specs/2026-06-17-realtime-streaming-risk-control-design.md)承载（Flink→Redis 特征库→`STREAM` handler，引擎零改动按 sourceType 路由；与 B25 Redis `MetricCache` 同一套 Flink+Kafka+Redis infra）。引新 KV/列存需与 D9「全 MySQL」专门决策；CEP 旧 `d5c-cep.md`/`-design.md` 届时归档 |
| B11 | **跨 Scene 规则复用**（RuleTemplate / RuleFragment） | 08-evo §2.3 | 新表 `rule_template` / `rule_fragment`；发布期展开逻辑；dry-run 兼容；UI | B14 异步化、B15 模板市场的前置依赖。**注：与 D75 DECISION_FLOW 不同层——B11 是 authoring 复用（一条逻辑骨架实例化成多条独立规则），DECISION_FLOW 是 runtime 编排（一次评估内串联多条已有规则），各不代替** |

---

## 三、触发式（条件命中再做，别预排期）

| # | 功能 | 触发条件 | 来源 | 预计改动范围 / 剩余项 |
|---|------|---------|------|--------------------|
| B9 | **节点级 trace 冷热分级** | trace 表膨胀影响查询性能 | 08-evo §2.5 | `node_trace` 热表 7 天 + 冷归档按月分区；可选 ClickHouse / ES；查询接口不变 |
| B13 | **嵌入式 SDK 生产硬化**（核心已实装 D33–D40） | P99 < 5ms 场景 | 08-evo §2.14 | 仅剩：推送式 Watcher（Mq/Nacos/Zk，秒级生效，替代现有轮询）+ 跨实例灰度桶审计；进程内评估 SDK（`rule-sdk` + starter + `RuleSource`/`RuleVersionExecutor` SPI + 注解模式）已 done。原"Action 反向回写通道"随 D60 移除动作子系统已作废 |
| B14 | **`evaluation_session` 异步化路径** | profile 显示 session insert 进热路径 P99；依赖 B11 | 08-evo §2.15 | 幂等基础设施切换（持久化 KV）；对账数据源切换；父子表时序重设计 |
| B16 | **合规演进**（字段级加密 + 审计 hash chain + 数据右遗忘） | 高合规场景 | 08-evo §2.8 | 三项均未做：落库前字段级加密 / `audit_log` hash chain 防篡改 / 按 subject 物理右遗忘。**地基**：D71 敏感字段标记（`sensitive` + `SensitiveRefsResolver`，现仅 trace 展示脱敏，非落库加密）可复用为"加密哪些字段"的依据 |
| B18 | **Scene schema 自动放量 / 回退**（按 SLO 推进） | 自动化放量需求（v2 范畴） | 08-evo §2.7 | 灰度 v1 已完成；自动化放量是 v2 范畴 |
| B25 | **取数层 v2 增强**（剩余两项） | 各子项独立按需触发 | D45 / B21 | ① Redis `MetricCache` 实现（SPI 已抽象，纯实现，触发=多实例共享缓存，与 B29 物化特征库是不同用途）；② metric `required` 字段分级（`MetricDescriptor`+assembler 降级区分 required→ERROR / optional→继续）。`STREAM` sourceType 实装已并入 B29/实时流式风控设计 |
| B28 | **Modulith 显式 `allowedDependencies` 边界加固** | 模块边界被无意打穿（如 eval 越过 config.api.event 伸进 internal）/ 模块数继续增长 | 本会话架构审查（2026-06-07） | 给各模块加 `@ApplicationModule(allowedDependencies=...)`，把"eval 仅依赖 config 的 `api.event`/`api.spi`"等现有边界**声明式锁死**，`ModulithStructureTest` 升级为依赖白名单校验。属"加固已有优点"（防腐），非重构；现边界已干净，故触发式 |
| B30 | **what-if 批量回放 / 新规则陪跑** | 上线前需评估新规则在历史真实流量上的命中率 / 拦截率变化 | 08-evo §2.25 | 复用 D70 忠实重放：`evaluateReplay`「锁当时版本」抽出「取指定 / 当前版本」形参 → 历史输入 × 新规则得反事实结果；按 sceneCode+时间窗+采样率批量回放 + 新旧决策 diff 聚合报告；`POST /admin/v1/replay-batches`（异步任务 + 进度/报告查询）。**强依赖 D70**（无捕获的存量 session 不可回放）；离线只读零副作用。第三代「高可靠」，风控规则迭代的安全网 |

> **已落地（从触发式移除）**：
> - B10 外部系统集成契约标准化 → connector 标准化落地（2026-06-15，`connector_definition` V1_34 + `ConnectorWriteService` + `DeclarativeHttpConnectorHandler` + `AuthScheme`；声明式 EXTERNAL_HTTP 协议，多团队零代码接入，08-evo §2.11）。
> - B24 Scene 级默认时区激活 → 已生效（`OccurredAtEvaluator` 等传 `ctx.sceneDefaultParams().get(TIMEZONE)`，Scene 默认参数经 `SceneRuleIndex` 注入 EvalContext，解析序 字面量>params>Scene>UTC 全通）。

---

## 四、v3 远期（不急）

| # | 功能 | 来源 | 备注 |
|---|------|------|------|
| B15 | **规则模板市场** | 08-evo §2.10 | **参数化模板目录 + 租户按参实例化**，≠ B7 导出/导入（B7 搬"具体规则"原样复制，仅作 B15 的搬运层）。真正前置是 **B11 RuleTemplate/RuleFragment（带占位符的规则骨架，未做）**——B11 落地前做不了。多租户复用场景才拉入；v3 范畴 |
