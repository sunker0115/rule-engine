# 规则引擎纯决策化 — 移除动作子系统(D60)

> 状态:设计待评审 · 日期 2026-06-11

## 一、背景与决策

规则引擎定位收敛为**纯决策引擎(decision service)**:引擎只产出决策(`Decision`),"命中后做什么"(副作用、调用下游、流程编排)归**消费方 / 流程引擎**。对标 OPA(策略返回决策,PEP 执行)、Camunda **DMN(决策)/ BPMN(编排)** 的分工——决策与编排分属两层。

后续编排接**开源流程引擎(首选 Flowable,BPMN 系)**:Flowable 用 Service Task / HTTP Task 把本引擎当一个"决策节点"调(`/api/v1/rule/evaluate` 或嵌入式 `RuleEngineClient.evaluate`),拿 `Decision` 再编排;本引擎对流程引擎一无所知(单向)。流程引擎集成是**独立项目,不在本 spec**。

本 spec = **移除整个动作子系统**。新决策 **D60 取代/作废** D16(链式触发与事件环)、D18(Action 失败补偿)、D27(Action 归 Decision)、D28(Decision.actions 生效时机)、D57(无通用阻断动作)。greenfield 无兼容包袱;`scene_action_binding` / retry / compensate / rate_limit 已于 V1_14 / V1_21 / V1_23 移除,本次完成剩余清除。

## 二、保留(明确不动)

- **决策输出口**:`RuleEngineClient.evaluate()` 返回 `EvalResult`;SDK `EvalResultListener` / `EvalSessionListener` 回调。这些是结果出口、不属动作子系统。
- **PULL / PUSH 双模(D14)**:PULL 同步返回决策;PUSH 仍接收→评估→落库 `evaluation_session` / `node_trace`,但**不再派发动作**——消费方拿决策或查 session 自行编排。
- Scene / Rule / Decision / Metric 配置与治理、发布生命周期、不可变快照(D6)、节点 trace、`(code,version)` 身份、取数管线(metric / sourceType / MetricSourceHandler)——全部不动。

## 三、移除范围(分层)

**kernel(`rule-kernel`)**
- 删:`api/spi/action/ActionHandler`、`api/annotation/ActionType`、`api/model/ActionContext`、`api/model/ActionResult`。
- 改:`Decision` 去 `actions` 字段;`EvalResult` 去 `actionResults`;`RuleVersionSnapshot.DecisionBinding` 去 `actions`,删嵌套 `DecisionAction` record;`EvalEngine` / `DecisionTreeExecutor` / `DecisionTableExecutor` 去掉构建/回填 actions 的逻辑。

**eval-svc(`rule-eval-svc`)**
- 删整包 `internal/action/`(`ActionDispatchService` / `SendAlertHandler` / `SendAlertProperties` / `ActionExecutionPersister`)、`internal/dispatch/EvalActionDispatcher`、`internal/async/` 中动作相关(`ActionCommandChannel` / `ActionExecutedEvent` / `DispatchActionsCommand` / `InProcessAsyncCommandChannel`——确认 InProcessAsyncCommandChannel 仅服务 action,若兼做他用则只摘 action 分支)、`internal/domain/ActionExecutionEntity` + `repository/ActionExecutionMapper`。
- 改:`EvalServiceImpl` 去派发调用;`EvalAutoConfiguration` 去相关 Bean 装配;`SessionRetentionCleaner` / `RetentionProperties` 去 `action_execution` 清理。

**config-svc / api(`rule-config-svc` / `rule-api`)**
- 改:`DecisionDefinition` 去 `actions`;`DecisionService` / `DecisionServiceImpl` / `DecisionController` 去 actions 入参/出参;`MetadataService.MetadataResponse` 去 `actionTypes` + `ActionTypeMeta`,`MetadataServiceImpl` / `MetadataController` 同步;`RuleBundle` / `RuleImportResult` / `RuleExportService` / `RuleImportService` 去 actions 字段;`PublishService` 去发布期冻结 actions 进快照。

**DB(新迁移 `V1_27`)**
- `DROP TABLE action_execution;`
- `ALTER TABLE decision_definition DROP COLUMN actions;`
- `rule_version.decision_bindings` JSON 内残留的 `actions` 字段:见 §五风险(靠 codec 未知字段宽容忽略,或清库重建)。

**文档**
- 删 `04-extension.md §三(加 ActionType)`;`00-decisions.md` 追加 **D60**(取代 D16/D18/D27/D28/D57,并在汇总表标注);`01-concepts.md` / `02-runtime.md` / `05-storage.md` / `10-api-contract.md` 清除 action 段落与字段(评估响应、decision 配置、metadata 契约)。

**测试 / 样例**
- 删 eval / config / kernel / api 中动作相关测试;`rule-samples` 确认不含 `@ActionType`(原计划的 @ActionType 样例不再做)。

## 四、删后模型

- `Decision` = 纯结果:`code` / `name` / `priority` / `category` / `fromRuleVersionId` / `fromRuleCode` / `fromRuleVersion`。
- `EvalResult`:`ruleHit` / `finalDecision` / `hitDecisions` / `nodeTrace` / `errorCode` / `score` / `category` / `decision`(去 `actionResults`)。
- `MetadataResponse`:`conditionTypes`(现状 v1 空 stub)+ `availableMetrics`(去 `actionTypes`)。
- PUSH:接收→异步评估→落库 session/trace,无派发。

## 五、风险与对策

- **删除面大,跨 ~8 模块**:由 writing-plans 拆任务,**自底向上**(kernel 模型 → 各消费方 eval/config/api → DB 迁移 → 文档),每步 `$MVN -pl <module> -am test`,一轮收尾 `clean test` 兜底。
- **`rule_version.decision_bindings` 旧 JSON 带 `actions`**:`DecisionBinding` 去 `actions` 后反序列化。**先确认 `AstJsonCodec` 是否 disable `FAIL_ON_UNKNOWN_PROPERTIES`**(早先注释提及它仍在用该 feature)——宽容则旧字段被忽略,安全;不宽容则 greenfield 直接**清库重建**(truncate rule_version / 重新发布样例)。计划任务里明确二选一。
- **`InProcessAsyncCommandChannel` / async 包**:确认其是否仅服务 action 派发;若 dry-run / 审计落库也复用同一 channel,只摘 action 分支,别误删旁路。
- **D60 取代多条决策**:00-decisions 为 append-only,D60 追加并显式声明取代 D16/D18/D27/D28/D57;被取代条目正文不改(历史保留),仅 D60 + 汇总表标注。

## 六、非目标(YAGNI)

- 不建任何新的动作 / 事件绑定 / 结果事件 emission 机制(编排交流程引擎;"为 PUSH 消费方发结果事件"是未来增强,本次不做)。
- 不动 PULL/PUSH 双模本身(只去掉 PUSH 的派发副作用)。
- flow-engine 选型 / Flowable 集成 / adapter —— 独立项目,不在本 spec。

## 七、验收

- 全量 `clean test` 绿。
- `grep -rn` 确认无 `ActionHandler` / `@ActionType` / `ActionContext` / `ActionResult` / `decision.actions` / `action_execution` 残留(测试除外的清理彻底)。
- 端到端真起服务:PULL `/api/v1/rule/evaluate` 返回纯 `Decision`(无 `actions` / `actionResults`);SDK `evaluate()` + `EvalResultListener` 正常出决策;`node_trace` 落库正常。
- 派 `rule-engine-reviewer` 审"代码 ↔ 文档对齐"(D60 与各文档、被取代决策的标注一致)。
