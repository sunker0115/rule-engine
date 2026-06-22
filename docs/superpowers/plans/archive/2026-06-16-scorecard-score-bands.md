# 评分卡分段决策（Score Bands）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给评分卡（kind=SCORECARD）增加"分数段→决策"能力：算出 score 后按 band 落段出对应 decision+category；threshold 仍作命中门槛；不配 bands 时行为与现状完全一致。

**Architecture:** `ScorecardRootNode` AST 加 `bands` 值对象列表（向后兼容空构造）；`ScorecardExecutor` bands 空走老路、非空按段出决策（复用 EvalEngine 既有 hitDecisions 路径，零改 EvalEngine）；发布期校验 bands 无重叠 + decisionCode 存在并回填 name/priority；前端删 DecisionBinding 死字段、ScorecardEditor 加 bands 配置区。

**Tech Stack:** Java 25 / Spring Boot 4 / MyBatis-Plus / Jackson3 typed AST / 前端 React+TS+antd+i18next。

**Spec:** `docs/superpowers/specs/2026-06-16-scorecard-score-bands-design.md`

**环境：** 后端 `mvn-env` skill 设环境用 `$MVN`，跨模块带 `-am`，结束 `$MVN clean test`。前端 `frontend/` 下 `npm run build`。测试方法名英文、注释中文。提交后 `git status --short` 确认干净。

---

## 文件结构

**kernel：**
- Create `api/model/ast/ScoreBand.java` — 分段值对象 record
- Modify `api/model/ast/ScorecardRootNode.java` — 加 bands + 兼容构造
- Modify `internal/evaluator/ScorecardExecutor.java` — bands 非空分段出决策

**config-svc：**
- Modify `internal/publish/PublishService.java` — validateKindStructure 加 bands 校验 + 回填 band decisionCode 的 name/priority

**前端：**
- Modify `src/types/ast.ts` — ScorecardRootNode 加 bands；新增 ScoreBand
- Modify `src/types/rule.ts` — DecisionBinding 删 scoreRangeMin/Max
- Modify `src/pages/rule-editor/DecisionBindingEditor.tsx` — 删死字段输入
- Modify `src/pages/rule-editor/ScorecardEditor.tsx` — 加 bands 配置表格
- Modify `src/i18n/locales/{zh-CN,en}/rule.ts` + `types.ts` — bands 相关文案，删 scoreRange 文案

---

## Task 1: ScoreBand 值对象 + ScorecardRootNode 加 bands

**Files:**
- Create: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/ast/ScoreBand.java`
- Modify: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/ast/ScorecardRootNode.java`
- Test: `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/api/model/ast/ScorecardRootNodeTest.java`（新建或追加）

- [ ] **Step 1: 写 ScoreBand record**

```java
package com.sstlfsj.rule.kernel.api.model.ast;

/**
 * 评分卡分段：score ∈ [minScore, maxScore) 时出 decisionCode（带 category 风险等级）。
 * 非 AST 节点，是 ScorecardRootNode 的值对象，不进 @JsonSubTypes 多态体系。
 *
 * @param minScore     段下界（含）
 * @param maxScore     段上界（不含，左闭右开）
 * @param decisionCode 该段命中产出的决策码（发布期校验存在并回填 name/priority）
 * @param category     风险等级标签（如 HIGH_RISK），可空
 */
public record ScoreBand(double minScore, double maxScore, String decisionCode, String category) {}
```

- [ ] **Step 2: ScorecardRootNode 加 bands 字段 + 兼容构造（先写测试）**

测试 `ScorecardRootNodeTest`：
```java
@Test
void compatConstructor_defaultsBandsEmpty() {
    ScorecardRootNode n = new ScorecardRootNode(java.util.List.of(), 60.0);
    assertThat(n.bands()).isEmpty();
    assertThat(n.threshold()).isEqualTo(60.0);
}

@Test
void bands_nullNormalizedToEmpty() {
    ScorecardRootNode n = new ScorecardRootNode(java.util.List.of(), 60.0, null);
    assertThat(n.bands()).isEmpty();
}

@Test
void bands_retained() {
    ScoreBand b = new ScoreBand(0, 60, "REJECT", "HIGH_RISK");
    ScorecardRootNode n = new ScorecardRootNode(java.util.List.of(), 60.0, java.util.List.of(b));
    assertThat(n.bands()).containsExactly(b);
}
```

