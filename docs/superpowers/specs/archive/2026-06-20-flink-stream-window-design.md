# P2: Flink 流式计算 — 设计

> 状态：设计稿（2026-06-20，rev2：滚动速率改微桶实现）。配套：`2026-06-17-realtime-streaming-risk-control-design.md` §四（rule-rt-stream）、P0 STREAM handler 已落地、P1 Redis 特征 schema + Scene B 已验证。

## 1. 目标

消费 Kafka `rt.trade.raw` 交易流 → keyBy(customerId) → 1s 微桶 + 环形缓冲滚动求 6 个 RT-M 速率 + RT-D 日累计 + RT-B 通道占比 → 派生 RT 状态 + sus_score → 写回 Redis `rt:feat:{customerId}`。引擎侧零改动（读端已就绪）。

## 2. 项目结构

```
rule-rt-stream/                          ← 新 Maven 模块，非 Spring Boot
├── pom.xml                             ← Flink 2.1.3 + Kafka connector 5.0.0-2.1 + Jedis + shade(fat jar)
│                                          Jackson3 由根 pom(Spring Boot 4 BOM)统一管，不自锁版本
└── src/main/java/com/sstlfsj/rule/stream/
    ├── TradeStreamJob.java             ← StreamExecutionEnvironment 入口，构建微桶 + RT-D + RT-B pipeline
    ├── model/
    │   ├── TradeEvent.java             ← Kafka value 反序列化 record
    │   ├── SecondCount.java            ← 1s 桶计数（customerId, count, epochSecond），喂 RollingCountFn
    │   ├── FeatureField.java           ← enum：RTM_1S..RTM_5M / RTD_AMOUNT / FAST_TRADE_RATIO（封闭取值）
    │   ├── PartialFeature.java         ← 单字段部分特征（customerId, field, value, eventTime），union 统一元素
    │   └── FeatureSnapshot.java        ← 合并后完整快照（经 FeatureSnapshotMerger 写 Redis）
    ├── source/
    │   └── TradeEventDeserializer.java ← JSON → TradeEvent（value-only）
    ├── feature/
    │   ├── PerSecondCountFn.java       ← 1s tumbling 增量计数（AggregateFunction，仅存累加器）
    │   ├── SecondCountTagFn.java       ← ProcessWindowFunction：给每秒计数补 customerId + 窗口结束时间
    │   ├── RollingWindowState.java     ← 纯函数：按秒计数 → 6 个 size 滚动和（无 Flink 依赖，可单测）
    │   ├── RollingCountFn.java         ← KeyedProcessFunction：环形缓冲(最近300秒) + event-time timer 驱动回落
    │   ├── DailyAmountFn.java          ← RT-D KeyedProcessFunction：每事件 emit 当日累计，跨日重置
    │   ├── RtbProcessFn.java           ← RT-B 5min 滑动窗口 API 通道占比 → PartialFeature
    │   ├── FeatureSnapshotMerger.java  ← 合并 RT-M/RT-D/RT-B 三流（KeyedProcessFunction + ValueState + event-time timer）
    │   └── SusScoreFn.java             ← sus_score 加权计算（纯函数）
    ├── state/
    │   └── RtStateDeriver.java         ← 特征 → RT 状态（纯函数）
    └── sink/
        └── RedisFeatureSink.java       ← Flink Sink V2：HSET rt:feat:{customerId} + EXPIRE
```

## 3. 技术栈

| 组件 | 版本 | 用途 |
|---|---|---|
| flink-streaming-java | 2.1.3 | DataStream API（scope=provided，集群自带） |
| flink-clients | 2.1.3 | 本地 `-Plocal` 跑 job（scope=provided，local profile 提 compile） |
| flink-connector-kafka | 5.0.0-2.1 | Kafka source consumer（connector 独立版本，非 Flink 版本） |
| kafka-clients | 由 Flink connector 传递 | Kafka consumer API |
| jedis | 由根 pom 管理 | Redis Hash HSET sink |
| jackson-databind | 由 Spring Boot 4 BOM 管理（tools.jackson 3.x） | Kafka JSON 反序列化 |

**依赖原则：**
- `rule-rt-stream` 只写坐标不写 version：Flink / Kafka connector / Jedis 由根 pom `dependencyManagement` 锁；Jackson3 由 `spring-boot-starter-parent`（4.x）BOM 锁（根 pom 无 jackson 自定义版本）。
- **窗口 API 用 `java.time.Duration`**：Flink 2.0 起已删除 `org.apache.flink.streaming.api.windowing.time.Time` 类（FLIP-335），window assigner 工厂方法只接受 `Duration`。
- **Jackson 3**：`java.time` 支持已并入 `jackson-databind`，无需单独 `jackson-datatype-jsr310`，`Instant` 默认可序列化；`ObjectMapper` 走 `JsonMapper.builder().build()`。
- 不是 Spring Boot 应用，`main()` 手写 Flink pipeline。
- **打包**：`maven-shade-plugin` 出 fat jar（含 connector/jedis/jackson）提交集群；flink 依赖 provided 不打进 jar。

