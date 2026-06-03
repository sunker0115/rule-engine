# D5-C CEP（复杂事件处理）设计文档

## 目标

在现有单事件评估（D5-B）的基础上，支持跨事件时序规则：频率类（N 时间窗内 X 次）、序列类（A → B within N，含 NOT 模式）、聚合类（N 时间窗内指标累计超阈值）。

---

## 核心技术选型

| 决策点 | 选择 | 原因 |
|--------|------|------|
| 事件总线 | RocketMQ | 现有基础设施 |
| 计算层 | Apache Flink（flink-connector-rocketmq） | 原生支持多步序列 + NOT 模式 + watermark 乱序处理，Redis 状态机无法表达 |
| 状态后端 | RocksDB（Flink 内置） | 高基数 key（亿级用户各自独立状态）走磁盘，内存压力可控 |

---

## 架构

### 整体拓扑

```
业务系统
  └─ RuleEvent → RocketMQ（rule-events topic）
                    ↓
              Flink CEP Job（rule-cep-job）
                    ├─ 频率类 Pattern Operator
                    ├─ 序列类 Pattern Operator（含 NOT）
                    └─ 聚合类 Window Operator
                    ↓
              CepFiredEvent → RocketMQ（rule-cep-fired topic）
                    ↓
              rule-eval-svc（现有）
                    └─ 转为 RuleEvent 投入倒排索引评估链路
```

CEP Job 的职责是**检测模式是否满足**，满足后把匹配上下文发回 rule-eval-svc，由现有评估链路负责 Decision 绑定和 Action 派发。CEP 层不做 Decision 判定，只做模式检测。

---

## 新增模块：`rule-cep-job`

独立 Flink 应用，不纳入 `rule-app` Spring Boot 进程，独立部署到 Flink 集群。

### 包结构

```
rule-cep-job/
  src/main/java/com/sstlfsj/rule/cep/
    ├─ CepJobMain.java              # Flink StreamExecutionEnvironment 入口
    ├─ source/
    │    └─ RocketMqEventSource.java   # RocketMQ source connector
    ├─ pattern/
    │    ├─ FrequencyPatternFactory.java   # 频率类 Pattern 构建
    │    ├─ SequencePatternFactory.java    # 序列类 Pattern 构建（含 NOT）
    │    └─ AggregationWindowFactory.java  # 聚合类 Window 构建
    ├─ model/
    │    ├─ CepRuleSpec.java        # CEP 规则描述（从 rule-api 拉取）
    │    └─ CepFiredEvent.java      # 模式命中事件（发往 rule-eval-svc）
    └─ sink/
         └─ CepFiredEventSink.java  # 写回 RocketMQ
```

---

## CEP 规则模型

### `Rule.kind` 新增枚举值：`CEP_FREQUENCY` / `CEP_SEQUENCE` / `CEP_AGGREGATION`

```java
// rule_definition.kind 新增三个值
CEP_FREQUENCY    // 频率类：N 时间窗内同一主体触发 X 次
CEP_SEQUENCE     // 序列类：事件 A 后 N 分钟内事件 B 发生（含 NOT 模式）
CEP_AGGREGATION  // 聚合类：N 时间窗内某指标累计超阈值
```

### `CepRuleSpec`（存储在 `rule_version.condition_ast`）

**频率类：**
```json
{
  "kind": "CEP_FREQUENCY",
  "eventType": "ORDER_PLACED",
  "subjectField": "userId",
  "windowSize": "PT5M",
  "minCount": 3
}
```

**序列类：**
```json
{
  "kind": "CEP_SEQUENCE",
  "steps": [
    { "eventType": "LOGIN",        "alias": "a" },
    { "eventType": "LARGE_TRANSFER", "alias": "b", "condition": "amount > 50000" }
  ],
  "notEvents": [
    { "eventType": "VERIFY_CODE",  "between": ["a", "b"] }
  ],
  "withinWindow": "PT10M",
  "subjectField": "userId"
}
```

**聚合类：**
```json
{
  "kind": "CEP_AGGREGATION",
  "eventType": "TRANSFER",
  "subjectField": "userId",
  "aggregateField": "amount",
  "aggregateFunc": "SUM",
  "windowSize": "P1D",
  "threshold": 50000
}
```

