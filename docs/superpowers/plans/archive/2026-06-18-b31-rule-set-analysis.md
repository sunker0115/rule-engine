# B31 规则集静态分析 / 冲突检测 — 实现计划（含前端）

> **✅ 已实装（2026-06-18，develop）。** 权威记录见 `08-evolution.md §2.26` 已实装块。本计划为实现前设计稿，保留作历史；以下偏差以**实装为准**：
> - **多了一类检查**：**冗余 redundancy**（单规则内一条件被同组另一条件蕴含，可简化；AST_BOOLEAN 扁平 AND + 决策树 IfNode + 决策表行内复用同机制）——原计划只有 6 类，实装 7 类。
> - **草稿优先**：分析取每条规则 DRAFT 优先、否则 ACTIVE（原计划读 ACTIVE）——支持发布前自查。
> - **前端入口**：左栏**摘要条作唯一入口**（点击进抽屉）+ 抽屉分类 + 规则 badge；**去掉了独立「规则集分析」按钮**；非可分析 kind（评分卡/脚本）隐藏入口。右栏属性面板未动。
> - **FIRST_HIT 死规则**：等优先级 masking 因 tie-break(ruleVersionId) 分析期不可见而**保守降级不报**（漏报非误报）——见下文 §0.3 已修正。
> - **决策表**：行内不一致 + 行内冗余均已做。
> - 验证：全量 clean test 27 模块绿 + 真 MySQL Testcontainers 集成测试（`RuleAnalysisIT`）。
>
> 来源 backlog B31 / 08-evolution §2.26。基于 code-architect 模式分析（2026-06-18）。
> 性质：纯只读静态分析。**零 DDL、不发事件、不碰评估热路径、不碰发布路径。**

---

## 0. v1 取舍点（需先拍板，定错返工）

1. **触发方式 = 按需 API**（编辑器点"分析"→ `GET /admin/v1/scenes/{sceneCode}/analysis`），**不挂发布期拦截**。发布路径一行不动；发布期 advisory 检查留后续。
2. **精确推理范围 = `AST_BOOLEAN` 的"顶层 AND-of-Condition"**。这是风控规则最常见形态。含 OR/XOR/NOT/IfNode、或 SCORECARD/DECISION_TREE/DECISION_TABLE/EXPRESSION_SCRIPT → 整条标 **UNKNOWN**，进报告 `unanalyzableRules` 列表，**绝不静默当"无问题"**。零误报优先（宁漏报不误报）。
3. **三策略死规则都可精确判（已确认实装 + 各有确定先后序）**：`EvalEngine` 三分支均实装并测试。死规则/遮蔽判定的"谁先/谁胜"语义按策略取：
   - `HIGHEST_PRIORITY`：按 decision priority（高者胜）；被更高优先级规则在输入空间上完全覆盖 → 决策永不胜出。
   - `FIRST_HIT`：引擎 `FIRST_HIT_ORDER` = binding priority 倒序，**平局用 ruleVersionId 升序**。分析期 `AnalyzableRule`/`ProjectedRule` **不携带 ruleVersionId**，故跨规则 masking **仅当 priority 严格大于时可精确判**；**等优先级**因 tie-break(ruleVersionId)分析期不可见而**保守降级不报(漏报非误报)**。决策表 row 有显式行序,FIRST 语义下 masking 精确(不受此限)。（已实装语义,见 `DeadRuleDetector` 注释。）
   - `ALL_HITS`：全量收集所有命中、互不遮蔽 → 无 masking 概念；该策略下死规则 = 仅 incoherence（自空集）或完全 redundancy。
   - 决策表 row 同样有序，FIRST 语义下 masking 精确。v1 不新增任何排序字段（复用 `FIRST_HIT_ORDER` / decision priority）。

> 以上三条是我的推荐默认，符合"以简为先 + 零误报"。要改告诉我，否则按此实现。

---

## 0.5 开源参照系（设计依据，非自创）

