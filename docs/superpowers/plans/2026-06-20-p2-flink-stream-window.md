# P2: Flink 流式计算窗口 Implementation Plan（rev2：微桶滚动）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新建 `rule-rt-stream` Maven 模块（非 Spring Boot），Flink 2.1.3 DataStream Job 消费 Kafka 交易流 → keyBy(customerId) → **1s 微桶 + 环形缓冲滚动**求 6 个 RT-M 速率 + RT-D 日累计 + RT-B 通道占比 → union → FeatureSnapshotMerger 合并 → RtStateDeriver + sus_score → Redis HSET sink（Flink Sink V2）。

**Architecture:** RT-M 6 个滚动速率不用 6 个原生 sliding window（大 size + 小 slide 会按 ⌈size/slide⌉ 复制事件，状态放大），改用「1 个 1s tumbling 窗口算每秒笔数 → `RollingCountFn`（KeyedProcessFunction + 最近 300 秒环形缓冲）每秒滚动求 6 个 size 的和」。触发/乱序/水印归原生 1s 窗口，环形缓冲只做纯求和（抽 `RollingWindowState` 可单测）。回落语义：每秒重算（含 0）→ merger 按字段覆盖写，高频后自然回落。RT-D/RT-B 各自原生窗口。三流产出统一 `PartialFeature`（含 `FeatureField` enum 标识字段），union 后 merger 覆盖合并、event-time timer 200ms 批量、`updated_at` 取 event-time。Sink 用 Flink Sink V2（`Sink`+`SinkWriter`）。打包 shade fat jar，本地 `-Plocal` 跑。

**Tech Stack:** Java 25、Flink 2.1.3（窗口 API 用 `java.time.Duration`，`Time` 类 Flink 2.0 已删/FLIP-335）、flink-connector-kafka 5.0.0-2.1、flink-clients 2.1.3、Jedis、Jackson3（Spring Boot 4 BOM 管，`java.time` 已内置 databind 无需 jsr310）、JUnit5 + AssertJ + flink-test-utils。前置：`mvn-env` skill 设 `$MVN`（JDK25）。

设计依据：`docs/superpowers/specs/2026-06-20-flink-stream-window-design.md`（rev2）。

---

## 文件结构

**根 pom：**
- Modify: `pom.xml` — `<modules>` 加 `rule-rt-stream`；加 Flink/flink-clients/Kafka connector/Jedis version properties + dependencyManagement（**不加 Jackson3，已由 Spring Boot 4 BOM 管**）

**rule-rt-stream（新建模块，14 个 Java 文件）：**
- Create: `pom.xml`（flink provided + shade + local profile；Jackson3/Jedis 只写坐标）
- model: `TradeEvent` / `SecondCount` / `FeatureField` / `PartialFeature` / `FeatureSnapshot`
- source: `TradeEventDeserializer`
- feature: `PerSecondCountFn` / `SecondCountTagFn` / `RollingWindowState` / `RollingCountFn` / `AmountSumAggregateFn` / `AmountTagFn` / `RtbProcessFn` / `FeatureSnapshotMerger` / `SusScoreFn`
- state: `RtStateDeriver`
- sink: `RedisFeatureSink`
- `TradeStreamJob`
- 测试文件（每 task 同步创建）

---

## Task 1: 根 pom 加 rule-rt-stream 模块 + Flink/flink-clients/Kafka/Jedis 版本锁

**Files:** Modify `pom.xml`

**Step A — `<modules>`** 加（在 `rule-benchmark` / `rule-samples` 后或合适位置）：

```xml
        <module>rule-rt-stream</module>
```

**Step B — `<properties>`** 末加（**不加 jackson3.version，Jackson3 由 spring-boot-starter-parent 4.x BOM 管**）：

```xml
        <!-- Flink 2.1.3 流计算 -->
        <flink.version>2.1.3</flink.version>
        <flink-kafka-connector.version>5.0.0-2.1</flink-kafka-connector.version>
        <jedis.version>5.2.0</jedis.version>
```

**Step C — `<dependencyManagement><dependencies>`** 加：

```xml
            <!-- Flink 流计算核心（rule-rt-stream provided） -->
            <dependency>
                <groupId>org.apache.flink</groupId>
                <artifactId>flink-streaming-java</artifactId>
                <version>${flink.version}</version>
            </dependency>
            <dependency>
                <groupId>org.apache.flink</groupId>
                <artifactId>flink-clients</artifactId>
                <version>${flink.version}</version>
            </dependency>
            <!-- Flink Kafka connector 5.0.0-2.1（connector 独立版本，非 Flink 版本） -->
            <dependency>
                <groupId>org.apache.flink</groupId>
                <artifactId>flink-connector-kafka</artifactId>
                <version>${flink-kafka-connector.version}</version>
            </dependency>
            <!-- flink-test-utils（rule-rt-stream test scope harness/MiniCluster） -->
            <dependency>
                <groupId>org.apache.flink</groupId>
                <artifactId>flink-test-utils</artifactId>
                <version>${flink.version}</version>
            </dependency>
            <!-- Jedis Redis client -->
            <dependency>
                <groupId>redis.clients</groupId>
                <artifactId>jedis</artifactId>
                <version>${jedis.version}</version>
            </dependency>
```

- [ ] **Step 1: Read root pom，定位 `<modules>`/`<properties>`/`<dependencyManagement>`**
- [ ] **Step 2: 编辑写入**
- [ ] **Step 3: 验证 `$MVN validate`**
- [ ] **Step 4: Commit**

```bash
git add pom.xml && git commit -m "build: 根 pom 加 rule-rt-stream 模块 + 锁 Flink 2.1.3/Kafka-connector 5.0.0-2.1/Jedis 5.2.0（Jackson3 由 Spring Boot 4 BOM 管）"
```

---

## Task 2: 新建 rule-rt-stream 模块 pom（provided flink + shade + local profile）

