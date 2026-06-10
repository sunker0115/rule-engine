# 功能测试覆盖清单

> 记录各**端到端流程**是否经「真服务 + 真落库 / 真副作用」功能测试跑通(单测 / 集成测试 mock 不掉的部分)。
> **触及某流程或新增功能后,更新本表对应行**(状态 + 日期 + 备注)。
>
> **「跑通」判据**:起真实服务(打包产物)→ 走该流程的 HTTP/触发入口 → 查持久层确认应落的数据 / 副作用真发生(失败 / 跳过态也算正确落库)。
>
> **图例**:✅ 真服务跑通 · ⬜ 未跑(本地可跑,缺验证)· 🟡 需外部基建才能跑(真数据源 / webhook / xxl-admin 等)· ⚪ stub / 未实现(没法跑)
>
> **验证来源缩写**:`示例` = `docs/examples/risk-control/high-risk-login`(README 声明已 HTTP 端到端跑通)· 日期 = 该日功能测试会话。

---

## 一、评估流程(public eval)

| 流程 | 入口 | 状态 | 验证 | 备注 |
|---|---|---|---|---|
| PULL 评估 `/evaluate`(命中/未命中) | `EvalController.evaluate` | ✅ | 示例 / 2026-06-10 | 含 payload 注入、`evaluation_session` + `node_trace` 落库 |
| 评估期入参校验(缺必填/类型不符 → 400) | `EvalServiceImpl` + `PayloadInputValidator` | ✅ | 2026-06-10 | `MISSING_REQUIRED_INPUT` / `INPUT_TYPE_MISMATCH` |
| dry-run `/dry-run?ruleVersionId=`(带版本) | `EvalController.dryRun` | ✅ | 2026-06-10 | `dry_run_session` + `dry_run_node_trace` 落库;**本轮逮并修了 actual_value JSON bug** |
| dry-run 场景级(不带 ruleVersionId) | 同上 → 候选分支 | ⬜ | — | 落普通候选路径、不进 dry_run 表(设计如此),语义待确认 |
| 输入清单发现 `/scenes/{code}/input-manifest` | `SceneManifestController` | ✅ | 2026-06-10 | eventType 收窄正确 |
| **PUSH 事件 `/event` → 异步 action 派发 → `action_execution` 落库** | `EvalController.pushEvent` → `ActionDispatchService` → `ActionExecutionPersister` | ✅ | 2026-06-10 | PUSH 场景 + decision 带 SEND_ALERT action;202 受理 → `evaluation_session`(HIT/PUSH_REJECT)+ `node_trace` + `action_execution`(SEND_ALERT/SKIPPED/NO_WEBHOOK_URL,空 url 走跳过态)全落库;persister 无吞错(不像 trace writer 有 JSON 列雷,action_execution 全 varchar) |
| HYBRID 评估 | 与 PUSH 共享派发路径 | ⬜ | — | 派发路径已由 PUSH 验证;HYBRID 自身(同步返回 + 异步派发并存)未直接跑 |
| 整次输入快照 `evaluation_session.context_snapshot` | `AuditPersister`(开关 `engine.rule.audit.context-snapshot.enabled`,默认关) | ✅ | 2026-06-10 | 开关开后验证合并 map 落库 |

## 二、配置写入(admin CRUD)

| 流程 | 入口 | 状态 | 验证 | 备注 |
|---|---|---|---|---|
| 建场景 `POST /scenes` | `SceneController.create` | ✅ | 示例 / 2026-06-10 | |
| 改场景 `PATCH /scenes/{code}` | `SceneController.updateScene` | ✅ | 2026-06-10 | name/eventTypes/payloadSchema/defaultParams 可 patch(tenantId 在 body);**description 不在 `UpdateSceneRequest`、建后不可改**(小产品缺口,非 bug) |
| 建规则草稿 `POST /rules` | `RuleController.createDraft` | ✅ | 示例 / 2026-06-10 | |
| 发布规则 `POST /rules/{id}/publish` | `RuleController.publish` | ✅ | 示例 / 2026-06-10 | 含 payload/metric 依赖冻结、快照落库 |
| 停用规则 `POST /rules/{id}/disable` | `RuleController.disable` | ✅ | 2026-06-10 | rule_definition.status → DISABLED |
| 建/改/停 decision | `DecisionController` | ✅ | 示例 / 2026-06-10 | 建(示例)+ PUT(改 name/priority/description)+ disable(status→DISABLED)均真落库 |
| metric 注册 `POST /metrics` | `MetricController.create` | ✅ | 示例 | |
| metric 版本影响面查询 `/{code}/versions/{v}/impact` | `MetricController` | ✅ | 2026-06-10 | amount v1 → affectedRules 含 rule 871 |
| metric 改 `PUT /metrics/{code}` | `MetricController` | ⬜ | — | 未跑 |
| 场景元数据 `GET /scenes/{code}/metadata` | `MetadataController` | ✅ | 2026-06-10 | availableMetrics 返回 tenant 级 ACTIVE metric |
| 审计 / 会话查询 `GET /evaluation-sessions`·`/audit-logs`·`/trace` | `AuditController` | ✅ | 示例 | |