- [ ] **Step 3: 跑测试确认失败（编译错：3 参构造不存在）**

Run: `$MVN -pl rule-kernel test -Dtest=ScorecardRootNodeTest -Dsurefire.failIfNoSpecifiedTests=false -q`
Expected: 编译失败 / 测试失败

- [ ] **Step 4: 改 ScorecardRootNode**

```java
public record ScorecardRootNode(
        List<ConditionNode> conditions,
        double threshold,
        List<ScoreBand> bands
) implements AstNode {
    public ScorecardRootNode {
        conditions = conditions == null ? List.of() : List.copyOf(conditions);
        bands = bands == null ? List.of() : List.copyOf(bands);
    }
    /** 兼容构造：无 bands，现有 2 参调用点不变。 */
    public ScorecardRootNode(List<ConditionNode> conditions, double threshold) {
        this(conditions, threshold, List.of());
    }
}
```
（保留原 Javadoc，threshold 说明改为"命中门槛：score < threshold 则规则不命中；conditions 各带 weight"。）

- [ ] **Step 5: 跑测试通过 + 全 kernel 回归（确认 2 参旧调用点不破）**

Run: `$MVN -pl rule-kernel test -q`
Expected: BUILD SUCCESS（2 参兼容构造保证 SDK/前端建的快照等旧调用点不变）

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat(kernel): ScorecardRootNode 加 bands 分段 + ScoreBand 值对象（向后兼容空构造）"
```

---

## Task 2: ScorecardExecutor 分段出决策

**Files:**
- Modify: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/evaluator/ScorecardExecutor.java`
- Test: `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/evaluator/ScorecardExecutorTest.java`（追加）

参考：现有 `ScorecardExecutor.execute`（累分逻辑保留）、`DecisionTreeExecutor.hit`（构造 Decision 带 category 的范式）、`Decision` 构造 `(code, name, priority, fromRuleVersionId, fromRuleCode, fromRuleVersion, category)`。

- [ ] **Step 1: 写失败测试（bands 分段）**

`ScorecardExecutorTest` 追加（**先打开该测试看现有 bindings/ctx 构造 helper，复用**）：
```java
@Test
void bandsHit_emitsDecisionWithCategory() {
    // score=70 落 [60,80) → REVIEW/MEDIUM_RISK
    ScoreBand low = new ScoreBand(0, 60, "REJECT", "HIGH");
    ScoreBand mid = new ScoreBand(60, 80, "REVIEW", "MEDIUM");
    ScorecardRootNode root = new ScorecardRootNode(
            List.of(condWithWeight("c1", 70.0)), 0.0, List.of(low, mid));
    RuleVersionSnapshot snap = scorecardSnapshot(root);   // helper：kind=SCORECARD
    EvalResult r = executor.execute(snap, ctxWithCondTrue());
    assertThat(r.ruleHit()).isTrue();
    assertThat(r.score()).isEqualTo(70.0);
    assertThat(r.finalDecision().code()).isEqualTo("REVIEW");
    assertThat(r.category()).isEqualTo("MEDIUM");
    assertThat(r.hitDecisions()).hasSize(1);
}

@Test
void belowThreshold_notHit_withBands() {
    ScorecardRootNode root = new ScorecardRootNode(
            List.of(condWithWeight("c1", 10.0)), 50.0,
            List.of(new ScoreBand(0, 100, "X", null)));
    EvalResult r = executor.execute(scorecardSnapshot(root), ctxWithCondTrue());
    assertThat(r.ruleHit()).isFalse();   // score 10 < threshold 50 → 弃权
    assertThat(r.score()).isEqualTo(10.0);
    assertThat(r.finalDecision()).isNull();
}

@Test
void scoreInGap_hitsButNoBandDecision() {
    // bands 只覆盖 [0,60)，score=70 落空隙 → 命中但无段决策（回退 binding）
    ScorecardRootNode root = new ScorecardRootNode(
            List.of(condWithWeight("c1", 70.0)), 0.0,
            List.of(new ScoreBand(0, 60, "REJECT", null)));
    EvalResult r = executor.execute(scorecardSnapshot(root), ctxWithCondTrue());
    assertThat(r.ruleHit()).isTrue();
    assertThat(r.finalDecision()).isNull();
    assertThat(r.hitDecisions()).isEmpty();
}

@Test
void noBands_legacyThresholdBehaviorUnchanged() {
    // bands 空 → 老逻辑：score>=threshold 命中，无 decision
    ScorecardRootNode root = new ScorecardRootNode(
            List.of(condWithWeight("c1", 70.0)), 60.0);   // 2 参，bands 空
    EvalResult r = executor.execute(scorecardSnapshot(root), ctxWithCondTrue());
    assertThat(r.ruleHit()).isTrue();
    assertThat(r.score()).isEqualTo(70.0);
    assertThat(r.finalDecision()).isNull();
    assertThat(r.hitDecisions()).isEmpty();
}
```
> 若现有测试无 `condWithWeight`/`scorecardSnapshot`/`ctxWithCondTrue` helper，照现有测试已有的构造方式写（打开文件看它怎么造 ConditionNode+weight、RuleVersionSnapshot、EvalContext）。

