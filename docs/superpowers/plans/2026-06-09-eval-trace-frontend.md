# 面向前端的评估 trace Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 4 类规则(boolean/scorecard/tree/table)都产出内容完整、可被前端一致渲染的评估 trace——补 tree/table 的 NodeTrace、在求值源头接上 actualValue/valueSource、加 expectedValue/displayLabel 并落库、补 eval_duration_ms。

**Architecture:** 求值源头 `ConditionEvaluation` 把已算出的实际值/来源带进新 `ConditionOutcome` 字段;各 executor 写入 NodeTrace;tree/table 新建 trace(读 `TraceScope.COLLECT`,disabled 零分配);Scorecard 加 `ScorecardRoot` 根节点让 root nodeType 自描述 kind;NodeTrace 加 `expectedValue`(落现有 `params` 列)+ `displayLabel`(落新增 `display_label` 列),主/ dry-run 两条 trace 链同步;eval_duration 用 `context.now()` 当起点 + 事件带 `durationMs`。落库形态选 A(自包含),A→B 可逆。

**Tech Stack:** Java 25 / Spring Boot 4 / MyBatis-Plus / Flyway / JUnit5+AssertJ / GraalVM native(无新反射、无 preview)。

**环境(每个 `$MVN` 前 export):**
```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-25.jdk/Contents/Home
export PATH=$JAVA_HOME/bin:$PATH
MVN=/Users/sunke/.m2/wrapper/dists/apache-maven-3.9.9-bin/4nf9hui3q3djbarqar9g711ggc/apache-maven-3.9.9/bin/mvn
```
**分支:** develop 直接提交,不 push。每增量该模块测试全绿才 commit。

---

## File Structure

| 文件 | 责任 | 增量 |
|---|---|---|
| `rule-kernel/.../internal/evaluator/ConditionOutcome.java` | 三态 + 携带 resolvedValue/valueSource | 1 |
| `rule-kernel/.../internal/evaluator/ConditionEvaluation.java` | 求值时把 mv.value/source 带进 outcome | 1 |
| `rule-kernel/.../internal/evaluator/InterpretedExecutor.java` | 叶子 trace 填实际值/来源 | 1 |
| `rule-kernel/.../internal/evaluator/ScorecardExecutor.java` | 因子 trace 填值 + ScorecardRoot 根节点 | 1 |
| `rule-kernel/.../internal/evaluator/DecisionTreeExecutor.java` | 新建 IfNode 路径 + 条件子树 + leaf trace | 2 |
| `rule-kernel/.../internal/evaluator/DecisionTableExecutor.java` | 新建 行/列 trace | 2 |
| `rule-kernel/.../api/model/NodeTrace.java` | 加 expectedValue + displayLabel 字段 + 兼容构造 | 3 |
| `rule-observability/.../internal/domain/NodeTraceEntity.java` + `DryRunNodeTraceEntity.java` | 加 displayLabel 字段 | 3 |
| `rule-observability/.../internal/trace/TraceWriterDbImpl.java` + `DryRunTraceWriterDbImpl.java` | flatten 映射 params(expected)+display_label | 3 |
| `rule-config-svc/.../db/migration/V1_12__node_trace_display_label.sql` | 两表加 display_label 列 | 3 |
| `rule-eval-svc/.../internal/async/AuditRecorded.java` + `DryRunRecorded.java` | 加 durationMs 字段 | 4 |
| `rule-eval-svc/.../internal/service/EvalServiceImpl.java` | 算 durationMs 传入事件 | 4 |
| `rule-eval-svc/.../internal/async/AuditPersister.java` + `DryRunPersister.java` | started=ctx.now / finished / eval_duration_ms | 4 |

> 注:`NodeTrace` 现 9 参 `(nodeType, conditionType, metricCode, result, actualValue, valueSource, errorCode, children, ruleVersionId)`。增量 1/2 仍用 9 参形态(只是把原来传 null 的 actualValue/valueSource 填实);增量 3 才加 expectedValue/displayLabel,届时所有构造点改 11 参或走兼容构造。

---

## 增量 1：ConditionOutcome 接线 + Interpreted/Scorecard 填值 + ScorecardRoot

