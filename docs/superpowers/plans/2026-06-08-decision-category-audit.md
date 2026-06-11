# 决策 category 审计 + 引擎决策来源修根 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让引擎聚合用 executor 自选的决策（决策树/表）而非最高优先级 binding，修复决策被篡改 + FIRST_HIT 布尔缺口，并把每条命中决策的分类标签 `category` 落进 `evaluation_session` 审计。

**Architecture:** `Decision` 记录新增可空 `category` 字段（自洽携带）；`EvalEngine` 抽共享判别器 `resolveRuleDecisions`——`r.hitDecisions()` 非空用 executor 决策（tree/table），否则回退 binding（boolean/scorecard）；审计 `hit_decisions` 升级为对象数组并新增 `evaluation_session.category` 单列（finalDecision 同源）。

**Tech Stack:** Java 25 / Spring Boot 4 / MyBatis-Plus / tools.jackson (Jackson 3) / JUnit 5 + Mockito / Flyway / Maven。

**Spec:** `docs/superpowers/specs/2026-06-08-decision-category-audit-design.md`

---

## 环境前置（每个 task 跑测试前）

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-25.jdk/Contents/Home
export PATH=$JAVA_HOME/bin:$PATH
MVN=/Users/sunke/.m2/wrapper/dists/apache-maven-3.9.9-bin/4nf9hui3q3djbarqar9g711ggc/apache-maven-3.9.9/bin/mvn
cd /Users/sunke/dev/ai-project/rule-engine
```

## 文件清单

| 文件 | 职责 | 动作 |
|---|---|---|
| `rule-kernel/.../api/model/Decision.java` | 决策记录，新增 category | 改 |
| `rule-kernel/.../api/model/DecisionTest.java` | Decision 单测 | 改 |
| `rule-kernel/.../internal/evaluator/DecisionTreeExecutor.java` | 叶子决策焊接 category | 改 |
| `rule-kernel/.../evaluator/DecisionTreeExecutorTest.java` | 决策树 executor 单测 | 改 |
| `rule-kernel/.../internal/engine/EvalEngine.java` | 修根 + resolveRuleDecisions | 改 |
| `rule-kernel/.../internal/engine/EvalEngineStrategyTest.java` | 策略/回归单测 | 改 |
| `rule-eval-svc/.../internal/domain/EvaluationSession.java` | 加 category 字段 | 改 |
| `rule-eval-svc/.../internal/domain/EvaluationSessionTest.java` | 实体单测 | 改 |
| `rule-config-svc/.../db/migration/V1_10__add_session_category.sql` | 加 category 列 | 建 |
| `rule-eval-svc/.../internal/async/AuditPersister.java` | 注 ObjectMapper + hit_decisions 对象 + session.category | 改 |
| `rule-eval-svc/.../async/AuditPersisterTest.java` | 审计单测 | 改 |

---

## Task 1: `Decision` 增 `category` 字段 + 便捷构造器

**Files:**
- Modify: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/Decision.java`
- Test: `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/api/model/DecisionTest.java`

- [ ] **Step 1: 写失败测试**（追加到 `DecisionTest` 类内）

```java
    @org.junit.jupiter.api.Test
    void category_carriedByFiveArgCtor_nullByFourArgCtor() {
        com.sstlfsj.rule.kernel.api.model.Decision withCat =
                new com.sstlfsj.rule.kernel.api.model.Decision("REVIEW", "", 20, 9L, "中危");
        com.sstlfsj.rule.kernel.api.model.Decision noCat =
                new com.sstlfsj.rule.kernel.api.model.Decision("PASS", "", 10, 9L);

        org.junit.jupiter.api.Assertions.assertEquals("中危", withCat.category());
        org.junit.jupiter.api.Assertions.assertNull(noCat.category());
    }
```

- [ ] **Step 2: 跑测试确认失败（编译错误）**

```bash
$MVN -pl rule-kernel test -Dtest='DecisionTest' -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | grep -E 'ERROR|BUILD'
```
Expected: 编译失败——`Decision` 无 5 参构造器 / `category()` 方法不存在。

