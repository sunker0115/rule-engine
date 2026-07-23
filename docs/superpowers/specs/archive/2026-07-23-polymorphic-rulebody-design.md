# 多态 RuleBody —— 三承载平铺收敛设计

> **性质**：加性重构（非新功能）。把 `RuleVersion` 顶层三个互斥可空的"承载"字段收敛成一个 sealed `RuleBody` 多态类型，全栈贯穿（DB → kernel → API → 前端，即 brainstorming 敲定的 **L2** 深度）。greenfield 无生产数据，一次改到底。

## 背景

DECISION_FLOW（D75）落地后，`RuleVersion` / `RuleVersionSnapshot` 顶层平铺了**三个按 kind 三选一互斥、另两者恒 null 的承载字段**：

- `conditionAst`（AST 系四形态 AST_BOOLEAN / SCORECARD / DECISION_TREE / DECISION_TABLE）
- `scriptSource`（EXPRESSION_SCRIPT）
- `flowGraph` + `referencedSnapshots`（DECISION_FLOW）

由此产生的债：

1. **可空字段 soup**：每个载体都可空，"恰好一个非空"只是应用层约定，无结构保证；每处都拖着"其它 kind 为 null"的注释。
2. **kind 分派散落**：6 个 executor、PublishService、静态分析各自读三个可空字段 + 按 kind 判空，没有编译期穷尽。
3. **加 kind 成本递增**：每新增一种 kind（DECISION_FLOW 就是活例）要在实体列 / 迁移 / Row / ReadMapper / SnapshotAssembler / Codec / 4 个 DTO / Hasher / Export·Import / 请求 DTO / 前端 store & types **十余个平铺位点各加一个字段**（"照抄 scriptSource 孪生"）。
4. **兼容构造器堆积**：`RuleVersionSnapshot` 已有 3 个兼容构造器（13 / 12 / 8 参），全是为承载这些平铺载体的历史形态。

收敛成多态 `RuleBody` 后：加 kind = 加一个 `RuleBody` 变体，一列 DDL、一个平铺位点都不动；executor 从"读三可空字段 + kind 判空"变成 `switch(body)` 编译期穷尽。

## 目标

- 三承载收敛成 sealed `RuleBody`，三变体一一对应三承载。
- 全栈贯穿 L2：DB 单 `body` JSON 列（drop 原四列）、kernel 模型 / config 实体 / API 契约 / 前端状态一律以 `body` 承载。
- 消除 `RuleVersionSnapshot` 的兼容构造器堆积（它们仅为承载被收敛的平铺载体而存在）。
- **加性等价**：现有六形态的求值 / 发布 / 分析 / 导入导出行为**完全不变**，纯结构收敛；现有测试全绿即未退化。

## 非目标（本轮不做）

- **不动 `kind`**：`kind` 仍是 executor 选择器与对外契约字段（AST 系四 kind 共用 `AstBody` 但各有 executor，kind 不能被 body 类型替代）。
- **不把 `decisionBindings` / metric·payload 依赖 / preGates / triggerEventTypes 并进 body**：它们是跨 kind 元数据（`decisionBindings` 被血缘按需扫、AST+script 共用；依赖是发布期冻结的派生数据、所有 kind 都有），保持顶层平铺。
- **不改求值 / 取数 / Scene 合成核心路径**：executor 内部逻辑不变，只是取载体的方式从字段变模式匹配。
- **不动 `AstNode` / `FlowNode` 各自的内部多态**：它们是 body 变体内部的载荷，原样嵌套。

## 核心模型

kernel `api/model/RuleBody.java`，仿既有 `AstNode` / `FlowNode` 的 Jackson 多态定式：

```java
@JsonTypeInfo(use = Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = AstBody.class,    name = "AstBody"),
    @JsonSubTypes.Type(value = ScriptBody.class, name = "ScriptBody"),
    @JsonSubTypes.Type(value = FlowBody.class,   name = "FlowBody"),
})
public sealed interface RuleBody permits AstBody, ScriptBody, FlowBody {}

public record AstBody(AstNode conditionAst) implements RuleBody {}            // AST 系四 kind
public record ScriptBody(ScriptSource script) implements RuleBody {}         // EXPRESSION_SCRIPT
public record FlowBody(FlowGraph flowGraph,
                       Map<String, RuleVersionSnapshot> referencedSnapshots) // DECISION_FLOW
        implements RuleBody {}
```

