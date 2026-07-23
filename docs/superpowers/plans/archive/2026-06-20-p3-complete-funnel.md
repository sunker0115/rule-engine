# P3 完整漏斗 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 P2 微桶滚动 pipeline 上补齐实时风控漏斗：eventId 去重 → Stage-1 风险筛选门（Side Output）→ `rt.suspect.customer` Kafka topic → 新 `rule-rt-bridge` Spring Boot 模块消费并 HTTP 调引擎 → 决策落 `rt.decision`。

**Architecture:** rule-rt-stream 改 pipeline——`keyBy(customerId) → EventDedupFn`（MapState+TTL 去重）→ 三条窗口流各自 `keyBy` 重新分流 → merger → `Stage1GateFn`（主输出全量 Redis，OutputTag 侧输出过门 SuspectEvent）→ `SuspectEventSink`（KafkaSink）。新 `rule-rt-bridge` 纯 Spring Boot + Spring Kafka，`@KafkaListener` 消费 suspect → `RestClient` POST `rule-api /api/v1/rule/evaluate` → `KafkaTemplate` 发 `rt.decision`，不依赖任何 rule-* 内部 jar。

**Tech Stack:** Java 25、Flink 2.1.3（`KeyedProcessFunction`/`OutputTag`/`KafkaSink`/`StateTtlConfig`+`Duration`）、Spring Boot 4.1.0、Spring Kafka 4.1.0（BOM 管）、Jackson3、JUnit5 + AssertJ + flink-test-utils。前置：`mvn-env` skill 设 `$MVN`（JDK25）。

设计依据：`docs/superpowers/specs/2026-06-20-p3-complete-funnel-design.md`。

---

## 文件结构

**rule-rt-stream 改（Flink 侧）：**
- Create: `model/SuspectEvent.java` — suspect 消息 POJO（typed 字段，非 Map）
- Create: `feature/EventDedupFn.java` — eventId 去重（MapState + Duration TTL 10min）
- Create: `gate/ThresholdConfig.java` — 阈值常量
- Create: `gate/Stage1GateFn.java` — Side Output 风险筛选门
- Create: `sink/SuspectEventSink.java` — KafkaSink<SuspectEvent>
- Modify: `TradeStreamJob.java` — dedup 前置 + 三流重新 keyBy + Side Output 分流
- Test: `EventDedupFnTest.java` / `Stage1GateFnTest.java`

**根 pom：**
- Modify: `pom.xml` — `<modules>` 加 `rule-rt-bridge`

**rule-rt-bridge 新建（Spring Boot 侧）：**
- Create: `pom.xml`
- Create: `model/SuspectPayload.java` — suspect 反序列化 record
- Create: `EvalClient.java` — RestClient POST rule-api
- Create: `DecisionPublisher.java` — KafkaTemplate 发 rt.decision
- Create: `SuspectConsumer.java` — @KafkaListener 编排
- Create: `RtBridgeApp.java` — @SpringBootApplication
- Create: `src/main/resources/application.yml`
- Test: `EvalClientTest.java`（MockRestServiceServer）

---

## Task 1: SuspectEvent 模型（Flink 侧 POJO）

**Files:**
- Create: `rule-rt-stream/src/main/java/com/sstlfsj/rule/stream/model/SuspectEvent.java`

- [ ] **Step 1: 写 SuspectEvent**

```java
package com.sstlfsj.rule.stream.model;

import java.time.Instant;

/** Stage-1 风险筛选门过门事件，emit 到 rt.suspect.customer。typed 字段（非 Map），跨模块 JSON 契约。 */
public class SuspectEvent {
    public String customerId;
    public long rtmMwr1s;
    public long rtmMwr10s;
    public long rtmMwr1m;
    public long rtmMwr5m;
    public double rtdAmountSum;
    public double fastTradeRatio;
    public double susScore;
    public String rtState;
    public String suspectId;       // customerId + "-" + updatedAt，幂等键
    public Instant occurredAt;     // event-time

    public SuspectEvent() {}
}
```

- [ ] **Step 2: 编译**

Run: `$MVN -Plocal -pl rule-rt-stream compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add rule-rt-stream/src/main/java/com/sstlfsj/rule/stream/model/SuspectEvent.java
git commit -m "feat(stream-rt): SuspectEvent 模型——风险筛选门过门事件 POJO"
```

---

## Task 2: EventDedupFn — eventId 去重