**Files:** Create `rule-rt-stream/pom.xml`

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
    <artifactId>rule-rt-stream</artifactId>

    <dependencies>
        <!-- Flink 集群自带，provided 不打进 fat jar；本地用 -Plocal 提 compile -->
        <dependency>
            <groupId>org.apache.flink</groupId>
            <artifactId>flink-streaming-java</artifactId>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>org.apache.flink</groupId>
            <artifactId>flink-clients</artifactId>
            <scope>provided</scope>
        </dependency>
        <!-- connector + jedis 打进 fat jar -->
        <dependency>
            <groupId>org.apache.flink</groupId>
            <artifactId>flink-connector-kafka</artifactId>
        </dependency>
        <dependency>
            <groupId>redis.clients</groupId>
            <artifactId>jedis</artifactId>
        </dependency>
        <!-- Jackson3：反序列化 Kafka JSON。版本由 Spring Boot 4 BOM 管，java.time 已内置 databind -->
        <dependency>
            <groupId>tools.jackson.core</groupId>
            <artifactId>jackson-databind</artifactId>
        </dependency>
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <scope>provided</scope>
        </dependency>
        <!-- test -->
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.assertj</groupId>
            <artifactId>assertj-core</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.apache.flink</groupId>
            <artifactId>flink-test-utils</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <!-- shade 出 fat jar（含 connector/jedis/jackson），提交 Flink 集群用 -->
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-shade-plugin</artifactId>
                <executions>
                    <execution>
                        <phase>package</phase>
                        <goals><goal>shade</goal></goals>
                        <configuration>
                            <transformers>
                                <!-- Flink connector 走 SPI，必须合并 META-INF/services -->
                                <transformer implementation="org.apache.maven.plugins.shade.resource.ServicesResourceTransformer"/>
                                <transformer implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
                                    <mainClass>com.sstlfsj.rule.stream.TradeStreamJob</mainClass>
                                </transformer>
                            </transformers>
                        </configuration>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>

    <profiles>
        <!-- 本地跑 job：把 provided 的 flink 依赖提为 compile，供 exec:java / IDE run -->
        <profile>
            <id>local</id>
            <dependencies>
                <dependency>
                    <groupId>org.apache.flink</groupId>
                    <artifactId>flink-streaming-java</artifactId>
                    <scope>compile</scope>
                </dependency>
                <dependency>
                    <groupId>org.apache.flink</groupId>
                    <artifactId>flink-clients</artifactId>
                    <scope>compile</scope>
                </dependency>
            </dependencies>
        </profile>
    </profiles>
</project>
```

- [ ] **Step 1: 创建包目录 `rule-rt-stream/src/main/java/com/sstlfsj/rule/stream/`（+ model/source/feature/state/sink 子包）+ `src/test/java/...` + 写 pom**
- [ ] **Step 2: 验证 `$MVN -pl rule-rt-stream compile`**
- [ ] **Step 3: Commit**

---

## Task 3: TradeEvent 模型 + Deserializer（Jackson3）

**Files:** `model/TradeEvent.java`、`source/TradeEventDeserializer.java`、`test/.../source/TradeEventDeserializerTest.java`

### TradeEvent.java

```java
package com.sstlfsj.rule.stream.model;

import java.math.BigDecimal;
import java.time.Instant;

/** Kafka 交易流反序列化 record。事件时间 = occurredAt（与引擎同源）。 */
public record TradeEvent(
        String customerId,
        String instrument,
        BigDecimal amount,
        String channel,       // APP / WEB / API
        Instant occurredAt,
        String eventId
) {}
```

### TradeEventDeserializer.java

> Jackson 3：`JsonMapper.builder().build()`，`java.time`（Instant）支持已内置 databind，无需注册 JavaTimeModule、无需 jsr310 依赖。

```java
package com.sstlfsj.rule.stream.source;

import com.sstlfsj.rule.stream.model.TradeEvent;
import org.apache.flink.api.common.serialization.DeserializationSchema;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** 从 Kafka value(JSON bytes) 反序列化为 TradeEvent。 */
public class TradeEventDeserializer implements DeserializationSchema<TradeEvent> {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    @Override
    public TradeEvent deserialize(byte[] message) {
        return MAPPER.readValue(message, TradeEvent.class);
    }

    @Override
    public boolean isEndOfStream(TradeEvent nextElement) { return false; }

    @Override
    public TypeInformation<TradeEvent> getProducedType() {
        return TypeInformation.of(TradeEvent.class);
    }
}
```

### TradeEventDeserializerTest.java

```java
package com.sstlfsj.rule.stream.source;

import com.sstlfsj.rule.stream.model.TradeEvent;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.assertThat;

class TradeEventDeserializerTest {

    private final TradeEventDeserializer d = new TradeEventDeserializer();

    @Test
    void roundTrip() {
        String json = "{\"customerId\":\"cust-1\",\"instrument\":\"BTC\",\"amount\":150.00,"
                + "\"channel\":\"API\",\"occurredAt\":\"2026-06-20T10:00:00Z\",\"eventId\":\"evt-1\"}";
        TradeEvent e = d.deserialize(json.getBytes(StandardCharsets.UTF_8));
        assertThat(e.customerId()).isEqualTo("cust-1");
        assertThat(e.amount()).isEqualByComparingTo(new BigDecimal("150.00"));
        assertThat(e.channel()).isEqualTo("API");
        assertThat(e.eventId()).isEqualTo("evt-1");
    }
}
```

- [ ] **Step 1: 写 3 个文件**
- [ ] **Step 2: 跑 `$MVN -pl rule-rt-stream test -Dtest='TradeEventDeserializerTest' -Dsurefire.failIfNoSpecifiedTests=false`**
- [ ] **Step 3: Commit**

---

## Task 4: model — SecondCount / FeatureField / PartialFeature / FeatureSnapshot

**Files:** `model/SecondCount.java`、`model/FeatureField.java`、`model/PartialFeature.java`、`model/FeatureSnapshot.java`

> 这些类在 Flink 算子间传输，用 POJO（public 字段 + 无参构造），Flink `PojoSerializer` 直接支持；`FeatureField` enum Flink 原生支持。

```java
package com.sstlfsj.rule.stream.model;

/** 1s 桶计数：customerId + 该秒笔数 + epoch second。喂 RollingCountFn。 */
public class SecondCount {
    public String customerId;
    public long count;
    public long epochSecond;
    public SecondCount() {}
    public SecondCount(String customerId, long count, long epochSecond) {
        this.customerId = customerId; this.count = count; this.epochSecond = epochSecond;
    }
}
```

```java
package com.sstlfsj.rule.stream.model;

/** 部分特征字段判别（封闭取值，union 后 merger 按它覆盖对应字段）。 */
public enum FeatureField {
    RTM_1S, RTM_10S, RTM_30S, RTM_1M, RTM_2M, RTM_5M, RTD_AMOUNT, FAST_TRADE_RATIO
}
```

```java
package com.sstlfsj.rule.stream.model;

