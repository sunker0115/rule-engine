# 非 trace 模式跳过 NodeTrace 收集 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: 用 superpowers:subagent-driven-development 或 executing-plans 逐 task 执行。步骤用 `- [ ]` 勾选。

**Goal:** 生产 `trace.enabled=false` 时,AST_BOOLEAN/Scorecard 评估不建 NodeTrace(省分配),dry-run 仍强制收集;顺带合并两个解释执行器消重复。

**Architecture:** 用 Java 25 `ScopedValue<Boolean> TraceScope.COLLECT` 作 ambient 载体(EvalEngine 在评估入口绑定:dry-run=true / 普通=traceEnabled),执行器读 `COLLECT.orElse(true)` 决定建不建 trace。`EvalContext`/SPI 不变。

**Tech Stack:** Java 25(ScopedValue),Spring Boot 4,纯 kernel(无 Spring)。

**环境(每 task 跑测试):**
```
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-25.jdk/Contents/Home
export PATH=$JAVA_HOME/bin:$PATH
MVN=/Users/sunke/.m2/wrapper/dists/apache-maven-3.9.9-bin/4nf9hui3q3djbarqar9g711ggc/apache-maven-3.9.9/bin/mvn
```

**设计依据:** `docs/superpowers/specs/2026-06-08-eval-trace-skip-design.md`

## 文件清单

- 新建 `rule-kernel/.../internal/evaluator/TraceScope.java` — ScopedValue holder。
- 改 `rule-kernel/.../internal/evaluator/InterpretedExecutor.java` — 吸收 tracing 遍历 + `collect` 守卫。
- 删 `rule-kernel/.../internal/evaluator/TracingInterpretedExecutor.java`(+ 其测试迁移)。
- 改 `rule-kernel/.../internal/evaluator/ScorecardExecutor.java` — trace 构建加守卫。
- 改 `rule-kernel/.../internal/engine/EvalEngine.java` — `traceEnabled` 构造参 + 核心方法 `collectTrace` 形参 + ScopedValue 绑定。
- 改 `rule-eval-svc/.../service/EvalServiceImpl.java` — dry-run 传 `collectTrace=true`。
- 改 `rule-eval-svc/.../EvalAutoConfiguration.java` — 注册合并后的 InterpretedExecutor + 注入 traceEnabled。
- 测试迁移:`InterpretedExecutorTest` / `TracingInterpretedExecutorTest`(删/并)/ `XorNodeTest` / `MetricErrorTraceTest` / `ScorecardExecutorTest`。

---

## Task 1: `TraceScope` holder

**Files:** Create `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/evaluator/TraceScope.java`；Test `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/evaluator/TraceScopeTest.java`

- [ ] **Step 1: 写失败测试**
```java
package com.sstlfsj.rule.kernel.evaluator;

import com.sstlfsj.rule.kernel.internal.evaluator.TraceScope;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class TraceScopeTest {
    @Test
    void unbound_defaultsToTrue() {
        assertThat(TraceScope.COLLECT.orElse(true)).isTrue();
    }
    @Test
    void boundFalse_isHonoredWithinScope() {
        boolean inside = ScopedValue.where(TraceScope.COLLECT, false)
                .call(() -> TraceScope.COLLECT.orElse(true));
        assertThat(inside).isFalse();
        assertThat(TraceScope.COLLECT.orElse(true)).isTrue();   // 出作用域自动解绑
    }
}
```

- [ ] **Step 2: 跑测试确认失败**
Run: `$MVN -pl rule-kernel test -Dtest='TraceScopeTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败(TraceScope 不存在)。

- [ ] **Step 3: 实现 holder**
```java
package com.sstlfsj.rule.kernel.internal.evaluator;

/** 逐次评估的 ambient 执行模式:是否收集 NodeTrace。EvalEngine 入口绑定,执行器读取。 */
public final class TraceScope {
    /** 未绑定时默认 true(= 现状"始终收集";直调执行器的测试无需感知)。 */
    public static final ScopedValue<Boolean> COLLECT = ScopedValue.newInstance();
    private TraceScope() {}
}
```

- [ ] **Step 4: 跑测试确认通过**
Run: `$MVN -pl rule-kernel test -Dtest='TraceScopeTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS。

- [ ] **Step 5: 提交**
```bash
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/evaluator/TraceScope.java \
        rule-kernel/src/test/java/com/sstlfsj/rule/kernel/evaluator/TraceScopeTest.java
git commit -m "feat(kernel): TraceScope ScopedValue holder(逐次评估 collectTrace ambient)"
```

