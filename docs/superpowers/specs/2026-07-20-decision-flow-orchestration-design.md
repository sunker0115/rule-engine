# DECISION_FLOW —— 决策图编排层设计

**日期**：2026-07-20
**状态**：设计定稿，待实现（对应决策 D75）

## 背景

现有规则模型是「Scene 下一堆平铺规则 + 优先级合成策略」：每条规则各自独立求值，命中后按 `decisionStrategy`（HIGHEST_PRIORITY / ALL_HIT / FIRST_HIT）合成。这个模型服务「运营配一条条独立规则、批量治理」的定位，很合适。

但它表达不了一类需求：**在一次评估内，把多条决策串成有分支的流水线**——「先跑决策 A → 按 A 的结果分支 → 命中某支再跑决策 B → 输出」。跨规则复用、多步编排、按中间结果分流，扁平模型都做不到。

对标 GoRules ZEN Engine 的 JDM（JSON Decision Model）：它把一个决策建成有向图，节点类型有 Decision Table / Switch / Function / Expression / Decision（调子决策）。**关键观察**：zen 的图里，叶子节点就是决策表本身，`Decision` 节点是调用另一张子图——它从不把一张决策表拆成更小的图节点。图只做**编排**，叶子是**原子**。

本设计把这套思路**加性**地引入本项目：新增一种规则形态 `DECISION_FLOW`，body 是一张 DAG，节点引用现有的规则形态作为叶子，图只做编排。**内核、Metric SPI、5 种既有形态、发布/灰度/血缘全部复用,不重写。**

## 目标

- 新增 `DECISION_FLOW` rule kind：一条规则的 body 是决策图 DAG。
- 图节点最小集四种：`RuleRef`（调一条已有规则）、`Switch`（按表达式结果分支）、`Transform`（表达式变换 context）、`Output`（产出决策）。
- 图**只做编排**：叶子决策仍是现有 5 形态，通过 `RuleRef` 引用，作者用既有表单编辑器编写，图不吃 `AstNode`。
- 一次评估内同步跑完（无状态、无持久化、无等待）——是 DMN 类决策图，**不是** BPMN 类流程引擎。
- 发布期把 `RuleRef` 引用的规则版本冻进快照（D6 不可变），评估期零额外查询。
- 复用现有取数编排：flow 快照的 `metricDependencies` = 全图引用规则的并集，`EvalContextAssembler` 一次取全。

## 非目标（本轮不做）

- **不做流程引擎**：无状态实例、无持久化、无人工任务、无定时/等待/补偿。要长运行有状态编排，另立项目（参考 raftkit 拆分先例），规则做被调节点。08-evolution §2.1/§2.4 与 README D12 行早期写的「决策流不进 Rule.kind、归流程引擎」特指这类**有状态动作编排**（仍成立）；本设计的 DECISION_FLOW 是**同步纯决策图**，与之不同物——命名双义已在 D75 拆分。判据 = 要不要「等」：要等归流程引擎，一口气算完归 DECISION_FLOW。
- **不做 Function/JS 节点**：zen 有 QuickJS 跑 JS 变换节点。本项目 D60 已把引擎净化为纯决策，`Transform` 只允许表达式（走既有 6 引擎之一），**不允许任意副作用脚本**，守纯决策边界。
- **不重写扁平模型**：`DECISION_FLOW` 是并列的第 6 种形态，不替换 AST_BOOLEAN 等。单条规则仍可独立存在，不强制包一层图。
- **不做嵌套深度无限的子流程**：`RuleRef` 可引用另一条 `DECISION_FLOW`，但发布期环检测拒绝成环；深度限制留运维参数。

## 核心模型

### 形态归属：script 范式，非 ast 范式

现有形态分两类承载 body：
- AST 系（AST_BOOLEAN / SCORECARD / DECISION_TREE / DECISION_TABLE）→ 塞进同一个 `conditionAst` 字段（`AstNode` sealed 家族不同根）。
- EXPRESSION_SCRIPT → 走平级独立字段 `ScriptSource script`，`conditionAst=null`。