**Files:**
- Modify: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/evaluator/ConditionOutcome.java`
- Modify: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/evaluator/ConditionEvaluation.java`
- Modify: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/evaluator/InterpretedExecutor.java`
- Modify: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/evaluator/ScorecardExecutor.java`
- Test: `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/evaluator/`(新增 trace 内容断言)

- [ ] **Step 1: 写失败测试——叶子 trace 带 actualValue/valueSource**

新建 `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/evaluator/TraceContentTest.java`：
```java
package com.sstlfsj.rule.kernel.internal.evaluator;

import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.spi.condition.ConditionEvaluator;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TraceContentTest {
    private static final Instant NOW = Instant.parse("2026-06-09T00:00:00Z");

    private EvalContext ctxWith(String code, Object val, String source) {
        Map<String, MetricValue> m = Map.of(code, new MetricValue(val, "LONG", source));
        RuleEvent ev = new RuleEvent("1","PAY","t","u1","e1",NOW,Map.of(),Map.of(),EventSource.HTTP);
        return new EvalContext("1", ev, new Subject("u1", SubjectType.USER, Map.of()), m, NOW);
    }

    @Test
    void interpretedLeafTrace_carriesActualValueAndSource() {
        ConditionNode node = new ConditionNode("GTE","score","score>=0",Map.of("threshold",0),null,"LONG");
        // evaluator: score(100) >= 0 → true
        Map<String,ConditionEvaluator> evals = Map.of("GTE", (n,c) ->
                ((Number)c.getMetric(n.metricCode()).value()).longValue() >= 0);
        InterpretedExecutor exec = new InterpretedExecutor(evals);
        RuleVersionSnapshot snap = RuleVersionSnapshot.builder()
                .ruleVersionId(7L).tenantId("1").sceneCode("PAY").conditionAst(node).build();

        EvalResult r = exec.execute(snap, ctxWith("score", 100L, "PROVIDED"));

        NodeTrace leaf = r.nodeTrace().getFirst();
        assertThat(leaf.nodeType()).isEqualTo("ConditionNode");
        assertThat(leaf.result()).isTrue();
        assertThat(leaf.actualValue()).isEqualTo(100L);
        assertThat(leaf.valueSource()).isEqualTo("PROVIDED");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
$MVN -pl rule-kernel test -Dtest='TraceContentTest' -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: FAIL——`actualValue` 当前为 null(executor 传 null)。

- [ ] **Step 3: `ConditionOutcome` 加 resolvedValue/valueSource**

整体替换为:
```java
package com.sstlfsj.rule.kernel.internal.evaluator;

/** 条件求值三态：满足/不满足/不可判定；并携带叶子的实际值与来源(供 trace)。 */
record ConditionOutcome(Status status, String errorCode, Object resolvedValue, String valueSource) {

    enum Status { SATISFIED, NOT_SATISFIED, ERROR }

    static final ConditionOutcome SATISFIED = new ConditionOutcome(Status.SATISFIED, null, null, null);
    static final ConditionOutcome NOT_SATISFIED = new ConditionOutcome(Status.NOT_SATISFIED, null, null, null);

    /** 容器组合结果(And/Or/Not),无叶子值。 */
    static ConditionOutcome of(boolean satisfied) {
        return satisfied ? SATISFIED : NOT_SATISFIED;
    }

    /** 不可判定(无叶子值,如 NO_EVALUATOR 容器层)。 */
    static ConditionOutcome error(String errorCode) {
        return new ConditionOutcome(Status.ERROR, errorCode, null, null);
    }

    /** 叶子求值结果,携带实际值/来源。 */
    static ConditionOutcome leaf(boolean satisfied, Object resolvedValue, String valueSource) {
        return new ConditionOutcome(satisfied ? Status.SATISFIED : Status.NOT_SATISFIED,
                null, resolvedValue, valueSource);
    }

    /** 叶子不可判定(取数失败),携带来源。 */
    static ConditionOutcome error(String errorCode, Object resolvedValue, String valueSource) {
        return new ConditionOutcome(Status.ERROR, errorCode, resolvedValue, valueSource);
    }