算法与检查体系对齐两套成熟开源/学界实践，避免闭门造车：

- **Drools Verifier**（开源规则库静态校验器，Java）——做法即"DRL AST 拍平成扁平结构后让校验规则推理"，与本设计"AST→cube"同构。其检查清单直接作为 B31 的蓝本：
  - `redundancy`（同条件同结果）→ 本设计**重叠/冗余**
  - `subsumption`（一规则条件 ⊇ 另一、同结果 → 后者永不独立命中）→ 本设计**死规则/masking**
  - `conflict`（无法同时满足，或同条件不同结果）→ 本设计**冲突**
  - `range/gap`（字段区间缺口）→ 本设计**覆盖缺口**的区间维度
  - **`incoherence`（规则自身条件矛盾，如 `age==20 && age==30` → 必死）→ 本设计补入的 intra-rule 检查**（单规则 cube 为空集）
  - 严重度 Notes/Warnings/Errors → 本设计 INFO/WARN/**ERROR**
- **DMN 决策表校验**（Calvanese et al. 2016《Semantics and Analysis of DMN Decision Tables》形式化 + 开源工具 `red6/dmn-check`，Java）——决策表行内分析的直接依据：
  - **几何解释**：每条规则/行 = 输入空间的超矩形，overlap=相交、missing=未覆盖区域 = 本设计 `ConditionSpace` cube（这是 OMG/学界标准形式化，非自拟）。
  - **hit policy 决定 overlap 是否为错**：UNIQUE→overlap 即错；ANY→overlap 须同输出；PRIORITY→优先级裁决；FIRST→按序 masking。**映射到本项目 `Scene.decisionStrategy`**：`HIGHEST_PRIORITY`≈PRIORITY、`ALL_HITS`≈COLLECT、`FIRST_HIT`≈FIRST。
  - dmn-check / drools-verifier 均 Java，作参考实现对照。

---

## 1. 模块落点（不新建 Maven 模块）

| 层 | 模块 | 职责 |
|---|---|---|
| 分析内核 | `rule-kernel`（新包 `internal.analysis` + 公开契约 `api.analysis`） | 纯 Java：sealed AST 遍历 + 区间推理 + 4 个 detector。无 Spring（ArchUnit 红线） |
| 编排 | `rule-config-svc`（新包 `internal.analysis`） | 查快照/decision、组 `AnalyzableRule`、调 analyzer、出报告。`@Transactional(readOnly=true)` |
| API | `rule-api` | `RuleAnalysisController` GET 端点 |
| 前端 | `frontend` | api 封装 + 右栏分析面板 + 左栏规则告警 badge |

理由：算法是纯计算属 kernel（参照 `AstDataTypeResolver` 的 sealed switch 范式），可被 SDK 复用；与 B33 血缘"共享读快照底座"同模块承载。**不碰 eval 模块**（config 禁依赖 eval，快照从 config 侧 typed 实体读，不走 `RuleVersionReadMapper`）。

---

## 2. 核心抽象（kernel）

```
// 条件取值空间（可推理约束），sealed
sealed interface ConditionSpace permits NumericRange, PointSet, AnySpace, UnknownSpace
  record NumericRange(double lo, boolean loInc, double hi, boolean hiInc)   // GT/GTE/LT/LTE/BETWEEN/DATE_*
  record PointSet(Set<Object> values, boolean negated)                      // EQ/NEQ/IN/NOT_IN
  record AnySpace()                                                         // 该维度无约束
  record UnknownSpace(String reason)                                        // MATCHES/脚本/time.*/自定义 → 降级
  Tri intersects(ConditionSpace o)   // TRUE/FALSE/UNKNOWN
  Tri subsumes(ConditionSpace o)     // this ⊇ o ?