- [ ] **Step 3: 实现**（整体替换 `Decision.java`）

```java
package com.sstlfsj.rule.kernel.api.model;

/** 规则命中后的决策描述，priority 越大越优先。category 为 DECISION_TREE 命中叶子的分类标签，其他 kind 为 null。 */
public record Decision(
        String code,
        String name,
        int priority,
        Long fromRuleVersionId,
        String category
) {
    /** 无分类（boolean/scorecard/decision-table 等）的便捷构造，category=null。 */
    public Decision(String code, String name, int priority, Long fromRuleVersionId) {
        this(code, name, priority, fromRuleVersionId, null);
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

```bash
$MVN -pl rule-kernel test -Dtest='DecisionTest' -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | grep -E 'Tests run|BUILD'
```
Expected: `Tests run: N, Failures: 0, Errors: 0` + `BUILD SUCCESS`（N = 原用例数 + 1）。

- [ ] **Step 5: 提交**

```bash
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/Decision.java \
        rule-kernel/src/test/java/com/sstlfsj/rule/kernel/api/model/DecisionTest.java
git commit -m "feat(kernel): Decision 增 category 字段(配 4 参便捷构造器零 blast)"
```

---

## Task 2: `DecisionTreeExecutor` 把 category 焊到 Decision

**Files:**
- Modify: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/evaluator/DecisionTreeExecutor.java:88-96`
- Test: `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/evaluator/DecisionTreeExecutorTest.java`

- [ ] **Step 1: 写失败测试**（追加到 `DecisionTreeExecutorTest`，断言命中决策带 category）

```java
    @org.junit.jupiter.api.Test
    void hitLeaf_decisionCarriesLeafCategory() {
        // 构造单层决策树：if (demo.flag GTE 1) then leaf(decisionCode=REVIEW, category=中危)
        com.sstlfsj.rule.kernel.api.model.ast.ConditionNode cond =
                new com.sstlfsj.rule.kernel.api.model.ast.ConditionNode(
                        "GTE", "demo.flag", "f>=1", java.util.Map.of("threshold", 1), "LONG", null);
        com.sstlfsj.rule.kernel.api.model.ast.DecisionLeafNode leaf =
                new com.sstlfsj.rule.kernel.api.model.ast.DecisionLeafNode("REVIEW", "中危", null);
        com.sstlfsj.rule.kernel.api.model.ast.IfNode root =
                new com.sstlfsj.rule.kernel.api.model.ast.IfNode(cond, leaf, null, null, null);
        com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot snap =
                new com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot(1L, "s", "t1", root,
                        java.util.List.of(),
                        java.util.List.of(new com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot.DecisionBinding("REVIEW", 20)),
                        java.util.List.of(), "DECISION_TREE");
        com.sstlfsj.rule.kernel.api.model.MetricValue mv =
                new com.sstlfsj.rule.kernel.api.model.MetricValue(1L, "LONG", "PROVIDED");
        com.sstlfsj.rule.kernel.api.model.EvalContext ctx =
                new com.sstlfsj.rule.kernel.api.model.EvalContext("t1", null, null,
                        java.util.Map.of("demo.flag", mv), java.time.Instant.EPOCH);

        com.sstlfsj.rule.kernel.api.model.EvalResult r =
                newTreeExecutor().execute(snap, ctx);

        org.junit.jupiter.api.Assertions.assertTrue(r.ruleHit());
        org.junit.jupiter.api.Assertions.assertEquals("REVIEW", r.finalDecision().code());
        org.junit.jupiter.api.Assertions.assertEquals("中危", r.finalDecision().category());
    }

    /** 构造一个带 GTE 求值器的 DecisionTreeExecutor。 */
    private static com.sstlfsj.rule.kernel.internal.evaluator.DecisionTreeExecutor newTreeExecutor() {
        return new com.sstlfsj.rule.kernel.internal.evaluator.DecisionTreeExecutor(
                java.util.Map.of("GTE", new com.sstlfsj.rule.kernel.internal.condition.GteEvaluator()));
    }
```