    boolean satisfied() { return status == Status.SATISFIED; }
    boolean isError()   { return status == Status.ERROR; }
}
```

- [ ] **Step 4: `ConditionEvaluation.evaluate` 带出 mv.value/source**

替换方法体:
```java
    static ConditionOutcome evaluate(ConditionNode node, EvalContext ctx,
                                     Map<String, ConditionEvaluator> evaluators) {
        String mc = node.metricCode();
        Object actual = null;
        String source = null;
        if (mc != null) {
            MetricValue mv = ctx.getMetric(mc);
            if (mv != null) { actual = mv.value(); source = mv.valueSource(); }
            if (mv != null && mv.isError()) {
                return ConditionOutcome.error(
                        mv.errorCode() != null ? mv.errorCode() : METRIC_FETCH_FAIL, actual, source);
            }
        }
        ConditionEvaluator evaluator = evaluators.get(node.conditionType());
        if (evaluator == null) return ConditionOutcome.error(NO_EVALUATOR, actual, source);
        return ConditionOutcome.leaf(evaluator.evaluate(node, ctx), actual, source);
    }
```

- [ ] **Step 5: `InterpretedExecutor.evalCondition` 填值**

把两处 `new NodeTrace("ConditionNode", ...)` 的第 5/6 参(actualValue/valueSource)从 `null, null` 改为 `outcome.resolvedValue(), outcome.valueSource()`：
```java
    private boolean evalCondition(ConditionNode node, EvalContext ctx, List<NodeTrace> sink) {
        ConditionOutcome outcome = ConditionEvaluation.evaluate(node, ctx, evaluators);
        if (outcome.isError()) {
            if (sink != null) {
                sink.add(new NodeTrace("ConditionNode", node.conditionType(), node.metricCode(),
                        false, outcome.resolvedValue(), outcome.valueSource(), outcome.errorCode(),
                        List.of(), null));
            }
            return false;
        }
        if (sink != null) {
            sink.add(new NodeTrace("ConditionNode", node.conditionType(), node.metricCode(),
                    outcome.satisfied(), outcome.resolvedValue(), outcome.valueSource(), null,
                    List.of(), null));
        }
        return outcome.satisfied();
    }
```

- [ ] **Step 6: `ScorecardExecutor` 填值 + 加 ScorecardRoot 根节点**

替换 `execute` 方法体的循环与返回部分:
```java
        boolean collect = TraceScope.COLLECT.orElse(true);
        List<NodeTrace> factorTraces = collect ? new ArrayList<>() : null;
        double score = 0.0;
        Long rvId = snapshot.ruleVersionId();

        for (ConditionNode node : root.conditions()) {
            ConditionOutcome outcome = ConditionEvaluation.evaluate(node, ctx, evaluators);
            if (outcome.isError()) {
                if (collect) {
                    factorTraces.add(new NodeTrace("ConditionNode", node.conditionType(), node.metricCode(),
                            false, outcome.resolvedValue(), outcome.valueSource(), outcome.errorCode(),
                            List.of(), rvId));
                }
                return new EvalResult(false, null, List.of(),
                        scorecardRoot(collect, false, factorTraces, rvId),
                        outcome.errorCode(), List.of(), null, null, null);
            }
            boolean met = outcome.satisfied();
            if (met && node.weight() != null) score += node.weight();
            if (collect) {
                factorTraces.add(new NodeTrace("ConditionNode", node.conditionType(), node.metricCode(),
                        met, outcome.resolvedValue(), outcome.valueSource(), null, List.of(), rvId));
            }
        }

        boolean hit = score >= root.threshold();
        return new EvalResult(hit, null, List.of(),
                scorecardRoot(collect, hit, factorTraces, rvId),
                null, List.of(), score, null, null);
    }

    /** 因子 trace 包进 ScorecardRoot 根节点(root nodeType 自描述 SCORECARD kind);collect=false 返回空。 */
    private static List<NodeTrace> scorecardRoot(boolean collect, boolean result,
                                                 List<NodeTrace> factors, Long rvId) {
        if (!collect) return List.of();
        return List.of(new NodeTrace("ScorecardRoot", null, null, result, null, null, null, factors, rvId));
    }
