# 待实现功能 Backlog

整理自文档中所有"未实装 / 留到 v1.5 / 留到 v2 / 演进方向"标注。
按**执行性质**分组：主动推进序列 → 大件能力 → 触发式 → v3 远期；组内按建议执行顺序排列。

> 主动推进序列默认**能力/性能优先**（B1/B5 靠前）。若改为"正确性/运维兜底优先"，把 B6、B7 提到 B1 之前即可。

---

## 一、主动推进序列（按此顺序直接做）

| 序 | # | 功能 | 来源 | 预计改动范围 | 备注 |
|---|---|------|------|------------|------|
| 1 | B1 | **EXPRESSION_SCRIPT evaluator**（CEL / Aviator 脚本沙箱） | D42 / 08-evo §2.1 / trae R2 | `rule-kernel`：新增 `ScriptExecutor`；commons-pool2 对象池管理 `ScriptEngine` 实例（非线程安全，每次 borrow/return）；沙箱安全边界；发布期 schema 校验 | 加表达力，SPI 已预留，不动现有执行路径；CEL 开源直接用；**对象池（trae R2）必须同步落地**，否则 ScriptEngine 初始化开销是秒级灾难 |
| 2 | B5 | **预编译执行器**（`CompiledExecutor`） | D20 §5 / 08-evo §2.13 / trae R5 | `rule-kernel`：`CompiledExecutor` + Janino/LambdaMetafactory；`ExecutorRegistry` 灰度切换；`rule_version.compiled_predicate_ref` 启用；可同期落地 alpha 节点共享（`ConditionEvaluationKey` 缓存去重，参考 trae R5） | TPS 可从 5–10 μs/规则降至 0.3–1 μs；已有 SPI + 字段预留；**B13 的前置** |
| 3 | B6 | **Metric 版本化** | 08-evo §2.2 | `metric_definition` 加 `version` 列；`rule_version.metric_dependencies` JSON 升级；发布期 + 评估期解析逻辑；运营 UI 影响面展示 | 正确性兜底：Metric 语义变更时防止存量规则静默错误的根本解法；改动面横跨发布期/评估期两端 |
| 4 | B7 | **规则导出 / 导入** | 08-evo §2.9 | 独立工具链；导出格式 JSON Bundle；导入幂等写入 + 权限校验；无核心引擎变动 | 风险最低（不动引擎），可穿插做；跨环境迁移、Incident 复现的基础工具；**解锁 B15** |

> **B19 类型化比较策略工厂 / B20 时间框架 / B21 FETCHED 取数层 / B23 嵌入式 SDK 取数 已落地，2026-06-06 移除。** 遗留小项（未完成 / 归后续，记录在此）：
> - **B20 时区解析序的 Scene 级默认时区（优先级3）暂缓**：解析序为 字面量 offset > 条件 `params.timezone` > **Scene 默认（暂缓）** > UTC。运行时 `EvalContext`/`RuleVersionSnapshot` 不携带 `Scene.defaultParams.timezone`，需 config→snapshot→`EvalContext` 管线打通后激活；当前 `TimeZoneResolver.resolve(paramsTz, sceneTz)` 形参已预留，调用方一律传 `sceneTz=null`。
> - **B19 `ComparisonStrategyFactory` 的 LIST 走 `DefaultComparisonStrategy`**（无独立 `ListComparisonStrategy`；数值仍走 BigDecimal 不丢精度）；决策表列级 dataType 冻结 + 发布校验 → 归 **B22**。
> - **B21 v1 不做（留 v2）**：`STREAM` sourceType 实装（当前无 handler → 自动降级 `METRIC_FETCH_FAIL`）；HTTP OAuth2 自动刷 token；Scene 级数据源白名单；Redis 缓存（v1 进程内 Caffeine）；metric `required` 字段分级。全局取数超时阈值待 `07-operability` 统一管理。
> - **B23 嵌入式 SDK 取数已落地**（D46，实现见 `plans/2026-06-06-b23-sdk-fetch.md`）：定义独立下发（对称 `RuleSource` 的 DSL/File/Polling 三来源）+ 宿主注入 handler + 注入 handler 才启用 fetch（默认 providedMetrics-only 不变）。服务端 `listMetricDefinitions` 已按 scenes 收紧（`DECLARED` 按「scenes 下 ACTIVE rule_version 的 `metricDependencies` 并集」过滤，`ALL` 返回全部；不需 `scene_metric_binding` 表，详见 D46 §6）。EXTERNAL_HTTP 命名端点范式即 **B10**（`MetricFetcher` SDK）协议基础。

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
| B22 | **决策表列 dataType 冻结 + 发布校验**（B19 已知边界延续） | 决策表重度使用 / 出现声明≠运行时类型问题 | B19 设计 §2 边界 | 现状：`DecisionTableNode.Column` 不冻结 dataType，求值期合成节点走 Default 策略（数值仍走 BigDecimal，不丢精度）；缺的是列级发布期算子×dataType 校验 + 声明类型驱动路由。改动：Column 加 dataType（发布期从 metricCode 冻结）+ AstDataTypeResolver 处理 Column + DecisionTableExecutor 传该 dataType 进合成 ConditionNode。不紧急 |

---

## 四、v3 远期（不急）

| # | 功能 | 来源 | 备注 |
|---|------|------|------|
| B15 | **规则模板市场** | 08-evo §2.10 | 依赖 B11（跨 Scene 复用）+ B7（导出导入）；v3 范畴 |
| B17 | **payloadSchema 字段引用校验**（AST ConditionNode 引用 payload 字段） | 08-evo §2.12 | 需约定 ConditionNode.params 字段引用编码规范；留 v3 |
