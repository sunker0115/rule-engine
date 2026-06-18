# B3 可观测告警阈值 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在评估错误率或 trace 队列利用率超阈值时，通过 `EvalAlarmEvent` 发布告警，v1 打 WARN 日志，全部告警逻辑集中在 `rule-observability`。

**Architecture:** eval-svc 注册 Counter（evalError / evalTotal）并在 `afterPropertiesSet` 注册 Gauge（trace 队列利用率），所有埋点通过进程内共享 `MeterRegistry`；`rule-observability` 的 `ObservabilityAlarmChecker`（@Scheduled）读 MeterRegistry 值、发 `EvalAlarmEvent`；`ObservabilityAlarmListener`（@EventListener）打 WARN 日志，未来可换 Webhook/钉钉通道。

**Tech Stack:** Micrometer、Spring @Scheduled、Spring @EventListener、Lombok @Getter @Setter、JUnit5 + AssertJ + Mockito。前置：`mvn-env` skill 设 `$MVN`（JDK25）。

---

## 文件结构

**新建（rule-observability）：**
- `api/events/EvalAlarmEvent.java`
- `internal/alarm/ObservabilityAlarmProperties.java`
- `internal/alarm/ObservabilityAlarmChecker.java`
- `internal/alarm/ObservabilityAlarmListener.java`

**修改（rule-eval-svc）：**
- `EvalAutoConfiguration.java`：加 3 个 @Bean（evalErrorCounter、evalTotalCounter + PushEventDispatcher Gauge via Autowired）
- `PushEventDispatcher.java`：加 `queueSize()` / `queueCapacity()` 只读方法
- `EvalServiceImpl.java`：构造器加 Counter 注入 + MeterRegistry，`afterPropertiesSet` 注册 Gauge，`doEvaluate` 埋点

**修改（rule-observability）：**
- `ObservabilityAutoConfiguration.java`：加 @EnableConfigurationProperties + 注册 alarm bean

---

## Task 1: PushEventDispatcher 暴露只读方法

**Files:**
- Modify: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/dispatch/PushEventDispatcher.java`
- Test: `rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/dispatch/PushEventDispatcherTest.java`（已存在，追加）

- [ ] **Step 1: 写失败测试（追加到既有 PushEventDispatcherTest）**

```java
@Test
void queueSize_and_capacity_exposed() {
    PushEventDispatcher dispatcher = new PushEventDispatcher(100, e -> {});
    dispatcher.start();
    assertThat(dispatcher.queueCapacity()).isEqualTo(100);
    assertThat(dispatcher.queueSize()).isZero();
    dispatcher.stop();
}
```

- [ ] **Step 2: 跑确认失败**

Run: `$MVN -pl rule-eval-svc -am test -Dtest='PushEventDispatcherTest#queueSize_and_capacity_exposed' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败（方法不存在）。

- [ ] **Step 3: 在 PushEventDispatcher.java 的 `submit()` 方法后追加**

```java
    /** trace 队列当前积压数（Gauge 注册用）。 */
    public int queueSize() { return queue != null ? queue.size() : 0; }

    /** trace 队列最大容量（Gauge 注册用）。 */
    public int queueCapacity() { return capacity; }
```

- [ ] **Step 4: 跑测试通过**

Run: `$MVN -pl rule-eval-svc -am test -Dtest='PushEventDispatcherTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/dispatch/PushEventDispatcher.java \
        rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/dispatch/PushEventDispatcherTest.java
git commit -m "feat(eval): PushEventDispatcher 暴露 queueSize/queueCapacity(Gauge 注册用)"
```

---

## Task 2: EvalAlarmEvent

**Files:**
- Create: `rule-observability/src/main/java/com/sstlfsj/rule/observability/api/events/EvalAlarmEvent.java`
- Test: `rule-observability/src/test/java/com/sstlfsj/rule/observability/api/events/EvalAlarmEventTest.java`

- [ ] **Step 1: 写测试**