/** 单字段部分特征——三条窗口流 union 的统一元素。eventTime 供 merger 注册 timer / 算 updated_at。 */
public class PartialFeature {
    public String customerId;
    public FeatureField field;
    public double value;
    public long eventTime;   // 该特征对应的 event-time（毫秒）
    public PartialFeature() {}
    public PartialFeature(String customerId, FeatureField field, double value, long eventTime) {
        this.customerId = customerId; this.field = field; this.value = value; this.eventTime = eventTime;
    }
}
```

```java
package com.sstlfsj.rule.stream.model;

/** 合并后的完整 RT 特征快照。FeatureSnapshotMerger 算出派生字段后写 Redis。 */
public class FeatureSnapshot {
    public String customerId;
    // RT-M 6 窗口滚动计数
    public long rtmMwr1s, rtmMwr10s, rtmMwr30s, rtmMwr1m, rtmMwr2m, rtmMwr5m;
    // RT-D
    public double rtdAmountSum;
    // RT-B
    public double fastTradeRatio;
    // 派生
    public double susScore;
    public String rtState;
    // event-time epoch second（引擎侧新鲜度校验用）
    public long updatedAt;

    public FeatureSnapshot() {}
    public FeatureSnapshot(String customerId) { this.customerId = customerId; }
}
```

- [ ] **Step 1: 写 4 个文件**
- [ ] **Step 2: `$MVN -pl rule-rt-stream compile`**
- [ ] **Step 3: Commit**

---

## Task 5: RT-M 微桶滚动（PerSecondCountFn + SecondCountTagFn + RollingWindowState + RollingCountFn）

**Files:** `feature/PerSecondCountFn.java`、`feature/SecondCountTagFn.java`、`feature/RollingWindowState.java`、`feature/RollingCountFn.java`、`test/.../feature/RollingWindowStateTest.java`

### PerSecondCountFn.java（1s tumbling 增量计数）

```java
package com.sstlfsj.rule.stream.feature;

import com.sstlfsj.rule.stream.model.TradeEvent;
import org.apache.flink.api.common.functions.AggregateFunction;

/** 1s 窗口增量计数——仅存一个 long 累加器，不缓冲事件流。 */
public class PerSecondCountFn implements AggregateFunction<TradeEvent, Long, Long> {
    @Override public Long createAccumulator() { return 0L; }
    @Override public Long add(TradeEvent event, Long acc) { return acc + 1; }
    @Override public Long getResult(Long acc) { return acc; }
    @Override public Long merge(Long a, Long b) { return a + b; }
}
```

### SecondCountTagFn.java（补 customerId + 窗口结束秒）

> `aggregate(AggregateFunction, ProcessWindowFunction)` 组合：增量聚合 + 窗口触发时拿 key/window 元信息。Iterable 是单元素（聚合结果）。

```java
package com.sstlfsj.rule.stream.feature;

import com.sstlfsj.rule.stream.model.SecondCount;
import org.apache.flink.streaming.api.functions.windowing.ProcessWindowFunction;
import org.apache.flink.streaming.api.windowing.windows.TimeWindow;
import org.apache.flink.util.Collector;

/** 给每秒计数补 customerId + 窗口结束秒（epoch second）。 */
public class SecondCountTagFn extends ProcessWindowFunction<Long, SecondCount, String, TimeWindow> {
    @Override
    public void process(String customerId, Context ctx, Iterable<Long> counts, Collector<SecondCount> out) {
        long count = counts.iterator().next();
        long epochSecond = ctx.window().getEnd() / 1000;   // 窗口结束时刻对应秒
        out.collect(new SecondCount(customerId, count, epochSecond));
    }
}
```

### RollingWindowState.java（纯函数：6 个 size 滚动和）

```java
package com.sstlfsj.rule.stream.feature;

import java.util.Map;

/** 按秒计数 → 6 个 RT-M size 的滚动和（无 Flink 依赖，可单测）。 */
public final class RollingWindowState {
    private RollingWindowState() {}

    /** RT-M 6 窗口 size（秒），顺序对齐 FeatureField.RTM_1S..RTM_5M。 */
    public static final int[] SIZES_SECONDS = {1, 10, 30, 60, 120, 300};

    /**
     * 给定每秒计数与当前秒，算 6 个 size 的滚动和。
     * size 窗口 = (currentSecond-size, currentSecond] 即最近 size 个秒槽（age 0..size-1）。
     */
    public static long[] rollingSums(Map<Long, Long> secondCounts, long currentSecond) {
        long[] sums = new long[SIZES_SECONDS.length];
        for (Map.Entry<Long, Long> e : secondCounts.entrySet()) {
            long age = currentSecond - e.getKey();
            if (age < 0) continue;                 // 未来秒（乱序）忽略
            for (int i = 0; i < SIZES_SECONDS.length; i++) {
                if (age < SIZES_SECONDS[i]) sums[i] += e.getValue();
            }
        }
        return sums;
    }
}
```

### RollingCountFn.java（环形缓冲 KeyedProcessFunction）

```java
package com.sstlfsj.rule.stream.feature;

import com.sstlfsj.rule.stream.model.FeatureField;
import com.sstlfsj.rule.stream.model.PartialFeature;
import com.sstlfsj.rule.stream.model.SecondCount;
import org.apache.flink.api.common.functions.OpenContext;
import org.apache.flink.api.common.state.MapState;
import org.apache.flink.api.common.state.MapStateDescriptor;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.util.Collector;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * 维护每客户最近 300 秒计数（MapState<second,count>），每收到一秒计数即滚动重算 6 个 RT-M 值。
 * 每秒重算（含 0）保证高频后自然回落；迟到秒由 1s 窗口 allowedLateness 再触发、put 覆盖后重算。
 */
public class RollingCountFn extends KeyedProcessFunction<String, SecondCount, PartialFeature> {

    private static final int MAX_WINDOW = 300;   // 最大 size（5m）
    private static final FeatureField[] FIELDS = {
            FeatureField.RTM_1S, FeatureField.RTM_10S, FeatureField.RTM_30S,
            FeatureField.RTM_1M, FeatureField.RTM_2M, FeatureField.RTM_5M
    };