**`DECISION_FLOW` 仿 EXPRESSION_SCRIPT**：新增平级独立 typed 字段 `FlowGraph flowGraph`，`conditionAst=null`。全链路照抄 `scriptSource` 的孪生位点（实体列 / 迁移 / RuleVersionRow / ReadMapper SQL / SnapshotAssembler / AstJsonCodec / RuleVersionSnapshot / RuleContent / 请求 DTO / 前端 store & types）。

> **三承载互斥**：`conditionAst`（AST 系四形态）/ `scriptSource`（EXPRESSION_SCRIPT）/ `flowGraph`（DECISION_FLOW）按 kind 三选一，同一 `RuleVersion` 只填其一，另两个为 null。01-concepts §3.4 「ast 与其他 kind 结构互斥」需登记 flowGraph 为第三承载。

### Scene 归属与 RuleRef 作用域

DECISION_FLOW 与所有规则一样，属于一个 Scene，走同样的 Matcher（`tenant + scene + eventType`）路由。

`RuleRefNode` 按 `ruleCode` 引用，`ruleCode` 在 tenant 内唯一——所以**技术上 `ruleCode` 不感知 Scene**，RuleRef 天然可以跨 Scene 引用。

**v1 限制同 Scene**：发布期校验 `resolveFlowDraft` 拒绝 RuleRef 引用其他 Scene 的规则。理由：治理简单——被引规则在同一 Scene，血缘图不发散、排障直达。将来放开只需去除此校验，不动协议/字段/存储。

### FlowGraph / FlowNode 多态模型

仿 `AstNode` 的 sealed + Jackson 多态定式（`@JsonTypeInfo(use=NAME, property="type")` + `@JsonSubTypes`，注解在 `com.fasterxml.jackson.annotation`），另起一个 sealed 家族，放新包 `api/model/flow/`（对齐 `api/model/ast/`）：

```
FlowGraph {
  List<FlowNode> nodes;      // 节点，各带唯一 id
  List<FlowEdge> edges;      // 边：from nodeId → to nodeId，Switch 出边带 caseKey
  String inputNodeId;        // 入口
}

sealed interface FlowNode permits RuleRefNode, SwitchNode, TransformNode, OutputNode

RuleRefNode(String id, String ruleCode)
    // 引用一条已有规则；版本发布期冻结（见下），不写死在图里
SwitchNode(String id, ExpressionLang lang, String expression, List<String> caseKeys)
    // 按表达式结果值选出边；表达式复用既有引擎；default 支持
TransformNode(String id, ExpressionLang lang, String expression, String outputKey)
    // 用表达式算一个值写进 context 的 outputKey，供下游节点/Switch 读
OutputNode(String id, String decisionCode)
    // 终点：把命中决策合成进 flow 结果
```

> record 若含 primitive 字段，按 memory「Jackson3 primitive 反序列化」加 `@JsonSetter(nulls=AS_EMPTY)`。

### 求值语义

- 从 `inputNodeId` 顺边遍历，context 沿边流动（同 zen 从左到右）。
- `RuleRefNode`：拿被引规则的冻结 snapshot，调**单规则求值入口** `RuleVersionExecutor.execute(snap, ctx)`，命中/未命中 + trace 汇入。
- `SwitchNode`：求值表达式 → 匹配 `caseKey` 走对应出边；无匹配走 default 边；无 default 则该分支终止。
- `TransformNode`：求值表达式 → 写 `ctx[outputKey]`，继续。
- `OutputNode`：产出 `decisionCode` 对应决策，进入 flow 结果集。
- flow 内多个 `OutputNode` 命中 → 按 flow 自身的合成语义收敛（复用规则级 `decisionStrategy` 语义，flow 作为一条规则参与 Scene 级合成）。
- 短路 / 错误不短路 / trace 收集：沿用现有 executor 契约（`TraceScope.COLLECT.orElse(true)`，sink 为 null 时零分配）。

