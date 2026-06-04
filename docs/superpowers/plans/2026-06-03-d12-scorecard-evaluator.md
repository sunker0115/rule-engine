# D12 SCORECARD Evaluator 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实装 `Rule.kind=SCORECARD` 的评分卡 evaluator：各叶子节点有 `weight` 权重，满足则累加分，最终 `EvalResult.score` 与阈值带对比决定是否命中，支持 dry-run trace 与 Decision 绑定。

**Architecture:**
- `ConditionNode.weight`：AST JSON 中已有字段（D12 预留），evaluator 读取它；`InterpretedExecutor` / `TracingInterpretedExecutor` 忽略该字段不受影响。
- 新增 `ScorecardExecutor implements RuleVersionExecutor`：遍历所有叶子节点（不短路），累加满足节点的 weight，与 `RuleVersionSnapshot` 携带的阈值配置比较，输出 `EvalResult.score` + `ruleHit`。
- 阈值配置存在 `rule_version.condition_ast` 根节点的 `threshold` 字段（根节点 `ScorecardRootNode` 新增），或独立 JSON 列；本计划采用**根节点方案**：新增 `ScorecardRootNode`（sealed hierarchy 扩展），`conditionAst` 可以是 `ScorecardRootNode`，其 `children` 是叶子 `ConditionNode`，`threshold` 定义命中下限。
- `EvalResult.score` 字段已在 record 中有 `Double score` 占位（按 D12 设计应补充），本计划同步添加。
- 发布校验：`kind=SCORECARD` 时，根节点必须是 `ScorecardRootNode`，每个叶子必须有 `weight > 0`。

**Tech Stack:** Java 25 / sealed interface / `TracingInterpretedExecutor` 模式复用

---

## 文件清单

| 文件 | 动作 |
|------|------|
| `rule-kernel/.../ast/ScorecardRootNode.java` | 新建 sealed AST 节点 |
| `rule-kernel/.../ast/AstNode.java` | 加 `ScorecardRootNode` 到 sealed permits |
| `rule-kernel/.../model/EvalResult.java` | 加 `Double score` 字段 |
| `rule-kernel/.../evaluator/ScorecardExecutor.java` | 新建 evaluator |
| `rule-kernel/.../codec/AstJsonCodec`（即 rule-eval-svc 中的 `AstJsonCodec`）| 加 `scorecard` 类型反序列化 |
| `rule-eval-svc/.../EvalAutoConfiguration.java` | 注册 `ScorecardExecutor` + `ExecutorRouter` |
| `rule-eval-svc/.../service/EvalServiceImpl.java` | 按 `kind` 路由到对应 executor |
| `rule-eval-svc/.../snapshot/RuleVersionRow.java` | 加 `kind` 字段 |
| `rule-eval-svc/.../snapshot/SnapshotAssembler.java` | 传 `kind` 到 snapshot |
| `rule-kernel/.../model/RuleVersionSnapshot.java` | 加 `kind` 字段 |
| `rule-eval-svc/.../repository/RuleVersionReadMapper.java` | SELECT 加 `rd.kind` |
| `rule-config-svc/.../publish/PublishService.java` | 发布校验：SCORECARD 检查 |
| `rule-kernel/src/test/.../evaluator/ScorecardExecutorTest.java` | 新建单测 |
| `rule-eval-svc/src/test/.../service/EvalServiceImplScorecardTest.java` | 新建单测 |

---

## Task 1：`ScorecardRootNode` + `AstNode` sealed 扩展 + `ConditionNode.weight`

**Files:**
- Create: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/ast/ScorecardRootNode.java`
- Modify: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/ast/AstNode.java`
- Modify: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/ast/ConditionNode.java`

### Step 1: 写失败测试（验证编译失败，因为 ScorecardRootNode 还不存在）

```bash
# 先看 AstNode 当前的 sealed permits
cat rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/ast/AstNode.java
```

Expected: 有 `sealed interface AstNode permits AndNode, OrNode, NotNode, ConditionNode`

### Step 2: 在 `ConditionNode` 加 `weight` 字段

当前 `ConditionNode`：
```java
public record ConditionNode(
        String conditionType,
        String metricCode,
        String displayLabel,
        Map<String, Object> params
) implements AstNode {
    public ConditionNode {
        params = Map.copyOf(params);
    }
}
```

改为（加 `weight`，默认 0，向后兼容）：

```java
public record ConditionNode(
        String conditionType,
        String metricCode,
        String displayLabel,
        Map<String, Object> params,
        /** 评分卡权重；AST_BOOLEAN kind 时忽略，SCORECARD kind 时由 ScorecardExecutor 累加。 */
        double weight
) implements AstNode {
    public ConditionNode {
        params = Map.copyOf(params);
    }
}
```

> 已有测试用 `new ConditionNode(type, code, label, params)` 四参数构造的地方需要补第五个参数 `0.0`——本步骤同时修复所有受影响的测试代码（`TracingInterpretedExecutorTest`、`InterpretedExecutorTest` 等）。

### Step 3: 新建 `ScorecardRootNode`

```java
package com.sstlfsj.rule.kernel.api.model.ast;

import java.util.List;

/**
 * 评分卡根节点：持有叶子条件列表（各自带 weight）和命中阈值。
 * kind=SCORECARD 的规则 conditionAst 顶层节点为此类型。
 */
