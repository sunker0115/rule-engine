# ScoreBand name/priority 内聚化实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** ScoreBand 加 name/priority 字段，发布期直接回填进 band，ScorecardExecutor 从 band 直接读，评分卡彻底不依赖 decisionBindings（前端对 SCORECARD 隐藏决策绑定面板）。

**Architecture:** ScoreBand record 加末位 `name(String)` + `priority(int)` 字段（保留 4 参兼容构造）；发布期新增 `enrichBands` 方法从 decision_definition 批量查回填，替代旧的 `mergeBandDecisionCodes` 逻辑；ScorecardExecutor 段命中时直接用 `band.name()/priority()` 构造 Decision，不再索引 snapshot.decisionBindings。

**Tech Stack:** Java 25 / record / @JsonSetter(nulls=AS_EMPTY) for primitive / MyBatis-Plus / 前端 React+TS。

**Spec:** 对话里的架构讨论（方向 A 第一步：评分卡 ScoreBand 自包含）。

**环境：** 后端 `mvn-env` skill 设环境用 `$MVN`，跨模块带 `-am`，结束 `$MVN clean test`。前端 `frontend/` 下 `npm run build`。测试方法名英文，注释中文，提交后 `git status --short` 确认干净。

---

## 文件结构

**kernel（Task 1 + Task 3）：**
- Modify `api/model/ast/ScoreBand.java` — 加 name/String + priority/int + 4 参兼容构造
- Modify `internal/evaluator/ScorecardExecutor.java` — 段命中从 band 直接读 name/priority

**config-svc（Task 2）：**
- Modify `internal/publish/PublishService.java` — 加 `enrichBands` 方法，删 `mergeBandDecisionCodes` 对评分卡的注入，resolveAndValidate 里 SCORECARD bands 直接回填

**前端（Task 4）：**
- Modify `frontend/src/types/connector.ts` → `frontend/src/types/ast.ts` — ScoreBand type 加 name/priority
- Modify `frontend/src/pages/rule-editor/RightPanel.tsx` — SCORECARD 时隐藏 DecisionBindingEditor

---

## Task 1: ScoreBand 加 name/priority + 兼容构造 + 调用点

**Files:**
- Modify: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/ast/ScoreBand.java`
- Modify: `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/api/model/ast/ScoreBandTest.java`（追加用例）
- Test（调用点更新）: 所有 `new ScoreBand(4 参)` 的调用文件——`ScorecardExecutorTest.java`、`ScorecardRootNodeTest.java`、`JacksonConfigTest.java`

- [ ] **Step 1: 改 ScoreBand record（先不跑测试，Step 2 追加用例后一起跑）**

```java
package com.sstlfsj.rule.kernel.api.model.ast;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;

/**
 * 评分卡分段：score ∈ [minScore, maxScore) 时出 decisionCode + 决策名称/优先级（发布期回填）。
 * 非 AST 节点，是 ScorecardRootNode 的值对象，不进 @JsonSubTypes 多态体系。
 *
 * @param minScore     段下界（含）
 * @param maxScore     段上界（不含，左闭右开）
 * @param decisionCode 该段命中产出的决策码（发布期校验存在并回填 name/priority）
 * @param category     风险等级标签（如 HIGH_RISK），可空
 * @param name         决策名称（发布期从 decision_definition 回填，运行期直读，用户不填）
 * @param priority     决策优先级（发布期从 decision_definition 回填；primitive，缺键兜底 0）
 */
public record ScoreBand(
        double minScore, double maxScore,
        String decisionCode, String category,
        String name,
        @JsonSetter(nulls = Nulls.AS_EMPTY) int priority) {

    /** 4 参兼容构造：用于用户输入和测试（name/priority 发布期回填，初始为空/0）。 */
    public ScoreBand(double minScore, double maxScore, String decisionCode, String category) {
        this(minScore, maxScore, decisionCode, category, "", 0);
    }
}
```

- [ ] **Step 2: ScoreBandTest 追加 name/priority 用例**

在 `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/api/model/ast/ScoreBandTest.java` 末尾追加（不改已有用例，4 参兼容构造继续工作）：

```java
@Test
void nameAndPriorityDefaultsFromCompatConstructor() {
    ScoreBand band = new ScoreBand(0, 60, "REJECT", "HIGH");
    assertThat(band.name()).isEmpty();
    assertThat(band.priority()).isEqualTo(0);
}