**EvalResult 契约（不新增字段）**：DECISION_FLOW 复用现有 `EvalResult` record（`rule-kernel/.../api/model/EvalResult.java`，字段：`ruleHit` / `finalDecision` / `hitDecisions` / `nodeTrace` / `errorCode` / `score` / `category` / `decision`）——`OutputNode` 产出的决策进 `finalDecision` / `hitDecisions`（与普通规则同，flow 作为一条规则参与 Scene 级合成）；`ruleHit` 语义 = 图是否产出任一决策（有 Output 命中为 true）。不新增字段。

> 注意：01-concepts §3.4 EvalResult 契约使用字段名 `satisfied`，此为文档与代码的既有命名漂移（代码实际字段为 `ruleHit`），本条按代码实际字段名写。01-concepts 的漂移留后续文档维护收敛。

## 与既有求值管线的接缝（为什么不是打补丁）

DECISION_FLOW 只是 `RuleVersionExecutor` SPI 上的第 6 个实现，不改旧 5 个 executor、不改 EvalEngine 分派、不改 Assembler 取数、不改合成管线。以下逐关节说明输入端/图内传递/输出端的接缝。

### 输入端：flow 如何拿到评估数据

现有链路——`EvalContextAssembler.collectChosenVersions(candidates)` 把所有候选规则的 `metricDependencies` 并集 → 一次并发取全 → 进 `EvalContext`。

DECISION_FLOW 的 `metricDependencies` 在**发布期已冻成全图 RuleRef 引用规则的并集**（见 §发布期解析冻结第 3 点）。例如 flow `RuleRef(A) → Switch → RuleRef(B)`，A 依赖 `metrics.txn_cnt_7d`、B 依赖 `metrics.user_level`，flow 自己的 `metricDependencies` = `{txn_cnt_7d, user_level}`。`EvalContextAssembler` 扫到这条 flow 候选时自动把所需 metric 并进取数集合，**跟普通规则走同一条路，零区别对待**。FlowExecutor 拿到的 `EvalContext` 已是全图所需，不改 Assembler 一行代码。

### 输出端：flow 产出的决策如何参与 Scene 合成

flow 作为**一条规则**，`execute()` 返回 `EvalResult{finalDecision, hitDecisions, ruleHit}`——签名与 `InterpretedExecutor`（AST_BOOLEAN）完全相同。`EvalEngine` 把它的 hitDecisions 和其他规则的 hitDecisions 一起按 `decisionStrategy` 排序合成，flow 不享有任何特殊路径。

flow 内部的 `OutputNode` 产出的 decisionCode 与 `RuleRef` 命中带回的 decisionCode 一并汇入 flow 的 `hitDecisions`，对 EvalEngine 透明。`EvalEngine` 只知道「这条规则命中了这些决策」，不知道这些决策是 AST 算的还是 flow 串出来的。

### 图内部上下文传递：flow 命名空间

这是唯一新增的抽象。既有 `EvalContext` 不可变，图遍历中需要传递中间值（Transfom 产出 → Switch 读）和上游 RuleRef 求值结果（Switch 按结果分支）。

**约束：RuleRef 节点不应看到 flow 内部变量。** RuleRef 引用的是独立编写、独立发布的规则，其 author 不知道也不该知道「我的规则会被某个 flow 调用」。如果 flow 能把变量注入 RuleRef 的评估上下文，被引规则的行为就依赖调用者——可测试性崩了，D6（快照在任何时候求值结果一致）也破了。

**解法**：新增第 5 个表达式命名空间 `flow`，与 `metrics/payload/subject/now` 平级，**仅在 flow 图内的 Switch/Transform 表达式中可见**：

```
RuleRef(A) → Transform(outputKey="riskScore", expression="metrics.score * 1.5")
           → Switch(expression="flow.riskScore > 80")
                ├─ [>80] → RuleRef(B)
                └─ [≤80] → Output(PASS)
```

