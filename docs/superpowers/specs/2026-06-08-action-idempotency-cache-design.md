# Action 幂等缓存(claim-before-execute)设计

> Status: 设计已批准(2026-06-08),待写实现计划。让重复 eventId 不再重复执行 action handler:用带 TTL 的进程内缓存做 claim-before-execute 的同步去重,确定化 actionId 兑现 schema 既有的 `uk_idempotency`,DB 唯一键留行级 backstop,action_execution 写库仍异步 best-effort。

## 1. 动机(现状 gap)

`action_execution` 表 schema 早已设计 action 幂等:`UNIQUE KEY uk_idempotency (tenant_id, event_id, decision_code, action_id)`(`event_id` 注释「冗余字段,用于幂等 UK」)。但 `ActionDispatchService.dispatch` 把它架空了:

1. `String actionId = UUID.randomUUID().toString()`(`ActionDispatchService.java:58`)—— actionId **每次派发都是新随机 UUID**,故 uk `(tenant, event, decision, action_id)` **永不碰撞**,幂等键形同虚设。
2. `executeHandler(...)` 在 `insertExecution(...)` **之前**(`:59-60`)—— 先执行副作用再记录,即便 uk 碰撞也已执行过。
3. insert 异常 `catch` 静默 warn(`:96-99`)。

后果:重复 eventId(常见来源 = 上游/客户端**重试**)→ 重新评估 + **重新执行 handler** + 多插一行 action_execution。`handler 自去重` 也使不上那个 uk(actionId 对它随机)。v1 handler 为 stub 无真实副作用,故现在不显现,但接真实 handler 后即为重复副作用 bug。

## 2. 已批准的关键决策

- **缓存做同步去重决策**(快、不查库),DB action_execution 写库仍异步 best-effort 只当审计留痕——两者职责分离。
- **actionId 确定化 = `binding.actionType()`**:`scene_action_binding` 有 `uk_scene_action (scene_id, action_type)`,一个 scene 内 actionType 唯一;事件只属一个 scene。故 `(tenant, eventId, decisionCode, actionType)` 是稳定唯一的逻辑 action 键,无需给 DTO 加 binding id。
- **claim-before-execute + 失败释放(方案 b)**:执行前原子占坑(挡并发双执行);handler 失败 → 释放占坑(让「重复 eventId = 重试」能重新执行到成功);成功 → 保留占坑至 TTL 过期。
- **TTL 配置化**:`engine.rule.action.idempotency.ttl-seconds`(默认 600)。
- **缝抽象**:`ActionIdempotencyGuard` 接口,进程内 Caffeine 实现;升级 Redis/durable 只换 Bean,派发方不动(同 `ActionCommandChannel` 手法)。
- **best-effort 显式接受**:进程内缓存重启丢 / 多实例不共享,这两种情况 TTL 窗口内重复缓存挡不住、会重复执行;DB uk 留行级 backstop;硬保证留 Redis 升级。

## 3. 组件

**新增(rule-eval-svc):**
- `ActionIdempotencyGuard`(SPI 风格接口,internal/action 或 api/spi):
  ```java
  public interface ActionIdempotencyGuard {
      /** 原子占坑。返回 true=占到(可执行);false=已被占(TTL 内已派发,跳过)。 */
      boolean claim(String key);
      /** 释放占坑(handler 失败时调,允许后续重发重试)。 */
      void release(String key);
  }
  ```
- `CaffeineActionIdempotencyGuard`(`@Component`,`@ImportRuntimeHints(CaffeineNativeHints.class)`):
  - `Cache<String, Boolean>`,`Caffeine.newBuilder().maximumSize(props.maxSize()).expireAfterWrite(props.ttlSeconds(), SECONDS)`。
  - `claim`：`cache.asMap().putIfAbsent(key, Boolean.TRUE) == null`。
  - `release`：`cache.invalidate(key)`。
- `ActionIdempotencyProperties`(`@ConfigurationProperties("engine.rule.action.idempotency")`,Lombok `@Getter/@Setter`):`long ttlSeconds = 600;` `long maxSize = 100_000;`。

