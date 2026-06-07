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

## 已知未覆盖
- **action_execution 写**：本轮 scene_action_binding 无写入路径（config-svc 无该写服务），`dispatch` 因 bindings 空提前 return，action 写**未压到**；session+trace 写已覆盖（命中触发 `updateFinal`+trace）。二期补 action 写路径后再测。
- PUSH `/event` 路径与背压、native 镜像对比、SLO 验收：本轮非目标，留二期。