- `Transform` 算完写 `flow.riskScore`，下游 Switch 通过 `flow.riskScore` 读到
- `RuleRef(B)` 拿到的 ctx 是**原始 EvalContext**（不合并 flow 变量），保持被引规则的行为独立
- 实现：`ExpressionEngine.compile()` 声明 `flow: map(string, dyn)`，FlowExecutor 在 walker 中维护一个 `Map<String, Object> flowVars`，进入 Switch/Transform 时合并进表达式绑定

**跨规则的数据传递不靠 flow 变量，靠 Switch 读 RuleRef 求值结果。** 如果 B 是否执行取决于 A 命中了什么决策，Switch 读的是 walker 持有的「上一步 RuleRef 返回了什么」，不需要注入被引规则的 context：

```
RuleRef(A) → Switch(expression="hitDecisions[0].code")   // ← walker 在内部持有 A 的结果
               ├─ [REJECT] → Output(REJECT)               // A 拒绝，短路
               └─ [PASS]   → RuleRef(B)                   // A 放行，继续
```

### 改动影响面（逐关节自检）

| 连接点 | 改动 | 性质 |
|---|---|---|
| 输入（metric 取数） | **不改 Assembler**——发布期已冻并集，自动收 | 复用现有 |
| 输出（Scene 合成） | **不改 EvalEngine 合成**——flow 就是一个 RuleVersionExecutor，返回 EvalResult | 实现既有接口 |
| 图内上下文 | 加 `flow` 命名空间进 ExpressionEngine（声明一个变量），RuleRef 不合并 flow 变量 | 加性扩展，旧引擎不受影响 |
| RuleRef 调单规则 | 调现有 `RuleVersionExecutor.execute(snap, ctx)` | 复用现有入口 |
| EvalEngine 分派 | `Map<kind, executor>` 加一个 entry | 加性，不改 switch/if 链 |
| 旧 5 形态 executors | **零改动** | 不感知 flow |
| 内容序列化孪生（Hasher/Bundle/详情 VO/Export·Import） | 各加 `flowGraph` 字段/键（照抄 `script` 孪生） | 加性；漏则详情/导出丢 body、两条不同 flow 撞同 hash |
| SDK 嵌入式评估（`RuleEngineClient`） | executors 注册 FlowExecutor | 加性；漏则命中 AST_BOOLEAN 回退陷阱出错 |
| trace 记录 | 编排节点复用 `NodeTrace` 树，RuleRef 挂叶子子树；新增平级 `FlowNodeType`，`NodeType` **不动** | 复用载体 + 平级枚举，不污染 AST 词表 |

整个 DECISION_FLOW **没有改一行现有求值/取数/合成的核心路径**。打补丁的特征是「因为有新需求，所以在旧代码里塞 if-else 分支」——这里不存在。



## 存储

`RuleVersion` 加 typed JSON 列，照抄 `scriptSource` 整条：

```java
@TableField(typeHandler = Jackson3TypeHandler.class)
private FlowGraph flowGraph;
```

