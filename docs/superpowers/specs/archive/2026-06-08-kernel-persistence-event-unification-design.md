# 内核落库统一事件化(领域事件 + 单一投递缝)设计

> Status: 设计已批准(2026-06-08),待写实现计划。把内核四条落库统一成「领域事件 → 单一投递缝 → 各 persister」,事件自带 durability,投递层按需路由。进程内实现复用 Spring 异步事件;预留唯一 MQ 缝(`DomainEventPublisher`),durable 投递落地时只换实现,发布方与 persister 不动。是 `2026-06-08-eval-async-persistence-design` 的推广。greenfield 无生产数据,放手重构。

## 1. 动机

当前四条落库机制不统一:
- `evaluation_session` + `node_trace`:事件驱动 + 异步(`AuditRecorded` 经 Spring `ApplicationEventPublisher` → `AuditPersister` 批量;trace piggyback 同一事件)。
- `action_execution`:**内联**在 `ActionDispatchService.dispatch`(执行 handler 后同步 insert,无独立事件)。
- `dry_run_session`:**同步两阶段**(`EvalSessionWriter.insertDryRunPending` → `updateDryRunFinal`)。

且投递机制分裂:audit/trace 走裸 Spring 事件(无 MQ 缝),action 触发走 `ActionCommandChannel`(为 MQ 留的缝)。MQ durable 投递落地时,这种分裂要逐条改造。**目标**:统一成单一事件契约 + 单一投递缝,让 MQ 来时只换一处 transport 实现。

## 2. 契约(核心抽象)

新包 `rule-eval-svc/.../internal/event`:

```java
/** 领域事件统一标记;每个事件声明 durability,供投递层路由(进程内为元数据,MQ 决定 topic/ack)。 */
public interface DomainEvent {
    Durability durability();
}

/** 投递可靠性等级。 */
public enum Durability { BEST_EFFORT, AT_LEAST_ONCE }

/** 唯一发布缝。进程内 / MQ 各一实现,发布方与 persister 不感知 transport。 */
public interface DomainEventPublisher {
    void publish(DomainEvent event);
}
```

这是 MQ 的唯一缝:MQ 落地时实现 `MqDomainEventPublisher implements DomainEventPublisher`,按 `event.durability()` 路由 topic + ack 模式,发布方/persister 零改动。

## 3. 事件清单(3 个领域落库事件)

| 事件 | durability | 落库目标 | 生产者 |
|---|---|---|---|
| `AuditRecorded` | BEST_EFFORT | evaluation_session + node_trace | `doEvaluate` 主路径 |
| `ActionExecuted` | AT_LEAST_ONCE | action_execution | `ActionDispatchService` 执行后 |
| `DryRunRecorded` | BEST_EFFORT | dry_run_session + dry-run trace | `doEvaluate` dry-run 路径 |

- 现有 `AuditRecorded`(record)沿用,实现 `DomainEvent` 返回 `BEST_EFFORT`。
- `ActionExecuted`:新 record,携带 sessionId/tenantId/eventId/actionId(=actionType,确定化)/actionType/decisionCode/ActionResult,`AT_LEAST_ONCE`。
- `DryRunRecorded`:新 record,携带 dry-run sessionId/event/ruleVersionId/EvalResult/EvalContext,`BEST_EFFORT`。
- `DispatchActionsCommand`(现有)**保留且语义不变**:它触发「去执行 action」(驱动 dispatch),与「落库事件」正交,不并入本契约;它仍走 `ActionCommandChannel`(action 触发缝)。

## 4. Persister(3 个,transport 无关)

每个 persister 暴露纯方法 `accept(XxxEvent)` 承载落库逻辑;进程内用 `@EventListener` 桥接到该方法,MQ 落地时由 MQ 消费者调同一 `accept`(落库逻辑零改)。

- `AuditPersister`(已存在):`accept(AuditRecorded)` = 现 `onAudit`(session 单次终态 INSERT + trace 旁路 + 现有有界队列/批量/虚拟线程消费)。改:`onAudit` 方法签名/注解对齐(吃 `AuditRecorded`,仍 `@EventListener`)。
- `ActionExecutionPersister`(**新**):`accept(ActionExecuted)` → insert action_execution;`DuplicateKeyException` 降 debug(uk_idempotency 行级 backstop,从 dispatch 搬来)。
- `DryRunPersister`(**新**):`accept(DryRunRecorded)` → 单次终态 INSERT dry_run_session(status=HIT/MISS/ERROR)+ dry-run trace + context_snapshot。**吸收 `EvalSessionWriter` 的 dry-run 逻辑(serializeSnapshot 等)后删除 `EvalSessionWriter`**。

## 5. 生产方改造(只发布,不内联落库)

