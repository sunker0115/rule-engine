# 规则引擎压测设计（探顶找瓶颈 · PULL · JVM）

> Status: Draft（2026-06-08）。目标：探顶找出**评估热路径的业务逻辑瓶颈**，用数据佐证瓶颈排序，并回答"连接池是不是第一道墙、trace 关掉值不值、要不要 Disruptor"。

## 1. 目标与范围

- **目标类型**：探顶（不预设 SLO）。阶梯加压直到吞吐见顶 / 延迟拐起，定位每档的限制资源。
- **压测路径**：PULL `POST /api/v1/rule/evaluate`（同步，完整 DB 往返摊在请求线程上）。
- **环境**：JVM jar（JIT 热起来后的峰值吞吐画像，profiler 工具链最成熟）。
- **负载画像**：隔离引擎+写路径——规则**全命中** + metric 走 **providedMetrics（不触发外部取数）** + 中等复杂度 + 每命中 1–2 个 stub action。
  - 前提：miss 不落库（无写、无派发），探写路径瓶颈必须用高命中负载。
- **候选规则数**：做成档位变量 **10 / 50 / 200 条/scene**，画"候选数→单次评估成本"曲线。
- **优化范围**：本轮只测现状 + **零代码改动的配置旋钮对照**（Hikari 池、trace 开关）。改代码的优化留二期。

## 2. 热路径瓶颈预判（代码静态分析，待压测验证）

单次 PULL HIT 的同步副作用（请求线程上，`EvalServiceImpl.doEvaluate`）：
1. `match` —— 内存倒排索引 `SceneRuleIndex`，不查库 ✅
2. `insertPending` —— 1× INSERT `evaluation_session`（含 `uk_tenant_event` 幂等键）
3. `evaluateWithContext` —— metric 定义解析（Caffeine 60s）+ 取值（Caffeine 按 ttl，并发池 8–32）+ AST 解释执行（`TracingInterpretedExecutor`，始终收集 NodeTrace）
4. `updateFinal` —— 1× UPDATE session + context_snapshot JSON 序列化
5. `traceWriter.write` —— **异步批量**（`TraceWriterDbImpl` 队列1万/批500/200ms），off 热路径 ✅，可 `engine.rule.trace.enabled=false` 关
6. `dispatch`（命中才走）—— `findBySceneCode`（**每次命中查库、未缓存** ⚠️）+ 每个 decision×binding 一条 `action_execution` INSERT（同步）+ handler（v1 stub）

**预判瓶颈排序**（压测要验证）：
1. **Hikari 连接池 = 默认 10**（application.yml 未显式配）。每请求 ~3+N 次同步 DB 往返 → 连接池排队，**预判第一道墙**。
2. **scene action bindings 未缓存**（每命中一次 SELECT）。
3. **action_execution 同步逐条 INSERT**（命中 N 条）。
4. **session INSERT+UPDATE 两次往返**（PENDING→final 固有）。
5. metric 缓存命中率（本轮用 providedMetrics 规避，不触发取数）。
6. tracing 恒开（每次构造 node trace 的内存分配）。

## 3. 逻辑核查发现（顺带，非本压测的修复目标）

**PULL 幂等不完整**：重复 eventId 时 `insertPending` 撞唯一键返回已存在 session id，但后续**照常重新评估 + 重写 trace + 重派 action**（`executeHandler` 在审计 insert 之前执行）。session 行去重 ✅，但重新评估与 action 触发会发生——stub handler 无害，接真实 webhook（v1.5）会重复副作用。现有测试 `pull_idempotent_..._onlyOneSession` 只断言 session 数=1，给了虚假安全感。
> 处理：记录在案，本压测不修；二期连同写路径优化一起评估（短路已 final 的重复事件 / 让 action 触发幂等）。

## 4. 架构 / 流程

```
seeder 播种（三档候选 10/50/200）
  → 预热（灌满 metric-def 缓存 / 规则索引 / 连接池）
  → k6 阶梯加压打 PULL /evaluate
  → 观测（actuator/prometheus + 拐点处 async-profiler）
  → 分析（找拐点 QPS、归因限制资源）
  → 换配置臂复跑（池 10/50/100 × trace 开/关 × 候选 10/50/200）
  → 产出瓶颈排序 + 证据
```
单进程、单机、JVM jar；DB 用本机 MySQL。