@Test
void sixParamConstructorRetainsAllFields() {
    ScoreBand band = new ScoreBand(0, 60, "REJECT", "HIGH", "拒绝", 1);
    assertThat(band.name()).isEqualTo("拒绝");
    assertThat(band.priority()).isEqualTo(1);
    assertThat(band.decisionCode()).isEqualTo("REJECT");
    assertThat(band.category()).isEqualTo("HIGH");
}

@Test
void priorityCatchesNullFromJsonViaAsEmpty() throws Exception {
    // 旧 JSON 里没有 priority 键（存量数据兜底）：@JsonSetter(AS_EMPTY) 应兜底为 0
    String json = "{\"minScore\":0,\"maxScore\":60,\"decisionCode\":\"REJECT\",\"category\":\"HIGH\",\"name\":\"拒绝\"}";
    tools.jackson.databind.ObjectMapper mapper = tools.jackson.databind.json.JsonMapper.builder().build();
    ScoreBand band = mapper.readValue(json, ScoreBand.class);
    assertThat(band.priority()).isEqualTo(0);
    assertThat(band.name()).isEqualTo("拒绝");
}
```

- [ ] **Step 3: 更新所有 4 参 new ScoreBand() 调用点**

以下文件的 `new ScoreBand(X, Y, Z, W)` 都仍可用（兼容构造已加），**但 ScoreBandTest 里有 4 参用例要确认编译通过**（兼容构造覆盖了）。

同时更新以下已有测试里用 6 参的地方（ScorecardExecutorTest 里部分用了 4 参 new ScoreBand()，兼容构造保障，**无需改动**）。

以下文件打开确认编译不报错（4 参兼容构造覆盖）：
- `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/evaluator/ScorecardExecutorTest.java`
- `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/api/model/ast/ScorecardRootNodeTest.java`
- `rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/JacksonConfigTest.java`

Run: `$MVN -pl rule-kernel test-compile -q`
Expected: BUILD SUCCESS（兼容构造保证所有 4 参调用点不报错）

- [ ] **Step 4: 跑 kernel 全量测试**

Run: `$MVN -pl rule-kernel test -q`
Expected: BUILD SUCCESS（ScoreBandTest 含新 3 个用例绿）

- [ ] **Step 5: Commit**

```bash
git add rule-kernel/
git commit -m "feat(kernel): ScoreBand 加 name/priority（发布期回填，4 参兼容构造保向后兼容）"
```

---

## Task 2: 发布期 enrichBands 替代 mergeBandDecisionCodes

**Files:**
- Modify: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/publish/PublishService.java`
- Test: `rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/publish/PublishServiceTest.java`（追加用例）

**背景：** 当前流程（resolveAndValidate 里）：
1. `mergeBandDecisionCodes(bindings, resolvedAst)` 把 band.decisionCode 注入 decisionBindings
2. `freezeDecisionBindings(...)` 统一校验存在+回填 name/priority

新流程（评分卡分支）：
1. `enrichBands(tenantId, scRoot.bands())` 直接从 decisionDefinitionMapper 批量查回填，返回含 name/priority 的新 ScoreBand 列表
2. 用 enriched bands 重建 ScorecardRootNode 替换 resolvedAst（不可变 record，new 新实例）
3. 不再调用 `mergeBandDecisionCodes`（或调用时跳过 SCORECARD）
4. decisionBindings 对评分卡为空，`freezeDecisionBindings` 对空列表是幂等的（保留调用）

- [ ] **Step 1: 写失败测试（enrichBands 校验 + 回填）**

在 `PublishServiceTest` 末尾追加（**先打开 PublishServiceTest 看 publishScorecard/decisionsExist/weightedCond helper 是否已有，复用同款**）：

