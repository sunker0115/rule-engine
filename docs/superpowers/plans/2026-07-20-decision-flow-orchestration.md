# DECISION_FLOW 决策图编排层 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新增第 6 种规则形态 `DECISION_FLOW`——body 是决策图 DAG，节点引用现有规则形态作为叶子，图只做编排（RuleRef 调规则 / Switch 分支 / Transform 变换 / Output 产出决策），一次评估内同步跑完。内核、Metric SPI、5 形态、发布/灰度/血缘全部复用，加性不重写。

**Architecture:** `DECISION_FLOW` 仿 EXPRESSION_SCRIPT 走「平级独立 typed 字段」范式——`RuleVersion.flowGraph`（`FlowGraph` typed JSON 列，`conditionAst=null`）。求值加 `FlowExecutor implements RuleVersionExecutor`，图遍历遇 RuleRef 回调单规则求值。发布期把 RuleRef 引用规则的 ACTIVE 版本冻进快照 + metricDeps 并集，评估期零额外查询。静态分析加图环检测 + 死节点。前端加 reactflow 画布编辑器，叶子规则表单编辑器不动。

**Tech Stack:** Java 25、Spring Boot 4.1、Spring Modulith、MyBatis-Plus、Jackson3（多态注解在 `com.fasterxml.jackson.annotation`）、reactflow（前端新增）、JUnit5 + AssertJ + ArchUnit + Testcontainers。前置：`mvn-env` skill 设 `$MVN`（JDK25）。

**设计依据：** `docs/superpowers/specs/2026-07-20-decision-flow-orchestration-design.md`（决策 D75）。

**实现前置：** 先跑 `doc-consistency-review` 扫 spec 与 01-concepts / 02-runtime / 03-rule-expression / 05-storage / 10-api-contract 自洽性，新增 kind 在这些文档登记后再动代码。

---

## 阶段总览

| 阶段 | 内容 | 可独立验证 |
|---|---|---|
| P0 | kernel 图模型（FlowGraph/FlowNode 4 子节点 + RuleKind 值） | 序列化往返 + RuleKindTest |
| P1 | kernel 求值 FlowExecutor + 装配 | FlowExecutorTest（图遍历/RuleRef/Switch/trace） |
| P2 | 存储读取链（实体列 + 迁移 + Row/Mapper/Assembler/Snapshot） | SnapshotAssembler flowGraph 往返测试 |
| P2b | config-svc DTO/Bundle/Hasher 孪生（RuleContent/VO/Bundle/Hasher/Export·Import） | Hasher flow hash 区分 + import/export 往返 |
| P3 | config 发布期解析/冻结/校验 | FlowResolveValidateTest（冻结 + 拒绝无 ACTIVE） |
| P4 | 静态分析环检测 + 死节点 | FlowCycleDetectorTest / FlowReachabilityDetectorTest |
| P5 | API 契约（DTO + 透传） | RuleController 集成测试 |
| P6 | 前端画布编辑器 | 手动 UI 验证 |
| P7 | 端到端功能测试（真起服务，配 flow → 发布 → 评估 → 查落库） | e2e 剧本 |

> 每阶段提交前 `$MVN -pl <module> -am test` 全绿；跨模块改实体类型后本轮结束用 `$MVN clean test` 全量兜底（memory「ENUM→VARCHAR」教训）。

---

## P0 — kernel 图模型

**Files (rule-kernel):**
- Modify: `api/model/RuleKind.java` — 加 `DECISION_FLOW`
- Create: `api/model/flow/FlowGraph.java` / `FlowNode.java`（sealed）/ `RuleRefNode.java` / `SwitchNode.java` / `TransformNode.java` / `OutputNode.java` / `FlowEdge.java`
- Create: `api/model/FlowNodeType.java`（与 `NodeType` 平级的枚举 `RULEREF`/`SWITCH`/`TRANSFORM`/`OUTPUT`；**不往 `NodeType` 加值**——`NodeType` 是 AST 专属词表，flow 平级承载对应平级枚举，`NodeTrace.nodeType` 是 String 可原样承载其 tag）
- Test: `RuleKindTest`（加 tag 断言）、`FlowGraphSerdeTest`（Jackson 多态往返）