---

## Flink 作业设计

### 规则热加载

Flink Job 启动时从 `GET /api/v1/cep/rules` 全量拉取所有 `kind IN (CEP_*)` 的 ACTIVE 规则，构建 `PatternStream`。

规则变更时（`RulePublishedEvent` 发到专用 RocketMQ topic），Flink 通过 Broadcast State 热更新 Pattern，不重启 Job。

### 事件路由

```
RuleEvent（from RocketMQ）
  └─ keyBy(tenantId + sceneCode + subjectId)
       └─ 按 kind 分发到三类 Pattern Operator
```

`keyBy` 保证同一主体的事件在同一 Flink task slot 内处理，状态不跨节点。

### 模式匹配输出

Pattern 满足时生成 `CepFiredEvent`：

```java
public record CepFiredEvent(
    String tenantId,
    String sceneCode,
    String subjectId,
    Long   ruleVersionId,
    String matchedKind,        // CEP_FREQUENCY / CEP_SEQUENCE / CEP_AGGREGATION
    Map<String, Object> matchContext,  // 匹配上下文（触发事件 payload 聚合）
    Instant firedAt
) {}
```

`CepFiredEvent` 发到 `rule-cep-fired` RocketMQ topic。

---

## rule-eval-svc 改动

新增 `CepFiredEventConsumer`（RocketMQ 消费者）：

```java
// 消费 CepFiredEvent，构造合成 RuleEvent，投入现有评估链路
RuleEvent syntheticEvent = RuleEvent.builder()
    .tenantId(fired.tenantId())
    .sceneCode(fired.sceneCode())
    .subjectId(fired.subjectId())
    .eventType("CEP_FIRED")
    .payload(fired.matchContext())
    .occurredAt(fired.firedAt())
    .build();
evalService.evaluate(syntheticEvent);
```

现有 `EvalServiceImpl` 的评估链路无需改动，CEP 命中后走相同的 Decision 绑定 + Action 派发流程。

---

## rule-api 新增端点

```
GET /api/v1/cep/rules?tenantId=t1
```

返回所有 `kind IN (CEP_FREQUENCY, CEP_SEQUENCE, CEP_AGGREGATION)` 的 ACTIVE 规则快照，供 Flink Job 启动加载和热更新。

---

## rule-config-svc 改动

`PublishService.publish()` 已有 `SCORECARD` 校验扩展点，追加 CEP 三种 kind 的发布校验：

- `CEP_FREQUENCY`：`windowSize` 合法 ISO 8601 duration + `minCount > 0`
- `CEP_SEQUENCE`：`steps` 至少 2 个 + 每个 step 有 `eventType` + `withinWindow` 合法
- `CEP_AGGREGATION`：`aggregateFunc` ∈ `{SUM, COUNT, AVG, MAX, MIN}` + `threshold` 不为 null

---

## 不做的（范围边界）

- 不支持跨 Scene 的 CEP 规则（事件必须属于同一 sceneCode）
- 不支持 CEP 规则的 dry-run（Flink 有状态流难以回放）
- 不支持 `CEP_SEQUENCE` 的无限时间窗（`withinWindow` 必填，防止状态无限膨胀）
- Flink 集群运维（checkpoint 配置、RocksDB 调优、故障恢复）不在本设计范围内
- 不支持 `condition` 字段的表达式求值（v1 仅支持简单数值比较如 `amount > 50000`，复杂表达式留 v2）

---

## 模块清单

| 模块 | 说明 |
|------|------|
| `rule-cep-job`（新建） | 独立 Flink 应用，不纳入 Spring Boot 主服务 |
| `rule-kernel`（改动） | `Rule.kind` 枚举追加三个 CEP 值 |
| `rule-config-svc`（改动） | `PublishService` 追加 CEP 发布校验 |
| `rule-eval-svc`（改动） | 新增 `CepFiredEventConsumer` |
| `rule-api`（改动） | 新增 `GET /api/v1/cep/rules` 端点 |

---

## 依赖前提

- D12 SCORECARD evaluator 先落地（`Rule.kind` 扩展点模式已验证）
- D13 payloadSchema 先落地（CEP 规则发布时需要 eventType 白名单校验）
- 基础设施：RocketMQ 已有；Flink 集群需新建