- 判别属性 `type`（值 == 简单类名），与内层 `AstNode.type` / `FlowNode.type` 不同嵌套层，无冲突。
- 三变体 = 三承载，`referencedSnapshots` 随 `FlowBody` 走，名副其实。
- 变体 record 若含 primitive 字段（当前无）需 `@JsonSetter(nulls=AS_EMPTY)`（memory：Jackson3 primitive 反序列化）。

**留在顶层平铺、不进 body**（跨 kind 元数据）：`decisionBindings` / `metricDependencies` / `payloadDependencies` / `preGates` / `triggerEventTypes` / `kind` / 身份字段（id/code/version/scene/tenant）。

**`kind` ↔ body 一致性不变量**：发布期校验 kind 家族与 body 类型匹配——AST 系四 kind → `AstBody`、`EXPRESSION_SCRIPT` → `ScriptBody`、`DECISION_FLOW` → `FlowBody`；不一致拒发布（新错误码 `KIND_BODY_MISMATCH` 或复用现有结构校验错误码）。落点 `PublishService.validateKindStructure`。

## 存储

`rule_version` 表：**drop 四列** `condition_ast` / `script_source` / `flow_graph` / `referenced_snapshots`，**新增单列** `body JSON NOT NULL`。

```
body  JSON  NOT NULL  COMMENT '规则判定主体多态载体 RuleBody（AstBody/ScriptBody/FlowBody 三选一），按 kind 一致；三承载收敛（原 condition_ast/script_source/flow_graph/referenced_snapshots 四列）'
```

实体 `RuleVersion`：删四个 typed 字段 → 加 `private RuleBody body;`（`@TableField(typeHandler=Jackson3TypeHandler.class)`，照 `RuleVersion` JSON 列模板）。

**迁移 `V1_40__rule_version_body_polymorphic.sql`**（当前最高 V1_39）——真数据迁移，不清表：

```sql
ALTER TABLE rule_version ADD COLUMN body JSON NULL AFTER version;
UPDATE rule_version SET body = CASE
  WHEN script_source IS NOT NULL THEN JSON_OBJECT('type','ScriptBody','script', script_source)
  WHEN flow_graph    IS NOT NULL THEN JSON_OBJECT('type','FlowBody','flowGraph', flow_graph, 'referencedSnapshots', referenced_snapshots)
  ELSE JSON_OBJECT('type','AstBody','conditionAst', condition_ast)
END;
ALTER TABLE rule_version MODIFY COLUMN body JSON NOT NULL;
ALTER TABLE rule_version DROP COLUMN condition_ast, DROP COLUMN script_source,
                         DROP COLUMN flow_graph,    DROP COLUMN referenced_snapshots;
```

（`condition_ast`/`script_source`/`flow_graph`/`referenced_snapshots` 均已是 JSON 对象，`JSON_OBJECT` 直接嵌套无需再解析。已核实全仓无任何 SQL 查进这四列，drop 不破坏任何查询——见 brainstorming 记录。）

## 读取链改造（rule-eval-svc + kernel）

- `RuleVersionRow`：删 `conditionAstJson` / `scriptSourceJson` / `flowGraphJson` / `referencedSnapshotsJson` → 加 `bodyJson`（+ 收敛兼容构造）。
- `RuleVersionReadMapper` 三条 `@Select`：`rv.condition_ast/script_source/flow_graph/referenced_snapshots AS ...` → `rv.body AS bodyJson`。
- `AstJsonCodec`：删 `deserializeAst`（作 body 载荷仍需）/`deserializeScriptSource`/`deserializeFlowGraph`/`deserializeReferencedSnapshots` 的**顶层调用** → 加 `deserializeBody(String) : RuleBody`。（`AstNode` 反序列化能力保留，供 `AstBody` 内层用。）
- `SnapshotAssembler.assemble()`：`body = codec.deserializeBody(row.bodyJson())`，填进 snapshot；kind=null 默认仍 `AST_BOOLEAN`（body 为空时如何默认——见下"边界"）。
- `RuleVersionSnapshot`：删 `conditionAst`/`script`/`flowGraph`/`referencedSnapshots` 四字段 → 加 `RuleBody body`；**删 3 个兼容构造器（13/12/8 参）**，收敛为单一规范构造 + builder（builder 的 `.conditionAst()/.script()/.flowGraph()` 改为 `.body(RuleBody)`，或保留三个便捷方法内部包成对应变体——见"命名与人机工学"）。