- [ ] **Step 1:** `RuleKind` 加 `DECISION_FLOW`，`RuleKindTest.java:11-17` 加 `DECISION_FLOW.tag()=="DECISION_FLOW"` 断言。
- [ ] **Step 2:** 建 `flow/` 包，`FlowNode` sealed interface + `@JsonTypeInfo(use=NAME, property="type")` + `@JsonSubTypes` 4 子类型，各为 record（判别值 == 简单类名）。primitive 字段加 `@JsonSetter(nulls=AS_EMPTY)`。
- [ ] **Step 3:** `FlowGraph` record（`List<FlowNode> nodes` + `List<FlowEdge> edges` + `String inputNodeId`）；`FlowEdge` record（`from` / `to` / nullable `caseKey`）。
- [ ] **Step 4:** `FlowGraphSerdeTest`——含 4 种节点 + 分支边的图 JSON 往返，断言多态判别正确、缺键不报错。
- [ ] **Verify:** `$MVN -pl rule-kernel -am test`

## P1 — kernel 求值 FlowExecutor

**Files (rule-kernel):**
- Create: `internal/evaluator/FlowExecutor.java`（implements `RuleVersionExecutor`）
- Test: `evaluator/FlowExecutorTest.java`

- [ ] **Step 1:** 读 `DecisionTreeExecutor.java:42-78`（分支跳转范本）+ `InterpretedExecutor.java:89-106`（sealed switch + 短路 + trace sink 零分配）+ `EvalResult.java`（确认字段名为 `ruleHit` 非 `satisfied`）后再写。
- [ ] **Step 2:** `FlowExecutor.execute(snapshot, ctx)`：从 `snapshot.flowGraph()` 的 `inputNodeId` 顺边遍历。
  - **上下文传递**：FlowExecutor 内部维护 `Map<String,Object> flowVars`。Transform 产出写 `flowVars`；Switch/Transform 表达式求值时把 `flowVars` 以 `"flow"` key 合并进 bindings（CEL 构造器需加 `addVar("flow", map(string,dyn))`，非 CEL 引擎无需改动——bindings 里有就能用）。**RuleRef 调 `leafExecutor.execute(refSnap, ctx)` 时传原始 ctx，不合并 flowVars**——被引规则行为必须独立于调用方（守 D6 不可变）。
  - **命名空间**：表达式可见变量 = `metrics/payload/subject/now`（既有 4 个）+ `flow`（新增，仅 flow 图内 Switch/Transform 可引用，RuleRef 不可见）。
  - Switch 求值表达式 → 匹配 caseKey 选出边（default 支持）；Transform 求值表达式 → 写 `flowVars[outputKey]`；Output 收 `decisionCode` 进 hitDecisions。
- [ ] **Step 3:** trace **复用 `NodeTrace` 树，不造新载体**：每个编排节点产一个 `NodeTrace`，`nodeType` = `FlowNodeType.xxx.tag()`；**RuleRef 节点的 `children` 直接挂被引规则 `leafExecutor.execute()` 返回的 `nodeTrace` 子树**（叶子 trace 自带 `ruleCode/ruleVersion`，天然标明归属，组合语义免费得）。Switch 用 `actualValue` 记选中 caseKey、Transform 记输出值。trace 收集沿用 `TraceScope.COLLECT.orElse(true)`，sink null 时不分配。`EvalResult.ruleHit` = 图是否产出任一决策（有 Output 命中为 true）。前端 `fetch-trace-view` / `NodeTraceFormatter` 原样渲染，仅需按新 tag 加图标。
- [ ] **Step 4:** `FlowExecutorTest`：单 RuleRef 直连、Switch 两分支各命中、Transform→Switch 读 `flow.xxx` 中间值、**RuleRef 隔离（断言被引规则 ctx 不含 flow 变量）**、多 Output 合成、ruleHit=true/false、trace 收集态 vs 零分配态。
- [ ] **Verify:** `$MVN -pl rule-kernel -am test`

## P2 — 存储读取链