- [ ] **Step 2: 跑确认失败**

Run: `$MVN -pl rule-kernel test -Dtest=ScorecardExecutorTest -q`
Expected: 新用例 FAIL（现状 bands 被忽略、decision 恒 null）

- [ ] **Step 3: 改 execute 的命中返回段**

把现有 `boolean hit = score >= root.threshold(); return new EvalResult(hit, null, ...)` 替换为分段逻辑：
```java
// 累分循环不变（score 已算好）。命中判定 + 分段决策：
if (root.bands().isEmpty()) {
    // 现状路径：单 threshold，无 decision（行为一字不动）
    boolean hit = score >= root.threshold();
    return new EvalResult(hit, null, List.of(),
            scorecardRoot(collect, hit, factorTraces, rvId, code, version),
            null, score, null, null);
}
// bands 非空：threshold 作命中门槛
if (score < root.threshold()) {
    return new EvalResult(false, null, List.of(),
            scorecardRoot(collect, false, factorTraces, rvId, code, version),
            null, score, null, null);
}
// 命中门槛：找 score ∈ [minScore, maxScore) 的段
ScoreBand band = null;
for (ScoreBand b : root.bands()) {
    if (score >= b.minScore() && score < b.maxScore()) { band = b; break; }
}
if (band == null) {
    // 落空隙：命中但无段决策（回退 EvalEngine binding）
    return new EvalResult(true, null, List.of(),
            scorecardRoot(collect, true, factorTraces, rvId, code, version),
            null, score, null, null);
}
// 段命中：出决策（name/priority 发布期已回填进快照 decisionBindings，按 decisionCode 索引；
// 找不到则 name="" priority=0，与 DecisionTreeExecutor.hit 回退一致）
Decision decision = snapshot.decisionBindings().stream()
        .filter(b -> b.decisionCode().equals(band.decisionCode()))
        .max(java.util.Comparator.comparingInt(RuleVersionSnapshot.DecisionBinding::priority))
        .map(b -> new Decision(b.decisionCode(), b.name(), b.priority(),
                snapshot.ruleVersionId(), snapshot.code(), snapshot.version(), band.category()))
        .orElseGet(() -> new Decision(band.decisionCode(), "", 0,
                snapshot.ruleVersionId(), snapshot.code(), snapshot.version(), band.category()));
return new EvalResult(true, decision, List.of(decision),
        scorecardRoot(collect, true, factorTraces, rvId, code, version),
        null, score, band.category(), null);
```
（import `ScoreBand`、`Decision`。`Decision` 7 参构造见 Decision.java。）

- [ ] **Step 4: 跑测试通过 + kernel 全回归**

Run: `$MVN -pl rule-kernel test -q`
Expected: BUILD SUCCESS（含老 scorecard 测试不回归）

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(kernel): ScorecardExecutor 按 band 分段出决策（bands 空走老逻辑，复用 hitDecisions 路径）"
```

---

## Task 3: 发布期 bands 校验 + decisionCode 回填

**Files:**
- Modify: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/publish/PublishService.java`
- Test: `rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/publish/PublishServiceTest.java`（追加）

参考：`validateKindStructure`（line 582，SCORECARD 分支）、决策码校验回填范式（line 718-738，`DECISION_CODE_NOT_FOUND` + 从 decision_definition 回填 priority/name）、`decisionDefinitionMapper`。

