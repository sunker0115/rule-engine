# Action 投递 best-effort 化 — 设计待办

> 状态:方向已定,待执行(2026-06-09 讨论达成)。诚实化 + 砍伪能力 + 真实化一个 handler。

## 决策

- **当前**:Action 命中后的投递语义为 **best-effort fire-and-forget**——命中 → 派发 → 进程内队列异步跑 handler → 落 `action_execution`;队列满 / 进程重启会丢;不重试、不补偿、不保证投递。
- **未来**:可靠投递接 **MQ**(at-least-once / 重试 / 死信由 MQ 基础设施保证),业务补偿(撤销已执行 action)走 **saga / 补偿事务**,届时重新设计。
- **中间不保留任何应用层半吊子可靠投递**——要么诚实 best-effort,要么上 MQ,不要中间态。因此当前砍掉所有应用层 retry/补偿建模。

## 落地清单

### 1. 砍 retry / 补偿(列 + 代码)
- **依据**:未来可靠投递走 MQ,重试由 MQ 消费端保证、不在 `action_execution` 列;业务补偿走 saga、不复用 `compensate()` SPI。这套应用层 retry/补偿在未来方案下**确定不复用**(非预留),与 `payload_template` 同类 → 砍。
- **DB**(新 Flyway 迁移):`action_execution` drop 列 `retryable` / `retry_count` / `compensated` / `compensated_at` / `compensated_by`,drop 索引 `idx_status_retryable`。
- **SPI**:删除 `ActionHandler.compensate()` 默认方法(保留 `execute` / `dryRun`)。
- **代码**:`ActionExecutionPersister` / `ActionExecutionEntity` 里 retry/compensate 字段与映射删除;`QUEUE_OVERFLOW` errorCode 删除。
- **保留**:`action_execution` 主表(best-effort 仍记执行结果)、`uk_idempotency` 唯一键、`dryRun` SPI。

### 2. 砍进程内幂等缓存
- 删除 `ActionIdempotencyGuard` / `CaffeineActionIdempotencyGuard` / `ActionIdempotencyProperties`,以及 `ActionDispatchService` 的 `claim` / `release`。
- **依据**:① `release` 的"失败让后续重发重试"语义随 retry 砍而失效;② 重复防护 DB 已有两层且跨实例可靠——评估入口 `uk_tenant_event` + 落库 `uk_idempotency`;③ 进程内 Caffeine 不可靠(重启失效/多实例不互斥),未来 MQ 消费幂等由消费端 DB 保证,这个进程内实现确定重写、不复用。

### 3. 真实化 SendAlertHandler
- `SEND_ALERT` 的 `execute` 由 stub `return success` 改为接**真实 HTTP webhook**:可配 URL(`engine.rule.action.send-alert.*`)、短超时、POST 告警载荷;失败返回 `status=FAILED`(best-effort,不重试)。
- `dryRun` 保留预览(不实发)。
- `BlockTransactionHandler` 留 stub(对接外部交易系统是更重的真集成,本轮不做)。

### 4. 诚实化注释 / 文档
- `EvalServiceImpl:99` 注释 "at-least-once 不丢" → "当前 best-effort fire-and-forget;可靠投递未来接 MQ"。
- `02-runtime` / `01-concepts` / `00-decisions`(D18/D20)里"持久投递 / 重试队列 / QUEUE_OVERFLOW / compensate 补偿"的承诺,改成 best-effort 口径并注明未来 MQ 方向;`00-decisions` 追加一条收敛决策。
- `05-storage` action_execution 表去掉 retry/compensate 列说明。

### 5. 队列满可观测
- `InProcessAsyncCommandChannel` 队列满现为静默 `offer` 丢弃 → 保留丢弃,但加**丢弃计数 metric + WARN 日志**(best-effort 可丢,但不能无声)。

## 收尾
- 全量 `clean test`。
- `doc-consistency-review` 扫 00/01/02/05 自洽。
- 改 docs + rule-* 代码,显式调用 `rule-engine-reviewer` 审代码↔文档对齐。

## 取舍(已接受)
- best-effort:命中后 action 不保证执行(队列满/重启丢失),不重试不补偿。强保证留给未来 MQ。
- 砍幂等缓存:重复派发防护降级为 DB uk(落库去重),不防"handler 被重复执行"——best-effort 下接受;未来 MQ 消费端再做幂等。

## 未来方向(钉死,免得有人把应用层 retry/补偿加回来)
- 可靠投递 = **MQ**(at-least-once 由 MQ 保证),不在应用层做重试表/重发逻辑。
- 业务补偿 = **saga / 补偿事务**,不复用本次删除的 `compensate()` SPI / `compensated` 列,届时重新设计。

## 与其它待办关系
- 独立于 `payload-direct-reference`、`pregate-convergence`。
- handler 全面真实化(BlockTransaction 等)是更后面的事,本轮只真实化 SendAlert 一个。
