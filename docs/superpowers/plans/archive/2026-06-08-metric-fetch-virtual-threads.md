# metric 取数 fan-out 虚拟线程化 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 metric 取数 fan-out 从固定平台线程池(core8/max32/queue256)换成虚拟线程-per-task + `ExecutorService.invokeAll(timeout)`,去掉人为 32 上限并拿到干净的超时/取消语义,行为(routing/provided/cache/降级)保持不变。

**Architecture:** `EvalContextAssembler.fetchConcurrently` 改用 `invokeAll`;承载它的 `fetchExecutor` 类型 `Executor → ExecutorService`,该签名变更原子地波及三个模块——kernel(assembler + 测试)、eval-svc(bean + 注入点)、sdk(Builder)。SPI、kernel 评估核、缓存语义不动。弃 `StructuredTaskScope`(JDK25 preview,与 GraalVM native 冲突)。

**Tech Stack:** Java 25(`Executors.newVirtualThreadPerTaskExecutor()` / `ExecutorService.invokeAll` 均为正式 API)、Spring Boot 4、Maven 多模块、JUnit5 + AssertJ、GraalVM native(本改动零新反射、无 `--enable-preview`)。

**环境(每个 `$MVN` 步骤前 export):**
```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-25.jdk/Contents/Home
export PATH=$JAVA_HOME/bin:$PATH
MVN=/Users/sunke/.m2/wrapper/dists/apache-maven-3.9.9-bin/4nf9hui3q3djbarqar9g711ggc/apache-maven-3.9.9/bin/mvn
```

**分支纪律:** develop 直接提交,**不 push、不 merge、不开 PR**。

---

## File Structure(改动面)

- **修改** `rule-kernel/.../internal/context/EvalContextAssembler.java` — `fetchExecutor` 字段/构造参 `Executor→ExecutorService`;`fetchConcurrently` 改 `invokeAll`。
- **修改** `rule-kernel/.../internal/context/EvalContextAssemblerFetchTest.java` — 7 处 `Runnable::run →` 共享 `ExecutorService`;新增 2 个超时测试。
- **修改** `rule-eval-svc/.../eval/EvalAutoConfiguration.java` — `metricFetchExecutor` bean 返回 `ExecutorService`(vthread);注入点形参 `Executor→ExecutorService`。
- **修改** `rule-sdk/.../sdk/RuleEngineClient.java` — Builder `fetchExecutor` 字段/setter `Executor→ExecutorService`。
- **不动**:`MetricSourceHandler` SPI、`EvalEngine`、AST、缓存、`MetricVersionResolveTest`/`EvalEngineBenchmark`(传 `null`,`null` 可赋 `ExecutorService`,免改)。

---

## Task 1: Executor→ExecutorService 类型迁移 + invokeAll 改写(原子,跨 3 模块)

**说明:** 改 kernel 构造签名会令依赖它的 eval-svc / sdk 不编译,故四处签名相关改动 + 既有 7 个 fetch 测试**同一 commit**完成,保证 reactor 始终可编译。既有 7 个测试 + 全量 kernel 套件是「行为不变」的回归守门。

**Files:**
- Modify: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/context/EvalContextAssembler.java`
- Modify: `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/context/EvalContextAssemblerFetchTest.java`
- Modify: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/EvalAutoConfiguration.java`
- Modify: `rule-sdk/src/main/java/com/sstlfsj/rule/sdk/RuleEngineClient.java`

- [ ] **Step 1: 改 `EvalContextAssembler` 字段类型**

把字段
```java
    private final Executor fetchExecutor;
```
改为
```java
    private final ExecutorService fetchExecutor;
```
(文件已 `import java.util.concurrent.*;`,`ExecutorService` 现成,无需加 import。)

- [ ] **Step 2: 改 6 参构造形参类型 + Javadoc**

把 6 参构造的形参
```java
                                Executor fetchExecutor,
```
改为
```java
                                ExecutorService fetchExecutor,
```
Javadoc 那行同步改:
```java
     * @param fetchExecutor        并发取数 ExecutorService（null 时用 ForkJoinPool.commonPool）
```

- [ ] **Step 3: 改写 `fetchConcurrently` 为 invokeAll**