- 另加 `referenced_snapshots` JSON 列：发布期冻结的被引规则完整快照（`Map<ruleCode, RuleVersionSnapshot>`），评估期直读组装进 `RuleVersionSnapshot.referencedSnapshots`，FlowExecutor 的 RuleRef 直接取用，守零额外查询。
- 迁移：`V1_39__rule_version_flow_graph.sql`（当前最高 V1_38），`ALTER TABLE rule_version ADD COLUMN flow_graph JSON NULL` + `ADD COLUMN referenced_snapshots JSON NULL`。纯 ADD COLUMN，无需 COLLATE。
- 索引读取链同步（script 的孪生位点逐一改齐）：`RuleVersionRow` 加 `flowGraphJson` + 兼容构造；`RuleVersionReadMapper` 三条 `@Select` 各加 `rv.flow_graph AS flowGraphJson`；`SnapshotAssembler.assemble()` 加反序列化；`AstJsonCodec.deserializeFlowGraph()`（仿 `deserializeScriptSource`）；`RuleVersionSnapshot` record 加 `FlowGraph flowGraph` + builder。
- config-svc 内容序列化孪生（同 script 一条不落）：`RuleContent` / `RuleDetailVO` / `RuleVersionContentVO` / `RuleBundle` 各加 `flowGraph` 字段（record 改构造器 → `ConfigServiceImpl` 两处 VO 装配点须同步补 `getFlowGraph()`，否则编译断）；`RuleContentHasher.ruleHash` 签名 + canonical 加 `flowGraph`（**幂等红线**：flow 的 ast/script 均 null，不进 hash 则不同 flow 同 hash）；`RuleExportService` / `RuleImportService` 携带/读回 flowGraph。

## 发布期解析、冻结、校验

在 `PublishService.resolveAndValidate(...)` 加 flow 分支（仿 EXPRESSION_SCRIPT 的提前 return）：`resolveFlowDraft(...)` 做四件事：

1. **结构校验**：DAG 合法（有 input、无孤儿、Switch 出边 caseKey 与 `caseKeys` 一致、Output 的 decisionCode 存在），加进 `validKinds` 两处 Set + `validateKindStructure`。
2. **RuleRef 版本冻结**（复用 `freezeMetricDeps` / `freezeDecisionBindings` 同款模式）：遍历 `RuleRefNode`，按 code 查被引规则的 **ACTIVE 版本**，冻结 `(code, version)`；被引规则无 ACTIVE 版本 → 拒绝发布（仿 metric `:555` 拒绝）。把被引规则的完整 snapshot（含其 conditionAst/script/bindings/metricDeps）冻进 flow 快照。
3. **metric 依赖并集**：flow 版本的 `metricDependencies` = 全图 `RuleRef` 引用规则的 metricDeps 并集（Switch/Transform 表达式引用的 metric 也扫进来）。这样评估期 `EvalContextAssembler.collectChosenVersions` 自动一次取全，FlowExecutor 拿到的 `EvalContext` 已含全图所需 metric，无需二次取数、无需触碰 index/DB。
   - **payload 依赖同理并入**：全图 Switch/Transform 表达式引用的 `payload.*` 字段扫入，调 `freezePayloadDeps` 冻结并校验字段已在 `scene.payloadSchema` 声明（undeclared 拒 `UNRESOLVED_VARIABLE`），与 EXPRESSION_SCRIPT 的 payload 处理对称——否则 flow 表达式引用未声明 payload 会发布期静默通过、运行期静默 null。
   - **typeCheck 为 v1 非目标**：flow 的 Switch/Transform 表达式发布期只做 `compile` 语法校验，不做脚本 kind 的 `engine.typeCheck` 强类型检查（图 type env 需按节点构建，较脚本单表达式复杂，留 backlog）。
4. **环检测前置**：发布期即拒绝成环的 flow（详见静态分析）。

`ResolvedDraft` 加 `FlowGraph` 字段，`buildDraftVersion()` 加 `rv.setFlowGraph(...)`。`createDraft` / `editDraft` / `newVersion` 三入口现各只读 `content.script()`，三处都补读 `content.flowGraph()` 透传，否则草稿收不到 flowGraph。

## 静态分析：图环检测 + 死节点可达性

现有 7 类冲突检测是「规则集两两」维度；flow 的分析是「单规则图内部结构」维度，是新增维度：