```java
package com.sstlfsj.rule.observability.api.events;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class EvalAlarmEventTest {
    @Test
    void fields_accessible() {
        EvalAlarmEvent e = new EvalAlarmEvent("rule_eval_error_total", 0.05, 0.12, "错误率超阈值");
        assertThat(e.metric()).isEqualTo("rule_eval_error_total");
        assertThat(e.threshold()).isEqualTo(0.05);
        assertThat(e.actual()).isEqualTo(0.12);
        assertThat(e.message()).isEqualTo("错误率超阈值");
    }
}
```

- [ ] **Step 2: 跑确认失败**

Run: `$MVN -pl rule-observability -am test -Dtest='EvalAlarmEventTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败。

- [ ] **Step 3: 新建 EvalAlarmEvent**

```java
package com.sstlfsj.rule.observability.api.events;

/**
 * 评估告警事件，由 ObservabilityAlarmChecker 发布。
 * 定义在 observability api 层，任何模块可监听扩展（Webhook / 钉钉等）；
 * v1 由 ObservabilityAlarmListener 打 WARN 日志。
 *
 * @param metric    指标名（RuleMetrics 常量）
 * @param threshold 配置阈值
 * @param actual    当前实测值
 * @param message   可读告警消息
 */
public record EvalAlarmEvent(String metric, double threshold, double actual, String message) {}
```

- [ ] **Step 4: 跑测试通过**

Run: `$MVN -pl rule-observability -am test -Dtest='EvalAlarmEventTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add rule-observability/src/main/java/com/sstlfsj/rule/observability/api/events/EvalAlarmEvent.java \
        rule-observability/src/test/java/com/sstlfsj/rule/observability/api/events/EvalAlarmEventTest.java
git commit -m "feat(observability): EvalAlarmEvent 告警事件 record"
```

---

## Task 3: ObservabilityAlarmProperties

**Files:**
- Create: `rule-observability/src/main/java/com/sstlfsj/rule/observability/internal/alarm/ObservabilityAlarmProperties.java`
- Test: `rule-observability/src/test/java/com/sstlfsj/rule/observability/internal/alarm/ObservabilityAlarmPropertiesTest.java`

- [ ] **Step 1: 写测试**

```java
package com.sstlfsj.rule.observability.internal.alarm;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;

class ObservabilityAlarmPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Config.class);

    @EnableConfigurationProperties(ObservabilityAlarmProperties.class)
    static class Config {}

    @Test
    void defaults() {
        runner.run(ctx -> {
            ObservabilityAlarmProperties p = ctx.getBean(ObservabilityAlarmProperties.class);
            assertThat(p.getEvalErrorRateThreshold()).isEqualTo(0.05);
            assertThat(p.getTraceQueueFullThreshold()).isEqualTo(0.8);
            assertThat(p.getCheckIntervalMs()).isEqualTo(60_000L);
        });
    }

    @Test
    void binds_under_prefix() {
        runner.withPropertyValues(
                "engine.rule.observability.eval-error-rate-threshold=0.1",
                "engine.rule.observability.trace-queue-full-threshold=0.9",
                "engine.rule.observability.check-interval-ms=30000")
                .run(ctx -> {
                    ObservabilityAlarmProperties p = ctx.getBean(ObservabilityAlarmProperties.class);
                    assertThat(p.getEvalErrorRateThreshold()).isEqualTo(0.1);
                    assertThat(p.getTraceQueueFullThreshold()).isEqualTo(0.9);
                    assertThat(p.getCheckIntervalMs()).isEqualTo(30_000L);
                });
    }
}
```

- [ ] **Step 2: 跑确认失败**

Run: `$MVN -pl rule-observability -am test -Dtest='ObservabilityAlarmPropertiesTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败。

- [ ] **Step 3: 新建 ObservabilityAlarmProperties**

```java
package com.sstlfsj.rule.observability.internal.alarm;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 可观测告警阈值配置，绑定 engine.rule.observability.* 前缀。
 * 已在 application.yml 占位（eval-error-rate-threshold / trace-queue-full-threshold）。
 */
@Getter
@Setter
@ConfigurationProperties("engine.rule.observability")
public class ObservabilityAlarmProperties {

    /** 评估错误率告警阈值（0~1，默认 5%）。 */
    private double evalErrorRateThreshold = 0.05;
    /** trace 队列利用率告警阈值（0~1，默认 80%）。 */
    private double traceQueueFullThreshold = 0.8;
    /** 检查间隔（毫秒，默认 1 分钟）。 */
    private long checkIntervalMs = 60_000L;
}
```