```

- [ ] **Step 7: 改既有 Scorecard trace 断言以适配 ScorecardRoot 包裹**

找 `ScorecardExecutor` 相关测试中断言「trace 顶层是 ConditionNode 扁平列表」的用例,改为「顶层是单个 `ScorecardRoot`、其 `children()` 为因子」。命令定位:
```bash
grep -rln 'ScorecardExecutor\|ScorecardRoot\|nodeTrace' rule-kernel/src/test
```
逐个把 `r.nodeTrace().get(i)` 形态的断言改为 `r.nodeTrace().getFirst().children().get(i)`,并加一条 `assertThat(r.nodeTrace().getFirst().nodeType()).isEqualTo("ScorecardRoot")`。

- [ ] **Step 8: 跑 kernel 全量回归**

```bash
$MVN -pl rule-kernel test
```
Expected: `BUILD SUCCESS`;`TraceContentTest` 通过;既有用例全绿(Interpreted/Scorecard trace 行为除 actualValue 填实、Scorecard 多一层 root 外不变)。

- [ ] **Step 9: Commit**

```bash
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/evaluator/ConditionOutcome.java \
        rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/evaluator/ConditionEvaluation.java \
        rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/evaluator/InterpretedExecutor.java \
        rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/evaluator/ScorecardExecutor.java \
        rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/evaluator/
git commit -m "feat(trace): ConditionOutcome 带出实际值/来源,Interpreted/Scorecard 填实 + ScorecardRoot 根节点"
```

---

## 增量 2：tree/table 补 NodeTrace

**Files:**
- Modify: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/evaluator/DecisionTreeExecutor.java`
- Modify: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/evaluator/DecisionTableExecutor.java`
- Test: `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/evaluator/DecisionTreeTraceTest.java`、`DecisionTableTraceTest.java`(新建)

> 约定:读 `TraceScope.COLLECT.orElse(true)`,collect=false 时 sink=null、零分配、`nodeTrace()=List.of()`(与 Interpreted 一致);rvId 内联写入。tree 条件子树复用 And/Or/Not/Condition 约定(在 tree 自己的 `evaluateCondition` 里穿 sink,**不抽共享 walker**——见 spec §2 Q3)。

- [ ] **Step 1: 写失败测试——tree trace 路径 + 条件 + leaf**

新建 `DecisionTreeTraceTest.java`(给一个 `IfNode(condition, then=leaf, else=...)`,断言 trace 顶层 nodeType="IfNode"、result=条件结果、children 含条件 ConditionNode + 到达的 DecisionLeafNode);COLLECT=false 时 `nodeTrace().isEmpty()`。(测试用 `ScopedValue.where(TraceScope.COLLECT,false).call(...)` 包裹验证零 trace。)

- [ ] **Step 2: 跑确认失败**(当前 tree `nodeTrace()` 恒空)

```bash
$MVN -pl rule-kernel test -Dtest='DecisionTreeTraceTest' -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: FAIL。

- [ ] **Step 3: `DecisionTreeExecutor` 穿 sink 建 trace**

`execute` 读 collect、建顶层 sink、调 `evaluate(root, snap, ctx, sink)`,末尾 `new EvalResult(..., collect? sink : List.of(), ...)`。`evaluate/evaluateIf/evaluateCondition/hit` 各加 `List<NodeTrace> sink` 形参:
- `evaluateIf`:`List<NodeTrace> condChildren = sink!=null? new ArrayList<>():null;` 先对 condition 求值并把条件子树收进 condChildren;再递归选中分支收进同一 children;`sink.add(new NodeTrace("IfNode", null, null, cond.satisfied(), null, null, cond.isError()?cond.errorCode():null, condChildren, rvId))`。
- `evaluateCondition`:ConditionNode → 复用增量 1 的叶子写法(`ConditionEvaluation.evaluate` + actualValue/valueSource);And/Or/Not → 仿 InterpretedExecutor 的容器写法(childTraces + `new NodeTrace("AndNode"/"OrNode"/"NotNode", null,null,result,null,null,null,childTraces,rvId)`)。
- `hit`:`sink.add(new NodeTrace("DecisionLeafNode", null,null,true,null,null,null,List.of(),rvId))`。