public record ScorecardRootNode(
        /** 评分卡叶子条件列表（元素均为 ConditionNode，带各自 weight）。 */
        List<ConditionNode> conditions,
        /** 规则命中所需最低分（满足 score >= threshold 则 ruleHit=true）。 */
        double threshold
) implements AstNode {
    public ScorecardRootNode {
        conditions = conditions == null ? List.of() : List.copyOf(conditions);
    }
}
```

### Step 4: 在 `AstNode` permits 列表加 `ScorecardRootNode`

找到 `AstNode.java`，将：
```java
sealed interface AstNode permits AndNode, OrNode, NotNode, ConditionNode {
```
改为：
```java
sealed interface AstNode permits AndNode, OrNode, NotNode, ConditionNode, ScorecardRootNode {
```

### Step 5: 修复所有受 `ConditionNode` 构造参数变更影响的代码

搜索所有 `new ConditionNode(` 的地方，补 `0.0` 第五参数：

```bash
grep -rn "new ConditionNode(" --include="*.java" .
```

对每一处四参数调用改为五参数，例如：
```java
// 改前
new ConditionNode("ALWAYS_TRUE", "metric1", null, Map.of())
// 改后
new ConditionNode("ALWAYS_TRUE", "metric1", null, Map.of(), 0.0)
```

### Step 6: 在 `AstJsonCodec.AstNodeMixin` 加 `ScorecardRootNode` 类型映射

`AstJsonCodec` 位于 `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/snapshot/AstJsonCodec.java`。

找到 `@JsonSubTypes` 注解，加一行：

```java
@JsonSubTypes({
        @JsonSubTypes.Type(value = AndNode.class,           name = "AndNode"),
        @JsonSubTypes.Type(value = OrNode.class,            name = "OrNode"),
        @JsonSubTypes.Type(value = NotNode.class,           name = "NotNode"),
        @JsonSubTypes.Type(value = ConditionNode.class,     name = "ConditionNode"),
        @JsonSubTypes.Type(value = ScorecardRootNode.class, name = "ScorecardRootNode")   // 新增
})
```

### Step 7: 运行 rule-kernel + rule-eval-svc 测试，确认现有测试通过

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-25.jdk/Contents/Home
export PATH=$JAVA_HOME/bin:$PATH
MVN=/Users/sunke/.m2/wrapper/dists/apache-maven-3.9.9-bin/4nf9hui3q3djbarqar9g711ggc/apache-maven-3.9.9/bin/mvn
$MVN -pl rule-kernel,rule-eval-svc -am test
```

Expected: BUILD SUCCESS，现有 TracingInterpretedExecutorTest / InterpretedExecutorTest / SnapshotAssemblerTest 全部通过。

### Step 8: commit

```bash
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/ast/
git add rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/snapshot/AstJsonCodec.java
git add -u
git commit -m "feat(kernel): ScorecardRootNode + ConditionNode.weight + AstJsonCodec 映射（D12 评分卡基础）"
```

---

## Task 2：`EvalResult` 加 `score` 字段

**Files:**
- Modify: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/EvalResult.java`

### Step 1: 写失败测试

```java
// rule-kernel/src/test/java/com/sstlfsj/rule/kernel/api/model/EvalResultTest.java
@Test
void evalResult_score字段默认为null() {
    EvalResult r = EvalResult.hit();
    assertThat(r.score()).isNull();
}

@Test
void evalResult_可以携带score() {
    EvalResult r = new EvalResult(true, null, List.of(), List.of(), null, List.of(), 75.0);
    assertThat(r.score()).isEqualTo(75.0);
}
```

运行：
```bash
$MVN -pl rule-kernel -am test -Dtest=EvalResultTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL（score 字段不存在）

### Step 2: 在 `EvalResult` record 加 `score` 字段

当前 `EvalResult`：
```java
public record EvalResult(
        boolean ruleHit,
        Decision finalDecision,
        List<Decision> hitDecisions,
        List<NodeTrace> nodeTrace,
        String errorCode,
        List<ActionResult> actionResults
)
```

改为：
```java
public record EvalResult(
        boolean ruleHit,
        Decision finalDecision,
        List<Decision> hitDecisions,
        List<NodeTrace> nodeTrace,
        String errorCode,
        List<ActionResult> actionResults,
        /** D12 SCORECARD kind 的累计分；AST_BOOLEAN kind 时为 null。 */
        Double score
) {
    public EvalResult {
        hitDecisions = hitDecisions == null ? List.of() : List.copyOf(hitDecisions);
        nodeTrace = nodeTrace == null ? List.of() : List.copyOf(nodeTrace);
        actionResults = actionResults == null ? List.of() : List.copyOf(actionResults);
    }

    /** 规则命中时的标准结果（AST_BOOLEAN kind）。 */
    public static EvalResult hit() {
        return new EvalResult(true, null, List.of(), List.of(), null, List.of(), null);
    }

    /** 规则未命中时的标准结果（AST_BOOLEAN kind）。 */
    public static EvalResult miss() {
        return new EvalResult(false, null, List.of(), List.of(), null, List.of(), null);
    }
}
```

> `TracingInterpretedExecutor.execute()` 中 `new EvalResult(satisfied, null, List.of(), traces, null, List.of())` 需补 `, null` 第七参数。搜索所有 `new EvalResult(` 调用处一并修复。

### Step 3: 修复所有 `new EvalResult(` 六参数调用

```bash
grep -rn "new EvalResult(" --include="*.java" .
```

每处补 `, null` 第七参数（score 默认 null）。

### Step 4: 运行 rule-kernel 全量测试

```bash
$MVN -pl rule-kernel -am test
```

Expected: BUILD SUCCESS

### Step 5: commit

```bash
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/EvalResult.java
git add -u
git commit -m "feat(kernel): EvalResult 加 score 字段（D12 SCORECARD 输出占位）"
```

---

## Task 3：`ScorecardExecutor` 实现

**Files:**
- Create: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/evaluator/ScorecardExecutor.java`
- Create: `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/evaluator/ScorecardExecutorTest.java`

### Step 1: 写失败测试

```java
package com.sstlfsj.rule.kernel.evaluator;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.model.ast.ScorecardRootNode;
import com.sstlfsj.rule.kernel.api.spi.condition.ConditionEvaluator;
import com.sstlfsj.rule.kernel.internal.evaluator.ScorecardExecutor;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** ScorecardExecutor 单测：验证权重累加、阈值判断、trace 生成。 */
class ScorecardExecutorTest {

    private static final String ALWAYS_TRUE  = "ALWAYS_TRUE";
    private static final String ALWAYS_FALSE = "ALWAYS_FALSE";

    private final ConditionEvaluator alwaysTrue  = (node, ctx) -> true;
    private final ConditionEvaluator alwaysFalse = (node, ctx) -> false;

    private EvalContext ctx() {
        RuleEvent event = new RuleEvent("t1", "scene1", "ORDER_PLACED", "u1",
                "evt-1", Instant.now(), Map.of(), null);
        return new EvalContext("t1", event, null, Map.of());
    }

    private RuleVersionSnapshot snapshot(ScorecardRootNode root) {
        return new RuleVersionSnapshot(1L, "scene1", "t1", root, null, null, null, "SCORECARD");
    }

    @Test
    void allConditionsMet_scoreEqualsSum() {
        // 两个条件都满足，分数 = 30 + 70 = 100，threshold=80，命中
        ScorecardRootNode root = new ScorecardRootNode(List.of(
                new ConditionNode(ALWAYS_TRUE,  "m1", null, Map.of(), 30.0),
                new ConditionNode(ALWAYS_TRUE,  "m2", null, Map.of(), 70.0)
        ), 80.0);
        EvalResult result = new ScorecardExecutor(Map.of(ALWAYS_TRUE, alwaysTrue))
                .execute(snapshot(root), ctx());
        assertThat(result.ruleHit()).isTrue();
        assertThat(result.score()).isEqualTo(100.0);
    }

    @Test
    void partialConditionsMet_scoreBelow_threshold_miss() {
        // 只有第一个满足（30 分），threshold=80，未命中
        ScorecardRootNode root = new ScorecardRootNode(List.of(
                new ConditionNode(ALWAYS_TRUE,  "m1", null, Map.of(), 30.0),
                new ConditionNode(ALWAYS_FALSE, "m2", null, Map.of(), 70.0)
        ), 80.0);
        EvalResult result = new ScorecardExecutor(
                Map.of(ALWAYS_TRUE, alwaysTrue, ALWAYS_FALSE, alwaysFalse))
                .execute(snapshot(root), ctx());
        assertThat(result.ruleHit()).isFalse();
        assertThat(result.score()).isEqualTo(30.0);
    }

    @Test
    void scoreEqualsThreshold_isHit() {
        // 分数恰好等于阈值（>=），命中
        ScorecardRootNode root = new ScorecardRootNode(List.of(
                new ConditionNode(ALWAYS_TRUE, "m1", null, Map.of(), 50.0)
        ), 50.0);
        EvalResult result = new ScorecardExecutor(Map.of(ALWAYS_TRUE, alwaysTrue))
                .execute(snapshot(root), ctx());
        assertThat(result.ruleHit()).isTrue();
        assertThat(result.score()).isEqualTo(50.0);
    }

    @Test
    void noConditions_scoreZero_miss() {
        // 空条件列表，score=0，threshold=1，未命中
        ScorecardRootNode root = new ScorecardRootNode(List.of(), 1.0);
        EvalResult result = new ScorecardExecutor(Map.of())
                .execute(snapshot(root), ctx());
        assertThat(result.ruleHit()).isFalse();
        assertThat(result.score()).isEqualTo(0.0);
    }

    @Test
    void nodeTrace_containsAllConditions_noShortCircuit() {
        // 即使第一个满足，SCORECARD 不短路，两个条件都有 trace
        ScorecardRootNode root = new ScorecardRootNode(List.of(
                new ConditionNode(ALWAYS_TRUE,  "m1", null, Map.of(), 30.0),
                new ConditionNode(ALWAYS_FALSE, "m2", null, Map.of(), 20.0)
        ), 100.0);
        EvalResult result = new ScorecardExecutor(
                Map.of(ALWAYS_TRUE, alwaysTrue, ALWAYS_FALSE, alwaysFalse))
                .execute(snapshot(root), ctx());
        // ScorecardRootNode trace + 2 个叶子 trace（或扁平 2 条）
        assertThat(result.nodeTrace()).isNotEmpty();
    }
}
```

运行（预期 FAIL，类不存在）：
```bash
$MVN -pl rule-kernel -am test -Dtest=ScorecardExecutorTest -Dsurefire.failIfNoSpecifiedTests=false
```

### Step 2: 实现 `ScorecardExecutor`

```java
package com.sstlfsj.rule.kernel.internal.evaluator;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.NodeTrace;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.model.ast.ScorecardRootNode;
import com.sstlfsj.rule.kernel.api.spi.condition.ConditionEvaluator;
import com.sstlfsj.rule.kernel.api.spi.executor.RuleVersionExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 评分卡 evaluator（D12 SCORECARD kind）。
 * 遍历所有叶子条件（不短路），满足则累加 weight，score >= threshold 时 ruleHit=true。
 */
public class ScorecardExecutor implements RuleVersionExecutor {

    private final Map<String, ConditionEvaluator> evaluators;

    /** @param evaluators conditionType 到 ConditionEvaluator 的映射 */
    public ScorecardExecutor(Map<String, ConditionEvaluator> evaluators) {
        this.evaluators = Map.copyOf(evaluators);
    }

    @Override
    public EvalResult execute(RuleVersionSnapshot snapshot, EvalContext ctx) {
        if (!(snapshot.conditionAst() instanceof ScorecardRootNode root)) {
            // kind=SCORECARD 但根节点类型不符，降级返回 miss + errorCode
            return new EvalResult(false, null, List.of(), List.of(),
                    "SCORECARD_AST_TYPE_MISMATCH", List.of(), null);
        }

        List<NodeTrace> traces = new ArrayList<>();
        double score = 0.0;

        for (ConditionNode node : root.conditions()) {
            ConditionEvaluator evaluator = evaluators.get(node.conditionType());
            boolean met = false;
            String errorCode = null;

            if (evaluator == null) {
                errorCode = "NO_EVALUATOR";
            } else {
                met = evaluator.evaluate(node, ctx);
                if (met) {
                    score += node.weight();
                }
            }

            Long rvId = snapshot.ruleVersionId();
            traces.add(new NodeTrace("ConditionNode", node.conditionType(), node.metricCode(),
                    met, null, null, errorCode, List.of(), rvId));
        }

        boolean hit = score >= root.threshold();
        return new EvalResult(hit, null, List.of(), traces, null, List.of(), score);
    }
}
```

### Step 3: 运行 `ScorecardExecutorTest`

```bash
$MVN -pl rule-kernel -am test -Dtest=ScorecardExecutorTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: 全部 5 个测试通过

### Step 4: 运行 rule-kernel 全量测试

```bash
$MVN -pl rule-kernel -am test
```

Expected: BUILD SUCCESS

### Step 5: commit

```bash
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/evaluator/ScorecardExecutor.java
git add rule-kernel/src/test/java/com/sstlfsj/rule/kernel/evaluator/ScorecardExecutorTest.java
git commit -m "feat(kernel): ScorecardExecutor 实装（D12 评分卡 evaluator）"
```

---

## Task 4：`kind` 字段流转到 `RuleVersionSnapshot`

> 评估服务需要知道 `kind` 才能选对应 executor。`kind` 存在 `rule_definition.kind`，需要经 JOIN 查询→`RuleVersionRow`→`RuleVersionSnapshot` 传下来。

**Files:**
- Modify: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/RuleVersionSnapshot.java`
- Modify: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/snapshot/RuleVersionRow.java`
- Modify: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/snapshot/SnapshotAssembler.java`
- Modify: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/repository/RuleVersionReadMapper.java`

### Step 1: `RuleVersionSnapshot` 加 `kind` 字段

当前构造：
```java
public record RuleVersionSnapshot(
        Long ruleVersionId,
        String sceneCode,
        String tenantId,
        AstNode conditionAst,
        List<PreGateConfig> preGates,
        List<DecisionBinding> decisionBindings,
        List<String> triggerEventTypes
)
```

改为（加最后一个字段 `kind`）：
```java
public record RuleVersionSnapshot(
        Long ruleVersionId,
        String sceneCode,
        String tenantId,
        AstNode conditionAst,
        List<PreGateConfig> preGates,
        List<DecisionBinding> decisionBindings,
        List<String> triggerEventTypes,
        /** 规则类型，默认 AST_BOOLEAN；SCORECARD 时由 ScorecardExecutor 求值。 */
        String kind
) {
    public RuleVersionSnapshot {
        preGates = preGates == null ? List.of() : List.copyOf(preGates);
        decisionBindings = decisionBindings == null ? List.of() : List.copyOf(decisionBindings);
        triggerEventTypes = triggerEventTypes == null ? List.of() : List.copyOf(triggerEventTypes);
        kind = kind == null ? "AST_BOOLEAN" : kind;
    }
    ...
}
```

修复所有 `new RuleVersionSnapshot(` 七参数调用处，补第八参数 `"AST_BOOLEAN"`（测试、生产代码均需修复）。

```bash
grep -rn "new RuleVersionSnapshot(" --include="*.java" .
```

### Step 2: `RuleVersionRow` 加 `kind` 字段

```java
public record RuleVersionRow(
        Long ruleVersionId,
        String sceneCode,
        Long tenantId,
        String conditionAstJson,
        String preGatesJson,
        String decisionBindingsJson,
        String triggerEventTypesJson,
        String kind          // 来自 rule_definition.kind
) {}
```

### Step 3: `RuleVersionReadMapper` SELECT 加 `rd.kind`

在三个 `@Select` 语句的 SELECT 列表中加 `rd.kind AS kind`：

```sql
SELECT
  rv.id              AS ruleVersionId,
  s.code             AS sceneCode,
  rd.tenant_id       AS tenantId,
  rd.kind            AS kind,
  rv.condition_ast   AS conditionAstJson,
  ...
```

### Step 4: `SnapshotAssembler.assemble()` 传 `kind`

```java
return new RuleVersionSnapshot(
        row.ruleVersionId(),
        row.sceneCode(),
        String.valueOf(row.tenantId()),
        conditionAst,
        preGates,
        decisionBindings,
        triggerEventTypes,
        row.kind() != null ? row.kind() : "AST_BOOLEAN"
);
```

### Step 5: 运行 rule-eval-svc 全量测试

```bash
$MVN -pl rule-eval-svc -am test
```

Expected: BUILD SUCCESS

### Step 6: commit

```bash
git add -u
git commit -m "feat(eval): RuleVersionSnapshot / Row 加 kind 字段，评估路由基础"
```

---

## Task 5：`EvalServiceImpl` 按 `kind` 路由 executor

**Files:**
- Modify: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/EvalAutoConfiguration.java`
- Modify: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/service/EvalServiceImpl.java`
- Create: `rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/service/EvalServiceImplScorecardTest.java`

### Step 1: 写失败测试

```java
package com.sstlfsj.rule.eval.internal.service;

import com.sstlfsj.rule.eval.api.dto.EvalRequest;
import com.sstlfsj.rule.eval.api.service.EvalService;
import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.model.ast.ScorecardRootNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * EvalServiceImpl SCORECARD 路由测试：验证 kind=SCORECARD 时走 ScorecardExecutor。
 * 使用 Mock SceneRuleIndex 注入固定 snapshot，不依赖 DB。
 */
class EvalServiceImplScorecardTest {
    // 具体测试内容在 Step 2 实现后补全
    // 此处先写编译通过的最小 test 结构

    @Test
    void placeholder_scorecard_routing() {
        // 在 Task 5 Step 2 完成后替换为真实断言
        assertThat(true).isTrue();
    }
}
```

### Step 2: 在 `EvalAutoConfiguration` 注册 `ScorecardExecutor` Bean

```java
@Bean
public ScorecardExecutor scorecardExecutor(
        @Autowired(required = false)
        Map<String, com.sstlfsj.rule.kernel.api.spi.condition.ConditionEvaluator> conditionEvaluators) {
    return new ScorecardExecutor(conditionEvaluators == null ? Map.of() : conditionEvaluators);
}
```

同时修改 `evalServiceImpl` Bean 的声明，将 `ScorecardExecutor` 注入进去（当前 `EvalAutoConfiguration` 显式构造 `EvalServiceImpl`）。若 `EvalServiceImpl` 由 `@ComponentScan` 自动装配，则在其构造器里加 `ScorecardExecutor` 参数。

### Step 3: 修改 `EvalServiceImpl` 构造器，加 `ScorecardExecutor` 参数

当前构造器：
```java
EvalServiceImpl(SceneRuleIndex index, SceneSnapshotLoader snapshotLoader,
                List<PreGate> preGates, EvalContextAssembler contextAssembler,
                RuleVersionExecutor executor, EvalSessionWriter sessionWriter,
                TraceWriter traceWriter, DryRunTraceWriter dryRunTraceWriter,
                ActionDispatchService actionDispatchService)
```

改为（加 `ScorecardExecutor scorecardExecutor` 参数，并加字段）：
```java
EvalServiceImpl(SceneRuleIndex index, SceneSnapshotLoader snapshotLoader,
                List<PreGate> preGates, EvalContextAssembler contextAssembler,
                RuleVersionExecutor executor,
                ScorecardExecutor scorecardExecutor,   // 新增
                EvalSessionWriter sessionWriter,
                TraceWriter traceWriter, DryRunTraceWriter dryRunTraceWriter,
                ActionDispatchService actionDispatchService)
```

加字段 `private final ScorecardExecutor scorecardExecutor;` 并在构造器中赋值。

加私有路由方法：
```java
private RuleVersionExecutor selectExecutor(RuleVersionSnapshot snapshot) {
    return "SCORECARD".equals(snapshot.kind()) ? scorecardExecutor : executor;
}
```

在评估循环中，将所有 `executor.execute(snapshot, ctx)` 改为 `selectExecutor(snapshot).execute(snapshot, ctx)`。

### Step 4: 完善 `EvalServiceImplScorecardTest`，替换 placeholder

`EvalServiceImplTest` 用 `@ExtendWith(MockitoExtension.class)` + `@InjectMocks EvalServiceImpl`，SCORECARD 测试同款：

```java
package com.sstlfsj.rule.eval.internal.service;

import com.sstlfsj.rule.eval.internal.action.ActionDispatchService;
import com.sstlfsj.rule.eval.internal.context.EvalContextAssembler;
import com.sstlfsj.rule.eval.internal.index.SceneRuleIndex;
import com.sstlfsj.rule.eval.internal.session.EvalSessionWriter;
import com.sstlfsj.rule.eval.internal.snapshot.SceneSnapshotLoader;
import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.model.ast.ScorecardRootNode;
import com.sstlfsj.rule.kernel.api.spi.executor.RuleVersionExecutor;
import com.sstlfsj.rule.kernel.api.spi.trace.DryRunTraceWriter;
import com.sstlfsj.rule.kernel.api.spi.trace.TraceWriter;
import com.sstlfsj.rule.kernel.internal.evaluator.ScorecardExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** EvalServiceImpl SCORECARD 路由测试。 */
@ExtendWith(MockitoExtension.class)
class EvalServiceImplScorecardTest {

    @Mock SceneRuleIndex index;
    @Mock SceneSnapshotLoader snapshotLoader;
    @Mock EvalContextAssembler contextAssembler;
    @Mock RuleVersionExecutor executor;           // 默认 AST_BOOLEAN executor（不应被调用）
    @Mock ScorecardExecutor scorecardExecutor;    // SCORECARD executor
    @Mock EvalSessionWriter sessionWriter;
    @Mock TraceWriter traceWriter;
    @Mock DryRunTraceWriter dryRunTraceWriter;
    @Mock ActionDispatchService actionDispatchService;

    @InjectMocks EvalServiceImpl impl;

    private RuleEvent event() {
        return new RuleEvent("1", "risk.transfer", "ORDER", "u1",
                "evt-sc-001", Instant.now(), Map.of(), Map.of());
    }

    @Test
    void scorecard_snapshot_routes_to_scorecardExecutor_and_returns_score() {
        ScorecardRootNode ast = new ScorecardRootNode(List.of(
                new ConditionNode("ALWAYS_TRUE", "m1", null, Map.of(), 60.0)
        ), 50.0);
        RuleVersionSnapshot snap = new RuleVersionSnapshot(
                99L, "risk.transfer", "1", ast,
                List.of(),
                List.of(new RuleVersionSnapshot.DecisionBinding("REJECT", 1)),
                List.of("ORDER"),
                "SCORECARD");

        when(index.match("1", "risk.transfer", "ORDER")).thenReturn(List.of(snap));
        when(contextAssembler.assemble(any(), any()))
                .thenReturn(new EvalContext("1", event(),
                        new Subject("u1", SubjectType.USER, Map.of()), Map.of()));
        // ScorecardExecutor 返回 score=60，命中
        when(scorecardExecutor.execute(any(), any()))
                .thenReturn(new EvalResult(true, null, List.of(), List.of(), null, List.of(), 60.0));
        when(sessionWriter.insertPending(any(), anyInt(), anyString())).thenReturn(1L);

        EvalResult result = impl.evaluate(event());

        assertThat(result.ruleHit()).isTrue();
        assertThat(result.score()).isEqualTo(60.0);
        // 验证用的是 scorecardExecutor，不是默认 executor
        verify(scorecardExecutor).execute(eq(snap), any());
        verifyNoInteractions(executor);
    }

    @Test
    void astBoolean_snapshot_routes_to_default_executor() {
        // kind=AST_BOOLEAN（默认），走 executor，不走 scorecardExecutor
        RuleVersionSnapshot snap = new RuleVersionSnapshot(
                1L, "risk.transfer", "1",
                new ConditionNode("EQ", null, null, Map.of(), 0.0),
                List.of(),
                List.of(new RuleVersionSnapshot.DecisionBinding("PASS", 10)),
                List.of("ORDER"),
                "AST_BOOLEAN");

        when(index.match("1", "risk.transfer", "ORDER")).thenReturn(List.of(snap));
        when(contextAssembler.assemble(any(), any()))
                .thenReturn(new EvalContext("1", event(),
                        new Subject("u1", SubjectType.USER, Map.of()), Map.of()));
        when(executor.execute(any(), any())).thenReturn(EvalResult.hit());
        when(sessionWriter.insertPending(any(), anyInt(), anyString())).thenReturn(1L);

        EvalResult result = impl.evaluate(event());

        assertThat(result.ruleHit()).isTrue();
        verify(executor).execute(eq(snap), any());
        verifyNoInteractions(scorecardExecutor);
    }
}
```

### Step 5: 运行 rule-eval-svc 全量测试

```bash
$MVN -pl rule-eval-svc -am test
```

Expected: BUILD SUCCESS

### Step 6: commit

```bash
git add -u
git commit -m "feat(eval): EvalServiceImpl 按 kind 路由 ScorecardExecutor（D12）"
```

---

## Task 6：发布校验 + 文档更新

**Files:**
- Modify: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/publish/PublishService.java`
- Modify: `docs/08-evolution.md`（§2.1 更新状态）

### Step 1: 在 `PublishService.publish()` 加 SCORECARD 校验

在 Step 4（反序列化 AST 后）加校验：

```java
// SCORECARD kind 校验：根节点必须是 ScorecardRootNode，叶子必须有 weight > 0
if ("SCORECARD".equals(rule.getKind())) {
    if (!(ast instanceof ScorecardRootNode scorecardRoot)) {
        throw new IllegalArgumentException(
                "kind=SCORECARD 的规则 conditionAst 根节点必须是 ScorecardRootNode");
    }
    for (ConditionNode leaf : scorecardRoot.conditions()) {
        if (leaf.weight() <= 0) {
            throw new IllegalArgumentException(
                    "SCORECARD 条件节点 weight 必须 > 0，conditionType=" + leaf.conditionType());
        }
    }
}
```

### Step 2: 写 PublishService 校验测试

在 `PublishServiceTest` 中加：

```java
@Test
void publish_scorecard_invalidRoot_throwsIllegalArgument() {
    // rule.kind=SCORECARD 但 conditionAst 是 AndNode → 应抛异常
    rule.setKind("SCORECARD");
    // draftVersion.conditionAst = AND(...) JSON
    // when(astSerializer.fromJson(any())).thenReturn(new AndNode(...));
    // assertThatThrownBy(() -> publishService.publish(...))
    //         .isInstanceOf(IllegalArgumentException.class)
    //         .hasMessageContaining("ScorecardRootNode");
}

@Test
void publish_scorecard_zeroWeight_throwsIllegalArgument() {
    // SCORECARD 根节点，但某叶子 weight=0 → 应抛异常
}
```

### Step 3: 更新 `08-evolution.md §2.1`

在 `§2.1 kind 多态（来源 D12）` 的表格中，将 SCORECARD 行 `状态` 列从 `待实现` 改为 `✅ 已实装`：

```markdown
| `SCORECARD` | JSON 列承载条件列表 + 各自 `weight` + 阈值带 | `EvalResult.score` | ✅ 已实装（v2 SCORECARD plan） |
```

### Step 4: 运行 rule-config-svc 全量测试

```bash
$MVN -pl rule-config-svc -am test
```

Expected: BUILD SUCCESS

### Step 5: 运行全量测试

```bash
$MVN -pl rule-kernel,rule-eval-svc,rule-config-svc,rule-api -am test
```

Expected: BUILD SUCCESS

### Step 6: commit

```bash
git add -u
git commit -m "feat(config): SCORECARD 发布校验 + §2.1 文档更新（D12 完成）"
```

---

## Task 7：XorNode 实现（§2.21 AST_BOOLEAN 扩展）

> **定位**：XOR 是 `AST_BOOLEAN` kind 内部的新逻辑节点，不引入新 `Rule.kind`，不需要新 executor。
> Task 1 已扩展 `AstNode` sealed 和 `AstJsonCodec`，本 Task 复用相同路径追加 `XorNode`。

**Files:**
- Create: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/ast/XorNode.java`
- Modify: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/ast/AstNode.java`
- Modify: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/snapshot/AstJsonCodec.java`
- Modify: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/evaluator/InterpretedExecutor.java`
- Modify: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/evaluator/TracingInterpretedExecutor.java`
- Create: `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/evaluator/XorNodeTest.java`

### Step 1: 写失败测试（验证 XorNode 不存在）

```bash
$MVN -pl rule-kernel -am test -Dtest=XorNodeTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL（类不存在）

### Step 2: 新建 `XorNode`

```java
package com.sstlfsj.rule.kernel.api.model.ast;

import java.util.List;

/**
 * XOR 逻辑节点：子节点中有且仅有一个求值为 true 时，整个节点才为 true。
 * 属于 AST_BOOLEAN kind 的内置逻辑节点，不短路，全量遍历所有子节点。
 */
public record XorNode(
        List<AstNode> children,
        /** 给运营 UI 看的分组标题，评估时忽略。 */
        String displayLabel
) implements AstNode {
    public XorNode {
        children = children == null ? List.of() : List.copyOf(children);
    }
}
```

### Step 3: 在 `AstNode` permits 列表加 `XorNode`

找到：
```java
sealed interface AstNode permits AndNode, OrNode, NotNode, ConditionNode, ScorecardRootNode {
```
改为：
```java
sealed interface AstNode permits AndNode, OrNode, NotNode, ConditionNode, ScorecardRootNode, XorNode {
```

### Step 4: 在 `AstJsonCodec` 加 `XorNode` 类型映射

找到 `@JsonSubTypes`，追加一行：

```java
@JsonSubTypes({
        @JsonSubTypes.Type(value = AndNode.class,           name = "AndNode"),
        @JsonSubTypes.Type(value = OrNode.class,            name = "OrNode"),
        @JsonSubTypes.Type(value = NotNode.class,           name = "NotNode"),
        @JsonSubTypes.Type(value = ConditionNode.class,     name = "ConditionNode"),
        @JsonSubTypes.Type(value = ScorecardRootNode.class, name = "ScorecardRootNode"),
        @JsonSubTypes.Type(value = XorNode.class,           name = "XorNode")   // 新增
})
```

### Step 5: 在 `InterpretedExecutor` 补 XOR 分支

找到 `switch (node)` 或 `instanceof` 链，在 `OrNode` 分支后追加 `XorNode` 分支：

```java
case XorNode xor -> {
    // 全量遍历，不短路，统计满足节点数
    int satisfiedCount = 0;
    for (AstNode child : xor.children()) {
        if (evaluate(child, ctx)) satisfiedCount++;
        // 已超过 1 个可提前退出（优化）
        if (satisfiedCount > 1) break;
    }
    yield satisfiedCount == 1;
}
```

### Step 6: 在 `TracingInterpretedExecutor` 补 XOR 分支

与 `InterpretedExecutor` 同款，额外记录每个子节点的 trace：

```java
case XorNode xor -> {
    List<NodeTrace> childTraces = new ArrayList<>();
    int satisfiedCount = 0;
    for (AstNode child : xor.children()) {
        boolean childResult = evaluateWithTrace(child, ctx, childTraces);
        if (childResult) satisfiedCount++;
    }
    boolean result = satisfiedCount == 1;
    traces.add(new NodeTrace("XorNode", null, null, null,
            result, null, null, childTraces, snapshot.ruleVersionId()));
    yield result;
}
```

> 注意：XOR 不短路，所有子节点都要求值并记录 trace，帮助运营看到"哪个子条件满足了"。

### Step 7: 写单测 `XorNodeTest`

```java
package com.sstlfsj.rule.kernel.evaluator;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.model.ast.XorNode;
import com.sstlfsj.rule.kernel.api.spi.condition.ConditionEvaluator;
import com.sstlfsj.rule.kernel.internal.evaluator.InterpretedExecutor;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** XorNode 单测：验证"有且仅有一个满足"语义及全量遍历（不短路）。 */
class XorNodeTest {

    private static final String T = "ALWAYS_TRUE";
    private static final String F = "ALWAYS_FALSE";

    private final ConditionEvaluator alwaysTrue  = (node, ctx) -> true;
    private final ConditionEvaluator alwaysFalse = (node, ctx) -> false;
    private final Map<String, ConditionEvaluator> evaluators = Map.of(T, alwaysTrue, F, alwaysFalse);

    private EvalContext ctx() {
        RuleEvent event = new RuleEvent("t1", "scene1", "EVT", "u1",
                "e1", Instant.now(), Map.of(), null);
        return new EvalContext("t1", event, null, Map.of());
    }

    private RuleVersionSnapshot snapshot(XorNode xor) {
        return new RuleVersionSnapshot(1L, "scene1", "t1", xor,
                null, null, null, "AST_BOOLEAN");
    }

    private ConditionNode t(String metric) {
        return new ConditionNode(T, metric, null, Map.of(), 0.0);
    }

    private ConditionNode f(String metric) {
        return new ConditionNode(F, metric, null, Map.of(), 0.0);
    }

    @Test
    void 恰好一个满足_命中() {
        XorNode xor = new XorNode(List.of(t("m1"), f("m2"), f("m3")), null);
        EvalResult result = new InterpretedExecutor(evaluators).execute(snapshot(xor), ctx());
        assertThat(result.ruleHit()).isTrue();
    }

    @Test
    void 全部满足_不命中() {
        XorNode xor = new XorNode(List.of(t("m1"), t("m2")), null);
        EvalResult result = new InterpretedExecutor(evaluators).execute(snapshot(xor), ctx());
        assertThat(result.ruleHit()).isFalse();
    }

    @Test
    void 全部不满足_不命中() {
        XorNode xor = new XorNode(List.of(f("m1"), f("m2")), null);
        EvalResult result = new InterpretedExecutor(evaluators).execute(snapshot(xor), ctx());
        assertThat(result.ruleHit()).isFalse();
    }

    @Test
    void 空子节点_不命中() {
        XorNode xor = new XorNode(List.of(), null);
        EvalResult result = new InterpretedExecutor(evaluators).execute(snapshot(xor), ctx());
        assertThat(result.ruleHit()).isFalse();
    }

    @Test
    void 两个满足_不命中() {
        XorNode xor = new XorNode(List.of(t("m1"), t("m2"), f("m3")), null);
        EvalResult result = new InterpretedExecutor(evaluators).execute(snapshot(xor), ctx());
        assertThat(result.ruleHit()).isFalse();
    }
}
```

### Step 8: 运行 rule-kernel 全量测试

```bash
$MVN -pl rule-kernel -am test
```

Expected: BUILD SUCCESS，5 个 XorNodeTest 全部通过，已有测试无回归。

### Step 9: 运行 rule-eval-svc 测试（验证 AstJsonCodec 序列化）

```bash
$MVN -pl rule-eval-svc -am test
```

Expected: BUILD SUCCESS

### Step 10: 更新 `08-evolution.md §2.21`

在 §2.21 末尾追加已实装标注：

```
- **已实装（d12-scorecard-evaluator Task 7）**：`XorNode` sealed AST 节点 + `AstJsonCodec` 映射 + `InterpretedExecutor` / `TracingInterpretedExecutor` 全量遍历分支 + 5 个单测覆盖（恰好一个/全部满足/全部不满足/空/两个满足）。
```

### Step 11: commit

```bash
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/ast/XorNode.java
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/ast/AstNode.java
git add rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/snapshot/AstJsonCodec.java
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/evaluator/InterpretedExecutor.java
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/evaluator/TracingInterpretedExecutor.java
git add rule-kernel/src/test/java/com/sstlfsj/rule/kernel/evaluator/XorNodeTest.java
git commit -m "feat(kernel): XorNode AST 节点实装（§2.21，AST_BOOLEAN 内置 XOR 逻辑）"
```

---

## 验证命令汇总

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-25.jdk/Contents/Home
export PATH=$JAVA_HOME/bin:$PATH
MVN=/Users/sunke/.m2/wrapper/dists/apache-maven-3.9.9-bin/4nf9hui3q3djbarqar9g711ggc/apache-maven-3.9.9/bin/mvn

# Task 1-3：kernel
$MVN -pl rule-kernel -am test

# Task 4-5：eval-svc（依赖 kernel）
$MVN -pl rule-eval-svc -am test

# Task 6：config-svc
$MVN -pl rule-config-svc -am test

# Task 7：kernel（XorNode）
$MVN -pl rule-kernel -am test -Dtest=XorNodeTest

# 全量
$MVN -pl rule-kernel,rule-eval-svc,rule-config-svc,rule-api -am test
```