- [ ] **Step 4: 跑测试通过**

Run: `$MVN -pl rule-observability -am test -Dtest='ObservabilityAlarmPropertiesTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add rule-observability/src/main/java/com/sstlfsj/rule/observability/internal/alarm/ObservabilityAlarmProperties.java \
        rule-observability/src/test/java/com/sstlfsj/rule/observability/internal/alarm/ObservabilityAlarmPropertiesTest.java
git commit -m "feat(observability): ObservabilityAlarmProperties 告警阈值配置"
```

---

## Task 4: EvalAutoConfiguration 注册 Counter bean + EvalServiceImpl 埋点

**Files:**
- Modify: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/EvalAutoConfiguration.java`
- Modify: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/service/EvalServiceImpl.java`
- Test: `rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/EvalAutoConfigurationTest.java`（已存在，追加）

> **架构说明**：PushEventDispatcher 在 EvalServiceImpl 构造器内 new，不是 Spring bean，因此 Gauge（trace 队列利用率）在 EvalServiceImpl.afterPropertiesSet() 里注册（dispatcher 已存在）。Counter（evalError / evalTotal）作为 bean 由 EvalAutoConfiguration 声明，注入 EvalServiceImpl 构造器。

- [ ] **Step 1: EvalAutoConfiguration 加 2 个 Counter bean**

在 `EvalAutoConfiguration.java` 中加（import `io.micrometer.core.instrument.Counter`、`MeterRegistry`，使用 Spring Boot 自动装配的 MeterRegistry bean）：

```java
    /**
     * 评估错误计数器（errorCode 非 null 时 increment）。
     *
     * @param meterRegistry Spring Boot 自动装配的 Micrometer 注册表
     * @return Counter 实例
     */
    @Bean
    public Counter evalErrorCounter(MeterRegistry meterRegistry) {
        return Counter.builder(RuleMetrics.EVAL_ERROR_TOTAL)
                .description("评估错误总数（errorCode 非空）")
                .register(meterRegistry);
    }

    /**
     * 评估总次数计数器（每次 doEvaluate 入口 increment）。
     *
     * @param meterRegistry Spring Boot 自动装配的 Micrometer 注册表
     * @return Counter 实例
     */
    @Bean
    public Counter evalTotalCounter(MeterRegistry meterRegistry) {
        return Counter.builder(RuleMetrics.EVAL_TOTAL)
                .description("评估总次数")
                .register(meterRegistry);
    }
```

同时加 import：
```java
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import com.sstlfsj.rule.observability.api.metrics.RuleMetrics;
```

- [ ] **Step 2: EvalServiceImpl 构造器加 3 个参数（Counter×2 + MeterRegistry）**

当前构造器 4 参，改为 7 参（追加到末尾）：

```java
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import com.sstlfsj.rule.observability.api.metrics.RuleMetrics;

// 新增字段（在 dispatcher 字段之后）
private final Counter evalErrorCounter;
private final Counter evalTotalCounter;
private final MeterRegistry meterRegistry;

// 构造器末尾追加 3 个参数
EvalServiceImpl(EvalEngine evalEngine, SceneSnapshotLoader snapshotLoader,
                DomainEventPublisher eventPublisher,
                RuleVersionReadMapper ruleVersionReadMapper,
                Counter evalErrorCounter,
                Counter evalTotalCounter,
                MeterRegistry meterRegistry) {
    this.evalEngine = evalEngine;
    this.snapshotLoader = snapshotLoader;
    this.eventPublisher = eventPublisher;
    this.ruleVersionReadMapper = ruleVersionReadMapper;
    this.evalErrorCounter = evalErrorCounter;
    this.evalTotalCounter = evalTotalCounter;
    this.meterRegistry = meterRegistry;
    this.dispatcher = new PushEventDispatcher(10000, e -> doEvaluate(e, EvalMode.PUSH, false, null, null));
}
```

- [ ] **Step 3: afterPropertiesSet 注册 Gauge**