**Files:**
- Modify (rule-config-svc): `RuleVersion.java`（加 `flowGraph` typed 列）；迁移 `V1_39__rule_version_flow_graph.sql`
- Modify (rule-eval-svc): `RuleVersionRow.java`（加 `flowGraphJson`）、`RuleVersionReadMapper.java`（三条 `@Select` 加 `rv.flow_graph AS flowGraphJson`）、`SnapshotAssembler.java`、`AstJsonCodec.java`（加 `deserializeFlowGraph`）
- Modify (rule-kernel): `RuleVersionSnapshot.java`（加 `FlowGraph flowGraph` + builder）
- Test: `SnapshotAssemblerFlowTest`、`RuleVersionRowTest`

- [ ] **Step 1:** grep `scriptSource` / `ScriptSource` / `deserializeScriptSource` 定位全部孪生位点，照抄一遍换成 flow。
- [ ] **Step 2:** `RuleVersion` 加两个 typed JSON 列 `flowGraph`（`FlowGraph`）+ `referencedSnapshots`（`Map<String, RuleVersionSnapshot>`，发布期冻结的被引规则快照，评估期直读守零查询），均 `@TableField(typeHandler=Jackson3TypeHandler.class)`；迁移 `V1_39` 纯 ADD COLUMN `flow_graph JSON NULL` + `referenced_snapshots JSON NULL`（无需 COLLATE）。
- [ ] **Step 3:** `RuleVersionRow` 加 `flowGraphJson` + `referencedSnapshotsJson` + 兼容构造；`RuleVersionReadMapper` 三条 SQL 各加 `rv.flow_graph`/`rv.referenced_snapshots`；`SnapshotAssembler.assemble()` 填 `flowGraph` + `referencedSnapshots`；`AstJsonCodec` 加 `deserializeFlowGraph` + `deserializeReferencedSnapshots`。**注：`RuleVersionSnapshot` 的 `flowGraph`+`referencedSnapshots` 字段+builder 已在 P1 完成，勿重复添加。**
- [ ] **Step 4:** `SnapshotAssemblerFlowTest`——DB row（flowGraphJson）→ snapshot.flowGraph 往返。
- [ ] **Verify:** `$MVN -pl rule-eval-svc -am test`（跨模块，带 `-am`）

## P2b — config-svc DTO / Bundle / Hasher 孪生位点

> P2-Step1 grep 出的 config-svc 侧孪生，单列一节防漏。缺任一处：flow body 不进详情/导出，或两条不同 flow 撞同 hash 坏 import 幂等。

**Files (rule-config-svc):**
- Modify: `api/dto/RuleContent.java` / `api/dto/RuleDetailVO.java` / `api/dto/RuleVersionContentVO.java` / `api/dto/RuleBundle.java`（各加 `FlowGraph flowGraph`，仿 `script`）
- Modify: `internal/service/ConfigServiceImpl.java`（`RuleDetailVO` 装配 @:169-180、`RuleVersionContentVO` 装配 @:193-199 两处 `new ...VO(...)` 补 `getFlowGraph()`——record 加字段改构造器，不补则编译断）
- Modify: `internal/bundle/RuleContentHasher.java`（`ruleHash` 入参加 flowGraph，canonical 加 `"flowGraph"` 键）
- Modify: `internal/bundle/RuleExportService.java`（:115/:124 导出携带 `rv.getFlowGraph()`）、`internal/bundle/RuleImportService.java`（:156/:168 导入读回 flowGraph）
- Test: `RuleContentHasherTest`（两条 nodes 不同的 flow → hash 不同）、`RuleExportServiceTest` / import 往返

- [ ] **Step 1:** 4 个 DTO 各加 `FlowGraph flowGraph` 字段（record 规范构造，位置紧邻 `script`）。
- [ ] **Step 2:** `RuleContentHasher.ruleHash` 签名加 `FlowGraph flowGraph`，`canonical.put("flowGraph", flowGraph)`；全部调用点补传（grep `ruleHash(`）。**这是幂等红线**——DECISION_FLOW 的 ast/script 均为 null，不进 hash 则不同 flow 同 hash。
- [ ] **Step 3:** Export/Import 两处照抄 script 携带/读回 flowGraph。
- [ ] **Step 4:** Hasher 区分测试 + import/export 往返测试。
- [ ] **Verify:** `$MVN -pl rule-config-svc -am test`