**Files:**
- Create: `rule-rt-stream/src/main/java/com/sstlfsj/rule/stream/feature/EventDedupFn.java`
- Test: `rule-rt-stream/src/test/java/com/sstlfsj/rule/stream/feature/EventDedupFnTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.sstlfsj.rule.stream.feature;

import com.sstlfsj.rule.stream.model.TradeEvent;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.streaming.api.operators.KeyedProcessOperator;
import org.apache.flink.streaming.util.KeyedOneInputStreamOperatorTestHarness;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class EventDedupFnTest {

    private KeyedOneInputStreamOperatorTestHarness<String, TradeEvent, TradeEvent> harness() throws Exception {
        KeyedProcessOperator<String, TradeEvent, TradeEvent> op = new KeyedProcessOperator<>(new EventDedupFn());
        var h = new KeyedOneInputStreamOperatorTestHarness<>(op, TradeEvent::customerId, Types.STRING);
        h.open();
        return h;
    }

    private TradeEvent trade(String eventId) {
        return new TradeEvent("c1", "BTC", new BigDecimal("100.00"), "API", Instant.parse("2026-06-20T07:00:00Z"), eventId);
    }

    @Test
    void forwardsFirstDropsDuplicate() throws Exception {
        var h = harness();
        h.processElement(trade("e1"), 1);
        h.processElement(trade("e1"), 2);   // 同 eventId → drop
        h.processElement(trade("e2"), 3);   // 新 eventId → forward
        assertThat(h.extractOutputValues()).hasSize(2);
        h.close();
    }

    @Test
    void forwardsWhenEventIdBlank() throws Exception {
        var h = harness();
        TradeEvent noId = new TradeEvent("c1", "BTC", new BigDecimal("1"), "API", Instant.parse("2026-06-20T07:00:00Z"), "");
        h.processElement(noId, 1);
        h.processElement(noId, 2);   // 空 eventId 不过滤，全 forward
        assertThat(h.extractOutputValues()).hasSize(2);
        h.close();
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `$MVN -pl rule-rt-stream test -Dtest='EventDedupFnTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL（EventDedupFn 不存在，编译失败）

- [ ] **Step 3: 写 EventDedupFn**

```java
package com.sstlfsj.rule.stream.feature;

import com.sstlfsj.rule.stream.model.TradeEvent;
import org.apache.flink.api.common.state.MapState;
import org.apache.flink.api.common.state.MapStateDescriptor;
import org.apache.flink.api.common.state.StateTtlConfig;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.util.Collector;

import java.time.Duration;

/**
 * eventId 去重：MapState&lt;eventId,Boolean&gt; + 10min state TTL（OnCreateAndWrite，读不刷新）。
 * 已见过 → drop；未见过 → put + forward。无 eventId 的事件不过滤。
 * Flink 2.0 已删 Time 类，TTL 用 java.time.Duration。
 */
public class EventDedupFn extends KeyedProcessFunction<String, TradeEvent, TradeEvent> {

    private transient MapState<String, Boolean> seenEventIds;

    @Override
    public void open(org.apache.flink.api.common.functions.OpenContext openContext) {
        StateTtlConfig ttl = StateTtlConfig
                .newBuilder(Duration.ofMinutes(10))
                .setUpdateType(StateTtlConfig.UpdateType.OnCreateAndWrite)
                .build();
        MapStateDescriptor<String, Boolean> desc =
                new MapStateDescriptor<>("seenEventIds", String.class, Boolean.class);
        desc.enableTimeToLive(ttl);
        seenEventIds = getRuntimeContext().getMapState(desc);
    }

    @Override
    public void processElement(TradeEvent event, Context ctx, Collector<TradeEvent> out) throws Exception {
        if (event.eventId() == null || event.eventId().isEmpty()) {
            out.collect(event);
            return;
        }
        if (seenEventIds.contains(event.eventId())) return;
        seenEventIds.put(event.eventId(), Boolean.TRUE);
        out.collect(event);
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `$MVN -pl rule-rt-stream test -Dtest='EventDedupFnTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: Tests run: 2, Failures: 0

- [ ] **Step 5: Commit**

```bash
git add rule-rt-stream/src/main/java/com/sstlfsj/rule/stream/feature/EventDedupFn.java \
        rule-rt-stream/src/test/java/com/sstlfsj/rule/stream/feature/EventDedupFnTest.java
git commit -m "feat(stream-rt): EventDedupFn — eventId 去重(MapState+10min TTL)"
```