- `EvalServiceImpl.doEvaluate` 主路径:沿用 `publisher.publish(AuditRecorded)`(现 publishAudit 收敛到统一 publish);命中有决策仍发 `DispatchActionsCommand` 触发派发。
- `ActionDispatchService.dispatch`:claim → `executeHandler` → **`publisher.publish(new ActionExecuted(result...))`**(替掉内联 `insertExecution`)→ `FAILED` 时 `guard.release`。记录与执行解耦;`insertExecution`/`ActionExecutionMapper` 注入移到 `ActionExecutionPersister`。
- dry-run 路径:计算后 `publisher.publish(new DryRunRecorded(...))`,**同步返回 EvalResult(含 nodeTrace)给调用方**;dry_run_session 落库转异步(调用方结果在响应里,不依赖查表)。删除 `EvalSessionWriter` 及其同步两阶段 + `insertDryRunPending/updateDryRunFinal` 调用。
- `EvaluationEventPublisher`(现 publishAudit/publishActions 两方法):**删除**;`EvalServiceImpl` 改注入 `DomainEventPublisher`,直接 `publish(...)`(audit 事件)。`DispatchActionsCommand` 仍经现有 `ActionCommandChannel` 发(触发缝,见 §3),不走 DomainEventPublisher。
- `DryRunTraceWriter`:dry-run trace 写入移入 `DryRunPersister`(由其调用)。

## 6. 进程内投递实现 + MQ 就绪

- `InProcessDomainEventPublisher`(`@Component implements DomainEventPublisher`):`publish(e)` → `applicationEventPublisher.publishEvent(e)`。复用 Spring 事件 + persister 的 `@EventListener`(AuditPersister 现有的有界队列/批量虚拟线程消费维持)。
- 进程内 durability 当前都按 best-effort 异步实现(右尺寸现状,无进程内 durable);`durability()` 是 MQ 用的路由元数据。
- MQ 落地(本期非目标):`MqDomainEventPublisher` 按 durability 发不同 topic;MQ 消费者反序列化 → 调对应 persister 的 `accept`。`@EventListener` 桥接届时按 profile/条件关闭。

## 7. 失败 / durability / 一致性语义

- **BEST_EFFORT**(audit/dry-run):溢出/崩溃可丢;`evaluation_session.uk_tenant_event`、dry_run 唯一键防重复行(同现状)。
- **AT_LEAST_ONCE**(action):进程内仍 best-effort(现状),契约声明意图,MQ 兑现真 at-least-once;`action_execution.uk_idempotency` + 缓存 claim(已实现)保幂等;`ActionExecutionPersister` insert 撞 uk → 降 debug。
- 投递/persister 内异常隔离,不影响 `EvalResult` 与请求线程(同现状)。
- `ActionExecuted` record 经 Jackson 序列化(进程内不序列化,MQ 才序列化)——若进程内 persister 不序列化则 native 无需 hints;若将来 MQ 序列化需补 `@RegisterReflectionForBinding`(同 `HitDecisionView` 模式)。

## 8. 测试 + 验收

- 契约:`DomainEvent.durability()` 各事件返回正确等级;`InProcessDomainEventPublisher.publish(e)` → Spring 事件发出 → 对应 persister `accept` 被调(可用 ApplicationEvents 测试或 mock publisher 验证)。
- `AuditPersister`:沿用现有单测(吃 AuditRecorded、session 终态 + trace + category/score + blockedBy)。
- `ActionExecutionPersister`(新):`accept(ActionExecuted)` → insert action_execution(actionId=actionType、status、uk 降级 debug)。
- `DryRunPersister`(新):`accept(DryRunRecorded)` → dry_run_session 单次终态 + trace + snapshot;吸收原 `EvalSessionWriterTest` 的 dry-run 断言。
- 生产方:`doEvaluate` 主/dry-run 各发对应事件(mock DomainEventPublisher 验 publish);`ActionDispatchService` 发 `ActionExecuted`(替内联 insert),claim/release 不变。
- 回归:删 `EvalSessionWriter` 后全量 kernel+eval-svc 绿;dry-run 端到端(异步落库轮询);一次 native 构建(新事件 record 若 MQ 序列化才需 hints,进程内 boot 验证不破)。

## 9. 非目标(本期不含)

- MQ broker 实现(`MqDomainEventPublisher` / topic / 消费者):仅留 `DomainEventPublisher` 缝。
- 进程内真 durable(outbox 重投):进程内维持 best-effort,durable 留 MQ。
- 事件 schema 版本化 / 跨服务事件总线:本期单进程内聚。
- 现有表结构变更:沿用(session/action_execution/dry_run_session/node_trace 表不动)。