(完整方法体在执行时按上述约定补全;rvId=`snapshot.ruleVersionId()` 在 execute 取一次贯穿传入。)

- [ ] **Step 4: 跑 tree 测试通过 + COLLECT=false 零 trace**

```bash
$MVN -pl rule-kernel test -Dtest='DecisionTreeTraceTest' -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: PASS。

- [ ] **Step 5: 写失败测试——table 行/列 trace**

新建 `DecisionTableTraceTest.java`:多行表,断言 trace 顶层是若干 `DecisionTableRow`(到命中行止)、每行 children 为被求值列的 ConditionNode、命中行 result=true。

- [ ] **Step 6: `DecisionTableExecutor` 建 trace**

`execute` 读 collect、建 sink;`rowMatches` 每测一列把列 ConditionNode trace 收进该行 childTraces(用增量 1 叶子写法:列合成的 `ConditionNode node` + `ConditionEvaluation.evaluate`);每行测完 `sink.add(new NodeTrace("DecisionTableRow", null,null,matched,null,null,rowError,colTraces,rvId))`;命中即 break(FIRST_HIT)。末尾 `new EvalResult(..., collect? sink:List.of(), ...)`。

- [ ] **Step 7: 跑 table 测试 + kernel 全量回归**

```bash
$MVN -pl rule-kernel test
```
Expected: `BUILD SUCCESS`,tree/table trace 测试绿,既有 tree/table 命中/裁决用例不变(只多了 trace 产出)。

- [ ] **Step 8: Commit**

```bash
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/evaluator/DecisionTreeExecutor.java \
        rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/evaluator/DecisionTableExecutor.java \
        rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/evaluator/DecisionTreeTraceTest.java \
        rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/evaluator/DecisionTableTraceTest.java
git commit -m "feat(trace): DecisionTree/DecisionTable 补 NodeTrace(全保真,读 TraceScope.COLLECT)"
```

---

## 增量 3：NodeTrace 加 expectedValue/displayLabel + 落库(主/dry-run 两链)

**Files:**
- Modify: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/NodeTrace.java`
- Modify: 4 个 executor 的叶子 ConditionNode 构造点(填 expectedValue=`node.params()`、displayLabel=`node.displayLabel()`)
- Modify: `rule-observability/.../internal/domain/NodeTraceEntity.java`、`DryRunNodeTraceEntity.java`(加 `displayLabel` 字段)
- Modify: `rule-observability/.../internal/trace/TraceWriterDbImpl.java`、`DryRunTraceWriterDbImpl.java`(flatten 映射 params + display_label)
- Create: `rule-config-svc/src/main/resources/db/migration/V1_12__node_trace_display_label.sql`

- [ ] **Step 1: `NodeTrace` 加字段 + 兼容构造**

在 record 组件末尾加 `Object expectedValue, String displayLabel`(放最后,避免打乱现有位置);并加一个保留旧 9 参签名的兼容构造(delegate expectedValue/displayLabel=null),挡住暂未填这两字段的构造点:
```java
public record NodeTrace(
        String nodeType, String conditionType, String metricCode,
        Boolean result, Object actualValue, String valueSource, String errorCode,
        List<NodeTrace> children, Long ruleVersionId,
        Object expectedValue, String displayLabel) {
    public NodeTrace {
        children = children == null ? List.of() : List.copyOf(children);
    }
    /** 兼容构造:不带 expectedValue/displayLabel 的旧 9 参形态。 */
    public NodeTrace(String nodeType, String conditionType, String metricCode,
                     Boolean result, Object actualValue, String valueSource, String errorCode,
                     List<NodeTrace> children, Long ruleVersionId) {
        this(nodeType, conditionType, metricCode, result, actualValue, valueSource, errorCode,
                children, ruleVersionId, null, null);
    }
}
```
> 注意 `InterpretedExecutor.withRuleVersionId` 用全参重建 NodeTrace——它要带上 `t.expectedValue(), t.displayLabel()`(否则重建丢字段)。改那处:`new NodeTrace(..., children, rvId, t.expectedValue(), t.displayLabel())`。