```java
@Test
void scorecardBands_decisionCodeNotFound_rejected_viaEnrichBands() {
    // bands 里引用不存在的 decision → 新 enrichBands 路径也应报错
    ScorecardRootNode ast = new ScorecardRootNode(List.of(weightedCond()), 0.0,
            List.of(new ScoreBand(0, 60, "NONEXISTENT", "HIGH")));
    assertThatThrownBy(() -> publishScorecard(ast, /* mock 返回空查询结果 */ decisionsExist()))
            .hasMessageContaining("DECISION_CODE_NOT_FOUND");
}

@Test
void scorecardBands_nameAndPriorityFilledFromDecisionDefinition() {
    // bands 里 decisionCode=REJECT → 从 decision_definition 回填 name="拒绝" priority=1
    ScorecardRootNode ast = new ScorecardRootNode(List.of(weightedCond()), 0.0,
            List.of(new ScoreBand(0, 60, "REJECT", "HIGH")));
    // decisionsExist("REJECT") mock 返回 name="拒绝", priority=1
    ResolvedDraft draft = publishScorecard(ast, decisionsExist("REJECT"));
    // resolvedAst 里的 bands[0] 应含回填的 name/priority
    ScorecardRootNode resolved = (ScorecardRootNode) draft.resolvedAst();
    assertThat(resolved.bands().get(0).name()).isEqualTo("拒绝");
    assertThat(resolved.bands().get(0).priority()).isEqualTo(1);
}
```

> **注意**：`publishScorecard` helper 已有（SB-T3 的测试），若 ResolvedDraft 不对外可见，用 mock verify / DB 插入方式验证。**先打开 PublishServiceTest 确认可用的 helper，照实际情况改断言**。

Run: `$MVN -pl rule-config-svc -am test -Dtest=PublishServiceTest -Dsurefire.failIfNoSpecifiedTests=false -q`
Expected: 新用例 FAIL（enrichBands 方法不存在）

- [ ] **Step 2: PublishService 加 `enrichBands` 方法**

在 `PublishService.java` 里加私有方法（在 `mergeBandDecisionCodes` 同区域，参考 `freezeDecisionBindings` 查 mapper 的写法）：

```java
/**
 * 从 decision_definition 批量回填 band 的 name/priority，返回重建的 ScoreBand 列表。
 * band decisionCode 不存在时抛 IllegalArgumentException(DECISION_CODE_NOT_FOUND)（与 freezeDecisionBindings 一致）。
 *
 * @param tenantId 租户 ID
 * @param bands    原始 ScoreBand 列表（name/priority 为空占位）
 * @return 含回填 name/priority 的新 ScoreBand 列表（不可变）
 */
private List<ScoreBand> enrichBands(Long tenantId, List<ScoreBand> bands) {
    if (bands.isEmpty()) return List.of();
    List<String> codes = bands.stream().map(ScoreBand::decisionCode).distinct().toList();
    Map<String, DecisionDefinition> byCode = decisionDefinitionMapper.findByCodes(tenantId, codes)
            .stream().collect(Collectors.toMap(DecisionDefinition::getCode, d -> d));
    List<ScoreBand> result = new ArrayList<>(bands.size());
    for (ScoreBand band : bands) {
        DecisionDefinition d = byCode.get(band.decisionCode());
        if (d == null) {
            throw new IllegalArgumentException("DECISION_CODE_NOT_FOUND: bands 引用的 decision 不存在: " + band.decisionCode());
        }
        int priority = d.getPriority() != null ? d.getPriority() : 0;
        result.add(new ScoreBand(band.minScore(), band.maxScore(), band.decisionCode(),
                band.category(), d.getName() != null ? d.getName() : "", priority));
    }
    return Collections.unmodifiableList(result);
}
```

> import: `java.util.ArrayList`, `java.util.Collections`, `java.util.Map`, `java.util.stream.Collectors`, `ScoreBand`, `DecisionDefinition`。

- [ ] **Step 3: resolveAndValidate 里调用 enrichBands + 重建 resolvedAst**

在 `resolveAndValidate` 里，`AstNode resolvedAst = ...` 计算完之后，在调用 `mergeBandDecisionCodes` 之前插入：

```java
// SCORECARD bands 直接回填 name/priority（不走 decisionBindings 搬运）
if (RuleKind.SCORECARD.tag().equals(kindTag) && resolvedAst instanceof ScorecardRootNode scRoot
        && !scRoot.bands().isEmpty()) {
    List<ScoreBand> enrichedBands = enrichBands(tenantId, scRoot.bands());
    resolvedAst = new ScorecardRootNode(scRoot.conditions(), scRoot.threshold(), enrichedBands);
}
```

然后把 `mergeBandDecisionCodes` 的调用改为对评分卡跳过（它对 AST_BOOLEAN 等无影响，对 SCORECARD 现在不需要注入）：