## 求值 executor 改造（kernel，行为不变）

6 个 executor 取载体方式从字段变模式匹配。EvalEngine 仍按 kind 分派到对应 executor（不变），executor 内部：

```java
// InterpretedExecutor / ScorecardExecutor / DecisionTreeExecutor / DecisionTableExecutor
AstNode ast = ((AstBody) snapshot.body()).conditionAst();
// ScriptExecutor
ScriptSource script = ((ScriptBody) snapshot.body()).script();
// FlowExecutor
FlowBody fb = (FlowBody) snapshot.body(); fb.flowGraph(); fb.referencedSnapshots();
```

（executor 已由 kind 选定，body 变体确定，直接 cast + 记录清晰失败信息；或用 `switch` pattern。求值逻辑本身一行不改。）

## 发布期（rule-config-svc，PublishService）

- `resolveAndValidate` 的 kind 分派：现读 `content.conditionAst()/script()/flowGraph()` → 读 `content.body()` 后按变体分派到 `resolveAstDraft`/`resolveScriptDraft`/`resolveFlowDraft`（分支逻辑不变，只是入口取值方式变）。
- `ResolvedDraft`：四个平铺载体字段 → `RuleBody body`。
- `buildDraftVersion`：`rv.setConditionAst/setScriptSource/setFlowGraph/setReferencedSnapshots` → `rv.setBody(...)`。
- `validKinds` 两处 Set 不变；`validateKindStructure` 加 **kind↔body 一致性校验**。
- freeze 逻辑（metricDeps / payloadDeps / RuleRef 冻结）读载体内容处改为从 body 变体取（FlowBody 冻结后 body 内 referencedSnapshots 回填）。

## config 写路径 + 序列化孪生（rule-config-svc）

四处 DTO 的三个平铺载体字段 → 单 `RuleBody body`：

- `RuleContent` / `RuleDetailVO` / `RuleVersionContentVO` / `RuleBundle`：删 `conditionAst`/`script`/`flowGraph`（record 改构造器）→ 加 `RuleBody body`；`ConfigServiceImpl` 两处 VO 装配点同步改 `getBody()`（record 改构造器不改则编译断）。
- `RuleContentHasher.ruleHash`：签名的三载体入参 → `RuleBody body`；canonical map 从三个键 → 单 `"body"` 键（**幂等语义等价**：body 已多态判别，两条不同规则 body 不同则 hash 不同，比原三键更直接）。
- `RuleExportService` / `RuleImportService`：携带 / 读回从三字段 → `body`。

## API 契约（rule-api）

走现有 `/admin/v1/rules` 同入口：

- `RuleContentSource` 接口：`conditionAst()/script()/flowGraph()` → `body() : RuleBody`。
- 三写请求 record（`CreateRuleRequest` / `EditDraftRequest` / `NewVersionRequest`）：三载体字段 → `RuleBody body`。
- `RuleContent` record 同上；`RuleController.toContent()` 透传 `body`。
- 详情 / 版本 / diff / export VO 已在上节收敛。
- **契约破坏**：请求 / 响应 JSON 从平铺 `conditionAst`/`script`/`flowGraph` 变为单 `body`（含 `type` 判别）。greenfield 无外部消费者，可接受（memory：不写向后兼容）。
- kind 仍是请求体字符串字段，不变。

## SDK（rule-sdk / -spring-boot-starter）

- `RuleEngineClient`：executors 按 kind 注册**不变**（仍 `Map<kind, executor>`）；本地建规则从三个 setter → `.body(RuleBody)`。
- 若 `RuleEngineClient` / 注解模式有构造 snapshot 处，改走 body。

## 前端（React）

`ast` / `script` / `flowGraph` 三个平铺 store 字段 → 单 `body`（判别联合，按后端 `type`）：

- `types/rule.ts`：请求 / 详情类型的三载体字段 → `body: RuleBody`（判别联合 `{ type:'AstBody', conditionAst } | { type:'ScriptBody', script } | { type:'FlowBody', flowGraph, referencedSnapshots }`）；`types/ast.ts` / `types/flow.ts` 作为 body 内层载荷保留。
- `store/ruleStore.ts`：三个平铺状态 → 单 `body` + setter；`loadFromDetail` 回填 `detail.body`。
- `CenterPanel.renderEditor()`：现按 kind 分派各编辑器——改为读 `body.type` 取对应载荷喂给既有编辑器（AST 树 / 评分卡 / 决策树 / 决策表 / ScriptEditor / FlowCanvasEditor 六个编辑器**内部不改**，只改外层取值来源）。
- 创建规则弹窗 `rule-list` / `rules-all` 的 `handleCreate`：按 kind 播种默认体 → 播种对应 body 变体骨架。
- 只读链 `VersionContentDrawer` / `VersionDiffDrawer`：从三字段展示 → 按 `body.type` 展示对应载荷。
- 提交时把 `body`（含 `type`）塞进写请求体 `body` 字段。