- [ ] **Step 1: 写失败测试（bands 校验）**

`PublishServiceTest` 追加（**先看现有 SCORECARD 发布测试怎么造规则 + mock decisionDefinitionMapper**）：
```java
@Test
void scorecardBands_overlap_rejected() {
    // [0,60) 与 [50,80) 重叠 → 拒绝
    ScorecardRootNode ast = new ScorecardRootNode(List.of(weightedCond()), 0.0,
            List.of(new ScoreBand(0, 60, "A", null), new ScoreBand(50, 80, "B", null)));
    assertThatThrownBy(() -> publishScorecard(ast, decisionsExist("A", "B")))
            .hasMessageContaining("重叠");
}

@Test
void scorecardBands_minGeMax_rejected() {
    ScorecardRootNode ast = new ScorecardRootNode(List.of(weightedCond()), 0.0,
            List.of(new ScoreBand(80, 60, "A", null)));
    assertThatThrownBy(() -> publishScorecard(ast, decisionsExist("A")))
            .hasMessageContaining("minScore");
}

@Test
void scorecardBands_decisionNotFound_rejected() {
    ScorecardRootNode ast = new ScorecardRootNode(List.of(weightedCond()), 0.0,
            List.of(new ScoreBand(0, 60, "MISSING", null)));
    assertThatThrownBy(() -> publishScorecard(ast, decisionsExist(/*none*/)))
            .hasMessageContaining("DECISION_CODE_NOT_FOUND");
}

@Test
void scorecardBands_valid_passes() {
    ScorecardRootNode ast = new ScorecardRootNode(List.of(weightedCond()), 0.0,
            List.of(new ScoreBand(0, 60, "REJECT", "HIGH"), new ScoreBand(60, 100, "PASS", "LOW")));
    // 不抛即通过
    publishScorecard(ast, decisionsExist("REJECT", "PASS"));
}
```
> helper 按现有 PublishServiceTest 的发布测试范式写（造 RuleVersion + mock mapper）。`weightedCond()` = 带 weight>0 的 ConditionNode。

- [ ] **Step 2: 跑确认失败**

Run: `$MVN -pl rule-config-svc -am test -Dtest=PublishServiceTest -q`
Expected: 新用例 FAIL（bands 当前不校验）

- [ ] **Step 3: validateKindStructure 的 SCORECARD 分支加 bands 校验**

在 `validateKindStructure`（line 583 SCORECARD 分支，weight>0 校验之后）追加 bands 校验：
```java
// bands 非空时：min<max、按 minScore 排序后相邻不重叠（左闭右开端点相接不算重叠）
List<ScoreBand> bands = scorecardRoot.bands();
if (!bands.isEmpty()) {
    List<ScoreBand> sorted = bands.stream()
            .sorted(java.util.Comparator.comparingDouble(ScoreBand::minScore)).toList();
    for (ScoreBand b : sorted) {
        if (b.minScore() >= b.maxScore()) {
            throw new IllegalArgumentException(
                    "SCORECARD band minScore 必须 < maxScore: [" + b.minScore() + "," + b.maxScore() + ")");
        }
    }
    for (int i = 1; i < sorted.size(); i++) {
        if (sorted.get(i).minScore() < sorted.get(i - 1).maxScore()) {
            throw new IllegalArgumentException("SCORECARD bands 区间重叠: "
                    + "[" + sorted.get(i - 1).minScore() + "," + sorted.get(i - 1).maxScore() + ") 与 "
                    + "[" + sorted.get(i).minScore() + "," + sorted.get(i).maxScore() + ")");
        }
    }
}
```
（import `ScoreBand`、`List`。）

- [ ] **Step 4: band decisionCode 校验存在 + 回填进快照**

定位 SCORECARD 发布时构造快照 decisionBindings 的位置（看 line 718 `enrichBindings` 或同类方法）。band 的 decisionCode 也要进 decision 校验池：发布期收集"AST 里所有引用的 decisionCode"（含 bands），统一校验存在 + 回填 name/priority 进快照 decisionBindings（这样 §Task2 executor 按 decisionCode 索引能拿到 name/priority）。

