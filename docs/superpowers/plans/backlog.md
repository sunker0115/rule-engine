# 待实现功能 Backlog

整理自文档中所有"未实装 / 留到 v1.5 / 留到 v2 / 演进方向"标注。
按**执行性质**分组：主动推进序列 → 大件能力 → 触发式 → v3 远期；组内按建议执行顺序排列。

> 主动推进序列默认**正确性/运维兜底优先**。B6（Metric 版本化）、B7（规则导出 / 导入）已落地，从本序列移除。

---

## 一、主动推进序列（按此顺序直接做）

| 序 | # | 功能 | 来源 | 预计改动范围 | 备注 |
|---|---|------|------|------------|------|
| 1 | B1 | **EXPRESSION_SCRIPT evaluator**（CEL / Aviator 脚本沙箱） | D42 / 08-evo §2.1 / trae R2 | `rule-kernel`：新增 `ScriptExecutor`；commons-pool2 对象池管理 `ScriptEngine` 实例（非线程安全，每次 borrow/return）；沙箱安全边界；发布期 schema 校验 | 加表达力，SPI 已预留，不动现有执行路径；CEL 开源直接用；**对象池（trae R2）必须同步落地**，否则 ScriptEngine 初始化开销是秒级灾难 |
| 2 | B5 | **预编译执行器**（`CompiledExecutor`） | D20 §5 / 08-evo §2.13 / trae R5 | `rule-kernel`：`CompiledExecutor` + Janino/LambdaMetafactory；`ExecutorRegistry` 灰度切换；`rule_version.compiled_predicate_ref` 启用；可同期落地 alpha 节点共享（`ConditionEvaluationKey` 缓存去重，参考 trae R5） | TPS 可从 5–10 μs/规则降至 0.3–1 μs；已有 SPI + 字段预留；**B13 的前置** |

> **已落地（从主动推进序列移除）**：B7 规则导出 / 导入（2026-06-06，详见 08-evolution §2.9 已实装块 + 10-api-contract §4.8–4.9）；**解锁 B15**（规则模板市场依赖 B7 + B11）。

---

## 二、大件能力（单独立项，规模大，按业务需求拉入）

| # | 功能 | 来源 | 预计改动范围 | 备注 |
|---|------|------|------------|------|
| B8 | **CEP 复杂事件处理**（D5-C） | D5-C / 08-evo §2.1 | `rule-eval-svc` + Flink；频率/序列/聚合三模式；较大 | 计划文件已写（`d5c-cep.md`），还未执行；引 Flink 是新 infra/运维负担，单独排期不混入主线 |
| B11 | **跨 Scene 规则复用**（RuleTemplate / RuleFragment） | 08-evo §2.3 | 新表 `rule_template` / `rule_fragment`；发布期展开逻辑；dry-run 兼容；UI | B14 异步化、B15 模板市场的前置依赖 |
| B12 | **规则间依赖与编排**（Camunda / Flowable 或自研轻量 Flow） | D4 / 08-evo §2.4 / trae R4 | 引入工作流引擎；运维形态变化 | 引擎本身不变，编排在 Action 层之外；**决策点**：trae R4 约 2000 行自研 FlowEngine（7 种节点 + JSON 驱动）可作为"轻量替代 Camunda"的评估基线，规模小时成本更低；trae `flow/` + `context/` 目录有完整参考实现 |

---

## 三、触发式（条件命中再做，别预排期）

