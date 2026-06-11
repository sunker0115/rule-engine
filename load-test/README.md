# 规则引擎压测（探顶找瓶颈 · PULL · JVM）

设计见 `docs/superpowers/specs/2026-06-08-load-test-design.md`，计划见 `docs/superpowers/plans/2026-06-08-load-test.md`。

> 租户寻址：API 外部契约用 `tenantCode`（业务标识），seeder 建 `tenant(id=9001, code='loadtest')` 行，k6 发 `tenantCode='loadtest'`，入口边界解析为内部 id 9001。下文「租户 9001」均指内部 id。

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
   - c. **trace 已验证：开/关零差异（583↔589 req/s）**——trace 异步批量不占请求线程。**这反证了同步 session 写才是墙，把 session 改异步是最高 ROI 的二期改造**（异步这条路 trace 臂已证明零成本）。

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

## Native 镜像 vs JVM 对比（PULL · 候选50 · 池=10 · 2026-06-08）

同机背对背重跑（同 seed、同 k6 阶梯至 400 VU），消除机器状态差异：

| | JVM (zulu-25) | Native (GraalVM 25) |
|---|---|---|
| 吞吐 | **22,014 req/s** | **12,120 req/s**（≈0.55×） |
| p50/p95 | 2.62 / **13.06ms** | 4.93 / **26.56ms** |
| RSS（稳态） | 500MB | **167MB**（≈0.33×） |
| 启动到 health UP | ~4s | ~4s |
| err% / Hikari | 0 · 3/10·pending 0 | 0 · 3/10·pending 0 |

- **吞吐 JVM 胜（native ≈ 0.55×）**：这是持续高 QPS、eval-CPU 绑定负载的预期——HotSpot C2 JIT 在 150s 持续压测中充分热身、峰值优于 AOT；native 无 JIT 峰值。**内存 native 胜（RSS ≈ 1/3）**，是其核心卖点（快扩容 / serverless / 低内存部署）。
- **启动两者都 ~4s**：此处 4s 被 DB 连接 + Flyway 校验 + 索引载入(50 规则)主导，掩盖了 native 的进程冷启动优势；native 真正的冷启边际要在无 DB 场景才显现（轮询粒度 0.5s，4s 偏粗）。
- **两者 Hikari 均 3/10、pending 0** → 再次印证 right-size 后 eval-CPU 绑定，DB 不是墙，与 PULL 候选曲线一致。
- **构建期提示**：native build 推荐 `--pgo`（Profile-Guided Optimization，先采集 profile 再重建）可提吞吐、缩小与 JVM 的差距——本轮未启用，留二期若要 native 高吞吐再做。
- **踩坑（已修）**：早先 native 启动验证未 seed ACTIVE 规则 → `IndexStartupLoader` 不载 AST → 多态反序列化路径从未触发，漏注册的 binding hints 被掩盖；seed50 后启动即崩。修复见 commit `fix(native): 注册 AST 多态反序列化反射 hints`（`AstNativeHints` + `@ImportRuntimeHints` 挂 `SceneSnapshotLoader`）。**教训：native reachability 验证必须覆盖有数据的真实启动路径,空库启动会漏掉数据驱动的反射点。**

**选型结论**：高吞吐持续负载 → JVM；快扩容 / 低内存 / serverless → native（吞吐可用 `--pgo` 补）。本引擎 eval 热路径属前者,生产默认 JVM,native 作弹性/边缘部署备选。

## Metric fetch（SQL_AGGREGATE）取数路径压测 + 正确性（2026-06-08）

前述所有 PULL/PUSH 压测都走 `providedMetrics`（指标由请求携带，请求线程 0 取数）。真实规则常依赖**非 provided** 指标，需 `EvalContextAssembler` 在**请求线程内同步取数**（并发 fetch + 缓存 + 800ms 超时 + 失败降级）。本轮 seed 一条依赖 `demo.agg`（`SQL_AGGREGATE`、`allow_provided=false`、`cache_ttl=60s`、只读源 `loadtest_ro`、SQL `SELECT 100` 以隔离往返成本）的规则，请求不传该指标 → 强制走 fetch。脚本 `k6/evaluate-fetch.js`（`SUBJECT_MODE=warm|cold`），seed `LoadTestSeeder#seedFetch`。

