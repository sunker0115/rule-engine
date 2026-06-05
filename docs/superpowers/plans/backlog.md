# 待实现功能 Backlog

整理自文档中所有"未实装 / 留到 v1.5 / 留到 v2 / 演进方向"标注。
按**实现价值 × 复杂度**分层，优先级高低在同层内从上到下排列。

---

## 第一层：价值高，复杂度低（直接实现）

| # | 功能 | 来源 | 预计改动范围 | 备注 |
|---|------|------|------------|------|
| B1 | **EXPRESSION_SCRIPT evaluator**（CEL / Aviator 脚本沙箱） | D42 / 08-evo §2.1 / trae R2 | `rule-kernel`：新增 `ScriptExecutor`；commons-pool2 对象池管理 `ScriptEngine` 实例（非线程安全，每次 borrow/return）；沙箱安全边界；发布期 schema 校验 | 已有 SPI 预留，缺评估器 + 沙箱；CEL 开源直接用；**对象池（trae R2）必须同步落地**，否则 ScriptEngine 初始化开销是秒级灾难 |
| B3 | **OTLP 可观测性**（OpenTelemetry + Grafana LGTM） | 08-evo §2.22 | `rule-app/pom.xml` 加依赖；`application.yml` 2 行；`logback-spring.xml` appender；`docker-compose.yml` 1 service | 纯配置改动，零业务代码 |
| B4 | **XOR 逻辑节点**（已实装，仅前端 UI 未接） | 08-evo §2.21 | 后端已完成；前端编辑器新增 XOR 选项 | 后端零改动；仅前端一个 UI 变体 |
| B5 | **预编译执行器**（`CompiledExecutor`） | D20 §5 / 08-evo §2.13 / trae R5 | `rule-kernel`：`CompiledExecutor` + Janino/LambdaMetafactory；`ExecutorRegistry` 灰度切换；`rule_version.compiled_predicate_ref` 启用；可同期落地 alpha 节点共享（`ConditionEvaluationKey` 缓存去重，参考 trae R5） | TPS 可从 5–10 μs/规则降至 0.3–1 μs；已有 SPI + 字段预留 |

---

## 第二层：价值高，复杂度中（需计划）

| # | 功能 | 来源 | 预计改动范围 | 备注 |
|---|------|------|------------|------|
| B6 | **Metric 版本化** | 08-evo §2.2 | `metric_definition` 加 `version` 列；`rule_version.metric_dependencies` JSON 升级；发布期 + 评估期解析逻辑；运营 UI 影响面展示 | Metric 语义变更时防止存量规则静默错误的根本解法 |
| B7 | **规则导出 / 导入** | 08-evo §2.9 | 独立工具链；导出格式 JSON Bundle；导入幂等写入 + 权限校验；无核心引擎变动 | 跨环境迁移、Incident 复现的基础工具 |
| B8 | **CEP 复杂事件处理**（D5-C） | D5-C / 08-evo §2.1 | `rule-eval-svc` + Flink；频率/序列/聚合三模式；较大 | 计划文件已写（`d5c-cep.md`），还未执行 |
| B9 | **节点级 trace 冷热分级** | 08-evo §2.5 | `node_trace` 热表 7 天 + 冷归档按月分区；可选 ClickHouse / ES；查询接口不变 | 触发条件：trace 表膨胀影响查询性能 |
| B10 | **外部系统集成契约标准化**（`MetricFetcher` 通用 SDK） | 08-evo §2.11 | 协议定义 + `MetricFetcher` SDK + 测试套件 | 多团队各自实现 EXTERNAL_HTTP 协议各异时触发 |
| B19 | **类型化比较策略工厂**（`ComparisonStrategy` 按 dataType 路由） | trae R3 | `rule-kernel`：`ComparisonStrategyFactory.forType(dataType)` + 5 种策略类（Numeric/String/Boolean/List/Date）；替换 `ConditionEvaluator` 内部的 instanceof if-else | 随 `ConditionEvaluator` 多类型场景批次落地，不单独开 task；trae `rule/condition/strategy/` 有完整参考实现 |

---

## 第三层：价值中，复杂度高（视业务触发）

| # | 功能 | 来源 | 预计改动范围 | 备注 |
|---|------|------|------------|------|
| B11 | **跨 Scene 规则复用**（RuleTemplate / RuleFragment） | 08-evo §2.3 | 新表 `rule_template` / `rule_fragment`；发布期展开逻辑；dry-run 兼容；UI | `evaluation_session` 异步化（B14）、规则模板市场（B15）的前置依赖 |
| B12 | **规则间依赖与编排**（Camunda / Flowable 或自研轻量 Flow） | D4 / 08-evo §2.4 / trae R4 | 引入工作流引擎；运维形态变化 | 引擎本身不变，编排在 Action 层之外；**决策点**：trae R4 约 2000 行自研 FlowEngine（7 种节点 + JSON 驱动）可作为"轻量替代 Camunda"的评估基线，规模小时成本更低；trae `flow/` + `context/` 目录有完整参考实现 |
| B13 | **嵌入式 SDK 模式**（评估执行下沉，进程内） | 08-evo §2.14 | SDK jar；MqRuleWatcher 等多 backend；Action 反向回写通道；跨实例灰度桶审计 | 依赖 B5（预编译）先就位；P99 < 5ms 场景触发 |
| B14 | **`evaluation_session` 异步化路径** | 08-evo §2.15 | 幂等基础设施切换（持久化 KV）；对账数据源切换；父子表时序重设计 | 触发条件：profile 显示 session insert 进热路径 P99；现阶段不做 |

---

## 第四层：优先级低 / v3 范畴（不急）

| # | 功能 | 来源 | 备注 |
|---|------|------|------|
| B15 | **规则模板市场** | 08-evo §2.10 | 依赖 B11（跨 Scene 复用）+ B7（导出导入）；v3 范畴 |
| B16 | **合规演进**（字段级加密 + 审计 hash chain + 数据右遗忘） | 08-evo §2.8 | 高合规场景触发；涉及所有持久化对象 |
| B17 | **payloadSchema 字段引用校验**（AST ConditionNode 引用 payload 字段） | 08-evo §2.12 | 留 v3；需约定 ConditionNode.params 字段引用编码规范 |
| B18 | **Scene schema 自动放量 / 回退**（按 SLO 推进） | 08-evo §2.7 | 灰度 v1 已完成；自动化放量是 v2 范畴 |

