# 模板编辑器授权 UX + 表达式变量语义 — 设计文档

## Context

D74 参数化模板的**后端**已完整落地(JsonPointer 统一寻址、params 冻结常量命名空间、binder SPI、快照式实例化,全量测试绿 + e2e 验过,功能已默认开启)。但**前端模板编辑器**是 Task 12 的最小占位实现:`bodySkeleton` 是一个 JSON 大文本框、binding 靠手打 JsonPointer(当时 feature 默认关,spec 明确"不追求打磨")。功能既已开启,占位编辑器低于可用门槛——本轮把它按 spec 原意**正式化**:复用规则编辑器搭 skeleton + 点选式声明参数化。

顺带把一个跨规则/脚本/流程的**表达式变量语义**沉淀成权威参考章节(§5)——`metrics`/`payload`/`subject`/`now`/`params`/`flow`/`hitDecisions` 各自的来源、作用域、可变性、隔离规则,供后续理解与维护。

**前置依据**:后端设计见 `2026-07-24-parameterized-rule-template-redesign-design.md`。本文档只覆盖前端授权 UX + 变量语义,不改后端。

## 1. Scope(已锁定)

**本轮做:**
- **AST 四种**(AST_BOOLEAN/SCORECARD/DECISION_TREE/DECISION_TABLE):复用 `RuleBodyEditor` 搭 skeleton + 位置选择器声明 slot + slots/bindings 面板。
- **EXPRESSION_SCRIPT**:脚本参数表(增删改 + "参数化"开关)+ `editableParams` 分流(模板可编辑 / 规则只读)+ `params.` 补全接入现有 `expressionCompletions`。
- **DECISION_FLOW —— 仅结构参数化**:位置选择器覆盖 `RuleRefNode.ruleCode`(选被引规则)/ `OutputNode.decisionCode`(选决策)/ `SwitchNode.caseKeys`(分支键)。
- **共享件**:`SlotValueInput`(按 DataType 渲染值输入,与实例化表单共用)、`expressionCompletions` 加 `params` 命名空间、规则编辑器脚本 `params` round-trip 保真。
- **参照场景(reference scene)**:模板编辑器顶部一个场景选择器,仅供创作期提供 payload 字段补全;不写进模板。