> 注：若 `ConditionNode` / `DecisionLeafNode` / `IfNode` / `EvalContext` / `MetricValue` 构造签名与上不符，以 `DecisionTreeExecutorTest` 已有用例的构造方式为准（复制其工厂方法）；本测试只关心 `r.finalDecision().category()=="中危"`。

- [ ] **Step 2: 跑测试确认失败**

```bash
$MVN -pl rule-kernel test -Dtest='DecisionTreeExecutorTest#hitLeaf_decisionCarriesLeafCategory' -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | grep -E 'Tests run|expected.*中危|BUILD'
```
Expected: FAIL —— `finalDecision().category()` 为 null（现在 category 只挂 EvalResult 不挂 Decision），断言 `中危` 失败。

- [ ] **Step 3: 实现**（替换 `DecisionTreeExecutor.hit()`，第 88-96 行）

```java
    private EvalResult hit(DecisionLeafNode leaf, RuleVersionSnapshot snapshot) {
        Decision decision = snapshot.decisionBindings().stream()
                .filter(b -> b.decisionCode().equals(leaf.decisionCode()))
                .max(java.util.Comparator.comparingInt(RuleVersionSnapshot.DecisionBinding::priority))
                .map(b -> new Decision(b.decisionCode(), "", b.priority(), snapshot.ruleVersionId(), leaf.category()))
                .orElseGet(() -> new Decision(leaf.decisionCode(), "", 0, snapshot.ruleVersionId(), leaf.category()));
        return new EvalResult(true, decision, List.of(decision),
                List.of(), null, List.of(), null, leaf.category(), null);
    }
```

- [ ] **Step 4: 跑测试确认通过**

```bash
$MVN -pl rule-kernel test -Dtest='DecisionTreeExecutorTest' -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | grep -E 'Tests run|BUILD'
```
Expected: `Failures: 0, Errors: 0` + `BUILD SUCCESS`。

- [ ] **Step 5: 提交**

```bash
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/evaluator/DecisionTreeExecutor.java \
        rule-kernel/src/test/java/com/sstlfsj/rule/kernel/evaluator/DecisionTreeExecutorTest.java
git commit -m "feat(kernel): DecisionTreeExecutor 把叶子 category 焊到命中 Decision"
```

---

## Task 3: `EvalEngine` 修根——`resolveRuleDecisions` + 接入两策略