- `AnalyzableRule` 加 `FlowGraph flowGraph` 字段，`RuleAnalysisServiceImpl` 拆入。
- 新建 `FlowCycleDetector`（RuleRef 引用图成环）、`FlowReachabilityDetector`（从 input 不可达的死节点），仿 `DeadRuleDetector` 静态 detector 结构，各返回 finding list；新增 `FlowCycleFinding` / `FlowDeadNodeFinding` record 到 `api/analysis/`。
- `RuleSetAnalyzer.analyze()` 挂上两个 detector + 各自 Comparator；`RuleSetAnalysisReport` 扩字段。
- 环检测只需遍历本 flow 的 FlowNode 邻接，不需 CubeProjector 投影。
- **现有 detector 的 kind 连带**：`CoverageGapDetector` 的 `switch(kind)` 无 default，需补 `case DECISION_FLOW`（收集全图 `OutputNode.decisionCode`），否则 flow 落空被误判为"不产决策"；`RedundancyDetector` if-else 链 flow 落 else 跳过（正确），仅更新注释。
- **前端按维度分流**（后端分析引擎统一入口，finding 都进 report）：**图内维度**（环/死节点）在 flow 画布原生可视化（成环边标红、死节点置灰），不入规则集冲突面板；**跨规则维度**（决策码覆盖）flow 纳入既有规则集分析面板。环/死节点是图结构问题，画布是其原生展示位——塞进跨规则冲突面板是维度错配。

## API 契约

走现有 `/admin/v1/rules` 同一入口，不加新端点：

- `RuleContentSource` 接口加 `FlowGraph flowGraph()`（仿 `script()`）。
- 三个写请求 record（`CreateRuleRequest` / `EditDraftRequest` / `NewVersionRequest`）各加 `FlowGraph flowGraph` 字段。
- `RuleContent` record 加字段；`RuleController.toContent()` 加透传。
- 详情/导出 VO（`RuleDetailVO` / `RuleVersionContentVO` / `RuleBundle`）加字段，否则 GET 详情、export bundle 丢 flow body（见存储节孪生清单）。
- **SDK 嵌入式评估**：`RuleEngineClient`（executors 注册处）显式 `executors.put(RuleKind.DECISION_FLOW.tag(), flowExecutor)`——同 ScriptExecutor 始终注册的理由，避开 EvalEngine 对未知 kind 回退 AST_BOOLEAN（flow 的 conditionAst=null）的陷阱；`RuleEngineClientAutoConfiguration` 补依赖装配。
- kind 只是请求体字符串字段，`DECISION_FLOW` 同一 POST/PUT 承载。

## 前端：画布编辑器（新增 reactflow 依赖）

