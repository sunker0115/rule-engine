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
| dry-run `/dry-run?ruleVersionId=`(精确版本) | `EvalController.dryRun` | ✅ | 2026-06-10 | `dry_run_session` + `dry_run_node_trace` 落库;**本轮逮并修了 actual_value JSON bug** |
| dry-run `/dry-run?ruleId=`(取最新版本含 DRAFT,D56) | `EvalController.dryRun` | ✅ | 2026-06-11 | 取到 v2 DRAFT(ruleVersionId=1756),trace 对;`evaluation_session`=0、`action_execution`=0 —— 无副作用 ✅ |
| dry-run 无 target → 400 `MISSING_DRYRUN_TARGET`(D56) | `EvalController.dryRun` | ✅ | 2026-06-11 | 不传 ruleId/ruleVersionId → 400 + "MISSING_DRYRUN_TARGET: 必须指定 ruleId 或 ruleVersionId" |
| ~~dry-run 场景级副作用 BUG~~(D56 已根除) | `EvalServiceImpl.dryRun` | ✅ | 2026-06-11 | D56 **结构根除**已真服务验证:dry-run 恒走带版本单快照分支,不落 `evaluation_session`、不派发 action;不再靠 `isDryRun` 逐处门控 |
| 输入清单发现 `/scenes/{code}/input-manifest` | `SceneManifestController` | ✅ | 2026-06-10 | eventType 收窄正确 |
| **PUSH 事件 `/event` → 异步 action 派发 → `action_execution` 落库** | `EvalController.pushEvent` → `ActionDispatchService` → `ActionExecutionPersister` | ✅ | 2026-06-10 | PUSH 场景 + decision 带 SEND_ALERT action;202 受理 → `evaluation_session`(HIT/PUSH_REJECT)+ `node_trace` + `action_execution`(SEND_ALERT/SKIPPED/NO_WEBHOOK_URL,空 url 走跳过态)全落库;persister 无吞错(不像 trace writer 有 JSON 列雷,action_execution 全 varchar) |
| HYBRID 评估 | 与 PUSH 共享派发路径 | ✅ | 2026-06-11 | evaluate sync HIT/MISS + event async HIT/MISS 双路径全验;sync path 同样派发 action(空 webhook→SKIPPED),`evaluation_session`+`node_trace`+`action_execution` 全落库 |
| 整次输入快照 `evaluation_session.context_snapshot` | `AuditPersister`(开关 `engine.rule.audit.context-snapshot.enabled`,默认关) | ✅ | 2026-06-10 | 开关开后验证合并 map 落库 |

## 二、配置写入(admin CRUD)

| 流程 | 入口 | 状态 | 验证 | 备注 |
|---|---|---|---|---|
| 建场景 `POST /scenes` | `SceneController.create` | ✅ | 示例 / 2026-06-10 | |
| 改场景 `PATCH /scenes/{code}` | `SceneController.updateScene` | ✅ | 2026-06-10 | name/eventTypes/payloadSchema/defaultParams 可 patch(tenantId 在 body);**description 不在 `UpdateSceneRequest`、建后不可改**(小产品缺口,非 bug) |
| 建规则草稿 `POST /rules`(premise A 冻结快照,D56) | `RuleController.createDraft` | ✅ | 示例 / 2026-06-11 | premise A 已真服务验证:落库 DRAFT 行 `metric_dependencies` 冻版本号、`condition_ast` 含 `dataType`(LONG)、`decision_bindings` 冻 name/actions/priority |
| 编辑草稿 `PUT /rules/{id}/draft`(不增版本,D56) | `RuleController.editDraft` | ✅ | 2026-06-11 | 原地更新 DRAFT 行 version=1 不变、内容(threshold 100→200,GT→GTE)已改、落库生效 |
| 出新版本 / 回退 `POST /rules/{id}/versions`(D56) | `RuleController.newVersion` | ✅ | 2026-06-11 | 已发布规则出 v2 DRAFT;`fromVersionId=1755`(v1) 回退 → 克隆 v1 内容 + 按当前世界重解析产出 v2 DRAFT;在途 DRAFT 时拒 |
| 发布规则 `POST /rules/{id}/publish`(退化为激活,D56) | `RuleController.publish` | ✅ | 2026-06-11 | DRAFT 行 **原地翻 ACTIVE**(同 id=1755,同 version=1),旧 ACTIVE→SUPERSEDED;`triggerEventTypes` 落库为草稿声明值 `["login"]`;rule_definition PUBLISHED + currentVersion 指向激活行 |
| 停用规则 `POST /rules/{id}/disable` | `RuleController.disable` | ✅ | 示例 / 2026-06-11 | rule_definition.status → DISABLED;cleanup 阶段复用 |
| 删规则(从未发布)`DELETE /rules/{id}`(D56) | `RuleController.deleteRule` | ✅ | 2026-06-11 | 未发布草稿(rule 883 + rv 1758) 级联删净(`rd=0 rv=0`);已发布规则(882) 拒删("已发布过……请改用禁用") |
| 删草稿版本 `DELETE /rules/{id}/versions/{versionId}`(D56) | `RuleController.deleteDraftVersion` | ✅ | 2026-06-11 | 删 v2 DRAFT(1756)→成功;cleanup 前复用(清在途草稿为回退开路);碰 ACTIVE 拒 |
| 建/改/停 decision | `DecisionController` | ✅ | 示例 / 2026-06-10 | 建(示例)+ PUT(改 name/priority/description)+ disable(status→DISABLED)均真落库 |
| metric 注册 `POST /metrics` | `MetricController.create` | ✅ | 示例 | |
| metric 版本影响面查询 `/{code}/versions/{v}/impact` | `MetricController` | ✅ | 2026-06-10 | amount v1 → affectedRules 含 rule 871 |
| metric 改 `PUT /metrics/{code}` | `MetricController` | ✅ | 2026-06-11 | 原地改(breakingChange=false,name+cacheTtlSeconds 改,version 不变)+ 显式升版(breakingChange=true,version 递增,旧行→SUPERSEDED)+ 自动升版(sourceType 变更即使传 false 也走 breaking change)全验 |
| 场景元数据 `GET /scenes/{code}/metadata` | `MetadataController` | ✅ | 2026-06-10 | availableMetrics 返回 tenant 级 ACTIVE metric |
| 审计 / 会话查询 `GET /evaluation-sessions`·`/audit-logs`·`/trace` | `AuditController` | ✅ | 示例 | |