具体：在富化 decisionBindings 的方法里，把 `scorecardRoot.bands()` 的 decisionCode 也纳入待校验/回填集合（去重），对每个查 `decisionDefinitionMapper`，缺失抛 `DECISION_CODE_NOT_FOUND`，存在则加一条 `DecisionBinding(code, name, priority)` 进快照（若该 code 尚未在 decisionBindings 中）。**先打开 enrichBindings/freezeXxx 看它怎么收集 + 回填，band 的 code 并入同一流程。**

- [ ] **Step 5: 跑测试通过 + config-svc 回归**

Run: `$MVN -pl rule-config-svc -am test -q`
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat(config): 发布期校验评分卡 bands（无重叠+min<max）+ band decisionCode 存在校验与回填"
```

---

## Task 4: 前端类型 + 删 DecisionBinding 死字段

**Files:**
- Modify: `frontend/src/types/ast.ts`、`frontend/src/types/rule.ts`
- Modify: `frontend/src/pages/rule-editor/DecisionBindingEditor.tsx`
- Modify: `frontend/src/i18n/locales/zh-CN/rule.ts`、`en/rule.ts`、`src/i18n/types.ts`

- [ ] **Step 1: types/ast.ts — ScorecardRootNode 加 bands + ScoreBand**

```typescript
export interface ScorecardRootNode {
  type: 'ScorecardRootNode';
  conditions: ConditionNode[];
  threshold: number;
  bands?: ScoreBand[];
}

export interface ScoreBand {
  minScore: number;
  maxScore: number;
  decisionCode: string;
  category?: string | null;
}
```

- [ ] **Step 2: types/rule.ts — DecisionBinding 删死字段**

```typescript
export interface DecisionBinding {
  decisionCode: string;
}
```
（删 `scoreRangeMin?`/`scoreRangeMax?` 两行。）

- [ ] **Step 3: DecisionBindingEditor 删 min/max 输入**

打开 `DecisionBindingEditor.tsx`，删掉 `scoreRangeMin`/`scoreRangeMax` 的两个 `InputNumber`（约 line 52-64 区域）+ 不再用的 `InputNumber` import（若仅此处用）。只留 decisionCode 的 Select。

- [ ] **Step 4: i18n 删 scoreRange 文案**

`zh-CN/rule.ts` + `en/rule.ts` 的 `decisionBinding` 块删 `scoreRangeMin`/`scoreRangeMax`；`types.ts` 的 `decisionBinding` 类型删这两 key。

- [ ] **Step 5: 构建**

Run（frontend）：`npm run build`
Expected: 通过（tsc + vite，无 scoreRange 残留引用）

- [ ] **Step 6: Commit**

```bash
git add frontend/src/types/ frontend/src/pages/rule-editor/DecisionBindingEditor.tsx frontend/src/i18n/
git commit -m "refactor(frontend): 决策绑定删 scoreRangeMin/Max 死字段 + ScorecardRootNode 类型加 bands"
```

---

## Task 5: ScorecardEditor 加 bands 配置区

**Files:**
- Modify: `frontend/src/pages/rule-editor/ScorecardEditor.tsx`
- Modify: `frontend/src/i18n/locales/zh-CN/rule.ts`、`en/rule.ts`、`src/i18n/types.ts`

参考：现有 ScorecardEditor（threshold + conditions 列表）、决策下拉数据来源（DecisionBindingEditor 怎么拿 decisions 列表 → 同款传入或复用）。

- [ ] **Step 1: ScorecardEditor 接 decisions 选项 + bands 表格**

在 threshold 区下方加 bands 配置表格。每行 `[minScore(InputNumber), maxScore(InputNumber), decision(Select 选 decisionCode), category(Input 可选)]` + 删行按钮 + "添加分段"按钮。bands 空时显示提示 `t('editor.scorecard.bandsEmptyHint')`（"不配分段则按阈值单命中"）。

```tsx
// 增删改 band
const updateBand = (i: number, patch: Partial<ScoreBand>) =>
  onChange({ ...node, bands: (node.bands ?? []).map((b, idx) => idx === i ? { ...b, ...patch } : b) });