**Files:**
- Modify: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/engine/EvalEngine.java`
- Test: `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/engine/EvalEngineStrategyTest.java`

- [ ] **Step 1: 写失败测试**（追加 3 个用例到 `EvalEngineStrategyTest`，复用其已有 `snapshot`/`event`/`hitExecutor` 工厂）

```java
    /** 回归:决策树命中 PASS 叶子,引擎不能用最高优先级 binding(BLOCK)覆盖。 */
    @org.junit.jupiter.api.Test
    void allHits_decisionTree_usesLeafDecisionNotMaxBinding() {
        // executor:固定返回 PASS 决策(模拟叶子命中),priority 10;snapshot bindings 含更高优先级 BLOCK(30)
        com.sstlfsj.rule.kernel.api.spi.executor.RuleVersionExecutor treeExec = (s, c) -> {
            com.sstlfsj.rule.kernel.api.model.Decision pass =
                    new com.sstlfsj.rule.kernel.api.model.Decision("PASS", "", 10, s.ruleVersionId(), "低危");
            return new com.sstlfsj.rule.kernel.api.model.EvalResult(true, pass, java.util.List.of(pass),
                    java.util.List.of(), null, java.util.List.of(), null, "低危", null);
        };
        RuleVersionSnapshot snap = new RuleVersionSnapshot(1L, "fraud", "t1", EMPTY_AND,
                java.util.List.of(),
                java.util.List.of(
                        new RuleVersionSnapshot.DecisionBinding("BLOCK", 30),
                        new RuleVersionSnapshot.DecisionBinding("PASS", 10)),
                java.util.List.of(), "DECISION_TREE");
        SceneRuleIndex index = new SceneRuleIndex();
        index.update("t1", "fraud", "*", java.util.List.of(snap));
        EvalEngine engine = new EvalEngine(index, new EvalContextAssembler(java.util.List.of(), java.util.List.of()),
                java.util.Map.of(), java.util.Map.of("DECISION_TREE", treeExec));

        EvalResult r = engine.evaluate(event("t1", "fraud"));

        org.junit.jupiter.api.Assertions.assertEquals("PASS", r.finalDecision().code());   // 不是 BLOCK
        org.junit.jupiter.api.Assertions.assertEquals("低危", r.finalDecision().category());
        org.junit.jupiter.api.Assertions.assertEquals("低危", r.category());                // 聚合 EvalResult.category
    }

    /** 多条决策树各自 category 都保留在 hitDecisions,finalDecision.category=胜出者。 */
    @org.junit.jupiter.api.Test
    void allHits_multipleTrees_eachCategoryPreserved() {
        com.sstlfsj.rule.kernel.api.spi.executor.RuleVersionExecutor dev = (s, c) -> {
            com.sstlfsj.rule.kernel.api.model.Decision d =
                    new com.sstlfsj.rule.kernel.api.model.Decision("REVIEW", "", 20, s.ruleVersionId(), "中危");
            return new com.sstlfsj.rule.kernel.api.model.EvalResult(true, d, java.util.List.of(d),
                    java.util.List.of(), null, java.util.List.of(), null, "中危", null);
        };
        com.sstlfsj.rule.kernel.api.spi.executor.RuleVersionExecutor amt = (s, c) -> {
            com.sstlfsj.rule.kernel.api.model.Decision d =
                    new com.sstlfsj.rule.kernel.api.model.Decision("REVIEW", "", 10, s.ruleVersionId(), "大额");
            return new com.sstlfsj.rule.kernel.api.model.EvalResult(true, d, java.util.List.of(d),
                    java.util.List.of(), null, java.util.List.of(), null, "大额", null);
        };
        RuleVersionSnapshot s1 = new RuleVersionSnapshot(1L, "fraud", "t1", EMPTY_AND, java.util.List.of(),
                java.util.List.of(new RuleVersionSnapshot.DecisionBinding("REVIEW", 20)), java.util.List.of(), "DEV");
        RuleVersionSnapshot s2 = new RuleVersionSnapshot(2L, "fraud", "t1", EMPTY_AND, java.util.List.of(),
                java.util.List.of(new RuleVersionSnapshot.DecisionBinding("REVIEW", 10)), java.util.List.of(), "AMT");
        SceneRuleIndex index = new SceneRuleIndex();
        index.setStrategy("t1", "fraud", com.sstlfsj.rule.kernel.api.model.SceneExecutionStrategy.ALL_HITS);
        index.update("t1", "fraud", "*", java.util.List.of(s1, s2));
        EvalEngine engine = new EvalEngine(index, new EvalContextAssembler(java.util.List.of(), java.util.List.of()),
                java.util.Map.of(), java.util.Map.of("DEV", dev, "AMT", amt));

        EvalResult r = engine.evaluate(event("t1", "fraud"));

        java.util.List<String> cats = r.hitDecisions().stream()
                .map(com.sstlfsj.rule.kernel.api.model.Decision::category).sorted().toList();
        org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of("中危", "大额"), cats);
        org.junit.jupiter.api.Assertions.assertEquals("中危", r.finalDecision().category());  // priority 20 胜
    }

    /** 回归:FIRST_HIT 下布尔规则(executor 不自选决策)应由 binding 赋决策,winner 非 null。 */
    @org.junit.jupiter.api.Test
    void firstHit_booleanRule_winnerFromBindingNotNull() {
        com.sstlfsj.rule.kernel.api.spi.executor.RuleVersionExecutor boolExec =
                (s, c) -> com.sstlfsj.rule.kernel.api.model.EvalResult.hit();   // finalDecision/hitDecisions 空
        RuleVersionSnapshot snap = new RuleVersionSnapshot(1L, "fraud", "t1", EMPTY_AND, java.util.List.of(),
                java.util.List.of(new RuleVersionSnapshot.DecisionBinding("PASS", 5)), java.util.List.of(), "AST_BOOLEAN");
        SceneRuleIndex index = new SceneRuleIndex();
        index.setStrategy("t1", "fraud", com.sstlfsj.rule.kernel.api.model.SceneExecutionStrategy.FIRST_HIT);
        index.update("t1", "fraud", "*", java.util.List.of(snap));
        EvalEngine engine = new EvalEngine(index, new EvalContextAssembler(java.util.List.of(), java.util.List.of()),
                java.util.Map.of(), java.util.Map.of("AST_BOOLEAN", boolExec));

        EvalResult r = engine.evaluate(event("t1", "fraud"));

        org.junit.jupiter.api.Assertions.assertTrue(r.ruleHit());
        org.junit.jupiter.api.Assertions.assertNotNull(r.finalDecision());
        org.junit.jupiter.api.Assertions.assertEquals("PASS", r.finalDecision().code());
        org.junit.jupiter.api.Assertions.assertNull(r.finalDecision().category());
    }