## 4. TradeEvent

```java
public record TradeEvent(
        String customerId,    // 分区键（keyBy）
        String instrument,    // 标的代码
        BigDecimal amount,    // 交易金额
        String channel,       // APP / WEB / API
        Instant occurredAt,   // 交易发生时刻（event-time + watermark 源）
        String eventId        // 幂等键
) {}
```

## 5. 窗口定义与实现策略

所有计算基于 `keyBy(customerId)` 的 keyed state，event-time + watermark（`forBoundedOutOfOrderness(Duration.ofSeconds(5))` + `withIdleness(Duration.ofSeconds(10))`，本地 e2e 用 10s 让空分区快速让位；prod 可调大）。

| 特征 | 产出字段 | 含义 | 实现 | 滚动粒度 |
|---|---|---|---|---|
| RT-M ×6 | `rtm_mwr_1s/10s/30s/1m/2m/5m` | 过去 N 秒笔数（速率） | 1s 微桶 + RollingCountFn 环形缓冲 + event-time timer | 每秒（含回落） |
| RT-D | `rtd_amount_sum` | UTC 日累计交易额（日内实时） | `DailyAmountFn`（KeyedProcessFunction，每事件 emit 当日累计） | 每事件 |
| RT-B | `fast_trade_ratio` | 5min 内 API 通道占比 | `SlidingEventTimeWindows(5m, 30s)` + `RtbProcessFn` | 30s |

> **RT-M 回落由 timer 驱动（关键）**：`RollingCountFn` 收到每秒计数后注册下一秒 event-time timer。即使此后无新交易，watermark 推进也逐秒触发重算——老秒滑出窗口即归零，避免高频后卡在峰值直到 TTL。state 全部滑出后 emit 一次全 0 并停止续约。**仅靠 1s 窗口触发不足以回落**（无交易则窗口不 fire），必须 timer。
>
> **RT-D 不用日 tumbling 窗口**：日窗口一天只在窗口结束（次日 00:00）emit 一次，日内 Redis 值全天为 0，且窗口 end 作为 eventTime 会顶掉 merger 去抖 timer。改 `DailyAmountFn` 每笔交易 emit 当前 UTC 日累计（eventTime=事件时刻，与 RT-M 量级一致），跨日重置。

**RT-M 微桶滚动 pipeline**：

```
keyBy(customerId)
  └─ TumblingEventTimeWindows(1s) + aggregate(PerSecondCountFn, SecondCountTagFn)   ← 每秒笔数(补 key+时间)
       └─ keyBy(customerId)
            └─ process(RollingCountFn)                                              ← 环形缓冲，每秒 emit 6 个 RTM PartialFeature
```

`RtbProcessFn`（RT-B）/ `AmountSumAggregateFn`（RT-D）各自原生窗口产出 `PartialFeature`。三流 `union` 后 `keyBy(customerId).process(FeatureSnapshotMerger)`：维护 `ValueState<FeatureSnapshot>` 当前快照，按 `FeatureField` 覆盖对应字段，通过短 event-time timer（200ms 批量合并）发射完整快照到 Redis sink。

**为什么用微桶而非 6 个原生 sliding window**：
- 原生 sliding window 实现"大 size + 小 slide(滚动)"时，会把每个事件复制进 ⌈size/slide⌉ 个 pane（5m/1s=300×），状态与计算随 size 放大，高频客户成热点——这是已知反模式。
- 微桶把"每秒笔数"交给 **1 个 1s tumbling 原生窗口**（每事件只进 1 个窗口，零放大；乱序/迟到由原生窗口的 watermark + allowedLateness 处理）。`RollingCountFn` 只在每秒计数之上做"最近 N 秒求和"——状态固定为最近 300 个 long/客户，与 size 无关。
- 这是滑动计数的业界标准（时间桶 ring buffer）。**触发/乱序/水印仍归 Flink 原生窗口**，环形缓冲只是纯求和逻辑（抽成 `RollingWindowState` 可单测），不重造窗口机制。
- 滚动精度 = 桶粒度 1s（秒级风控足够；未来要 sub-second 改桶粒度即可，架构不变）。

**回落语义（修早期缺陷）**：`RollingCountFn` 每秒重算 6 个滚动和（老秒滑出即归零），`FeatureSnapshotMerger` 按字段**覆盖写**（含 0）。一波高频后安静下来，RT-M 自然回落到 0，不再卡在峰值——避免 `rt_state` 误判为 `LATENCY_ARB` 直到 TTL 过期。

