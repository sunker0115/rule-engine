# 多态 RuleBody 三承载收敛 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** 把 `RuleVersion` / `RuleVersionSnapshot` 顶层三个互斥可空承载字段（`conditionAst` / `scriptSource` / `flowGraph`+`referencedSnapshots`）收敛成一个 sealed `RuleBody` 多态类型，全栈贯穿（DB 单 `body` 列 → kernel → API → 前端）。

**Architecture:** `RuleBody` sealed interface + 三 record 变体 `AstBody(conditionAst)` / `ScriptBody(script)` / `FlowBody(flowGraph, referencedSnapshots)`，Jackson 多态判别 `type`（仿 `AstNode`/`FlowNode`）。三变体 = 三承载。`decisionBindings` / metric·payload 依赖 / preGates / triggerEventTypes / `kind` 留顶层平铺。`kind` 保留作 executor 选择器，发布期加 kind↔body 一致性校验。**加性等价重构**：六形态求值/发布/分析行为不变，只改载体的承载与取用方式；现有测试改构造方式后全绿 = 未退化。

**Tech Stack:** Java 25、Spring Boot 4.1、Spring Modulith、MyBatis-Plus、Jackson3（多态注解在 `com.fasterxml.jackson.annotation`）、Flyway、JUnit5 + AssertJ + ArchUnit + Testcontainers、React（前端）。前置：`mvn-env` skill 设 `$MVN`（JDK25）。

**设计依据：** `docs/superpowers/specs/2026-07-23-polymorphic-rulebody-design.md`（决策 D76）。GATE 1 自审已通过（用户授权代审）。

**关键决定（自设计 F2/F4 落定）：**
- **F4 builder**：`RuleVersionSnapshot.Builder` 保留 `.conditionAst()/.script()/.flowGraph()/.addReferencedSnapshot()` 便捷 setter（本地 SDK/测试人机工学），另加 `.body(RuleBody)`；`build()` 组装 body：显式 `.body(x)` 优先，否则 `flowGraph!=null → FlowBody`、`script!=null → ScriptBody`、else `AstBody(conditionAst)`。record 本身**只暴露 `body()`**，不留三个平铺 accessor（L2 收敛真身）。
- **F2 assembler 降级**：`SnapshotAssembler` 遇 bodyJson null/blank（迁移后 body NOT NULL，理论不现）默认 `new AstBody(null)` + 降级 log，不抛。

---

## 阶段总览

| 阶段 | 模块 | 内容 | 可独立验证 |
|---|---|---|---|
| P0 | kernel | `RuleBody`+3 变体 + Jackson 多态往返测试 | `RuleBodySerdeTest` |
| P1 | kernel | `RuleVersionSnapshot` 收敛（4 字段→body、删 3 兼容构造、builder 改造）+ 7 executor + 4 analysis 读者 + `AstJsonCodec.deserializeBody` + `SnapshotAssembler` + 全 kernel 测试改构造 | `$MVN -pl rule-kernel -am test` 全绿 |
| P2 | config-svc | `RuleVersion` 实体四列→`body` + 迁移 V1_40 + `ResolvedDraft`→body + `PublishService`（resolve 分派/set/clone/freeze + kind↔body 校验） | `FlowResolveValidateTest`/`ScriptResolveValidateTest` 等改构造后绿 |
| P3 | config-svc | DTO 孪生（`RuleContent`/`RuleDetailVO`/`RuleVersionContentVO`/`RuleBundle`）+ `ConfigServiceImpl` VO 装配 + `RuleContentHasher` 单 body 键 + Export/Import + `RuleAnalysisServiceImpl` | `RuleContentHasherTest`/Export·Import 往返改构造后绿 |
| P4 | eval-svc | `RuleVersionRow`(bodyJson) + `RuleVersionReadMapper` 三 SQL `rv.body` + 集成测试改列 | `$MVN -pl rule-eval-svc -am test` |
| P5 | rule-api | `RuleContentSource.body()` + 3 请求 DTO + `RuleController.toContent` | RuleController 集成测试改构造后绿 |
| P6 | rule-sdk | `RuleEngineClient` 本地建规则走 body | `RuleEngineClientTest` 改构造后绿 |
| P7 | frontend | `types/rule.ts` body 判别联合 + `ruleStore` + `CenterPanel` 分派 + 创建弹窗 + 只读抽屉 | `npm run build` + 手动六形态建/编/发布 |
| P8 | docs | 01-concepts/05-storage/10-api-contract/00-decisions(D76)/README + live examples；`doc-consistency-review` | 文档自洽 0 修改收敛 |
| P9 | e2e | 真起服务：六形态各建/发布/评估、查 body 真落库、迁移正确性、清理；`$MVN clean test` 全量兜底；归档设计+计划 | e2e 剧本绿 |