---

## Task 3: ThresholdConfig + Stage1GateFn（Side Output 风险筛选门）

**Files:**
- Create: `rule-rt-stream/src/main/java/com/sstlfsj/rule/stream/gate/ThresholdConfig.java`
- Create: `rule-rt-stream/src/main/java/com/sstlfsj/rule/stream/gate/Stage1GateFn.java`
- Test: `rule-rt-stream/src/test/java/com/sstlfsj/rule/stream/gate/Stage1GateFnTest.java`

- [ ] **Step 1: 写 ThresholdConfig**

```java
package com.sstlfsj.rule.stream.gate;

/** Stage-1 风险筛选门阈值（P3 常量，P4 改动态配置）。 */
public final class ThresholdConfig {
    private ThresholdConfig() {}

    /** susScore ≥ 0.5 触发 suspect event（落在 RtStateDeriver 的 RT_WATCH(0.3)~SHORT_ALPHA(0.6) 之间）。 */
    public static final double DEFAULT_SUS_SCORE_THRESHOLD = 0.5;
}
```

- [ ] **Step 2: 写失败测试**

```java
package com.sstlfsj.rule.stream.gate;

import com.sstlfsj.rule.stream.model.FeatureSnapshot;
import com.sstlfsj.rule.stream.model.SuspectEvent;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.streaming.api.operators.KeyedProcessOperator;
import org.apache.flink.streaming.util.KeyedOneInputStreamOperatorTestHarness;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class Stage1GateFnTest {

    private KeyedOneInputStreamOperatorTestHarness<String, FeatureSnapshot, FeatureSnapshot> harness() throws Exception {
        KeyedProcessOperator<String, FeatureSnapshot, FeatureSnapshot> op =
                new KeyedProcessOperator<>(new Stage1GateFn(0.5));
        var h = new KeyedOneInputStreamOperatorTestHarness<>(op, s -> s.customerId, Types.STRING);
        h.open();
        return h;
    }

    private FeatureSnapshot snap(double sus) {
        FeatureSnapshot s = new FeatureSnapshot("c1");
        s.susScore = sus;
        s.rtState = "RT_WATCH";
        s.updatedAt = 100L;
        return s;
    }

    @Test
    void belowThreshold_onlyMainOutput() throws Exception {
        var h = harness();
        h.processElement(snap(0.3), 1);
        assertThat(h.extractOutputValues()).hasSize(1);                       // 主输出有
        assertThat(h.getSideOutput(Stage1GateFn.SUSPECT_OUT)).isNull();       // 侧输出空
        h.close();
    }

    @Test
    void aboveThreshold_mainAndSideOutput() throws Exception {
        var h = harness();
        h.processElement(snap(0.7), 1);
        assertThat(h.extractOutputValues()).hasSize(1);                       // 主输出有
        var side = h.getSideOutput(Stage1GateFn.SUSPECT_OUT);
        assertThat(side).hasSize(1);
        SuspectEvent se = side.peek().getValue();
        assertThat(se.customerId).isEqualTo("c1");
        assertThat(se.susScore).isEqualTo(0.7);
        assertThat(se.suspectId).isEqualTo("c1-100");
        h.close();
    }
}
```

- [ ] **Step 3: 跑测试确认失败**

Run: `$MVN -pl rule-rt-stream test -Dtest='Stage1GateFnTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL（Stage1GateFn 不存在）

- [ ] **Step 4: 写 Stage1GateFn**

```java
package com.sstlfsj.rule.stream.gate;

import com.sstlfsj.rule.stream.model.FeatureSnapshot;
import com.sstlfsj.rule.stream.model.SuspectEvent;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.util.Collector;
import org.apache.flink.util.OutputTag;

import java.time.Instant;

/**
 * Stage-1 风险筛选门：主输出全量 FeatureSnapshot（→Redis），侧输出仅 susScore≥阈值的 SuspectEvent（→suspect topic）。
 * OutputTag 用匿名子类创建以保留泛型 TypeInformation。
 */
public class Stage1GateFn extends KeyedProcessFunction<String, FeatureSnapshot, FeatureSnapshot> {

    public static final OutputTag<SuspectEvent> SUSPECT_OUT = new OutputTag<SuspectEvent>("suspect-out") {};

    private final double threshold;

    public Stage1GateFn(double threshold) { this.threshold = threshold; }

