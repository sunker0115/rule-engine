# 配置闭环 B 轮 — 设计待办

> 状态:方向已定,待执行(2026-06-10 讨论达成,方向从「作废 D27」纠正为「补齐 D27」)。核心是把从未实装的 D27 接通(decision.actions 接进派发)+ 补 decision 写 API + 修 finalDecision.name 真 bug + 砍 metric binding 死表。

## 背景(现状核实结论)

- **D27("action 挂 decision")从未实装**:派发是 `hitDecisions × scene_action_binding` 笛卡尔积(ActionDispatchService:56-72),params 取 `scene_action_binding.default_params`(:82)——即 D27 之前"action 挂 scene"旧模型。`decision_definition.actions` 列只被 bundle import 写、评估从不读(死列)。
- **scene_action_binding 整表鸡肋**:在 D27/A(触发源唯一=decision、action 与 scene 无关)下,该表退化为纯白名单后是 action 最后残留的 scene 耦合,双重门控,本轮整表砍除(见决策三)。
- **finalDecision.name 永远是空字符串、category 拿不到**:`DecisionBinding` 快照只有 `(decisionCode, priority)`(RuleVersionSnapshot:59),不含 name/category/actions;`resolveRuleDecisions` 回退路径 `new Decision(code, "", priority, ...)`(EvalEngine:237)硬塞空串。这是真 bug(demo 时 name 空的根因)。
- **`DecisionAction` record 已定义但无人引用**(RuleVersionSnapshot:69):快照里有这个结构,但 `DecisionBinding` 不引用、派发不读——死结构,正是 D27 断链处。
- **scene_metric_binding 是死表**:无实体/Mapper/读写口,MetadataServiceImpl 显式绕过白名单。
- decision 写口只有 bundle import(无 Controller/写 Service);三个闭合校验 errorCode 主代码零命中;三张表全空。

## 决策

- **决策一 = A:补齐 D27**。action 的"触发 + 参数"归 decision(tenant 级,scene 无关);单一触发源 = decision,无两层并存。这恰是 D27 当初选 B 否 C 的本意。
- **决策二 = 砍**:砍 metric binding 白名单 + scene_metric_binding 死表,metric 在 tenant 级可用。
- **决策三 = 砍**:砍 scene_action_binding **整表**(连同 D50 写 API)。理由:D27/A 下触发源唯一=decision、params 归 decision.action,该表退化为纯 actionType 白名单后是 action 最后残留的 scene 耦合,鸡肋。actionType 合法性(有无 handler)降级为**运行期 NO_HANDLER skip**(与 best-effort 方向一致),不在发布期校验。

> **触发源单一性(对照 D27 否决的 C 方案)**:action 只在 decision 触发,scene **不触发**只"许不许用"。"同一处置在不同 scene 要不同 action"走**不同 decision** 解决,不靠 scene 级差异化 action——那是伪需求,也正是 C(两层并存)被否的原因。

## 落地清单(按依赖顺序)

### 1. 砍 metric binding 白名单(独立,先做)
- Flyway drop table scene_metric_binding。
- MetadataServiceImpl 去掉"v1 简化未启用白名单"措辞(正式就是 tenant 级可用)。
- 文档 01-concepts / 05-storage / 02-runtime 去掉 scene_metric_binding + metric 治理边界相关。
- 不做 METRIC_NOT_BOUND 校验。

### 2. 补齐 D27:decision.actions 接进派发(核心)
**目标**:把"decision_definition.actions(死列)→ 快照 → 评估 → 派发"这条断链接通。

- **快照扩展(方案甲:内嵌 actions,守 D6)**:`DecisionBinding` 从 `(decisionCode, priority)` 扩成 `(decisionCode, name, priority, category, actions)`,actions 复用已定义的 `DecisionAction`。发布期从 `decision_definition` 冻结这些字段进 `rule_version.decision_bindings`,快照自包含、不可变(D6)、评估零额外查询。
  - **取舍**:多规则绑同一 decision 时 actions 在各自快照里重复存(几十字节 JSON,无所谓);**改 decision 不自动生效**——已发布规则用旧快照,需重发规则才生效(D28 已承认此语义)。否决方案乙(快照只存 decisionCode、评估期查 decision_definition):为 greenfield 不存在的"即时生效"诉求推翻 D6 + 热路径加查询,不值。
- **发布期闭合校验(DECISION_CODE_NOT_FOUND)**:校验 rule 的 decisionBindings 引用的 decisionCode 存在于 `decision_definition`,不存在则拒绝;同时冻结上述字段进快照。
- **不做 actionType 白名单校验**:actionType 合法性降级为运行期 NO_HANDLER skip(见决策三),发布期不校验。
- **评估期修 name bug**:`resolveRuleDecisions` 从快照拿 name(不再空串);`Decision` 带上 actions。(注:`decision_definition` 无 category 列,category 是 DECISION_TREE 命中叶子标签、由 tree executor 在 eval 期填,不从 decision 冻结;发布期只冻 name + actions。)
- **派发改造**:`ActionDispatchService.dispatch` 改读 **finalDecision.actions**——遍历 finalDecision 的 actions(actionType + params 取 `DecisionAction.params`),不再 `hitDecisions × scene_action_binding` 笛卡尔积。去掉 `SceneActionBindingIndex` 依赖。仅 finalDecision 派发(hitDecisions 里其他 decision 的 actions 不派发,D27 §2);handler 找不到 → `ActionResult.skipped(NO_HANDLER)`(现成逻辑)。
- **幂等键**:本轮**不碰**(维持现状列),留给 action-best-effort 轮连同 guard 一起收。