> 每阶段提交前 `$MVN -pl <module> -am test` 全绿；跨模块改实体类型，P9 收口 `$MVN clean test` 全量兜底（memory：ENUM→VARCHAR / 跨模块 NoSuchMethodError 教训）。**实现与测试同 commit**（用户硬要求 + 项目测试纪律）。

---

## P0 — kernel RuleBody 模型

**Files (rule-kernel):**
- Create: `api/model/RuleBody.java`、`api/model/AstBody.java`、`api/model/ScriptBody.java`、`api/model/FlowBody.java`
- Test: `api/model/RuleBodySerdeTest.java`

- [x] **Step 1: 写 RuleBodySerdeTest（红）**

`rule-kernel/src/test/java/com/sstlfsj/rule/kernel/api/model/RuleBodySerdeTest.java`：用真实 `JsonMapper` 对三变体各做序列化→反序列化往返，断言判别 `type` 正确、内层 `AstNode`/`FlowGraph` 恢复正确、`instanceof` 类型对。

```java
class RuleBodySerdeTest {
    private final ObjectMapper om = JsonMapper.builder().build();

    @Test void astBody_roundTrips() throws Exception {
        RuleBody b = new AstBody(new ConditionNode("GT", "score", Map.of("threshold", 80), null, null));
        String json = om.writeValueAsString(b);
        assertThat(json).contains("\"type\":\"AstBody\"");
        RuleBody back = om.readValue(json, RuleBody.class);
        assertThat(back).isInstanceOf(AstBody.class);
        assertThat(((AstBody) back).conditionAst()).isInstanceOf(ConditionNode.class);
    }

    @Test void scriptBody_roundTrips() throws Exception {
        RuleBody b = new ScriptBody(new ScriptSource("score > 80", "CEL"));
        RuleBody back = om.readValue(om.writeValueAsString(b), RuleBody.class);
        assertThat(back).isInstanceOf(ScriptBody.class);
        assertThat(((ScriptBody) back).script().lang()).isEqualTo("CEL");
    }

    @Test void flowBody_roundTrips() throws Exception {
        FlowGraph g = new FlowGraph(List.of(), List.of(), "in");
        RuleBody b = new FlowBody(g, Map.of());
        RuleBody back = om.readValue(om.writeValueAsString(b), RuleBody.class);
        assertThat(back).isInstanceOf(FlowBody.class);
        assertThat(((FlowBody) back).flowGraph().inputNodeId()).isEqualTo("in");
    }
}
```
（`ConditionNode`/`ScriptSource`/`FlowGraph` 构造参数以各自 record 当前签名为准，写前 Read 确认。）

- [x] **Step 2: 运行确认 FAIL**（RuleBody 未定义，编译失败）
- [x] **Step 3: 建四个类型**

`RuleBody.java`（`api/model`，Javadoc 说明"判定主体多态载体"）：

```java
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = AstBody.class,    name = "AstBody"),
    @JsonSubTypes.Type(value = ScriptBody.class, name = "ScriptBody"),
    @JsonSubTypes.Type(value = FlowBody.class,   name = "FlowBody"),
})
public sealed interface RuleBody permits AstBody, ScriptBody, FlowBody {}
```

