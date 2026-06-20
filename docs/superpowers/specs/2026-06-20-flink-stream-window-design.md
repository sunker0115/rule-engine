# P2: Flink 流式计算 — 设计

> 状态：设计稿（2026-06-20）。配套：`2026-06-17-realtime-streaming-risk-control-design.md` §四（rule-stream-rt）、P0 STREAM handler 已落地、P1 Redis 特征 schema + Scene B 已验证。

## 1. 目标

消费 Kafka `rt.trade.raw` 交易流 → keyBy(customerId) → 8 窗口聚合 → 派生 RT 状态 + sus_score → 写回 Redis `rt:feat:{customerId}`。引擎侧零改动（读端已就绪）。

## 2. 项目结构

```
rule-stream-rt/                          ← 新 Maven 模块，非 Spring Boot
├── pom.xml                             ← Flink 2.1.3 + Kafka connector + Jedis
└── src/main/java/com/sstlfsj/rule/stream/
    ├── TradeStreamJob.java             ← StreamExecutionEnvironment 入口
    ├── model/
    │   └── TradeEvent.java             ← Kafka value 反序列化 record
    ├── source/
    │   └── TradeEventDeserializer.java ← JSON → TradeEvent
    ├── feature/
    │   ├── RtmWindowFn.java            ← RT-M 6 窗口 MWR 聚合
    │   ├── RtdDailyFn.java             ← RT-D 日累计
    │   ├── RtbBehaviorFn.java          ← RT-B fast_trade_ratio
    │   └── SusScoreFn.java             ← sus_score 加权计算
    ├── state/
    │   └── RtStateDeriver.java         ← 特征 → RT 状态
    └── sink/
        └── RedisFeatureSink.java       ← HSET rt:feat:{customerId}
```

## 3. 技术栈

| 组件 | 版本 | 用途 |
|---|---|---|
| flink-streaming-java | 2.1.3 | DataStream API |
| flink-connector-kafka | 2.1.3 | Kafka source consumer |
| kafka-clients | 由 Flink connector 传递 | Kafka consumer API |
| jedis | 由根 pom 管理 | Redis Hash HSET sink |

**依赖原则：** `rule-stream-rt` 只写坐标不写 version（由根 pom 统一锁）。不是 Spring Boot 应用，`main()` 手写 Flink pipeline。

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

## 5. 窗口定义

所有窗口基于 `keyBy(customerId)` 的 keyed state，event-time + watermark（`forBoundedOutOfOrderness(Duration.ofSeconds(5))`）。

| 窗口 | Flink API | 参数 | 产出字段 | 含义 | 产出键 |
|---|---|---|---|---|---|
| RT-M 1s | sliding | size=1s, slide=1s | `rtm_mwr_1s` | 1秒笔数 | `rtm_mwr_1s` |
| RT-M 10s | sliding | size=10s, slide=10s | `rtm_mwr_10s` | 10秒笔数 | `rtm_mwr_10s` |
| RT-M 30s | sliding | size=30s, slide=30s | `rtm_mwr_30s` | 30秒笔数 | `rtm_mwr_30s` |
| RT-M 1m | sliding | size=60s, slide=60s | `rtm_mwr_1m` | 1分钟笔数 | `rtm_mwr_1m` |
| RT-M 2m | sliding | size=120s, slide=120s | `rtm_mwr_2m` | 2分钟笔数 | `rtm_mwr_2m` |
| RT-M 5m | sliding | size=300s, slide=300s | `rtm_mwr_5m` | 5分钟笔数 | `rtm_mwr_5m` |
| RT-D | tumbling | size=1d | `rtd_amount_sum` | 日累计交易额 | `rtd_amount_sum` |
| RT-B | sliding | size=5m, slide=30s | `fast_trade_ratio` | 5min内API通道占比 | `fast_trade_ratio` |

> `forBoundedOutOfOrderness` 与引擎 `occurredAt` 同源（交易发生时刻），保证可复现、可回放。

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

`RedisFeatureSink` 每次窗口触发后 HSET 至 `rt:feat:{customerId}`：

```
HSET rt:feat:{customerId} rtm_mwr_1s <value> rtm_mwr_10s <value> ... rt_state <value> sus_score <value>
```

- TTL = 604875s（7d+1h），`EXPIRE` 每次 HSET 后重置
- 覆盖写（最新值胜），at-least-once 语义（checkpoint 重放写覆盖无副作用）
- Jedis `Pipeline` 批量：一次 HSET + 一次 EXPIRE → 两个 command pipeline 提交

## 9. 不涉及

- Stage-1 削峰门（P3）
- `rt-bridge` 消费 suspect topic → 调引擎（P3）
- Stage-1 阈值动态配置（P4）
- 显著性检验 + 样本量门（P3）
- Kafka 换 RocketMQ（connector 可切换，架构不变）

## 10. 测试策略

- **TradeEvent JSON round-trip**：序列化/反序列化对称
- **窗口聚合单测**：Flink DataStream `TestHarness` / `KeyedOneInputStreamOperatorTestHarness` 验证窗口计数
- **sus_score 单测**：边界值（0、0.5、1、超限 clamp）验证
- **RtStateDeriver 单测**：5 种状态覆盖
- **Redis sink 集成测**：`docker exec redis-feature redis-cli HGETALL rt:feat:test-cust` 验证 HSET 正确
- **端到端**：Docker Kafka → 生产者灌 3 条 TradeEvent → Flink 本地执行 → `HGETALL` 验证

## 11. 文件清单

| 模块 | 文件 | 操作 |
|---|---|---|
| 根 pom | `pom.xml` | 修改：加 Flink 2.1.3 + Kafka dependencies 到 dependencyManagement |
| rule-stream-rt | `pom.xml` | 新建 |
| rule-stream-rt | 10 个 Java 文件（§2 结构） | 新建 |
| rule-stream-rt | `src/test/` 对应测试 | 新建 |