**找到这行：**
```java
List<RuleVersionSnapshot.DecisionBinding> bindingsWithBands =
        mergeBandDecisionCodes(bindings, resolvedAst);
```

**改为：**
```java
// SCORECARD 的 band decisionCode 已在 enrichBands 回填进 ScoreBand，不再注入 decisionBindings
List<RuleVersionSnapshot.DecisionBinding> bindingsWithBands =
        (RuleKind.SCORECARD.tag().equals(kindTag))
                ? bindings
                : mergeBandDecisionCodes(bindings, resolvedAst);
```

- [ ] **Step 4: 跑 config-svc 全量测试**

Run: `$MVN -pl rule-config-svc -am test -q`
Expected: BUILD SUCCESS（含原有 bands 重叠校验测试 + 新回填用例绿）

- [ ] **Step 5: Commit**

```bash
git add rule-config-svc/
git commit -m "feat(config): enrichBands 发布期直接回填 ScoreBand.name/priority，评分卡脱离 decisionBindings 搬运"
```

---

## Task 3: ScorecardExecutor 从 band 直接读 name/priority

**Files:**
- Modify: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/evaluator/ScorecardExecutor.java`
- Test: `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/evaluator/ScorecardExecutorTest.java`（追加）

- [ ] **Step 1: 追加失败测试（band 含 name/priority 时 Decision 正确）**

```java
@Test
void bandsWithNamePriority_decisionContainsNameAndPriority() {
    // band 含回填的 name/priority → Decision 应直接从 band 读，不索引 decisionBindings
    ScoreBand band = new ScoreBand(60, 100, "PASS", "LOW", "通过", 100);
    ScorecardRootNode root = new ScorecardRootNode(
            List.of(condWithWeight("income", 70.0)), 0.0, List.of(
                    new ScoreBand(0, 60, "REJECT", "HIGH", "拒绝", 1),
                    band));
    // snapshot decisionBindings 为空（评分卡发布后不再注入）
    RuleVersionSnapshot snap = scorecardSnapshotWithBindings(root, List.of());
    EvalResult r = executor.execute(snap, ctxWithCondTrue());
    assertThat(r.ruleHit()).isTrue();
    assertThat(r.score()).isEqualTo(70.0);
    assertThat(r.finalDecision().code()).isEqualTo("PASS");
    assertThat(r.finalDecision().name()).isEqualTo("通过");
    assertThat(r.finalDecision().priority()).isEqualTo(100);
    assertThat(r.category()).isEqualTo("LOW");
}
```

> **先打开 ScorecardExecutorTest** 看 `scorecardSnapshot`/`ctxWithCondTrue`/`condWithWeight` helper 是否已有；若无 `scorecardSnapshotWithBindings`（传空 bindings），照现有 helper 加一个变体：构造 `RuleVersionSnapshot` 时 decisionBindings 传 `List.of()`。

Run: `$MVN -pl rule-kernel test -Dtest=ScorecardExecutorTest -q`
Expected: 新用例 FAIL（当前代码仍从 decisionBindings 索引，bindings 空时回退 name="" priority=0）

- [ ] **Step 2: ScorecardExecutor 段命中改从 band 直接读**

找到这段并替换（约 line 100-113）：

**原代码：**
```java
        Decision decision = snapshot.decisionBindings().stream()
                .filter(b -> b.decisionCode().equals(hitBand.decisionCode()))
                .max(java.util.Comparator.comparingInt(RuleVersionSnapshot.DecisionBinding::priority))
                .map(b -> new Decision(b.decisionCode(), b.name(), b.priority(),
                        snapshot.ruleVersionId(), snapshot.code(), snapshot.version(), hitBand.category()))
                .orElseGet(() -> new Decision(hitBand.decisionCode(), "", 0,
                        snapshot.ruleVersionId(), snapshot.code(), snapshot.version(), hitBand.category()));
```

**改为：**
```java
        // ScoreBand 已含发布期回填的 name/priority，直接读，不再索引 decisionBindings（评分卡语义内聚）。
        // 旧快照（bands 无 name/priority）兜底：name="" priority=0，行为与原 orElseGet 回退一致。
        Decision decision = new Decision(hitBand.decisionCode(),
                hitBand.name() != null ? hitBand.name() : "",
                hitBand.priority(),
                snapshot.ruleVersionId(), snapshot.code(), snapshot.version(), hitBand.category());