`AstBody.java`：`public record AstBody(AstNode conditionAst) implements RuleBody {}`（类级 Javadoc：AST 系四 kind 载体，conditionAst 可空=空 AST）。
`ScriptBody.java`：`public record ScriptBody(ScriptSource script) implements RuleBody {}`。
`FlowBody.java`：`public record FlowBody(FlowGraph flowGraph, Map<String, RuleVersionSnapshot> referencedSnapshots) implements RuleBody { public FlowBody { referencedSnapshots = referencedSnapshots == null ? Map.of() : Map.copyOf(referencedSnapshots); } }`。

判别属性 `type` 与内层 `AstNode.type`/`FlowNode.type` 不同层，无冲突。注解 import 走 `com.fasterxml.jackson.annotation`（memory：Jackson3 注解包）。

- [x] **Step 4: 运行确认 PASS**：`$MVN -pl rule-kernel -am test -Dtest=RuleBodySerdeTest`
- [x] **Step 5: Commit**：`feat(kernel): RuleBody 多态载体（AstBody/ScriptBody/FlowBody）`

## P1 — kernel RuleVersionSnapshot 收敛 + 全读者改造

> 删 Snapshot 的四个载体字段会同时断掉所有 kernel 读者，必须同阶段一起改到编译通过；本阶段用"现有测试改构造后仍绿"作为等价性证明（TDD 的重构变体）。

**Files (rule-kernel):**
- Modify: `api/model/RuleVersionSnapshot.java`（四字段→`RuleBody body`；删 13/12/8 参兼容构造；Builder 改造见"关键决定 F4"）
- Modify readers: `internal/evaluator/InterpretedExecutor.java:56`、`ScorecardExecutor.java:37`、`DecisionTreeExecutor.java:30`、`DecisionTableExecutor.java:33`、`ScriptExecutor.java:41,78`、`FlowExecutor.java:54,109`、`CompiledExecutor.java:68`
- Modify analysis: `internal/analysis/FlowReachabilityDetector.java:46,107`、`FlowCycleDetector.java:48,124`、`CoverageGapDetector.java:100`、`api/analysis/AnalyzableRule.java`（`conditionAst`+`flowGraph` 两字段→`RuleBody body`）
- Modify codec: `internal/codec/AstJsonCodec.java`（加 `deserializeBody(String):RuleBody`；顶层 `deserializeScriptSource`/`deserializeFlowGraph`/`deserializeReferencedSnapshots` 不再被 Assembler 调，可删；`deserializeAst` 保留供 body 内层/别处）、`internal/codec/SnapshotAssembler.java:43-79`
- Test: 更新 `SnapshotAssemblerTest`、`InterpretedExecutorTest`、`ScorecardExecutorTest`、`DecisionTreeExecutorTest`、`DecisionTableExecutorTest`、`ScriptExecutorTest`、`FlowExecutorTest`、`CompiledExecutorTest`、`FlowCycleDetectorTest`、`FlowReachabilityDetectorTest`、`CoverageGapDetectorTest`、`EvalResultTest` 等所有构造 Snapshot/AnalyzableRule 处

- [x] **Step 1:** `RuleVersionSnapshot` record：删 `conditionAst`/`script`/`flowGraph`/`referencedSnapshots` 四 component → 加 `RuleBody body`（紧邻 kind 后）；compact 构造删对应 copyOf；**删三个兼容构造器**（13/12/8 参）。Builder：保留 `conditionAst`/`script`/`flowGraph`/`addReferencedSnapshot` staging setter，加 `body(RuleBody)`，`build()` 按 F4 规则组装 body（显式 body 优先，否则 flowGraph→FlowBody(flowGraph, referencedSnapshots)、script→ScriptBody、else AstBody(conditionAst)）。
- [x] **Step 2:** executor 读者改模式匹配（executor 已 kind 选定，cast 安全）：
  - `InterpretedExecutor:56` `eval(snapshot.conditionAst(),...)` → `eval(((AstBody) snapshot.body()).conditionAst(),...)`
  - `ScorecardExecutor:37` `snapshot.conditionAst() instanceof ScorecardRootNode root` → `snapshot.body() instanceof AstBody(ScorecardRootNode root)`（record pattern）
  - `DecisionTreeExecutor:30` 同理 `snapshot.body() instanceof AstBody(IfNode root)`
  - `DecisionTableExecutor:33` `snapshot.body() instanceof AstBody(DecisionTableNode(...))`（保持既有嵌套解构，外层套 `AstBody(...)`）
  - `ScriptExecutor:41,78` `snapshot.script()` → `((ScriptBody) snapshot.body()).script()`；空校验从 `script==null` 改 `!(body instanceof ScriptBody sb) || sb.script()==null` → 保留 `EvalErrorCode.SCRIPT_NULL`（:38）
  - `FlowExecutor:54` `snapshot.flowGraph()` → `((FlowBody) snapshot.body()).flowGraph()`；`:109` `snapshot.referencedSnapshots()` → 同一 `FlowBody` 的 `.referencedSnapshots()`（先取 `FlowBody fb = (FlowBody) snapshot.body();` 复用）；FLOW_NULL（:44）校验同步
  - `CompiledExecutor:68` `compiler.compile(snapshot.conditionAst())` → `((AstBody) snapshot.body()).conditionAst()`