编辑器分派从"kind 三处判空取字段"变"`body.type` 单一判别"，比现在清爽——非纯 churn。

## 静态分析（kernel + config-svc）

- `AnalyzableRule`：`conditionAst` + `flowGraph` 两字段 → `RuleBody body`（detector 内按变体取）。
- `RuleAnalysisServiceImpl` 拆入处改传 body。
- `CoverageGapDetector` / `FlowCycleDetector` / `FlowReachabilityDetector` / 各 AST detector：取载体处改从 body 变体取，检测逻辑不变。

## 边界纪律

- `RuleBody` 及三变体全放 kernel `api/model`，**禁引 Spring**（`KernelArchTest`）。`FlowBody` 引 `RuleVersionSnapshot`（已在 kernel，无跨模块问题）。
- body 为空 / null 的默认：`SnapshotAssembler` 遇 body 缺失时的降级（历史上 kind=null 默认 AST_BOOLEAN + conditionAst=null）——迁移后 body NOT NULL，理论不出现；保守保留一条降级日志。
- 收敛遵循 CLAUDE.md「数据类型与边界规范」：body 及变体全 typed record，无裸 Map/String；`decisionBindings` 等封闭元数据不动。

## 改动文件清单（概览，细见实现计划）

| 模块 | 改动 |
|---|---|
| rule-kernel | 新增 `api/model/RuleBody`+`AstBody`/`ScriptBody`/`FlowBody`；`RuleVersionSnapshot` 四字段→`body`、删兼容构造、builder 调整；6 executor 取载体改模式匹配；`AstJsonCodec` 顶层解析改 `deserializeBody`；`SnapshotAssembler` 装配 body；`AnalyzableRule`+各 detector 取值改 body |
| rule-config-svc | `RuleVersion` 实体四列→`body`；迁移 V1_40；`PublishService`（resolveAndValidate 分派 / ResolvedDraft / buildDraftVersion / validateKindStructure 加一致性校验 / freeze 取值）；`RuleContent`/`RuleDetailVO`/`RuleVersionContentVO`/`RuleBundle`→`body`；`ConfigServiceImpl` 两处 VO 装配；`RuleContentHasher` 单 body 键；`RuleExportService`/`RuleImportService`；`RuleAnalysisServiceImpl` |
| rule-eval-svc | `RuleVersionRow`（bodyJson）；`RuleVersionReadMapper` 三 SQL 选 `rv.body`；`SnapshotAssembler`/`AstJsonCodec`（在 kernel，此处随读取链） |
| rule-api | `RuleContentSource.body()`；3 请求 DTO→`body`；`RuleController.toContent` 透传；（EvalAutoConfiguration executors 注册不变） |
| rule-sdk / -spring-boot-starter | `RuleEngineClient` 本地建规则走 body |
| frontend | `types/rule.ts` body 判别联合；`ruleStore` body 状态；`CenterPanel` 按 body.type 分派；两创建弹窗播种 body 骨架；`VersionContentDrawer`/`VersionDiffDrawer` 只读按 body.type |
| docs/examples | **live** 剧本的规则请求体 JSON（`conditionAst`/`script`/`flowGraph` → `body`）+ 预期结果；`archive/` 下历史样例冻结不动（不再跑） |

## spec delta（docs 正文登记点）

