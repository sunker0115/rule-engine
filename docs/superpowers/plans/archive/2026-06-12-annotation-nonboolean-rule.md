# 注解表达非 boolean 规则与多决策 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让注解能表达非 boolean 规则:`@Decide`(Java 直接返回决策码,含多码=多决策)、`@Score`+`@ScoreBand`(评分分档→决策),不改 kernel 执行器,靠 SDK 合成 `RuleVersionExecutor` 复用引擎"executor 自选决策"口子。

**Architecture:** 新增两个 SDK 合成执行器(`AnnotatedDecideExecutor`/`AnnotatedScoreExecutor`),按 SDK 本地 String kind tag(`__anno_decide`/`__anno_score`)注册进 `RuleEngineClient` 的 executors map(RuleEngineClient.java:79-82,由单 `AST_BOOLEAN` 扩多 kind)。`AnnotatedRuleScanner` 识别判定原语(`@Condition`/`@Decide`/`@Score` 三选一),设置快照 kind + 把方法登记进对应执行器注册表。合成规则的 `conditionAst` 复用 `Condition.of(coordKey).toAst()` 携带坐标键,执行器据此反查方法。设计依据见 `docs/superpowers/specs/2026-06-12-annotation-nonboolean-rule-design.md`(D64)。

**Tech Stack:** Java 25、JUnit 5 + AssertJ、Spring Boot AutoConfiguration、Maven 多模块。

**前置:** `mvn-env` skill 设 JDK 25;跨模块改动带 `-am`;收尾 `$MVN clean test`。注释中文、测试方法名英文。本计划基于 D61 基线;若 D63(易用性)已落地,`FactResolver` 的增强对本计划透明(参数注入共用 `resolve`),无冲突——Task 中对 `AnnotatedRuleScanner` 的改动只动"判定原语识别",不碰 D63 改的取名/校验细节。

---

## 关键 API(已核)

- `RuleVersionExecutor.execute(RuleVersionSnapshot, EvalContext) → EvalResult`(单方法 SPI)。
- `EvalResult(boolean ruleHit, Decision finalDecision, List<Decision> hitDecisions, List<NodeTrace> nodeTrace, String errorCode, Double score, String category, String decision)`;静态 `miss()`/`error(code)`。
- `Decision(code,name,priority,fromRuleVersionId,fromRuleCode,fromRuleVersion,category)`。
- `RuleVersionSnapshot.Builder.kind(String)`;`DecisionBinding(decisionCode,name,priority)`;`snapshot.decisionBindings()`。
- 引擎 `resolveRuleDecisions`:`r.hitDecisions()` 非空即原样采用(EvalEngine.java:228)。
- `RuleEngineClient` build:`Map.of(RuleKind.AST_BOOLEAN.tag(), executor)`(line 81);Builder 有 `extraEvaluators`(line 171)。
- `ConditionNode`:`com.sstlfsj.rule.kernel.api.model.ast.ConditionNode`,`.conditionType()`。

---

## 文件结构

**rule-sdk**
- `annotation/Decide.java`、`annotation/Score.java`、`annotation/ScoreBand.java` + `annotation/ScoreBands.java`(新)
- `source/AnnotatedDecideExecutor.java`、`source/AnnotatedScoreExecutor.java`(新,implements `RuleVersionExecutor`)
- `source/AnnotatedRuleScanner.java` — 判定原语三选一 + `ScanResult` 扩展 + 校验
- `RuleEngineClient.java` — Builder 收集 decide/score 注册表;build 时 executors map 扩多 kind

**rule-sdk-spring-boot-starter**
- `RuleEngineClientAutoConfiguration.java` — bean 识别纳入 `@Decide`/`@Score`;灌注册表

**rule-samples**
- `annotation/CreditScoreRule.java`(`@Score`)、`annotation/RiskDecideRule.java`(`@Decide` 多分支)+ 各 IT

---

## Task 1: `@Decide` 注解 + 合成执行器(单码)

**Files:**
- Create: `rule-sdk/src/main/java/com/sstlfsj/rule/sdk/annotation/Decide.java`
- Create: `rule-sdk/src/main/java/com/sstlfsj/rule/sdk/source/AnnotatedDecideExecutor.java`
- Test: `rule-sdk/src/test/java/com/sstlfsj/rule/sdk/source/AnnotatedDecideExecutorTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.sstlfsj.rule.sdk.source;

import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.sdk.Condition;
import com.sstlfsj.rule.sdk.FactResolver;
import com.sstlfsj.rule.sdk.annotation.Decide;
import com.sstlfsj.rule.sdk.annotation.Fact;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AnnotatedDecideExecutorTest {

    static class Rule {
        @Decide
        public String decide(@Fact("amount") Integer amount) {
            return amount > 5000 ? "REJECT" : "PASS";
        }
    }

    private RuleVersionSnapshot snap(String key) {
        return RuleVersionSnapshot.builder()
                .ruleVersionId(1L).tenantId("t").sceneCode("s").code("r").version(1L)
                .kind("__anno_decide")
                .conditionAst(Condition.of(key, Map.of()).toAst())
                .addTriggerEventType("evt")
                .addDecisionBinding("PASS", 10)
                .addDecisionBinding("REJECT", 90)
                .build();
    }

    private EvalContext ctx(int amount) {
        RuleEvent e = RuleEvent.builder().tenantId("t").sceneCode("s").eventType("evt")
                .subjectId("u").eventId("e1").occurredAt(Instant.now())
                .payload(Map.of("amount", amount)).source(EventSource.SDK).build();
        return new EvalContext("t", e, null, Map.of(), Instant.now());
    }

    @Test
    void decide_returnsBoundDecision() throws Exception {
        Method m = Rule.class.getMethod("decide", Integer.class);
        var inv = new AnnotatedDecideExecutor.Invocation(new Rule(), m, new FactResolver());
        var exec = new AnnotatedDecideExecutor(Map.of("k1", inv));

        EvalResult hi = exec.execute(snap("k1"), ctx(8000));
        assertThat(hi.ruleHit()).isTrue();
        assertThat(hi.finalDecision().code()).isEqualTo("REJECT");
        assertThat(hi.finalDecision().fromRuleCode()).isEqualTo("r");

        EvalResult lo = exec.execute(snap("k1"), ctx(100));
        assertThat(lo.finalDecision().code()).isEqualTo("PASS");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `$MVN -pl rule-sdk -am test -Dtest=AnnotatedDecideExecutorTest`
Expected: 编译失败(`Decide`/`AnnotatedDecideExecutor` 不存在)

- [ ] **Step 3: 写 `@Decide`**

```java
package com.sstlfsj.rule.sdk.annotation;

