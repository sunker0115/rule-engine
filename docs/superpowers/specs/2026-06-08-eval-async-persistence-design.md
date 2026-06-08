# 评估结果事件化异步持久化 + action 异步派发架构设计

> Status: 已实施并 right-size（2026-06-08）。把评估后的副作用（审计落库 + action 派发）从请求线程搬到**事件驱动异步**，请求线程只做纯计算并同步返回 EvalResult。压测（2026-06-08-load-test）已证明：同步 `session` 两写是吞吐墙（池绑定，本机 ~600 req/s 封顶），trace 已异步则零成本。

> **实施调整（right-size，2026-06-08）**：§2/§4/§5 的"action 持久 outbox（Modulith events-jdbc + EVENT_PUBLICATION + at-least-once）"在实施后**降级为本期内存 best-effort 异步**（`InProcessAsyncDeliveryChannel`）。原因：v1 ActionHandler 为 stub、无真实不可丢副作用，DB outbox 既过度设计（YAGNI）又在压测中成为新瓶颈（@Transactional 持久写顶替 session 写、吞吐无提升）。**保留 `ActionDeliveryChannel` 抽象作 MQ 缝**——待真实「不可丢」handler 落地，经此缝换 Kafka/AMQP 实现 durable，发布方不动。审计内存异步（可丢）维持原设计。right-size 后压测：池=10 吞吐 246→**24,434 req/s（~100×）**，p95 1.52s→12ms。详见 `load-test/README.md`。

## 1. 动机（压测实证）

| 臂 | 吞吐 | Hikari |
|---|---|---|
| 池=10 | 246 req/s | 10/10 · pending 107 |
| 池=100 | 583 req/s | 100/100 · pending 17 |
| 池=300 | 602 req/s | 111/300 · pending 0 |

- **池=300 时连接池已不是墙**（idle 42、pending 0），吞吐仍卡 ~600 → 单机 CPU/MySQL 写入是本机天花板；
- **trace on/off 零差异（583↔589）** → 异步路径零热路径成本，反证**同步 session 两写（INSERT pending + UPDATE final）才是墙**；
- 根因：每请求握 Hikari 连接做多次同步 DB 往返 → 并发上限 ≈ 池大小。

## 2. 关键决策（brainstorm 敲定）

- **边界（Q1）**：本设计聚焦"评估结果的异步持久化（审计）+ action 异步派发"。`EvalEngine` 纯计算不动；`/evaluate` 对外契约不变（仍同步返回 EvalResult）。
- **action 不能同步（约束）**：action 派发（执行 handler）必须移出请求线程。
- **持久性按消费者分离（Q2）**：**审计可丢**（best-effort，内存异步，请求线程 0 DB 写）；**action 不可丢**（持久 outbox，at-least-once）。持久写**仅当"命中且有 action 绑定"时**才发生。
- **action 投递语义（Q3）**：at-least-once + **handler 幂等**（引擎保证至少投递一次，副作用方按 eventId/actionId 自去重）。
- **MQ 分期（Q4）**：本期进程内异步（Spring Modulith，项目已依赖 `spring-modulith-events`）；**预留 `Delivery` 抽象层**，下一期 drop-in Kafka/AMQP，发布方与消费方代码不动。

## 3. 架构

```
EvalServiceImpl.doEvaluate（请求线程，纯内存）
  match(SceneRuleIndex) → interpret(Executor) → 算出 EvalResult
  → sessionId = snowflake（本地生成，不依赖 DB 自增）
  → 发布出站事件（无候选短路 miss 直接 return，不发任何事件，保留现状）：
       ① AuditRecorded                          —— 内存异步，best-effort，可丢
       ② DispatchActionsCommand（仅命中且有 action 绑定）—— 经 Delivery 抽象，持久 at-least-once，不可丢
  → 同步 return EvalResult ✅（不等落库、不等 action）

      ①─▶ AuditPersister（@Async，有界队列+批量）
            → 落 evaluation_session（一次 INSERT 终态）/ node_trace / action_execution（审计行）
      ②─▶ ActionDispatcher（异步，at-least-once）
            → 执行 ActionHandler（幂等）+ 回写 action_execution 结果
```

**事件模型 = 两个事件**（非单事件双监听）：两种持久性天然分到两个事件，且**持久写只在有 action 时发生**（无 action 评估 0 DB 写，吞吐顶满）。

## 4. Delivery 抽象（MQ 的预留缝）

`DispatchActionsCommand` 不直接耦合 Modulith，经唯一投递契约：

```java
/** action 派发事件的可靠投递契约：保证 at-least-once 投递到 ActionDispatcher。 */
public interface ActionDeliveryChannel {
    void deliver(DispatchActionsCommand event);
}
```