| 场景 | 吞吐 | p50/p95 | 正确性(rule_hits) | 主池 HikariPool-1 |
|---|---|---|---|---|
| **warm**（`s-${VU}` 有界 → 预热后缓存命中） | **30,701 req/s** | 2.34 / **9.15ms** | == http_reqs ✓ | 3/10 · pending 0 |
| **cold**（subjectId 每请求唯一 → 缓存恒穿透） | **2,840 req/s** | 23.23 / **122.57ms** | == http_reqs ✓ | 3/10 · pending 0 |

- **缓存是分水岭**：`MetricCache` 键含 `subjectId`。warm 下同 subject 命中 Caffeine（内存）→ 取数近乎免费、系统**仍 eval-CPU 绑定**（1 候选规则，吞吐落在候选曲线单规则档 ~30k）；cold 下每请求一次 `SELECT` 往返、请求线程阻塞在 `allOf.get`，**吞吐塌 10.8×（30.7k→2.84k）、p95 涨 13×（9→122ms）**。
- **新瓶颈 = 只读取数池**：cold 时主池仍 3/10 闲（DB 写不是墙），瓶颈是 `MetricDataSourceRegistry` 里**硬编码 maximumPoolSize=8** 的 `metric-ro-loadtest_ro` 池 + 请求线程同步等取数。400 VU 抢 8 条只读连接 → 吞吐封顶。**该池大小当前不可配，是 cold 取数场景的直接调优旋钮（建议提为配置项）。**
- **取数正确性端到端验证**：warm/cold 的 `rule_hits == http_reqs`（`SELECT 100` → `demo.agg=100 ≥ 0` → HIT）证明 resolver→datasource→bind→coerce→condition 全链路对；**降级**（不配数据源启动）单请求返回 `ruleHit=false`、nodeTrace `errorCode=METRIC_FETCH_FAIL`、**HTTP 200 无 500** → 取数失败优雅降级为 MISS、不崩。
- **设计含义**：right-size 的「请求线程 0 同步 I/O」**仅在 provided 或缓存热时成立**。需新鲜取数的规则付约 10× 吞吐代价；缓解靠（a）缓存 TTL + 业务时间局部性（风控 per-user 指标天然命中率高）、（b）调大只读池、（c）能 provided 就 provided。
- **附带瑕疵（记一笔）**：取数命中时 nodeTrace 的 `actualValue=null`（取到的值未回填 trace），不影响决策，但排障时看不到实际取数值。

## async-profiler 摸排（2026-06-08，证据替代直觉,**推翻 backlog 前提**）

trace.enabled=false(生产默认)、~19.5k req/s 稳态、候选50,asprof 采 CPU+alloc 各 30s + jstat 130s。

**CPU(总 10583 样本)——引擎不是瓶颈:**
| 占比 | 来源 |
|---|---|
| ~58% | syscall/IO/线程等待:`__psynch_cvwait` 22%、`kevent` 17%、`write` 10%、`read` 6%… |
| ~3% | 框架:Spring ResolvableType、Jackson、Micrometer/OTel |
| ~1.5% | GC 线程 |
| **~1%** | `InterpretedExecutor.execute`(AST 评估本体);`EvalEngine` ~0.3% |