- `getRuleKindOptions`（`constants/enums.ts`）加 `DECISION_FLOW` 选项 + i18n（zh-CN / en 的 `enum.kind`）。
- 创建规则弹窗 `rule-list` / `rules-all` 的 `handleCreate` 按 kind 播种默认体，须加 `DECISION_FLOW` 分支塞最小合法 FlowGraph 骨架（类比 DECISION_TREE）——否则选了新 kind 但送空 body，被发布期结构校验拒、建不出 flow。
- `CenterPanel.tsx` `renderEditor()` 加 `if (kind === 'DECISION_FLOW') return <FlowCanvasEditor .../>`（仿 ScriptEditor 分派）。
- 新建 `FlowCanvasEditor.tsx`，引入 reactflow：拖 4 种节点、连边、RuleRef 节点内嵌「选一条已有规则」下拉。flow 数据**不走 `ast`**（`ast` 类型是 `AstNode` 联合），在 `ruleStore` 加平级 `flowGraph` 状态（仿 script）。
- `types/flow.ts`（对齐后端 record，判别字段 `type`），与 `types/ast.ts` 平级；`types/rule.ts` 的 `RuleKind` 联合加 `'DECISION_FLOW'`（**硬伤**：不加则 `kind==='DECISION_FLOW'` 比较报错、`npm run build` 直接挂）+ 请求/详情类型加 `flowGraph`。
- 提交时把 flowGraph 塞进写请求体 `flowGraph` 字段。
- 只读链（详情/版本查看/diff）：`index.tsx` `loadFromDetail` 回填 flowGraph；`VersionContentDrawer` / `VersionDiffDrawer` 加 flowGraph 展示项 + i18n `versionContent.flowGraph`。只读用 `json(content.flowGraph)` 平铺即可，**不做只读画布**（与现有 script 一致，守以简为先）。
- 分析展示分两维度：**跨规则**（决策码覆盖）flow 纳入 `ANALYZABLE_KINDS`，进规则集分析面板；**图内**（环/死节点）在 `FlowCanvasEditor` 画布原生可视化（成环边标红、死节点置灰），不塞进规则集冲突面板。
- flow trace 复用后端 `NodeTrace` 树（RuleRef 挂叶子子树），`fetch-trace-view` / `NodeTraceFormatter` 原样渲染，仅按 `FlowNodeType` 新 tag 加图标。
- **节点下钻编辑（一个画布搞定）**：RuleRef 双击 → 抽屉内嵌**被引规则的现有编辑器**（4 个受控编辑器直接复用；`ScriptEditor` 从 `useRuleStore` 单例对齐为受控 props 后同样复用，5 个编辑器均不重写），编辑落**被引规则自己的草稿**（走 `editDraft`，非 flowGraph）；画布内还可"新建本场景叶子规则"（`createDraft`）建完自动引用，消除跳出去建规则的断点。抽屉提示**冻结隔离**：改叶子进其草稿、需各自发布，已发布 flow 引用冻结版本不受影响（D6）。
- 这是 DMN「DRD 图 + 双击下钻决策逻辑」/ ZEN「graph + 节点编辑器」的标准形态——**体验合一在一个画布，存储/发布/冻结仍是引用**（叶子是一等规则，flowGraph 只存图 + ruleCode；把叶子逻辑内联进 flowGraph 才是打补丁：违反数据边界规范、丢复用/版本/血缘、JSON 膨胀）。
- **三栏适配**：左栏元信息/版本/DryRun 通用不动，flow 纳入分析后显示规则集分析摘要（跨规则维度，图内环/死节点仍在画布）；中栏编辑区换 `FlowCanvasEditor`；右栏 executor(lang) tab 本就只 EXPRESSION_SCRIPT 显示（flow 无规则级 lang，各 Switch/Transform 节点自带），preGate tab 保留，**decisionBinding tab 需把 `DECISION_FLOW` 加进 `showBinding` 排除**——决策由 Output 节点内联产出，不走 decisionBinding（类比评分卡 bands / 决策树叶子内联）。

## Trace 可视化（分层演进，复用既有组件）

flow 是图，评估回放的最优形态也是图——但按 §2 分层落地：首版保底、增强渐进，每层都复用既有能力不新造引擎/组件：

| 层级 | 形态 | 回答的问题 | 复用 | 落地 |
|---|---|---|---|---|
| **首版** | Trace 树 | 走了哪些节点 | `components/trace-tree` 零改造，仅加 `FlowNodeType` 四标签图标 | P6 |
| 增强1 | 画布路径高亮回放 | 为什么走这条 / 没走那条 | 只读 `FlowCanvasEditor` + trace overlay；RuleRef 点开叶子 AST trace 仍用 `trace-tree` | 迭代 |
| 增强2 | What-if 反事实探针 | 换个输入会走哪、出什么决策 | 现有 DryRun + 画布回放，改输入即时重算 | 迭代 |
| 增强3 | 路径覆盖率热力图 | 生产上每条分支多久走一次、哪条从没走 | 评估审计聚合；与静态死节点分析形成「静态不可达 + 动态没走过」闭环 | 迭代 |

原则：**图归图**（flow 编排走画布回放）、**树归树**（叶子规则 AST 走 trace-tree）。首版只做 Trace 树，增强项不阻塞主线。四层原型见同目录 `2026-07-20-decision-flow-{trace,trace-canvas,whatif}-mockup.html`，编辑器原型见 `2026-07-20-decision-flow-mockup.html`。

## 边界纪律

