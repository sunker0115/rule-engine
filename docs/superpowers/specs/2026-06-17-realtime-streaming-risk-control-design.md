# 实时流式风控 — 完整设计（上下游打通）

> 状态：设计稿（2026-06-17）。配套既有：trading-risk-scenario / factor-scoring-integration（memory）、`08-evolution.md` §2.24 物化特征层 / §2.25 what-if 回放、`SourceType.STREAM`（已预留未实装）。
>
> 核心立场：**规则引擎不改 reactive、不持有窗口状态。** 流式计算是上游新项目；引擎只读预计算特征、出 Decision。本设计 = `08-evolution.md` §2.24「物化特征层」的具体落地实例。

---

## 一、范围与不变量

**做什么**：交易流的实时两阶段漏斗风控——Stage-1 逐笔削峰筛查，Stage-2 客户级深度分析出风控等级 Decision（LOW/MID/HIGH/CRITICAL）。

**三条不变量（贯穿全设计，不得破）**：

1. **引擎无状态**：所有窗口/计数/统计状态在 Flink + Redis，引擎侧经 `MetricSourceHandler` 只读。引擎仍是同步单发决策。
2. **引擎做决策不做分析**：sus_score、RT-M、显著性检验、RT 状态都是上游算好的**数字**，引擎只做「数字 → 哪个 Decision」的映射。
3. **引擎不派发副作用**（D60）：引擎只产出 Decision；CustomerRiskProfile 状态机迁移由下游消费 Decision 完成。

---

## 二、总体架构

```
交易系统 ── produce ──▶ Kafka: rt.trade.raw (交易流，分区键=customerId)
                              │
                              ▼
              ┌──────────────────────────────────────────────┐
              │  rule-rt-stream  (新项目，Flink Job)            │
              │  ───────────────────────────────────────────  │
              │  ① keyBy(customerId) + 多窗口聚合              │
              │     RT-M(1s/10s/30s/1m/2m/5m) / RT-D / RT-B    │
              │  ② 二项显著性检验 + 样本量门（N<20 跳过）       │
              │  ③ 派生 RT 状态 / sus_score                    │
              │  ④ Stage-1 阈值门（sus_score ≥ 阈值，阈值可配） │
              └───────────┬───────────────────────┬──────────┘
            写特征(全量)   │                       │ emit(仅过门的可疑客户)
                          ▼                       ▼
                   Redis 特征库            Kafka: rt.suspect.customer
                  (customerId→特征)        (Stage-2 触发流)
                          │                       │
                          │                       ▼
                          │            ┌──────────────────────────┐
                          │            │ rt-bridge (薄消费者/SDK)   │
                          │            │ 消费 suspect → 调引擎评估   │
                          │            └────────────┬─────────────┘
                          │ STREAM handler 读        │ RuleEvent(sceneCode=Scene B)
                          └─────────────────────────▼
                                    ┌──────────────────────────────┐
                                    │  rule-engine (现有，零核心改动)  │
                                    │  Scene B 规则: RT状态 → Decision │
                                    │  STREAM MetricSourceHandler 读   │
                                    │  Redis 特征 (subjectId=customerId)│
                                    └────────────┬─────────────────┘
                                                 │ Decision(LOW/MID/HIGH/CRITICAL)
                                                 ▼
                                    Kafka: rt.decision  (下游消费)
                                                 │
                                                 ▼
                              CustomerRiskProfile 状态机迁移 (引擎外，消费 Decision)
```

**四个组件，职责钉死**：

| 组件 | 归属 | 职责 | 状态 |
|---|---|---|---|
| `rule-rt-stream` | 新项目（Flink） | 有状态窗口计算 + Stage-1 削峰门 | 全新 |
| Redis 特征库 | 新基建 | customerId→预计算特征，O(1) 读 | 新接 |
| `rt-bridge` | 新薄层 | 消费 suspect topic → 调引擎 | 全新（薄） |
| `rule-engine` | 现有 | Scene B 决策 + STREAM handler | 仅加 1 个 handler |

---

## 三、上下游契约（接缝精确定义）

### 3.1 Kafka topics

| topic | 生产者 | 消费者 | 分区键 | 语义 |
|---|---|---|---|---|
| `rt.trade.raw` | 交易系统 | Flink | `customerId` | 交易流（input）。同客户落同分区，保证 keyed state 正确 |
| `rt.suspect.customer` | Flink | `rt-bridge` | `customerId` | Stage-1 过门的可疑客户事件（funnel） |
| `rt.decision` | `rt-bridge`/引擎下游 | 状态机服务 | `customerId` | 引擎决策结果，下游消费 |

> RocketMQ 备选：若组织标准是 RocketMQ，三条 topic 换成 RocketMQ topic，Flink 侧用 `rocketmq-flink` connector，其余设计不变（source/sink 是 connector 配置项，非架构）。

### 3.2 Redis 特征库 key 规范

这是 JetLinks「Scope 分层」在本项目的落地——**Scope = key 维度，Counter = 特征值，生命周期 = TTL**：