在 `dispatcher.start();` 之后追加：

```java
        // Gauge 在 dispatcher.start() 之后注册，此时 queue 已初始化
        Gauge.builder(RuleMetrics.TRACE_QUEUE_SIZE, dispatcher,
                d -> d.queueCapacity() > 0
                        ? (double) d.queueSize() / d.queueCapacity()
                        : 0.0)
                .description("trace 队列利用率（queueSize/capacity，0~1）")
                .register(meterRegistry);
```

- [ ] **Step 4: doEvaluate 埋点（在方法 evalNow 行之后最前面）**

在 `doEvaluate` 方法体第一行（`Instant evalNow = ...` 之前）追加：

```java
        evalTotalCounter.increment();
```

在方法体内 EvalResult 返回前找到 `result` 被赋值之后（在 dry-run 和普通两条路径的 return 之前）各自追加：

> **简化方案**：在现有 `eventPublisher.publish(new AuditRecordedEvent(...))` 这行之后（普通路径）和 dry-run 的 `eventPublisher.publish(new DryRunRecordedEvent(...))` 之后各追加：
> ```java
> if (result.errorCode() != null) { evalErrorCounter.increment(); }
> ```
> （result 是本次评估结果，`EvalResult.error()` 时 errorCode 非 null。）

- [ ] **Step 5: EvalAutoConfigurationTest 追加验证**

```java
@Test
void evalCounterBeans_registered_in_meterRegistry() {
    // 复用既有测试类 ApplicationContextRunner / Config
    // 断言 meterRegistry.find(EVAL_ERROR_TOTAL).counter() != null
    // 断言 meterRegistry.find(EVAL_TOTAL).counter() != null
    runner.run(ctx -> {
        MeterRegistry registry = ctx.getBean(MeterRegistry.class);
        assertThat(registry.find(RuleMetrics.EVAL_ERROR_TOTAL).counter()).isNotNull();
        assertThat(registry.find(RuleMetrics.EVAL_TOTAL).counter()).isNotNull();
    });
}
```

> 注：`runner` 的创建参考该测试类既有 setup；需引入 `MeterRegistry` 依赖（Spring Boot Actuator/Micrometer 已在 eval-svc pom）。

- [ ] **Step 6: 跑 eval-svc 全量**

Run: `$MVN -pl rule-eval-svc -am test`
Expected: 全绿。EvalServiceImpl 新增了 3 个构造器参数，Spring 能从 context 注入（evalErrorCounter/evalTotalCounter bean 在 EvalAutoConfiguration，MeterRegistry 由 Spring Boot 自动装配）。

- [ ] **Step 7: Commit**

```bash
git add rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/EvalAutoConfiguration.java \
        rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/service/EvalServiceImpl.java \
        rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/EvalAutoConfigurationTest.java
git commit -m "feat(eval): 注册 Counter(evalError/evalTotal) + Gauge(traceQueueSize) + doEvaluate 埋点"
```

---

## Task 5: ObservabilityAlarmChecker

**Files:**
- Create: `rule-observability/src/main/java/com/sstlfsj/rule/observability/internal/alarm/ObservabilityAlarmChecker.java`
- Test: `rule-observability/src/test/java/com/sstlfsj/rule/observability/internal/alarm/ObservabilityAlarmCheckerTest.java`

- [ ] **Step 1: 写测试**