- [ ] **Step 2: 叶子构造点填 expectedValue/displayLabel**

4 个 executor 里**叶子 ConditionNode** 的 NodeTrace 构造(Interpreted.evalCondition 两处、Scorecard 两处、tree 的条件叶、table 的列)改用 11 参,末尾加 `node.params(), node.displayLabel()`(table 列合成的 ConditionNode 已带 params/displayLabel=null)。容器节点(And/Or/Not/Xor/IfNode/Row/ScorecardRoot)用 9 参兼容构造即可(expectedValue/displayLabel=null)。

- [ ] **Step 3: 写失败测试——叶子 trace 带 expectedValue + displayLabel**

在 `TraceContentTest` 加用例:断言 `leaf.expectedValue()` = `{threshold=0}`(node.params)、`leaf.displayLabel()` = `"score>=0"`。

- [ ] **Step 4: 跑 kernel 测试通过**

```bash
$MVN -pl rule-kernel test
```
Expected: `BUILD SUCCESS`。

- [ ] **Step 5: Flyway 迁移——两表加 display_label**

新建 `rule-config-svc/src/main/resources/db/migration/V1_12__node_trace_display_label.sql`：
```sql
ALTER TABLE node_trace          ADD COLUMN display_label VARCHAR(256) NULL COMMENT '条件可读标签快照(displayLabel)' AFTER metric_code;
ALTER TABLE dry_run_node_trace  ADD COLUMN display_label VARCHAR(256) NULL COMMENT '条件可读标签快照(displayLabel)' AFTER metric_code;
```
(`expectedValue` 复用现有 `params` JSON 列,无需 DDL。)

- [ ] **Step 6: entity + flatten 映射(两条链)**

`NodeTraceEntity` 与 `DryRunNodeTraceEntity` 各加 `private String displayLabel;`(Lombok @Getter/@Setter 已在类上)。
`TraceWriterDbImpl.flattenToList` 与 `DryRunTraceWriterDbImpl.flattenToList` 在 set 块加:
```java
entity.setParams(trace.expectedValue() == null ? null : objectMapper.writeValueAsString(trace.expectedValue()));
entity.setDisplayLabel(trace.displayLabel());
```
(两个 Writer 已持有/可注入 `ObjectMapper`——若 DryRunTraceWriterDbImpl 当前无 ObjectMapper,按 Spring 注入全局 ObjectMapper Bean 补构造参;主 TraceWriterDbImpl 同理。params 列是 JSON,用 ObjectMapper 序列化 expectedValue。)

- [ ] **Step 7: 跑 observability + config-svc 测试**

```bash
$MVN -pl rule-observability,rule-config-svc -am test
```
Expected: `BUILD SUCCESS`(含 flatten 映射单测若有;迁移在 config-svc 模块,Testcontainers 应跑通 V1_12)。

- [ ] **Step 8: Commit**

```bash
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/NodeTrace.java \
        rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/evaluator/ \
        rule-observability/src/main/java/com/sstlfsj/rule/observability/internal/domain/NodeTraceEntity.java \
        rule-observability/src/main/java/com/sstlfsj/rule/observability/internal/domain/DryRunNodeTraceEntity.java \
        rule-observability/src/main/java/com/sstlfsj/rule/observability/internal/trace/TraceWriterDbImpl.java \
        rule-observability/src/main/java/com/sstlfsj/rule/observability/internal/trace/DryRunTraceWriterDbImpl.java \
        rule-config-svc/src/main/resources/db/migration/V1_12__node_trace_display_label.sql \
        rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/evaluator/TraceContentTest.java
git commit -m "feat(trace): NodeTrace 加 expectedValue(→params 列)+displayLabel(→新列),主/dry-run 两链落库"
```

---

## 增量 4：eval_duration_ms(context.now 起点 + 事件带 durationMs)

**Files:**
- Modify: `rule-eval-svc/.../internal/async/AuditRecorded.java`、`DryRunRecorded.java`(加 `int durationMs`)
- Modify: `rule-eval-svc/.../internal/service/EvalServiceImpl.java`(算 durationMs 传入事件)
- Modify: `rule-eval-svc/.../internal/async/AuditPersister.java`、`DryRunPersister.java`(started=ctx.now / finished / eval_duration_ms)
- Test: `rule-eval-svc/.../internal/async/AuditPersisterTest`(或新增)断言 eval_duration_ms/started 来自评估时刻

