# 评估期预编译(纯编译版)实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 `AST_BOOLEAN` 规则的求值从解释器树遍历换成"一次编译成嵌套 `Predicate<EvalContext>` 闭包、之后每次评估直接 `test(ctx)`",灰度可控、随时回退、trace 语义不变。

**Architecture:** 新增 `AstCompiler`(AST→闭包)+ `RuleVersionCache`(按 ruleVersionId 缓存)+ `CompiledExecutor`(包住 `InterpretedExecutor`,仅服务非 trace 快路径,开 trace/关开关/灰度未命中时委托解释器)。装配点改 `EvalAutoConfiguration.ruleVersionExecutor()` 一处,`EvalEngine` 零改动。`CompiledPredicateEvictor` 监听索引热更清缓存。

**Tech Stack:** Java 25、Spring Boot 4、Modulith 事件、JMH(rule-benchmark)、JUnit5 + AssertJ。

**前置:** 跑 Maven 前先用 `mvn-env` skill 设置 `$MVN`(本机 mvn 不在 PATH)。

**设计依据:** `docs/superpowers/specs/2026-06-13-eval-precompilation-design.md`。

---

## 文件结构

新建(`rule-kernel`,全部落 `internal/evaluator` 包 —— 因叶子三态门面 `ConditionEvaluation`/`ConditionOutcome` 是该包私有):
- `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/evaluator/CompileErrorPolicy.java` — 编译失败处置策略枚举
- `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/evaluator/AstCompiler.java` — AST→`Predicate<EvalContext>` 递归编译
- `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/evaluator/RuleVersionCache.java` — `ruleVersionId → Predicate` 缓存
- `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/evaluator/CompiledExecutor.java` — 编译执行器

新建(`rule-eval-svc`):
- `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/CompiledExecutorProperties.java` — 灰度配置
- `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/listener/CompiledPredicateEvictor.java` — 缓存失效监听

新建(`rule-benchmark`):
- `rule-benchmark/src/main/java/com/sstlfsj/rule/benchmark/InterpretedExecBenchmark.java` — Phase 0 闸基准
- `rule-benchmark/src/main/java/com/sstlfsj/rule/benchmark/CompiledVsInterpretedBenchmark.java` — A/B 对照

修改:
- `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/EvalAutoConfiguration.java` — 装配 `CompiledExecutor` + `RuleVersionCache` + 配置
- `rule-app/src/main/resources/application.yml` — 新增配置默认值(注释说明)
- `docs/08-evolution.md`、`docs/00-decisions.md` — 状态与决策日志

测试:
- `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/evaluator/AstCompilerTest.java`
- `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/evaluator/RuleVersionCacheTest.java`
- `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/evaluator/CompiledExecutorTest.java`
- `rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/listener/CompiledPredicateEvictorTest.java`

---

## Task 1: Phase 0 benchmark 闸(先行,go/no-go)

**Files:**
- Create: `rule-benchmark/src/main/java/com/sstlfsj/rule/benchmark/InterpretedExecBenchmark.java`

- [ ] **Step 1: 写基准**

```java
package com.sstlfsj.rule.benchmark;

import com.sstlfsj.rule.kernel.api.model.ConditionTypes;
import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.EventSource;
import com.sstlfsj.rule.kernel.api.model.MetricValue;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.model.ast.AndNode;
import com.sstlfsj.rule.kernel.api.model.ast.AstNode;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.internal.condition.KernelEvaluators;
import com.sstlfsj.rule.kernel.internal.evaluator.InterpretedExecutor;
import com.sstlfsj.rule.kernel.internal.evaluator.TraceScope;
import org.openjdk.jmh.annotations.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Phase 0 闸：仅量 InterpretedExecutor.execute() 纯 AST 求值耗时（非 trace 快路径）。
 * AST = AND(N 个 GT 条件)，全部 provided metric 命中。产出 ns/op 对照生产端到端时延，
 * 估算 AST 求值占比，决定 §2.13 是否值得做。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class InterpretedExecBenchmark {

    @Param({"5", "20", "50"})
    public int n;

    private InterpretedExecutor executor;
    private RuleVersionSnapshot snapshot;
    private EvalContext ctx;

    @Setup
    public void setup() {
        executor = new InterpretedExecutor(KernelEvaluators.defaults());
        List<AstNode> conds = new ArrayList<>();
        Map<String, MetricValue> metrics = new HashMap<>();
        for (int i = 0; i < n; i++) {
            String mc = "m" + i;
            conds.add(new ConditionNode(ConditionTypes.GT, mc, null, Map.of("threshold", 0L), 0.0));
            metrics.put(mc, new MetricValue(1L, "LONG", "PROVIDED"));
        }
        AstNode ast = new AndNode(conds, null, null);
        snapshot = new RuleVersionSnapshot(1L, "scene", "t1", ast, null, null, null, "AST_BOOLEAN");
        RuleEvent event = new RuleEvent("t1", "scene", "ORDER", "sub1", "evt-1",
                Instant.now(), Map.of(), Map.of(), EventSource.HTTP);
        ctx = new EvalContext("t1", event, null, metrics, Instant.now());
    }

    @Benchmark
    public boolean executeFastPath() throws Exception {
        return ScopedValue.where(TraceScope.COLLECT, false)
                .call(() -> executor.execute(snapshot, ctx).ruleHit());
    }
}
```

- [ ] **Step 2: 打包并运行**

```bash
$MVN -pl rule-benchmark -am package -DskipTests
java -jar rule-benchmark/target/benchmarks.jar InterpretedExecBenchmark -prof gc
```
Expected: 输出每个 n(5/20/50)的 `executeFastPath` ns/op 与 `gc.alloc.rate.norm`(每 op 分配字节)。记录 50 条件的 ns/op。

- [ ] **Step 3: 应用闸决策(人工 checkpoint)**

用 50 条件的 ns/op 对照本项目生产端到端评估时延(团队已知值,主要由 metric 取数主导):
- AST 求值占比 **>20%** → 收益真实,继续 Task 2。
- 占比 **<5%**(被取数淹没)→ **停**,把工作量挪去优化取数。回报评估结论,不继续。
- 5%–20% → 记录数据,据绝对 QPS / CPU 成本与用户确认后再定。