    @Override
    public void processElement(FeatureSnapshot snap, Context ctx, Collector<FeatureSnapshot> out) {
        out.collect(snap);   // 主输出：全量 → Redis

        if (snap.susScore >= threshold) {
            SuspectEvent se = new SuspectEvent();
            se.customerId = snap.customerId;
            se.rtmMwr1s = snap.rtmMwr1s;
            se.rtmMwr10s = snap.rtmMwr10s;
            se.rtmMwr1m = snap.rtmMwr1m;
            se.rtmMwr5m = snap.rtmMwr5m;
            se.rtdAmountSum = snap.rtdAmountSum;
            se.fastTradeRatio = snap.fastTradeRatio;
            se.susScore = snap.susScore;
            se.rtState = snap.rtState;
            se.suspectId = snap.customerId + "-" + snap.updatedAt;
            se.occurredAt = Instant.ofEpochSecond(snap.updatedAt);
            ctx.output(SUSPECT_OUT, se);
        }
    }
}
```

- [ ] **Step 5: 跑测试确认通过**

Run: `$MVN -pl rule-rt-stream test -Dtest='Stage1GateFnTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: Tests run: 2, Failures: 0

- [ ] **Step 6: Commit**

```bash
git add rule-rt-stream/src/main/java/com/sstlfsj/rule/stream/gate/ \
        rule-rt-stream/src/test/java/com/sstlfsj/rule/stream/gate/Stage1GateFnTest.java
git commit -m "feat(stream-rt): Stage1GateFn — Side Output 风险筛选门 + ThresholdConfig"
```

---

## Task 4: SuspectEventSink（KafkaSink）

**Files:**
- Create: `rule-rt-stream/src/main/java/com/sstlfsj/rule/stream/sink/SuspectEventSink.java`

> 无独立单测——KafkaSink 序列化逻辑在 Task 7 的端到端验证里覆盖（真实 Kafka）。本 Task 仅编译验证。

- [ ] **Step 1: 写 SuspectEventSink**

```java
package com.sstlfsj.rule.stream.sink;

import com.sstlfsj.rule.stream.model.SuspectEvent;
import org.apache.flink.connector.kafka.sink.KafkaRecordSerializationSchema;
import org.apache.flink.connector.kafka.sink.KafkaSink;
import org.apache.kafka.clients.producer.ProducerRecord;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;

/** 将 SuspectEvent 序列化 JSON 发到 rt.suspect.customer，key=customerId。 */
public final class SuspectEventSink {
    private SuspectEventSink() {}

    public static KafkaSink<SuspectEvent> create(String brokers, String topic) {
        return KafkaSink.<SuspectEvent>builder()
                .setBootstrapServers(brokers)
                .setRecordSerializer(new SuspectEventSerializer(topic))
                .build();
    }

    private static final class SuspectEventSerializer implements KafkaRecordSerializationSchema<SuspectEvent> {
        private static final JsonMapper MAPPER = JsonMapper.builder().build();
        private final String topic;

        SuspectEventSerializer(String topic) { this.topic = topic; }

        @Override
        public ProducerRecord<byte[], byte[]> serialize(SuspectEvent e, KafkaSinkContext ctx, Long timestamp) {
            try {
                byte[] key = e.customerId.getBytes(StandardCharsets.UTF_8);
                byte[] val = MAPPER.writeValueAsBytes(e);
                return new ProducerRecord<>(topic, key, val);
            } catch (Exception ex) {
                throw new RuntimeException("Failed to serialize SuspectEvent", ex);
            }
        }
    }
}
```

- [ ] **Step 2: 编译**

Run: `$MVN -Plocal -pl rule-rt-stream compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add rule-rt-stream/src/main/java/com/sstlfsj/rule/stream/sink/SuspectEventSink.java
git commit -m "feat(stream-rt): SuspectEventSink — KafkaSink 发 rt.suspect.customer"
```

---

## Task 5: TradeStreamJob 改 — dedup 前置 + 三流重新 keyBy + Side Output

**Files:**
- Modify: `rule-rt-stream/src/main/java/com/sstlfsj/rule/stream/TradeStreamJob.java`

- [ ] **Step 1: 改 pipeline 与环境变量**

把 `TradeStreamJob.main` 里从 `DataStream<TradeEvent> trades = ...` 到 `env.execute(...)` 之间的内容替换为下面这段（source/watermark 构建与 `getEnv` 方法不变；新增 suspect topic 环境变量读取放在原有 `redisPort` 之后）。