import java.lang.annotation.*;

/**
 * 标在规则 POJO 的方法上,返回命中的 decision code(null/空=不命中);
 * 返回值须是 @RuleDef.decisions 声明的码之一。返回 List&lt;String&gt;/String[] 可一次发多个决策。
 * 与 @Condition/@Score 互斥,一个规则恰好一个判定原语。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Decide {}
```

- [ ] **Step 4: 写 `AnnotatedDecideExecutor`(本 task 仅单码,多码在 Task 2)**

```java
package com.sstlfsj.rule.sdk.source;

import com.sstlfsj.rule.kernel.api.model.Decision;
import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.spi.executor.RuleVersionExecutor;
import com.sstlfsj.rule.sdk.FactResolver;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 合成执行器:把 @Decide 方法的返回决策码翻译成 EvalResult.hitDecisions。
 * 按快照 conditionAst 携带的坐标键反查方法;返回码须 ⊆ 快照 decisionBindings,非法码丢弃 + errorCode。
 */
public final class AnnotatedDecideExecutor implements RuleVersionExecutor {

    /** 一条 @Decide 规则的调用三元组。 */
    public record Invocation(Object bean, Method method, FactResolver factResolver) {}

    private static final Comparator<Decision> BY_PRIORITY = Comparator.comparingInt(Decision::priority);

    private final Map<String, Invocation> byKey;

    public AnnotatedDecideExecutor(Map<String, Invocation> byKey) {
        this.byKey = Map.copyOf(byKey);
    }

    @Override
    public EvalResult execute(RuleVersionSnapshot snapshot, EvalContext ctx) {
        String key = ((ConditionNode) snapshot.conditionAst()).conditionType();
        Invocation inv = byKey.get(key);
        if (inv == null) return EvalResult.error("ANNO_DECIDE_UNREGISTERED");

        List<String> codes;
        try {
            inv.method().setAccessible(true);
            Object[] args = inv.factResolver().resolve(inv.method().getParameters(), ctx, null);
            codes = toCodes(inv.method().invoke(inv.bean(), args));
        } catch (Exception e) {
            return EvalResult.error("DECIDE_EVAL_ERROR");
        }
        if (codes.isEmpty()) return EvalResult.miss();

        List<Decision> hits = new ArrayList<>();
        String errorCode = null;
        for (String code : codes) {
            RuleVersionSnapshot.DecisionBinding b = findBinding(snapshot, code);
            if (b == null) { errorCode = "INVALID_DECISION_CODE"; continue; }
            hits.add(new Decision(b.decisionCode(), b.name(), b.priority(),
                    snapshot.ruleVersionId(), snapshot.code(), snapshot.version(), null));
        }
        if (hits.isEmpty()) {
            return EvalResult.error(errorCode == null ? "ANNO_DECIDE_NO_HIT" : errorCode);
        }
        Decision finalD = Collections.max(hits, BY_PRIORITY);
        return new EvalResult(true, finalD, hits, List.of(), errorCode, null, finalD.category(), finalD.code());
    }

    /** 本 task 仅单 String;多返回值在 Task 2 扩展。 */
    private static List<String> toCodes(Object ret) {
        if (ret == null) return List.of();
        if (ret instanceof String s) return s.isBlank() ? List.of() : List.of(s);
        throw new IllegalStateException("@Decide 返回类型暂只支持 String: " + ret.getClass());
    }

    private static RuleVersionSnapshot.DecisionBinding findBinding(RuleVersionSnapshot snap, String code) {
        for (RuleVersionSnapshot.DecisionBinding b : snap.decisionBindings()) {
            if (b.decisionCode().equals(code)) return b;
        }
        return null;
    }
}
```

- [ ] **Step 5: 跑测试确认通过**

Run: `$MVN -pl rule-sdk -am test -Dtest=AnnotatedDecideExecutorTest`
Expected: PASS

- [ ] **Step 6: 提交**

```bash
git add rule-sdk/src/main/java/com/sstlfsj/rule/sdk/annotation/Decide.java rule-sdk/src/main/java/com/sstlfsj/rule/sdk/source/AnnotatedDecideExecutor.java rule-sdk/src/test/java/com/sstlfsj/rule/sdk/source/AnnotatedDecideExecutorTest.java
git commit -m "feat(sdk): @Decide 注解 + AnnotatedDecideExecutor(单码)"
```

---

## Task 2: F — `@Decide` 多决策(List/数组)

**Files:**
- Modify: `rule-sdk/src/main/java/com/sstlfsj/rule/sdk/source/AnnotatedDecideExecutor.java`
- Test: `rule-sdk/src/test/java/com/sstlfsj/rule/sdk/source/AnnotatedDecideMultiTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.sstlfsj.rule.sdk.source;

