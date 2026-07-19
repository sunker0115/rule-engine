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

- [ ] **Step 1:** 读 `DecisionTreeExecutor.java:42-78`（分支跳转范本）+ `InterpretedExecutor.java:89-106`（sealed switch + 短路 + trace sink 零分配）确认遍历/trace 契约后再写。
- [ ] **Step 2:** `FlowExecutor.execute(snapshot, ctx)`：从 `snapshot.flowGraph()` 的 `inputNodeId` 顺边遍历。RuleRef 节点持有冻结进快照的被引 snapshot + 按 kind 分派的 `Map<String,RuleVersionExecutor>`（方案 b，零额外查询），调 `leafExecutor.execute(refSnap, ctx)`。Switch 求值选出边，Transform 写 ctx，Output 收决策。
- [ ] **Step 3:** trace 收集沿用 `TraceScope.COLLECT.orElse(true)`，sink null 时不分配。
- [ ] **Step 4:** `FlowExecutorTest`：单 RuleRef 直连、Switch 两分支各命中、Transform→Switch 读中间值、多 Output 合成、trace 收集态 vs 零分配态。
- [ ] **Verify:** `$MVN -pl rule-kernel -am test`

## P2 — 存储读取链

**Files:**
- Modify (rule-config-svc): `RuleVersion.java`（加 `flowGraph` typed 列）；迁移 `V1_39__rule_version_flow_graph.sql`
- Modify (rule-eval-svc): `RuleVersionRow.java`（加 `flowGraphJson`）、`RuleVersionReadMapper.java`（三条 `@Select` 加 `rv.flow_graph AS flowGraphJson`）、`SnapshotAssembler.java`、`AstJsonCodec.java`（加 `deserializeFlowGraph`）
- Modify (rule-kernel): `RuleVersionSnapshot.java`（加 `FlowGraph flowGraph` + builder）
- Test: `SnapshotAssemblerFlowTest`、`RuleVersionRowTest`

- [ ] **Step 1:** grep `scriptSource` / `ScriptSource` / `deserializeScriptSource` 定位全部孪生位点，照抄一遍换成 flow。
- [ ] **Step 2:** `RuleVersion.flowGraph` 加 `@TableField(typeHandler=Jackson3TypeHandler.class)`；迁移纯 ADD COLUMN JSON NULL（无需 COLLATE）。
- [ ] **Step 3:** `RuleVersionRow` 加字段 + 兼容构造；`RuleVersionReadMapper` 三条 SQL 各加列；`SnapshotAssembler.assemble()` 加反序列化；`AstJsonCodec.deserializeFlowGraph`；`RuleVersionSnapshot` 加字段 + builder。
- [ ] **Step 4:** `SnapshotAssemblerFlowTest`——DB row（flowGraphJson）→ snapshot.flowGraph 往返。
- [ ] **Verify:** `$MVN -pl rule-eval-svc -am test`（跨模块，带 `-am`）

## P3 — config 发布期解析/冻结/校验

**Files (rule-config-svc):**
- Modify: `publish/PublishService.java`（加 `resolveFlowDraft` + `resolveAndValidate` 分支 + `validKinds` 两处 + `validateKindStructure` + `ResolvedDraft` 字段 + `buildDraftVersion`）
- Test: `publish/FlowResolveValidateTest.java`