```

- [ ] **Step 2: 跑测试确认失败**

```bash
$MVN -pl rule-kernel test -Dtest='EvalEngineStrategyTest' -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | grep -E 'Tests run|expected|BUILD'
```
Expected: 新 3 个用例 FAIL —— 现在 ALL_HITS 用最高优先级 binding(BLOCK 覆盖 PASS)、聚合 category 恒 null、FIRST_HIT 布尔 winner 为 null。

- [ ] **Step 3: 实现**

(3a) 在 `EvalEngine` 加私有方法（放在 `maxBindingPriority` 方法旁）：

```java
    /**
     * 一条命中规则贡献的决策：executor 自选了决策（tree/table，hitDecisions 非空）就用它（带 category）；
     * 否则（boolean/scorecard）回退按最高优先级 binding 赋决策（category=null）。
     */
    private static List<Decision> resolveRuleDecisions(RuleVersionSnapshot snap, EvalResult r) {
        if (!r.hitDecisions().isEmpty()) return r.hitDecisions();
        return snap.decisionBindings().stream()
                .max(Comparator.comparingInt(RuleVersionSnapshot.DecisionBinding::priority)
                        .thenComparing(RuleVersionSnapshot.DecisionBinding::decisionCode))
                .map(b -> List.<Decision>of(
                        new Decision(b.decisionCode(), "", b.priority(), snap.ruleVersionId())))
                .orElse(List.of());
    }
```

(3b) 替换 `evaluateAllCandidates` 中 `if (r.ruleHit()) { ... }` 决策收集块（原从 binding 重建的那段）为：

```java
                if (r.ruleHit()) {
                    hitDecisions.addAll(resolveRuleDecisions(snap, r));
                }
```

(3c) 替换 `evaluateAllCandidates` 末尾的 `return new EvalResult(...)`，把第 8 位 category 从 `null` 改为 finalDecision 同源：

```java
        return new EvalResult(
                !hitDecisions.isEmpty(),
                finalDecision,
                List.copyOf(hitDecisions),
                List.copyOf(allTraces),
                errorCode,
                List.of(),
                aggregatedScore,
                finalDecision != null ? finalDecision.category() : null,
                null
        );
```

(3d) 替换 `evaluateFirstHit` 的命中分支：

```java
                if (r.ruleHit()) {
                    Decision winner = resolveRuleDecisions(snap, r).stream()
                            .max(DECISION_PRECEDENCE)
                            .orElse(null);
                    return new EvalResult(true, winner,
                            winner == null ? List.of() : List.of(winner),
                            r.nodeTrace(), r.errorCode(), List.of(), r.score(),
                            winner == null ? null : winner.category(), null);
                }
```

- [ ] **Step 4: 跑测试确认通过（含既有用例不破）**

```bash
$MVN -pl rule-kernel test -Dtest='EvalEngineStrategyTest,EvalEngineTest,ScorecardExecutorTest,DecisionTableExecutorTest' -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | grep -E 'Tests run|BUILD'
```
Expected: 全部 `Failures: 0, Errors: 0` + `BUILD SUCCESS`。

- [ ] **Step 5: 提交**

```bash
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/engine/EvalEngine.java \
        rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/engine/EvalEngineStrategyTest.java