> `forBoundedOutOfOrderness` 与引擎 `occurredAt`（交易发生时刻）同源，保证可复现、可回放。`withIdleness(1min)` 防止空分区导致 watermark 停滞。

## 6. sus_score 公式（简化，显著性检验留 P3）

```
sus_score = clamp(
  (rtm_mwr_1s / 10.0) * 0.3 +
  (fast_trade_ratio / 0.8) * 0.4 +
  (rtm_mwr_1m / 30.0) * 0.3
, 0, 1)
```

无样本量门（P3 加 `sample_n_7d < 20 → RT_CLEAN`）。

## 7. RT 状态派生

`RtStateDeriver` 按优先级逐条件判断，命中即返回：

| 优先级 | rt_state | 条件 |
|---|---|---|
| 1 | `LATENCY_ARB` | `fast_trade_ratio > 0.8 AND rtm_mwr_1s > 8` |
| 2 | `SHORT_ALPHA` | `sus_score >= 0.6 AND rtm_mwr_1s > 10` |
| 3 | `RT_WATCH` | `sus_score >= 0.3 OR fast_trade_ratio > 0.3` |
| 4 | `RT_CLEAN` | 其余（无异常） |

## 8. Redis sink

`RedisFeatureSink` 实现 **Flink Sink V2**（`Sink` + `SinkWriter`，非已弃用的 `RichSinkFunction`/`addSink`）。每次 `FeatureSnapshotMerger` 发射后 HSET 至 `rt:feat:{customerId}`：

```
HSET rt:feat:{customerId} rtm_mwr_1s <value> rtm_mwr_10s <value> ... rt_state <value> sus_score <value> updated_at <epoch_second>
```

- TTL = 604875s（7d+1h），`EXPIRE` 每次 HSET 后重置
- `updated_at`：merger `onTimer` 的 **event-time**（非墙钟），供引擎侧 STREAM handler 做新鲜度校验；用 event-time 保证回放可复现、新鲜度判断准确
- 覆盖写（最新值胜），at-least-once 语义（checkpoint 重放写覆盖无副作用）
- Jedis `Pipeline` 批量：一次性 HSET 全字段 + 一次 EXPIRE
- Redis 连接异常时 catch + SLF4J 日志，不终止 Flink job（checkpoint 重放可恢复）

## 9. 不涉及

- Stage-1 削峰门（P3）
- `rt-bridge` 消费 suspect topic → 调引擎（P3）
- Stage-1 阈值动态配置（P4）
- 显著性检验 + 样本量门（P3）
- Kafka 换 RocketMQ（connector 可切换，架构不变）
- **checkpoint state backend**：P2 用默认 `HashMapStateBackend` + 本地 checkpoint dir；prod 的 RocksDB + 远端持久化存储留 P3。
- **eventId 去重**：P2 阶段不实现幂等去重，Kafka 重放可能导致同一事件重复计数。checkpoint 恢复期间的 at-least-once 重复由覆盖写语义吸收（特征值不敏感），但上游重放导致的重复计数需在 P3 通过 `MapState<eventId, Boolean>` 去重解决。

## 10. 测试策略

- **TradeEvent JSON round-trip**：序列化/反序列化对称
- **RollingWindowState 单测（纯函数，核心）**：喂秒计数序列，验 6 个 size 滚动和正确 + 老秒滑出归零（回落）+ 迟到秒覆盖后重算
- **RollingCountFn / FeatureSnapshotMerger harness 测**：`KeyedOneInputStreamOperatorTestHarness` 验环形推进、覆盖合并、event-time timer 批量发射
- **sus_score 单测**：边界值（0、0.5、1、超限 clamp）验证
- **RtStateDeriver 单测**：5 种状态覆盖
- **Redis sink 集成测**：`docker exec redis-feature redis-cli HGETALL rt:feat:test-cust` 验证 HSET 正确
- **端到端**：Docker Kafka + Redis → 同秒灌 9 笔 API 交易（触发 LATENCY_ARB）→ Flink 本地执行 → `HGETALL` 验证

## 11. 文件清单

| 模块 | 文件 | 操作 |
|---|---|---|
| 根 pom | `pom.xml` | 修改：`<modules>` 加 `rule-rt-stream`；加 Flink 2.1.3 + flink-clients + Kafka connector 5.0.0-2.1 + Jedis 到 `dependencyManagement`（Jackson3 已由 Spring Boot 4 BOM 管，不加） |
| rule-rt-stream | `pom.xml` | 新建：flink provided + flink-clients provided（local profile 提 compile）+ shade plugin；Jackson3/Jedis 只写坐标 |
| rule-rt-stream | 14 个 Java 文件（§2 结构） | 新建 |
| rule-rt-stream | `src/test/` 对应测试 | 新建 |