- [x] **Step 3:** `AnalyzableRule` 两字段→`RuleBody body`；analysis 读者：`FlowReachabilityDetector:46/107` `rule.flowGraph()` → `rule.body() instanceof FlowBody fb ? fb.flowGraph() : null`（:107 判定 `rule.body() instanceof FlowBody`）；`FlowCycleDetector:48/124` 同理；`CoverageGapDetector:100` case DECISION_FLOW 从 body 取 flowGraph；AST detector 若读 conditionAst 同法。
- [x] **Step 4:** `AstJsonCodec.deserializeBody(String json)`：`json` null/blank → 返回 `null`（Assembler 兜底）；否则 `mapper.readValue(json, RuleBody.class)`。`SnapshotAssembler.assemble`（:43-79）：删四个载体反序列化 → `RuleBody body = codec.deserializeBody(row.bodyJson()); if (body == null) { log.warn(...); body = new AstBody(null); }`；构造 Snapshot 传 `body`（删传四载体的参数，改走 builder 或新规范构造）。
- [x] **Step 5:** 逐一更新上述所有测试的构造：`.conditionAst(x)`/`.script(x)`/`.flowGraph(x)` builder 调用**保持可用**（F4 便捷 setter 未删），故多数 builder 构造的测试**无需改**；仅"直接调兼容构造器 new RuleVersionSnapshot(... 13/12/8 参 ...)"的测试改走 builder 或新规范构造；`SnapshotAssemblerTest` 的 `RuleVersionRow` 从四载体 json 改单 bodyJson（依赖 P4 的 Row 结构——此处 P1 先按新 Row 签名写，P4 落 Row）。**注：Row 结构变更跨 kernel/eval，P1 与 P4 的 Row/Assembler 需一起编译**；实操：P1 内先把 `RuleVersionRow`（在 eval-svc）与 Assembler 一起改（带 `-am`），P4 只剩 Mapper SQL。
- [x] **Step 6: Verify**：`$MVN -pl rule-eval-svc -am test`（含 kernel + eval，一次覆盖 Snapshot/Assembler/Row/executor）全绿。
- [x] **Step 7: Commit**：`refactor(kernel): RuleVersionSnapshot 收敛为多态 body + 读者改造`

## P2 — config-svc 实体 + 迁移 + 发布期

**Files (rule-config-svc):**
- Modify: `internal/domain/RuleVersion.java`（四 typed 列→`RuleBody body`）
- Create: `src/main/resources/db/migration/V1_40__rule_version_body_polymorphic.sql`
- Modify: `internal/publish/PublishService.java`（`ResolvedDraft` 及 :164-167/:191-196/:219-228/:269-274/:303-308/:655-658/:873-878/:943-952 载体读写 → body；`validateKindStructure` 加 kind↔body 校验）
- Test: `publish/*ResolveValidateTest`、`PublishServiceTest` 等