- [ ] **Step 1: 写失败测试——session 的 eval_duration_ms 来自事件 durationMs、started_at 来自 ctx.now**

在 AuditPersister 的测试里构造 `AuditRecorded`(durationMs=42、context.now()=已知 Instant),断言落出的 `EvaluationSession.getEvalDurationMs()==42`、`getStartedAt()` 对应 ctx.now。

- [ ] **Step 2: 跑确认失败**(当前 durationMs 字段不存在 / started=now())

- [ ] **Step 3: 事件加 `durationMs`**

`AuditRecorded`/`DryRunRecorded` record 加 `int durationMs` 组件(放 sessionId 之后或末尾,统一即可)。

- [ ] **Step 4: `EvalServiceImpl` 算时长传入**

发布前用已有 `Instant evalNow`(line 69)：
```java
int durationMs = (int) java.time.Duration.between(evalNow, Instant.now()).toMillis();
```
两处 publish(line 78 DryRunRecorded、line 92 AuditRecorded)把 `durationMs` 传入构造。

- [ ] **Step 5: persister 写 started/finished/duration**

`AuditPersister` 把
```java
LocalDateTime now = LocalDateTime.now();
s.setStartedAt(now);
s.setFinishedAt(now);
```
改为:
```java
LocalDateTime start = e.context() != null
        ? LocalDateTime.ofInstant(e.context().now(), ZoneId.systemDefault())
        : LocalDateTime.now();
s.setStartedAt(start);
s.setFinishedAt(start.plusNanos(e.durationMs() * 1_000_000L));
s.setEvalDurationMs(e.durationMs());
```
`DryRunPersister` 同理(它已 setFinishedAt;补 setStartedAt=ctx.now、setEvalDurationMs=e.durationMs())。

- [ ] **Step 6: 跑 eval-svc 测试**

```bash
$MVN -pl rule-eval-svc -am test
```
Expected: `BUILD SUCCESS`。

- [ ] **Step 7: Commit**

```bash
git add rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/async/AuditRecorded.java \
        rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/async/DryRunRecorded.java \
        rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/service/EvalServiceImpl.java \
        rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/async/AuditPersister.java \
        rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/async/DryRunPersister.java \
        rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/async/
git commit -m "feat(audit): eval_duration_ms 用 context.now 起点 + 事件 durationMs,修 started/finished 盖落库时刻"
```

---

## 收尾：全量回归

- [ ] 跑 `$MVN -pl rule-kernel,rule-eval-svc,rule-observability,rule-config-svc -am test`,全绿。
- [ ] (可选)起 app + dry-run 一条 tree/table/scorecard 规则,核对响应 nodeTrace 的 root nodeType 自描述 kind、叶子带 actualValue/expectedValue/displayLabel。

---

## Self-Review(对照 spec)
- spec §3 tree/table 形态 → 增量 2 ✓;§5 ConditionOutcome 接线 + actualValue/valueSource → 增量 1 ✓;ScorecardRoot/kind 自描述 → 增量 1 ✓;expectedValue→params / displayLabel→新列 / 两链落库 → 增量 3 ✓;eval_duration(context.now+durationMs)→ 增量 4 ✓;value_source enum 无需扩(已核)✓;A 自包含(label/expected 入 trace 行)✓。
- 占位:tree/table 完整方法体在增量 2 Step 3/6 给了约定 + 关键构造,执行时按约定补全(非 placeholder,是有明确规则的展开)——若执行 agent 需要逐行,可在该步展开 Interpreted 的容器写法照搬。
- 类型一致:NodeTrace 11 参顺序(…children, ruleVersionId, expectedValue, displayLabel)全增量统一;ConditionOutcome.leaf/error(code,val,src) 命名一致;durationMs 字段名一致。
- 非目标守住:未统一二值/三值、未抽 walker、未加 decisionCode 字段、未动 #3 冗余表/#4 context_snapshot。