---

## Task 2: 合并执行器(InterpretedExecutor 吸收 tracing + 守卫,删 TracingInterpretedExecutor)

**Files:** Modify `rule-kernel/.../internal/evaluator/InterpretedExecutor.java`；Delete `TracingInterpretedExecutor.java`；迁移测试 `InterpretedExecutorTest.java`(并入 Tracing 断言)、删 `TracingInterpretedExecutorTest.java`、改 `XorNodeTest.java` / `MetricErrorTraceTest.java` 的构造。

- [ ] **Step 1: 改 `InterpretedExecutor` 为「单遍历 + collect 守卫」**

把现 `InterpretedExecutor` 的 body 替换为(吸收 `TracingInterpretedExecutor` 的遍历,trace 构建用 `collect` 守卫):
```java
@Override
public EvalResult execute(RuleVersionSnapshot snapshot, EvalContext ctx) {
    boolean collect = TraceScope.COLLECT.orElse(true);
    List<NodeTrace> rawTraces = collect ? new ArrayList<>() : null;
    boolean satisfied = eval(snapshot.conditionAst(), ctx, rawTraces);
    List<NodeTrace> traces;
    if (collect) {
        Long rvId = snapshot.ruleVersionId();
        traces = rawTraces.stream().map(t -> withRuleVersionId(t, rvId)).toList();
    } else {
        traces = List.of();
    }
    return new EvalResult(satisfied, null, List.of(), traces, null, List.of(), null, null, null);
}
```
- `eval(node, ctx, sink)`:沿用 `TracingInterpretedExecutor.evalAndTrace` 的 switch 与 traceAnd/Or/Not/Xor/Condition 逻辑,但**每个 `sink.add(...)` 前判 `if (sink != null)`**(sink==null 即非收集模式,跳过建 trace);返回的 boolean 结果不变(短路语义不变)。childTraces 同理:`List<NodeTrace> childTraces = sink != null ? new ArrayList<>() : null;`,传入子节点 eval。
- 保留 `withRuleVersionId` 私有方法(从 Tracing 原样搬来)。
- 保留对 ScorecardRootNode/IfNode/DecisionLeafNode/DecisionTableNode 的 `IllegalStateException`(文案里的类名改 InterpretedExecutor)。
- import 增加 `com.sstlfsj.rule.kernel.api.model.NodeTrace`、`java.util.ArrayList`、`java.util.List`、`TraceScope` 不需 import(同包)。

- [ ] **Step 2: 删 `TracingInterpretedExecutor.java`**
```bash
git rm rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/evaluator/TracingInterpretedExecutor.java
```

- [ ] **Step 3: 迁移测试**
- 把 `TracingInterpretedExecutorTest.java` 的 trace 断言并入 `InterpretedExecutorTest.java`(构造改 `new InterpretedExecutor(...)`;默认 COLLECT 未绑定=true,trace 断言照旧);删 `TracingInterpretedExecutorTest.java`。
- 新增一条:`ScopedValue.where(TraceScope.COLLECT, false).run(() -> ...)` 下 execute → `result.nodeTrace()` 空、`ruleHit()` 与 true 模式一致。
- `XorNodeTest.java` / `MetricErrorTraceTest.java`:把 `new TracingInterpretedExecutor` 改 `new InterpretedExecutor`(行为在 COLLECT 默认 true 下一致,断言不变)。
- `InterpretedExecutorTest.java` 原有 hit/miss 断言:若它断言了"空 nodeTrace",改为绑 `COLLECT=false` 再断言空(默认 true 下现在会有 trace)。

- [ ] **Step 4: 跑 kernel evaluator 测试**
Run: `$MVN -pl rule-kernel test -Dtest='InterpretedExecutorTest,XorNodeTest,MetricErrorTraceTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS;无 `TracingInterpretedExecutor` 残留引用。

- [ ] **Step 5: 提交**
```bash
git add -A rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/evaluator/ rule-kernel/src/test/java/com/sstlfsj/rule/kernel/evaluator/
git commit -m "refactor(kernel): 合并 Tracing 进 InterpretedExecutor,collect 守卫跳过 trace 构建"
```

---

## Task 3: `ScorecardExecutor` 守卫

**Files:** Modify `rule-kernel/.../internal/evaluator/ScorecardExecutor.java`；Test `ScorecardExecutorTest.java`

- [ ] **Step 1: 写失败测试(off 模式空 trace,score 不变)**
在 `ScorecardExecutorTest` 加:
```java
@Test
void collectFalse_noTrace_sameScore() {
    EvalResult on  = ScopedValue.where(TraceScope.COLLECT, true).call(() -> executor.execute(snap, ctx));
    EvalResult off = ScopedValue.where(TraceScope.COLLECT, false).call(() -> executor.execute(snap, ctx));
    assertThat(off.nodeTrace()).isEmpty();
    assertThat(off.score()).isEqualTo(on.score());
    assertThat(off.ruleHit()).isEqualTo(on.ruleHit());
}
```
(snap/ctx 用该测试已有的 fixture;import `TraceScope`。)

- [ ] **Step 2: 跑测试确认失败**
Run: `$MVN -pl rule-kernel test -Dtest='ScorecardExecutorTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL(off 仍有 trace)。