git commit -m "fix(kernel): 引擎聚合用 executor 自选决策(resolveRuleDecisions),修 tree/table 决策被 binding 覆盖 + FIRST_HIT 布尔缺口 + category 同源"
```

---

## Task 4: `EvaluationSession` 增 `category` 字段

**Files:**
- Modify: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/domain/EvaluationSession.java`
- Test: `rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/domain/EvaluationSessionTest.java`

- [ ] **Step 1: 写失败测试**（追加到 `EvaluationSessionTest`）

```java
    @org.junit.jupiter.api.Test
    void category_setAndGet() {
        EvaluationSession s = new EvaluationSession();
        org.junit.jupiter.api.Assertions.assertNull(s.getCategory());
        s.setCategory("中危");
        org.junit.jupiter.api.Assertions.assertEquals("中危", s.getCategory());
    }
```

- [ ] **Step 2: 跑测试确认失败**

```bash
$MVN -pl rule-eval-svc -am test -Dtest='EvaluationSessionTest' -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | grep -E 'ERROR|BUILD'
```
Expected: 编译失败——`getCategory`/`setCategory` 不存在。

- [ ] **Step 3: 实现**（在 `EvaluationSession` 的 `private Double score;` 之后加一行）

```java
    /** SCORECARD 累计分；AST_BOOLEAN 等无分场景为 null。 */
    private Double score;
    /** DECISION_TREE 主分类（finalDecision 同源）；其他 kind 为 null。 */
    private String category;
```

- [ ] **Step 4: 跑测试确认通过**

```bash
$MVN -pl rule-eval-svc -am test -Dtest='EvaluationSessionTest' -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | grep -E 'Tests run|BUILD'
```
Expected: `Failures: 0, Errors: 0` + `BUILD SUCCESS`。

- [ ] **Step 5: 提交**

```bash
git add rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/domain/EvaluationSession.java \
        rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/domain/EvaluationSessionTest.java
git commit -m "feat(eval): EvaluationSession 增 category 字段"
```

---

## Task 5: V1_10 迁移——`evaluation_session` 加 `category` 列

**Files:**
- Create: `rule-config-svc/src/main/resources/db/migration/V1_10__add_session_category.sql`

- [ ] **Step 1: 写迁移文件**

```sql
-- D42 DECISION_TREE 主分类审计：evaluation_session 增 category 列（finalDecision 同源，单列可聚合；明细在 hit_decisions）。
-- greenfield 无生产数据，空表直接 ADD。
ALTER TABLE evaluation_session
    ADD COLUMN category VARCHAR(64) NULL COMMENT 'DECISION_TREE 主分类（finalDecision 同源）；其他 kind NULL' AFTER score;
```

- [ ] **Step 2: 验证迁移可应用（干净构建触发 Flyway 校验）**

```bash
$MVN -q -pl rule-app -am package -DskipTests 2>&1 | tail -3
```
Expected: `BUILD SUCCESS`（迁移文件被打进 classpath；实际 ALTER 在下次 app 启动应用，V1_9→V1_10 顺序合法）。

- [ ] **Step 3: 提交**

```bash
git add rule-config-svc/src/main/resources/db/migration/V1_10__add_session_category.sql
git commit -m "feat(db): V1_10 evaluation_session 加 category 列"
```

---

## Task 6: `AuditPersister`——注 ObjectMapper + hit_decisions 对象 + session.category