- [x] **Step 1:** `RuleVersion` 实体：删 `conditionAst`/`scriptSource`/`flowGraph`/`referencedSnapshots` 四 `@TableField` → 加 `@TableField(typeHandler=Jackson3TypeHandler.class) private RuleBody body;`（Lombok `@Getter/@Setter` 自动出 `getBody/setBody`）。
- [x] **Step 2:** 迁移 `V1_40`（当前最高 V1_39）：

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

- [x] **Step 3:** `ResolvedDraft`（PublishService 内，:375 附近）：删四载体字段 → 加 `RuleBody body`。`resolveAndValidate`（:191-196、:873-878）：读 `content.body()` 后 `switch`/instanceof 分派到 `resolveAstDraft`/`resolveScriptDraft`/`resolveFlowDraft`（各分支内部逻辑不变，冻结产物包成对应 body 变体存进 ResolvedDraft.body；FlowBody 冻结后 referencedSnapshots 回填其中）。
- [x] **Step 4:** 写点（:219-228、:943-952、:164-167、:655-658）：`draft.setConditionAst/setScriptSource/setFlowGraph/setReferencedSnapshots(...)` → `draft.setBody(resolved.body())`；构造 Snapshot 处（:164-167 for validation snapshot、:655-658 clone/active snapshot）改走 builder `.body(...)` 或新规范构造。clone（:303-308 `newVersion` 读 from.getConditionAst/getScriptSource/getFlowGraph）→ `from.getBody()`。
- [x] **Step 5:** `validateKindStructure`（:599 附近）加 **kind↔body 一致性**：AST 系四 kind 要求 `body instanceof AstBody`、EXPRESSION_SCRIPT 要求 `ScriptBody`、DECISION_FLOW 要求 `FlowBody`，不符抛发布校验异常（新 `EvalErrorCode`/config 错误码 `KIND_BODY_MISMATCH` 或复用现有结构错误码；错误信息含 kind + 实际 body 类型）。
- [x] **Step 6:** 更新 `*ResolveValidateTest`/`PublishServiceTest` 构造：content 从 set 三载体改 set `body`；新增 kind↔body mismatch 拒绝用例。
- [x] **Step 7: Verify**：`$MVN -pl rule-config-svc -am test`
- [x] **Step 8: Commit**：`refactor(config): RuleVersion 实体 body 列 + 迁移 V1_40 + 发布期分派 + kind↔body 校验`

## P3 — config-svc DTO 孪生 + Hasher + Export/Import + 分析拆入

**Files (rule-config-svc):**
- Modify: `api/dto/RuleContent.java`、`api/dto/RuleDetailVO.java`、`api/dto/RuleVersionContentVO.java`、`api/dto/RuleBundle.java`（各删三载体字段→`RuleBody body`）
- Modify: `internal/service/ConfigServiceImpl.java:174-179,197-198`（VO 装配 → `getBody()`）
- Modify: `internal/bundle/RuleContentHasher.java:44`（`ruleHash` 签名三载体→`RuleBody body`，canonical 三键→单 `"body"` 键）+ 调用点 `RuleExportService.java:113`、`RuleImportService.java:152`
- Modify: `RuleExportService.java:113-126`、`RuleImportService.java:153-157`（携带/读回 → `getBody()`/`body`）
- Modify: `internal/analysis/RuleAnalysisServiceImpl.java:61-62`（拆入 → `version.getBody()`）
- Test: `RuleContentHasherTest`、`RuleExportServiceTest`、`RuleImportServiceTest`、`RuleContentTest`