整体替换 `fetchConcurrently` 方法体为:
```java
    private void fetchConcurrently(RuleEvent event, Instant now, Set<String> codes,
                                   Map<String, MetricDescriptor> descriptors,
                                   Map<String, MetricValue> metrics) {
        ExecutorService exec = fetchExecutor != null ? fetchExecutor : ForkJoinPool.commonPool();
        long timeoutMs = fetchTimeoutMs > 0 ? fetchTimeoutMs : Long.MAX_VALUE;

        List<String> orderedCodes = new ArrayList<>(codes);
        List<Callable<MetricValue>> tasks = new ArrayList<>(orderedCodes.size());
        for (String code : orderedCodes) {
            MetricDescriptor def = descriptors.get(code);
            MetricQuery query = new MetricQuery(code, event.tenantId(), event.subjectId(),
                    def.params(), event.payload(), now);
            MetricSourceHandler handler = handlersBySourceType.get(def.sourceType());
            tasks.add(() -> {
                if (handler == null) return MetricValue.error(METRIC_FETCH_FAIL);
                try {
                    MetricValue v = handler.fetch(query);
                    return v != null ? v : MetricValue.error(METRIC_FETCH_FAIL);
                } catch (Exception e) {
                    // 子任务内吞异常→降级（替代旧 .exceptionally）
                    return MetricValue.error(METRIC_FETCH_FAIL);
                }
            });
        }

        List<Future<MetricValue>> results;
        try {
            results = exec.invokeAll(tasks, timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            // 调用线程被中断：恢复中断位并全部降级
            Thread.currentThread().interrupt();
            for (String code : orderedCodes) metrics.put(code, MetricValue.error(METRIC_FETCH_FAIL));
            return;
        }

        for (int i = 0; i < orderedCodes.size(); i++) {
            String code = orderedCodes.get(i);
            Future<MetricValue> f = results.get(i);
            MetricValue v;
            if (f.isCancelled()) {
                // 超时未完成：invokeAll 已中断该子任务
                v = MetricValue.error(METRIC_FETCH_FAIL);
            } else {
                try { v = f.get(); } catch (Exception e) { v = MetricValue.error(METRIC_FETCH_FAIL); }
            }
            metrics.put(code, v);
            if (cache != null && !v.isError()) {
                MetricDescriptor def = descriptors.get(code);
                if (def.cacheTtlSeconds() > 0) {
                    // 缓存键的 metricCode 段含 version，与取时保持一致
                    cache.put(cacheKey(event.tenantId(), code + ":" + def.metricVersion(),
                            event.subjectId(), def.params()), v, def.cacheTtlSeconds());
                }
            }
        }
    }
```
(`Callable` / `Future` / `TimeUnit` / `ForkJoinPool` 均在 `java.util.concurrent.*` 内,已 import。)

- [ ] **Step 4: 改 `EvalAutoConfiguration` 的 bean + 注入点**

bean 方法整体替换为:
```java
    @Bean(name = "metricFetchExecutor")
    public ExecutorService metricFetchExecutor() {
        // 虚拟线程-per-task：无固定上限，取数并发由下游连接池兜底；ExecutorService 自带 AutoCloseable，Spring 关闭时 close
        return Executors.newVirtualThreadPerTaskExecutor();
    }
```
注入点形参:
```java
            @Qualifier("metricFetchExecutor") Executor fetchExecutor,
```
改为:
```java
            @Qualifier("metricFetchExecutor") ExecutorService fetchExecutor,
```
import 调整:删 `import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;`,加
```java
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
```
(若文件已有 `import java.util.concurrent.Executor;` 且不再被其它处使用,一并删除;若仍被其它 bean 用则保留。)

- [ ] **Step 5: 改 sdk `RuleEngineClient.Builder` 的 fetchExecutor 类型**

import:`import java.util.concurrent.Executor;` → `import java.util.concurrent.ExecutorService;`
字段:
```java
        private Executor fetchExecutor;
```
改为
```java
        private ExecutorService fetchExecutor;
```
setter:
```java
        public Builder fetchExecutor(Executor v) { this.fetchExecutor = v; return this; }
```
改为
```java
        public Builder fetchExecutor(ExecutorService v) { this.fetchExecutor = v; return this; }
```
(setter 上方 Javadoc `@param v Executor` 改 `@param v ExecutorService`。)

- [ ] **Step 6: 改 `EvalContextAssemblerFetchTest` 的 executor(7 处)+ 加共享 ExecutorService**