**Files:**
- Modify: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/async/AuditPersister.java`
- Test: `rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/async/AuditPersisterTest.java`

- [ ] **Step 1: 写失败测试**（追加到 `AuditPersisterTest`；注意所有 `new AuditPersister(...)` 需补 ObjectMapper 参数——见 Step 3）

```java
    @org.junit.jupiter.api.Test
    void hitDecisions_serializedAsObjectsWithCategory_andSessionCategoryFromFinal() throws Exception {
        EvaluationSessionMapper mapper = mock(EvaluationSessionMapper.class);
        TraceWriter traceWriter = mock(TraceWriter.class);
        tools.jackson.databind.ObjectMapper om = tools.jackson.databind.json.JsonMapper.builder().build();
        AuditPersister persister = new AuditPersister(2000, 200, 50, mapper, traceWriter, om);
        persister.afterPropertiesSet();

        RuleEvent event = RuleEvent.builder().tenantId("1").sceneCode("s").eventType("t")
                .subjectId("u1").eventId("e9").source(EventSource.HTTP).occurredAt(Instant.now()).build();
        com.sstlfsj.rule.kernel.api.model.Decision dev =
                new com.sstlfsj.rule.kernel.api.model.Decision("REVIEW", "", 20, 11L, "中危");
        com.sstlfsj.rule.kernel.api.model.Decision amt =
                new com.sstlfsj.rule.kernel.api.model.Decision("REVIEW", "", 10, 22L, "大额");
        EvalResult r = new EvalResult(true, dev, java.util.List.of(dev, amt), java.util.List.of(),
                null, java.util.List.of(), null, "中危", null);
        persister.onAudit(new AuditRecorded(91L, event, "PULL", 2, r, null, null));

        Thread.sleep(300);
        persister.destroy();

        ArgumentCaptor<EvaluationSession> captor = ArgumentCaptor.forClass(EvaluationSession.class);
        verify(mapper, times(1)).insert(captor.capture());
        EvaluationSession s = captor.getValue();
        assertThat(s.getCategory()).isEqualTo("中危");                       // finalDecision 同源
        assertThat(s.getHitDecisions()).contains("\"category\":\"中危\"")
                .contains("\"category\":\"大额\"").contains("\"ruleVersionId\":11");
    }
```

- [ ] **Step 2: 跑测试确认失败**

```bash
$MVN -pl rule-eval-svc -am test -Dtest='AuditPersisterTest' -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | grep -E 'ERROR|Tests run|BUILD'
```
Expected: 编译失败——6 参 `AuditPersister` 构造器不存在（当前 5 参）。

- [ ] **Step 3: 实现**

(3a) `AuditPersister` 顶部 import 调整：删 `import java.util.stream.Collectors;`，加 `import tools.jackson.databind.ObjectMapper;`。

(3b) 加字段并改两个构造器：

```java
    private final EvaluationSessionMapper sessionMapper;
    private final TraceWriter traceWriter;
    private final ObjectMapper objectMapper;
