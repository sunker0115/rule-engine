# 配置闭环 B 轮 — 设计待办

> 状态:方向已定,待执行(2026-06-10 讨论达成)。核心是作废从未实装的 D27 + 砍 metric binding 死表 + 补 decision 写 API + 修 finalDecision.name 真 bug。

## 背景(现状核实结论)

- **D27("action 挂 decision")从未实装**:Decision 内核 record 无 actions 字段;派发是 hitDecisions × scene_action_binding 笛卡尔积(ActionDispatchService:49-73),params 取 scene_action_binding.default_params——即 D27 之前"action 挂 scene"旧模型。decision_definition.actions 列只被 bundle import 写、评估从不读(死列)。
- **finalDecision.name 永远是空字符串**:评估组装不查 decision_definition,name 取快照里的空串(EvalEngine:228-238)。这是真 bug(demo 时 name 空的根因)。
- **scene_metric_binding 是死表**:无实体/Mapper/读写口,MetadataServiceImpl 显式绕过白名单。
- decision 写口只有 bundle import(无 Controller/写 Service);三个闭合校验 errorCode 主代码零命中;三张表全空。

## 决策

- **决策一 = B**:作废 D27。decision 退回纯语义(code/name/priority/category/status),删 decision_definition.actions;action 正式归 scene_action_binding(scene 级,保留现状派发 + scene 级差异化——这恰是 D26 最初否决"挂 decision"时想保的能力)。
- **决策二 = 砍**:砍 metric binding 白名单 + scene_metric_binding 死表,metric 在 tenant 级可用。

## 落地清单(按依赖顺序)

### 1. 砍 metric binding 白名单(独立,先做)
- Flyway drop table scene_metric_binding。
- MetadataServiceImpl 去掉"v1 简化未启用白名单"措辞(正式就是 tenant 级可用)。
- 文档 01-concepts / 05-storage / 02-runtime 去掉 scene_metric_binding + metric 治理边界相关。
- 不做 METRIC_NOT_BOUND 校验。

### 2. 作废 D27:decision 剥离 action
- Flyway drop column decision_definition.actions。
- DecisionDefinition 实体删 actions 字段(及 DecisionAction 在 decision 侧的使用)。
- 确认并剥离 rule_version.decision_bindings 快照里的 actions:核对 RuleVersionSnapshot 的 DecisionBinding/DecisionAction 结构,decision_bindings 若内嵌 actions 则一并去掉。
- action 归 scene_action_binding(现状派发逻辑不变)。
- 文档:00-decisions 追加一条决策作废 D27(D27 历史条目不改,新追加覆盖);01-concepts §3.19/§3.7 去掉 decision.actions、Action 归属改回 scene_action_binding;05-storage decision_definition 去 actions 列。

### 3. decision 写 API(纯语义 tenant 级 CRUD)
- DecisionController /admin/v1/decisions:create / update / disable / list(tenant 级,非 scene 级)。
- DecisionService + impl:CRUD,照 SceneActionBindingServiceImpl 套路(参数校验、落库、发 OperationAuditedEvent 审计;decision 是 tenant 级,无需 SceneChangedEvent)。
- 字段:code / name / priority / description / category / status(无 actions)。
- DTO 转实体:MapStruct(web/admin/convert/)或手写(字段少可手写,参照样板)。

### 4. 修 finalDecision.name + DECISION_CODE_NOT_FOUND(依赖 3)
- 发布期:校验 rule 的 decisionBindings 引用的 decisionCode 存在于 decision_definition,不存在则拒绝(DECISION_CODE_NOT_FOUND);同时把 decision 的 name/priority/category 冻结进 rule_version.decision_bindings 快照(D6 不可变 + 评估零额外查询)。
- 评估期:resolveRuleDecisions 从快照拿 name/priority/category(不再是空串)。
- 一步把 name bug 与 decisionCode 闭合校验一起做掉。

### 5. ACTION_TYPE_NOT_BOUND 重定位(可选,本轮可不做)
- 路线 B 下 action 不挂 rule/decision → 不在 rule 发布期校验 actionType。
- actionType 合法性(有无注册 handler)可轻量移到 scene_action_binding 写时校验。本轮可暂不做。

## 迁移
- decision_definition 0 行 + 存量规则引用 decisionCode → 开 DECISION_CODE_NOT_FOUND 校验前必须回填 decision(为现有 decision_bindings 出现的所有 decisionCode 建条目),否则存量规则重发全拒。
- greenfield:存量是 loadtest 压测规则(可删)+ demo(rule 867 引用 REJECT)。可先建 REJECT/REVIEW/PASS decision、重发 demo;压测 lt-rule 直接删。

## 收尾
- 全量 clean test。
- doc-consistency-review 扫 00/01/02/05 自洽。
- 改 docs + rule-* 代码,显式调用 rule-engine-reviewer 审代码对齐文档。

## 取舍(已接受)
- 作废 D27:放弃"配置集中(同决策统一一套 action)",换回 scene 级 action 差异化 + 改动小、不动内核派发模型。
- 砍 metric binding:放弃"scene 只能用绑定 metric"的治理边界,metric 在 tenant 级对所有 scene 可用。

## 与其它待办关系
- 独立于 payload-direct-reference / pregate-convergence / action-best-effort。
- 与 action-best-effort 有轻微交叉(都碰 ActionDispatchService),执行时注意先后;本轮不改派发模型,只剥离 decision.actions。