```java
package com.sstlfsj.rule.observability.internal.alarm;

import com.sstlfsj.rule.observability.api.events.EvalAlarmEvent;
import com.sstlfsj.rule.observability.api.metrics.RuleMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.util.concurrent.atomic.AtomicDouble;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ObservabilityAlarmCheckerTest {

    private MeterRegistry registry;
    private ApplicationEventPublisher publisher;
    private ObservabilityAlarmProperties props;
    private ObservabilityAlarmChecker checker;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        publisher = mock(ApplicationEventPublisher.class);
        props = new ObservabilityAlarmProperties();  // 默认值 0.05 / 0.8
        checker = new ObservabilityAlarmChecker(registry, props, publisher);
    }

    @Test
    void errorRate_above_threshold_publishes_alarm() {
        // total=100, error=10 → rate=0.10 > 0.05
        Counter total = Counter.builder(RuleMetrics.EVAL_TOTAL).register(registry);
        Counter error = Counter.builder(RuleMetrics.EVAL_ERROR_TOTAL).register(registry);
        total.increment(100);
        error.increment(10);

        checker.check();

        ArgumentCaptor<EvalAlarmEvent> cap = ArgumentCaptor.forClass(EvalAlarmEvent.class);
        verify(publisher).publishEvent(cap.capture());
        assertThat(cap.getValue().metric()).isEqualTo(RuleMetrics.EVAL_ERROR_TOTAL);
        assertThat(cap.getValue().actual()).isEqualTo(0.1);
    }

    @Test
    void errorRate_below_threshold_no_alarm() {
        Counter total = Counter.builder(RuleMetrics.EVAL_TOTAL).register(registry);
        Counter error = Counter.builder(RuleMetrics.EVAL_ERROR_TOTAL).register(registry);
        total.increment(100);
        error.increment(4);  // 4% < 5%

        checker.check();

        verify(publisher, never()).publishEvent(any());
    }

    @Test
    void queue_above_threshold_publishes_alarm() {
        AtomicDouble util = new AtomicDouble(0.9);
        Gauge.builder(RuleMetrics.TRACE_QUEUE_SIZE, util, AtomicDouble::get).register(registry);

        checker.check();

        ArgumentCaptor<EvalAlarmEvent> cap = ArgumentCaptor.forClass(EvalAlarmEvent.class);
        verify(publisher).publishEvent(cap.capture());
        assertThat(cap.getValue().metric()).isEqualTo(RuleMetrics.TRACE_QUEUE_SIZE);
    }

    @Test
    void total_zero_no_alarm() {
        // 无评估发生，不产生告警
        checker.check();
        verify(publisher, never()).publishEvent(any());
    }
}
```

- [ ] **Step 2: 跑确认失败**

Run: `$MVN -pl rule-observability -am test -Dtest='ObservabilityAlarmCheckerTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败（类不存在）。

- [ ] **Step 3: 新建 ObservabilityAlarmChecker**

```java
package com.sstlfsj.rule.observability.internal.alarm;

import com.sstlfsj.rule.observability.api.events.EvalAlarmEvent;
import com.sstlfsj.rule.observability.api.metrics.RuleMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定期检查评估错误率 + trace 队列利用率，超阈值时发布 {@link EvalAlarmEvent}。
 * 从进程内共享 MeterRegistry 读值，零跨模块耦合；告警逻辑全集中在 observability。
 */
@Component
public class ObservabilityAlarmChecker {

    private final MeterRegistry meterRegistry;
    private final ObservabilityAlarmProperties props;
    private final ApplicationEventPublisher eventPublisher;

    public ObservabilityAlarmChecker(MeterRegistry meterRegistry,
                                     ObservabilityAlarmProperties props,
                                     ApplicationEventPublisher eventPublisher) {
        this.meterRegistry = meterRegistry;
        this.props = props;
        this.eventPublisher = eventPublisher;
    }

    @Scheduled(fixedDelayString = "${engine.rule.observability.check-interval-ms:60000}")
    public void check() {
        checkErrorRate();
        checkQueueUtilization();
    }

    void checkErrorRate() {
        double total = count(RuleMetrics.EVAL_TOTAL);
        if (total == 0) return;
        double errors = count(RuleMetrics.EVAL_ERROR_TOTAL);
        double rate = errors / total;
        if (rate > props.getEvalErrorRateThreshold()) {
            eventPublisher.publishEvent(new EvalAlarmEvent(
                    RuleMetrics.EVAL_ERROR_TOTAL,
                    props.getEvalErrorRateThreshold(),
                    rate,
                    String.format("评估错误率 %.1f%% 超过阈值 %.1f%%",
                            rate * 100, props.getEvalErrorRateThreshold() * 100)));
        }
    }