**本轮不做(后端已 ready,前端待真需求):**
- **Flow 表达式内 `params.x` 常量参数化 + flow 参数表**(#8)。`FlowGraph.params` 后端已支持,前端授权 UI 暂缓。
- 内联点选参数化(Drools 式,字段旁开关)——留作后续增强。
- 触发字符唤起(`/` 等)、批量实例化数据网格(Drools Data tab)。

## 2. 核心原则:模板 = 一条正常规则(skeleton)+ 一层"哪些值可调"覆盖

不重新发明 body 编辑。`bodySkeleton` 就是一条**完整、合法、默认值就位**的规则 body;参数化是**纯 opt-in 的旁挂特性**——没被 binding 指到的位置永久固定。所以复用规则编辑器,叠加薄薄的 slot 声明层。

- `RuleBodyEditor` 已是 **prop 驱动、不读 store**(其 Javadoc:"供 CenterPanel 与 flow 下钻抽屉共用"),覆盖 5 种 kind;DECISION_FLOW 复用 `FlowCanvasEditor`。模板编辑器直接复用,替掉 JSON 文本框。
- body 子编辑器(ConditionCard/ScorecardEditor/…)**零改动**,保持规则无关——模板逻辑全留在模板编辑器。

## 3. Slot 声明 UX:内省 + 带标签位置选择器(不暴露 JsonPointer)

- 作者先在规则编辑器里把 skeleton 建好(真实默认值)。
- "参数"面板一个 **`+ 参数化` 按钮**(平时只一个按钮,零杂乱;**不用触发字符**)。点击 → 弹**可搜索 Select**,列出可参数化位置的**人类标签**(如 `AND组 › 条件1「金额 AMOUNT_GT」› 阈值`),打字过滤。
- 选一个位置 → 自动生成 binding(`JsonPointerTarget`,pointer 藏背后,**用户全程不见 JsonPointer**)+ 预填 slot(dataType 从当前值推断、label 预填可改),再补 required/constraint。
- **位置内省器**:按 body 类型遍历 skeleton 产 `{jsonPointer, label, 当前值, 推断dataType}`。是**创作期便利**(提供候选),非运行时机制;真相源仍是显式 `SlotBinding`,不违反"运行时无扫描"。
  - AST:遍历 `conditionAst` 的值位(ConditionNode.params.*、weight;决策表 Row.conditions cell;决策树 IfNode 深层)。
  - Script:`script.params` 的键即候选(见 §4,脚本用参数表更直接,不走下拉)。
  - Flow(仅结构):`flowGraph.nodes[]` 的 `ruleCode`/`decisionCode`/`caseKeys`。

**为什么不用 `/` 等触发字符**:`/` 与 JsonPointer(位置本身是 `/path`)和除法运算符冲突;且会把交互拉向"输入 pointer 文本框",重新泄漏 JsonPointer——与"不让用户碰 pointer"的目标相悖。按钮 + 可搜索 Select 同样满足"按需唤起、不杂乱",且可发现性更高、零泄漏。

## 4. 脚本 params:参数表(单一真相源喂三个行为)

`script.params`(body 里的 map)是**单一真相源**,派生三件事,不重复造:

1. **智能提示**:`params` 作为**第 4 个命名空间**接进现有 `expressionCompletions`(与 metrics/payload/subject 平级),`params.` 后补全**当前 `script.params` 的已声明键**;触发就是现有 `.`,**无新唤起字符**。
2. **skeleton 默认值**:map 里键的值就是冻结默认。
3. **slot 候选**:每个 param 键 = 可绑位置 `/script/params/<key>`。

**参数表**(在 ScriptEditor 源码框下方):

```
┌─ 参数 (params) ───────────────── [+ 添加参数] ┐
│ 参数名      类型      默认值    参数化   操作 │
│ threshold  LONG ▾   100      [✓]     🗑   │
└──────────────────────────────────────────────┘
```

- **创建**:`+ 添加参数` → 填参数名(`params.<名>` 里的名)、选类型(kernel `DataType`)、填默认值(`SlotValueInput` 按类型渲染)。
- **修改**:行内改名/类型/默认值。
- **删除**:🗑;若该行已"参数化",连带移除对应 slot+binding,二次确认。
- **"参数化"列**:勾上 = 提升为 slot(派生 slot schema + binding `/script/params/<名>`);取消 = 退回纯固定常量。脚本**不需要单独位置下拉**——参数表行本身即候选。
- **补全不是创建入口**:`params.` 补全只浮出已声明键(类比:不靠在脚本里打字创建 metric);创建在参数表。
- **可选 lint**:源码引用 `params.x` 但表里没声明 → CodeMirror 标黄"未声明参数"(复用现有 debounced lint 通道)。

## 5. 表达式变量语义(权威参考 —— 按代码实证)

**适用范围**:本节讲的是 **EXPRESSION_SCRIPT / DECISION_FLOW 表达式**的变量绑定面(交给 6 引擎求值的)。AST 四种**不走这套**——AST 通过 `ConditionNode.valueRef`(METRIC/PAYLOAD)+ `ConditionEvaluator` 取值,不是命名空间绑定。

### 5.1 完整绑定面(顶层 key)

| 命名空间 | 来源 | 作用域 | 可变性 | Script | Flow 表达式 |
|---|---|---|---|---|---|
| `metrics.<code>` | `EvalContext`(取数) | 跨规则**共享** | 每事件不同 | ✓ | ✓ |
| `payload.<field>` | `EvalContext`(事件载荷) | 跨规则**共享** | 每事件不同 | ✓ | ✓ |
| `subject.<attr>` | `EvalContext`(主体属性) | 跨规则**共享** | 每事件不同 | ✓ | ✓ |
| `now` | `EvalContext`(评估时钟) | 跨规则**共享** | 每次求值 | ✓ | ✓ |
| `params.<key>` | **本规则 body** 的冻结常量(`ScriptSource.params` / `FlowGraph.params`) | **本规则私有** | 不变(实例化冻结) | ✓ | ✓ |
| `flow.<outputKey>` | 本 flow **TransformNode 运行时算出** | **本 flow 私有** | 求值中变、路径依赖 | ✗ | ✓ |
| `hitDecisions` | 上一步 RuleRef 命中的决策 | **本 flow 私有** | 求值中变 | ✗ | ✓ |

注:`ScriptBindings.from(ctx)` 产出 `{metrics, payload, subject, now}`;`ScriptExecutor` 追加 `params`;`FlowExecutor.evalExpr` 追加 `flow`/`params`/`hitDecisions`。**`subjectId`/`tenantId` 出现在前端补全 builtins 里,但运行时未作为顶层 binding 注入**(既有小不一致,主体标识经 `subject.*` 读;非本轮修复项,记录备查)。

### 5.2 三类变量的本质区别

- **共享输入(`metrics`/`payload`/`subject`/`now`)**:同一个事件的评估上下文 `ctx`,随事件变,**跨规则共享**(被引规则也读同一份)。
- **冻结常量(`params`)**:实例化时冻进 body、之后不变、**本规则私有**、任何执行路径都可用。**结构化 map,可被 JsonPointer 参数化**——这是它存在的核心理由。
- **运行时计算态(`flow` / `hitDecisions`,仅 flow)**:本次求值过程中 Transform/RuleRef 产生,**可变、路径依赖、本 flow 私有**,值嵌在表达式文本里、结构上不可被模板寻址。

### 5.3 `params` vs `flow.*`(为什么 flow 要独立 params,不复用 flowVars)

二者**正交**,不能互相替代:

| | `flow.*`(flowVars) | `params.*` |
|---|---|---|
| 语义 | 流程的中间**计算产出** | **配置常量** |
| 来源 | 运行时 TransformNode 算 | 授权/实例化冻结 |
| 可变/可用 | 边走边填、**路径依赖**(未走到的分支为空) | 不变、**无条件可用** |
| 模板可参数化 | ✗ 值嵌在 Transform 表达式文本里,只能文本替换(token 之罪) | ✓ 结构化字段,JsonPointer 直接绑 |

用 flowVars 装阈值 → 得加 `flow.threshold = 500` 的 Transform,`500` 嵌在表达式文本里 → 参数化就得往文本里塞占位符(否决过的 token 反模式);且常量变成路径依赖 + 每次重算 + 配置与计算态混淆。`params` 就是为"结构化可寻址、无条件可用的冻结常量位"存在的——与脚本 `params` 同一个理由。

### 5.4 隔离规则(D6 强制)

一条 flow 可经 `RuleRefNode` 引用多条规则(可为 script)。**每条规则的 `params` / `flow` 严格锁在本规则内,不跨规则边界**;只有 `ctx`(metrics/payload/subject/now)向下共享。

- Flow 的 `params`(`flowGraph.params`)**只对本 flow 表达式可见**,**不进**被引规则的 `params`。
- 被引规则(如 script)的 `params`(`script.params`)**只在该规则内**,flow 看不到。
- 机制:`FlowExecutor.handleRef` 调被引规则时传**原始 `ctx`**(不合并 flow 的 binding);各 executor 从**各自 snapshot 的 body** 现建 `params`。嵌套 flow 同理逐层隔离。
- **为什么必须如此**:被引规则是冻结快照,须"无论被谁调、行为一致"(D6)。若 flow 能注入 params 改变被引规则行为,快照确定性即破。既有代码注释即"被引规则行为独立于调用方(守 D6)"。

## 6. params 数据全生命周期(AST + Script 闭环)

| 阶段 | params 数据从哪来 | 存到哪 |
|---|---|---|
| 建模板 | 作者在参数表填(名+默认值) | `bodySkeleton.script.params` |
| 参数化 | 勾"参数化"派生 slot + binding | `slots[]` + `bindings[]` |
| 实例化 | 具体值来自请求 `slotValues` | binder.bind 写进 `/script/params/<key>` |
| 冻结 | createDraft→resolveAndValidate(`params.*` 不被抽成 metric/payload 依赖) | `rule_version.body.script.params` |
| 运行 | 冻结的 `body.script.params` | ScriptExecutor `put("params",…)` → 引擎读 |
| 回编辑 | getRule→`bodyToCarriers` 透传 | ScriptEditor 只读展示,存回不丢(round-trip) |
| 补全 | 当前 `script.params` 键集 | 喂 `expressionCompletions` |

单一授权来源(参数表默认值 / 实例化具体值)+ 单一运行时家(`body.script.params`),其余全读它。

## 7. 参照场景(reference scene)

模板是 tenant 级、创作时不绑 scene,但 `RuleBodyEditor` 需要 metric/payload/conditionType/decision 元数据。边界(已核实):

- `metric_definition` **tenant 级**(无 scene_id)→ tenant 全量可列,不需 scene。
- conditionType **SPI 全局**、decision **tenant 级** → 不需 scene。
- **仅 payload 字段**(`payload.*` 补全 + payload-ref 条件)是 **scene 级**(`scene.payloadSchema`)。

**方案(A)**:模板编辑器顶部加"参照场景"选择器,**仅供创作期** payload 补全(复用规则编辑器现成的 `getSceneMetadata`/`getScene`)。**不写进模板**——模板仍 scene 无关、可实例化到任意 scene。若实例化到 payload schema 不兼容的 scene,实例化的 `resolveAndValidate` 正确拒绝(期望行为),参照场景不制造隐藏耦合。

## 8. editableParams:一个组件两个场景

`ScriptEditor` 加 `editableParams` prop:

- **模板编辑器(true)**:参数表可增删改 + 参数化开关。
- **规则编辑器(false)**:参数表只读(round-trip 展示模板注入了什么),补全照常列出,无增删改。

## 9. 组件改动清单(纯前端,后端 0)

- `types/rule.ts`:`ScriptBody`/`BodyCarriers`/`carriersToBody`/`bodyToCarriers` 带 `params`(round-trip)。**已起改**。
- `store/ruleStore.ts`:`script` 类型带 `params`。**已起改**。
- `expressionCompletions.ts`:加 `params` 命名空间 + 分支(约 10 行),补全源 = 传入 param 键集。
- 新增 `SlotValueInput`:按 `DataType` 渲染值输入;实例化表单 + 参数表默认值格共用(部分从实例化页抽取)。
- `ScriptEditor.tsx`:参数表(CRUD + 参数化开关)+ `editableParams` + 自 `script.params` 派生补全键。
- 模板编辑器 `template-editor/`:JSON 文本框 → 复用 `RuleBodyEditor`/`FlowCanvasEditor` 搭 skeleton;参照场景选择器;位置选择器(AST/flow 结构);slots 面板保留结构化表单、binding 改为选位置派生(去掉手填 pointer)。
- 位置内省器:AST walker(值位→标签+pointer+推断类型)、flow 结构 walker(ruleCode/decision/caseKeys)。

## 10. What We're NOT Doing

- **Flow 表达式 `params.x` 常量 + flow 参数表**(#8):后端 ready,前端待真需求。
- **内联点选参数化**(字段旁开关):Drools 式,更方便但要动 5 个共享子编辑器(高耦合),留后续增强。
- **触发字符唤起**(`/` 等):撞 JsonPointer/除法、泄漏 pointer、低频场景可发现性差。
- **批量实例化数据网格**(Drools Data tab):YAGNI。
- **引入外部编辑器**(GoRules jdm-editor 等):产出 JDM/DRL 等**外部引擎模型**,非我们 `RuleBody`,适配 = 换引擎或建翻译层,违背"不过度/不打补丁"。复用的是**自有** `RuleBodyEditor`。

## 11. 业界对标校准

- **Drools Guided Rule Template**:在普通编辑器建规则 + 内联标 "Template Key" + Data tab 批量生成。印证"复用编辑器搭 skeleton";其内联点选留作后续;批量 Data tab 不做。
- **GoRules jdm-editor**(MIT React):可嵌入编辑器,印证"复用编辑器"哲学;其 `${}` 文本插值恰是我们为脚本否决的做法(反面佐证结构化 `params` 更优);其 JDM 是 ZEN 引擎模型,不能 drop-in。

## 12. 测试策略

- 前端测试基座有限,门槛 = `npx tsc -b` 干净 + 手动/e2e 验证。
- 关键手动核对:①模板编辑器建 script 模板(params 表 + 参数化)→ 发布 → 实例化填值 → 查 `rule_version.body.script.params` 冻结正确;②模板实例化的 script 规则在规则编辑器打开→保存,`params` 不丢(round-trip);③`params.` 补全出已声明键;④flow 结构参数化(选被引规则)实例化后 `RuleRefNode.ruleCode` 正确替换。
- 后端无改动,不新增后端测试。

## References

- 后端设计:`2026-07-24-parameterized-rule-template-redesign-design.md`
- 复用锚点:`RuleBodyEditor`(prop 驱动,5 kind)、`FlowCanvasEditor`、`expressionCompletions.ts`、`ScriptEditor.tsx`、实例化表单值输入
- 绑定面实证:`ScriptBindings.from`、`ScriptExecutor`(+params)、`FlowExecutor.evalExpr`(+flow/params/hitDecisions)
- 隔离依据:`FlowExecutor.handleRef`(传原始 ctx,守 D6)