- [ ] **Step 3: 加守卫**
`ScorecardExecutor.execute`:`boolean collect = TraceScope.COLLECT.orElse(true);` 开头取;两处 `traces.add(new NodeTrace(...))` 包 `if (collect)`;`new EvalResult(..., traces, ...)` 改为 `collect ? traces : List.of()`(或始终传 traces——off 时 traces 为空 list,等价;选其一,保证 off 时 nodeTrace 空)。

- [ ] **Step 4: 跑测试确认通过**
Run: `$MVN -pl rule-kernel test -Dtest='ScorecardExecutorTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS。

- [ ] **Step 5: 提交**
```bash
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/evaluator/ScorecardExecutor.java rule-kernel/src/test/java/com/sstlfsj/rule/kernel/evaluator/ScorecardExecutorTest.java
git commit -m "perf(kernel): ScorecardExecutor 非 collect 模式跳过 NodeTrace 构建"
```

---

## Task 4: `EvalEngine` 绑定 ScopedValue

**Files:** Modify `rule-kernel/.../internal/engine/EvalEngine.java`；Test `rule-kernel/.../engine/EvalEngineTest.java`(若无则建)

- [ ] **Step 1: 写失败测试**
```java
@Test
void collectFalse_yieldsEmptyTrace_collectTrue_nonEmpty() {
    // engine 用 AST_BOOLEAN 单条候选(命中)的 fixture;evaluateWithContext 核心重载带 collectTrace
    EvalOutcome off = engine.evaluateWithContext(event, candidates,
            SceneExecutionStrategy.HIGHEST_PRIORITY, now, false);
    EvalOutcome on  = engine.evaluateWithContext(event, candidates,
            SceneExecutionStrategy.HIGHEST_PRIORITY, now, true);
    assertThat(off.result().nodeTrace()).isEmpty();
    assertThat(on.result().nodeTrace()).isNotEmpty();
    assertThat(off.result().ruleHit()).isEqualTo(on.result().ruleHit());
}
```

- [ ] **Step 2: 跑测试确认失败**
Run: `$MVN -pl rule-kernel test -Dtest='EvalEngineTest#collectFalse_yieldsEmptyTrace_collectTrue_nonEmpty' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败(无 5 参重载)。

- [ ] **Step 3: 改 EvalEngine**
- 构造器加 `boolean traceEnabled` 末参,存字段(`this.traceEnabled = traceEnabled;`)。
- 加核心 5 参重载 `evaluateWithContext(event, candidates, strategy, now, boolean collectTrace)`:把现 4 参重载的 body 移入,**在调用 `evaluateFirstHit/evaluateAllCandidates` 处用 ScopedValue 包住**:
```java
EvalResult result = ScopedValue.where(TraceScope.COLLECT, collectTrace).call(() -> switch (strategy) {
    case FIRST_HIT -> evaluateFirstHit(event, passed, ctx);
    case HIGHEST_PRIORITY, ALL_HITS -> evaluateAllCandidates(passed, ctx);
});
```
  (`.call(...)` 抛 Exception;用 `throws` 不便则包成 RuntimeException 或用 ScopedValue 的 `run` + 外层 holder——选 `call` 并在方法内 try/catch 重抛 unchecked,与现状无 checked 异常一致。)
- 现 4 参重载改为委托:`return evaluateWithContext(event, candidates, strategy, now, traceEnabled);`
- 现 3 参重载不变(它调 4 参 → 自动用 traceEnabled)。
- import `TraceScope`、`ScopedValue` 无需(java.lang)。

