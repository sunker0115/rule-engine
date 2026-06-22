# P0: STREAM MetricSourceHandler Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 填 `SourceType.STREAM` 坑位——`@MetricSourceType("STREAM")` handler 从 Redis Hash 读特征，引擎侧零改动，P0 先 Mock Redis 验证"STREAM handler 能注册、能取数、能进评估链路"。

**Architecture:** 加 `spring-boot-starter-data-redis` 依赖，新建 `StreamFeatureMetricSourceHandler`（粘 `SqlAggregateMetricSourceHandler` / `FeatureStoreHandler` 范式：`@Component` + `@MetricSourceType` + `implements MetricSourceHandler`）。`EvalContextAssembler` 自动收集，零 auto-config 改动。P0 测试 Mock Redis，P1 真连。

**Tech Stack:** Java 25 / Spring Boot Data Redis (Lettuce) / JUnit5 + Mockito + AssertJ。前置：`mvn-env` skill 设 `$MVN`（JDK25）。

设计依据：`docs/superpowers/specs/2026-06-17-realtime-streaming-risk-control-design.md` §3.4。

---

## 文件结构

**rule-eval-svc（仅改此模块）：**
- Modify: `pom.xml` — 加 `spring-boot-starter-data-redis`
- Create: `internal/metric/stream/StreamFeatureMetricSourceHandler.java`
- Create: `src/test/.../internal/metric/stream/StreamFeatureMetricSourceHandlerTest.java`

**rule-kernel / rule-app：不改。**

---

## Task 1: 加 spring-boot-starter-data-redis 依赖

**Files:**
- Modify: `rule-eval-svc/pom.xml`

- [ ] **Step 1: 看 eval-svc pom 当前依赖结构**

Read `rule-eval-svc/pom.xml`，找 Spring Boot starter 依赖的位置，确认 `<scope>`/optional 模式。

- [ ] **Step 2: 加依赖**

在合适位置追加（注：Redisson 版本由根 pom properties 集中管，不本处再写另外 version）：

```xml
        <!-- 流式特征取数：StringRedisTemplate(opsForHash) 读 Redis 特征库 -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-redis</artifactId>
        </dependency>
```

- [ ] **Step 3: 验证依赖解析**

Run: `$MVN -pl rule-eval-svc -am test-compile`
Expected: 依赖下载成功，编译通过。

- [ ] **Step 4: Commit**

```bash
git add rule-eval-svc/pom.xml
git commit -m "feat(eval-svc): 加 spring-boot-starter-data-redis(STREAM handler 依赖)"
```

---

## Task 2: 新建 StreamFeatureMetricSourceHandler

**Files:**
- Create: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/metric/stream/StreamFeatureMetricSourceHandler.java`

- [ ] **Step 1: 创建目录**

```bash
mkdir -p rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/metric/stream
```

- [ ] **Step 2: 写 handler（含新鲜度校验 todo）**

```java
package com.sstlfsj.rule.eval.internal.metric.stream;

import com.sstlfsj.rule.kernel.api.annotation.MetricSourceType;
import com.sstlfsj.rule.kernel.api.model.MetricQuery;
import com.sstlfsj.rule.kernel.api.model.MetricValue;
import com.sstlfsj.rule.kernel.api.model.SourceType;
import com.sstlfsj.rule.kernel.api.model.ValueSource;
import com.sstlfsj.rule.kernel.api.spi.metric.MetricSourceHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 流式预计算特征取数 handler（SourceType=STREAM）。
 *
 * <p>从 Redis Hash 按 subjectId(=customerId) + feature 字段名读预计算特征值。
 * key 规范 {@code rt:feat:{subjectId}}，field = {@code query.params().get("feature")}。
 * 写侧为外部 Flink 作业（rule-stream-rt），handler 只读不写。
 * 新鲜度校验留 P1。
 */
@Component
@MetricSourceType(SourceType.STREAM)
public class StreamFeatureMetricSourceHandler implements MetricSourceHandler {

    private static final Logger log = LoggerFactory.getLogger(StreamFeatureMetricSourceHandler.class);

    private final StringRedisTemplate redis;

    public StreamFeatureMetricSourceHandler(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public MetricValue fetch(MetricQuery query) {
        Object featureObj = query.params().get("feature");
        if (featureObj == null) {
            return MetricValue.error("STREAM_PARAM_MISSING");
        }
        String redisKey = "rt:feat:" + query.subjectId();
        String field = featureObj.toString();
        Object raw = null;
        try {
            raw = redis.opsForHash().get(redisKey, field);
        } catch (RuntimeException e) {
            log.warn("STREAM handler Redis 取数失败 key={} field={}", redisKey, field, e);
            return MetricValue.error("STREAM_REDIS_ERROR");
        }
        if (raw == null) {
            return MetricValue.error("STREAM_FEATURE_MISSING");
        }
        return new MetricValue(raw, "UNKNOWN", ValueSource.FETCHED.tag());
    }
}
```

> 注：`dataType` 传 `"UNKNOWN"` 是临时态——`MetricValue` 需要 dataType 但 handler 不知道特征的具体类型。后续 metric_definition STREAM 档扩展时 `MetricDefinitionResolver` 会把 `dataType` 注入 `query.params()`，届时取 `params.get("dataType")` 即可。

- [ ] **Step 3: 提交**

```bash
git add rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/metric/stream/
git commit -m "feat(eval-svc): StreamFeatureMetricSourceHandler(STREAM 源从 Redis Hash 读特征)"
```

---

## Task 3: 写 StreamFeatureMetricSourceHandler 测试

**Files:**
- Create: `rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/metric/stream/StreamFeatureMetricSourceHandlerTest.java`

- [ ] **Step 1: 创建测试目录 + 写测试**

```bash
mkdir -p rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/metric/stream
```

```java
package com.sstlfsj.rule.eval.internal.metric.stream;

import com.sstlfsj.rule.kernel.api.model.MetricQuery;
import com.sstlfsj.rule.kernel.api.model.MetricValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StreamFeatureMetricSourceHandlerTest {

    private StringRedisTemplate redis;
    private HashOperations<String, Object, Object> hashOps;
    private StreamFeatureMetricSourceHandler handler;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        hashOps = mock(HashOperations.class);
        when(redis.opsForHash()).thenReturn(hashOps);
        handler = new StreamFeatureMetricSourceHandler(redis);
    }