文件顶部 import 区加:
```java
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
```
类体开头(`private static final Instant NOW = ...` 之后)加共享字段 + 收尾:
```java
    private static final ExecutorService EXEC = Executors.newVirtualThreadPerTaskExecutor();

    @AfterAll
    static void closeExec() { EXEC.shutdown(); }
```
把全部 7 处构造里的 `Runnable::run` 替换为 `EXEC`(在 lines ~42/58/72/85/98/116/130 的 `new EvalContextAssembler(... , Runnable::run, <timeout>L)`)。

- [ ] **Step 7: 跑 kernel 测试(行为不变回归)**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-25.jdk/Contents/Home; export PATH=$JAVA_HOME/bin:$PATH; MVN=/Users/sunke/.m2/wrapper/dists/apache-maven-3.9.9-bin/4nf9hui3q3djbarqar9g711ggc/apache-maven-3.9.9/bin/mvn
$MVN -pl rule-kernel test
```
Expected: `BUILD SUCCESS`;`EvalContextAssemblerFetchTest` 7 个全绿,kernel 总数与改前一致(541 上下)。

- [ ] **Step 8: 验证整 reactor 编译(sdk + eval-svc + app 都跟上签名)**

```bash
$MVN -pl rule-app -am -DskipTests package
```
Expected: `BUILD SUCCESS`(证明 eval-svc 注入点、sdk Builder 已与新签名一致,无编译错误)。

- [ ] **Step 9: Commit**

```bash
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/context/EvalContextAssembler.java \
        rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/context/EvalContextAssemblerFetchTest.java \
        rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/EvalAutoConfiguration.java \
        rule-sdk/src/main/java/com/sstlfsj/rule/sdk/RuleEngineClient.java
git commit -m "perf(eval): metric 取数 fan-out 改虚拟线程 + invokeAll，去 32 线程上限