先在 `int redisPort = ...;` 之后加：

```java
        String suspectTopic = getEnv("SUSPECT_TOPIC", "rt.suspect.customer");
        double threshold = Double.parseDouble(getEnv("SUSPECT_THRESHOLD", "0.5"));
```

再把 pipeline 段（第 49 行 `DataStream<TradeEvent> trades` 起至 `env.execute` 前）替换为：

```java
        DataStream<TradeEvent> trades = env.fromSource(source, watermark, "kafka-trades");

        // eventId 去重（keyBy 后、窗口前）；输出无 key，下游需重新 keyBy
        DataStream<TradeEvent> deduped = trades
                .keyBy(TradeEvent::customerId)
                .process(new EventDedupFn())
                .name("event-dedup");

        // RT-M：1s tumbling 每秒计数 → RollingCountFn 环形缓冲滚动求 6 个 size
        DataStream<PartialFeature> rtm = deduped
                .keyBy(TradeEvent::customerId)
                .window(TumblingEventTimeWindows.of(Duration.ofSeconds(1)))
                .aggregate(new PerSecondCountFn(), new SecondCountTagFn())
                .keyBy((SecondCount sc) -> sc.customerId)
                .process(new RollingCountFn());

        // RT-D：UTC 自然日累计金额，每笔交易即 emit 当前累计（日内实时）
        DataStream<PartialFeature> rtd = deduped
                .keyBy(TradeEvent::customerId)
                .process(new DailyAmountFn());

        // RT-B：5min/30s 滑动 API 通道占比
        DataStream<PartialFeature> rtb = deduped
                .keyBy(TradeEvent::customerId)
                .window(SlidingEventTimeWindows.of(Duration.ofMinutes(5), Duration.ofSeconds(30)))
                .process(new RtbProcessFn());

        // 合并三流 → 风险筛选门（Side Output）
        org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator<FeatureSnapshot> gated =
                rtm.union(rtd, rtb)
                        .keyBy((PartialFeature p) -> p.customerId)
                        .process(new FeatureSnapshotMerger())
                        .name("feature-snapshot-merger")
                        .process(new com.sstlfsj.rule.stream.gate.Stage1GateFn(threshold))
                        .name("stage-1-gate");

        // 主输出：全量特征 → Redis
        gated.sinkTo(new RedisFeatureSink(redisHost, redisPort)).name("redis-sink");

        // 侧输出：过门 suspect → Kafka
        gated.getSideOutput(com.sstlfsj.rule.stream.gate.Stage1GateFn.SUSPECT_OUT)
                .sinkTo(com.sstlfsj.rule.stream.sink.SuspectEventSink.create(brokers, suspectTopic))
                .name("suspect-sink");

        env.execute("rule-rt-stream feature pipeline");
```

- [ ] **Step 2: 编译**

Run: `$MVN -Plocal -pl rule-rt-stream compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: 全量 rule-rt-stream 测试兜底**

Run: `$MVN -pl rule-rt-stream clean test`
Expected: Tests run: 24+, Failures: 0（原 20 + EventDedupFn 2 + Stage1GateFn 2）

- [ ] **Step 4: Commit**

```bash
git add rule-rt-stream/src/main/java/com/sstlfsj/rule/stream/TradeStreamJob.java
git commit -m "feat(stream-rt): pipeline 接入 dedup + Side Output 风险筛选门 + suspect sink"
```

---

## Task 6: 根 pom 加 rule-rt-bridge 模块

**Files:**
- Modify: `pom.xml`

- [ ] **Step 1: `<modules>` 加 rule-rt-bridge**

在 `<module>rule-rt-stream</module>` 之后加：

```xml
        <module>rule-rt-bridge</module>