```

```java
    public AuditPersister(int queueCapacity, int batchSize, long flushIntervalMs,
                          EvaluationSessionMapper sessionMapper, TraceWriter traceWriter,
                          ObjectMapper objectMapper) {
        this.queueCapacity = queueCapacity;
        this.batchSize = batchSize;
        this.flushIntervalMs = flushIntervalMs;
        this.sessionMapper = sessionMapper;
        this.traceWriter = traceWriter;
        this.objectMapper = objectMapper;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AuditPersister(EvaluationSessionMapper sessionMapper, TraceWriter traceWriter,
                          ObjectMapper objectMapper) {
        this(10000, 500, 200, sessionMapper, traceWriter, objectMapper);
    }
```

(3c) 在 `toSession` 中替换 `setHitDecisions(...)` 段，并在 `setScore(...)` 后加 `setCategory(...)`：

```java
        s.setScore(r.score());   // SCORECARD 累计分；其他 kind 为 null
        s.setCategory(r.finalDecision() != null ? r.finalDecision().category() : null);
        s.setHitDecisions(objectMapper.writeValueAsString(
                r.hitDecisions().stream()
                        .map(d -> new HitDecisionView(d.code(), d.category(), d.fromRuleVersionId()))
                        .toList()));
```

> 删掉原 `setHitDecisions` 的 `Collectors.joining` 写法。`r.hitDecisions()` 为空时 `writeValueAsString` 输出 `[]`，语义不变。

(3d) 在 `AuditPersister` 类内加私有 record（hit_decisions 的序列化视图）：

```java
    /** hit_decisions JSON 元素：每条命中决策的码 + 分类 + 来源规则版本。 */
    private record HitDecisionView(String code, String category, Long ruleVersionId) {}
```

(3e) 既有 `AuditPersisterTest` 中所有 `new AuditPersister(2000, 200, 50, mapper, traceWriter)` 改为 6 参，补 `tools.jackson.databind.json.JsonMapper.builder().build()`：

```java
        tools.jackson.databind.ObjectMapper om = tools.jackson.databind.json.JsonMapper.builder().build();
        AuditPersister persister = new AuditPersister(2000, 200, 50, mapper, traceWriter, om);
```
（`insertsTerminalSessionOnceAndWritesTrace` / `blockedBy_nonNull_persistsBlockedStatusAndBlockedBy` / `scorecardResult_persistsScore` 三处均需改。）

- [ ] **Step 4: 跑测试确认通过**

```bash
$MVN -pl rule-eval-svc -am test -Dtest='AuditPersisterTest' -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | grep -E 'Tests run|BUILD'
```
Expected: `Failures: 0, Errors: 0` + `BUILD SUCCESS`。

- [ ] **Step 5: 提交**

```bash
git add rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/async/AuditPersister.java \
        rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/async/AuditPersisterTest.java
git commit -m "feat(eval): 审计 hit_decisions 升级对象数组(含 category/ruleVersionId) + 落 session.category"
```

---

## Task 7: 全量回归 + 功能冒烟

**Files:** 无（验证）

- [ ] **Step 1: 两模块全量测试**

```bash
$MVN -pl rule-kernel,rule-eval-svc -am test 2>&1 | grep -E 'Tests run:.*Failures|BUILD' | tail -6
```
Expected: 所有行 `Failures: 0, Errors: 0` + `BUILD SUCCESS`。

- [ ] **Step 2: 功能冒烟（决策树端到端 category 落库）**

seed 一条决策树规则（含多档 binding + 叶子 category），起 app，POST `/api/v1/rule/evaluate`，查 `evaluation_session.category` 与 `hit_decisions`。

```bash
# 复用既有 mvn-env 与 app 启动方式；评估后查库：
/usr/bin/mysql -uroot -p123456 rule_engine -e \
  "SELECT status, final_decision, category, hit_decisions FROM evaluation_session WHERE tenant_id=9001 ORDER BY id DESC LIMIT 1"
```
Expected: `category` 为叶子分类（非 null），`hit_decisions` 为 `[{"code":...,"category":...,"ruleVersionId":...}]` 对象数组。

> 注：决策树 seed 不在现有 `LoadTestSeeder`（其为 AST_BOOLEAN）。本冒烟可手工 seed 或新增一个临时 seed 方法；非阻塞项，单测已覆盖核心逻辑。

- [ ] **Step 3: 无新增提交**（纯验证；若 Step 2 发现问题回到对应 Task 修复）

---

## Self-Review（计划自检）

**Spec 覆盖：**
- §3.1 Decision.category → Task 1 ✓
- §3.2 DecisionTreeExecutor 焊接 → Task 2 ✓
- §3.3 resolveRuleDecisions + 两策略 + 聚合 category → Task 3 ✓
- §3.4 AuditPersister(ObjectMapper/hit_decisions/session.category) → Task 6 ✓
- §3.4 EvaluationSession.category → Task 4 ✓
- §3.5 V1_10 迁移 → Task 5 ✓
- §5 测试（Decision/Tree/Engine 回归/AuditPersister/Session）→ Task 1-6 各自 + Task 7 全量 ✓

**类型一致性：** `Decision` 5 参 `(code,name,priority,fromRuleVersionId,category)` 全 Task 统一；`resolveRuleDecisions(RuleVersionSnapshot, EvalResult)` 返回 `List<Decision>`，Task 3 两处调用一致；`HitDecisionView(code,category,ruleVersionId)` Task 6 内自洽；`AuditPersister` 6 参构造器 Task 6 定义并在测试统一使用。

**占位符：** 无 TBD/TODO；每个改代码步骤均含完整代码。Task 2/7 的「以既有用例构造方式为准」「手工 seed」为对既有测试工厂/冒烟的合理指引，非代码占位。