    private transient MapState<Long, Long> secondCounts;

    @Override
    public void open(OpenContext ctx) {
        secondCounts = getRuntimeContext().getMapState(
                new MapStateDescriptor<>("secondCounts", Long.class, Long.class));
    }

    @Override
    public void processElement(SecondCount sc, Context ctx, Collector<PartialFeature> out) throws Exception {
        secondCounts.put(sc.epochSecond, sc.count);          // 覆盖（迟到秒更新）

        // 清理滑出最大窗口的旧秒
        Iterator<Long> it = secondCounts.keys().iterator();
        while (it.hasNext()) {
            if (sc.epochSecond - it.next() >= MAX_WINDOW) it.remove();
        }

        Map<Long, Long> snapshot = new HashMap<>();
        for (Map.Entry<Long, Long> e : secondCounts.entries()) snapshot.put(e.getKey(), e.getValue());

        long[] sums = RollingWindowState.rollingSums(snapshot, sc.epochSecond);
        long eventTime = sc.epochSecond * 1000;
        for (int i = 0; i < FIELDS.length; i++) {
            out.collect(new PartialFeature(ctx.getCurrentKey(), FIELDS[i], sums[i], eventTime));
        }
    }
}
```

### RollingWindowStateTest.java（核心纯函数测）

```java
package com.sstlfsj.rule.stream.feature;

import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class RollingWindowStateTest {

    @Test
    void sumsAcrossSizes() {
        Map<Long, Long> counts = new HashMap<>();
        counts.put(100L, 3L);   // 当前秒
        counts.put(95L, 2L);    // 5 秒前 → 在 10s/30s/.. 内，不在 1s 内
        counts.put(80L, 5L);    // 20 秒前 → 在 30s/1m/2m/5m 内
        long[] s = RollingWindowState.rollingSums(counts, 100L);
        // 顺序 1s/10s/30s/1m/2m/5m
        assertThat(s[0]).isEqualTo(3);          // 1s：仅当前秒
        assertThat(s[1]).isEqualTo(5);          // 10s：100+95
        assertThat(s[2]).isEqualTo(10);         // 30s：100+95+80
        assertThat(s[5]).isEqualTo(10);         // 5m：全部
    }

    @Test
    void falloffWhenQuiet() {
        // 一波在秒 100，当前推进到秒 102（无新计数），1s 窗口应回落到 0
        Map<Long, Long> counts = new HashMap<>();
        counts.put(100L, 9L);
        long[] s = RollingWindowState.rollingSums(counts, 102L);
        assertThat(s[0]).isEqualTo(0);          // 1s：秒 102 无计数 → 回落
        assertThat(s[1]).isEqualTo(9);          // 10s：仍含秒 100
    }

    @Test
    void ignoresFutureSeconds() {
        Map<Long, Long> counts = new HashMap<>();
        counts.put(105L, 7L);                   // 比 current 晚（乱序）
        assertThat(RollingWindowState.rollingSums(counts, 100L)[5]).isEqualTo(0);
    }
}
```

- [ ] **Step 1: 写 5 个文件**
- [ ] **Step 2: 跑 `RollingWindowStateTest`（纯函数，无需 mini cluster）+ `$MVN -pl rule-rt-stream compile`**
- [ ] **Step 3: Commit**

---

## Task 6: RT-D + RT-B（AmountSumAggregateFn + AmountTagFn + RtbProcessFn）

**Files:** `feature/AmountSumAggregateFn.java`、`feature/AmountTagFn.java`、`feature/RtbProcessFn.java`

```java
package com.sstlfsj.rule.stream.feature;

import com.sstlfsj.rule.stream.model.TradeEvent;
import org.apache.flink.api.common.functions.AggregateFunction;

/** RT-D 日累计交易额增量聚合。 */
public class AmountSumAggregateFn implements AggregateFunction<TradeEvent, Double, Double> {
    @Override public Double createAccumulator() { return 0.0; }
    @Override public Double add(TradeEvent event, Double acc) { return acc + event.amount().doubleValue(); }
    @Override public Double getResult(Double acc) { return acc; }
    @Override public Double merge(Double a, Double b) { return a + b; }
}
```

```java
package com.sstlfsj.rule.stream.feature;

import com.sstlfsj.rule.stream.model.FeatureField;
import com.sstlfsj.rule.stream.model.PartialFeature;
import org.apache.flink.streaming.api.functions.windowing.ProcessWindowFunction;
import org.apache.flink.streaming.api.windowing.windows.TimeWindow;
import org.apache.flink.util.Collector;

/** 给 RT-D 日累计补 customerId + 窗口结束时间 → PartialFeature(RTD_AMOUNT)。 */
public class AmountTagFn extends ProcessWindowFunction<Double, PartialFeature, String, TimeWindow> {
    @Override
    public void process(String customerId, Context ctx, Iterable<Double> sums, Collector<PartialFeature> out) {
        double sum = sums.iterator().next();
        out.collect(new PartialFeature(customerId, FeatureField.RTD_AMOUNT, sum, ctx.window().getEnd()));
    }
}
```

```java
package com.sstlfsj.rule.stream.feature;

import com.sstlfsj.rule.stream.model.FeatureField;
import com.sstlfsj.rule.stream.model.PartialFeature;
import com.sstlfsj.rule.stream.model.TradeEvent;
import org.apache.flink.streaming.api.functions.windowing.ProcessWindowFunction;
import org.apache.flink.streaming.api.windowing.windows.TimeWindow;
import org.apache.flink.util.Collector;

/** RT-B：5min 滑动窗口内 API 通道占比。需遍历窗口事件，用 ProcessWindowFunction。 */
public class RtbProcessFn extends ProcessWindowFunction<TradeEvent, PartialFeature, String, TimeWindow> {
    @Override
    public void process(String customerId, Context ctx, Iterable<TradeEvent> events, Collector<PartialFeature> out) {
        int total = 0, api = 0;
        for (TradeEvent e : events) { total++; if ("API".equals(e.channel())) api++; }
        double ratio = total > 0 ? (double) api / total : 0;
        out.collect(new PartialFeature(customerId, FeatureField.FAST_TRADE_RATIO, ratio, ctx.window().getEnd()));
    }
}
```

- [ ] **Step 1: 写 3 个文件**
- [ ] **Step 2: `$MVN -pl rule-rt-stream compile`**
- [ ] **Step 3: Commit**

---

## Task 7: SusScoreFn + RtStateDeriver（纯函数）

**Files:** `feature/SusScoreFn.java`、`state/RtStateDeriver.java`、对应两个测试

```java
package com.sstlfsj.rule.stream.feature;