metricFetchExecutor 固定平台池(core8/max32/queue256)→ vthread-per-task；
fetchConcurrently 的 allOf+手动 cancel → invokeAll(tasks,timeout) 内建超时取消。
fetchExecutor 类型 Executor→ExecutorService（波及 kernel/eval-svc/sdk 签名）。
行为不变：routing/provided/cache/降级语义与既有 7 个 fetch 测试对齐。"
```

---

## Task 2: 新增 2 个超时测试(锁 invokeAll 超时/取消语义)

**说明:** 既有 7 个测试 handler 均瞬时返回,从不触发超时分支。补两个用真实 vthread executor + 短 timeout 的测试,锁定「超时→降级 error 且不挂起」「慢指标不毒化快指标」。impl 已在 Task 1 落地,故两个测试预期直接 PASS(作为回归锁)。

**Files:**
- Modify: `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/context/EvalContextAssemblerFetchTest.java`

- [ ] **Step 1: 加 `fetchTimeout_degradesToError` + `partialTimeout_fastSucceedsSlowDegrades`**

在 `EvalContextAssemblerFetchTest` 类体内(`queryCarriesNow` 之后)追加:
```java
    @Test
    void fetchTimeout_degradesToError() {
        // handler 睡 500ms 超过 50ms 超时 → invokeAll 中断该子任务 → 降级 error，且不挂满 500ms
        MetricSourceHandler slow = q -> {
            try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return new MetricValue(1L, "LONG", "FETCHED");
        };
        MetricDefinitionResolver resolver = (t, c, v) -> sqlDef(c, false, 0);
        EvalContextAssembler asm = new EvalContextAssembler(
                List.of(), Map.of("SQL_AGGREGATE", slow), resolver, null, EXEC, 50L);

        EvalContext ctx = asm.assemble(event(Map.of()), List.of(snapWithDep("balance")), NOW);

        MetricValue mv = ctx.getMetric("balance");
        assertThat(mv.isError()).isTrue();
        assertThat(mv.errorCode()).isEqualTo("METRIC_FETCH_FAIL");
    }

    @Test
    void partialTimeout_fastSucceedsSlowDegrades() {
        // fast 瞬时、slow 睡 500ms；50ms 超时 → fast 得值、slow 降级，证明慢指标不拖挂整批、不毒化快指标
        MetricSourceHandler handler = q -> {
            if ("slow".equals(q.metricCode())) {
                try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            return new MetricValue("slow".equals(q.metricCode()) ? 2L : 1L, "LONG", "FETCHED");
        };
        MetricDefinitionResolver resolver = (t, c, v) -> sqlDef(c, false, 0);
        EvalContextAssembler asm = new EvalContextAssembler(
                List.of(), Map.of("SQL_AGGREGATE", handler), resolver, null, EXEC, 50L);

        EvalContext ctx = asm.assemble(event(Map.of()),
                List.of(snapWithDep("fast"), snapWithDep("slow")), NOW);

        assertThat(ctx.getMetric("fast").value()).isEqualTo(1L);
        assertThat(ctx.getMetric("fast").isError()).isFalse();
        assertThat(ctx.getMetric("slow").isError()).isTrue();
    }
```
(`MetricQuery` 的访问器是 `metricCode()`,已核;`snapWithDep`/`sqlDef`/`event`/`NOW`/`EXEC` 均为本类已有成员。)

- [ ] **Step 2: 跑这两个测试**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-25.jdk/Contents/Home; export PATH=$JAVA_HOME/bin:$PATH; MVN=/Users/sunke/.m2/wrapper/dists/apache-maven-3.9.9-bin/4nf9hui3q3djbarqar9g711ggc/apache-maven-3.9.9/bin/mvn
$MVN -pl rule-kernel test -Dtest='EvalContextAssemblerFetchTest' -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: `Tests run: 9`(原 7 + 新 2),全绿。

- [ ] **Step 3: Commit**

```bash
git add rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/context/EvalContextAssemblerFetchTest.java
git commit -m "test(eval): 锁 metric 取数超时/部分超时降级语义

补 fetchTimeout_degradesToError + partialTimeout_fastSucceedsSlowDegrades，
覆盖 invokeAll 超时→中断→降级、慢指标不拖挂整批。"
```

---

## Task 3: 全量回归 gate(kernel + eval-svc)

**说明:** Task 1 的 Step 7 只跑了 kernel;eval-svc 有 Spring 上下文/AOT 相关测试,需确认换 vthread bean 后上下文仍装配正常。本任务无新代码,纯验证门。

**Files:** 无(验证门)。

- [ ] **Step 1: 跑 kernel + eval-svc 全量**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-25.jdk/Contents/Home; export PATH=$JAVA_HOME/bin:$PATH; MVN=/Users/sunke/.m2/wrapper/dists/apache-maven-3.9.9-bin/4nf9hui3q3djbarqar9g711ggc/apache-maven-3.9.9/bin/mvn
$MVN -pl rule-kernel,rule-eval-svc -am test
```
Expected: `BUILD SUCCESS`;kernel 543(541 + 新 2)、eval-svc 175 全绿。

- [ ] **Step 2: 若全绿,任务完成(无代码改动,不产生 commit)**

若 eval-svc 出现因 bean 类型变化导致的上下文/断言失败,回到对应测试按 `ExecutorService` 调整后再跑;不得 `-DskipTests` 绕过。

---

## Self-Review(对照 spec)

- **spec §3.1 bean → vthread**:Task 1 Step 4 ✓
- **spec §3.1 注入点类型**:Task 1 Step 4 ✓
- **spec §3.2 assembler 字段/构造/Javadoc**:Task 1 Step 1-2 ✓
- **spec §4 fetchConcurrently invokeAll 改写 + 语义对照**:Task 1 Step 3 ✓(逐条语义保持)
- **spec §5 回归(7 测试改 executor)**:Task 1 Step 6 ✓
- **spec §5 新增 2 超时测试**:Task 2 ✓
- **spec §5 全量 kernel+eval-svc 回归**:Task 3 ✓
- **spec §5 native 零新风险**:用 `newVirtualThreadPerTaskExecutor()`/`invokeAll` 正式 API,无 preview/反射 ✓
- **spec 漏项修正**:spec 称「两处文件」,实际签名变更波及 sdk `RuleEngineClient`(Builder.fetchExecutor 类型)——Task 1 Step 5 已纳入;`MetricVersionResolveTest`/`EvalEngineBenchmark` 传 `null` 免改。
- **占位符扫描**:无 TBD/TODO,每个改动步骤含完整代码 ✓
- **类型一致性**:`fetchExecutor` 全链路统一 `ExecutorService`(assembler 字段/构造、eval-svc 注入、sdk Builder),`EXEC` 测试字段类型一致 ✓