- [x] **Step 1:** 4 个 DTO record 删三载体字段 → 加 `RuleBody body`（record 改构造器）。
- [x] **Step 2:** `ConfigServiceImpl` 两处 VO 装配（:174-179 RuleDetailVO、:197-198 RuleVersionContentVO）从 `getConditionAst()/getScriptSource()/getFlowGraph()` → `getBody()`。
- [x] **Step 3:** `RuleContentHasher.ruleHash`：签名参数 `AstNode ast, ..., ScriptSource script, FlowGraph flow` 三载体 → `RuleBody body`；canonical map 删 `"conditionAst"/"script"/"flowGraph"` 三键 → 单 `"body"` 键。两调用点（Export:113、Import:152）改传 `getBody()`。**幂等语义**：body 多态判别，不同规则 body 不同则 hash 不同。
- [x] **Step 4:** Export（:113-126）/Import（:153-157）携带/读回从三载体 → `body`。
- [x] **Step 5:** `RuleAnalysisServiceImpl:61-62` 构造 AnalyzableRule 从 `getConditionAst()`+`getFlowGraph()` → `getBody()`（AnalyzableRule 已在 P1 改 body 字段）。
- [x] **Step 6:** 更新测试：`RuleContentHasherTest`（三载体入参→body，含三变体区分 hash 用例）、Export/Import 往返（body 携带）、`RuleContentTest`。
- [x] **Step 7: Verify**：`$MVN -pl rule-config-svc -am test`
- [x] **Step 8: Commit**：`refactor(config): DTO/Hasher/Export·Import/分析 收敛为 body`

## P4 — eval-svc 读取链 SQL

> `RuleVersionRow` + `SnapshotAssembler` 已在 P1 随 Snapshot 改（带 `-am`），此处只剩 Mapper SQL。

**Files (rule-eval-svc):**
- Modify: `internal/repository/RuleVersionReadMapper.java:20,32,48,60,79,91`（三条 `@Select` 各删 `rv.condition_ast/script_source/flow_graph/referenced_snapshots AS ...` → 加 `rv.body AS bodyJson`）
- Test: `SnapshotAssemblerTest`（P1 已改）、`integration/EvalIntegrationTest.java:151`（建表/插数 SQL 从四列→body 列）

- [x] **Step 1:** 三条 `@Select` 的 select 列表改 `rv.body AS bodyJson`（删四载体列）。
- [x] **Step 2:** `EvalIntegrationTest:151` 及其它集成测试的 rule_version 插入 SQL：四列→`body`（插 `{"type":"AstBody","conditionAst":{...}}` 形态）。
- [x] **Step 3: Verify**：`$MVN -pl rule-eval-svc -am test`
- [x] **Step 4: Commit**：`refactor(eval): RuleVersionReadMapper 读 body 列`

## P5 — rule-api 契约

**Files (rule-api):**
- Modify: `RuleContentSource.java`（三载体方法→`RuleBody body()`）、`CreateRuleRequest`/`EditDraftRequest`/`NewVersionRequest`（各三载体字段→`RuleBody body`）、`RuleContent`（P3 已改，此处确认透传）、`RuleController.toContent()`（透传 body）
- Test: RuleController 集成测试（建/发布 body 请求）

- [x] **Step 1:** `RuleContentSource` 接口 `conditionAst()/script()/flowGraph()` → `body() : RuleBody`。
- [x] **Step 2:** 三写请求 record 删三载体 → 加 `RuleBody body`（primitive 无需 `@JsonSetter`；body 为对象）。
- [x] **Step 3:** `RuleController.toContent()` 透传 `source.body()`。
- [x] **Step 4:** 更新 RuleController 集成测试：POST/PUT 请求体从平铺三字段 → `body`（含 `type`）；六 kind 各一条建/发布断言。
- [x] **Step 5: Verify**：`$MVN -pl rule-api -am test`
- [x] **Step 6: Commit**：`refactor(api): 规则请求契约收敛为 body`

## P6 — rule-sdk 嵌入式

**Files (rule-sdk / -spring-boot-starter):**
- Modify: `RuleEngineClient.java`（本地建 Snapshot 处走 `.body()`/便捷 setter；executors 按 kind 注册**不变**）
- Test: `RuleEngineClientTest`

- [x] **Step 1:** grep `RuleEngineClient` 内构造 `RuleVersionSnapshot` / 读三载体处，改走 builder body（F4 便捷 setter 仍可用，多数无需改）。
- [x] **Step 2:** 更新 `RuleEngineClientTest` 构造（若直调兼容构造器）。
- [x] **Step 3: Verify**：`$MVN -pl rule-sdk-spring-boot-starter -am test`
- [x] **Step 4: Commit**：`refactor(sdk): 本地建规则走 body`