/** sus_score 加权计算（静态，零依赖）。 */
public final class SusScoreFn {
    private SusScoreFn() {}

    public static double clamp(double v) { return Math.max(0, Math.min(1, v)); }

    public static double compute(double rtm1s, double fastTradeRatio, double rtm1m) {
        return clamp((rtm1s / 10.0) * 0.3 + (fastTradeRatio / 0.8) * 0.4 + (rtm1m / 30.0) * 0.3);
    }
}
```

```java
package com.sstlfsj.rule.stream.state;

import com.sstlfsj.rule.stream.model.FeatureSnapshot;

/** 特征快照 → RT 状态（静态，零依赖）。 */
public final class RtStateDeriver {
    private RtStateDeriver() {}

    public static final String RT_CLEAN = "RT_CLEAN";
    public static final String RT_WATCH = "RT_WATCH";
    public static final String SHORT_ALPHA = "SHORT_ALPHA";
    public static final String LATENCY_ARB = "LATENCY_ARB";

    /** 按优先级判状态，命中即返回。 */
    public static String derive(FeatureSnapshot s) {
        if (s.fastTradeRatio > 0.8 && s.rtmMwr1s > 8) return LATENCY_ARB;
        if (s.susScore >= 0.6 && s.rtmMwr1s > 10) return SHORT_ALPHA;
        if (s.susScore >= 0.3 || s.fastTradeRatio > 0.3) return RT_WATCH;
        return RT_CLEAN;
    }
}
```

### SusScoreFnTest.java

```java
package com.sstlfsj.rule.stream.feature;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class SusScoreFnTest {
    @Test void zeroAll() { assertThat(SusScoreFn.compute(0, 0, 0)).isEqualTo(0.0); }
    @Test void maxAll() { assertThat(SusScoreFn.compute(100, 1, 100)).isEqualTo(1.0); }
    @Test void typical() { assertThat(SusScoreFn.compute(8, 0.45, 15)).isGreaterThan(0.3).isLessThan(0.7); }
}
```

### RtStateDeriverTest.java

```java
package com.sstlfsj.rule.stream.state;

import com.sstlfsj.rule.stream.model.FeatureSnapshot;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class RtStateDeriverTest {

    private FeatureSnapshot snap(long rtm1s, double fastRatio, double sus) {
        FeatureSnapshot s = new FeatureSnapshot("test");
        s.rtmMwr1s = rtm1s; s.fastTradeRatio = fastRatio; s.susScore = sus;
        return s;
    }

    @Test void latencyArb() { assertThat(RtStateDeriver.derive(snap(9, 0.9, 0.8))).isEqualTo(RtStateDeriver.LATENCY_ARB); }
    @Test void shortAlpha() { assertThat(RtStateDeriver.derive(snap(12, 0.5, 0.7))).isEqualTo(RtStateDeriver.SHORT_ALPHA); }
    @Test void watchBySus() { assertThat(RtStateDeriver.derive(snap(3, 0.2, 0.35))).isEqualTo(RtStateDeriver.RT_WATCH); }
    @Test void watchByRatio() { assertThat(RtStateDeriver.derive(snap(3, 0.4, 0.1))).isEqualTo(RtStateDeriver.RT_WATCH); }
    @Test void clean() { assertThat(RtStateDeriver.derive(snap(2, 0.1, 0.1))).isEqualTo(RtStateDeriver.RT_CLEAN); }
}
```

- [ ] **Step 1: 写 4 个文件 → Step 2: 跑测试 → Step 3: Commit**

---

## Task 8: FeatureSnapshotMerger + harness 测

**Files:** `feature/FeatureSnapshotMerger.java`、`test/.../feature/FeatureSnapshotMergerTest.java`

### FeatureSnapshotMerger.java

```java
package com.sstlfsj.rule.stream.feature;

import com.sstlfsj.rule.stream.model.FeatureSnapshot;
import com.sstlfsj.rule.stream.model.PartialFeature;
import com.sstlfsj.rule.stream.state.RtStateDeriver;
import org.apache.flink.api.common.functions.OpenContext;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.util.Collector;

/**
 * 合并 RT-M/RT-D/RT-B 三条 PartialFeature 流。维护 ValueState<FeatureSnapshot>，按 field 覆盖对应字段（含 0 → 回落）。
 * event-time timer（eventTime+200ms）批量合并，避免同客户多字段短时内触发重复写 Redis。
 * updated_at 取 timer 的 event-time（非墙钟），保证回放可复现、新鲜度准确。
 */
public class FeatureSnapshotMerger extends KeyedProcessFunction<String, PartialFeature, FeatureSnapshot> {

    private transient ValueState<FeatureSnapshot> snapshot;
    private transient ValueState<Long> pendingTimer;

    @Override
    public void open(OpenContext ctx) {
        snapshot = getRuntimeContext().getState(new ValueStateDescriptor<>("snapshot", FeatureSnapshot.class));
        pendingTimer = getRuntimeContext().getState(new ValueStateDescriptor<>("pendingTimer", Long.class));
    }

    @Override
    public void processElement(PartialFeature p, Context ctx, Collector<FeatureSnapshot> out) throws Exception {
        FeatureSnapshot cur = snapshot.value();
        if (cur == null) cur = new FeatureSnapshot(ctx.getCurrentKey());

        switch (p.field) {                         // 覆盖写（含 0 → 自然回落）
            case RTM_1S -> cur.rtmMwr1s = (long) p.value;
            case RTM_10S -> cur.rtmMwr10s = (long) p.value;
            case RTM_30S -> cur.rtmMwr30s = (long) p.value;
            case RTM_1M -> cur.rtmMwr1m = (long) p.value;
            case RTM_2M -> cur.rtmMwr2m = (long) p.value;
            case RTM_5M -> cur.rtmMwr5m = (long) p.value;
            case RTD_AMOUNT -> cur.rtdAmountSum = p.value;
            case FAST_TRADE_RATIO -> cur.fastTradeRatio = p.value;
        }
        snapshot.update(cur);

        long fireTime = p.eventTime + 200;
        Long existing = pendingTimer.value();
        if (existing != null) ctx.timerService().deleteEventTimeTimer(existing);
        ctx.timerService().registerEventTimeTimer(fireTime);
        pendingTimer.update(fireTime);
    }