## 三、Job

| 流程 | 入口 | 状态 | 验证 | 备注 |
|---|---|---|---|---|
| Job 手动触发 → 合成 RuleEvent → 评估 | `JobController.trigger` → `@RuleJob` | ⬜ | — | 进程内可跑(`DemoFraudJob` local profile) |
| Job CRUD / enable / disable / executions 查询 | `JobController` | ⬜ | — | |
| xxl-job 定时调度 | `XxlJobSchedulerAdapter` | 🟡 | — | 需真 xxl-job-admin |

## 四、Bundle 导出 / 导入

| 流程 | 入口 | 状态 | 验证 | 备注 |
|---|---|---|---|---|
| 导出 `GET /rules/export` | `RuleBundleController.export` | ✅ | 2026-06-10 | 按 sceneId 导出 → bundle JSON 含 rules/scenes/decisions + **payloadDependencies**(B-T7b) |
| 导入 `POST /rules/import` | `RuleBundleController.import` | ✅ | 2026-06-10 | multipart 导入到**新租户 9002** → scene+decision 重建、rule 落 DRAFT,`rule_version.payload_dependencies` 完整保留;真跨租户往返无 bug |

## 五、SDK 嵌入式

| 流程 | 入口 | 状态 | 验证 | 备注 |
|---|---|---|---|---|
| 快照下发 `GET /sdk/v1/snapshots` | `SdkController` | ✅ | 示例 | |
| metric 定义下发 `GET /sdk/v1/metric-definitions` | `SdkController` | ✅ | 2026-06-10 | 返回 tenant 级 4 个 metric 定义 |
| 嵌入式 zero-network 评估 | `RuleEngineClient` | 🟡 | — | 需 SDK 测试宿主 |
| HTTP 轮询拉快照刷新 | `PollingRuleSource` / `PollingMetricDefinitionSource` | 🟡 | — | 需跑着的 rule-app 配合 |
| DB 轮询 watcher | `DbPollingRuleWatcher` / `DbPollingSceneWatcher` | ⚪ | — | 抛 UnsupportedOperationException(SDK v2) |

## 六、取数 / 派发(需外部依赖,🟡)

| 流程 | 入口 | 状态 | 备注 |
|---|---|---|---|
| SQL_AGGREGATE 取数 | `SqlAggregateMetricSourceHandler` | 🟡 | 需真命名 MySQL 数据源 |
| EXTERNAL_HTTP 取数 | `ExternalHttpMetricSourceHandler` | 🟡 | 需真 HTTP 端点 |
| SEND_ALERT 派发 | `SendAlertHandler` | 🟡 | 需真 webhook URL(空 url = SKIPPED,可验跳过态) |
| ATTRIBUTE / STREAM 取数 | — | ⚪ | 无 handler bean(未实现) |
| BLOCK_TRANSACTION 派发 | `BlockTransactionHandler` | ⚪ | v1 stub,直接返回成功 |

## 七、运维 / 数据保留

| 流程 | 入口 | 状态 | 验证 | 备注 |
|---|---|---|---|---|
| 数据保留清理(5 表) | `TraceRetentionCleaner` / `SessionRetentionCleaner`(`@Scheduled`) | ✅ | 2026-06-10 | evaluation_session/node_trace/action_execution 直接验;dry_run 两表同款路径(action_execution 单独补验)|

---

## 优先补跑建议(⬜ 中,可跑、价值高)

1. **PUSH `/event` → action_execution 落库**(§一)—— 核心链路 + best-effort persister 从没跑过,优先级最高。
2. **Job 手动触发 → 评估**(§三)
3. **Bundle 导出→导入 真 HTTP 往返**(§四)
4. admin CRUD 边角(disable / PATCH / metric impact / metadata)