## P7 — frontend body 判别联合

**Files (frontend):**
- Modify: `types/rule.ts`（请求/详情类型三载体→`body: RuleBody` 判别联合；`RuleKind` 联合不变）、`types/ast.ts`/`types/flow.ts`（作 body 内层保留）
- Modify: `store/ruleStore.ts`（`ast`/`script`/`flowGraph` 三状态→`body` + setter；`loadFromDetail` 回填 `detail.body`）
- Modify: `pages/rule-editor/CenterPanel.tsx`（`renderEditor` 按 `body.type` 取载荷分派六编辑器）
- Modify: `pages/rule-list/index.tsx`、`pages/rules-all/index.tsx`（`handleCreate` 按 kind 播种对应 body 变体骨架）
- Modify: `pages/rule-editor/VersionContentDrawer.tsx`、`VersionDiffDrawer.tsx`（只读按 `body.type` 展示载荷）
- Modify: 写请求处把 `body` 塞进请求体

- [x] **Step 1:** `types/rule.ts`：`RuleBody = { type:'AstBody'; conditionAst: AstNode } | { type:'ScriptBody'; script: ScriptSource } | { type:'FlowBody'; flowGraph: FlowGraph; referencedSnapshots?: Record<string, unknown> }`；请求/详情类型三载体字段 → `body: RuleBody`。
- [x] **Step 2:** `ruleStore`：三状态→`body`+`setBody`；`loadFromDetail(detail)` 回填 `detail.body`；各 kind 播种默认 body。
- [x] **Step 3:** `CenterPanel.renderEditor`：`switch(body.type)`（或先按 kind 再取 body 载荷）分派 AST 树/评分卡/决策树/决策表/ScriptEditor/FlowCanvasEditor（六编辑器内部不改，只改喂入载荷来源与 onChange 回写目标为 body 变体）。
- [x] **Step 4:** 两创建弹窗 `handleCreate` 按 kind 播种 body 骨架（AST_BOOLEAN→`{type:'AstBody',conditionAst:空AST}`、EXPRESSION_SCRIPT→`{type:'ScriptBody',...}`、DECISION_FLOW→`{type:'FlowBody',flowGraph:最小合法骨架,referencedSnapshots:{}}` 等）。
- [x] **Step 5:** 只读抽屉 `VersionContentDrawer`/`VersionDiffDrawer` 按 `body.type` 平铺展示对应载荷（`json(body.xxx)`，与现有一致，不做只读画布）。
- [x] **Step 6:** 写请求体塞 `body`。
- [x] **Step 7: Verify**：`npm run build` 通过；手动建/编/发布六形态各一（尤其 flow 画布 + script + AST 树），确认回填/提交/只读展示不退化。
- [x] **Step 8: Commit**：`refactor(frontend): 规则 body 判别联合 + 编辑器分派`

## P8 — 文档 + examples 登记

**Files (docs):** 按设计 spec-delta 段逐点改（改前跑 `doc-consistency-review`）。

- [x] **Step 1:** `00-decisions.md` 追 **D76**（append-only）：三承载平铺收敛为多态 RuleBody，L2 全栈，kind↔body 不变量，迁移 V1_40；取代设计 F 记录中"待实现"表述。
- [x] **Step 2:** `01-concepts.md` §3.4:212「三承载互斥」→「判定主体由多态 `RuleBody`（AstBody/ScriptBody/FlowBody）承载，与 kind 一致」；名词表:27 / kind:210 措辞。
- [x] **Step 3:** `05-storage.md` rule_version DDL 四列→单 `body JSON NOT NULL` + COMMENT；表清单行。
- [x] **Step 4:** `10-api-contract.md` 按设计 spec-delta 穷尽点（请求体:234-237、editDraft:280、RuleDetailVO:519、Bundle:467-480、示例:795-822、UNRESOLVED_VARIABLE:675、错误码加 KIND_BODY_MISMATCH）。
- [x] **Step 5:** `README.md` D12 行 / 决策表补 D76。
- [x] **Step 6:** `docs/examples/` live 剧本规则请求体 JSON + 预期响应从平铺→body（archive 冻结不动）。
- [x] **Step 7:** 跑 `doc-consistency-review` 循环到 0 修改收敛。
- [x] **Step 8: Commit**：`docs: 登记多态 RuleBody（D76）到概念/存储/API/README + examples`

