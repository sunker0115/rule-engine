# P3: 完整漏斗 — 设计

> 状态：设计稿（2026-06-20）。前序：P2 Flink 微桶滚动已落地（`51c3b975`），P0 STREAM handler + P1 Redis schema 已就绪。
>
> 关键决策（2026-06-20）：显著性检验与 sample_n_7d 跳过 P3（sus_score+阈值即标准速度检查；长窗口计数留 P4 离线日批）；分支编排用 Side Output（Flink 标准 conditional routing）；rt-bridge 走 HTTP（Kafka consumer → POST rule-api → emit rt.decision）；`StateTtlConfig.newBuilder(Duration)`——Flink 2.0 全族删除 `Time` 类，TTL 改用 `java.time.Duration`。

## 1. 目标

在 P2 离线特征计算之上补齐：eventId 去重 → Stage-1 削峰门（side output）→ `rt.suspect.customer` Kafka topic → `rule-rt-bridge` 消费 → HTTP 调引擎 Scene B → 决策落 `rt.decision`。

## 2. 新增组件

```
rule-stream-rt（P2 已建，P3 改 pipeline）
  ├── 新: feature/EventDedupFn.java       ← MapState<eventId,Boolean> + Duration.ofMinutes(10) TTL
  ├── 新: gate/Stage1GateFn.java          ← KeyedProcessFunction: sus_score≥threshold → side output
  ├── 新: gate/ThresholdConfig.java        ← 阈值常量（P4 改动态），类级常量，不抽接口
  ├── 新: model/SuspectEvent.java          ← suspect topic 消息体（POJO）
  ├── 新: sink/SuspectEventSink.java       ← KafkaSink<SuspectEvent> JSON 序列化
  ├── 改: TradeStreamJob.java              ← dedup 前置 + Side Output + suspect sink
  └── 新测试: EventDedupFn + Stage1GateFn + SuspectEventSink

rule-rt-bridge/                              ← 新 Maven 模块，Spring Boot
  ├── pom.xml                               ← spring-boot-starter + spring-kafka + rule-kernel(Jackson3)
  └── src/main/java/com/sstlfsj/rule/bridge/
      ├── RtBridgeApp.java                  ← @SpringBootApplication
      ├── SuspectConsumer.java              ← @KafkaListener rt.suspect.customer → 调 EvalClient
      ├── EvalClient.java                   ← RestClient POST rule-api /api/v1/public/evaluate
      ├── DecisionPublisher.java            ← KafkaTemplate<String,String> → rt.decision
      └── model/SuspectPayload.java         ← suspect topic 反序列化 record
```

## 3. eventId 去重

`EventDedupFn`（`KeyedProcessFunction<String, TradeEvent, TradeEvent>`）放在 `keyBy(customerId)` 之后、三个窗口流之前。

- 状态：`MapState<String, Boolean>`，key=eventId，value=Boolean.TRUE
- TTL：`StateTtlConfig.newBuilder(Duration.ofMinutes(10)).setUpdateType(OnCreateAndWrite).build()`
  - Flink 2.0 已删除 `org.apache.flink.api.common.time.Time`，TTL 构建用 `java.time.Duration`
- 逻辑：已见过 → drop；未见过 → put + forward。无 eventId 的事件不过滤
- `KeyedProcessFunction.open(OpenContext)`——Flink 2.x 签名

去重后的 deduped stream 同时喂给 RT-M/RT-D/RT-B 三条窗口流。

## 4. Stage-1 削峰门（Side Output）

`Stage1GateFn`（`KeyedProcessFunction<String, FeatureSnapshot, FeatureSnapshot>`）。输入 FeatureSnapshot（merger 产出）。

Side Output 模式（Flink 标准 conditional routing）：
- **主输出** `FeatureSnapshot`：全量客户 → `sinkTo(RedisFeatureSink)`
- **侧输出** `OutputTag<SuspectEvent>`：仅 `susScore >= threshold` 的过门客户

```
merger → process(Stage1GateFn)
          ├─ 主输出: FeatureSnapshot → sinkTo(redis)       // 全量
          └─ 侧输出: SuspectEvent → sinkTo(kafka suspect)   // 仅过门
```