```

- [ ] **Step 3: 跑 kernel 全量测试**

Run: `$MVN -pl rule-kernel test -q`
Expected: BUILD SUCCESS（新用例绿，原有 bands 用例不回归）

- [ ] **Step 4: Commit**

```bash
git add rule-kernel/
git commit -m "feat(kernel): ScorecardExecutor 从 band.name/priority 直读 Decision，不再索引 decisionBindings"
```

---

## Task 4: 前端 ScoreBand 加 name/priority + SCORECARD 隐藏决策绑定

**Files:**
- Modify: `frontend/src/types/ast.ts` — ScoreBand 加 name?/priority?
- Modify: `frontend/src/pages/rule-editor/RightPanel.tsx` — SCORECARD 隐藏 DecisionBindingEditor

- [ ] **Step 1: ScoreBand type 加字段**

`frontend/src/types/ast.ts` 的 ScoreBand 接口加：

```typescript
export interface ScoreBand {
  minScore: number;
  maxScore: number;
  decisionCode: string;
  category?: string | null;
  name?: string;      // 发布期回填，前端只读展示
  priority?: number;  // 发布期回填，前端只读展示
}
```

- [ ] **Step 2: RightPanel 对 SCORECARD 隐藏 DecisionBindingEditor**

**先打开 `frontend/src/pages/rule-editor/RightPanel.tsx`** 看 DecisionBindingEditor 渲染的上下文（`ruleDetail.kind` 或 `kind` 来自 store），找到 DecisionBindingEditor 的渲染块，在外层加条件：

```tsx
{/* 评分卡的决策由 bands 内联，不需要单独配置绑定 */}
{ruleDetail.kind !== 'SCORECARD' && (
  <DecisionBindingEditor
    value={decisionBindings}
    ...
  />
)}
```

> 打开文件看具体 props，照实际改，只加 `kind !== 'SCORECARD'` 条件，不改其它。

- [ ] **Step 3: 构建**

Run（frontend）：`npm run build`
Expected: 通过（tsc + vite，ScoreBand 字段可选，不影响现有用法）

- [ ] **Step 4: Commit**

```bash
git add frontend/src/types/ast.ts frontend/src/pages/rule-editor/RightPanel.tsx
git commit -m "feat(frontend): ScoreBand 加 name/priority 类型 + SCORECARD 隐藏决策绑定面板"
```

---

## Task 5: 全量 clean test

**Files:** 无新增

- [ ] **Step 1: 全量 clean test**

Run: `$MVN clean test`
Expected: BUILD SUCCESS，全模块绿（含 ScoreBandTest、ScorecardExecutorTest、PublishServiceTest、JacksonConfigTest + 所有回归）

- [ ] **Step 2: 前端构建**

Run（frontend）：`npm run build`
Expected: 通过

- [ ] **Step 3: 可选 e2e（起服务）**

起真实服务，发布一条评分卡（bands 含 REJECT/REVIEW/PASS），评估三档分数，验证：
- `finalDecision.name` 返回"拒绝"/"人工审核"/"通过"
- `finalDecision.priority` 返回正确值（1/2/100）
- `decision_bindings` DB 字段对评分卡为 `[]`（不再注入）

---

## Self-Review 记录

- **Spec 覆盖**：ScoreBand 加字段(T1)、发布期回填(T2)、executor 直读(T3)、前端隐藏面板(T4)、存量数据兼容(T1 兼容构造+@JsonSetter)、全量测试(T5)——全覆盖。
- **Placeholder 扫描**：T2 Step 1 的断言方式注明"打开 PublishServiceTest 确认 helper"——因为 publishScorecard/ResolvedDraft 是否对测试可见取决于现有测试结构，执行者需先核实。
- **类型一致性**：`ScoreBand(min,max,code,cat,name,priority)` 6 参在 T1 定义，T2/T3 用同款一致。`enrichBands` 返回 `List<ScoreBand>`（含 name/priority），T3 executor 读 `hitBand.name()/priority()`——对齐。4 参兼容构造 T1 定义，T2 发布前的输入用 4 参，T3 测试用 6 参直接构造——均一致。
- **风险**：priority 是 primitive int，旧 JSON 缺键靠 `@JsonSetter(AS_EMPTY)` 兜底 0，T1 Step 2 有专门测试守护——已覆盖。T2 enrichBands 批量查用 `findByCodes` 已确认 mapper 有此方法。