→ **eval 计算仅 ~1% CPU**,成本在 HTTP I/O + Tomcat NIO + 框架 + 线程协调。**eval-CPU 优化(原 #2 快照预编译 / #5 字节码)被数据否决**——优化 1% 的东西没意义。

**分配(~55KB/请求,~1.08 GB/s)——你关心的对象创建:**
| 源 | 占比 | 可减? |
|---|---|---|
| Object[]/byte[]/char[]/String | ~28% | 多为框架 HTTP/Jackson 缓冲,难 |
| **BigDecimal** | **7.4%** | **引擎侧最大**:数值条件每比较 coerce × 50 候选 → 改原始类型比较 |
| **Stream 管道**(Head/ReduceOps/Comparator lambda×3/Collections$2/BinaryOperator) | **~10%** | `resolveRuleDecisions.stream().max()`、`evaluateFirstHit.stream().sorted()` 改循环 |
| EvalResult + Decision | 7.6% | 每候选一份,固有,低 ROI |
| HashMap/Node、Micrometer/OTel/Spring | ~11% | 部分框架 |

→ 引擎侧可减 ~25%(~14KB/req);**其余 ~75% 是框架开销**。

**GC/JVM 健康:** FGC=0、old 稳定 ~54%(朝生夕死,**无泄漏/无晋升压力**);YGC ~1.6/s×~12ms(churn 偏高但不病态);Metaspace 98.8% 接近满但**稳定不增长**(sized 紧,非泄漏)。无异常。

> 注:同机 k6 争核放大 syscall 占比;但"eval ~1% CPU、分配以框架+BigDecimal+stream 为主"的相对结论与压测端位置无关,稳健。

## 二期优化 backlog（**已按 2026-06-08 摸排证据重排**）

> 前提修正:DB 已出热路径,但**瓶颈不在 eval-CPU(实测仅 ~1%)**,而在 HTTP/框架 + 分配 churn。原"eval-CPU 绑定 + `26µs+0.26µs/候选`"的直觉前提被 profiler 否决——优化重心从"算得快"转向"少分配"(平 p99/GC)与"框架开销"。

1. **PUSH 并行消费 —— 最小改动，直接解 PUSH drain 瓶颈**。`EvalServiceImpl.java:40` `new EvalActionDispatcher(10000, ...)` 是单虚拟线程 drain；eval 无状态 CPU，改 N 消费者并行 → 候选 200 的 PUSH 从 10.2k/s 拉到接近 PULL。无共享状态、安全。**首选。**
1. **降分配·BigDecimal(摸排证据,引擎侧最大单一分配 7.4%)**。数值条件每次比较把 metric coerce 成 `BigDecimal` × 50 候选/请求。能用 primitive(double/long)比较时走原始类型,避免 BigDecimal 创建。平 p99/降 young GC,改动局部(ConditionEvaluation/数值算子)。**首选(有数据)。**
2. **降分配·eval 路径 stream 改循环(摸排证据 ~10%)**。`resolveRuleDecisions` 的 `.stream().max()`、`evaluateFirstHit` 的 `.stream().sorted()`、残留 `.stream().map().toList()` 制造 ReferencePipeline/ReduceOps/Comparator-lambda/Collections$2/BinaryOperator 大量临时对象。改手写循环可消。(part:`evaluateAllCandidates` 的 copyOf/stream 已在 `ed017f2` 减过。)
3. **PUSH 并行消费 —— 仅当用 PUSH `/event` 且需高 eval 吞吐**。`EvalActionDispatcher` 单虚拟线程 drain → N 消费者并行。无共享状态、安全。但 PUSH 摄入吞吐与候选数无关、eval 本身 ~1% CPU,优先级随实际 PUSH 用量。
4. ~~快照预编译(原 #2)~~ **数据降级**:摸排实测 `InterpretedExecutor.execute` 仅 ~1% CPU,"削 0.26µs/候选"的前提不成立——优化它收益 <0.5% CPU。除非出现 eval-CPU 真瓶颈(超大 AST / 超高候选),不做。
5. ~~AST→字节码(原 #5)~~ / ~~索引候选预过滤(原 #4)~~ **数据降级**:同上,eval 非 CPU 瓶颈;字节码方案还与 GraalVM native 冲突。留终极/搁置。
6. ~~审计/action 消费侧 keep-up~~ **✅ 已做(见 Track E)**:批量 INSERT,action 落库 0.13%→2.96%(~18×);瓶颈=本机 MySQL 单行写(归因否决并行 delivery)。进一步靠降 node_trace 写量 / MQ,**YAGNI**。
7. **trace 收集跳过 ✅ 已做**:见 spec/plan `2026-06-08-eval-trace-skip`;trace.enabled=false 时 eval 不建 NodeTrace(摸排已确认 alloc 里无 NodeTrace)。

**数据否决、不要碰**:Hikari 池(3/10)、Disruptor、trace 开关吞吐(开/关零差异)、并行 delivery 消费者(瓶颈在 DB 写)、**eval-CPU 计算优化(摸排实测仅 ~1% CPU)**。
**前提**:~75% 分配是框架(HTTP/Jackson/Micrometer/OTel),引擎侧降分配天花板 ~25%;要再降需碰框架配置(裁 OTel attr / Micrometer tag)。**未启动,留待真实 p99/吞吐 SLO 驱动。**

## 已知未覆盖
- ~~action_execution 写~~ / ~~审计落库 keep-up~~ **已覆盖**：见 Track A/D/E。action 派发对请求线程零成本(offer 丢弃 + dispatch 离线程);批量 INSERT 后 action keep-up 0.13%→2.96%、审计同批量化;高 QPS 下仍丢的部分是 best-effort 设计内(地板=本机 MySQL 单行写)。强一致完整落库留 MQ(经 `DomainEventPublisher` 缝)。
- **native 镜像对比**：已覆盖(见「Native 镜像 vs JVM」节)。
- **SLO 验收**：仍未做——全程为探顶,无生产目标。需要时另起一轮按 SLO 组织(而非漫无目的探顶)。
- **eval-CPU 二期 #1-5**：未启动,留待真实吞吐 SLO 驱动。

> **压测线状态(2026-06-08 封板)**：诊断使命完成——DB 池→right-size→eval-CPU 线性模型(`26µs + 0.26µs/候选`)→持久化 DB 写地板,瓶颈链全部定位;唯一明确代码胜利(批量 INSERT,~18×)已落地。后续优化均为"无目标提速",待 SLO 或新瓶颈驱动再开。

## 内核落库事件化重构后复测（PULL · 候选50 · 池10 · JVM zulu-25 · 2026-06-08）

把内核四条落库统一成「领域事件 → `DomainEventPublisher` 单一缝 → 各 persister」（`AuditRecorded` / `ActionExecuted` / `DryRunRecorded`）后复测，验证重构未给请求线程加成本，并首次压 action 派发路径与幂等去重。

### 回归校验：重构无吞吐回退

| run | 吞吐 | p50/p95/p99 | err% | Hikari(400VU 平台) |
|---|---|---|---|---|
| 重构后 PULL 基线（无 binding） | **21,374 req/s** | 2.88 / 13.43 / 23.84ms | 0 | 3/10 · pending 0 |

落在最可比的「native 对比 JVM 臂」22,014 的 **-2.9%**（同机噪声内；历史同配置三次自身散 22.0k–25.8k）。请求线程仍 0 同步 DB 写，DB 不是墙——重构没把任何写搬回热路径。

### Track A：action 派发路径（首次 seed binding 压测）

seed 一条 action 绑定（scene17 的 decision 配 SEND_ALERT，压测时经 `scene_action_binding` 表触发，该表已于 D54/V1_23 删除，现 action 直接走 `decision.actions`），PULL 候选50 复跑（3.5M 请求，150s 阶梯）：

| 路径 | 落库 / 3.5M 请求 | 落库率 | 消费侧形态 |
|---|---|---|---|
| 审计 evaluation_session | 68,739 (~458/s) | 2.0% | AuditPersister：有界队列 + 批量 500/200ms + 虚拟线程**异步** |
| action_execution | **4,500 (~30/s)** | **0.13%** | ActionExecutionPersister：**同步内联 insert**（无自有队列/批） |

- **active action 派发对请求线程吞吐零影响**：带 binding **23,371 req/s**（≈ 无 binding 21,374，差值噪声），p95 12.3ms，0 err，Hikari 3/10·pending 0。因 `InProcessAsyncDeliveryChannel.deliver()` 是 `offer()`（满即丢、不阻塞），`dispatch`+claim+handler+insert 全在单条异步消费线程上，离开请求线程。
- **action 落库高 QPS 下 99.87% best-effort 丢弃**（4,500/3.5M），比审计低 15×。0 写库错误 → 丢弃静默，符合设计。
- **请求内幂等去重生效**：50 个 PASS 决策同键 → 1 行（Caffeine claim）。
- **初判（后被 Track D 推翻）**：当时归因为 `ActionExecutionPersister.accept` 同步内联 insert 串在单条 action-delivery 线程上。Track D 实测推翻——见下。

### Track B：幂等去重（Caffeine claim-before-execute）

有界 eventId 空间 200 + `constant-arrival-rate` 1000/s（低于 2500/s drain 上限，消费侧 keep-up）跑 40s（~40k 请求）：

| 请求数 | eventId 空间 | action_execution 总行数 | 每幂等键最大行数 | uk backstop 命中 |
|---|---|---|---|---|
| ~40,000 | 200 | **200** | **1** | **0** |

- ~40k 并发重复事件 → 恰好 200 行、**每键 1 行、零重复**，去重比 200:1。
- **0 uk backstop 命中** → Caffeine claim 在 insert 之前完成 100% 去重，uk_idempotency 兜底未触发。claim-before-execute 在并发下完全可靠。

### Track D：`ActionExecutionPersister` 改异步后的验证（**负面结果**，推翻 Track A 初判）

把 `ActionExecutionPersister` 从同步内联 insert 改成与 `AuditPersister` 同构的异步批量消费（commit `52f7e65`）后，同 23k QPS 复跑：

| | action 落库 / 3.5M 请求 | 落库率 | 吞吐 |
|---|---|---|---|
| 改前（同步内联） | 4,500 | 0.13% | 23,371 |
| **改后（异步批量）** | **4,443** | **0.13%** | 22,907 |

- **改异步没有提升 keep-up**（4,443 ≈ 4,500）。请求线程吞吐照旧不受影响（offer 丢弃 + dispatch 离线程）。

接着加 action binding 内存索引（commit `9939998`，去掉每 dispatch 的 binding SELECT；压测时 binding 走 `scene_action_binding` 表，该表已于 D54/V1_23 删除，现 binding 直接来自 `decision.actions` 快照），同 23k QPS 三测：

| | action 落库 | 落库率 | 空载纯 drain |
|---|---|---|---|
| 原始（同步内联 insert） | 4,500 / 3.5M | 0.13% | — |
| `52f7e65`（异步 persister） | 4,443 / 3.5M | 0.13% | **~63/s** |
| `9939998`（+binding 缓存） | 7,106 / 2.7M | 0.26% | **~64.6/s** |

- **缓存 binding 同样没提升消费者**：空载纯 drain `64.6/s` 与缓存前 `~63/s` 一致 → binding SELECT 也不是地板。
- **真正的地板 = 本机 MySQL 单行 INSERT 吞吐（~64/s，fsync 绑定的笔记本）**。`ActionExecutionPersister`/`AuditPersister` 的"批量"只批量 drain，**SQL 仍是逐行 `mapper.insert`**（每行一次 autocommit + fsync），所以 ~64/s 封顶。`52f7e65`（搬 insert 离 dispatcher 线程）与 `9939998`（去 SELECT）都砍在了非瓶颈层 → 本机测不出收益。
- **两改动仍保留（superpowers 审核结论）**：均消除热路径上的真实每请求 I/O（dispatcher 内联写库 / 每 dispatch 一次 SELECT），**生产快 DB / 批量 INSERT / MQ 落地后才显形**；且与既有 `AuditPersister` / `SceneRuleIndex` 模式统一，非 YAGNI 投机。
- **真正提速杠杆（二期，按 ROI）**：(1) **批量/多行 INSERT**（`INSERT ... VALUES (...),(...)`）—— 直接抬 INSERT 地板，是当前唯一能动 keep-up 的点；(2) action-delivery 并行消费；(3) 强一致走 MQ（经 `DomainEventPublisher` 缝）。in-process action 落库本就 best-effort（设计内可丢）。

### Track E：多行批量 INSERT（commit `0fc4904`，**终于动了 keep-up**）

把 `ActionExecutionPersister` / `AuditPersister` 的逐行 `mapper.insert` 改成单次多行 `insertBatch`（`INSERT ... VALUES (...),(...) ON DUPLICATE KEY UPDATE id=id` 容忍 uk 重复），每批 500 行从 500 次 autocommit+fsync 降到 1 次：

| | action 落库 / 请求 | 落库率 |
|---|---|---|
| 逐行 insert（Track A/D） | 4,443 / 3.4M | 0.13% |
| **多行批量 insert** | **80,568 / 2.7M** | **2.96%** |

- **action 落库 ~18×（0.13%→2.96%）**，证实瓶颈就是逐行 insert 的 per-commit fsync。审计同窗口落 ~113k session。请求线程吞吐不受影响。
- **node_trace 早已是批量 insert**（trace-writer），故 trace 从来不是瓶颈。

#### Track E 续：稳态天花板 ~731/s + 线程转储归因（delivery vs persister）

8000/s 持续灌满队列，取中段 56s 窗口：action 落库稳态 **~731/s**（40,998 行 / 56s）。饱和期连抓 4 次 `jcmd Thread.dump`（含虚拟线程）归因：

| 线程 | 采样状态 | 结论 |
|---|---|---|
| `action-delivery`（投递消费者） | 3/4 在 `Thread.sleep`（200ms 节拍） | **基本空闲，非瓶颈** |
| `action-execution-persister` | 2/4 `NioSocketImpl.park`（等 MySQL INSERT） | 半忙插库 |
| `audit-persister` | **4/4 `NioSocketImpl.park`** | **100% 钉死在插库** |

- **真瓶颈 = JDBC INSERT 的 DB 写容量（persister 侧），不是 delivery 消费者**。delivery 大部分时间在 200ms 节拍里睡，能出 ~2500/s，但下游 persister 吃不下、多发的被 persister 队列 offer 丢弃。
- `audit-persister` 100% 钉死 → 审计每请求一条、直连请求线程无漏斗，把共享 MySQL 写预算占了大头；`action_execution` 的 ~731/s 是抢剩的。
- **⇒ 并行 delivery 消费者无用**（它本就闲）。要再抬 action keep-up 的杠杆在 DB 写侧：降写量（`node_trace` 每请求 50 行是写大户）、更大/更少 INSERT、放宽 fsync / 换更快 DB、强一致走 MQ + 批量 loader，或给 action 独立写路径别跟 audit 抢。

## 降分配复测：去 BigDecimal + stream→loop（PULL · 候选50 · 池10 · trace off · JVM zulu-25 · 2026-06-08）

backlog #1（数值类型分派，LONG/DOUBLE 走原始比较，去 BigDecimal）+ #2（`EvalEngine` 裁决/排序 stream 改手写循环）落地后复测。seed 规则 `demo.score GTE 0`（`dataType=LONG`，请求传 100）正是 LONG 比较路径——直接命中 fast-path。

| 指标 | 摸排基线（去优化前） | 本轮（去 BigDecimal + stream） | 说明 |
|---|---|---|---|
| 吞吐 | ~19.5k req/s 稳态 | **33,131 req/s** | 同机散点，不单归因（eval 仅 ~1% CPU）；至少无回退 |
| p50/p95 | — / — | **1.87 / 9.09ms** | 优于历史 right-size 臂（2.49/11.72） |
| **BigDecimal 分配占比** | **7.4%（引擎侧最大）** | **0%（profile 中完全消失）** | LONG→`Long.compare`，零 BigDecimal ✅ |
| **EvalEngine stream/lambda** | 属 ~10% stream 管道 | **~0.08% 残留** | `.stream().max()/.sorted()`→循环，比较器提 static final ✅ |
| YGC | ~1.6/s × ~12ms | **~1.5/s × ~12.7ms** | 频率持平但吞吐 ↑70% → **每请求 churn 明显下降** |
| FGC / old | 0 / 稳定 54% | **0 / 稳定 60-69%** | 朝生夕死，无晋升/泄漏 |

- **直接证据（profile）**：BigDecimal 从引擎侧最大单一分配（7.4%）降到 **0**；EvalEngine 的 stream 帧从 ~10% 的组成项降到 0.08% 残留。两处可减分配按预期清除。
- **EvalResult(7.01%) + Decision(4.69%)** 现成为引擎侧最大分配——这是每候选必产的结果对象（50/req，固有，README 已标低 ROI），噪声被清掉后自然冒头。
- GC 频率持平而吞吐 ↑ → 归一化看每请求分配下降；系统仍非 GC-bound（FGC=0），符合摸排"瓶颈在 HTTP/框架"定性。引擎侧降分配天花板（~25%）的两块大头（BigDecimal + stream）已兑现。
