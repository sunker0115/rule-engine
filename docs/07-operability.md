# 07 — 可运维

> **位置定位**：本文档承载 rule-engine 的**上线后视角**——幂等 / 审计 / 试算 / 灰度 / 监控 / 告警 / 可用性策略 / 运维参数默认值。
>
> **前置阅读**：[`00-decisions.md`](./00-decisions.md) D6 / D11 / D14 / D15 / D17 / D21、[`01-concepts.md`](./01-concepts.md) §3.11 audit_log + §3.14 Pre-Gate
>
> **解决什么疑问**："上线后出问题怎么排？""怎么灰度发布？""怎么 dry-run 验证？""有哪些 Prometheus 指标？""告警阈值怎么定？""v1 的可用性边界是什么？""引擎参数默认值在哪儿配？"
>
> **职责边界**——
> - ✅ 幂等 / 审计 / 试算 / 灰度 / 监控 / 告警 / 可用性策略 / 运维参数默认值集中表
> - ❌ 不写决策权衡（→ 00-decisions）、不写概念字段语义（→ 01-concepts）、不写运行时调度（→ 02-runtime）、不写 DDL（→ 05-storage）、不写前端 UI（→ 06-frontend）

---

## 一、文档状态

| 章节 | 状态 |
|------|------|
| §二 幂等 | ✅ |
| §三 EvaluationSession 落库策略 | ✅ |
| §四 dry-run 链路 | ✅ |
| §五 灰度 | ✅ |
| §六 Prometheus 指标清单 | ✅ |
| §七 告警阈值 | ✅ |
| §八 可用性策略汇总 | ✅ |
| §九 运维参数默认值表 | ✅ |

---

## 二、幂等

### 双层保障（D11）

| 层 | 实现 | 失效场景 |
|----|------|---------|
| 上半层 | `SET rule:session:{tenantId}:{eventId} <evalResultJson> NX EX 3600` | Redis 宕机 / 键过期 |
| 下半层 | `evaluation_session` UK `(tenant_id, event_id)` | 分布式竞争时最终一致 |

**流程：**
1. 评估前先 Redis SET NX：命中 → 返回缓存 EvalResult，不再评估
2. 未命中 → 正常评估 → evaluation_session INSERT
3. INSERT 遇 DuplicateKeyException → SELECT 已有行 → 返回已有 EvalResult

**幂等范围**：一次"评估"（Matcher + Pre-Gate + AST + 记录 session）幂等；Action 派发**不**幂等（由 ActionHandler 自行保证 execute() 幂等，见 04-extension §三）。

---

## 三、EvaluationSession 落库策略

| 操作 | 模式 | 原因 |
|------|------|------|
| `evaluation_session` 行 INSERT | **同步事务** | 幂等 UK 需先存在；量小（1 行/次），P99 延迟可忽略 |
| `node_trace` 批 INSERT | **异步批写** | 量大（10-1000 行/次）；旁路观察通道，失败降级丢弃，不影响主流程 |
| `action_execution` INSERT | **异步** | Action 派发本身异步，执行结果与评估线程解耦 |

TraceWriter 队列参数（建议默认值，可 `engine.rule.trace.*` 配置覆盖，见 §九）：

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `queue.capacity` | 100,000 | 内存 LinkedBlockingQueue 容量 |
| `batch.size` | 500 | 每批 INSERT 行数 |
| `flush.interval.ms` | 200 | 超时强制 flush |
| `consumer.threads` | 2 | 批写消费线程数 |

---

## 四、dry-run 链路

dry-run 走完整评估链路（Matcher / Pre-Gate / EvalContext / AST），但：
- **不派发 Action**（Dispatcher 短路）
- **不写** `evaluation_session` / `node_trace` prod 表
- **写** `dry_run_session` / `dry_run_node_trace`（隔离表，D7）
- 返回完整 `nodeTrace`（AST 每个节点的 result / actualValue / errorCode）

**入口**：`POST /api/v1/rule/dry-run`（PULL 模式同步返回，见 10-api-contract §三）

**用途**：
1. 规则发布前验证：编辑器内构造 mockEvent → 查看每个节点求值结果
2. 线上排障：用历史事件 eventId 重放 → 对比 trace 差异

**dry_run_session 保留期**：默认 7 天（见 §九 `engine.rule.retention.dry-run-session-days`）。

---

## 五、灰度

### 灰度算法（ROLLOUT Gate）

```
bucket = (murmur3_32(subjectId + ":" + ruleVersionId) & 0x7FFFFFFF) % 100
pass = bucket < rollout.percentage
```

- `ruleVersionId` 加入 hash：同一 subject 在不同版本间 bucket 独立（防止切版本导致 bucket 漂移）
- murmur3_32 保证分布均匀；hash seed 固定（见 §九 `engine.rule.rollout.hash-seed`），上线后不要改，否则桶分布漂移

### 灰度验证流程

1. 新版规则发布为 `ACTIVE`，ROLLOUT Gate 设 `percentage=5`
2. 监控 `evaluation_session.error_code` 分布 + Action 派发成功率（5% bucket）
3. 对账无异常 → percentage 逐步调至 100
4. 全量后将旧版 rule_version.status 改为 `SUPERSEDED`

### 灰度回退

将 ROLLOUT.percentage 调回 0（不删规则）→ 新流量全部走其他规则。若需立即停用，将 rule_definition.status 改为 DISABLED（Matcher 倒排索引热摘除，≤15s 生效）。

---

## 六、Prometheus 指标清单

所有指标前缀 `rule_engine_`，label 统一含 `tenant_id` / `scene_code`。