## 5. 组件（全部置于顶层 `load-test/`，不进生产模块）

### 5.1 seeder
- 形态：`@SpringBootTest` 手动触发（复用 app 上下文 + typed 实体 + `AstJsonCodec`）。
- 造数（方案 b：typed 构造 + 冒烟校验）：
  1. 用 kernel `AstNode` 等 typed 实体构造一条最简规则（`provided metric ≥ 阈值`），经同一 `AstJsonCodec`/ObjectMapper 序列化后入 `rule_version`，保证 round-trip 与解释器期望一致；
  2. **先 seed 1 条 → 调一次 `/evaluate` 断言 HIT（冒烟）**，把"假数据不合法/不命中"变成一次性可验证不变量；
  3. 冒烟过后按 10/50/200 三档批量生成（改 code/阈值，保持全命中）。
- 每档生成：1 租户 + 1 scene（PUSH/HYBRID，固定 eventType）+ N 条已发布规则 + 1 条 metric-def（`allowProvided`）+ 1–2 条 scene_action 绑定（stub action 类型）。
- 可重跑：清理用**按压测租户 DELETE**（专用 tenantId），不 TRUNCATE 共享表，避免误删；或整体跑在专用 schema。

### 5.2 k6 脚本 `evaluate.js`
- POST `/api/v1/rule/evaluate`，body 带 `providedMetrics`（让条件通过）；
- `eventId` **每次迭代唯一**（走真实写路径，不触发幂等去重）；
- 阶梯 VU（ramping-vus）；阈值断言（http_req_failed、http_req_duration p95/p99）；
- 输出 p50/p95/p99 + req/s；参数化 tier / baseURL。

### 5.3 观测
- 抓 `/actuator/prometheus`：Hikari `hikaricp_connections_active`/`_pending`、JVM GC、http server 延迟、metric-fetch 池；
- 拐点处 `async-profiler` attach JVM PID → CPU / alloc / lock(wall) flame graph。

## 6. 配置对照矩阵（零代码改动）

| 臂 | 变量 | 目的 |
|---|---|---|
| 基线 | 池=10（默认）, trace=on | 现状拐点 |
| 池臂 | `spring.datasource.hikari.maximum-pool-size` = 10 / 50 / 100 | 验证"池是不是第一道墙" |
| trace 臂 | `engine.rule.trace.enabled` = on / off | 量化异步 trace 的开销 |
| 候选臂 | 10 / 50 / 200 条规则/scene | 候选数→单次评估成本曲线 |

不做全笛卡尔积：**以候选=50 为锚**跑池臂和 trace 臂；再固定最优配置跑候选三档。约 8–10 次 run。

## 7. 产出 & 成功标准

- 每臂的**拐点 QPS**、p50/p95/p99、错误率；
- 拐点处**限制资源归因**（Hikari pending>0？CPU 饱和？flame graph 热点方法？）；
- **瓶颈排序 + 数据佐证**；
- 明确回答：池是不是第一墙、trace 关掉值不值、要不要 Disruptor → 决定二期（改代码优化）计划。

## 8. 非目标（本 spec 明确不含）

- 改代码的优化：缓存 action bindings / session 两写合一 / action_execution 批量 / Disruptor；
- PUSH `/event` 路径与背压；
- native 镜像对比；
- SLO 达标验收。

以上各项待本轮数据出来后视必要性单独成第二份计划。

## 9. 风险

- **seed 假数据不命中** → 整个压测退化为 miss 路径（无写）无效：用 §5.1 冒烟校验兜住。
- **本机 MySQL 成为外部瓶颈**（而非应用逻辑）：观测 DB 侧（慢日志/performance_schema），区分"应用同步写多" vs "DB 本身慢"。
- **单机压测端与被测同机争 CPU**：k6 与 app 资源隔离 / 限核，避免压测端反成瓶颈。
