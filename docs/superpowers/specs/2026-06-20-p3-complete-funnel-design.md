# P3: 完整漏斗 — 设计

> 状态：设计稿（2026-06-20）。前序：P2 Flink 微桶滚动已落地（`51c3b975`），P0 STREAM handler + P1 Redis schema 已就绪。
>
> 关键决策（2026-06-20）：显著性检验与 sample_n_7d 跳过 P3（sus_score+阈值即标准速度检查；长窗口计数留 P4 离线日批）；分支编排用 Side Output（Flink 标准 conditional routing）；rt-bridge 走 HTTP（Kafka consumer → POST rule-api → emit rt.decision）；`StateTtlConfig.newBuilder(Duration)`——Flink 2.0 全族删除 `Time` 类，TTL 改用 `java.time.Duration`。

## 1. 目标

在 P2 离线特征计算之上补齐：eventId 去重 → Stage-1 风险筛选门（side output）→ `rt.suspect.customer` Kafka topic → `rule-rt-bridge` 消费 → HTTP 调引擎 Scene B → 决策落 `rt.decision`。

## 2. 新增组件

```
rule-stream-rt（P2 已建，P3 改 pipeline）
  ├── 新: feature/EventDedupFn.java       ← MapState<eventId,Boolean> + Duration.ofMinutes(10) TTL
  ├── 新: gate/Stage1GateFn.java          ← KeyedProcessFunction: susScore≥threshold → side output(OutputTag)
  ├── 新: gate/ThresholdConfig.java        ← 阈值常量（P4 改动态），类级常量，不抽接口
  ├── 新: model/SuspectEvent.java          ← suspect topic 消息体（POJO，typed 字段非 Map）
  ├── 新: sink/SuspectEventSink.java       ← KafkaSink<SuspectEvent> JSON 序列化
  ├── 改: TradeStreamJob.java              ← dedup 前置(重新 keyBy) + Side Output + suspect sink
  └── 新测试: EventDedupFn + Stage1GateFn

rule-rt-bridge/                              ← 新 Maven 模块，Spring Boot
  ├── pom.xml                               ← spring-boot-starter + spring-kafka + rule-kernel(Jackson3)
  └── src/main/java/com/sstlfsj/rule/bridge/
      ├── RtBridgeApp.java                  ← @SpringBootApplication
      ├── SuspectConsumer.java              ← @KafkaListener rt.suspect.customer → 调 EvalClient
      ├── EvalClient.java                   ← RestClient POST rule-api /api/v1/rule/evaluate (EvalEventRequest)
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

**pipeline 改造**：`EventDedupFn` 输出的是普通 `DataStream<TradeEvent>`（KeyedProcessFunction 输出不再带 key）。三条窗口流（RT-M 1s 窗口 / RT-D DailyAmountFn / RT-B 5min 窗口）接入前**必须重新 `keyBy(TradeEvent::customerId)`**：

```
trades.keyBy(customerId).process(EventDedupFn)   // → DataStream<TradeEvent> deduped
deduped.keyBy(customerId).window(1s)...           // RT-M
deduped.keyBy(customerId).process(DailyAmountFn)  // RT-D
deduped.keyBy(customerId).window(5m,30s)...       // RT-B
```

注：P2 原 pipeline 是 `trades.keyBy(...)` 后直接接三窗口；P3 插入 dedup 后这一步重新 keyBy 极易漏。

## 4. Stage-1 风险筛选门（Side Output）

`Stage1GateFn`（`KeyedProcessFunction<String, FeatureSnapshot, FeatureSnapshot>`）。输入 FeatureSnapshot（merger 产出）。这是**风险打分筛选门**（susScore≥阈值的客户路由到 suspect topic），不是对原始事件流"削峰"——进门的已是 merger 压平后的稀疏快照流。

Side Output 模式（Flink 标准 conditional routing）：
- **主输出** `FeatureSnapshot`：全量客户 → `sinkTo(RedisFeatureSink)`
- **侧输出** `OutputTag<SuspectEvent>`：仅 `susScore >= threshold` 的过门客户

```
merger → process(Stage1GateFn)  // 保存算子引用 gated
          ├─ gated.sinkTo(redis)                              // 主输出：全量
          └─ gated.getSideOutput(SUSPECT_OUT).sinkTo(kafka)   // 侧输出：仅过门