ConditionSpaceFactory.from(ConditionNode): ConditionSpace   // 按 ConditionTypeCatalog.spec→ParamSpec 分档
AnalyzableRule(ruleCode, version, AstNode ast, List<DecisionBinding> bindings, kind)  // kernel 轻量输入
RuleSetAnalyzer.analyze(List<AnalyzableRule>, SceneExecutionStrategy, Set<String> boundDecisionCodes): RuleSetAnalysisReport
```

报告契约（`kernel.api.analysis`，record，供 config/api/前端共用；出 API 边界 enum `.name()` 转 String）：
```
RuleSetAnalysisReport(sceneCode, incoherences[], deadRules[], conflicts[], overlaps[], coverageGaps[], unanalyzableRules[])
IncoherenceFinding(ruleCode, reason, severity=ERROR)                  // 自相矛盾，必死
DeadRuleFinding(deadRuleCode, coveredByRuleCode, reason, severity)    // 跨规则 subsumption / 决策表 row masking
ConflictFinding(locA, locB, decisionA, decisionB, reason, severity)  // loc = ruleCode 或 "tableCode#row{n}"
OverlapFinding(locA, locB, reason, severity=INFO)
CoverageGapFinding(scope, decisionCode_or_inputRegion, reason, severity=WARN)
UnanalyzableRule(ruleCode, reason)   // 灰色"未分析"
```
Finding 的位置用 `loc`（ruleCode 或 `tableCode#row{n}`），使跨规则与决策表行内复用同一组 record。

算子分档（按 `ParamSpec`）：可精确 = NUMERIC/ANY_TYPE(EQ/NEQ)/BETWEEN/IN/DATE_COMPARE；降级 UNKNOWN = STRING_PREFIX/SUFFIX、REGEX、TIME_*、LIST_MEMBERSHIP、NONE(脚本/自定义)。**同 `(metricCode, valueRef)` 才可比，跨 metric 独立维度。**

---

## 3. 检查体系（对齐 Drools Verifier / DMN，hit-policy-aware）

5 个检查，severity 随 `Scene.decisionStrategy`（≈DMN hit policy）变：

| 检查 | severity | 算法 | hit-policy 语义 |
|---|---|---|---|
| **覆盖缺口** gap | WARN | `规则引用的 decisionCode 全集` − `任何路径可产出的 decisionCode`（bindings ∪ DecisionLeafNode ∪ DecisionTable rows）。+ 决策表/区间维度的输入空间缺口 | 与策略无关 |
| **不一致** incoherence | **ERROR** | 单规则自身 cube = 空集（如 `age==20 && age==30`、`GT 30 && LT 10`）→ 必死。**intra-rule，最廉价最确定** | 与策略无关 |
| **死规则/遮蔽** subsumption/masking | WARN | H.cube `subsumes` R.cube 且 H 胜出 → R 永不独立命中 | HIGHEST_PRIORITY：按 decision priority 判 H 胜出；FIRST_HIT：跨规则降级 INFO、决策表行**按序**精确判 masking |
| **重叠/冗余** overlap/redundancy | INFO | 两 cube 相交且互不包含 → 提示；条件+结果全同 = redundancy | PRIORITY/COLLECT 下 overlap 容许（INFO）；若 future 有 UNIQUE 类则升 ERROR |
| **冲突** conflict | WARN | cube 相交 + decisionCode 不同 + 同时生效 | ALL_HITS：相交且不同 decision 都触发 → conflict；HIGHEST_PRIORITY：仅当优先级相等才算冲突（否则优先级裁决，非冲突） |

- 算子分档可精确 = NUMERIC/EQ/NEQ/IN/BETWEEN/DATE；其余 → UnknownSpace，**任一维度 Unknown 即该对/该规则降级放过（宁漏报不误报）**。
- 非"顶层 AND-of-Condition"的 AST_BOOLEAN、决策树跨树、评分卡 → 整条进 `unanalyzableRules`（决策表行内分析照常做）。
- 报告 `severity` enum {INFO, WARN, ERROR}，出 API 边界 `.name()` 转 String。

---

## 4. 数据流