    private static MetricQuery query(String subjectId, String feature) {
        return new MetricQuery("rt_state", "9100", subjectId,
                Map.of("feature", feature), Map.of(), Instant.now(), Map.of());
    }

    @Test
    void fetch_returnsFeatureValue() {
        when(hashOps.get("rt:feat:customer-1", "rt_state")).thenReturn("RT_WATCH");

        MetricValue v = handler.fetch(query("customer-1", "rt_state"));

        assertThat(v.isError()).isFalse();
        assertThat(v.value()).isEqualTo("RT_WATCH");
    }

    @Test
    void fetch_missingFeature_returnsError() {
        when(hashOps.get("rt:feat:customer-1", "rt_state")).thenReturn(null);

        MetricValue v = handler.fetch(query("customer-1", "rt_state"));

        assertThat(v.isError()).isTrue();
        assertThat(v.errorCode()).isEqualTo("STREAM_FEATURE_MISSING");
    }

    @Test
    void fetch_missingFeatureParam_returnsError() {
        MetricQuery q = new MetricQuery("rt_state", "9100", "customer-1",
                Map.of(), Map.of(), Instant.now(), Map.of());

        MetricValue v = handler.fetch(q);

        assertThat(v.isError()).isTrue();
        assertThat(v.errorCode()).isEqualTo("STREAM_PARAM_MISSING");
    }

    @Test
    void fetch_redisException_returnsError() {
        when(hashOps.get("rt:feat:customer-1", "rt_state"))
                .thenThrow(new RuntimeException("connection timeout"));

        MetricValue v = handler.fetch(query("customer-1", "rt_state"));

        assertThat(v.isError()).isTrue();
        assertThat(v.errorCode()).isEqualTo("STREAM_REDIS_ERROR");
    }
}
```

- [ ] **Step 2: 跑测试**

Run: `$MVN -pl rule-eval-svc -am test -Dtest='StreamFeatureMetricSourceHandlerTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 4 tests pass。

- [ ] **Step 3: 提交**

```bash
git add rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/metric/stream/
git commit -m "test(eval-svc): StreamFeatureMetricSourceHandler 测试(4 用例:取数/缺值/缺参/Redis异常)"
```

---

## Task 4: 验证 handler 被 EvalContextAssembler 自动收集

- [ ] **Step 1: 写装配验证测试**

找到 eval-svc 现有 `AutoConfiguration` 测试（`EvalAutoConfigurationTest` 或类似），追加：

```java
    @Test
    void streamHandlerRegisteredAsMetricSource() {
        // runner 已含 EvalAutoConfiguration + 默认 bean
        // 断言 context 中 StreamFeatureMetricSourceHandler bean 存在
        // 断言 @MetricSourceType("STREAM") 归类到 EvalContextAssembler.handlersBySourceType
    }
```

> 具体断言方式取决于现有测试的 `ApplicationContextRunner` 结构。先 Read 现有装配测试再写。

- [ ] **Step 2: 跑装配测试**

Run: `$MVN -pl rule-eval-svc -am test -Dtest='EvalAutoConfigurationTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 包含新增用例，全绿。

- [ ] **Step 3: 提交**

---

## Task 5: 全量 clean test 兜底

- [ ] **Step 1: 全量**

Run: `$MVN clean test`
Expected: 27 模块全绿。

---

## Self-Review

**Spec 覆盖：**
- §3.4 `StreamFeatureMetricSourceHandler` → Task 2 ✓
- `@MetricSourceType("STREAM")` 注解 → Task 2 ✓
- 取数：`rt:feat:{subjectId}` hash `field`=params.feature → Task 2 ✓
- 缺值降级 `MetricValue.error` → Task 2 ✓
- Redis 异常降级 → Task 2 + Task 3 ✓
- 自动装配零改动 → Task 4 ✓
- 新鲜度校验留 P1 → 注在 Task 2 注释 ✓

**类型一致性：**
- `MetricQuery.subjectId()` = customerId，`params().get("feature")` = 特征字段名
- 返回 `MetricValue` 成功/error 均与现有 handler（`SqlAggregateMetricSourceHandler` / `FeatureStoreHandler`）范式一致
- `dataType` 临时 `"UNKNOWN"`，metric_definition STREAM 档扩展后切 `params.get("dataType")`

**占位符扫描：** 无 TBD/TODO。Task 4 待 Read 现有装配测试后补完整断言。