## P3 — config 发布期解析/冻结/校验

**Files (rule-config-svc):**
- Modify: `publish/PublishService.java`（加 `resolveFlowDraft` + `resolveAndValidate` 分支 + `validKinds` 两处 + `validateKindStructure` + `ResolvedDraft` 字段 + `buildDraftVersion`）
- Test: `publish/FlowResolveValidateTest.java`

- [ ] **Step 1:** 读 `PublishService.java` 的 `resolveScriptDraft`（:475-516）、`freezeMetricDeps`（:542-564）、`freezeDecisionBindings`（:779-799）确认冻结定式。
- [ ] **Step 2:** `resolveAndValidate` 加 flow 提前 return（仿 :423）→ `resolveFlowDraft`：结构校验（DAG 合法/Switch caseKey 一致/Output decisionCode 存在/**RuleRef 同 Scene 校验**——被引规则须与 flow 在同一 Scene，跨 Scene 拒绝，v1 治理简化、将来放开只需去此校验）。
- [ ] **Step 2b:** `editDraft`（:184）/`newVersion`（:258）/`createDraft`（:675）三入口各只读了 `content.script()`——三处都补读 `content.flowGraph()` 并透传进 `resolveAndValidate`/`ResolvedDraft`，否则草稿永远收不到 flowGraph。
- [ ] **Step 3:** RuleRef 冻结——遍历 RuleRefNode 查被引规则 ACTIVE 版本，冻 `(code,version)` + 完整 snapshot 进 flowGraph；无 ACTIVE 拒绝发布（新错误码或复用现有）。
- [ ] **Step 4:** metricDeps 并集——全图 RuleRef 引用规则 metricDeps + Switch/Transform 表达式 metric 扫入并集，写本版本 `metricDependencies`。表达式取 metric 的落点是 `rule-config-svc/.../internal/publish/MetricDependencyCollector`（**在 config-svc，非 kernel**），加 flow 分支扫 Switch/Transform expression。
- [ ] **Step 5:** `validKinds` 两处 Set（:415-418、:691-694）加 DECISION_FLOW；`validateKindStructure`（:599）加 flow 分支；`ResolvedDraft`（:375）加 `FlowGraph`；`buildDraftVersion`（:736-751）加 `setFlowGraph`。
- [ ] **Step 6:** `FlowResolveValidateTest`（仿 `ScriptResolveValidateTest`）：正常冻结 + 无 ACTIVE 被引规则拒绝 + metricDeps 并集正确 + 结构非法拒绝。
- [ ] **Verify:** `$MVN -pl rule-config-svc -am test`

## P4 — 静态分析环检测 + 死节点

**Files (rule-kernel):**
- Create: `internal/analysis/FlowCycleDetector.java` / `FlowReachabilityDetector.java`；`api/analysis/FlowCycleFinding.java` / `FlowDeadNodeFinding.java`
- Modify: `AnalyzableRule.java`（加 `flowGraph`）、`RuleSetAnalyzer.java`（挂 detector + Comparator + report 字段）、`reasonFor`（:112-127 加 kind 分支）
- Modify (rule-config-svc): `RuleAnalysisServiceImpl.java`（:59-61 拆入 flowGraph）
- Test: `analysis/FlowCycleDetectorTest`、`analysis/FlowReachabilityDetectorTest`

- [ ] **Step 1:** 读 `DeadRuleDetector.java:33-58` 确认 detector 结构。
- [ ] **Step 2:** `AnalyzableRule` 加 `FlowGraph flowGraph`；`RuleAnalysisServiceImpl` 拆入。
- [ ] **Step 3:** `FlowCycleDetector.detect`（RuleRef 引用图 DFS 找环）、`FlowReachabilityDetector.detect`（input BFS 找不可达节点），仿静态 detector；新增 2 finding record。
- [ ] **Step 3c:** 现有 `CoverageGapDetector.java:75` 的 `switch(kind)` 无 default——加 `DECISION_FLOW` 后会**静默落空**返回空决策码集（switch statement 非穷尽不报错，但覆盖分析把 flow 当"不产决策"误判）。补 `case DECISION_FLOW ->` 收集全图 `OutputNode.decisionCode`（或保守 `addBindings`）。`RedundancyDetector`（if-else 链）DECISION_FLOW 落 else 跳过，行为正确，仅更新 :26 注释 kind 列表。
- [ ] **Step 4:** `RuleSetAnalyzer.analyze()`（:65-93）挂两 detector + Comparator；`RuleSetAnalysisReport` 扩字段。
- [ ] **Step 5:** 发布期环检测前置——`resolveFlowDraft` 调环检测，成环拒绝发布。
- [ ] **Step 6:** 两 detector 测试（有环/无环、可达/死节点）。
- [ ] **Verify:** `$MVN -pl rule-config-svc -am test`

## P5 — API 契约

**Files (rule-api):**
- Modify: `RuleContentSource.java`（加 `FlowGraph flowGraph()`）、`CreateRuleRequest` / `EditDraftRequest` / `NewVersionRequest`（各加字段）、`RuleContent.java`（加字段）、`RuleController.toContent()`（透传）、`EvalAutoConfiguration.java`（注册 flowExecutor bean + 塞进 evalEngine map）
- Modify (rule-sdk / rule-sdk-spring-boot-starter): `RuleEngineClient.java`（executors 注册 FlowExecutor）、`RuleEngineClientAutoConfiguration.java`（装配 flow 所需依赖）
- Test: RuleController flow 建/发布集成测试；`RuleEngineClientTest` 嵌入式评估 flow

- [ ] **Step 1:** `RuleContentSource` 加 `flowGraph()`（仿 `script()`），3 请求 record 加 `FlowGraph flowGraph` 字段（primitive 注意 `@JsonSetter`）。
- [ ] **Step 2:** `RuleContent` 加字段；`toContent()`（:91）透传。
- [ ] **Step 3:** `EvalAutoConfiguration` 加 `@Bean flowExecutor()`（仿 scriptExecutor :178-188）+ 塞进 `evalEngine()` 的 map（:285-290）。
- [ ] **Step 3b:** SDK 嵌入式评估同源：`RuleEngineClient`（:99-101 附近）`executors.put(RuleKind.DECISION_FLOW.tag(), flowExecutor)`——**必须显式注册**，否则命中 EvalEngine 对未知 kind 回退 AST_BOOLEAN 的陷阱（flow 的 conditionAst=null），返回错误结果；`RuleEngineClientAutoConfiguration` 补 flow 所需依赖装配。
- [ ] **Step 4:** 集成测试：POST 建 DECISION_FLOW 草稿 → 发布 → 评估返回决策。
- [ ] **Verify:** `$MVN -pl rule-api -am test`

## P6 — 前端画布编辑器

**Files (frontend):**
- Modify: `package.json`（加 reactflow）、`constants/enums.ts`（`getRuleKindOptions` 加选项）、`pages/rule-editor/CenterPanel.tsx`（renderEditor 分派）、`store/ruleStore.ts`（加 `flowGraph` 状态 + setter + `loadFromDetail` 参数）、i18n（zh-CN/en `enum.kind` + `versionContent.flowGraph`）
- Modify: `types/rule.ts`（`RuleKind` 联合加 `'DECISION_FLOW'` + 请求/详情类型加 `flowGraph`）、`pages/rule-editor/index.tsx`（**仅** `loadFromDetail` 回填 flowGraph @:94-101）、`pages/rule-editor/LeftPanel.tsx`（**editDraft 写体** `handleSaveDraft` @:59-67 补 flowGraph——真正的草稿写路径在此，非 index.tsx）、`pages/rule-editor/VersionContentDrawer.tsx` / `VersionDiffDrawer.tsx`（只读展示 flowGraph）、`pages/rule-editor/analysisSummary.ts`（`ANALYZABLE_KINDS` 纳入 flow + 图内 finding 分流）、`pages/rule-editor/RightPanel.tsx`（`showBinding` 加 `DECISION_FLOW` 排除——决策由 Output 内联，不走 decisionBinding）
- Modify: `pages/rule-list/index.tsx`（handleCreate @:88-108）、`pages/rules-all/index.tsx`（handleCreate @:78-92）——两个创建弹窗的 kind 播种分支加 `DECISION_FLOW`，塞最小合法 FlowGraph 骨架
- Modify: `pages/rule-editor/ScriptEditor.tsx`（`useRuleStore` 单例 → 受控 `props: script + onChange`，与其余 4 个受控编辑器拉齐；`CenterPanel` EXPRESSION_SCRIPT 分支同步传 `script`/`onChange`）——下钻复用脚本叶子的前置，兼消除它作为唯一单例耦合编辑器的不一致
- Create: `pages/rule-editor/FlowCanvasEditor.tsx`、`pages/rule-editor/FlowNodeInspectorDrawer.tsx`（节点下钻抽屉）、`types/flow.ts`

- [ ] **Step 0:** `types/rule.ts` `RuleKind` 联合加 `'DECISION_FLOW'`（**硬伤：不加则 `kind==='DECISION_FLOW'` 比较 TS 报错、`npm run build` 直接挂**）+ 请求/详情类型平级加 `flowGraph?`。
- [ ] **Step 1:** `types/flow.ts` 对齐后端 record（判别字段 `type`）。
- [ ] **Step 2:** `enums.ts` 加 DECISION_FLOW 选项 + i18n。
- [ ] **Step 3:** `ruleStore` 加平级 `flowGraph` 状态（仿 script，不进 `ast`）。
- [ ] **Step 4:** `FlowCanvasEditor`：reactflow 画布、4 种节点拖放、连边、RuleRef 内嵌选规则下拉；`onChange` 回写 `flowGraph`。
- [ ] **Step 5:** `CenterPanel.renderEditor()` 加 `DECISION_FLOW` 分派（仿 ScriptEditor）。
- [ ] **Step 6:** 写路径补 flowGraph（**位点是 `LeftPanel.handleSaveDraft` @:59-67 的 editDraft body，不是 index.tsx**——index.tsx 只做 loadFromDetail 回填）。两个创建弹窗 `rule-list/index.tsx` handleCreate @:88-108 与 `rules-all/index.tsx` @:78-92 各加 `DECISION_FLOW` 分支播种最小合法 FlowGraph 骨架（类比 DECISION_TREE 骨架）——否则弹窗选 DECISION_FLOW 后 createRule body 无 flowGraph、被 `resolveFlowDraft` 结构校验（需 input/无孤儿）拒，根本建不出 flow。
- [ ] **Step 7:** 只读链：`index.tsx` `loadFromDetail` 回填 `detail.flowGraph`；`VersionContentDrawer`/`VersionDiffDrawer` 加 flowGraph 展示项 + i18n `versionContent.flowGraph` label。**只读用 `json(content.flowGraph)` 平铺即可，不做只读画布**（与现有 script 一致，守以简为先）。
- [ ] **Step 8:** 分析展示分两维度（不混一个面板）：**跨规则维度**——`ANALYZABLE_KINDS` 纳入 `DECISION_FLOW`（flow 的 Output 决策码参与规则集覆盖分析，进既有分析面板）；**图内维度**——`FlowCanvasEditor` 消费 `FlowCycleFinding`/`FlowDeadNodeFinding`，成环边标红、死节点置灰（reactflow 样式），**不塞进规则集冲突面板**；`analysisSummary` 按维度分流。
- [ ] **Step 9:** `ScriptEditor` 去 `useRuleStore()`，改受控 `props: script + onChange`（对齐其余 4 个受控编辑器）；`CenterPanel` EXPRESSION_SCRIPT 分支改传 `script={script} onChange={setScript}`。这是下钻复用脚本叶子的前置，也顺手消除既有单例耦合不一致（§3 被需求驱动的必要小改，非无端重构）。
- [ ] **Step 10:** 节点下钻（一个画布搞定，复用现有编辑器不重写）：`FlowNodeInspectorDrawer` —— RuleRef 双击 → 抽屉按被引规则 kind 分派内嵌**现有 5 个受控编辑器**，编辑落**被引规则自己的草稿**（`editDraft` API，非 flowGraph）；抽屉顶部提示**冻结隔离**（改叶子进其草稿、需各自发布，已发布 flow 引用冻结版本不受影响，D6）；画布内"新建本场景叶子规则"（`createDraft`）建完自动 RuleRef 引用。Switch/Transform/Output 双击 → 抽屉轻量编辑表达式/decisionCode。
- [ ] **Step 11a:** 三栏适配。**左栏** LeftPanel：展示部分（元信息/版本/DryRun）无需改，但 **editDraft 写体 `handleSaveDraft` @:59-67 需补 flowGraph**（见 Step 6）；`script.lang` 行本就是 EXPRESSION_SCRIPT 专属天然跳过。**右栏** RightPanel：executor(lang) tab 本就只 EXPRESSION_SCRIPT 显示（flow 的 lang 在各 Switch/Transform 节点/下钻里选，无规则级 lang）；preGate tab 保留；**`showBinding` 加 `DECISION_FLOW` 排除**——否则多出无用 decisionBinding tab（决策由 Output 内联，类比评分卡/决策树叶子）。
- [ ] **Step 11:** flow 评估 trace 首版**复用 `components/trace-tree` 零改造**（flow 产的 `NodeTrace` 森林直接渲染），仅为 `FlowNodeType` 四标签加图标/文案（Switch/Transform 别用 ⏭ skip 语义，用「选中 case」/「= 输出值」）。画布回放 / What-if 探针 / 覆盖率热力图为**增强项**（见 spec「Trace 可视化」分层，不做进首版）。
- [ ] **Verify:** `npm run build` + 手动 UI 建一条 flow 规则（含双击下钻编辑被引规则）；**回归 EXPRESSION_SCRIPT 编辑**（ScriptEditor 受控化后建/编/发布脚本规则不退化）+ 抽验其余 4 形态编辑器打开正常。

## P7 — 端到端功能测试

按 CLAUDE.md「功能测试纪律」：打可执行包起真实服务，别用 reactor run 目标。

- [ ] **Step 1:** 打包起服务，确认 V1_39 迁移执行、服务就绪。
- [ ] **Step 2:** 建两条叶子规则（如布尔树 A、评分卡 B）并发布。
- [ ] **Step 3:** 建 DECISION_FLOW（RuleRef A → Switch 分支 → RuleRef B → Output）并发布。
- [ ] **Step 4:** 查 `rule_version.flow_graph` 真落库 + 被引规则版本真冻进快照 + metricDependencies 真是并集（不是空/占位）。
- [ ] **Step 5:** 评估：验证图遍历输出正确 + trace 覆盖每个节点。
- [ ] **Step 6:** 环检测：造一条成环 flow，验证发布被拒。
- [ ] **Step 7:** DB 恒空字段审计：`flow_graph` 该有值的规则真有值；清理本次测试数据回干净基线。

---

## 收尾

- [ ] `$MVN clean test` 全量兜底（跨模块改实体类型）——**现有 5 形态的单测/集成测试全绿 = 未退化**，任一现有测试变红即为回归，必须查而非改测试就绿。
- [ ] **防退化回归**：e2e 抽验现有 5 形态各建/发布/评估一条不受影响（尤其 EXPRESSION_SCRIPT，ScriptEditor 改造 + RuleContentHasher 加键后）；确认现有规则 API 响应新增 `flowGraph:null` 不影响既有前端/SDK 消费。
- [ ] 更新 01-concepts（新 kind）、03-rule-expression 或新章（flow 节点语义）、05-storage（flow_graph 列）、10-api-contract（DECISION_FLOW 请求体）；改前跑 `doc-consistency-review`。
- [ ] 派 `rule-engine-reviewer` 审代码 ↔ 文档对齐。
- [ ] 本计划归档进 `plans/archive/`，设计并入 docs 正文。