    @Override
    public void onTimer(long timestamp, OnTimerContext ctx, Collector<FeatureSnapshot> out) throws Exception {
        FeatureSnapshot cur = snapshot.value();
        if (cur == null) return;
        cur.susScore = SusScoreFn.compute(cur.rtmMwr1s, cur.fastTradeRatio, cur.rtmMwr1m);
        cur.rtState = RtStateDeriver.derive(cur);
        cur.updatedAt = timestamp / 1000;          // event-time（timer 触发时刻），非墙钟
        out.collect(cur);
        pendingTimer.clear();
    }
}
```

### FeatureSnapshotMergerTest.java（harness：覆盖合并 + 回落 + timer 发射）

```java
package com.sstlfsj.rule.stream.feature;

import com.sstlfsj.rule.stream.model.FeatureField;
import com.sstlfsj.rule.stream.model.FeatureSnapshot;
import com.sstlfsj.rule.stream.model.PartialFeature;
import org.apache.flink.streaming.api.operators.KeyedProcessOperator;
import org.apache.flink.streaming.util.KeyedOneInputStreamOperatorTestHarness;
import org.apache.flink.api.common.typeinfo.Types;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FeatureSnapshotMergerTest {

    private KeyedOneInputStreamOperatorTestHarness<String, PartialFeature, FeatureSnapshot> harness() throws Exception {
        KeyedProcessOperator<String, PartialFeature, FeatureSnapshot> op =
                new KeyedProcessOperator<>(new FeatureSnapshotMerger());
        var h = new KeyedOneInputStreamOperatorTestHarness<>(op, p -> p.customerId, Types.STRING);
        h.open();
        return h;
    }

    @Test
    void mergesFieldsAndFiresOnTimer() throws Exception {
        var h = harness();
        // 同客户先后到 1s 计数 9、API 占比 0.9，eventTime 同为 1000ms
        h.processElement(new PartialFeature("c1", FeatureField.RTM_1S, 9, 1000), 1000);
        h.processElement(new PartialFeature("c1", FeatureField.FAST_TRADE_RATIO, 0.9, 1000), 1000);
        h.processWatermark(2000);   // 推进过 1000+200，触发 timer

        var out = h.extractOutputValues();
        assertThat(out).isNotEmpty();
        FeatureSnapshot last = out.get(out.size() - 1);
        assertThat(last.rtmMwr1s).isEqualTo(9);
        assertThat(last.fastTradeRatio).isEqualTo(0.9);
        assertThat(last.rtState).isEqualTo("LATENCY_ARB");
        assertThat(last.updatedAt).isEqualTo(1L);   // (1000+200)/1000 → event-time 秒
        h.close();
    }

    @Test
    void zeroOverwritesFallsBack() throws Exception {
        var h = harness();
        h.processElement(new PartialFeature("c1", FeatureField.RTM_1S, 9, 1000), 1000);
        h.processWatermark(1300);
        h.processElement(new PartialFeature("c1", FeatureField.RTM_1S, 0, 2000), 2000);  // 回落
        h.processWatermark(2300);

        var out = h.extractOutputValues();
        assertThat(out.get(out.size() - 1).rtmMwr1s).isEqualTo(0);   // 覆盖写，回落到 0
        h.close();
    }
}
```

> harness 包/签名以 Flink 2.1 `flink-test-utils` 实际为准（`KeyedOneInputStreamOperatorTestHarness` 构造或 `processWatermark` 签名若有差异按编译提示调整）。

- [ ] **Step 1: 写 2 个文件 → Step 2: 跑 `FeatureSnapshotMergerTest` → Step 3: Commit**

---

## Task 9: RedisFeatureSink（Flink Sink V2）

**Files:** `sink/RedisFeatureSink.java`、`test/.../sink/RedisFeatureSinkTest.java`

```java
package com.sstlfsj.rule.stream.sink;

import com.sstlfsj.rule.stream.model.FeatureSnapshot;
import org.apache.flink.api.connector.sink2.Sink;
import org.apache.flink.api.connector.sink2.SinkWriter;
import org.apache.flink.api.connector.sink2.WriterInitContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.Pipeline;

/** Flink Sink V2：HSET rt:feat:{customerId} 全字段 + EXPIRE。 */
public class RedisFeatureSink implements Sink<FeatureSnapshot> {

    private static final int TTL_SECONDS = 604875;   // 7d+1h
    private final String host;
    private final int port;

    public RedisFeatureSink(String host, int port) { this.host = host; this.port = port; }

    @Override
    public SinkWriter<FeatureSnapshot> createWriter(WriterInitContext context) {
        return new RedisWriter(host, port);
    }

    /** 运行时创建（不参与序列化），管 JedisPool 生命周期。 */
    static final class RedisWriter implements SinkWriter<FeatureSnapshot> {
        private static final Logger log = LoggerFactory.getLogger(RedisWriter.class);
        private final JedisPool pool;

        RedisWriter(String host, int port) {
            JedisPoolConfig cfg = new JedisPoolConfig();
            cfg.setMaxTotal(8);
            this.pool = new JedisPool(cfg, host, port);
        }

        @Override
        public void write(FeatureSnapshot snap, Context context) {
            try (Jedis jedis = pool.getResource()) {
                String key = "rt:feat:" + snap.customerId;
                Pipeline pipe = jedis.pipelined();
                pipe.hset(key, "rtm_mwr_1s", String.valueOf(snap.rtmMwr1s));
                pipe.hset(key, "rtm_mwr_10s", String.valueOf(snap.rtmMwr10s));
                pipe.hset(key, "rtm_mwr_30s", String.valueOf(snap.rtmMwr30s));
                pipe.hset(key, "rtm_mwr_1m", String.valueOf(snap.rtmMwr1m));
                pipe.hset(key, "rtm_mwr_2m", String.valueOf(snap.rtmMwr2m));
                pipe.hset(key, "rtm_mwr_5m", String.valueOf(snap.rtmMwr5m));
                pipe.hset(key, "rtd_amount_sum", String.valueOf(snap.rtdAmountSum));
                pipe.hset(key, "fast_trade_ratio", String.valueOf(snap.fastTradeRatio));
                pipe.hset(key, "sus_score", String.valueOf(snap.susScore));
                pipe.hset(key, "rt_state", snap.rtState != null ? snap.rtState : "RT_CLEAN");
                pipe.hset(key, "updated_at", String.valueOf(snap.updatedAt));
                pipe.expire(key, TTL_SECONDS);
                pipe.sync();
            } catch (Exception e) {
                // Redis 短暂不可用不让 job 崩溃——checkpoint 重放 + 覆盖写可恢复
                log.error("Redis HSET failed for customer={}, recover on checkpoint replay", snap.customerId, e);
            }
        }

