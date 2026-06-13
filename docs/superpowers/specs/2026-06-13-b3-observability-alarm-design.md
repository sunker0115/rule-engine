# B3 可观测告警阈值设计

> 日期：2026-06-13。来源：B3（gap-assessment）。
>
> **目标**：让运营在评估错误率或 trace 队列接近满时收到告警，而不是等到真正出问题才发现。
>
> **原则**：eval-svc 只管埋点（MeterRegistry Counter/Gauge），告警逻辑全集中在 `rule-observability`，通过 `EvalAlarmEvent` 解耦，v1 打 WARN 日志，未来可换通道不改 Checker。

---

## 1. 架构约束

`rule-observability` 依赖 `rule-eval-svc`（TraceWriter SPI），反向依赖会成环——因此 observability 不能直接监听 eval-svc 的 `AuditRecordedEvent`。

解法：**MeterRegistry 是进程内全局共享的**，eval-svc 注册指标，observability 从 MeterRegistry 读值——零跨模块耦合。

---

## 2. eval-svc 侧改动（最小，~15 行）

### 2.1 PushEventDispatcher 暴露只读方法

```java
// PushEventDispatcher.java 追加两个访问器
public int queueSize()     { return queue.size(); }
public int queueCapacity() { return capacity; }
```

### 2.2 EvalAutoConfiguration 注册指标

注入 `MeterRegistry`，装配时注册：

```java
// 错误计数
Counter evalErrorCounter = Counter.builder(RuleMetrics.EVAL_ERROR_TOTAL)
        .description("评估错误总数（errorCode 非空）")
        .register(meterRegistry);

// 总评估次数（已有常量 EVAL_TOTAL）
Counter evalTotalCounter = Counter.builder(RuleMetrics.EVAL_TOTAL)
        .description("评估总次数")
        .register(meterRegistry);

// trace 队列利用率（Gauge，0~1）
Gauge.builder(RuleMetrics.TRACE_QUEUE_SIZE, pushEventDispatcher,
        d -> d.queueCapacity() > 0
                ? (double) d.queueSize() / d.queueCapacity()
                : 0.0)
        .description("trace 队列利用率（queueSize/capacity）")
        .register(meterRegistry);
```

`evalErrorCounter` 和 `evalTotalCounter` 存为 bean，注入 `EvalServiceImpl`。

### 2.3 EvalServiceImpl.doEvaluate() 埋点

```java
evalTotalCounter.increment();               // 每次评估
if (result.errorCode() != null) {
    evalErrorCounter.increment();           // 有错误码时
}
```

> `METRIC_FETCH_FAIL` 等 errorCode 会触发计数；正常 hit/miss 不算。`blockedBy`（Pre-Gate 拦截）不计入评估错误，只是候选为空的早退路径。

---

## 3. rule-observability 侧（全部告警逻辑）

### 3.1 EvalAlarmEvent（api/events，public）

```java
package com.sstlfsj.rule.observability.api.events;

/**
 * 评估告警事件，由 ObservabilityAlarmChecker 发布。
 * 定义在 observability api 层，任何模块可监听扩展（Webhook / 钉钉等）。
 *
 * @param metric    指标名（RuleMetrics 常量）
 * @param threshold 配置阈值
 * @param actual    当前实测值
 * @param message   可读告警消息
 */
public record EvalAlarmEvent(String metric, double threshold, double actual, String message) {}
```

### 3.2 ObservabilityAlarmProperties（@ConfigurationProperties）

```java
@Getter @Setter
@ConfigurationProperties("engine.rule.observability")
public class ObservabilityAlarmProperties {
    /** 评估错误率告警阈值（0~1，默认 0.05 = 5%）。 */
    private double evalErrorRateThreshold = 0.05;
    /** trace 队列利用率告警阈值（0~1，默认 0.8 = 80%）。 */
    private double traceQueueFullThreshold = 0.8;
    /** 检查间隔（毫秒，默认 60000 = 1 分钟）。 */
    private long checkIntervalMs = 60_000L;
}
```

### 3.3 ObservabilityAlarmChecker（@Component，@Scheduled）