```

> spring-kafka 由 spring-boot-dependencies BOM(4.1.0) 管，无需在根 pom 加版本。

- [ ] **Step 2: 验证（rule-rt-bridge 目录尚不存在，validate 会报缺模块，属预期，下个 Task 建）**

Run: `$MVN validate`
Expected: 报 `rule-rt-bridge` 目录不存在（预期，Task 7 创建后消除）

- [ ] **Step 3: Commit**

```bash
git add pom.xml
git commit -m "build: 根 pom 加 rule-rt-bridge 模块"
```

---

## Task 7: rule-rt-bridge 模块 — pom + SuspectPayload + EvalClient + DecisionPublisher + Consumer + App + yml

**Files:**
- Create: `rule-rt-bridge/pom.xml`
- Create: `rule-rt-bridge/src/main/java/com/sstlfsj/rule/bridge/model/SuspectPayload.java`
- Create: `rule-rt-bridge/src/main/java/com/sstlfsj/rule/bridge/EvalClient.java`
- Create: `rule-rt-bridge/src/main/java/com/sstlfsj/rule/bridge/DecisionPublisher.java`
- Create: `rule-rt-bridge/src/main/java/com/sstlfsj/rule/bridge/SuspectConsumer.java`
- Create: `rule-rt-bridge/src/main/java/com/sstlfsj/rule/bridge/RtBridgeApp.java`
- Create: `rule-rt-bridge/src/main/resources/application.yml`
- Test: `rule-rt-bridge/src/test/java/com/sstlfsj/rule/bridge/EvalClientTest.java`

- [ ] **Step 1: 写 pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                             https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.sstlfsj.rule</groupId>
        <artifactId>rule-engine</artifactId>
        <version>${revision}</version>
    </parent>
    <artifactId>rule-rt-bridge</artifactId>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.kafka</groupId>
            <artifactId>spring-kafka</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.kafka</groupId>
            <artifactId>spring-kafka-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 2: 写 SuspectPayload（与 Flink 侧 SuspectEvent 字段对齐）**

```java
package com.sstlfsj.rule.bridge.model;

import java.time.Instant;

/** 消费 rt.suspect.customer 的反序列化体，字段与 rule-rt-stream 的 SuspectEvent 逐字段对齐（JSON 契约复制）。 */
public record SuspectPayload(
        String customerId,
        long rtmMwr1s,
        long rtmMwr10s,
        long rtmMwr1m,
        long rtmMwr5m,
        double rtdAmountSum,
        double fastTradeRatio,
        double susScore,
        String rtState,
        String suspectId,
        Instant occurredAt
) {}
```

- [ ] **Step 3: 写 EvalClient 的失败测试**

```java
package com.sstlfsj.rule.bridge;