        @Override public void flush(boolean endOfInput) {}
        @Override public void close() { pool.close(); }
    }
}
```

### RedisFeatureSinkTest.java

```java
package com.sstlfsj.rule.stream.sink;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThatCode;

class RedisFeatureSinkTest {
    @Test
    void construction_doesNotThrow() {
        assertThatCode(() -> new RedisFeatureSink("localhost", 6379)).doesNotThrowAnyException();
    }
}
```

> 真落库验证在 Task 12 e2e（docker redis + HGETALL）。

- [ ] **Step 1: 写 2 个文件**
- [ ] **Step 2: `$MVN -pl rule-rt-stream test -Dtest='RedisFeatureSinkTest' -Dsurefire.failIfNoSpecifiedTests=false`**
- [ ] **Step 3: Commit**

> 注：`sink2.WriterInitContext` 为 Flink 2.x SinkV2 签名；若该版本 `createWriter` 仍是 `InitContext`，按编译提示改导入即可（语义不变）。

---

## Task 10: TradeStreamJob — 微桶 + RT-D + RT-B pipeline

**Files:** `TradeStreamJob.java`

```java
package com.sstlfsj.rule.stream;

import com.sstlfsj.rule.stream.feature.*;
import com.sstlfsj.rule.stream.model.PartialFeature;
import com.sstlfsj.rule.stream.model.SecondCount;
import com.sstlfsj.rule.stream.model.TradeEvent;
import com.sstlfsj.rule.stream.sink.RedisFeatureSink;
import com.sstlfsj.rule.stream.source.TradeEventDeserializer;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.KeyedStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.windowing.assigners.SlidingEventTimeWindows;
import org.apache.flink.streaming.api.windowing.assigners.TumblingEventTimeWindows;

import java.time.Duration;

/** Flink 流式风控特征 pipeline——1s 微桶滚动 RT-M + RT-D + RT-B → union → merger → Redis。 */
public class TradeStreamJob {

    public static void main(String[] args) throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.enableCheckpointing(60_000);   // at-least-once；覆盖写下重复无副作用

        String brokers = getEnv("KAFKA_BROKERS", "localhost:9092");
        String topic = getEnv("KAFKA_TOPIC", "rt.trade.raw");
        String offsetMode = getEnv("KAFKA_OFFSET", "latest");   // e2e 用 earliest
        String redisHost = getEnv("REDIS_HOST", "localhost");
        int redisPort = Integer.parseInt(getEnv("REDIS_PORT", "6379"));

        KafkaSource<TradeEvent> source = KafkaSource.<TradeEvent>builder()
                .setBootstrapServers(brokers)
                .setTopics(topic)
                .setGroupId("rule-rt-stream")
                .setStartingOffsets("earliest".equals(offsetMode)
                        ? OffsetsInitializer.earliest() : OffsetsInitializer.latest())
                .setValueOnlyDeserializer(new TradeEventDeserializer())
                .build();

        WatermarkStrategy<TradeEvent> watermark = WatermarkStrategy
                .<TradeEvent>forBoundedOutOfOrderness(Duration.ofSeconds(5))
                .withTimestampAssigner((e, ts) -> e.occurredAt().toEpochMilli())
                .withIdleness(Duration.ofMinutes(1));

        DataStream<TradeEvent> trades = env.fromSource(source, watermark, "kafka-trades");
        KeyedStream<TradeEvent, String> keyed = trades.keyBy(TradeEvent::customerId);

        // RT-M：1s tumbling 每秒计数 → RollingCountFn 环形缓冲滚动求 6 个 size
        DataStream<PartialFeature> rtm = keyed
                .window(TumblingEventTimeWindows.of(Duration.ofSeconds(1)))
                .aggregate(new PerSecondCountFn(), new SecondCountTagFn())
                .keyBy((SecondCount sc) -> sc.customerId)
                .process(new RollingCountFn());

        // RT-D：自然日累计金额
        DataStream<PartialFeature> rtd = keyed
                .window(TumblingEventTimeWindows.of(Duration.ofDays(1)))
                .aggregate(new AmountSumAggregateFn(), new AmountTagFn());

        // RT-B：5min/30s 滑动 API 通道占比
        DataStream<PartialFeature> rtb = keyed
                .window(SlidingEventTimeWindows.of(Duration.ofMinutes(5), Duration.ofSeconds(30)))
                .process(new RtbProcessFn());

        rtm.union(rtd, rtb)
                .keyBy((PartialFeature p) -> p.customerId)
                .process(new FeatureSnapshotMerger())
                .name("feature-snapshot-merger")
                .sinkTo(new RedisFeatureSink(redisHost, redisPort))
                .name("redis-sink");

        env.execute("rule-rt-stream feature pipeline");
    }

    private static String getEnv(String key, String def) {
        String v = System.getenv(key);
        return v != null ? v : def;
    }
}
```

> 配置全走环境变量（非 Spring Boot）。`keyed` 被 3 个窗口 fan-out 复用（KeyedStream 可多下游消费，不复制数据）。窗口 API 全用 `Duration`。

- [ ] **Step 1: 写 TradeStreamJob → Step 2: 编译 → Step 3: Commit**

```bash
git add rule-rt-stream/src/main/java/com/sstlfsj/rule/stream/TradeStreamJob.java \
  && git commit -m "feat(stream-rt): TradeStreamJob — 1s 微桶滚动 RT-M + RT-D + RT-B + union + merger + Redis SinkV2"