- `00-decisions.md`：追一条 **D76 三承载平铺收敛为多态 RuleBody**（append-only，记 L2 决策 + kind↔body 不变量 + 迁移 V1_40）。
- `01-concepts.md`：§3.4 `ast`:212「三承载互斥」表述 → 改为「判定主体由多态 `RuleBody`（AstBody/ScriptBody/FlowBody 三变体）承载，与 kind 一致」；§一名词表:27 / kind:210 相应措辞。
- `05-storage.md`：§二表清单 rule_version 行、§三 rule_version DDL（四列 → 单 `body` 列）、相关 COMMENT。
- `10-api-contract.md`（落点穷尽，doc-consistency-review 复扫补齐）：
  - §4.1 创建请求体字段表（`kind`:234 保留；`conditionAst`:235 / `script`:236 / `flowGraph`:237 三行 → 单 `body` 字段行，含 `type` 判别 + 按 kind 一致说明）+ 请求 JSON 示例:218-219；
  - §4.2 editDraft「字段同 §4.1」清单:280 措辞（`conditionAst` → `body`）；§4.x newVersion 同；
  - `RuleDetailVO` 响应:519 与 `RuleVersionContentVO` 的 `conditionAst` → `body`；
  - 导出/导入 Bundle「所有 JSON 列……无损搬运」:467-480 的列举（`conditionAst` → `body`）；
  - 评估 / builder JSON 示例:795-822（`conditionAst` → `body`）；
  - `UNRESOLVED_VARIABLE`:675 措辞（"conditionAst / pre_gates / payload 引用" → "body / pre_gates / payload 引用"）；
  - 错误码清单加 `KIND_BODY_MISMATCH`（若新增）。
- `README.md`：D12 行 / 核心决策表按需补 D76。
- `docs/examples/`：live 剧本规则请求体 JSON + 预期响应从平铺三字段改 `body`（archive 冻结不动）。

## 评审记录（自审，GATE 1 —— 用户授权代审）

需求已锁定（L2 / RuleBody 命名 / decisionBindings 留平铺 / kind 保留 + 一致性校验），以评审者身份对抗性自审，结论 **通过**。查实并处理：

- **F1（已补）**：`docs/examples/**/rules/*.json` live 剧本用平铺请求字段，API 改 body 后须同步——已并入改动清单 + spec-delta。
- **F3（核实非阻塞）**：`contentHash` 不落库（`RuleExportService` 导出算 / `RuleImportService` 导入重算比对），Hasher 改单 body 键**无需 DB 迁移**；仅"改造前 bundle 改造后重导入失幂等"，greenfield 无关。
- **F2（计划细节）**：`SnapshotAssembler` 遇 body 缺失降级——迁移后 body NOT NULL 理论不现，保守默认空 `AstBody` + 降级日志。
- **F4（计划细节）**：`RuleVersionSnapshot.Builder` 保留 `.conditionAst()/.script()/.flowGraph()` 便捷方法（内部包成对应变体，非复活 soup）还是仅 `.body()`——writing-plans 定，倾向保留便捷方法（本地 SDK 人机工学）。
- 无过度设计：三变体 = 三承载，无投机变体；`decisionBindings`/依赖正确留平铺。

## 命名（待 GATE 确认）

类型名 `RuleBody`，变体 `AstBody` / `ScriptBody` / `FlowBody`。理由：描述"判定主体/正文载体"，能同时罩 AST 树 / 脚本 / 编排图三种异质载荷（`RuleLogic` 对编排图勉强，`DecisionLogic` 与 Decision 概念污染）；后缀风格与既有 `AstNode`/`FlowNode`/`FlowGraph` 一致；全仓无冲突（`RuleContent`/`RuleDefinition` 已占用，`RuleBody` 未占用）。如 GATE 阶段另择名，全文替换。

## 测试策略（实现+测试同 commit）

- **加性等价基线**：现有六形态的 executor 测试、SnapshotAssembler 往返测试、PublishService resolve/freeze 测试、Hasher 区分测试、Export/Import 往返测试、RuleController 集成测试——**全部改为构造 body 而非平铺字段后仍绿**，即证明行为未退化。改实现的同一 commit 同步改这些测试的构造方式。
- **新增**：`RuleBody` Jackson 多态往返测试（三变体判别正确、嵌套 AstNode/FlowNode 正确）；kind↔body 一致性校验测试（mismatch 拒发布）；迁移 V1_40 的 e2e 验证（老三形态数据迁移后 body 正确、评估行为不变）。
- 跨模块改实体类型，本轮收口用 `$MVN clean test` 全量兜底（memory：ENUM→VARCHAR 教训）。

## 复杂度判定

跨 6 模块 + 持久化格式变更（DB 列结构）+ 新抽象（RuleBody sealed）+ spec delta ≥3 Requirement + API 契约破坏 → **opsx 复杂路线**，进 `writing-plans` 出分阶段实现计划（GATE 2 后 TDD 逐 task 红绿）。