import com.sstlfsj.rule.bridge.model.SuspectPayload;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class EvalClientTest {

    @Test
    void postsEvalRequestAndExtractsDecision() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://rule-api");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        // ApiResponse<EvalResult> envelope，decision 在 data.finalDecision.code
        server.expect(requestTo("http://rule-api/api/v1/rule/evaluate"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(jsonPath("$.tenantCode").value("t1"))
                .andExpect(jsonPath("$.subjectId").value("c1"))
                .andExpect(jsonPath("$.eventId").value("c1-100"))
                .andRespond(withSuccess(
                        "{\"success\":true,\"data\":{\"finalDecision\":{\"code\":\"HIGH\"}}}",
                        MediaType.APPLICATION_JSON));

        EvalClient client = new EvalClient(builder.build(), "t1", "trading.scene_b", "trade.suspect");
        SuspectPayload p = new SuspectPayload("c1", 9, 9, 9, 9, 500.0, 1.0, 0.7, "SHORT_ALPHA",
                "c1-100", Instant.parse("2026-06-20T07:00:00Z"));

        String decision = client.evaluate(p);
        assertThat(decision).isEqualTo("HIGH");
        server.verify();
    }
}
```

- [ ] **Step 4: 跑测试确认失败**

Run: `$MVN -pl rule-rt-bridge -am test -Dtest='EvalClientTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL（EvalClient 不存在）

- [ ] **Step 5: 写 EvalClient**

```java
package com.sstlfsj.rule.bridge;

import com.sstlfsj.rule.bridge.model.SuspectPayload;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.Map;

/** 调 rule-api /api/v1/rule/evaluate（EvalEventRequest），解 ApiResponse&lt;EvalResult&gt; 取 finalDecision.code。 */
public class EvalClient {

    private final RestClient restClient;
    private final String tenantCode;
    private final String sceneCode;
    private final String eventType;

    public EvalClient(RestClient restClient, String tenantCode, String sceneCode, String eventType) {
        this.restClient = restClient;
        this.tenantCode = tenantCode;
        this.sceneCode = sceneCode;
        this.eventType = eventType;
    }

    /** @param p suspect 事件 @return 决策码（finalDecision.code），无决策时返回 null。 */
    @SuppressWarnings("unchecked")
    public String evaluate(SuspectPayload p) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("susScore", p.susScore());
        payload.put("rtState", p.rtState());
        payload.put("rtmMwr1s", p.rtmMwr1s());
        payload.put("rtmMwr10s", p.rtmMwr10s());
        payload.put("rtmMwr1m", p.rtmMwr1m());
        payload.put("rtmMwr5m", p.rtmMwr5m());
        payload.put("rtdAmountSum", p.rtdAmountSum());
        payload.put("fastTradeRatio", p.fastTradeRatio());

        Map<String, Object> req = new HashMap<>();
        req.put("tenantCode", tenantCode);
        req.put("sceneCode", sceneCode);
        req.put("eventType", eventType);
        req.put("subjectId", p.customerId());
        req.put("eventId", p.suspectId());
        req.put("occurredAt", p.occurredAt() == null ? null : p.occurredAt().toString());
        req.put("payload", payload);

        Map<String, Object> resp = restClient.post()
                .uri("/api/v1/rule/evaluate")
                .body(req)
                .retrieve()
                .body(Map.class);

        if (resp == null) return null;
        Map<String, Object> data = (Map<String, Object>) resp.get("data");
        if (data == null) return null;
        Map<String, Object> finalDecision = (Map<String, Object>) data.get("finalDecision");
        return finalDecision == null ? null : (String) finalDecision.get("code");
    }
}
```

- [ ] **Step 6: 跑测试确认通过**

Run: `$MVN -pl rule-rt-bridge -am test -Dtest='EvalClientTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: Tests run: 1, Failures: 0

- [ ] **Step 7: 写 DecisionPublisher**

```java
package com.sstlfsj.rule.bridge;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/** 发决策码到 rt.decision，key=customerId。 */
@Component
public class DecisionPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final String decisionTopic;

    public DecisionPublisher(KafkaTemplate<String, String> kafkaTemplate,
                             @org.springframework.beans.factory.annotation.Value("${bridge.decision-topic:rt.decision}") String decisionTopic) {
        this.kafkaTemplate = kafkaTemplate;
        this.decisionTopic = decisionTopic;
    }

    /** @param customerId 决策主体（topic key） @param decisionJson 决策 JSON */
    public void publish(String customerId, String decisionJson) {
        kafkaTemplate.send(decisionTopic, customerId, decisionJson);
    }
}
```

- [ ] **Step 8: 写 SuspectConsumer**

```java
package com.sstlfsj.rule.bridge;

import com.sstlfsj.rule.bridge.model.SuspectPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * 消费 rt.suspect.customer → 调引擎评估 → 发 rt.decision。
 * 失败 try-catch 吞掉 + SLF4J 告警，正常返回让 offset 提交（失败即丢弃不重投，靠 Flink 覆盖写补偿）。
 */
@Component
public class SuspectConsumer {

    private static final Logger log = LoggerFactory.getLogger(SuspectConsumer.class);

    private final EvalClient evalClient;
    private final DecisionPublisher decisionPublisher;

    public SuspectConsumer(EvalClient evalClient, DecisionPublisher decisionPublisher) {
        this.evalClient = evalClient;
        this.decisionPublisher = decisionPublisher;
    }

    @KafkaListener(topics = "${bridge.suspect-topic:rt.suspect.customer}")
    public void onSuspect(SuspectPayload payload) {
        try {
            String decision = evalClient.evaluate(payload);
            if (decision != null) {
                decisionPublisher.publish(payload.customerId(), "{\"customerId\":\"" + payload.customerId()
                        + "\",\"decision\":\"" + decision + "\"}");
            }
        } catch (Exception e) {
            // 失败即丢弃不重投：offset 正常提交，靠 Flink 侧覆盖写下一轮该客户特征更新后重发 suspect
            log.error("Eval failed for customer={}, suspectId={}, dropped",
                    payload.customerId(), payload.suspectId(), e);
        }
    }
}
```

- [ ] **Step 9: 写 RtBridgeApp + EvalClient Bean 装配**

```java
package com.sstlfsj.rule.bridge;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestClient;

/** rt-bridge：消费 suspect → HTTP 调引擎 → 发 decision。 */
@SpringBootApplication
public class RtBridgeApp {

    public static void main(String[] args) {
        SpringApplication.run(RtBridgeApp.class, args);
    }