```

---

## Task 11: 全量 clean test 兜底

- [ ] **Step 1: `$MVN clean test`**
- Expected: 全模块绿（原有 27 + rule-rt-stream）

---

## Task 12: Docker Kafka + Redis + Flink 本地端到端验证

> 需 Docker。起 Kafka KRaft + Redis → `earliest` 先灌后跑 → 同秒灌 9 笔 API 交易（触发 LATENCY_ARB）→ 本地 `-Plocal` 跑 job → HGETALL 验证。

- [ ] **Step 1: 起 Kafka KRaft + Redis 容器**

```bash
docker run -d --name kafka-raft -p 9092:9092 \
  -e KAFKA_CFG_NODE_ID=0 -e KAFKA_CFG_PROCESS_ROLES=controller,broker \
  -e KAFKA_CFG_LISTENERS=PLAINTEXT://:9092,CONTROLLER://:9093 \
  -e KAFKA_CFG_LISTENER_SECURITY_PROTOCOL_MAP=CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT \
  -e KAFKA_CFG_CONTROLLER_QUORUM_VOTERS=0@localhost:9093 \
  -e KAFKA_CFG_CONTROLLER_LISTENER_NAMES=CONTROLLER \
  bitnami/kafka:latest
docker run -d --name redis-feature -p 6379:6379 redis:latest
```

- [ ] **Step 2: 创建 topic**

```bash
docker exec kafka-raft kafka-topics.sh --create --topic rt.trade.raw \
  --bootstrap-server localhost:9092 --partitions 3 --replication-factor 1
```

- [ ] **Step 3: 灌测试交易**（cust-arb 同一秒 9 笔 API → rtm_mwr_1s=9>8 且 fast_trade_ratio=1.0>0.8 → LATENCY_ARB；cust-watch 1 笔 WEB 对照）

```bash
# cust-arb：9 笔同在 10:00:00 秒内（毫秒递增），全 API
for ms in 001 002 003 004 005 006 007 008 009; do
  echo "{\"customerId\":\"cust-arb\",\"instrument\":\"BTC\",\"amount\":500.00,\"channel\":\"API\",\"occurredAt\":\"2026-06-20T10:00:00.${ms}Z\",\"eventId\":\"e2e-arb-${ms}\"}"
done | docker exec -i kafka-raft kafka-console-producer.sh --topic rt.trade.raw --bootstrap-server localhost:9092

echo '{"customerId":"cust-watch","instrument":"ETH","amount":200.00,"channel":"WEB","occurredAt":"2026-06-20T10:00:02Z","eventId":"e2e-watch-1"}' \
  | docker exec -i kafka-raft kafka-console-producer.sh --topic rt.trade.raw --bootstrap-server localhost:9092
```

- [ ] **Step 4: 本地执行 Flink job（earliest 读已灌数据；`-Plocal` 让 flink 依赖 compile）**

```bash
KAFKA_OFFSET=earliest $MVN -Plocal -pl rule-rt-stream -am exec:java \
  -Dexec.mainClass="com.sstlfsj.rule.stream.TradeStreamJob"
# 等水印推进（watermark 5s 乱序 + idleness 1min）触发窗口后 Ctrl-C
```

- [ ] **Step 5: 验证 Redis 特征**

```bash
docker exec redis-feature redis-cli HGETALL rt:feat:cust-arb
# 预期含：rtm_mwr_1s=9  fast_trade_ratio=1  rt_state=LATENCY_ARB  updated_at=<event-time 秒>
docker exec redis-feature redis-cli HGETALL rt:feat:cust-watch
# 预期：rt_state=RT_CLEAN（1 笔 WEB，无异常）
```

- [ ] **Step 6: 清理**

```bash
docker rm -f kafka-raft redis-feature
```

---

## Self-Review

**Spec(rev2) 覆盖：**
- §2 结构（含 SecondCount/FeatureField/PartialFeature + 微桶类）→ Task 2/4/5 ✓
- §3 技术栈（Flink Duration API + Jackson3 BOM + shade + local profile）→ Task 1/2 ✓
- §4 TradeEvent → Task 3 ✓
- §5 微桶滚动（1s 桶 + 环形缓冲 + 三流 union）→ Task 5/6/8/10 ✓
- §6 sus_score / §7 RT 状态 → Task 7 ✓
- §8 Redis SinkV2 + event-time updated_at → Task 9 ✓
- §10 测试（RollingWindowState 纯函数 + merger harness）→ Task 5/8/3/7/9/12 ✓

**类型一致性：**
- `TradeEvent`(record) → Task 3；`SecondCount`/`PartialFeature`/`FeatureField`/`FeatureSnapshot`(POJO/enum) → Task 4
- 三流元素统一 `PartialFeature` → union 合法（Task 10）
- `PerSecondCountFn`(→Long) + `SecondCountTagFn`(Long→SecondCount) → `RollingCountFn`(SecondCount→PartialFeature) 链一致（Task 5）
- `FeatureSnapshotMerger`(PartialFeature→FeatureSnapshot) 按 `FeatureField` 覆盖（Task 8）
- `RedisFeatureSink`(host,port) Sink V2 → `sinkTo`（Task 9/10）

**修订点对账（对应 rev1 review 硬伤）：**
- Flink `Time` 删除 → 全用 `Duration`（Task 10）✓
- `keyBy` 返回 `KeyedStream`（Task 10）✓
- Jackson3 版本 → 由 Spring Boot 4 BOM 管，删 jackson3.version + 删 jsr310（Task 1/2/3）✓
- exec:java provided classpath → `-Plocal` profile 提 compile（Task 2/12）✓
- 无 fat jar → maven-shade-plugin（Task 2）✓
- e2e `latest` 漏数据 → `KAFKA_OFFSET=earliest`（Task 10/12）✓
- e2e 缺 redis 容器 → Task 12 Step 1 起 redis-feature ✓
- e2e 数据触发不了 LATENCY_ARB → 同秒 9 笔 API（Task 12 Step 3）✓
- 「非零才覆盖」脏读 → 微桶每秒重算 + merger 覆盖写（含 0 回落）（Task 5/8）✓
- updated_at 墙钟 → event-time（Task 8）✓
- SinkFunction 弃用 → Sink V2（Task 9）✓
- merger 无测试 → harness 测 + RollingWindowState 纯函数测（Task 5/8）✓

**已知 P2 限制（计划内不实现）：**
- eventId 去重（P3 MapState）
- `sample_n_7d` 显著性检验（P3）
- checkpoint state backend 仅本地默认 HashMap（prod RocksDB 留 P3）
- 滚动精度 = 1s 桶粒度（sub-second 留后续，改桶粒度即可）
```