    void checkQueueUtilization() {
        double util = gauge(RuleMetrics.TRACE_QUEUE_SIZE);
        if (util > props.getTraceQueueFullThreshold()) {
            eventPublisher.publishEvent(new EvalAlarmEvent(
                    RuleMetrics.TRACE_QUEUE_SIZE,
                    props.getTraceQueueFullThreshold(),
                    util,
                    String.format("trace 队列利用率 %.0f%% 超过阈值 %.0f%%",
                            util * 100, props.getTraceQueueFullThreshold() * 100)));
        }
    }

    private double count(String name) {
        Counter c = meterRegistry.find(name).counter();
        return c != null ? c.count() : 0.0;
    }

    private double gauge(String name) {
        Gauge g = meterRegistry.find(name).gauge();
        return g != null ? g.value() : 0.0;
    }
}
```

- [ ] **Step 4: 跑测试通过**

Run: `$MVN -pl rule-observability -am test -Dtest='ObservabilityAlarmCheckerTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 4 用例全绿。

- [ ] **Step 5: Commit**

```bash
git add rule-observability/src/main/java/com/sstlfsj/rule/observability/internal/alarm/ObservabilityAlarmChecker.java \
        rule-observability/src/test/java/com/sstlfsj/rule/observability/internal/alarm/ObservabilityAlarmCheckerTest.java
git commit -m "feat(observability): ObservabilityAlarmChecker @Scheduled 告警检查"
```

---

## Task 6: ObservabilityAlarmListener + ObservabilityAutoConfiguration

**Files:**
- Create: `rule-observability/src/main/java/com/sstlfsj/rule/observability/internal/alarm/ObservabilityAlarmListener.java`
- Modify: `rule-observability/src/main/java/com/sstlfsj/rule/observability/ObservabilityAutoConfiguration.java`
- Test: `rule-observability/src/test/java/com/sstlfsj/rule/observability/internal/alarm/ObservabilityAlarmListenerTest.java`

- [ ] **Step 1: 写 Listener 测试**

```java
package com.sstlfsj.rule.observability.internal.alarm;

import com.sstlfsj.rule.observability.api.events.EvalAlarmEvent;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThatCode;

class ObservabilityAlarmListenerTest {

    @Test
    void onAlarm_does_not_throw() {
        ObservabilityAlarmListener listener = new ObservabilityAlarmListener();
        EvalAlarmEvent event = new EvalAlarmEvent("rule_eval_error_total", 0.05, 0.12, "测试告警");
        assertThatCode(() -> listener.onAlarm(event)).doesNotThrowAnyException();
    }
}
```

- [ ] **Step 2: 新建 ObservabilityAlarmListener**

```java
package com.sstlfsj.rule.observability.internal.alarm;

import com.sstlfsj.rule.observability.api.events.EvalAlarmEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 告警事件监听器（v1）：收到 {@link EvalAlarmEvent} 打 WARN 日志。
 * 替换此 bean 或新增额外 @EventListener 即可扩展 Webhook / 钉钉等通道。
 */
@Component
public class ObservabilityAlarmListener {

    private static final Logger log = LoggerFactory.getLogger(ObservabilityAlarmListener.class);

    @EventListener
    public void onAlarm(EvalAlarmEvent event) {
        log.warn("[RULE_ALARM] metric={} actual={} threshold={} msg={}",
                event.metric(), event.actual(), event.threshold(), event.message());
    }
}
```

- [ ] **Step 3: ObservabilityAutoConfiguration 加 @EnableConfigurationProperties + @ComponentScan**

在 `ObservabilityAutoConfiguration.java` 的 `@EnableConfigurationProperties({...})` 里追加 `ObservabilityAlarmProperties.class`，并加 `@ComponentScan` 覆盖 `internal.alarm` 包（或显式注册 bean）。

**最小改动方案**（显式 @Bean 无需 ComponentScan）：