```

- `getSideOutput(tag)` 必须在 `process()` 返回的 `SingleOutputStreamOperator` 变量上调，不能链在 `sinkTo` 之后
- `OutputTag<SuspectEvent>` 因泛型擦除必须用匿名子类创建：`new OutputTag<SuspectEvent>("suspect-out"){}`，否则运行期报 "Could not determine TypeInformation"

Threshold：`ThresholdConfig.DEFAULT_SUS_SCORE_THRESHOLD = 0.5`（P3 类级常量，P4 改动态配置读 Redis）。取值落在 `RtStateDeriver` 的 RT_WATCH(0.3)~SHORT_ALPHA(0.6) 之间——即"已进入观察、尚未确证"的客户进 Scene B 深度分析。

`SuspectEvent`（POJO，public 字段）—— **typed 字段，不用 `Map` 当容器**（项目数据边界规范）。直接内嵌 FeatureSnapshot 的各 RT 特征字段（customerId / rtmMwr1s / rtmMwr10s / rtmMwr1m / rtmMwr5m / rtdAmountSum / fastTradeRatio / susScore / rtState）+ suspectId（customerId + "-" + updatedAt，与去重用的原始 eventId 区分命名）+ occurredAt（Instant，event-time）。仅在 bridge 端组装 `EvalEventRequest.payload`（开放 Map）时才转 Map——payload 本就是异构开放结构，那里用 Map 合规。

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

**SuspectConsumer**：`@KafkaListener(topics="${bridge.suspect-topic:rt.suspect.customer}")`，并发度由 `spring.kafka.listener.concurrency` 控制。反序列化 `SuspectPayload`（record，字段与生产端 `SuspectEvent` 逐字段对齐，见下表）。

**EvalClient**（契约以 `rule-api` 实际为准，三处校准）：
- **端点**：`POST {rule-api}/api/v1/rule/evaluate`（PULL 同步评估；`/api/v1/public/...` 不存在）
- **请求体 `EvalEventRequest`**（record，非 kernel 内部的 `RuleEvent`）：
  ```
  tenantCode   (必填,@NotBlank)  ← bridge 配置项 bridge.tenant-code
  sceneCode    (必填,@NotBlank)  ← bridge 配置项 bridge.scene-code（Scene B）
  eventType    (必填,@NotBlank)  ← bridge 配置项，如 "trade.suspect"
  subjectId    (必填,@NotBlank)  ← suspect.customerId
  eventId      (必填,@NotBlank)  ← suspect.suspectId（幂等键，复用 D11/D23）
  occurredAt   (Instant)         ← suspect.occurredAt
  payload      (Map<String,Object>) ← suspect 的 RT 特征转 Map（susScore/rtState/rtm*/rtd*/fastTradeRatio）
  asOf         (Instant,可选)    ← 留空，引擎用 occurredAt/now
  ```
  - 四个 `@NotBlank` 任一缺失 → 400；`tenantCode` 在 controller 边界 `resolveIdByCode` 解析，未知 code → 400。bridge 必须配齐 tenantCode/sceneCode/eventType。
- **返回体 `ApiResponse<EvalResult>`**（envelope，非裸 Decision）：解 `data.finalDecision`（或 `data.decision`）再 publish。
- `RestClient`（Spring Boot 4，`RestClient.create()`，非 RestTemplate），超时 5s。

**失败策略与 ack 语义**：Spring Kafka starter 默认 `enable.auto.commit=false` + 容器管理 offset（listener 正常返回才提交，抛异常触发重投）。本设计要"失败即丢弃不重试"，故 **listener 内 try-catch 吞掉评估异常 + 记 SLF4J 告警，正常返回让 offset 提交**（ack 模式 `BATCH`/`RECORD` 均可）。补偿：靠 Flink 侧覆盖写——该客户下一轮特征更新再次过门时重发 suspect。**已知缺口**：过门后即停止交易的客户，特征不再更新、该 suspect 永久丢失无补偿（P3 可接受，P4 加超时兜底）。

**DecisionPublisher**：`KafkaTemplate<String, String>` 发送 Decision JSON 到 `rt.decision`，key=customerId。

bridge 不依赖 `rule-eval-svc` / `rule-sdk` / `rule-api` 的 jar——纯 HTTP 契约，薄、独立部署、语言无关。代价：`SuspectEvent`(Flink 侧) 与 `SuspectPayload`(bridge 侧) 是**有意的 JSON 契约复制**，无编译期绑定，改字段两端手动同步。

**SuspectEvent ↔ SuspectPayload 字段对齐**（跨模块 JSON 契约）：

| 字段 | 类型 | 说明 |
|---|---|---|
| customerId | String | subjectId |
| susScore | double | sus_score |
| rtState | String | RT 状态 |
| rtmMwr1s/10s/1m/5m | long | RT-M 速率（进 payload） |
| rtdAmountSum | double | 日累计（进 payload） |
| fastTradeRatio | double | API 通道占比（进 payload） |
| suspectId | String | customerId + "-" + updatedAt，幂等键 |
| occurredAt | Instant | event-time |

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
