# 面向前端的评估 trace 设计（tree/table 覆盖 + 内容接线）

> 日期：2026-06-09。状态：设计待评审(明天)。源自两条线汇合——(1) 本会话发现 `DecisionTreeExecutor`/`DecisionTableExecutor` 的 `nodeTrace` 恒为 `List.of()`,tree/table 规则 dry-run/审计看不到逐节点判定;(2) DB 空字段审计(`docs/audits/2026-06-09-db-empty-fields-audit.md`)证实 `node_trace.actual_value/value_source/params` 设计了却从未接线。前端近期就要做规则解释 UI,且明确「trace 就是为定前端展示用」。**本文档只设计,未改代码。**

## 1. 背景与准绳
- 4 个 executor 里,**只有 `InterpretedExecutor`(AST_BOOLEAN)、`ScorecardExecutor`(SCORECARD)产出 NodeTrace**;tree/table 恒空。
- 叶子求值(`ConditionEvaluation` + `ComparisonStrategyFactory`)已全局共享;布尔组合(And/Or/Not/Xor)只在 Interpreted 与 tree 的 IfNode 条件两处存在。
- 用户准绳(本期一切以此为准):**前端可舒服展示各 kind + 结构干净 + 后端设计不能像打补丁,要有说得通的理由。**

## 2. 已定的架构决策（本会话敲定）
| # | 决策 | 选择 | 理由 |
|---|---|---|---|
| 保真度 | 条件子树展开多深 | **全保真(对齐 Interpreted)** | tree/table 用得多、目标是可解释;半吊子 trace 在复合条件下「知其否不知其所以否」,价值打折 |
| 模型扩展 | leaf/row 决策码进不进 NodeTrace | **不为 decisionCode 扩(结构终点节点 + finalDecision)** | NodeTrace 职责=条件溯源;决策产出归 `EvalResult.finalDecision`;`node_trace` 按 session+ruleVersionId 关系存储,非独立自解释 artifact。**注:若前端要脱离 finalDecision 独立渲染节点决策,可后续非破坏加字段** |
| 是否统一求值 | tree 抽共享 walker / 统一二值三值模型 | **不抽、不统一,tree 内联织 trace** | 开源(Drools 编译成规则单引擎、DMN 共享 FEEL)统一的前提是「单一求值+错误模型」;本仓 Interpreted=二值(错→false→续)、tree=三值(错→传播→中止,避免误命中分支)是**真异质且各有正确性理由**,硬抽 walker 会把两套语义耦进一处=反而打补丁 |

> 开源调研结论已记录:统一是「信号驱动的独立大项」,不塞进本次;本次顺纹理做。

## 3. trace 形态（全保真,复用现有 NodeTrace 约定）
- **tree**：`IfNode` → `NodeTrace("IfNode", result=条件结果, children=[条件子树... , 选中分支的 trace])`;条件子树按 And/Or/Not/Condition 既有约定逐节点建;到达 `DecisionLeafNode` → 结构终点节点 `NodeTrace("DecisionLeafNode", result=true)`;条件出错 → 该 IfNode result=false + errorCode,按三值语义中止(不走 else)。
- **table**：每**测试过的行** → `NodeTrace("DecisionTableRow", result=是否命中, children=[逐个被求值列的 ConditionNode trace])`;按 FIRST_HIT,记录到命中行为止;通配列(condValue=null)不求值故不记。
- **共性**：读 `TraceScope.COLLECT.orElse(true)`,null-sink 时零分配(与 Interpreted/Scorecard 一致);rvId 求值时内联写入(仿 Scorecard,免后置 stamp)。

## 4. trace 端到端流程
```
executor.execute()  [collect=TraceScope.COLLECT]
  ├ Interpreted ✓  ├ Scorecard ✓  ├ tree ←本期  └ table ←本期
        ↓ EvalResult.nodeTrace()(递归树)
  ├ dry-run：EvalServiceImpl 强制 collect=true → 直接回响应体(前端近期吃这条,递归树直接渲染)
  ├ 落库：DomainEvent → AuditPersister/DryRunPersister → TraceWriter → flattenToList → node_trace 行(nodePath 可重建树)
  └ 常规 trace.enabled=false → collect=false → 无 trace(省分配)
```

## 5. 范围：选项 Y（已定 2026-06-09）
tree/table「补 trace」(#5)与审计 #1「trace 内容接线」是**同一个前端目标的两半**,**已决定合并为一份设计(选项 Y),分 3 个增量执行**。审计 #2(eval_duration_ms)/#3(规范化表冗余)/#4(context_snapshot)各自独立,不并入本设计。

**选项 Y — 面向前端的完整 trace（本期范围）:**
- tree/table 产出 NodeTrace(§3 形态),与 Interpreted/Scorecard 一致读 `TraceScope.COLLECT`;
- `ConditionOutcome` 从 `(status,errorCode)` 扩成携带 `resolvedValue + valueSource`(求值器返回它本就算出的数据,**非补丁**——反面是 executor 为 trace 重新解析);
- 4 个 executor 把 `actualValue/valueSource` 写入 NodeTrace;
- NodeTrace 加 `expectedValue`(阈值/values)+ 可选 `displayLabel`,落库(entity/flatten/列迁移);
- **4 个 kind 统一填**,保证前端跨 kind 一致渲染。

**定 Y 的理由(对齐「不打补丁、要有理由」):** ① 前端近期就做且是驱动方,只做覆盖(原选项 X)会让前端立刻撞上 null actual_value、逼着马上补——正是要避免的打补丁;② 审计证明这些字段是「设计已考虑、接线缺失」,Y 是**补全既定契约**而非新增;③ 在求值源头(`ConditionOutcome`)一次接对,全 kind 一致,结构最干净。

**为何合并优于分开(已论证):** 分开的优势(覆盖更快更小上、核心变更延后)在「合并+分阶段执行」里基本都能拿到;而合并独有的「不双次改 executor、不上半成品、契约一次定全」是分开拿不到的,且正中红线。

**执行分 3 增量(降风险,详见实现计划):**
- **增量 1**：`ConditionOutcome` 扩 `resolvedValue+valueSource` + 接进现有 Interpreted/Scorecard trace → kernel 全量回归(隔离核心变更)。
- **增量 2**：tree/table 用已接好的字段建 trace。
- **增量 3**：NodeTrace 加 `expectedValue/displayLabel` + 落库(entity/flatten/Flyway 列)。

## 6. 非目标
- 统一二值/三值条件求值模型 / 抽共享 walker(信号驱动的独立项)。
- leaf/row decisionCode 内嵌 trace(前端先用 finalDecision;真要再非破坏加)。
- `node_trace` 变独立自解释 artifact。
- EXPRESSION_SCRIPT kind 的 trace(尚未实现该 executor)。

## 7. native / 风险
- 纯 kernel 逻辑 + (选 Y 时)`ConditionOutcome` 字段扩展,无新反射、无 preview API,GraalVM native 零新增风险。
- 选 Y 动 `ConditionOutcome` 是热路径核心:行为不变由 kernel 数值/策略/EvalEngine 全量测试 + 现有 Interpreted/Scorecard trace 测试守门;新增 tree/table trace 测试 + COLLECT=false 零 trace 测试。
- table trace 在大表(多行多列)下 trace 节点数 = 测试过的行×列,有界(FIRST_HIT 命中即止),非热点。

## 8. 后续
评审定下 X / Y 后 → `writing-plans` 出实现计划 → subagent 或 inline 执行。若选 Y,与审计 #2(eval_duration_ms)可顺带同批,但 #3(规范化表冗余)是独立结构决策,不混入。