```
GET /admin/v1/scenes/{sceneCode}/analysis?tenantId
 → RuleAnalysisController (rule-api)
 → RuleAnalysisService.analyze(tenantId, sceneCode)  [config, readOnly]
     ├ SceneMapper.findByCode → sceneId, decisionStrategy
     ├ RuleDefinitionMapper.findByTenantAndSceneIds → 逐条 RuleVersionMapper.findActiveVersion → typed AstNode+bindings（照搬 RuleExportService 读法）
     ├ DecisionDefinitionMapper.findByCodes → priority/name
     └ RuleSetAnalyzer.analyze(...)  [kernel 纯计算]
 → RuleSetAnalysisReport → ApiResponse.ok
```

---

## 5. 前端（方案 B：抽屉 + 左栏摘要条 + badge；右栏不动）

scene 级分析的常驻信号放**左栏**（左栏本就是 scene 级），详情放**抽屉**（复用 `DryRunDrawer` 模式），**右栏保持节点属性不污染**。mockup 见 `docs/examples/b31-rule-analysis-mockup.html`。

- `src/api/scene.ts` 加 `getAnalysis(sceneCode, tenantId)`（经 `api/client.ts`，注入 X-Actor-Id）。
- 新建 `src/pages/rule-editor/RuleAnalysisDrawer.tsx`：全高抽屉，按 severity 分组渲染 6 类 Finding（Ant Design `Collapse`+`Tag`+`Badge`，severity→颜色 ERROR/WARN/INFO/SKIP）；`unanalyzableRules` 灰显标"未分析"+reason，**防运营误判"无问题"**；点 finding 高亮中栏对应规则 / 决策表行（复用现有选中态）。
- `LeftPanel.tsx`：① 顶部加**场景级摘要条**（`⛔n 🔴n 🟠n 🟡n` + "点击查看"→开抽屉）；② 每条规则节点叠 badge（死/冲突/不一致/未分析），数据源同一份 report。
- 触发：编辑器顶部工具栏加 [规则集分析] 按钮（带未读角标）+ 左栏摘要条点击，均开 `RuleAnalysisDrawer`。
- 报告字段全 String/枚举/code，前端零类型推断。i18n 文案走 `react-i18next`（项目已用）。

---

## 6. 构建顺序（TDD，每步先测后码）

1. kernel `ConditionSpace` + `intersects/subsumes/isEmpty` 三态 + test
2. kernel `ConditionSpaceFactory.from` 按 ParamSpec 分档 + test
3. kernel 报告 record 全家 + `AnalyzableRule`
4. kernel `IncoherenceDetector`（单规则空集，最廉价）+ `CoverageGapDetector`（最稳）+ test
5. kernel `OverlapDetector` + `DeadRuleDetector`(subsumption) + `ConflictDetector`（hit-policy-aware）+ test
6. kernel `DecisionTableDetector`（row→cube，行内 overlap/gap/masking，复用同机制）+ test
7. kernel `RuleSetAnalyzer` 编排（AST_BOOLEAN 顶层 AND→cube；决策表走 row 分析；其余标 UNKNOWN）+ test
8. config `RuleAnalysisService` + Impl（照搬 RuleExportService 读法）+ test（mock Mapper）
9. api `RuleAnalysisController`
10. `$MVN -pl rule-kernel,rule-config-svc,rule-api -am test` → 最终 `$MVN clean test` 兜底
11. 前端 api + AnalysisPanel + RightPanel tab + LeftPanel badge
12. e2e：建 scene + 配重叠/冲突/自相矛盾/决策表缺口各一例发布 → 调 `/analysis` 验报告 → 清理测试数据

---

## 7. 纪律
- kernel 分析类禁 Spring（ArchUnit `KernelArchTest`）；config 禁依赖 eval。
- 所有"无法判定"显式降级 UNKNOWN + 进 `unanalyzableRules`，严禁静默当"无冲突"。
- 报告 record 出边界 enum→String；纯只读，不发事件，零 DDL。
- 测试方法名英文，注释中文。