import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.sdk.Condition;
import com.sstlfsj.rule.sdk.FactResolver;
import com.sstlfsj.rule.sdk.annotation.Decide;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AnnotatedDecideMultiTest {

    static class Rule {
        @Decide
        public List<String> decide() { return List.of("REVIEW", "NOTIFY"); }
    }

    @Test
    void decide_returnsMultipleDecisions() throws Exception {
        Method m = Rule.class.getMethod("decide");
        var inv = new AnnotatedDecideExecutor.Invocation(new Rule(), m, new FactResolver());
        var exec = new AnnotatedDecideExecutor(Map.of("k", inv));
        RuleVersionSnapshot snap = RuleVersionSnapshot.builder()
                .ruleVersionId(1L).tenantId("t").sceneCode("s").code("r").version(1L)
                .kind("__anno_decide").conditionAst(Condition.of("k", Map.of()).toAst())
                .addTriggerEventType("evt")
                .addDecisionBinding("REVIEW", 50).addDecisionBinding("NOTIFY", 10).build();
        RuleEvent e = RuleEvent.builder().tenantId("t").sceneCode("s").eventType("evt")
                .subjectId("u").eventId("e1").occurredAt(Instant.now())
                .payload(Map.of()).source(EventSource.SDK).build();

        EvalResult r = exec.execute(snap, new EvalContext("t", e, null, Map.of(), Instant.now()));
        assertThat(r.hitDecisions()).extracting(Decision::code).containsExactly("REVIEW", "NOTIFY");
        assertThat(r.finalDecision().code()).isEqualTo("REVIEW");  // 最高优先级
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `$MVN -pl rule-sdk -am test -Dtest=AnnotatedDecideMultiTest`
Expected: FAIL(`toCodes` 遇 List 抛 IllegalStateException)

- [ ] **Step 3: 扩 `toCodes`**

把 `AnnotatedDecideExecutor.toCodes` 替换为:
```java
    private static List<String> toCodes(Object ret) {
        if (ret == null) return List.of();
        if (ret instanceof String s) return s.isBlank() ? List.of() : List.of(s);
        if (ret instanceof String[] arr) {
            List<String> out = new ArrayList<>();
            for (String s : arr) if (s != null && !s.isBlank()) out.add(s);
            return out;
        }
        if (ret instanceof java.util.Collection<?> col) {
            List<String> out = new ArrayList<>();
            for (Object o : col) if (o != null && !o.toString().isBlank()) out.add(o.toString());
            return out;
        }
        throw new IllegalStateException("@Decide 返回类型须是 String / String[] / Collection<String>: " + ret.getClass());
    }
```

- [ ] **Step 4: 跑测试确认通过**

Run: `$MVN -pl rule-sdk -am test -Dtest=AnnotatedDecideMultiTest`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add rule-sdk/src/main/java/com/sstlfsj/rule/sdk/source/AnnotatedDecideExecutor.java rule-sdk/src/test/java/com/sstlfsj/rule/sdk/source/AnnotatedDecideMultiTest.java
git commit -m "feat(sdk): @Decide 支持 List/数组返回多决策(F)"
```

---

## Task 3: `@Score` + `@ScoreBand` + 合成执行器

**Files:**
- Create: `rule-sdk/src/main/java/com/sstlfsj/rule/sdk/annotation/Score.java`
- Create: `rule-sdk/src/main/java/com/sstlfsj/rule/sdk/annotation/ScoreBand.java`
- Create: `rule-sdk/src/main/java/com/sstlfsj/rule/sdk/annotation/ScoreBands.java`
- Create: `rule-sdk/src/main/java/com/sstlfsj/rule/sdk/source/AnnotatedScoreExecutor.java`
- Test: `rule-sdk/src/test/java/com/sstlfsj/rule/sdk/source/AnnotatedScoreExecutorTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.sstlfsj.rule.sdk.source;

import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.sdk.Condition;
import com.sstlfsj.rule.sdk.FactResolver;
import com.sstlfsj.rule.sdk.annotation.Fact;
import com.sstlfsj.rule.sdk.annotation.Score;
import com.sstlfsj.rule.sdk.annotation.ScoreBand;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AnnotatedScoreExecutorTest {

    static class Rule {
        @Score
        @ScoreBand(min = 0, decision = "PASS")
        @ScoreBand(min = 60, decision = "REVIEW")
        @ScoreBand(min = 90, decision = "REJECT")
        public double score(@Fact("risk") Integer risk) { return risk; }
    }

    private EvalResult run(int risk) throws Exception {
        Method m = Rule.class.getMethod("score", Integer.class);
        var inv = new AnnotatedScoreExecutor.Invocation(new Rule(), m, new FactResolver(),
                List.of(new AnnotatedScoreExecutor.Band(0, "PASS"),
                        new AnnotatedScoreExecutor.Band(60, "REVIEW"),
                        new AnnotatedScoreExecutor.Band(90, "REJECT")));
        var exec = new AnnotatedScoreExecutor(Map.of("k", inv));
        RuleVersionSnapshot snap = RuleVersionSnapshot.builder()
                .ruleVersionId(1L).tenantId("t").sceneCode("s").code("r").version(1L)
                .kind("__anno_score").conditionAst(Condition.of("k", Map.of()).toAst())
                .addTriggerEventType("evt")
                .addDecisionBinding("PASS", 10).addDecisionBinding("REVIEW", 50)
                .addDecisionBinding("REJECT", 90).build();
        RuleEvent e = RuleEvent.builder().tenantId("t").sceneCode("s").eventType("evt")
                .subjectId("u").eventId("e1").occurredAt(Instant.now())
                .payload(Map.of("risk", risk)).source(EventSource.SDK).build();
        return exec.execute(snap, new EvalContext("t", e, null, Map.of(), Instant.now()));
    }

    @Test
    void score_mapsToHighestMatchingBand_andSetsScore() throws Exception {
        EvalResult r = run(75);
        assertThat(r.finalDecision().code()).isEqualTo("REVIEW");  // 75 ≥ 60,< 90
        assertThat(r.score()).isEqualTo(75.0);

        assertThat(run(95).finalDecision().code()).isEqualTo("REJECT");
        assertThat(run(10).finalDecision().code()).isEqualTo("PASS");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `$MVN -pl rule-sdk -am test -Dtest=AnnotatedScoreExecutorTest`
Expected: 编译失败(`Score`/`ScoreBand`/`AnnotatedScoreExecutor` 不存在)

- [ ] **Step 3: 写注解**

`Score.java`:
```java
package com.sstlfsj.rule.sdk.annotation;

import java.lang.annotation.*;

/** 标在规则方法上,返回 double 评分;经方法上的 @ScoreBand 分档映射到决策并写入 EvalResult.score。与 @Condition/@Decide 互斥。 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Score {}
```

`ScoreBand.java`:
```java
package com.sstlfsj.rule.sdk.annotation;

import java.lang.annotation.*;

/** 评分分档:score ≥ min 时归入 decision;多档取满足条件中 min 最大的一档。decision 须是 @RuleDef.decisions 之一。 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Repeatable(ScoreBands.class)
public @interface ScoreBand {
    /** 下界(含)。 */
    double min();
    /** 命中该档的决策码。 */
    String decision();
}
```

`ScoreBands.java`:
```java
package com.sstlfsj.rule.sdk.annotation;

import java.lang.annotation.*;

/** @ScoreBand 的容器(@Repeatable 要求)。 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ScoreBands {
    ScoreBand[] value();
}
```

- [ ] **Step 4: 写 `AnnotatedScoreExecutor`**

```java
package com.sstlfsj.rule.sdk.source;

import com.sstlfsj.rule.kernel.api.model.Decision;
import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.spi.executor.RuleVersionExecutor;
import com.sstlfsj.rule.sdk.FactResolver;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

/**
 * 合成执行器:调 @Score 方法得分,按 @ScoreBand 分档(min 最大且 ≤ score 的一档)映射决策,
 * 并把分写入 EvalResult.score。无档命中=miss。
 */
public final class AnnotatedScoreExecutor implements RuleVersionExecutor {

    /** 单个评分分档。 */
    public record Band(double min, String decision) {}
    /** 一条 @Score 规则的调用信息(方法 + 分档表)。 */
    public record Invocation(Object bean, Method method, FactResolver factResolver, List<Band> bands) {}

    private final Map<String, Invocation> byKey;

    public AnnotatedScoreExecutor(Map<String, Invocation> byKey) {
        this.byKey = Map.copyOf(byKey);
    }

    @Override
    public EvalResult execute(RuleVersionSnapshot snapshot, EvalContext ctx) {
        String key = ((ConditionNode) snapshot.conditionAst()).conditionType();
        Invocation inv = byKey.get(key);
        if (inv == null) return EvalResult.error("ANNO_SCORE_UNREGISTERED");

        double score;
        try {
            inv.method().setAccessible(true);
            Object[] args = inv.factResolver().resolve(inv.method().getParameters(), ctx, null);
            Object ret = inv.method().invoke(inv.bean(), args);
            score = ((Number) ret).doubleValue();
        } catch (Exception e) {
            return EvalResult.error("SCORE_EVAL_ERROR");
        }

        Band best = null;
        for (Band b : inv.bands()) {
            if (score >= b.min() && (best == null || b.min() > best.min())) best = b;
        }
        if (best == null) {
            return new EvalResult(false, null, List.of(), List.of(), null, score, null, null);
        }
        RuleVersionSnapshot.DecisionBinding bind = findBinding(snapshot, best.decision());
        if (bind == null) {
            return new EvalResult(false, null, List.of(), List.of(), "INVALID_DECISION_CODE", score, null, null);
        }
        Decision d = new Decision(bind.decisionCode(), bind.name(), bind.priority(),
                snapshot.ruleVersionId(), snapshot.code(), snapshot.version(), null);
        return new EvalResult(true, d, List.of(d), List.of(), null, score, d.category(), d.code());
    }

    private static RuleVersionSnapshot.DecisionBinding findBinding(RuleVersionSnapshot snap, String code) {
        for (RuleVersionSnapshot.DecisionBinding b : snap.decisionBindings()) {
            if (b.decisionCode().equals(code)) return b;
        }
        return null;
    }
}
```

- [ ] **Step 5: 跑测试确认通过**

Run: `$MVN -pl rule-sdk -am test -Dtest=AnnotatedScoreExecutorTest`
Expected: PASS

- [ ] **Step 6: 提交**

```bash
git add rule-sdk/src/main/java/com/sstlfsj/rule/sdk/annotation/Score.java rule-sdk/src/main/java/com/sstlfsj/rule/sdk/annotation/ScoreBand.java rule-sdk/src/main/java/com/sstlfsj/rule/sdk/annotation/ScoreBands.java rule-sdk/src/main/java/com/sstlfsj/rule/sdk/source/AnnotatedScoreExecutor.java rule-sdk/src/test/java/com/sstlfsj/rule/sdk/source/AnnotatedScoreExecutorTest.java
git commit -m "feat(sdk): @Score + @ScoreBand 评分规则 + AnnotatedScoreExecutor"
```

---

## Task 4: 扫描器识别三判定原语 + ScanResult 扩展 + 校验

**Files:**
- Modify: `rule-sdk/src/main/java/com/sstlfsj/rule/sdk/source/AnnotatedRuleScanner.java`
- Test: `rule-sdk/src/test/java/com/sstlfsj/rule/sdk/source/AnnotatedRulePrimitiveTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.sstlfsj.rule.sdk.source;

import com.sstlfsj.rule.kernel.api.annotation.DecisionBinding;
import com.sstlfsj.rule.kernel.api.annotation.RuleDef;
import com.sstlfsj.rule.sdk.FactResolver;
import com.sstlfsj.rule.sdk.annotation.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class AnnotatedRulePrimitiveTest {

    @RuleDef(code = "d", sceneCode = "s", decisions = {
            @DecisionBinding(code = "PASS"), @DecisionBinding(code = "REJECT", priority = 90)})
    static class DecideRule {
        @Decide public String decide(@Fact("amount") Integer a) { return a > 100 ? "REJECT" : "PASS"; }
    }

    @RuleDef(code = "bad", sceneCode = "s")
    static class TwoPrimitives {
        @Condition public boolean c() { return true; }
        @Decide public String d() { return "X"; }
    }

    @Test
    void scan_buildsDecideRegistryAndKind() {
        AnnotatedRuleScanner.ScanResult r =
                new AnnotatedRuleScanner(new FactResolver(), "t").scan(List.of(new DecideRule()));
        assertThat(r.decideInvocations()).hasSize(1);
        assertThat(r.snapshots()).hasSize(1);
        assertThat(r.snapshots().get(0).kind()).isEqualTo("__anno_decide");
    }

    @Test
    void scan_rejectsMultiplePrimitives() {
        assertThatThrownBy(() ->
                new AnnotatedRuleScanner(new FactResolver(), "t").scan(List.of(new TwoPrimitives())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("判定原语");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `$MVN -pl rule-sdk -am test -Dtest=AnnotatedRulePrimitiveTest`
Expected: 编译失败(`ScanResult.decideInvocations()` 不存在)

- [ ] **Step 3: 重写 `AnnotatedRuleScanner`**

整体替换 `AnnotatedRuleScanner.java` 为(在 D61 版本上扩展三原语;若 D63 已落地,把其 `factResolver.validate(...)` 调用合并进各原语分支的参数处理——本步给出 D61 基线版,D63 的 validate 调用加在 `wrap`/decide/score 方法参数确定后):

```java
package com.sstlfsj.rule.sdk.source;

import com.sstlfsj.rule.kernel.api.annotation.DecisionBinding;
import com.sstlfsj.rule.kernel.api.annotation.RuleDef;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.spi.condition.ConditionEvaluator;
import com.sstlfsj.rule.sdk.Condition;
import com.sstlfsj.rule.sdk.FactResolver;
import com.sstlfsj.rule.sdk.annotation.Metric;
import com.sstlfsj.rule.sdk.annotation.ScoreBand;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 扫描 @RuleDef 规则 POJO,按判定原语(@Condition / @Decide / @Score 三选一)产出:
 * boolean → 合成 ConditionEvaluator(kind=AST_BOOLEAN);@Decide → decideInvocations(kind=__anno_decide);
 * @Score → scoreInvocations(kind=__anno_score)。三者快照的 conditionAst 都携带坐标键供执行器反查。
 */
public final class AnnotatedRuleScanner {

    public static final String KIND_DECIDE = "__anno_decide";
    public static final String KIND_SCORE  = "__anno_score";

    private final FactResolver factResolver;
    private final String defaultTenantId;

    public AnnotatedRuleScanner(FactResolver factResolver, String defaultTenantId) {
        this.factResolver = factResolver;
        this.defaultTenantId = defaultTenantId == null ? "" : defaultTenantId;
    }

    /** 扫描结果:三类注册产物 + 快照列表。 */
    public record ScanResult(Map<String, ConditionEvaluator> evaluators,
                             Map<String, AnnotatedDecideExecutor.Invocation> decideInvocations,
                             Map<String, AnnotatedScoreExecutor.Invocation> scoreInvocations,
                             List<RuleVersionSnapshot> snapshots) {}

    public ScanResult scan(List<?> ruleBeans) {
        Map<String, ConditionEvaluator> evaluators = new HashMap<>();
        Map<String, AnnotatedDecideExecutor.Invocation> decideInvocations = new HashMap<>();
        Map<String, AnnotatedScoreExecutor.Invocation> scoreInvocations = new HashMap<>();
        List<RuleVersionSnapshot> snapshots = new ArrayList<>();

        for (Object bean : ruleBeans) {
            RuleDef def = bean.getClass().getAnnotation(RuleDef.class);
            if (def == null) continue;

            Method primitive = findSinglePrimitive(bean);
            String tenant = def.tenantId().isBlank() ? defaultTenantId : def.tenantId();
            String key = "__anno:" + tenant + ":" + def.sceneCode() + ":" + def.code();
            if (evaluators.containsKey(key) || decideInvocations.containsKey(key)
                    || scoreInvocations.containsKey(key)) {
                throw new IllegalStateException("注解规则坐标重复: " + key);
            }

            RuleVersionSnapshot.Builder b = RuleVersionSnapshot.builder()
                    .ruleVersionId(stableId(tenant, def.sceneCode(), def.code()))
                    .tenantId(tenant).sceneCode(def.sceneCode()).code(def.code()).version(def.version())
                    .conditionAst(Condition.of(key, Map.of()).toAst());
            if (def.trigger().length == 0) b.addTriggerEventType("*");
            else for (String t : def.trigger()) b.addTriggerEventType(t);
            for (DecisionBinding d : def.decisions()) b.addDecisionBinding(d.code(), d.priority());
            for (Parameter p : primitive.getParameters()) {
                Metric m = p.getAnnotation(Metric.class);
                if (m != null) b.addMetricDependency(m.value(), m.version());
            }

            primitive.setAccessible(true);
            if (primitive.isAnnotationPresent(com.sstlfsj.rule.sdk.annotation.Condition.class)) {
                evaluators.put(key, wrapCondition(bean, primitive));
                // kind 默认 AST_BOOLEAN(不显式 set,执行器映射用 AST_BOOLEAN)
            } else if (primitive.isAnnotationPresent(com.sstlfsj.rule.sdk.annotation.Decide.class)) {
                decideInvocations.put(key,
                        new AnnotatedDecideExecutor.Invocation(bean, primitive, factResolver));
                b.kind(KIND_DECIDE);
            } else { // @Score
                scoreInvocations.put(key,
                        new AnnotatedScoreExecutor.Invocation(bean, primitive, factResolver, bands(primitive)));
                b.kind(KIND_SCORE);
            }
            snapshots.add(b.build());
        }
        return new ScanResult(evaluators, decideInvocations, scoreInvocations, snapshots);
    }

    /** 找出唯一判定原语方法(@Condition/@Decide/@Score 三选一),0 个或多个抛错。 */
    private static Method findSinglePrimitive(Object bean) {
        Method found = null;
        for (Method m : bean.getClass().getMethods()) {
            boolean isPrimitive = m.isAnnotationPresent(com.sstlfsj.rule.sdk.annotation.Condition.class)
                    || m.isAnnotationPresent(com.sstlfsj.rule.sdk.annotation.Decide.class)
                    || m.isAnnotationPresent(com.sstlfsj.rule.sdk.annotation.Score.class);
            if (isPrimitive) {
                if (found != null) {
                    throw new IllegalStateException("规则 " + bean.getClass().getName()
                            + " 有多个判定原语(@Condition/@Decide/@Score),只允许一个");
                }
                found = m;
            }
        }
        if (found == null) {
            throw new IllegalStateException("规则 " + bean.getClass().getName()
                    + " 缺少判定原语(@Condition/@Decide/@Score)");
        }
        return found;
    }

    private static List<AnnotatedScoreExecutor.Band> bands(Method m) {
        List<AnnotatedScoreExecutor.Band> out = new ArrayList<>();
        for (ScoreBand sb : m.getAnnotationsByType(ScoreBand.class)) {
            out.add(new AnnotatedScoreExecutor.Band(sb.min(), sb.decision()));
        }
        if (out.isEmpty()) {
            throw new IllegalStateException("@Score 方法须至少声明一个 @ScoreBand: " + m);
        }
        return out;
    }

    private ConditionEvaluator wrapCondition(Object bean, Method method) {
        return (node, ctx) -> {
            Object[] args = factResolver.resolve(method.getParameters(), ctx, null);
            try {
                return Boolean.TRUE.equals(method.invoke(bean, args));
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                throw new RuntimeException("规则条件求值失败: " + bean.getClass().getName(), cause);
            } catch (IllegalAccessException e) {
                throw new RuntimeException("规则条件不可访问: " + bean.getClass().getName(), e);
            }
        };
    }

    private static long stableId(String tenant, String scene, String code) {
        return (tenant + ":" + scene + ":" + code).hashCode() & 0xffffffffL;
    }
}
```

> 注:本步把 D61 的 `ScanResult(evaluators, snapshots)` 改为四元组,**所有读 ScanResult 的地方**(`RuleEngineClientAutoConfiguration`)须同步改(见 Task 5);Task 5 前本模块可能因 starter 编译不过,故 Task 4 的"确认通过"只跑 rule-sdk 单测(starter 在 Task 5 修)。

- [ ] **Step 4: 跑 rule-sdk 测试确认通过**

Run: `$MVN -pl rule-sdk -am test -Dtest=AnnotatedRulePrimitiveTest`
Expected: PASS(rule-sdk 内部自洽;starter 暂不编译)

- [ ] **Step 5: 提交**

```bash
git add rule-sdk/src/main/java/com/sstlfsj/rule/sdk/source/AnnotatedRuleScanner.java rule-sdk/src/test/java/com/sstlfsj/rule/sdk/source/AnnotatedRulePrimitiveTest.java
git commit -m "feat(sdk): 扫描器支持 @Condition/@Decide/@Score 三判定原语 + ScanResult 扩展"
```

---

## Task 5: SDK 装配 — executors map 扩多 kind + AutoConfiguration 灌注册表

**Files:**
- Modify: `rule-sdk/src/main/java/com/sstlfsj/rule/sdk/RuleEngineClient.java`
- Modify: `rule-sdk-spring-boot-starter/src/main/java/com/sstlfsj/rule/sdk/starter/RuleEngineClientAutoConfiguration.java`
- Test: `rule-sdk-spring-boot-starter/src/test/java/com/sstlfsj/rule/sdk/starter/DecideScoreWiringTest.java`

- [ ] **Step 1: 写失败测试(Spring 切片,端到端)**

```java
package com.sstlfsj.rule.sdk.starter;

import com.sstlfsj.rule.kernel.api.annotation.DecisionBinding;
import com.sstlfsj.rule.kernel.api.annotation.RuleDef;
import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.sdk.RuleEngineClient;
import com.sstlfsj.rule.sdk.annotation.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DecideScoreWiringTest {

    @RuleDef(code = "risk", sceneCode = "demo", trigger = "evt", decisions = {
            @DecisionBinding(code = "PASS", priority = 10),
            @DecisionBinding(code = "REJECT", priority = 90)})
    static class RiskRule {
        @Decide public String decide(@Fact("amount") Integer amount) {
            return amount > 5000 ? "REJECT" : "PASS";
        }
    }

    @Test
    void decideRule_isWiredAndEvaluated() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(RuleEngineClientAutoConfiguration.class))
                .withBean(RiskRule.class)
                .run(ctx -> {
                    RuleEngineClient client = ctx.getBean(RuleEngineClient.class);
                    RuleEvent e = RuleEvent.builder().tenantId("").sceneCode("demo").eventType("evt")
                            .subjectId("u").eventId("e1").occurredAt(Instant.now())
                            .payload(Map.of("amount", 8000)).source(EventSource.SDK).build();
                    EvalResult r = client.evaluate(e);
                    assertThat(r.ruleHit()).isTrue();
                    assertThat(r.finalDecision().code()).isEqualTo("REJECT");
                });
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `$MVN -pl rule-sdk-spring-boot-starter -am test -Dtest=DecideScoreWiringTest`
Expected: FAIL/编译失败(builder 无 decide/score 注册;AutoConfiguration 未灌注册表;且 Task4 改了 ScanResult,AutoConfiguration 现有 `scan.evaluators()`/`scan.snapshots()` 调用需适配)

- [ ] **Step 3: RuleEngineClient 加 decide/score 注册 + executors map 扩多 kind**

在 `RuleEngineClient.Builder` 字段区(`extraEvaluators` 旁,约 line 171)加:
```java
        private final Map<String, com.sstlfsj.rule.sdk.source.AnnotatedDecideExecutor.Invocation> decideInvocations = new HashMap<>();
        private final Map<String, com.sstlfsj.rule.sdk.source.AnnotatedScoreExecutor.Invocation> scoreInvocations = new HashMap<>();
```
在 Builder 方法区(`addEvaluator` 旁)加:
```java
        /** 注册 @Decide 规则调用(key=注解规则坐标键)。 */
        public Builder addDecideInvocations(Map<String, com.sstlfsj.rule.sdk.source.AnnotatedDecideExecutor.Invocation> m) {
            decideInvocations.putAll(m); return this;
        }
        /** 注册 @Score 规则调用(key=注解规则坐标键)。 */
        public Builder addScoreInvocations(Map<String, com.sstlfsj.rule.sdk.source.AnnotatedScoreExecutor.Invocation> m) {
            scoreInvocations.putAll(m); return this;
        }
```
把 build() 的 executors 装配(现 line 76-82)替换为:
```java
        RuleVersionExecutor executor = b.executor != null
                ? b.executor
                : new InterpretedExecutor(evaluators);
        Map<String, RuleVersionExecutor> executors = new HashMap<>();
        executors.put(RuleKind.AST_BOOLEAN.tag(), executor);
        if (!b.decideInvocations.isEmpty()) {
            executors.put(com.sstlfsj.rule.sdk.source.AnnotatedRuleScanner.KIND_DECIDE,
                    new com.sstlfsj.rule.sdk.source.AnnotatedDecideExecutor(b.decideInvocations));
        }
        if (!b.scoreInvocations.isEmpty()) {
            executors.put(com.sstlfsj.rule.sdk.source.AnnotatedRuleScanner.KIND_SCORE,
                    new com.sstlfsj.rule.sdk.source.AnnotatedScoreExecutor(b.scoreInvocations));
        }
        this.evalEngine = new EvalEngine(index, assembler,
                b.preGates != null ? b.preGates : Map.of(),
                executors,
                false);
```
(即把 `Map.of(RuleKind.AST_BOOLEAN.tag(), executor)` 换成可变 `executors` map 并按需加两个合成 executor。)

- [ ] **Step 4: AutoConfiguration 适配 ScanResult + 灌注册表 + 纳入 @Decide/@Score bean**

在 `RuleEngineClientAutoConfiguration` 收集注解规则 bean 的判定(现只认 `@Condition`)改为认三原语。把现有收集块的内层判断:
```java
                   if (m.isAnnotationPresent(com.sstlfsj.rule.sdk.annotation.Condition.class)) {
```
替换为:
```java
                   if (m.isAnnotationPresent(com.sstlfsj.rule.sdk.annotation.Condition.class)
                           || m.isAnnotationPresent(com.sstlfsj.rule.sdk.annotation.Decide.class)
                           || m.isAnnotationPresent(com.sstlfsj.rule.sdk.annotation.Score.class)) {
```
把现有 scan 结果消费(现 `scan.evaluators().forEach(builder::addEvaluator); builder.ruleSource(new DslRuleSource(scan.snapshots()));`)替换为:
```java
            scan.evaluators().forEach(builder::addEvaluator);
            builder.addDecideInvocations(scan.decideInvocations());
            builder.addScoreInvocations(scan.scoreInvocations());
            builder.ruleSource(new com.sstlfsj.rule.sdk.source.DslRuleSource(scan.snapshots()));
```

- [ ] **Step 5: 跑测试确认通过 + 两模块回归**

Run: `$MVN -pl rule-sdk-spring-boot-starter -am test -Dtest=DecideScoreWiringTest` 然后 `$MVN -pl rule-sdk,rule-sdk-spring-boot-starter -am test`
Expected: 均 PASS(D61 的 `AnnotatedRuleWiringTest` 仍绿——@Condition 路径不变)

- [ ] **Step 6: 提交**

```bash
git add rule-sdk/src/main/java/com/sstlfsj/rule/sdk/RuleEngineClient.java rule-sdk-spring-boot-starter/src/main/java/com/sstlfsj/rule/sdk/starter/RuleEngineClientAutoConfiguration.java rule-sdk-spring-boot-starter/src/test/java/com/sstlfsj/rule/sdk/starter/DecideScoreWiringTest.java
git commit -m "feat(sdk): executors map 扩多 kind 装配 @Decide/@Score 合成执行器"
```

---

## Task 6: 扫描期校验 — 决策码须 ⊆ @DecisionBinding

**Files:**
- Modify: `rule-sdk/src/main/java/com/sstlfsj/rule/sdk/source/AnnotatedRuleScanner.java`
- Test: `rule-sdk/src/test/java/com/sstlfsj/rule/sdk/source/AnnotatedDecisionCodeValidationTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.sstlfsj.rule.sdk.source;

import com.sstlfsj.rule.kernel.api.annotation.DecisionBinding;
import com.sstlfsj.rule.kernel.api.annotation.RuleDef;
import com.sstlfsj.rule.sdk.FactResolver;
import com.sstlfsj.rule.sdk.annotation.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class AnnotatedDecisionCodeValidationTest {

    @RuleDef(code = "s", sceneCode = "x", decisions = @DecisionBinding(code = "PASS"))
    static class ScoreBandUnbound {
        @Score @ScoreBand(min = 0, decision = "GHOST")  // GHOST 未在 decisions 声明
        public double sc() { return 1; }
    }

    @Test
    void scan_rejectsScoreBandReferencingUndeclaredDecision() {
        assertThatThrownBy(() ->
                new AnnotatedRuleScanner(new FactResolver(), "t").scan(List.of(new ScoreBandUnbound())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GHOST");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `$MVN -pl rule-sdk -am test -Dtest=AnnotatedDecisionCodeValidationTest`
Expected: FAIL(扫描期未校验决策码归属)

- [ ] **Step 3: 扫描器加决策码校验**

在 `AnnotatedRuleScanner.scan` 的 `@Score` 分支(`scoreInvocations.put(...)` 之前)与 `@Decide` 分支后,加校验。最简做法:在该 bean 处理末尾(`snapshots.add(b.build());` 之前)统一收集声明码集合并校验 `@ScoreBand`:
```java
            java.util.Set<String> declared = new java.util.HashSet<>();
            for (DecisionBinding d : def.decisions()) declared.add(d.code());
            if (primitive.isAnnotationPresent(com.sstlfsj.rule.sdk.annotation.Score.class)) {
                for (ScoreBand sb : primitive.getAnnotationsByType(ScoreBand.class)) {
                    if (!declared.contains(sb.decision())) {
                        throw new IllegalStateException("@ScoreBand 引用了未在 @RuleDef.decisions 声明的决策码: "
                                + sb.decision() + " (规则 " + bean.getClass().getName() + ")");
                    }
                }
            }
```
(`@Decide` 返回码是运行期产出,无法编译期穷举,沿用执行器的 `INVALID_DECISION_CODE` 运行期兜底,不在扫描期校验。)

- [ ] **Step 4: 跑测试确认通过**

Run: `$MVN -pl rule-sdk -am test -Dtest=AnnotatedDecisionCodeValidationTest`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add rule-sdk/src/main/java/com/sstlfsj/rule/sdk/source/AnnotatedRuleScanner.java rule-sdk/src/test/java/com/sstlfsj/rule/sdk/source/AnnotatedDecisionCodeValidationTest.java
git commit -m "feat(sdk): @ScoreBand 决策码扫描期校验 ⊆ @RuleDef.decisions"
```

---

## Task 7: samples — `@Score` 与 `@Decide` 示例 + 端到端

**Files:**
- Create: `rule-samples/src/main/java/com/sstlfsj/rule/samples/annotation/CreditScoreRule.java`
- Create: `rule-samples/src/main/java/com/sstlfsj/rule/samples/annotation/RiskDecideRule.java`
- Test: `rule-samples/src/test/java/com/sstlfsj/rule/samples/annotation/NonBooleanRuleIT.java`

- [ ] **Step 1: 写失败测试**

```java
package com.sstlfsj.rule.samples.annotation;

import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.sdk.RuleEngineClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 非 boolean 注解规则端到端:@Score 信用分分档 + @Decide 多分支风控。 */
class NonBooleanRuleIT {

    @Test
    void scoreAndDecide_produceDecisions() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        com.sstlfsj.rule.sdk.starter.RuleEngineClientAutoConfiguration.class))
                .withBean(CreditScoreRule.class)
                .withBean(RiskDecideRule.class)
                .run(ctx -> {
                    RuleEngineClient client = ctx.getBean(RuleEngineClient.class);

                    RuleEvent credit = RuleEvent.builder().tenantId("").sceneCode("credit-demo")
                            .eventType("apply").subjectId("u").eventId("c1").occurredAt(Instant.now())
                            .payload(Map.of("score", 72)).source(EventSource.SDK).build();
                    EvalResult cr = client.evaluate(credit);
                    assertThat(cr.finalDecision().code()).isEqualTo("MANUAL_REVIEW");
                    assertThat(cr.score()).isEqualTo(72.0);

                    RuleEvent risk = RuleEvent.builder().tenantId("").sceneCode("risk-demo")
                            .eventType("txn").subjectId("u").eventId("r1").occurredAt(Instant.now())
                            .payload(Map.of("amount", 99999)).source(EventSource.SDK).build();
                    assertThat(client.evaluate(risk).finalDecision().code()).isEqualTo("BLOCK");
                });
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `$MVN -pl rule-samples -am test -Dtest=NonBooleanRuleIT -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败(`CreditScoreRule`/`RiskDecideRule` 不存在)

- [ ] **Step 3: 写 `CreditScoreRule`(@Score)**

```java
package com.sstlfsj.rule.samples.annotation;

import com.sstlfsj.rule.kernel.api.annotation.DecisionBinding;
import com.sstlfsj.rule.kernel.api.annotation.RuleDef;
import com.sstlfsj.rule.sdk.annotation.Fact;
import com.sstlfsj.rule.sdk.annotation.Score;
import com.sstlfsj.rule.sdk.annotation.ScoreBand;
import org.springframework.stereotype.Component;

/** 评分规则示例:信用分分档 → 拒/人工/过。@Score 返回分,@ScoreBand 映射决策。 */
@RuleDef(code = "credit-score", sceneCode = "credit-demo", trigger = "apply", decisions = {
        @DecisionBinding(code = "AUTO_REJECT", priority = 90),
        @DecisionBinding(code = "MANUAL_REVIEW", priority = 50),
        @DecisionBinding(code = "AUTO_PASS", priority = 10)})
@Component
public class CreditScoreRule {

    @Score
    @ScoreBand(min = 0,  decision = "AUTO_REJECT")
    @ScoreBand(min = 60, decision = "MANUAL_REVIEW")
    @ScoreBand(min = 80, decision = "AUTO_PASS")
    public double creditScore(@Fact("score") Integer score) {
        return score == null ? 0 : score;
    }
}
```

- [ ] **Step 4: 写 `RiskDecideRule`(@Decide)**

```java
package com.sstlfsj.rule.samples.annotation;

import com.sstlfsj.rule.kernel.api.annotation.DecisionBinding;
import com.sstlfsj.rule.kernel.api.annotation.RuleDef;
import com.sstlfsj.rule.sdk.annotation.Decide;
import com.sstlfsj.rule.sdk.annotation.Fact;
import org.springframework.stereotype.Component;

/** 多分支风控示例:@Decide 在 Java 里直接算出决策码(替代决策树/表)。 */
@RuleDef(code = "risk-decide", sceneCode = "risk-demo", trigger = "txn", decisions = {
        @DecisionBinding(code = "BLOCK", priority = 90),
        @DecisionBinding(code = "REVIEW", priority = 50),
        @DecisionBinding(code = "ALLOW", priority = 10)})
@Component
public class RiskDecideRule {

    @Decide
    public String decide(@Fact("amount") Integer amount) {
        if (amount == null) return "ALLOW";
        if (amount >= 50000) return "BLOCK";
        if (amount >= 5000)  return "REVIEW";
        return "ALLOW";
    }
}
```

- [ ] **Step 5: 跑 IT 确认通过**

Run: `$MVN -pl rule-samples -am test -Dtest=NonBooleanRuleIT -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS

- [ ] **Step 6: 提交**

```bash
git add rule-samples/src/main/java/com/sstlfsj/rule/samples/annotation/CreditScoreRule.java rule-samples/src/main/java/com/sstlfsj/rule/samples/annotation/RiskDecideRule.java rule-samples/src/test/java/com/sstlfsj/rule/samples/annotation/NonBooleanRuleIT.java
git commit -m "test(samples): @Score 信用分 + @Decide 风控 端到端示例"
```

---

## Task 8: 全量回归 + spec 状态更新

**Files:**
- Modify: `docs/superpowers/specs/2026-06-12-annotation-nonboolean-rule-design.md`(状态行)

- [ ] **Step 1: 全量编译测试**

Run: `$MVN clean test`
Expected: 全绿(14 模块)。

- [ ] **Step 2: 更新 spec 状态行**

把文档首行 `> 状态:设计待评审` 改为 `> 状态:已实现`。

- [ ] **Step 3: 提交**

```bash
git add docs/superpowers/specs/2026-06-12-annotation-nonboolean-rule-design.md
git commit -m "docs: 注解非boolean规则/多决策(D64)标记为已实现"
```

---

## 自查清单(已核)

- **spec 覆盖**:E `@Decide`(Task1)+`@Score`/`@ScoreBand`(Task3);F 多决策(Task2);装配(Task5);校验(Task4 原语三选一 + Task6 决策码归属);samples(Task7)。
- **类型一致**:`AnnotatedDecideExecutor.Invocation(bean,method,factResolver)`、`AnnotatedScoreExecutor.Invocation(bean,method,factResolver,List<Band>)`、`Band(min,decision)`、`ScanResult(evaluators,decideInvocations,scoreInvocations,snapshots)`、`KIND_DECIDE/KIND_SCORE` 常量跨 task 一致。
- **API 真实性**:`RuleVersionExecutor.execute`、`EvalResult` 8 参构造、`Decision` 7 参构造、`RuleVersionSnapshot.Builder.kind/addDecisionBinding`、`DecisionBinding.decisionCode/name/priority`、`ConditionNode.conditionType`、`RuleEngineClient` executors map(line 81)、`getAnnotationsByType`(@Repeatable)均经源码核对。
- **引擎口子**:执行器返回 `hitDecisions` 非空 → `resolveRuleDecisions` 原样采用(EvalEngine.java:228),不改 kernel。
- **与 D63 兼容**:Task4 重写 scanner 时,若 D63 已加 `factResolver.validate(...)`,合并进各原语参数处理处(注释已说明);二者无逻辑冲突。

---

## 已知未覆盖(留作后续)

- **`@Condition` boolean 规则发全部 binding**:需改 kernel `resolveRuleDecisions`,不在本计划;用 `@Decide` 多返回替代。
- **`@Score` 不支持 `EXPRESSION_SCRIPT`/`SCORECARD` 引擎原生 AST 复用**:合成执行器自成一路,不接入 kernel ScorecardExecutor(SDK 本地评估不需要)。
- **trace**:合成执行器返回空 `nodeTrace`(SDK 本地评估不持久化快照,trace 非必需);如需 dry-run trace 另议。