- 本期实现 `ModulithOutboxDeliveryChannel`：用 Spring Modulith 持久事件 / event publication registry 做 outbox（未完成项重启后重投）。
- 下一期实现 `KafkaDeliveryChannel` / `AmqpDeliveryChannel`：换 Bean 即可，**发布方与 `ActionDispatcher` 不动**。
- 审计侧不经此层（可丢，纯 `@Async` 事件即可）。

## 5. 幂等 + 持久性语义

- **action**：`DispatchActionsCommand` 携带 `eventId`+`actionId`；at-least-once 投递，崩溃重投时 **handler 按此自去重**，不产生重复副作用。
- **审计**：`AuditPersister` 有界队列 + 批量；溢出/崩溃丢最近未落库的审计（**已接受**）；`evaluation_session` 唯一键 `uk_tenant_event` 防重复行。
- **重复业务事件**（同 eventId 提交两次）：action 由 handler 幂等兜住；审计可能多一条或被唯一键挡掉，可丢不纠结。
- **关机**：`AuditPersister` 关机尽力 flush；`DispatchActionsCommand` 的 outbox 未完成项重启后由 registry 重投（不丢）。
- **session 两写合一**：消费侧不再 PENDING→UPDATE，直接 INSERT 终态（status=HIT/MISS/ERROR）一次写入；sessionId 由请求线程 snowflake 生成，node_trace/action_execution 据此关联。

## 6. 组件与边界

**新增（rule-eval-svc 内，单一职责）：**
- `EvaluationEventPublisher`：评估完按"命中且有 action"决定发 ①/②。
- `AuditRecorded` / `DispatchActionsCommand`：领域事件 record。
- `AuditPersister`：消费 ①，有界队列+批量落库（复用/参照 `TraceWriterDbImpl` 模式）。
- `ActionDispatcher`：消费 ②，复用现 `ActionDispatchService` 的派发逻辑（移到异步、加幂等）。
- `ActionDeliveryChannel` + `ModulithOutboxDeliveryChannel`。
- `SnowflakeId`（或复用项目既有 id 生成；无则新增最小实现）。

**改造：**
- `EvalServiceImpl.doEvaluate`：删除同步 `sessionWriter.*` / `traceWriter.write` / `actionDispatchService.dispatch`，改为发事件；id 改 snowflake。

**不动：** `EvalEngine`、`SceneRuleIndex`、各 Executor、`/api/v1/rule/evaluate` 对外契约、PUSH `/event`（其已异步，本设计统一其落库路径）。

## 7. 测试 + 验收

- 发布点单测：**无候选短路 miss → 不发任何事件（保留现状 miss 不落库）**；评估后 MISS（有候选未命中）→ 发 `AuditRecorded`（落 session status=MISS）；命中无 action → 只发 `AuditRecorded`；命中有 action → 发 `AuditRecorded` + `DispatchActionsCommand`。
- `AuditPersister` 批量落库单测（session 单次 INSERT 终态 + trace + 审计行）。
- `ActionDispatcher` 幂等重投单测（同 eventId/actionId 重投 → handler 只产生一次副作用）。
- `ModulithOutboxDeliveryChannel` at-least-once 单测（消费抛异常 → 重投；重启 → 未完成项重投）。
- 端到端：评估同步返回 → 异步最终一致（session/trace 落库）+ action 执行恰好语义一次（幂等）。
- **验收（回归压测）**：用 `load-test/` 同一套 3 臂复测；期望**无 action 评估请求线程 0 DB 写、吞吐冲破 600 单机墙**；有 action 评估付 1 次 outbox 写。

## 8. 非目标（本期不含）

- MQ broker 实现（Kafka/AMQP）：仅预留 `Delivery` 抽象，实现留下一期。
- action 派发的重试退避策略细化 / DLQ：本期 at-least-once + 幂等，退避/死信留 MQ 期。
- PUSH 路径背压重设计、native、SLO 验收。
- 现有 `evaluation_session` 表结构大改（沿用，写入方式改为单次终态 INSERT）。

## 9. 风险

- **审计 best-effort 丢失**：崩溃/溢出丢最近审计——**已是显式接受的取舍**；如个别场景需强审计，后续可让其也走 outbox（同 action 路径），但默认不做。
- **Modulith 持久事件的 outbox 写仍是 1 次 DB 写**：有 action 的评估并非 0 写；但相比当前 4 写（含 UPDATE）仍数量级改善，且是 action 不丢的必要成本。
- **snowflake id**：需保证单机/多实例不冲突（worker id 配置）；无现成实现则引入最小依赖或自写。
- **幂等责任在 handler**：v1 handler 为 stub，接真实 handler 时必须落实 eventId/actionId 去重——在 SPI 文档显式要求。