```java
// 追加到 @EnableConfigurationProperties
@EnableConfigurationProperties({TraceWriterProperties.class, RetentionProperties.class,
        ObservabilityAlarmProperties.class})

// 追加两个 @Bean 方法
    /**
     * 告警阈值检查器：定期读 MeterRegistry，超阈值发 EvalAlarmEvent。
     *
     * @param meterRegistry           Micrometer 注册表
     * @param props                   告警阈值配置
     * @param eventPublisher          Spring 事件发布器
     * @return ObservabilityAlarmChecker 实例
     */
    @Bean
    public ObservabilityAlarmChecker observabilityAlarmChecker(MeterRegistry meterRegistry,
                                                               ObservabilityAlarmProperties props,
                                                               ApplicationEventPublisher eventPublisher) {
        return new ObservabilityAlarmChecker(meterRegistry, props, eventPublisher);
    }

    /**
     * 告警监听器（v1 打 WARN 日志）。
     *
     * @return ObservabilityAlarmListener 实例
     */
    @Bean
    public ObservabilityAlarmListener observabilityAlarmListener() {
        return new ObservabilityAlarmListener();
    }
```

加 import：
```java
import com.sstlfsj.rule.observability.internal.alarm.ObservabilityAlarmChecker;
import com.sstlfsj.rule.observability.internal.alarm.ObservabilityAlarmListener;
import com.sstlfsj.rule.observability.internal.alarm.ObservabilityAlarmProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.ApplicationEventPublisher;
```

> **注意**：若 ObservabilityAlarmChecker/Listener 上有 `@Component`，显式 @Bean 会产生双重注册。选一种即可：要么去掉 `@Component`（由 AutoConfiguration 管理），要么不加显式 @Bean（依赖 @ComponentScan）。**推荐去掉 @Component，由 AutoConfiguration 统一管理**（符合 AutoConfiguration 模式）。

- [ ] **Step 4: 跑 rule-observability 全量**

Run: `$MVN -pl rule-observability -am test`
Expected: 全绿。

- [ ] **Step 5: Commit**

```bash
git add rule-observability/src/main/java/com/sstlfsj/rule/observability/internal/alarm/ \
        rule-observability/src/main/java/com/sstlfsj/rule/observability/ObservabilityAutoConfiguration.java \
        rule-observability/src/test/java/com/sstlfsj/rule/observability/internal/alarm/ObservabilityAlarmListenerTest.java
git commit -m "feat(observability): ObservabilityAlarmListener + AutoConfiguration 注册告警 bean"
```

---

## Task 7: 全量 clean test

- [ ] **Step 1: 全量**

Run: `$MVN clean test`
Expected: 27 模块全绿。

---

## Self-Review

**Spec 覆盖：**
- §2 PushEventDispatcher 只读方法 → T1 ✓
- §2 EvalAutoConfiguration Counter bean → T4 ✓
- §2 afterPropertiesSet 注册 Gauge → T4 ✓
- §2 doEvaluate 埋点 → T4 ✓
- §3.1 EvalAlarmEvent → T2 ✓
- §3.2 ObservabilityAlarmProperties → T3 ✓
- §3.3 ObservabilityAlarmChecker @Scheduled → T5 ✓
- §3.4 ObservabilityAlarmListener → T6 ✓
- §3.5 ObservabilityAutoConfiguration → T6 ✓
- §4 application.yml 已有占位，不需新增 ✓（计划里注明）
- §9 不做项 ✓（不接外部通道、不滑窗、不 health 降级）

**占位扫描：** T4 Step 4 的 doEvaluate 埋点说明用了">简化方案"描述，不够直接。下方补全：

> **T4 Step 4 补全**：在 EvalServiceImpl 的 `doEvaluate` 方法中，在 `Instant evalNow = ...` 这行**之前**加 `evalTotalCounter.increment();`。然后搜索方法内两处 `eventPublisher.publish(` 调用——dry-run 路径（约 L113）和普通路径（约 L140）——在**每处之后**加：
> ```java
> if (result.errorCode() != null) { evalErrorCounter.increment(); }
> ```
> `result` 即各自路径的 `EvalResult` 变量（dry-run 路径是 `outcome.result()`，普通路径是 `outcome.result()`）。

**类型一致性：** `EvalAlarmEvent`（record,4参）T2 定义,T5/T6 引用一致;`ObservabilityAlarmProperties` getter 名 T3 定义,T5/T6 用 `getEvalErrorRateThreshold()`/`getTraceQueueFullThreshold()` 一致;`RuleMetrics.EVAL_ERROR_TOTAL`/`EVAL_TOTAL`/`TRACE_QUEUE_SIZE` 全程引用同一常量类。