记录结论(ns/op + 估算占比 + go/no-go)到本计划末尾或回报用户。

- [ ] **Step 4: Commit**

```bash
git add rule-benchmark/src/main/java/com/sstlfsj/rule/benchmark/InterpretedExecBenchmark.java
git commit -m "perf(benchmark): Phase 0 闸 — 量 InterpretedExecutor 纯 AST 求值耗时"
```

---

## Task 2: AstCompiler(AST→闭包,与解释器平价)

**Files:**
- Create: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/evaluator/AstCompiler.java`
- Test: `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/evaluator/AstCompilerTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.sstlfsj.rule.kernel.evaluator;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import com.sstlfsj.rule.kernel.api.model.EventSource;
import com.sstlfsj.rule.kernel.api.model.ast.*;
import com.sstlfsj.rule.kernel.api.spi.condition.ConditionEvaluator;
import com.sstlfsj.rule.kernel.internal.evaluator.AstCompiler;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AstCompilerTest {

    private static final String T = "ALWAYS_TRUE";
    private static final String F = "ALWAYS_FALSE";

    private final Map<String, ConditionEvaluator> evaluators = Map.of(
            T, (node, ctx) -> true,
            F, (node, ctx) -> false);

    private final AstCompiler compiler = new AstCompiler(evaluators);

    private ConditionNode t() { return new ConditionNode(T, null, null, Map.of(), 0.0); }
    private ConditionNode f() { return new ConditionNode(F, null, null, Map.of(), 0.0); }

    private EvalContext ctx() {
        RuleEvent event = new RuleEvent("t1", "scene1", "ORDER", "u1", "evt-1",
                Instant.now(), Map.of(), null, EventSource.HTTP);
        return new EvalContext("t1", event, null, Map.of(), Instant.parse("2026-06-01T00:00:00Z"));
    }

    @Test
    void condition_true() {
        assertThat(compiler.compile(t()).test(ctx())).isTrue();
    }

    @Test
    void condition_false() {
        assertThat(compiler.compile(f()).test(ctx())).isFalse();
    }

    @Test
    void and_allTrue_true_oneFalse_false() {
        assertThat(compiler.compile(new AndNode(List.of(t(), t(), t()), null, null)).test(ctx())).isTrue();
        assertThat(compiler.compile(new AndNode(List.of(t(), f(), t()), null, null)).test(ctx())).isFalse();
    }

    @Test
    void or_oneTrue_true_allFalse_false() {
        assertThat(compiler.compile(new OrNode(List.of(f(), t(), f()), null, null)).test(ctx())).isTrue();
        assertThat(compiler.compile(new OrNode(List.of(f(), f()), null, null)).test(ctx())).isFalse();
    }

    @Test
    void not_inverts() {
        assertThat(compiler.compile(new NotNode(t())).test(ctx())).isFalse();
        assertThat(compiler.compile(new NotNode(f())).test(ctx())).isTrue();
    }

    @Test
    void xor_exactlyOneTrue_true_else_false() {
        assertThat(compiler.compile(new XorNode(List.of(t(), f(), f()), null)).test(ctx())).isTrue();
        assertThat(compiler.compile(new XorNode(List.of(t(), t()), null)).test(ctx())).isFalse();
        assertThat(compiler.compile(new XorNode(List.of(f(), f()), null)).test(ctx())).isFalse();
    }

    @Test
    void nested_and_or() {
        // AND(true, OR(false, true)) = true
        AstNode ast = new AndNode(List.of(t(), new OrNode(List.of(f(), t()), null, null)), null, null);
        assertThat(compiler.compile(ast).test(ctx())).isTrue();
    }

    @Test
    void condition_noEvaluator_isFalse_notThrow() {
        // 镜像解释器：无算子 → ERROR → 不命中（false），不抛
        ConditionNode unknown = new ConditionNode("UNKNOWN", "m1", null, Map.of(), 0.0);
        assertThat(compiler.compile(unknown).test(ctx())).isFalse();
    }

    @Test
    void nonBooleanNode_throwsIllegalArgument() {
        assertThatThrownBy(() -> compiler.compile(new DecisionLeafNode("BLOCK", "HIGH")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DecisionLeafNode");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `$MVN -pl rule-kernel -am -Dtest=AstCompilerTest test`
Expected: 编译失败(`AstCompiler` 不存在)。

- [ ] **Step 3: 写实现**

```java
package com.sstlfsj.rule.kernel.internal.evaluator;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.ast.AndNode;
import com.sstlfsj.rule.kernel.api.model.ast.AstNode;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.model.ast.DecisionLeafNode;
import com.sstlfsj.rule.kernel.api.model.ast.DecisionTableNode;
import com.sstlfsj.rule.kernel.api.model.ast.IfNode;
import com.sstlfsj.rule.kernel.api.model.ast.NotNode;
import com.sstlfsj.rule.kernel.api.model.ast.OrNode;
import com.sstlfsj.rule.kernel.api.model.ast.ScorecardRootNode;
import com.sstlfsj.rule.kernel.api.model.ast.XorNode;
import com.sstlfsj.rule.kernel.api.spi.condition.ConditionEvaluator;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * 把 AST_BOOLEAN 条件 AST 编译为嵌套 {@link Predicate} 闭包，替换解释器树遍历。
 * 语义逐一对齐 {@link InterpretedExecutor}：叶子经包私有 {@link ConditionEvaluation} 三态门面，
 * ERROR(取数失败/无算子) 视为不命中(false)；XOR 全量求值恰一个真才命中。
 * 组合节点编译期收数组、求值期下标循环，求值路径零额外分配（不用 enhanced-for，避免 Iterator 分配）。
 */
public final class AstCompiler {

    /** conditionType → 算子映射。 */
    private final Map<String, ConditionEvaluator> evaluators;

    /**
     * @param evaluators conditionType 到 ConditionEvaluator 的映射
     */
    public AstCompiler(Map<String, ConditionEvaluator> evaluators) {
        this.evaluators = Map.copyOf(evaluators);
    }

    /**
     * 递归编译布尔节点为谓词。
     *
     * @param node 布尔 AST 节点(And/Or/Not/Xor/Condition)
     * @return 等价于解释器命中布尔的谓词
     * @throws IllegalArgumentException 遇非布尔节点(由专属 executor 处理)
     */
    public Predicate<EvalContext> compile(AstNode node) {
        return switch (node) {
            case ConditionNode c -> ctx -> {
                ConditionOutcome o = ConditionEvaluation.evaluate(c, ctx, evaluators);
                return !o.isError() && o.satisfied();
            };
            case AndNode a -> {
                Predicate<EvalContext>[] ps = compileChildren(a.children());
                yield ctx -> {
                    for (int i = 0; i < ps.length; i++) if (!ps[i].test(ctx)) return false;
                    return true;
                };
            }
            case OrNode o -> {
                Predicate<EvalContext>[] ps = compileChildren(o.children());
                yield ctx -> {
                    for (int i = 0; i < ps.length; i++) if (ps[i].test(ctx)) return true;
                    return false;
                };
            }
            case NotNode n -> {
                Predicate<EvalContext> p = compile(n.child());
                yield ctx -> !p.test(ctx);
            }
            case XorNode x -> {
                Predicate<EvalContext>[] ps = compileChildren(x.children());
                yield ctx -> {
                    int t = 0;
                    for (int i = 0; i < ps.length; i++) if (ps[i].test(ctx)) t++;
                    return t == 1;
                };
            }
            case ScorecardRootNode ignored ->
                    throw new IllegalArgumentException("ScorecardRootNode 非布尔节点，不可编译");
            case IfNode ignored ->
                    throw new IllegalArgumentException("IfNode 非布尔节点，不可编译");
            case DecisionLeafNode ignored ->
                    throw new IllegalArgumentException("DecisionLeafNode 非布尔节点，不可编译");
            case DecisionTableNode ignored ->
                    throw new IllegalArgumentException("DecisionTableNode 非布尔节点，不可编译");
        };
    }

    @SuppressWarnings("unchecked")
    private Predicate<EvalContext>[] compileChildren(List<AstNode> children) {
        Predicate<EvalContext>[] ps = new Predicate[children.size()];
        for (int i = 0; i < children.size(); i++) ps[i] = compile(children.get(i));
        return ps;
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `$MVN -pl rule-kernel -am -Dtest=AstCompilerTest test`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/evaluator/AstCompiler.java \
        rule-kernel/src/test/java/com/sstlfsj/rule/kernel/evaluator/AstCompilerTest.java
git commit -m "feat(eval): AstCompiler — AST_BOOLEAN 编译为闭包，与解释器平价"
```

---

## Task 3: RuleVersionCache(编译产物缓存)

**Files:**
- Create: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/evaluator/RuleVersionCache.java`
- Test: `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/evaluator/RuleVersionCacheTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.sstlfsj.rule.kernel.evaluator;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.internal.evaluator.RuleVersionCache;
import org.junit.jupiter.api.Test;

import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

class RuleVersionCacheTest {

    private final Predicate<EvalContext> alwaysTrue = ctx -> true;
    private final Predicate<EvalContext> alwaysFalse = ctx -> false;

    @Test
    void get_missing_returnsNull() {
        assertThat(new RuleVersionCache().get(1L)).isNull();
    }

    @Test
    void putIfAbsent_thenGet_returnsSame() {
        RuleVersionCache cache = new RuleVersionCache();
        cache.putIfAbsent(1L, alwaysTrue);
        assertThat(cache.get(1L)).isSameAs(alwaysTrue);
    }

    @Test
    void putIfAbsent_doesNotOverwrite() {
        RuleVersionCache cache = new RuleVersionCache();
        cache.putIfAbsent(1L, alwaysTrue);
        cache.putIfAbsent(1L, alwaysFalse);
        assertThat(cache.get(1L)).isSameAs(alwaysTrue);
    }

    @Test
    void evictAll_clears() {
        RuleVersionCache cache = new RuleVersionCache();
        cache.putIfAbsent(1L, alwaysTrue);
        cache.putIfAbsent(2L, alwaysFalse);
        assertThat(cache.size()).isEqualTo(2);
        cache.evictAll();
        assertThat(cache.size()).isZero();
        assertThat(cache.get(1L)).isNull();
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `$MVN -pl rule-kernel -am -Dtest=RuleVersionCacheTest test`
Expected: 编译失败(`RuleVersionCache` 不存在)。

- [ ] **Step 3: 写实现**

```java
package com.sstlfsj.rule.kernel.internal.evaluator;

import com.sstlfsj.rule.kernel.api.model.EvalContext;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * 编译产物缓存：ruleVersionId → 编译后的布尔谓词。
 * 键不可变(发布版本不可变)，故缓存永不脏；{@link #evictAll()} 仅为内存卫生
 * (发布/场景变更后清空，下次评估惰性重编译)。
 */
public final class RuleVersionCache {

    private final ConcurrentHashMap<Long, Predicate<EvalContext>> cache = new ConcurrentHashMap<>();

    /**
     * 取缓存谓词。
     *
     * @param ruleVersionId 规则版本 id
     * @return 缓存的谓词，不存在返回 null
     */
    public Predicate<EvalContext> get(long ruleVersionId) {
        return cache.get(ruleVersionId);
    }

    /**
     * 缺失时放入(并发幂等)。
     *
     * @param ruleVersionId 规则版本 id
     * @param predicate     编译产物
     */
    public void putIfAbsent(long ruleVersionId, Predicate<EvalContext> predicate) {
        cache.putIfAbsent(ruleVersionId, predicate);
    }

    /** 清空全部编译产物(发布/场景变更后调用)。 */
    public void evictAll() {
        cache.clear();
    }

    /** @return 当前缓存条目数(测试/可观测用) */
    public int size() {
        return cache.size();
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `$MVN -pl rule-kernel -am -Dtest=RuleVersionCacheTest test`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/evaluator/RuleVersionCache.java \
        rule-kernel/src/test/java/com/sstlfsj/rule/kernel/evaluator/RuleVersionCacheTest.java
git commit -m "feat(eval): RuleVersionCache — 按 ruleVersionId 缓存编译产物"
```

---

## Task 4: CompiledExecutor + CompileErrorPolicy

**Files:**
- Create: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/evaluator/CompileErrorPolicy.java`
- Create: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/evaluator/CompiledExecutor.java`
- Test: `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/evaluator/CompiledExecutorTest.java`

- [ ] **Step 1: 写 CompileErrorPolicy 枚举**

```java
package com.sstlfsj.rule.kernel.internal.evaluator;

/** AST 编译失败处置策略。 */
public enum CompileErrorPolicy {
    /** WARN 日志 + 该规则永久回落解释器(默认，编译版永不劣于解释器)。 */
    FALLBACK,
    /** 抛异常中止(发布期不变量违例，运行期宁可炸不静默)。 */
    FAIL
}
```

- [ ] **Step 2: 写失败测试**

```java
package com.sstlfsj.rule.kernel.evaluator;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import com.sstlfsj.rule.kernel.api.model.EventSource;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.model.ast.AndNode;
import com.sstlfsj.rule.kernel.api.model.ast.AstNode;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.model.ast.DecisionLeafNode;
import com.sstlfsj.rule.kernel.api.spi.condition.ConditionEvaluator;
import com.sstlfsj.rule.kernel.internal.evaluator.AstCompiler;
import com.sstlfsj.rule.kernel.internal.evaluator.CompileErrorPolicy;
import com.sstlfsj.rule.kernel.internal.evaluator.CompiledExecutor;
import com.sstlfsj.rule.kernel.internal.evaluator.InterpretedExecutor;
import com.sstlfsj.rule.kernel.internal.evaluator.RuleVersionCache;
import com.sstlfsj.rule.kernel.internal.evaluator.TraceScope;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CompiledExecutorTest {

    private static final String T = "ALWAYS_TRUE";
    private static final String F = "ALWAYS_FALSE";
    private final Map<String, ConditionEvaluator> evaluators = Map.of(
            T, (node, ctx) -> true, F, (node, ctx) -> false);
    private final InterpretedExecutor interpreter = new InterpretedExecutor(evaluators);
    private final AstCompiler compiler = new AstCompiler(evaluators);

    private ConditionNode t() { return new ConditionNode(T, null, null, Map.of(), 0.0); }
    private ConditionNode f() { return new ConditionNode(F, null, null, Map.of(), 0.0); }

    private RuleVersionSnapshot snap(AstNode ast) {
        return new RuleVersionSnapshot(1L, "scene1", "t1", ast, null, null, null,
                "AST_BOOLEAN", "RULE_A", 1L, List.of(), List.of());
    }

    private EvalContext ctx() {
        RuleEvent event = new RuleEvent("t1", "scene1", "ORDER", "u1", "evt-1",
                Instant.now(), Map.of(), null, EventSource.HTTP);
        return new EvalContext("t1", event, null, Map.of(), Instant.parse("2026-06-01T00:00:00Z"));
    }

    private CompiledExecutor executor(boolean enabled, Set<String> whitelist, CompileErrorPolicy policy) {
        return new CompiledExecutor(interpreter, compiler, new RuleVersionCache(), enabled, whitelist, policy);
    }

    @Test
    void disabled_delegatesToInterpreter_withTrace() {
        // 关开关：行为同解释器(含 NodeTrace)
        EvalResult r = executor(false, Set.of(), CompileErrorPolicy.FALLBACK)
                .execute(snap(t()), ctx());
        assertThat(r.ruleHit()).isTrue();
        assertThat(r.nodeTrace()).isNotEmpty();
    }

    @Test
    void enabled_nonTrace_usesCompiled_emptyTrace() throws Exception {
        // 开开关 + 非 trace：走编译，命中正确，trace 为空
        CompiledExecutor exec = executor(true, Set.of(), CompileErrorPolicy.FALLBACK);
        EvalResult hit = ScopedValue.where(TraceScope.COLLECT, false)
                .call(() -> exec.execute(snap(new AndNode(List.of(t(), t()), null, null)), ctx()));
        assertThat(hit.ruleHit()).isTrue();
        assertThat(hit.nodeTrace()).isEmpty();
        EvalResult miss = ScopedValue.where(TraceScope.COLLECT, false)
                .call(() -> exec.execute(snap(new AndNode(List.of(t(), f()), null, null)), ctx()));
        assertThat(miss.ruleHit()).isFalse();
        assertThat(miss.nodeTrace()).isEmpty();
    }

    @Test
    void enabled_traceMode_delegatesToInterpreter() throws Exception {
        // 开开关但开 trace(COLLECT=true)：回落解释器，产出 NodeTrace
        CompiledExecutor exec = executor(true, Set.of(), CompileErrorPolicy.FALLBACK);
        EvalResult r = ScopedValue.where(TraceScope.COLLECT, true)
                .call(() -> exec.execute(snap(t()), ctx()));
        assertThat(r.ruleHit()).isTrue();
        assertThat(r.nodeTrace()).isNotEmpty();
    }

    @Test
    void whitelist_nonMatching_delegatesToInterpreter() throws Exception {
        // 白名单非空且不含本规则 code(RULE_A)：走解释器
        CompiledExecutor exec = executor(true, Set.of("OTHER_RULE"), CompileErrorPolicy.FALLBACK);
        EvalResult r = ScopedValue.where(TraceScope.COLLECT, false)
                .call(() -> exec.execute(snap(t()), ctx()));
        assertThat(r.ruleHit()).isTrue();
        assertThat(r.nodeTrace()).isNotEmpty();
    }

    @Test
    void whitelist_matching_usesCompiled() throws Exception {
        CompiledExecutor exec = executor(true, Set.of("RULE_A"), CompileErrorPolicy.FALLBACK);
        EvalResult r = ScopedValue.where(TraceScope.COLLECT, false)
                .call(() -> exec.execute(snap(t()), ctx()));
        assertThat(r.ruleHit()).isTrue();
        assertThat(r.nodeTrace()).isEmpty();
    }

    @Test
    void compileError_fallback_delegatesAndWarns() throws Exception {
        // 非布尔节点强制编译失败，FALLBACK 策略 → 回落解释器(解释器对该节点抛 IllegalState)
        // 验证：编译异常被 FALLBACK 接住，转交解释器；解释器自身对 DecisionLeafNode 抛 IllegalState
        CompiledExecutor exec = executor(true, Set.of(), CompileErrorPolicy.FALLBACK);
        assertThatThrownBy(() -> ScopedValue.where(TraceScope.COLLECT, false)
                .call(() -> exec.execute(snap(new DecisionLeafNode("BLOCK", "HIGH")), ctx())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DecisionLeafNode");
    }

    @Test
    void compileError_fail_throwsIllegalState() throws Exception {
        // FAIL 策略：编译失败直接抛 IllegalStateException(带 ruleVersionId)
        CompiledExecutor exec = executor(true, Set.of(), CompileErrorPolicy.FAIL);
        assertThatThrownBy(() -> ScopedValue.where(TraceScope.COLLECT, false)
                .call(() -> exec.execute(snap(new DecisionLeafNode("BLOCK", "HIGH")), ctx())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ruleVersionId=1");
    }
}
```

> 注:`compileError_fallback_delegatesAndWarns` 中,FALLBACK 接住编译异常后转交 `InterpretedExecutor`,而解释器对 `DecisionLeafNode` 本就抛 `IllegalStateException`(见 `InterpretedExecutor.eval`)。该测试既验证 FALLBACK 不把编译异常上抛(而是委托),又验证最终行为=解释器行为。FAIL 用例则验证编译异常被包成带 ruleVersionId 的 `IllegalStateException` 上抛。两者异常类型相同但来源不同:FALLBACK 来自解释器、消息含 "DecisionLeafNode";FAIL 来自 CompiledExecutor、消息含 "ruleVersionId=1"。

- [ ] **Step 3: 跑测试确认失败**

Run: `$MVN -pl rule-kernel -am -Dtest=CompiledExecutorTest test`
Expected: 编译失败(`CompiledExecutor` 不存在)。

- [ ] **Step 4: 写实现**

```java
package com.sstlfsj.rule.kernel.internal.evaluator;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.spi.executor.RuleVersionExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.function.Predicate;

/**
 * 编译执行器：把 AST_BOOLEAN 规则编译为 {@link Predicate} 闭包并缓存，替换解释器树遍历。
 * 仅服务非 trace 布尔快路径；开 trace、灰度未命中、或关开关时委托内部 {@link InterpretedExecutor}。
 * 缓存键为不可变 ruleVersionId，陈旧条目永不会错(失效仅为内存卫生，见 {@link RuleVersionCache})。
 */
public final class CompiledExecutor implements RuleVersionExecutor {

    private static final Logger log = LoggerFactory.getLogger(CompiledExecutor.class);
    /** 回落哨兵：标记某 ruleVersionId 编译失败、永久走解释器(按引用比较，从不调用)。 */
    private static final Predicate<EvalContext> FALLBACK = ctx -> { throw new AssertionError("哨兵不应被调用"); };

    private final InterpretedExecutor interpreter;
    private final AstCompiler compiler;
    private final RuleVersionCache cache;
    private final boolean enabled;
    private final Set<String> whitelist;
    private final CompileErrorPolicy onCompileError;

    /**
     * @param interpreter    回落用解释器(trace/灰度未命中/关开关时委托)
     * @param compiler       AST 编译器
     * @param cache          编译产物缓存
     * @param enabled        是否启用编译执行器
     * @param whitelist      编译白名单(规则 code)；空=全量编译
     * @param onCompileError 编译失败处置策略
     */
    public CompiledExecutor(InterpretedExecutor interpreter, AstCompiler compiler, RuleVersionCache cache,
                            boolean enabled, Set<String> whitelist, CompileErrorPolicy onCompileError) {
        this.interpreter = interpreter;
        this.compiler = compiler;
        this.cache = cache;
        this.enabled = enabled;
        this.whitelist = Set.copyOf(whitelist);
        this.onCompileError = onCompileError;
    }

    @Override
    public EvalResult execute(RuleVersionSnapshot snapshot, EvalContext ctx) {
        // 关开关 / 灰度未命中 / 开 trace：与今天逐字节一致，委托解释器
        if (!enabled
                || (!whitelist.isEmpty() && !whitelist.contains(snapshot.code()))
                || TraceScope.COLLECT.orElse(true)) {
            return interpreter.execute(snapshot, ctx);
        }
        Predicate<EvalContext> p = obtain(snapshot);
        if (p == FALLBACK) return interpreter.execute(snapshot, ctx);
        return p.test(ctx) ? EvalResult.hit() : EvalResult.miss();
    }

    /** 取缓存或惰性编译；编译失败按策略 FAIL 抛出 / FALLBACK 记哨兵回落。 */
    private Predicate<EvalContext> obtain(RuleVersionSnapshot snapshot) {
        long id = snapshot.ruleVersionId();
        Predicate<EvalContext> p = cache.get(id);
        if (p != null) return p;
        try {
            p = compiler.compile(snapshot.conditionAst());
        } catch (RuntimeException e) {
            if (onCompileError == CompileErrorPolicy.FAIL) {
                throw new IllegalStateException(
                        "AST 编译失败 ruleVersionId=" + id + " code=" + snapshot.code(), e);
            }
            log.warn("AST 编译失败，回落解释器 ruleVersionId={} code={}", id, snapshot.code(), e);
            cache.putIfAbsent(id, FALLBACK);
            return FALLBACK;
        }
        cache.putIfAbsent(id, p);
        // 并发下另一线程可能先放入，统一返回缓存内实例
        return cache.get(id);
    }
}
```

- [ ] **Step 5: 跑测试确认通过**

Run: `$MVN -pl rule-kernel -am -Dtest=CompiledExecutorTest test`
Expected: PASS。

- [ ] **Step 6: Commit**

```bash
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/evaluator/CompileErrorPolicy.java \
        rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/evaluator/CompiledExecutor.java \
        rule-kernel/src/test/java/com/sstlfsj/rule/kernel/evaluator/CompiledExecutorTest.java
git commit -m "feat(eval): CompiledExecutor — 灰度/trace 回落 + 编译失败策略"
```

---

## Task 5: CompiledExecutorProperties(eval-svc 灰度配置)

**Files:**
- Create: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/CompiledExecutorProperties.java`

> 该类是 `@ConfigurationProperties` JavaBean,绑定由 Spring 完成,本任务不单独写单测(其行为在 Task 6 装配后由现有 eval 集成测试覆盖)。

- [ ] **Step 1: 写配置类**

```java
package com.sstlfsj.rule.eval.internal;

import com.sstlfsj.rule.kernel.internal.evaluator.CompileErrorPolicy;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 编译执行器灰度配置 engine.rule.eval.compiled-executor.*。
 * enabled=false(默认)时行为与解释器逐字节一致。
 */
@Getter
@Setter
@ConfigurationProperties("engine.rule.eval.compiled-executor")
public class CompiledExecutorProperties {

    /** 是否启用编译执行器；false=全部走解释器。 */
    private boolean enabled = false;

    /** 编译白名单(规则 code)；enabled 且为空=全量编译，非空=仅列出的 code 走编译。 */
    private List<String> ruleCodeWhitelist = List.of();

    /** 编译失败处置策略，默认 FALLBACK。 */
    private CompileErrorPolicy onCompileError = CompileErrorPolicy.FALLBACK;
}
```

- [ ] **Step 2: 编译确认无误**

Run: `$MVN -pl rule-eval-svc -am -DskipTests compile`
Expected: BUILD SUCCESS。

- [ ] **Step 3: Commit**

```bash
git add rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/CompiledExecutorProperties.java
git commit -m "feat(eval): CompiledExecutorProperties — 编译执行器灰度配置"
```

---

## Task 6: 装配 CompiledExecutor 进 EvalAutoConfiguration

**Files:**
- Modify: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/EvalAutoConfiguration.java`

- [ ] **Step 1: 加 import**

在 import 区(`InterpretedExecutor` import 附近)新增:
```java
import com.sstlfsj.rule.kernel.api.spi.condition.ConditionEvaluator;
import com.sstlfsj.rule.kernel.internal.evaluator.AstCompiler;
import com.sstlfsj.rule.kernel.internal.evaluator.CompiledExecutor;
import com.sstlfsj.rule.kernel.internal.evaluator.RuleVersionCache;
import com.sstlfsj.rule.eval.internal.CompiledExecutorProperties;
import java.util.Set;
```

- [ ] **Step 2: 注册 CompiledExecutorProperties**

把 `@EnableConfigurationProperties({...})` 列表追加一项:
```java
        com.sstlfsj.rule.eval.internal.CompiledExecutorProperties.class,
```
(加在 `RetentionProperties.class` 同列表内,例如其前一行)

- [ ] **Step 3: 替换 ruleVersionExecutor() bean + 新增 ruleVersionCache() bean**

把现有:
```java
    @Bean
    @Primary
    public RuleVersionExecutor ruleVersionExecutor() {
        return new InterpretedExecutor(KernelEvaluators.defaults());
    }
```
替换为:
```java
    /**
     * 编译产物缓存 bean，供 CompiledExecutor 与 CompiledPredicateEvictor 共享。
     *
     * @return RuleVersionCache 实例
     */
    @Bean
    public RuleVersionCache ruleVersionCache() {
        return new RuleVersionCache();
    }

    /**
     * AST_BOOLEAN executor：默认 CompiledExecutor 包裹 InterpretedExecutor。
     * compiled-executor.enabled=false(默认)时逐字节等同解释器(永远委托)；
     * 开启后非 trace 快路径走编译闭包，开 trace / 灰度未命中时回落解释器。
     *
     * @param ruleVersionCache 编译产物缓存
     * @param props            编译执行器灰度配置
     * @return CompiledExecutor 实例(对外仍是 RuleVersionExecutor)
     */
    @Bean
    @Primary
    public RuleVersionExecutor ruleVersionExecutor(RuleVersionCache ruleVersionCache,
                                                   CompiledExecutorProperties props) {
        Map<String, ConditionEvaluator> evaluators = KernelEvaluators.defaults();
        InterpretedExecutor interpreter = new InterpretedExecutor(evaluators);
        AstCompiler compiler = new AstCompiler(evaluators);
        return new CompiledExecutor(interpreter, compiler, ruleVersionCache,
                props.isEnabled(), Set.copyOf(props.getRuleCodeWhitelist()), props.getOnCompileError());
    }
```

- [ ] **Step 4: 跑 eval-svc 全量测试,确认默认(disabled)行为不变**

Run: `$MVN -pl rule-eval-svc -am test`
Expected: 全绿。默认 `enabled=false`,所有现有评估测试行为与改前完全一致(证明装配透明)。

- [ ] **Step 5: Commit**

```bash
git add rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/EvalAutoConfiguration.java
git commit -m "feat(eval): 装配 CompiledExecutor 为 AST_BOOLEAN executor(默认关，透明)"
```

---

## Task 7: CompiledPredicateEvictor(索引热更清缓存)

**Files:**
- Create: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/listener/CompiledPredicateEvictor.java`
- Test: `rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/listener/CompiledPredicateEvictorTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.sstlfsj.rule.eval.internal.listener;

import com.sstlfsj.rule.config.api.event.RulePublishedEvent;
import com.sstlfsj.rule.config.api.event.SceneChangedEvent;
import com.sstlfsj.rule.kernel.internal.evaluator.RuleVersionCache;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CompiledPredicateEvictorTest {

    @Test
    void onRulePublished_evictsAll() {
        RuleVersionCache cache = new RuleVersionCache();
        cache.putIfAbsent(1L, ctx -> true);
        CompiledPredicateEvictor evictor = new CompiledPredicateEvictor(cache);

        evictor.onRulePublished(new RulePublishedEvent("t1", "scene1", 1L));

        assertThat(cache.size()).isZero();
    }

    @Test
    void onSceneChanged_evictsAll() {
        RuleVersionCache cache = new RuleVersionCache();
        cache.putIfAbsent(1L, ctx -> true);
        CompiledPredicateEvictor evictor = new CompiledPredicateEvictor(cache);

        evictor.onSceneChanged(new SceneChangedEvent("t1", "scene1", false));

        assertThat(cache.size()).isZero();
    }
}
```

> 事件构造签名(已核对源码):`RulePublishedEvent(String tenantId, String sceneCode, Long ruleVersionId)`、`SceneChangedEvent(String tenantId, String sceneCode, boolean active)`。

- [ ] **Step 2: 跑测试确认失败**

Run: `$MVN -pl rule-eval-svc -am -Dtest=CompiledPredicateEvictorTest test`
Expected: 编译失败(`CompiledPredicateEvictor` 不存在)。

- [ ] **Step 3: 写实现**

```java
package com.sstlfsj.rule.eval.internal.listener;

import com.sstlfsj.rule.config.api.event.RulePublishedEvent;
import com.sstlfsj.rule.config.api.event.SceneChangedEvent;
import com.sstlfsj.rule.kernel.internal.evaluator.RuleVersionCache;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * 索引热更后清空编译产物缓存(内存卫生)。
 * 键为不可变 ruleVersionId，陈旧条目不会错，故全清即可，下次评估惰性重编译。
 * 与 {@link RuleIndexEventListener} 独立(单一职责：只管缓存失效)。
 */
@Component
public class CompiledPredicateEvictor {

    private final RuleVersionCache cache;

    /**
     * @param cache 编译产物缓存
     */
    public CompiledPredicateEvictor(RuleVersionCache cache) {
        this.cache = cache;
    }

    /**
     * 规则发布后清空编译缓存。
     *
     * @param event 规则发布事件
     */
    @ApplicationModuleListener
    public void onRulePublished(RulePublishedEvent event) {
        cache.evictAll();
    }

    /**
     * 场景变更后清空编译缓存。
     *
     * @param event 场景变更事件
     */
    @ApplicationModuleListener
    public void onSceneChanged(SceneChangedEvent event) {
        cache.evictAll();
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `$MVN -pl rule-eval-svc -am -Dtest=CompiledPredicateEvictorTest test`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/listener/CompiledPredicateEvictor.java \
        rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/listener/CompiledPredicateEvictorTest.java
git commit -m "feat(eval): CompiledPredicateEvictor — 索引热更清空编译缓存"
```

---

## Task 8: A/B benchmark + 收尾验证

**Files:**
- Create: `rule-benchmark/src/main/java/com/sstlfsj/rule/benchmark/CompiledVsInterpretedBenchmark.java`

- [ ] **Step 1: 写 A/B 基准**

```java
package com.sstlfsj.rule.benchmark;

import com.sstlfsj.rule.kernel.api.model.ConditionTypes;
import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.EventSource;
import com.sstlfsj.rule.kernel.api.model.MetricValue;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.model.ast.AndNode;
import com.sstlfsj.rule.kernel.api.model.ast.AstNode;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.internal.condition.KernelEvaluators;
import com.sstlfsj.rule.kernel.internal.evaluator.AstCompiler;
import com.sstlfsj.rule.kernel.internal.evaluator.CompileErrorPolicy;
import com.sstlfsj.rule.kernel.internal.evaluator.CompiledExecutor;
import com.sstlfsj.rule.kernel.internal.evaluator.InterpretedExecutor;
import com.sstlfsj.rule.kernel.internal.evaluator.RuleVersionCache;
import com.sstlfsj.rule.kernel.internal.evaluator.TraceScope;
import org.openjdk.jmh.annotations.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * A/B 对照：同一 AST(AND N 个 GT 条件) + 同一 ctx 下，解释器 vs 编译执行器的非 trace 快路径耗时与分配。
 * 编译版预热缓存后稳定走 predicate.test；产出用于确认收益与"零额外分配"成功判据。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class CompiledVsInterpretedBenchmark {

    @Param({"5", "20", "50"})
    public int n;

    private InterpretedExecutor interpreter;
    private CompiledExecutor compiled;
    private RuleVersionSnapshot snapshot;
    private EvalContext ctx;

    @Setup
    public void setup() {
        Map<String, com.sstlfsj.rule.kernel.api.spi.condition.ConditionEvaluator> evaluators =
                KernelEvaluators.defaults();
        interpreter = new InterpretedExecutor(evaluators);
        compiled = new CompiledExecutor(interpreter, new AstCompiler(evaluators), new RuleVersionCache(),
                true, Set.of(), CompileErrorPolicy.FALLBACK);

        List<AstNode> conds = new ArrayList<>();
        Map<String, MetricValue> metrics = new HashMap<>();
        for (int i = 0; i < n; i++) {
            String mc = "m" + i;
            conds.add(new ConditionNode(ConditionTypes.GT, mc, null, Map.of("threshold", 0L), 0.0));
            metrics.put(mc, new MetricValue(1L, "LONG", "PROVIDED"));
        }
        AstNode ast = new AndNode(conds, null, null);
        snapshot = new RuleVersionSnapshot(1L, "scene", "t1", ast, null, null, null,
                "AST_BOOLEAN", "RULE_A", 1L, List.of(), List.of());
        RuleEvent event = new RuleEvent("t1", "scene", "ORDER", "sub1", "evt-1",
                Instant.now(), Map.of(), Map.of(), EventSource.HTTP);
        ctx = new EvalContext("t1", event, null, metrics, Instant.now());
    }

    @Benchmark
    public boolean interpreted() throws Exception {
        return ScopedValue.where(TraceScope.COLLECT, false)
                .call(() -> interpreter.execute(snapshot, ctx).ruleHit());
    }

    @Benchmark
    public boolean compiled() throws Exception {
        return ScopedValue.where(TraceScope.COLLECT, false)
                .call(() -> compiled.execute(snapshot, ctx).ruleHit());
    }
}
```

- [ ] **Step 2: 打包并运行 A/B(含分配剖析)**

```bash
$MVN -pl rule-benchmark -am package -DskipTests
java -jar rule-benchmark/target/benchmarks.jar CompiledVsInterpretedBenchmark -prof gc
```
Expected: `compiled` 的 ns/op 显著低于 `interpreted`(成功判据:有可量提升);`compiled` 的 `gc.alloc.rate.norm` ≈ 0 B/op(零额外分配不变量)。记录两者数值。

- [ ] **Step 3: trace 平价人工核对**

确认设计层面 trace 一致性已由单测覆盖:`CompiledExecutorTest.enabled_traceMode_delegatesToInterpreter`(开 trace 走解释器,NodeTrace 非空)+ `enabled_nonTrace_usesCompiled_emptyTrace`(非 trace 走编译,空 trace)。无需额外端到端 trace 对比(编译版从不产 trace,trace 永远经解释器,逐行一致天然成立)。

- [ ] **Step 4: Commit**

```bash
git add rule-benchmark/src/main/java/com/sstlfsj/rule/benchmark/CompiledVsInterpretedBenchmark.java
git commit -m "perf(benchmark): 编译 vs 解释 A/B 对照(耗时 + 零分配验证)"
```

---

## Task 9: 文档与配置默认值

**Files:**
- Modify: `rule-app/src/main/resources/application.yml`
- Modify: `docs/08-evolution.md`(§2.13 状态)
- Modify: `docs/00-decisions.md`(追加决策条目)

- [ ] **Step 1: application.yml 加配置默认(注释说明)**

在 `engine.rule.eval` 段(若无则在 `engine.rule` 下新建 `eval`)追加:
```yaml
    eval:
      compiled-executor:
        enabled: false           # 编译执行器灰度开关；false=全部走解释器(默认)
        rule-code-whitelist: []  # enabled 且空=全量编译；非空=仅列出的 code 走编译
        on-compile-error: FALLBACK  # FALLBACK=WARN+回落解释器；FAIL=抛异常中止
```
> 先确认 `application.yml` 中 `engine.rule` 现有缩进层级,按既有风格放置。

- [ ] **Step 2: docs/08-evolution.md 更新 §2.13 状态**

把 §2.13 的"规划中/待实现"措辞改为"纯编译版已落地(2026-06-13):AstCompiler + RuleVersionCache + CompiledExecutor,灰度 `engine.rule.eval.compiled-executor.*`,默认关;alpha/CSE 共享仍为后续轮"。删除该节中与已落地内容矛盾的旧表述(文档纪律:废弃内容直接删/整段重写)。

- [ ] **Step 3: docs/00-decisions.md 追加决策**

在决策日志**末尾追加**(不改历史条目):
```markdown
## D67 评估期预编译(纯编译版)

AST_BOOLEAN 规则求值新增编译执行器:`AstCompiler` 把布尔 AST 编译为嵌套 `Predicate<EvalContext>` 闭包,`CompiledExecutor` 缓存并在非 trace 快路径直接 `test(ctx)`,开 trace / 灰度未命中 / 关开关时回落 `InterpretedExecutor`。编译技术选闭包组合(非 LambdaMetafactory/字节码生成),组合节点编译期收数组、求值期下标循环以保证零额外分配。缓存按不可变 ruleVersionId,失效用 evictAll(内存卫生)。灰度 `engine.rule.eval.compiled-executor.*`(enabled/whitelist/on-compile-error),默认关,行为与解释器逐字节一致。EvalEngine 零改动。alpha/CSE 跨规则共享为独立后续轮。
```

- [ ] **Step 4: 跑文档自洽 review**

用 `doc-consistency-review` skill 扫 `docs/`(改了 08-evolution + 00-decisions),确认与 01-concepts/02-runtime 等无矛盾。

- [ ] **Step 5: Commit**

```bash
git add rule-app/src/main/resources/application.yml docs/08-evolution.md docs/00-decisions.md
git commit -m "docs(eval): §2.13 编译执行器落地 — 配置默认 + D67 决策 + 08-evolution 状态"
```

---

## Task 10: 全量回归兜底

- [ ] **Step 1: 全量 clean test**

Run: `$MVN clean test`
Expected: 全模块全绿(`clean` 强制重编译所有 test 类,跨模块改动不留过期 jar 假象)。

- [ ] **Step 2: 若有失败,定位修复**

按 CLAUDE.md 测试纪律:不得 `-DskipTests` 绕过;同一问题 2 次未通过则停下报告。

---

## Self-Review(写计划后自检)

**Spec 覆盖:** §2 范围→Task 2/装配只碰 AST_BOOLEAN;§3 闭包编译→Task 2;§4 组件→Task 2/3/4/7;§5 执行逻辑→Task 4;§6 灰度零改 EvalEngine→Task 5/6;§7 错误处理(FALLBACK/FAIL)→Task 4 + CompileErrorPolicy;§8 测试→各 Task TDD;§9 benchmark 闸→Task 1 + Task 8。全覆盖。

**类型一致性:** `RuleVersionCache` 方法名(`get`/`putIfAbsent`/`evictAll`/`size`)在 Task 3 定义、Task 4/7/8 一致引用;`CompiledExecutor` 构造签名 `(InterpretedExecutor, AstCompiler, RuleVersionCache, boolean, Set<String>, CompileErrorPolicy)` 在 Task 4 定义、Task 6/8 一致;`AstCompiler.compile(AstNode):Predicate<EvalContext>` 一致;`EvalResult.hit()/miss()` 为既有工厂(已核对源码)。

**占位扫描:** 无 TBD/TODO;Task 7 对 `RulePublishedEvent` 构造签名加了"以实际为准"的明确兜底说明(非占位,是已知小风险的处置指引);Task 9 Step1 提示按既有缩进放置(非占位)。