Threshold：`ThresholdConfig.DEFAULT_SUS_SCORE_THRESHOLD = 0.5`（P3 类级常量，P4 改动态配置读 Redis）。

`SuspectEvent`（POJO，public 字段）含：customerId / susScore / rtState / features（Map，FeatureSnapshot 当前各字段值）/ eventId（customerId + "-" + updatedAt）/ occurredAt（Instant，event-time）。

## 5. SuspectEventSink

工厂方法 `SuspectEventSink.create(brokers, topic)` → `KafkaSink<SuspectEvent>`。

- 序列化：`KafkaRecordSerializationSchema`，key=customerId（`byte[]`），value=Jackson3 `JsonMapper` 序列化为 JSON bytes
- topic = `rt.suspect.customer`
- Kafka brokers 与 source 共用环境变量 `KAFKA_BROKERS`

## 6. rule-rt-bridge（新 Spring Boot 模块）

```
rt.suspect.customer → @KafkaListener(SuspectConsumer)
  → 反序列化 SuspectPayload
  → EvalClient.evaluate(customerId, payload)    // RestClient POST rule-api
  → DecisionPublisher.publish(decision)         // KafkaTemplate → rt.decision
```

**SuspectConsumer**：`@KafkaListener(topics="${bridge.suspect-topic:rt.suspect.customer}")`，并发度由 `spring.kafka.listener.concurrency` 控制。反序列化 `SuspectPayload`（record，Jackson3）。

**EvalClient**：Spring Boot 4 `RestClient`（`RestClient.create()`，非 RestTemplate），POST JSON 到 `rule-api` 的 `/api/v1/public/evaluate`。组装 `RuleEvent` 请求体（sceneCode 可配），超时 5s，返回 Decision JSON。失败策略：日志告警（SLF4J），不抛异常、**不重试**——suspect topic 的 offset 已提交（Spring Kafka 默认 auto-commit），失败的评估直接丢弃。靠 Flink 侧覆盖写（at-least-once）保证下一轮该客户的特征更新后可能再次触发 suspect。

**DecisionPublisher**：`KafkaTemplate<String, String>` 发送 JSON 到 `rt.decision`，key=customerId。

bridge 不依赖 `rule-eval-svc` / `rule-sdk` / `rule-api` 的 jar——纯 HTTP 契约，薄、独立部署、语言无关。

## 7. P3 不涉及

- 显著性检验（sus_score+阈值即标准速度检查，泊松 p 值不做）
- sample_n_7d 7d 样本计数（P4 离线日批写 Redis `rt:meta:{customerId}.sample_n_7d`）
- 阈值动态配置（P4 `ThresholdConfigSource` Redis/config topic 广播）
- `rt.decision` 下游消费者（P4 CustomerRiskProfile 状态机）
- SDK 嵌入式评估（P4 降延迟；P3 HTTP 够用）
- RocksDB state backend（P4 压测后评估；P3 用默认 HashMapStateBackend）

## 8. 文件清单

| 模块 | 文件 | 操作 |
|---|---|---|
| rule-stream-rt | `feature/EventDedupFn.java` | 新建 |
| rule-stream-rt | `gate/Stage1GateFn.java` | 新建 |
| rule-stream-rt | `gate/ThresholdConfig.java` | 新建 |
| rule-stream-rt | `model/SuspectEvent.java` | 新建（已建） |
| rule-stream-rt | `sink/SuspectEventSink.java` | 新建 |
| rule-stream-rt | `TradeStreamJob.java` | 修改：dedup 前置 + Side Output + suspect sink |
| rule-stream-rt | `src/test/` 对应测试 | 新建（EventDedupFn harness + Stage1GateFn harness） |
| 根 pom | `pom.xml` | 修改：`<modules>` 加 `rule-rt-bridge`；spring-kafka 已由 BOM 管 |
| rule-rt-bridge | `pom.xml` | 新建 |
| rule-rt-bridge | `RtBridgeApp.java` | 新建 |
| rule-rt-bridge | `SuspectConsumer.java` | 新建 |
| rule-rt-bridge | `EvalClient.java` | 新建 |
| rule-rt-bridge | `DecisionPublisher.java` | 新建 |
| rule-rt-bridge | `model/SuspectPayload.java` | 新建（record） |
| rule-rt-bridge | `src/test/` 对应测试 | 新建 |