**改造:**
- `ActionDispatchService`：构造器注入 `ActionIdempotencyGuard`;`dispatch` 内 actionId 改 `binding.actionType()`、加 claim/release 流程(见 §4)。
- `EvalAutoConfiguration`：`actionDispatchService(...)` @Bean 增加 guard 参数;启用 `ActionIdempotencyProperties`(`@EnableConfigurationProperties` 或等价)。

**不动:** ActionHandler SPI、ActionResult、action_execution 表结构(uk 已存在)、InProcessAsyncCommandChannel、请求热路径。

## 4. 数据流(`ActionDispatchService.dispatch`)

```
for decision in hitDecisions:
  for binding in bindings:
    String actionId = binding.actionType();                       // 确定化(替随机 UUID)
    String key = tenantId + ":" + eventId + ":" + decision.code() + ":" + actionId;
    if (!guard.claim(key)) { log.debug("action 幂等跳过 key={}", key); continue; }  // 不执行、不插库
    ActionResult result = executeHandler(actionId, binding, decision);
    if (result.status() == ActionStatus.FAILED) guard.release(key);   // 失败 → 释放,允许重发重试
    insertExecution(sessionId, tenantId, eventId, actionId,
            binding.actionType(), decision.code(), result);           // 审计行(已有 try-catch;best-effort + uk backstop)
```

- `claim` 在**异步派发消费者**(InProcessAsyncCommandChannel 消费 → dispatch),off 请求热路径,不影响请求吞吐。
- `executeHandler` 维持现状不额外捕获异常(handler 约定返回 ActionResult,不抛);极端情况 handler 抛异常则向上传播(同现状),占坑到 TTL 过期(不阻塞超过 TTL 的重发)——不为不存在的场景加 catch(YAGNI)。
- `SUCCESS` / `SKIPPED(NO_HANDLER)` → 不 release(成功无需重试;无 handler 重试也没用),占坑到 TTL 过期。
- claim 失败的重复 → skip handler **且 skip insert**(原始那次已留审计行;确定 actionId 下重复 insert 也撞 uk)。

## 5. 失败 / 并发 / 一致性

- **并发重复**：`putIfAbsent` 原子 → 仅一个 claim 成功执行,其余 skip,不双执行。
- **失败释放**：`result.status()==FAILED` → `release` → 后续重复 eventId 重新 claim 执行;若一直失败,依赖上游 at-least-once 重发驱动重试(系统当前无内部重试器,重发即重试)。handler 罕见抛异常时占坑保留至 TTL 过期(不额外捕获,同现状)。
- **DB uk backstop**：actionId 确定化后,缓存漏掉的重复(重启/多实例)在 insert 撞 `uk_idempotency` → `DuplicateKeyException` 捕获并降级 debug,挡重复审计行(挡不住已执行的副作用 → 由 Redis 升级解决)。
- **best-effort 边界(显式接受)**：进程内缓存重启丢 / 多实例不共享 → 这两种情况 TTL 窗口内重复会重复执行 handler。升级 `RedisActionIdempotencyGuard`(SETNX + DEL,SET 带 TTL)消除,接口不变。

## 6. 测试 + 验收

- **CaffeineActionIdempotencyGuard**：claim 首次 true、同 key 再次 false;release 后可重新 claim true;(可选)极短 TTL 过期后可重新 claim。
- **ActionDispatchService**：
  - 同 (eventId, decisionCode, binding) 二次 dispatch → handler `execute` 仅一次(`verify(handler, times(1))`)。
  - 插入的 `action_execution.actionId == binding.actionType()`(确定化,非随机 UUID)。
  - handler 返回 FAILED → `guard.release(key)` 被调 → 再次 dispatch 重新执行 handler。
  - handler SUCCESS → 再次 dispatch skip(handler 不再执行、不再插库)。
- **既有 `ActionDispatchServiceTest`**：随机 actionId → 确定化 actionType 后,更新相关断言;补 guard mock。
- **全量**：rule-eval-svc 模块测试全绿。

## 7. 非目标(本期不含)

- Redis / durable 幂等实现:仅留 `ActionIdempotencyGuard` 缝,实现留升级期。
- 内部 action 重试器 / 退避 / DLQ:本期靠上游 at-least-once 重发驱动重试。
- action_execution 表结构变更(uk 已存在,沿用)。
- 跨实例 / 重启的硬 exactly-once:本期 best-effort,显式接受。