## 三、Job

| 流程 | 入口 | 状态 | 验证 | 备注 |
|---|---|---|---|---|
| Job 手动触发 → 合成 RuleEvent → 评估 | `JobController.trigger` → `@RuleJob` | ✅ | 2026-06-10 | 触发 `demo-daily`(租户1/fraud_check)→ SUCCESS/subjectCount=2/errorCount=0,`job_execution` 落库;**eval 为 miss 不落 session**(fraud_check 无规则→空候选短路,预期);job→event→eval 提交路径已验 |
| Job executions 查询 `GET /jobs/{id}/executions` | `JobController` | ✅ | 2026-06-10 | 返回执行记录 |
| Job enable / disable | `JobController` | ✅ | 2026-06-11 | enable/disable 全验(DISABLED→ACTIVE 往返,enable 校验 scene 存在);Job 定义由 @RuleJob 注解启动期自动落库,无 CRUD 接口 |
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
| 嵌入式 zero-network 评估 | `RuleEngineClient` | ✅ | rule-example / 2026-06-11 | `SdkTradingScenario`:SDK client(serverUrl=base host)轮询拉快照→本地评估交易(amount>5000 PAYLOAD 引用),命中/未命中双验;serverUrl 须传 base host(SnapshotPoller 自拼 `/sdk/v1/snapshots`) |
| HTTP 轮询拉快照刷新 | `PollingRuleSource` / `SnapshotPoller` | ✅ | rule-example / 2026-06-11 | 同 `SdkTradingScenario`:`SnapshotPoller` 解析 `ApiResponse.data`→刷新 `SceneRuleIndex`,2s 轮询间隔下 15s 内拉到已发布规则 |

## 六、取数 / 派发

| 流程 | 入口 | 状态 | 备注 |
|---|---|---|---|
| SQL_AGGREGATE 取数 | `SqlAggregateMetricSourceHandler` | ✅ | rule-example / 2026-06-11 `OrderFraudScenario`:Testcontainers MySQL `orders` 业务表 + 命名数据源 `engine.rule.fetch.datasources[0]`,`SELECT SUM(amount)...` 真取数;params 用 `datasource`(小写)/`sql`,命中/未命中双验 |
| EXTERNAL_HTTP 取数 | `ExternalHttpMetricSourceHandler` | ✅ | rule-example / 2026-06-11 `CreditEvaluationScenario`:WireMock 模拟评分接口 + 命名端点 `engine.rule.fetch.endpoints[0]`,params 用 `endpoint`/`path`/`jsonPath`,JSONPath 提取 + 高低分双验 |
| SEND_ALERT 派发 | `SendAlertHandler` | ✅ | rule-example / 2026-06-11 `HighRiskLoginScenario`:WireMock webhook,200→`action_execution`=SUCCESS、500→FAILED 均真投递验;全局 url 配 `engine.rule.action.send-alert.url`(空 url=SKIPPED 历史已验) |
| ATTRIBUTE / STREAM 取数 | — | ⚪ | 无 handler bean(未实现) |

## 七、运维 / 数据保留

| 流程 | 入口 | 状态 | 验证 | 备注 |
|---|---|---|---|---|
| 数据保留清理(5 表) | `TraceRetentionCleaner` / `SessionRetentionCleaner`(`@Scheduled`) | ✅ | 2026-06-10 | evaluation_session/node_trace/action_execution 直接验;dry_run 两表同款路径(action_execution 单独补验)|

---

## 自动化端到端验证(rule-example,2026-06-11)

§五 SDK 嵌入式 + §六 取数/派发 的 🟡 项已由 `rule-app/src/test` 下 4 个 `*Scenario`(Failsafe + `examples` profile + Testcontainers/WireMock)自动化跑通,不再需手工补跑:

- 运行:`mvn verify -pl rule-app -Pexamples`(需 Docker;日常 `mvn test` 不触发)
- 场景即业务故事:`HighRiskLoginScenario`(登录风控 PUSH/PULL + 告警)、`OrderFraudScenario`(订单 SQL 取数)、`CreditEvaluationScenario`(信用 HTTP 取数)、`SdkTradingScenario`(SDK 轮询 + 嵌入式评估)
- 设计 / 计划见 `docs/superpowers/specs|plans/2026-06-11-rule-example-module*`