- [ ] **Step 1:** 读 `PublishService.java` 的 `resolveScriptDraft`（:475-516）、`freezeMetricDeps`（:542-564）、`freezeDecisionBindings`（:779-799）确认冻结定式。
- [ ] **Step 2:** `resolveAndValidate` 加 flow 提前 return（仿 :423）→ `resolveFlowDraft`：结构校验（DAG 合法/Switch caseKey 一致/Output decisionCode 存在）。
- [ ] **Step 3:** RuleRef 冻结——遍历 RuleRefNode 查被引规则 ACTIVE 版本，冻 `(code,version)` + 完整 snapshot 进 flowGraph；无 ACTIVE 拒绝发布（新错误码或复用现有）。
- [ ] **Step 4:** metricDeps 并集——全图 RuleRef 引用规则 metricDeps + Switch/Transform 表达式 metric 扫入并集，写本版本 `metricDependencies`。
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
- [ ] **Step 4:** `RuleSetAnalyzer.analyze()`（:65-93）挂两 detector + Comparator；`RuleSetAnalysisReport` 扩字段。
- [ ] **Step 5:** 发布期环检测前置——`resolveFlowDraft` 调环检测，成环拒绝发布。
- [ ] **Step 6:** 两 detector 测试（有环/无环、可达/死节点）。
- [ ] **Verify:** `$MVN -pl rule-config-svc -am test`

## P5 — API 契约

**Files (rule-api):**
- Modify: `RuleContentSource.java`（加 `FlowGraph flowGraph()`）、`CreateRuleRequest` / `EditDraftRequest` / `NewVersionRequest`（各加字段）、`RuleContent.java`（加字段）、`RuleController.toContent()`（透传）、`EvalAutoConfiguration.java`（注册 flowExecutor bean + 塞进 evalEngine map）
- Test: RuleController flow 建/发布集成测试

- [ ] **Step 1:** `RuleContentSource` 加 `flowGraph()`（仿 `script()`），3 请求 record 加 `FlowGraph flowGraph` 字段（primitive 注意 `@JsonSetter`）。
- [ ] **Step 2:** `RuleContent` 加字段；`toContent()`（:91）透传。
- [ ] **Step 3:** `EvalAutoConfiguration` 加 `@Bean flowExecutor()`（仿 scriptExecutor :178-188）+ 塞进 `evalEngine()` 的 map（:285-290）。
- [ ] **Step 4:** 集成测试：POST 建 DECISION_FLOW 草稿 → 发布 → 评估返回决策。
- [ ] **Verify:** `$MVN -pl rule-api -am test`

## P6 — 前端画布编辑器

**Files (frontend):**
- Modify: `package.json`（加 reactflow）、`constants/enums.ts`（`getRuleKindOptions` 加选项）、`pages/rule-editor/CenterPanel.tsx`（renderEditor 分派）、`store/ruleStore.ts`（加 `flowGraph` 状态）、i18n（zh-CN/en `enum.kind`）
- Create: `pages/rule-editor/FlowCanvasEditor.tsx`、`types/flow.ts`

- [ ] **Step 1:** `types/flow.ts` 对齐后端 record（判别字段 `type`）。
- [ ] **Step 2:** `enums.ts` 加 DECISION_FLOW 选项 + i18n。
- [ ] **Step 3:** `ruleStore` 加平级 `flowGraph` 状态（仿 script，不进 `ast`）。
- [ ] **Step 4:** `FlowCanvasEditor`：reactflow 画布、4 种节点拖放、连边、RuleRef 内嵌选规则下拉；`onChange` 回写 `flowGraph`。
- [ ] **Step 5:** `CenterPanel.renderEditor()` 加 `DECISION_FLOW` 分派（仿 ScriptEditor）。
- [ ] **Step 6:** 提交把 flowGraph 塞进写请求体 `flowGraph`。
- [ ] **Verify:** `npm run build` + 手动 UI 建一条 flow 规则。

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

- [ ] `$MVN clean test` 全量兜底（跨模块改实体类型）。
- [ ] 更新 01-concepts（新 kind）、03-rule-expression 或新章（flow 节点语义）、05-storage（flow_graph 列）、10-api-contract（DECISION_FLOW 请求体）；改前跑 `doc-consistency-review`。
- [ ] 派 `rule-engine-reviewer` 审代码 ↔ 文档对齐。
- [ ] 本计划归档进 `plans/archive/`，设计并入 docs 正文。