- `FlowNode` / `FlowGraph` / `FlowExecutor` 全放 kernel，**禁引 Spring**（ArchUnit `KernelArchTest` 会红）。
- flow 冻结需查被引规则，在 config 侧 `PublishService` 做，**禁 kernel/config 反向依赖 eval**。
- flow 发布仍走现有 `RulePublishedEvent` 触发 eval 索引热更，**不新增事件类别**。
- 事件/取数/存储/枚举一律遵循 CLAUDE.md「数据类型与边界规范」「副作用与事件解耦」：typed record、封闭取值 enum、无裸 Map/String。

## 改动文件清单（概览，细见实现计划）

| 模块 | 新增/改动 |
|---|---|
| rule-kernel | `RuleKind`+值、`api/model/flow/*`（FlowGraph/FlowNode/4 子节点）、`FlowNodeType`、`internal/evaluator/FlowExecutor`、`analysis/FlowCycleDetector`+`FlowReachabilityDetector`+2 finding、`CoverageGapDetector` 补 case、`AnalyzableRule`+字段 |
| rule-config-svc | `PublishService.resolveFlowDraft`+冻结+校验+三入口读 `content.flowGraph()`、`MetricDependencyCollector` flow 分支、`ResolvedDraft`+字段、`RuleContent`/`RuleDetailVO`/`RuleVersionContentVO`/`RuleBundle`+字段、`ConfigServiceImpl` 两处 VO 装配、`RuleContentHasher`+flowGraph 键、`RuleExportService`/`RuleImportService` 携带、`RuleAnalysisServiceImpl` 拆入、迁移 V1_39 |
| rule-eval-svc | `RuleVersionRow`/`RuleVersionReadMapper`/`SnapshotAssembler`/`AstJsonCodec`/`RuleVersionSnapshot`（flowGraph 读取链） |
| rule-api | `RuleContentSource`+方法、3 请求 DTO+字段、`RuleController.toContent` 透传、`EvalAutoConfiguration` 注册 flowExecutor |
| rule-sdk / -spring-boot-starter | `RuleEngineClient` executors 注册 FlowExecutor、`RuleEngineClientAutoConfiguration` 依赖装配 |
| frontend | `enums.ts`+i18n、`CenterPanel` 分派、`FlowCanvasEditor.tsx`、`types/flow.ts`、`types/rule.ts` 联合+字段、`ruleStore` flowGraph 状态、`index.tsx` 回填、`VersionContentDrawer`/`VersionDiffDrawer` 只读展示、reactflow 依赖 |

## 待实现前置

- 动实现前跑 `doc-consistency-review` skill 复扫自洽性。
- **实现期 kind 登记清单**（现处「设计定稿待实现」，以下「已实装」类枚举 **实装后** 才加 DECISION_FLOW，避免谎称已实装）：
  - `01-concepts.md`：§一名词表:27、§3.4 kind 字段:210、§3.4 已实装 kind:261、§3.4 「ast 与其他 kind 结构互斥」:212（登记 flowGraph 为第三承载）、EvalResult 契约:228-243（说明 flow 填 finalDecision/hitDecisions）。
  - `05-storage.md`：§二表清单 rule_version 行:37、§三 rule_version DDL 加 `flow_graph JSON NULL` 列:172-190、rule_definition/rule_version kind COMMENT:156/179（§478-479 维护原则要求 §二+§三+§四 与 01-concepts 同步）。
  - `10-api-contract.md`：kind 可选值:234、请求体加 `flowGraph`。
  - `08-evolution.md` §2.1:27/31/51 表 + §2.4：DECISION_FLOW 落地后由「待实现」改「已实装」。
  - `README.md`:74 D12 行同步；§二核心决策表按需补 D75 行。
  - `00-decisions.md`:240（D12 派生表）为历史条目，append-only 不改。
- 实现按 `docs/superpowers/plans/2026-07-20-decision-flow-orchestration.md` 分阶段推进。