```java
@Component
public class ObservabilityAlarmChecker {

    private final MeterRegistry meterRegistry;
    private final ObservabilityAlarmProperties props;
    private final ApplicationEventPublisher eventPublisher;

    public ObservabilityAlarmChecker(MeterRegistry meterRegistry,
                                     ObservabilityAlarmProperties props,
                                     ApplicationEventPublisher eventPublisher) { ... }

    @Scheduled(fixedDelayString = "${engine.rule.observability.check-interval-ms:60000}")
    public void check() {
        checkErrorRate();
        checkQueueUtilization();
    }

    private void checkErrorRate() {
        double total = count(RuleMetrics.EVAL_TOTAL);
        if (total == 0) return;
        double errors = count(RuleMetrics.EVAL_ERROR_TOTAL);
        double rate = errors / total;
        if (rate > props.getEvalErrorRateThreshold()) {
            eventPublisher.publishEvent(new EvalAlarmEvent(
                    RuleMetrics.EVAL_ERROR_TOTAL,
                    props.getEvalErrorRateThreshold(), rate,
                    String.format("评估错误率 %.1f%% 超过阈值 %.1f%%",
                            rate * 100, props.getEvalErrorRateThreshold() * 100)));
        }
    }

    private void checkQueueUtilization() {
        double util = gauge(RuleMetrics.TRACE_QUEUE_SIZE);
        if (util > props.getTraceQueueFullThreshold()) {
            eventPublisher.publishEvent(new EvalAlarmEvent(
                    RuleMetrics.TRACE_QUEUE_SIZE,
                    props.getTraceQueueFullThreshold(), util,
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

### 3.4 ObservabilityAlarmListener（@Component，v1 打 WARN）

```java
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

### 3.5 ObservabilityAutoConfiguration（追加）

```java
@EnableConfigurationProperties(ObservabilityAlarmProperties.class)
// 注册 ObservabilityAlarmChecker + ObservabilityAlarmListener bean
// 注意：需开启 @EnableScheduling（若 rule-app 未全局开，在此注解上加）
```

---

## 4. application.yml 配置（已占位，无需新增列）

```yaml
engine:
  rule:
    observability:
      eval-error-rate-threshold: 0.05
      trace-queue-full-threshold: 0.8
      # check-interval-ms: 60000  ← 可选覆盖，缺省 60s
```

---

## 5. 错误率计算语义

**累计比**（自启动以来）：`errorRate = EVAL_ERROR_TOTAL.count / EVAL_TOTAL.count`。

- 简单，无额外状态。
- 生产环境若需滑动窗口，在 Prometheus recording rule 层实现（`rate(rule_eval_error_total[5m]) / rate(rule_eval_total[5m])`），不改代码。
- `METRIC_FETCH_FAIL` / `NO_EVALUATOR` 等 errorCode 计入；Pre-Gate blockedBy 不计入（blockedBy 是正常拦截路径）。

---

## 6. 组件清单

**新建（rule-observability）：**
- `api/events/EvalAlarmEvent.java`
- `internal/alarm/ObservabilityAlarmProperties.java`
- `internal/alarm/ObservabilityAlarmChecker.java`
- `internal/alarm/ObservabilityAlarmListener.java`

**修改（rule-eval-svc）：**
- `EvalAutoConfiguration.java`（注册 Counter/Gauge bean，+MeterRegistry 入参，+PushEventDispatcher 入参）
- `PushEventDispatcher.java`（+queueSize()/queueCapacity()）
- `EvalServiceImpl.java`（+Counter 注入，doEvaluate 埋点）

**修改（rule-observability）：**
- `ObservabilityAutoConfiguration.java`（注册新 bean，开 @EnableScheduling）

---

## 7. 测试

- `ObservabilityAlarmPropertiesTest`：默认值断言。
- `ObservabilityAlarmCheckerTest`：mock MeterRegistry，模拟超阈值 → 断言 publishEvent 调用 EvalAlarmEvent；未超 → 不发。
- `ObservabilityAlarmListenerTest`：接收 EvalAlarmEvent → 不抛（行为测试，WARN 日志用 logback capturer 或直接断言不抛）。
- `EvalAutoConfigurationTest`（追加）：MeterRegistry 中能 find EVAL_ERROR_TOTAL / EVAL_TOTAL / TRACE_QUEUE_SIZE。
- `PushEventDispatcherTest`（追加）：queueSize()/queueCapacity() 返回预期值。

---

## 8. 成功判据

1. 服务启动后 `/actuator/prometheus` 能看到 `rule_eval_error_total`、`rule_eval_total`、`rule_trace_queue_size`。
2. 模拟错误率超 5% → WARN 日志含 `[RULE_ALARM]`。
3. 全量 clean test 27 模块绿。

---

## 9. 明确不做

- 不接外部告警通道（钉钉/Webhook/PagerDuty）——v1 WARN 日志够，接 listener 扩展即可。
- 不做滑动窗口（留 Prometheus recording rule）。
- 不暴露 `/actuator/health` 降级（告警是"提前知道"，health 是"已经坏了"，独立链路）。
