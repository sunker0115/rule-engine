# 规则引擎压测（探顶找瓶颈 · PULL · JVM）

设计见 `docs/superpowers/specs/2026-06-08-load-test-design.md`，计划见 `docs/superpowers/plans/2026-06-08-load-test.md`。

## 跑法
1. seed：`$MVN -pl rule-app -Dtest=LoadTestSeeder#seed50 -Dgroups=loadtest -DfailIfNoTests=false test`（写专用租户 9001，自清理）
2. 启动 app（带臂参数，见 `runbook.md`）→ `k6 run k6/evaluate.js` → `scripts/capture-prometheus.sh` 抓 Hikari 帧
3. 每 run 记一行下表

## 结果（2026-06-08，本机 JVM zulu-25 + 本机 MySQL，候选=50，k6 阶梯至 400 VU）

| run | 候选 | POOL | TRACE | 吞吐(req/s) | p50/p95(ms) | err% | Hikari active/pending |
|---|---|---|---|---|---|---|---|
| 基线 | 50 | 10 | on | **246** | 272 / **1520** | 0 | 10/10 · **107** |
| 池-100 | 50 | 100 | on | **583** | 137 / **520** | 0 | 100/100 · 17 |
| trace-off | 50 | 100 | off | **589** | 128 / **526** | 0 | 100/100 · 16 |
| 事件化异步(outbox) | 50 | 10 | on | **215** | 329 / **1750** | 0 | 10/10 · **116** |
| **right-size 异步(纯内存)** | 50 | 10 | on | **24434** | 2.6 / **11.8** | 0 | **3/10 · 0** |

> 注：所有请求 100% 命中（rule_hits = http_reqs）；同机压测（k6 与 app 争 CPU），绝对值偏保守，**臂间相对差**是结论依据。

## 结论

1. **第一道墙 = Hikari 连接池（铁证）**。池=10 时 ~100 VU 即打满（10/10 active、**107 pending**），吞吐封顶 ~246 req/s、p95 1.52s。**池 10→100：吞吐 2.4×（246→583）、p95 降 3×（1.52s→520ms）、pending 107→17**。零代码、一个启动参数 `-Dspring.datasource.hikari.maximum-pool-size`。
2. **负载是真·DB 写绑定**。池=100 时连接**仍 100% 占满**（100/100 active）——每请求要握着连接做 `session INSERT`+`UPDATE` 两次往返，**并发上限 ≈ 池大小**。继续加池只是抬高天花板，根因是单请求的 DB 连接占用时长。
3. **要不要 Disruptor：否（数据否决）**。瓶颈是 DB 写往返占用连接，不是队列/锁交接。Disruptor 解决不了"每请求 2 次同步 DB 往返"。
4. **二期改代码优化（按本轮数据排序，缩短连接占用 = 直接提吞吐）**：
   - a. `session` 两写合一（评估后单次 INSERT 终态，省一次往返/请求）；
   - b. `action_execution` 批量/异步（本轮 action 未覆盖，见下）；
   - c. 缓存 `scene_action_binding`（去掉每命中一次 SELECT，本轮 bindings 为空未触发）；
   - d. **trace 已验证：开/关零差异（583↔589 req/s）**——trace 异步批量不占请求线程。**这反证了同步 session 写才是墙，把 session 改异步是最高 ROI 的二期改造**（异步这条路 trace 臂已证明零成本）。

## 事件化异步复测发现（2026-06-08，重要）

把 session 写改事件驱动异步后，池=10 仍 **215 req/s（≈原基线 246，没变好）**，Hikari 仍 10/10 满、pending=116。

**诊断**：请求线程唯一的同步 DB 操作变成 `ModulithOutboxDeliveryChannel.deliver` 的 **@Transactional 持久事件写**（BEGIN+INSERT event_publication+COMMIT）。压测规则命中且有 PASS 决策 → 每请求发一次 ActionRequested → 每请求一次 outbox 事务写，成本与原 session 两写相当（事务 3 往返 ≈ 原 2 次自动提交写）。session 写确实搬异步了，但被 outbox 写顶替。

**根因（spec↔实现偏差）**：spec §3 说"命中**且有 action 绑定**才发持久事件"，实现成了"命中**且有决策**"。压测场景命中、有决策、**无 action 绑定** → 按 spec 本不该发 outbox，实现却发了。

**结论**：架构正确（审计已脱离请求线程），但 outbox 的 @Transactional 持久写顶替了 session 写，没拿到收益。

## right-size 复测（2026-06-08，决定性收益）

砍掉 DB outbox、action 改进程内异步 best-effort 后（请求线程 0 DB 写），池=10 复测：

| | 吞吐 | p50/p95 | Hikari |
|---|---|---|---|
| 原基线（同步） | 246 req/s | 272 / 1520ms | 10/10 · pending 107 |
| outbox 版 | 215 req/s | 329 / 1750ms | 10/10 · pending 116 |
| **right-size 异步** | **24,434 req/s** | **2.6 / 11.8ms** | **3/10 · pending 0** |