## P9 — 端到端 + 收口

按 CLAUDE.md「功能测试纪律」：打可执行包起真实服务，别用 reactor run 目标。

- [x] **Step 1:** `$MVN clean test` 全量兜底——现有六形态单测/集成测试全绿 = 未退化；任一变红查而非改测试就绿。
- [x] **Step 2:** 打包起服务，确认 V1_40 迁移执行（rule_version 有 `body` 列、四旧列已 drop）、服务就绪。**历史数据可丢**（用户确认）：迁移的 UPDATE 转换对存量行仍生效；若存量行状态异常，可直接清 rule_definition/rule_version 重种（greenfield）。
- [x] **Step 3:** **跑通 `docs/examples/` 下所有 live 剧本**（用户要求"所有场景都跑一遍"）：逐个 example 的 curl 脚本按依赖顺序建/发布/评估，覆盖六形态（AST_BOOLEAN/SCORECARD/DECISION_TREE/DECISION_TABLE/EXPRESSION_SCRIPT/DECISION_FLOW），每条对预期结果核对。
- [x] **Step 4:** 查 `rule_version.body` 真落库为对应变体 JSON（`type` 正确、载荷完整）；flow 的 referencedSnapshots 冻进 body。
- [x] **Step 5:** 评估六形态验证输出正确（与重构前等价）+ trace 正常。
- [x] **Step 6:** kind↔body 一致性：造 kind=AST_BOOLEAN 但 body=ScriptBody 的请求，验证发布被拒（KIND_BODY_MISMATCH）。
- [x] **Step 7:** 导出 bundle → 导入（含 flow），验证 body 无损往返 + contentHash 幂等。
- [x] **Step 8:** DB 恒空字段审计：`body` 全非空、无残留四旧列；清理本次测试数据回干净基线。
- [x] **Step 9:** 派 `rule-engine-reviewer` 审代码↔文档对齐。
- [x] **Step 10:** 归档：本计划进 `plans/archive/`、设计进 `specs/archive/`（设计已并入 docs 正文）。

---

## 自审（writing-plans self-review）

- **spec 覆盖**：设计每节均有对应阶段——模型 P0、Snapshot+executor P1、实体+迁移+发布 P2、DTO/Hasher/Export·Import P3、读取链 P4、API P5、SDK P6、前端 P7、docs+examples P8、e2e+归档 P9。F1(examples)→P8-Step6；F2(assembler 降级)→P1-Step4；F3(hash 不迁移)→已确认无 DB 步；F4(builder)→P1-Step1 + 关键决定。
- **类型一致**：`RuleBody`/`AstBody`/`ScriptBody`/`FlowBody` 名称全程一致；builder 便捷 setter 名沿用既有（`conditionAst/script/flowGraph/addReferencedSnapshot`）；`getBody/setBody` Lombok 生成。
- **无占位**：各阶段给了确切 file:line + 关键代码（RuleBody 类型、builder 组装规则、迁移 SQL、deserializeBody、kind↔body 校验）；机械孪生扫点给了 grep 依据行号。
- **顺序正确**：P1 因删字段断编译，明确"Row/Assembler 随 P1 一起改、带 `-am`"，P4 只剩 SQL——避免半编译态。

## GATE 2（计划审，用户授权代审 + 明确执行约束）

用户明确交代执行约束（"后端→前端→全测试"顺序 / "历史数据可丢" / "所有场景都跑一遍"），与计划阶段序 + P9 验收一致；requirements 已锁定。以 writing-plans 自审 + 上述约束核对，计划**通过 GATE 2**，进 inline TDD 执行（`executing-plans`）。选 inline 而非 subagent-driven 的理由：本重构删共享类型字段会跨模块级联断编译，任务高度耦合，需在单一上下文里协调多文件同改，subagent 分包易漏读者致半编译态。