const addBand = () => onChange({ ...node, bands: [...(node.bands ?? []), { minScore: 0, maxScore: 0, decisionCode: '', category: null }] });
const removeBand = (i: number) => onChange({ ...node, bands: (node.bands ?? []).filter((_, idx) => idx !== i) });
```
decisions 选项：给 ScorecardEditor 加一个 `decisions: { code: string; name: string }[]` prop（从 CenterPanel/编辑器已加载的决策列表传入——查 ScorecardEditor 在 CenterPanel 怎么被渲染、decisions 数据在哪），下拉 `options = decisions.map(d => ({ value: d.code, label: d.code + ' (' + d.name + ')' }))`。
前端轻校验（即时提示，非阻断）：min<max、相邻段重叠时行标红 + Typography 提示；权威校验在发布期后端。

- [ ] **Step 2: i18n 加 bands 文案**

`zh-CN/rule.ts` editor.scorecard 块加：`bandsTitle: '分段决策'`、`bandsEmptyHint: '不配分段则按阈值单命中'`、`bandMin: '最低分(含)'`、`bandMax: '最高分(不含)'`、`bandDecision: '决策'`、`bandCategory: '风险等级'`、`addBand: '添加分段'`、`bandOverlap: '区间重叠'`。`en/rule.ts` 对称。`types.ts` 的 editor.scorecard 类型加这些 key。

- [ ] **Step 3: 确认 ScorecardEditor 的 decisions prop 接通**

打开 CenterPanel.tsx 看 `<ScorecardEditor .../>` 渲染处，把决策列表传进去（决策数据来源同 DecisionBindingEditor 的 decisions——查它从哪个 hook/prop 拿，复用）。

- [ ] **Step 4: 构建**

Run（frontend）：`npm run build`
Expected: 通过

- [ ] **Step 5: Commit**

```bash
git add frontend/src/pages/rule-editor/ScorecardEditor.tsx frontend/src/pages/rule-editor/CenterPanel.tsx frontend/src/i18n/
git commit -m "feat(frontend): 评分卡编辑器加分段决策(bands)配置区"
```

---

## Task 6: 全量兜底 + 功能 e2e

**Files:** 无新增

- [ ] **Step 1: 后端全量 + 前端构建**

Run: `$MVN clean test`（全绿，含 kernel/config-svc/api 评分卡相关 + 老 scorecard 不回归 + Modulith/kernel 纯净）
Run（frontend）：`npm run build`

- [ ] **Step 2: 功能 e2e（起真实服务，CLAUDE.md 功能纪律）**

打可执行包起服务（连本地 MySQL）：
- 建 3 个 decision（如 REJECT/REVIEW/PASS）→ 建评分卡规则（含因子 weight + 3 个 band：[0,60)→REJECT/HIGH、[60,80)→REVIEW/MEDIUM、[80,∞)→PASS/LOW，threshold=0）→ 发布。
- 评估不同分数的主体（构造命中不同 weight 的 payload/metric），验证：score 落对应段 → finalDecision = 对应 decisionCode + category。
- 验 threshold>0 时低分主体 ruleHit=false（弃权）。
- 验老评分卡（无 bands）行为不变（score>=threshold 命中、无 decision）。
- 逐步查持久层确认快照里 bands + 回填后的 decisionBinding name/priority 真冻结。
- 清理测试数据（删本次建的 decision/规则），恢复干净基线。

- [ ] **Step 3: 收尾**

确认无脏数据；前端"未视觉验证"项在收尾说明列清。

---

## Self-Review 记录

- **Spec 覆盖**：§2 模型(Task1)、§3 执行语义含 bands 空/非空/门槛/空隙(Task2)、§4 发布期校验+回填(Task3)、§5 前端删死字段+编辑器(Task4/5)、§6 测试(各 task + Task6 e2e)。§7 非目标不实现（不全覆盖强制、不做重叠 hit policy、不动其他 kind）。
- **类型一致**：`ScoreBand(minScore,maxScore,decisionCode,category)`、`ScorecardRootNode(conditions,threshold,bands)` + 2 参兼容构造、`Decision` 7 参构造、前端 `ScoreBand{minScore,maxScore,decisionCode,category?}` 全计划一致。
- **占位符**：执行语义/校验/回填均给完整代码；测试 helper 指向"打开现有测试照其构造范式"（现有 ScorecardExecutorTest/PublishServiceTest 已有 scorecard 用例可仿）。
- **风险**：Task3 回填需并入现有 enrichBindings 流程（指明先读该方法）；Task5 decisions prop 接通需查 CenterPanel 渲染处（已指明）；前端无法视觉验证（标注）。