```
rt:feat:{customerId}                      # Hash：客户级 RT 特征快照
   ├── rtm_mwr_1s, rtm_mwr_10s, ...       # RT-M 各窗口
   ├── fast_trade_ratio, ultra_fast_ratio # RT-B
   ├── sample_n_7d                        # 7天50笔样本计数（样本量门用）
   ├── rt_state                           # RT_CLEAN/RT_WATCH/SHORT_ALPHA/LATENCY_ARB
   └── updated_at                         # 特征新鲜度（引擎侧降级判断用）
TTL = 滑动窗口最大跨度 + 缓冲（如 7d + 1h）
```

- key 维度按需扩展：`rt:feat:cust:{id}` / `rt:feat:inst:{instrument}` / `rt:feat:cust_inst:{id}:{inst}`——对应 Scope 的「按客户/按标的/按客户+标的」。
- Flink 写、引擎只读。**引擎绝不写 Redis**（守不变量①）。

### 3.3 引擎调用契约（rt-bridge → rule-engine）

`rt-bridge` 消费 `rt.suspect.customer`，组装 `RuleEvent` 调引擎 Scene B：

```
RuleEvent {
  tenantId, sceneCode = "trading.customer_deep_analysis" (Scene B),
  eventType, subjectId = customerId,
  payload = { 触发事件的交易事实（必填字段见 Scene B input-manifest） },
  eventId = 幂等键（来自 suspect 事件，复用 D11/D23 event_id 幂等）
}
```

- 引擎扫 Scene B 规则 AST 收集 `metricCode` → STREAM handler 按 `subjectId(=customerId)` 读 Redis 特征 → AST 求值 → Decision。
- **公开评估只收 payload（D55）**；RT 特征不走 payload，走 STREAM metric（平台侧取，调用方不可覆盖，`allowProvided=false`，对齐 D30 STREAM 默认）。

### 3.4 STREAM MetricSourceHandler（引擎侧唯一新增代码）

填 `SourceType.STREAM` 预留坑位，照 `rule-samples/.../featurestore/FeatureStoreHandler` 与 `SqlAggregateMetricSourceHandler` 范式：

```java
@MetricSourceType("STREAM")
@Component
public class StreamFeatureMetricSourceHandler implements MetricSourceHandler {
    private final StringRedisTemplate redis; // 注入全局 Bean

    @Override
    public MetricValue fetch(MetricQuery query) {
        // key 维度由 metric_definition.params 指定（cust / inst / cust_inst）
        String redisKey = "rt:feat:" + query.subjectId();
        String field = (String) query.params().get("feature");   // 如 "rt_state"
        Object raw = redis.opsForHash().get(redisKey, field);
        if (raw == null) {
            // D15 降级语义：缺值标 error，按 metric 粒度配置 忽略/失败/回落
            return MetricValue.error(/*dataType*/, "STREAM_FEATURE_MISSING");
        }
        // 新鲜度校验：updated_at 超阈值视为陈旧 → 降级（避免拿旧特征做决策）
        return new MetricValue(raw, dataType, "STREAM");
    }
}
```

- 自动装配：`EvalContextAssembler` 已按 `@MetricSourceType` 收集 `List<MetricSourceHandler>`，**加这个 Bean 即生效，评估编排零改动**（见 `EvalAutoConfiguration.evalContextAssembler`）。
- `metric_definition` 新增若干 STREAM 档：`source_type=STREAM` + `params={feature, keyDim}` + per-metric 降级策略（归 D15）。

---

## 四、新项目 rule-rt-stream（Flink）结构

```
rule-rt-stream/
├── src/main/java/com/sstlfsj/rule/stream/
│   ├── source/        TradeStreamSource          (Kafka rt.trade.raw 反序列化为 Trade)
│   ├── feature/
│   │   ├── RtmWindowFn  (多窗口 MWR：滑动窗口聚合 1s/10s/30s/1m/2m/5m)
│   │   ├── RtbBehaviorFn(fast_trade_ratio 等行为比率，keyed state)
│   │   └── SignificanceFn(二项分布显著性检验 p<0.05 + 样本量门 N<20 跳过)
│   ├── state/         RtStateDeriver             (特征 → RT_CLEAN/WATCH/SHORT_ALPHA/LATENCY_ARB)
│   ├── gate/          Stage1Gate                 (sus_score ≥ 阈值；阈值读 config，可热调)
│   ├── sink/
│   │   ├── RedisFeatureSink   (全量特征写 rt:feat:{customerId})
│   │   └── SuspectEventSink   (过门客户 emit rt.suspect.customer)
│   └── config/        ThresholdConfigSource      (阈值动态配置：Redis/config topic 广播)
└── pom.xml            (Flink + Kafka connector + Redis client；版本集中根 pom)
```