### 3. 砍 scene_action_binding 整表
- Flyway drop table scene_action_binding。
- 删 `SceneActionBinding` 实体 + Mapper + `SceneActionBindingService`/Controller/DTO + `SceneActionBindingIndex`(eval 侧);**D50 写 API 整个作废**。
- `ActionDispatchService` 构造去掉 `SceneActionBindingIndex` 依赖(派发只读 decision.actions,见 §2)。
- `ACTION_TYPE_NOT_BOUND` errorCode 删除;运行期 NO_HANDLER skip 兜底(best-effort 一致)。
- 装配处(rule-app)去掉 binding index 的 wiring + SceneChangedEvent 里若有 binding 失效分支一并清理(执行时核对)。

### 4. decision 写 API(tenant 级 CRUD,含 actions)
- DecisionController /admin/v1/decisions:create / update / disable / list(tenant 级,非 scene 级)。
- DecisionService + impl:CRUD,照 SceneActionBindingServiceImpl 套路(参数校验、落库、发 OperationAuditedEvent 审计;decision 是 tenant 级,无需 SceneChangedEvent)。
- 字段:code / name / priority / description / category / status / **actions**(List<DecisionAction>)。
- DTO 转实体:MapStruct(web/admin/convert/)或手写(字段少可手写,参照样板)。
- actionType 合法性本轮不在写期校验(降级运行期 NO_HANDLER skip,见决策三)。

## 迁移
- `decision_definition` 0 行 + 存量规则引用 decisionCode → 开 DECISION_CODE_NOT_FOUND 校验前必须回填 decision(为现有 decision_bindings 出现的所有 decisionCode 建条目,含 actions),否则存量规则重发全拒。
- greenfield:存量是 loadtest 压测规则(可删)+ demo(rule 867 引用 REJECT)。先建 REJECT/REVIEW/PASS decision(REJECT 挂 SEND_ALERT 等 action)、重发 demo;压测 lt-rule 直接删。
- 现状 demo 的 action 配在 scene_action_binding.default_params → 迁移到对应 decision.actions,随后该表整体砍除。

## 收尾
- 全量 clean test。
- doc-consistency-review 扫 00/01/02/04/05/06 自洽(D27 从"待实装"转"已实装";scene_action_binding 整表删除;D50 作废)。
- 改 docs + rule-* 代码,显式调用 rule-engine-reviewer 审代码对齐文档。

## 文档落点
- **00-decisions**:D27 历史条目不改;追加一条决策说明"D27 本轮实装 + 触发源单一性(否 C 两层并存)+ scene_action_binding 整表砍除 + D50 作废"。
- **01-concepts**:§3.7 Action 归属确认挂 decision、§3.19 Decision 字段表 actions、删 scene_action_binding 相关概念。
- **05-storage**:decision_definition.actions 保留并启用;删 scene_action_binding 表。
- **04-extension**:ActionHandler actionType 合法性 = 运行期 NO_HANDLER skip(去掉"注册到 Scene 白名单"描述)。
- **06-frontend**:配规则/decision 时 actionType 下拉不再按 scene 白名单过滤(改全局 handler 列表或自由填)。

## 取舍(已接受)
- **补齐 D27**:action 触发源唯一 = decision(tenant 级);放弃 scene 级 action 差异化(伪需求,走不同 decision 解决)。改动比"作废 D27"大,但接通的是正确实现而非固化错误模型。
- **砍 scene_action_binding 整表**:action 端到端 tenant 级、与 scene 无关;放弃"scene 限制可用 actionType"的治理边界(greenfield 无此诉求,YAGNI);actionType 非法 → 运行期静默 skip(best-effort)。
- **砍 metric binding**:放弃"scene 只能用绑定 metric"的治理边界,metric 在 tenant 级对所有 scene 可用。

## 与其它待办关系
- 独立于 payload-direct-reference / pregate-convergence。
- 与 **action-best-effort 强交叉**(都动 ActionDispatchService):
  - 本轮改派发的**数据来源**(scene_action_binding 笛卡尔积 → decision.actions),best-effort 轮改派发的**投递语义**(best-effort 化 + 砍 retry/补偿/幂等 guard)。
  - **幂等键 + guard 整块留 best-effort 轮**,本轮不碰,避免两轮打架。执行建议:先做本轮(接通 D27),再做 best-effort 轮(在已接通的派发上做投递语义收敛)。