    /** EvalClient Bean：注入 rule-api 基址 + 评估事件维度配置。 */
    @Bean
    public EvalClient evalClient(
            @Value("${bridge.rule-api-base-url:http://localhost:8080}") String baseUrl,
            @Value("${bridge.tenant-code}") String tenantCode,
            @Value("${bridge.scene-code}") String sceneCode,
            @Value("${bridge.event-type:trade.suspect}") String eventType) {
        RestClient restClient = RestClient.builder().baseUrl(baseUrl).build();
        return new EvalClient(restClient, tenantCode, sceneCode, eventType);
    }
}
```

- [ ] **Step 10: 写 application.yml**

```yaml
spring:
  application:
    name: rule-rt-bridge
  kafka:
    bootstrap-servers: ${KAFKA_BROKERS:localhost:9092}
    consumer:
      group-id: rule-rt-bridge
      auto-offset-reset: earliest
      # SuspectPayload 用 JSON 反序列化
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.springframework.kafka.support.serializer.JsonDeserializer
      properties:
        spring.json.value.default.type: com.sstlfsj.rule.bridge.model.SuspectPayload
        spring.json.trusted.packages: "com.sstlfsj.rule.bridge.model"
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.apache.kafka.common.serialization.StringSerializer
    listener:
      concurrency: 3

bridge:
  rule-api-base-url: ${RULE_API_BASE_URL:http://localhost:8080}
  tenant-code: ${BRIDGE_TENANT_CODE:default}
  scene-code: ${BRIDGE_SCENE_CODE:trading.scene_b}
  event-type: trade.suspect
  suspect-topic: rt.suspect.customer
  decision-topic: rt.decision
```

- [ ] **Step 11: 全量编译 + 测试**

Run: `$MVN -pl rule-rt-bridge -am clean test`
Expected: Tests run: 1+, Failures: 0, BUILD SUCCESS

- [ ] **Step 12: Commit**

```bash
git add rule-rt-bridge/
git commit -m "feat(rt-bridge): 消费 suspect → HTTP 调引擎 → 发 rt.decision"
```

---

## Task 8: 全量 clean test 兜底

**Files:** 无（仅验证）

- [ ] **Step 1: 全量 clean test**

Run: `$MVN clean test`
Expected: 29 模块全绿（28 + rule-rt-bridge），BUILD SUCCESS

- [ ] **Step 2: 若有失败，定位修复（跨模块改动用 -am 兜底），重跑直到全绿**

---

## Self-Review

**Spec 覆盖（对照 spec 各节）：**
- §3 eventId 去重 + pipeline 重新 keyBy → Task 2 + Task 5 ✓
- §4 Stage-1 风险筛选门（Side Output + OutputTag 匿名子类 + getSideOutput）→ Task 3 + Task 5 ✓
- §4 SuspectEvent typed 字段非 Map → Task 1 ✓
- §5 SuspectEventSink KafkaSink → Task 4 ✓
- §6 bridge 契约（/api/v1/rule/evaluate + EvalEventRequest 必填 tenantCode + ApiResponse<EvalResult> 取 finalDecision）→ Task 7 EvalClient ✓
- §6 失败 try-catch 吞异常 + offset 提交 → Task 7 SuspectConsumer ✓
- §6 SuspectEvent↔SuspectPayload 字段对齐 → Task 1 + Task 7 Step 2 ✓
- §8 根 pom 加模块 → Task 6 ✓

**类型一致性：**
- `SuspectEvent`（Task 1）字段 == `SuspectPayload`（Task 7）record 分量：customerId/rtmMwr1s/10s/1m/5m/rtdAmountSum/fastTradeRatio/susScore/rtState/suspectId/occurredAt ✓
- `Stage1GateFn.SUSPECT_OUT`（Task 3）在 Task 5 pipeline 用 `getSideOutput` 一致 ✓
- `EvalClient(RestClient, tenantCode, sceneCode, eventType)`（Task 7 Step 5）与测试（Step 3）、Bean 装配（Step 9）构造签名一致 ✓
- `Stage1GateFn(double threshold)` 构造（Task 3）与 Task 5 `new Stage1GateFn(threshold)` 一致 ✓

**已知 P3 限制（计划内不实现）：**
- 显著性检验 / sample_n_7d（P4）
- 阈值动态配置（P4）
- 停止交易的 suspect 无补偿（spec §6 已记）
- rt.decision 下游消费者（P4）
- 端到端真实 Kafka+引擎 e2e（需起 rule-app + Flink + bridge 三方，留功能测试阶段，本 plan 以单测/harness 为验收线）