**关键 Flink 设计点**：
- **event-time + watermark**：用交易发生时刻而非处理时刻，对齐引擎 `occurredAt`/asOf 可复现语义；乱序容忍按业务定 allowedLateness。
- **keyed state**：`keyBy(customerId)`，RT-M 多窗口 / 7天50笔样本都是 keyed window/state，Flink 自带容错。
- **checkpoint**：至少一次（at-least-once）足够——特征是覆盖写（最新值胜），重复计算不影响正确性；exactly-once 没必要，省开销。
- **样本量门**：`sample_n_7d < 20` 时 RtStateDeriver 直接产 `RT_CLEAN`（不判定），对齐 trading-risk-scenario 样本要求。

---

## 五、两阶段漏斗编排（#2 决策，甲/乙两版）

### 默认（乙）：Flink 削峰门 + 引擎 Scene B —— 推荐

- Stage-1（sus_score + 阈值门）在 Flink 内联：逐笔交易已在 Flink 流里，顺手算分过门，不打引擎。
- 仅过门的可疑客户 emit `rt.suspect.customer` → `rt-bridge` 调引擎 Scene B 做决策。
- 阈值 `Stage1Gate` 读 `ThresholdConfigSource`（Redis/config topic），运营调阈值不发版。
- 优点：逐笔洪流不打引擎，延迟/吞吐最优；适合 Stage-1 万级 TPS。
- 缺点：Stage-1 的「F 信号组合逻辑」在 Flink 代码里，改逻辑（非阈值）要发版。

### 备选（甲）：Stage-1 也走引擎 Scene A

- 每笔交易 → `rt-bridge` 调引擎 Scene A（沿用规则 AST 表达 F 信号），Scene A 出「疑似」Decision → 触发 Scene B。
- 优点：Stage-1 也完全运营自助（规则化）。
- 缺点：逐笔打引擎，仅适合 Stage-1 TPS 不高（≤ 几千）。
- 切换成本：低——Flink 侧只少做 gate；`rt-bridge` 多一次 Scene A 调用；引擎侧 Scene A 配置而已，STREAM handler/特征库/Scene B 全复用。

> **决策依据**：Stage-1 逐笔 TPS。万级 → 乙；几千以内且要 Stage-1 自助 → 甲。本设计其余部分两版完全共用。

---

## 六、横切关注点

| 关注点 | 设计 |
|---|---|
| **幂等** | `rt.suspect.customer` 事件带 eventId，引擎复用 D11/D23（Redis+DB event_id 幂等双兜底），重复投递不重复决策 |
| **特征降级** | Redis 不可用 / 特征缺失 / 陈旧 → STREAM handler 返回 `MetricValue.error`，按 metric 粒度走 D15 降级（忽略/失败/回落），**不静默用旧值** |
| **时间一致** | Flink event-time 与引擎 `occurredAt` 同源（交易发生时刻），保证流计算与决策可复现、可回放（衔接 D70/§2.25） |
| **背压** | 在 Flink 层（其原生 backpressure）；引擎侧用虚拟线程（`spring.threads.virtual.enabled=true`）扛 rt-bridge 并发调用，**不改 reactive** |
| **可观测** | 复用引擎 evaluation_session/node_trace；Flink 侧自带 metrics → 接 §2.22 OTLP/LGTM |
| **效果闭环** | 决策结果 + 事后标签 → 衔接「决策效果闭环」方向（拟新增 roadmap 锚点），度量各 Decision 的 TP/FP |

---

## 七、分阶段落地

1. **P0 引擎侧坑位（最小、可独立验）**：实现 `StreamFeatureMetricSourceHandler`（`@MetricSourceType("STREAM")`）+ Redis 接入 + `metric_definition` STREAM 档 + 单测/集成测（mock Redis 灌特征 → Scene B 评估出 Decision）。**不依赖 Flink，先打通引擎读特征→出决策。**
2. **P1 特征库 + 离线灌数**：Redis 特征 schema 定稿；先用批/脚本灌一批历史特征，验证 Scene B 规则配置（RT 状态 → Decision 映射）正确。
3. **P2 Flink 作业**：`rule-rt-stream` 跑通 Kafka source → 多窗口聚合 → 写 Redis；先不接显著性检验，验证特征实时更新。
4. **P3 完整漏斗**：补显著性检验 + 样本量门 + Stage-1 gate + suspect topic + `rt-bridge` 调引擎，端到端跑通。
5. **P4 下游闭环**：`rt.decision` → CustomerRiskProfile 状态机迁移服务。

每阶段独立可验，P0 与现有引擎契约对齐，先落地价值。

---

## 八、待定 / 风险

- **#2 Stage-1 放哪**：默认乙，待 Stage-1 TPS 数据确认或反转为甲。
- **Kafka 运维**：团队首次用 Kafka（原 RocketMQ）；若运维顾虑大，可改 RocketMQ + Flink rocketmq-connector，架构不变。
- **Redis 选型**：高吞吐特征读写考虑 Redis Cluster / Tair（对齐 §2.24「持久化 Redis / Tair / 列存」），与 D9「全 MySQL 起步」需专门决策（特征值不属引擎自有持久化范畴）。
- **实现前置**：按项目工作流，正式编码前对引擎集成侧（STREAM handler / autoconfiguration / metric_definition 扩列）跑一次 code-architect 模式分析，带出命名/错误处理/测试约定，再进实现。
```