| 指标名 | 类型 | labels | 说明 |
|--------|------|--------|------|
| `rule_engine_eval_total` | Counter | `result`(HIT/MISS/BLOCKED/ERROR) | 评估结果分布 |
| `rule_engine_eval_blocked_total` | Counter | `gate_type`(ROLLOUT/WHITELIST/BLACKLIST/RATE_LIMIT/MUTEX) | Pre-Gate 按类型拦截计数，对应 `blocked_by` 枚举（D22） |
| `rule_engine_eval_duration_ms` | Histogram | `scene_code` | 评估 P50/P95/P99 延迟 |
| `rule_engine_metric_fetch_duration_ms` | Histogram | `source_type`, `metric_code` | MetricSource 取数延迟 |
| `rule_engine_metric_fetch_errors_total` | Counter | `source_type`, `error_type` | 取数失败计数 |
| `rule_engine_action_dispatch_total` | Counter | `action_type`, `status` | Action 派发结果 |
| `rule_engine_action_duration_ms` | Histogram | `action_type` | Action 执行延迟 |
| `rule_engine_trace_queue_size` | Gauge | — | TraceWriter 队列深度 |
| `rule_engine_trace_queue_overflow_total` | Counter | — | trace 丢弃计数（队满） |
| `rule_engine_rule_version_cache_hit_total` | Counter | `scene_code` | Matcher 内存命中次数 |
| `rule_engine_idempotency_hit_total` | Counter | `layer`(REDIS/DB) | 幂等命中次数 |

---

## 七、告警阈值

建议值，不强制；业务侧可按实际基线调整。

| 告警规则 | 阈值 | 级别 | 说明 |
|---------|------|------|------|
| 评估 P99 延迟 | > 200ms 持续 5min | WARNING | 风控场景目标 < 100ms P99 |
| 评估 ERROR 率 | > 1% 持续 2min | WARNING | METRIC_FETCH_FAIL / CONDITION_EVAL_ERROR |
| 评估 ERROR 率 | > 5% 持续 1min | CRITICAL | 批量失败 |
| trace 队列溢出 | > 0 次/min 持续 5min | WARNING | 写入跟不上评估速率 |
| Action 失败率 | > 5% 持续 5min（按 action_type） | WARNING | |
| MetricSource P99 | > 500ms 持续 5min | WARNING | 按 source_type 分组 |

---

## 八、可用性策略汇总

### v1 SPOF 清单与降级矩阵

| 依赖 | 失效影响 | v1 降级策略 |
|------|---------|------------|
| MySQL | 无法写 evaluation_session，评估阻塞 | 评估入口返回 503；Redis 幂等层仍可检查重复 |
| Redis | 幂等上半层失效 | 降级走 DB UK 幂等；metric cache 全部击穿 DB / 外部服务 |
| MetricSource (EXTERNAL_HTTP) | 取数超时 | D15 单节点降级 false，EvalResult.errorCode=METRIC_FETCH_FAIL |
| MetricSource (SQL_AGGREGATE) | DB 慢查询 / 连接池耗尽 | 同上；建议对 SQL 指标设 cache_ttl > 0 |
| TraceWriter 队列满 | trace 行丢弃 | trace 丢弃 + counter 告警；**不影响** EvalResult |
| ActionHandler 外部系统不可用 | execute() 超时 | TIMEOUT retryable=true，入重试队列 |

### v1 不做的高可用（见 08-evolution）

- evaluation_session 异步化（§2.15）
- 嵌入式 SDK 模式（§2.14，无跨进程网络依赖）
- 节点级 trace 冷热分级（§2.5）

---

## 九、运维参数默认值表

所有参数均可通过 Spring 配置（`application.yml` 或配置中心）覆盖，命名空间 `engine.rule.*`。

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `engine.rule.matcher.cache-refresh-interval-seconds` | 15 | Matcher 倒排索引热更间隔（D17 最终一致窗口） |
| `engine.rule.scene.watch-interval-seconds` | 30 | Scene 配置热加载间隔 |
| `engine.rule.idempotency.redis-ttl-seconds` | 3600 | 幂等 Redis key 过期时间 |
| `engine.rule.trace.queue-capacity` | 100000 | TraceWriter 队列容量 |
| `engine.rule.trace.batch-size` | 500 | 批写行数 |
| `engine.rule.trace.flush-interval-ms` | 200 | 强制 flush 间隔 |
| `engine.rule.trace.consumer-threads` | 2 | 批写消费线程数 |
| `engine.rule.context.build-timeout-ms` | 500 | EvalContext 构建超时（含 Subject 加载 + metric 批拉，超时整 session 失败，D25） |
| `engine.rule.subject.load-timeout-ms` | 200 | SubjectLoader 单次加载超时（D25，超出则 EvalContext 失败） |
| `engine.rule.metric.default-cache-ttl-seconds` | 60 | metric 取数结果缓存 TTL（per-metric 可覆盖） |
| `engine.rule.action.default-timeout-ms` | 3000 | ActionHandler 默认超时（per-handler 可覆盖） |
| `engine.rule.retention.evaluation-session-days` | 30 | evaluation_session 保留天数（D9） |
| `engine.rule.retention.node-trace-days` | 30 | node_trace 保留天数 |
| `engine.rule.retention.dry-run-session-days` | 7 | dry_run_session 保留天数 |
| `engine.rule.rollout.hash-seed` | 0 | murmur3 hash seed（固定后不要改，否则桶分布漂移） |

---

## 十、维护原则

- 本文档**唯一持有运维参数默认值**——其他文档只引用本表（如 04-extension §六只写"建议短超时 ≤ 200ms"，具体数字在本表）。
- 新增运维参数必须同步登记 §九 默认值表。
- 新增告警必须在 §六 列指标 + §七 列阈值。
- 可用性策略变更（如 v2 异步化路径开通）回写 §八 + 同步指向 08-evolution。
