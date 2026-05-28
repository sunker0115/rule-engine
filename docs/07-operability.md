# 07 — 可运维（占位草稿）

> **位置定位**：本文档承载 rule-engine 的**上线后视角**——幂等 / 审计 / 试算 / 灰度 / 监控 / 告警 / 可用性策略 / 运维参数默认值。当前**占位**，仅章节就位，内部具体内容待定。
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
| §二 幂等 | ⏳ 未展开（IdempotencyGuard + record_no + eventId） |
| §三 EvaluationSession 落库策略 | ⏳ 未展开（v1 同步事务 / v2 异步路径指向 [`08-evolution.md`](./08-evolution.md) §2.15） |
| §四 dry-run 链路 | ⏳ 未展开 |
| §五 灰度 | ⏳ 未展开（hash bucket 算法 + rollout 结构 + 灰度验证流程） |
| §六 Prometheus 指标清单 | ⏳ 未展开 |
| §七 告警阈值 | ⏳ 未展开（建议值，不强制） |
| §八 可用性策略汇总 | ⏳ 未展开（v1 策略 + SPOF 清单 + 降级矩阵） |
| §九 运维参数默认值表 | ⏳ 未展开（`engine.rule.*` 命名空间集中表） |

---

## 二、幂等

⏳ 未展开。

> 展开时落定：`IdempotencyGuard` 接口契约 + `RuleEvent.eventId` 生成约定（外部 `record_no` 直传 / Job 模式 `hash(jobRunId + subjectId)`，D11 派生）+ `evaluation_session` 唯一约束承载幂等收口（D21 §3 派生）+ 重复事件的语义（直接返回上次 EvalResult 或拒绝）。

---

## 三、EvaluationSession 落库策略

⏳ 未展开。

> 展开时落定：v1 同步事务落 evaluation_session 行（D21 派生：含幂等收口 / 对账分母 / 外键时序三层角色）+ session 状态机 INIT → RUNNING → COMPLETED / ERROR + 与 audit_log（D14 同步事务）的写入边界 + 与 node_trace（D21 异步批写）的写入边界 + v2 异步化路径锚点指向 [`08-evolution.md`](./08-evolution.md) §2.15。

---

## 四、dry-run 链路

⏳ 未展开。

> 展开时落定：dry-run 入口（[`10-api-contract.md`](./10-api-contract.md) 接口） + 不落 evaluation_session / audit_log / node_trace 的旁路语义 + Pre-Gate 在 dry-run 模式下的行为（[`01-concepts.md`](./01-concepts.md) §3.14 派生）+ 返回结构（节点级 trace + EvalResult + 命中 Action 列表但**不实际派发**）。

---

## 五、灰度

⏳ 未展开。

> 展开时落定：`Rule.rollout` 结构（type / percentage / tagConditions） + 灰度桶 hash 算法（基于 `(subjectId, ruleVersionId)`，不依赖实例 ID，D17 派生）+ 灰度桶在引擎 Pre-Gate 计算（D6 + D11 派生）+ 灰度验证流程（dry-run + 灰度小流量 → 全量）+ 回退路径（DISABLED 切换或新版本发布覆盖）。

---

## 六、Prometheus 指标清单

⏳ 未展开。

> 展开时落定：核心指标分组——
>
> - **吞吐**：RuleEvent QPS / 各 Scene QPS / 各 Rule 命中率
> - **延迟**：评估 P50/P99 / metric 预拉 P99 / Action 派发 P99
> - **失败**：D15 三态对账（HIT / MISS / ERROR） / errorCode 分布
> - **队列**：D20 §2 Dispatcher 队列深度 / D21 TraceWriter 队列深度 / 溢出 counter
> - **可用性**：DB 连接池 / 倒排索引刷新延迟 / xxl-job 调度健康

---

## 七、告警阈值

⏳ 未展开。

> 展开时落定：每个指标的**建议**阈值（不强制，业务侧可覆盖）—— 队列溢出 counter > 0 直接 page / 评估 P99 超基线 X 倍持续 5 分钟 warn / DB 连接耗尽立即 page / 倒排索引刷新延迟 > 60s warn 等。

---

## 八、可用性策略汇总

⏳ 未展开。

> 展开时落定：
>
> - **v1 策略**：无状态水平扩展 + 关键路径降级，不做主备切换 / 分布式协调
> - **派生归纳**：D6（评估快照不可变 + 上游可重推） / D11（xxl-job 调度 HA） / D15（评估失败隔离：单节点 / 单规则 / 跨规则）/ D17（多实例最终一致 + 灰度桶不依赖实例）/ D21（trace 旁路降级，队列满丢弃 + 告警，不阻塞热路径）
> - **SPOF 清单**：
>   - DB（rule_definition / rule_version / audit_log 等）—— 单点，依赖 DB HA（主从 / Galera 等）
>   - 内存倒排索引 —— 单实例本地缓存，可重建（启动期全量扫描 rule_version + 后续 15s 增量），非 SPOF
>   - 异步队列（D20 §2 / D21）—— 进程内内存队列，进程重启丢失未消费消息，依赖上游 RuleEvent 可重推补偿
>   - xxl-job 调度器 —— 单点，依赖 xxl-job 自身 HA
> - **降级矩阵**：
>
>   | 路径 | 失败行为 | 降级策略 |
>   |------|---------|---------|
>   | metric 取数 | timeout / 熔断 | 归 `METRIC_FETCH_FAIL` + 该节点 satisfied=false（D15） |
>   | Action 派发 | Handler 异常 | continue-on-error（D18），单 Action 失败不影响同 Rule 后续 Action 也不影响 EvalResult.satisfied |
>   | TraceWriter 入库 | 队列满 / DB 写失败 | 丢弃 + counter 告警，不阻塞热路径（D21） |
>   | audit_log 入库 | DB 写失败 | 同步事务回滚，**不**降级（D14 "人的行为"红线） |
>   | Dispatcher 队列 | 队列溢出 | `QUEUE_OVERFLOW` errorCode + 该 Action 失败但不影响 EvalResult |

---

## 九、运维参数默认值表

⏳ 未展开。

> 展开时落定：`engine.rule.*` 命名空间下所有参数的默认值清单（业务侧可在 application.yml 覆盖）——
>
> - `engine.rule.poll-interval`（D17 默认 15s）
> - `engine.rule.dispatcher.queue-size` / `consumer-pool` / `retry-max`（D20 §2）
> - `engine.rule.trace-writer.queue-size` / `batch-size` / `consumer-pool`（D21）
> - `engine.rule.metric.timeout-default` / `cache-ttl-default`（§3.9 + D20 §1）
> - `engine.rule.job.cron-default-timezone`（D11 + B3 派生）
>
> **本文档是默认值唯一持有者**——00-decisions / 04-extension §六 实现建议值只列"建议"，具体默认值数字落本表。

---

## 十、维护原则

- 本文档**唯一持有运维参数默认值**——其他文档只引用本表（如 04-extension §六只写"建议短超时 ≤ 200ms"，具体数字在本表）。
- 新增运维参数必须同步登记 §九 默认值表。
- 新增告警必须在 §六 列指标 + §七 列阈值。
- 可用性策略变更（如 v2 异步化路径开通）回写 §八 + 同步指向 08-evolution。