- [ ] **Step 4: 跑测试确认通过**
Run: `$MVN -pl rule-kernel test -Dtest='EvalEngineTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS。

- [ ] **Step 5: 提交**
```bash
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/engine/EvalEngine.java rule-kernel/src/test/java/com/sstlfsj/rule/kernel/engine/EvalEngineTest.java
git commit -m "feat(kernel): EvalEngine 绑定 TraceScope.COLLECT(traceEnabled 默认 + collectTrace 形参)"
```

---

## Task 5: 装配 + dry-run 强制(原子)

**Files:** Modify `rule-eval-svc/.../EvalAutoConfiguration.java`、`rule-eval-svc/.../service/EvalServiceImpl.java`；Test `EvalServiceImplTest` / `EvalAutoConfigurationTest`

> 原子:EvalEngine 构造器加了 traceEnabled,装配必须同 commit 跟上,否则编译断。

- [ ] **Step 1: 改 EvalAutoConfiguration**
- `ruleVersionExecutor` @Bean:`return new InterpretedExecutor(KernelEvaluators.defaults());`(取代 TracingInterpretedExecutor;import 改)。
- `evalEngine` @Bean:形参加 `@org.springframework.beans.factory.annotation.Value("${engine.rule.trace.enabled:true}") boolean traceEnabled`,`new EvalEngine(..., traceEnabled)`。

- [ ] **Step 2: 改 EvalServiceImpl dry-run 传 collectTrace=true**
`doEvaluate` 的 dry-run 分支:`evalEngine.evaluateWithContext(event, List.of(snap), SceneExecutionStrategy.HIGHEST_PRIORITY, evalNow, true)`(5 参,强制 collectTrace=true)。普通分支不变(走 3 参 → traceEnabled)。

- [ ] **Step 3: 跑 eval-svc 全量**
Run: `$MVN -pl rule-eval-svc -am test 2>&1 | grep -E 'Tests run:.*Failures|BUILD' | tail -5`
Expected: Failures:0,BUILD SUCCESS。`EvalAutoConfigurationTest` 若断言了 ruleVersionExecutor 类型为 TracingInterpretedExecutor 则改为 InterpretedExecutor。

- [ ] **Step 4: 提交**
```bash
git add rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/EvalAutoConfiguration.java rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/service/EvalServiceImpl.java rule-eval-svc/src/test
git commit -m "feat(eval): 装配合并执行器 + 注入 traceEnabled;dry-run 强制 collectTrace=true"
```

---

## Task 6: 全量回归 + native 冒烟

- [ ] **Step 1: kernel + eval-svc 全量**
Run: `$MVN -pl rule-kernel,rule-eval-svc -am test 2>&1 | grep -E 'Tests run:.*Failures|BUILD' | grep -v 'Time elapsed' | tail -6`
Expected: 全 Failures:0,BUILD SUCCESS。

- [ ] **Step 2: native 冒烟(ScopedValue 在 native 下可用 + trace 行为)**
- `install` 后 `-Pnative -pl rule-app native:compile`(GraalVM 25);boot。
- 默认 `trace.enabled=true`:PULL evaluate → `evaluation_session` + `node_trace` 仍落(trace 收集照旧)。
- 设 `-Dengine.rule.trace.enabled=false` 重启:PULL evaluate → EvalResult.nodeTrace 空、`node_trace` 不落;**dry-run(带 ruleVersionId)→ 响应 nodeTrace 非空**(强制开回归)。
- 期望:native boot OK、ScopedValue 正常、上述 trace 行为符合。

- [ ] **Step 3: 无新增提交**(纯验证;若 native 缺 hints 才补)

---

## Self-Review

**Spec 覆盖:** §3 决策1(ScopedValue)→Task1+4;决策2(合并+守卫/Scorecard 守卫)→Task2+3;决策3(dry-run 强制)→Task5。§5.2 引擎绑定→Task4;§5.3 合并→Task2;§5.4 Scorecard→Task3;§5.6 装配→Task5。§7 测试分散各 task + Task6 全量 + native。§8 backlog(tree/table)不在计划内(符合)。
**占位符:** 无 TBD;novel 代码(TraceScope/绑定/守卫/装配)给出完整片段;大遍历合并给出 execute 框架 + "搬 evalAndTrace 加 sink!=null 守卫"的精确转换 + 保留 withRuleVersionId,引用现有 TracingInterpretedExecutor 源(实现者可读)。
**类型一致:** `TraceScope.COLLECT`(ScopedValue<Boolean>)全程一致;`evaluateWithContext(...,boolean collectTrace)` Task4 定义、Task5 调用一致;合并类名 `InterpretedExecutor` Task2 定、Task5 装配一致;`traceEnabled` Task4 构造参、Task5 注入一致。