| # | 功能 | 触发条件 | 来源 | 预计改动范围 / 剩余项 |
|---|------|---------|------|--------------------|
| B9 | **节点级 trace 冷热分级** | trace 表膨胀影响查询性能 | 08-evo §2.5 | `node_trace` 热表 7 天 + 冷归档按月分区；可选 ClickHouse / ES；查询接口不变 |
| B10 | **外部系统集成契约标准化**（`MetricFetcher` 通用 SDK） | 多团队各自实现 EXTERNAL_HTTP 协议各异 | 08-evo §2.11 | 协议定义 + `MetricFetcher` SDK + 测试套件 |
| B13 | **嵌入式 SDK 生产硬化**（核心已实装 D33–D40） | P99 < 5ms 场景；依赖 B5 预编译先就位 | 08-evo §2.14 | 仅剩：推送式 Watcher（Mq/Nacos/Zk，秒级生效，替代现有轮询）+ Action 反向回写通道（SDK 评估、中心派发）+ 跨实例灰度桶审计；进程内评估 SDK（`rule-sdk` + starter + `RuleSource`/`RuleVersionExecutor` SPI + 注解模式）已 done |
| B14 | **`evaluation_session` 异步化路径** | profile 显示 session insert 进热路径 P99；依赖 B11 | 08-evo §2.15 | 幂等基础设施切换（持久化 KV）；对账数据源切换；父子表时序重设计 |
| B16 | **合规演进**（字段级加密 + 审计 hash chain + 数据右遗忘） | 高合规场景 | 08-evo §2.8 | 涉及所有持久化对象 |
| B18 | **Scene schema 自动放量 / 回退**（按 SLO 推进） | 自动化放量需求（v2 范畴） | 08-evo §2.7 | 灰度 v1 已完成；自动化放量是 v2 范畴 |
| B24 | **Scene 级默认时区激活**（B20 暂缓项） | 出现多时区 Scene 需求 | B20 时区解析序 | 解析序 字面量 offset > `params.timezone` > **Scene 默认** > UTC；`TimeZoneResolver.resolve(paramsTz, sceneTz)` 形参已留、现一律传 null。**阻塞/设计点**：Scene 配置如何到评估期——时区是 Scene 操作配置，不冻进规则快照（对齐 D45），需新建 Scene 索引/缓存（类 `SceneRuleIndex`）评估期查后注入 `EvalContext` |
| B25 | **取数层 v2 增强**（B21 留 v2 子项） | 各子项独立按需触发 | D45 / B21 | `STREAM` sourceType 实装（需流式状态 infra，随 B8 CEP）；HTTP OAuth2 自动刷 token（`HttpEndpointRegistry` 加 `TokenProvider` 抽象）；Scene 级数据源白名单（`MetricResourceCatalog`/发布校验加 Scene 维度）；Redis `MetricCache` 实现（SPI 已抽象，纯实现，触发=多实例共享缓存）；metric `required` 字段分级（`MetricDescriptor`+assembler 降级区分 required→ERROR / optional→继续）。均不动现有契约，SPI/字段扩展即可 |
| B26 | **装载期编译取数计划**（KieBase 式编译期-运行期分离） | profile 显示评估 **CPU-bound**：近无取数 I/O（全 providedMetrics）+ 大候选集 + 高 TPS | 本会话 JMH 压测（`rule-benchmark`, 2026-06-07） | 把 `collectChosenVersions`（候选并集取 max version）从每次评估提到 `SceneRuleIndex` 装载期（`update()` 是快照入索引唯一入口），运行期只查编译好的取数计划。JMH：版本合并在 50 候选时占 CPU 路径一半以上，但整条 CPU 评估仅 ~4µs（单次取数 I/O 的 0.4% 以下），故仅 CPU-bound 才值得。**FIRST_HIT 排序提前编译已被压测证伪（与 HIGHEST_PRIORITY 同量级），不做** |
| B27 | **eclipse-collections 热点替换**（窄场景） | 同 B26 的 CPU-bound 场景，且微秒级也要抠 | 本会话 JMH 压测（`rule-benchmark`, 2026-06-07） | **仅**替换两处装箱热点：match 去重 `HashSet<Long>`→`LongHashSet`、版本合并 `LinkedHashMap<String,Integer>`→`ObjectIntHashMap`。JMH 测得 1.5–3x，但绝对值 sub-µs，被 I/O 完全掩盖——**不全局替换**（加依赖 + 原始集合 API 心智负担，P99 看不见收益）。`rule-benchmark` 已留 A/B 基准可复测 |
| B28 | **Modulith 显式 `allowedDependencies` 边界加固** | 模块边界被无意打穿（如 eval 越过 config.api.event 伸进 internal）/ 模块数继续增长 | 本会话架构审查（2026-06-07） | 给各模块加 `@ApplicationModule(allowedDependencies=...)`，把"eval 仅依赖 config 的 `api.event`/`api.spi`"等现有边界**声明式锁死**，`ModulithStructureTest` 升级为依赖白名单校验。属"加固已有优点"（防腐），非重构；现边界已干净，故触发式 |

---

## 四、v3 远期（不急）

| # | 功能 | 来源 | 备注 |
|---|------|------|------|
| B15 | **规则模板市场** | 08-evo §2.10 | 依赖 B11（跨 Scene 复用）+ B7（导出导入）；v3 范畴 |
| B17 | **payloadSchema 字段引用校验**（AST ConditionNode 引用 payload 字段） | 08-evo §2.12 | 需约定 ConditionNode.params 字段引用编码规范；留 v3 |