- **~100× 吞吐（246 → 24,434）、p95 1.52s → 12ms、池只用 3/10 零排队**，366 万请求 0 失败。
- 请求线程退化成纯 CPU（index 命中 + AST 解释 + 入队），不再被 DB 绑定 → 进入"内存级引擎"量级（对标无IO 基准 Aviator/Grule/EasyRules 的几万~十万 QPS），且这是 k6 争核的笔记本。
- **设计教训**：审计「可丢」→ 内存异步是 80/20 收益；action 的 DB outbox 在 stub handler 阶段既过度设计又恰是瓶颈。durable 留到真 handler，经 `ActionDeliveryChannel` 缝换 MQ。

## 候选规则数 → 吞吐 曲线（right-size 异步后，2026-06-08）

DB 出热路径后系统转为 eval-CPU 绑定，候选规则数（每请求评估的 AST 数）成主导成本。池=10、同 async 配置实测：

| 候选规则数 | 吞吐 | p50/p95 | 每请求成本 | Hikari |
|---|---|---|---|---|
| 10 | **34,340 req/s** | 2.0 / 8.65ms | ~29µs | 3/10 · pending 0 |
| 50 | **25,830 req/s** | 2.49 / 11.72ms | ~39µs | 3/10 · pending 0 |
| 200 | **12,846 req/s** | 3.78 / 23.15ms | ~78µs | 3/10 · pending 0 |

- **线性成本模型**：每请求 ≈ **26µs 固定 + 0.26µs × 候选规则数**（10→50 +40 条加 10µs、50→200 +150 条加 39µs，均约 0.26µs/条）。固定开销 = HTTP+Jackson+index 命中+2 次事件入队；每候选 ~0.26µs = 简单条件 AST 解释。
- **全程 Hikari 3/10、pending 0** → 瓶颈彻底从 DB 转到 eval-CPU；DB 不再是墙。
- 容量规划：本机单实例 200 条候选规则 ≈ 1.3 万 QPS（笔记本 + k6 争核，服务器上更高）。

## PUSH `/event` 路径与背压实证（2026-06-08）

PULL `/evaluate` 同步返回 EvalResult；PUSH `/event` 入队即返 **202**，评估在后台**单条虚拟线程消费者**（`EvalActionDispatcher`，队列 `LinkedBlockingQueue(10000)`）串行 drain，队列满时 `submit` 返回 false → body `data.accepted=false` 表达**背压**（HTTP 仍 202，不靠拖慢响应）。压测脚本 `k6/event.js`，计数 `push_accepted` / `push_rejected`。

| 候选 | 摄入(req/s) | accepted(drain上限) | rejected(背压) | 背压率 | p95 | 诊断 |
|---|---|---|---|---|---|---|
| 50 | 28,469 | **28,457** | 12.7 | **0.04%** | ~12ms | 摄入≈单线程 eval，几乎不溢出 |
| 200 | 27,962 | **10,230** | **17,732** | **63%** | 11.67ms | 单线程 drain 10.2k/s ≪ 摄入 28k/s → 队列填满主动拒 |

- **背压机制验证**：候选=200 时单线程 eval 慢到 ~10.2k/s（候选越多每条越慢，与 PULL 线性成本一致），摄入端 28k/s 远超 drain → 队列瞬间填满 10000 → 稳定 **63% 主动拒绝**，而 **p95 仍 11.67ms**——证明背压走「入队即返 202 + body.accepted=false」而非堆积拖垮延迟，符合设计。
- **PUSH vs PULL 取舍**：PUSH 把 eval 移出请求线程，摄入吞吐只受 HTTP+入队限制（候选 50/200 摄入都 ~28k/s，**与候选数无关**）；代价是单消费者 drain 成新瓶颈（候选 200 仅 10.2k/s 真正被评估）。需要高 eval 吞吐应**多消费者并行 drain**（当前单线程是有意的最简实现），或继续用 PULL（请求线程并行评估，candidate 曲线见上）。
- **结论**：背压设计正确且可观测（rejected 计数 = 队列保护生效）。单消费者是当前刻意的下限，**并行消费**留二期（与审计/​action 异步消费侧同一优化方向）。

## 已知未覆盖
- **action_execution 写（压测层面无需补）**：right-size 后 action 派发已**异步、离开请求线程**——压测场景无 scene_action_binding（config-svc 无写服务），但即便有，请求路径也只入队即返、**不影响吞吐**；后台消费侧在 24k/s 下本就被本地 DB 限速（审计已大量丢，best-effort 预期内）。该写路径由单测覆盖委托 + 一次功能冒烟（seed binding → evaluate → 异步落 `action_execution`）确认端到端，非压测目标。
- **审计落库 keep-up**：24k req/s 远超 AuditPersister 批写能力（~500/200ms）+ 本地 MySQL 写入速率 → 大量 session 被丢（设计内「可丢」）。需强审计的场景另议（该 metric 走 outbox / MQ）。
- **PUSH 单消费者并行化、native 镜像对比、SLO 验收**：本轮非目标，留二期。
