# 压测 Runbook（PULL · JVM · 探顶）

## 前置
1. package：`$MVN -pl rule-app -am -DskipTests package`
2. seed 目标档位（写专用租户 9001，自清理可重跑）：
   `$MVN -pl rule-app -Dtest=LoadTestSeeder#seed50 -Dgroups=loadtest -DfailIfNoTests=false test`
3. **每次改配置 / 换档位都要重启 app**（ACTIVE 规则启动期由 IndexStartupLoader 载入内存索引）。

## 启动 app（按臂改参数）
```bash
java \
  -Dmanagement.endpoints.web.exposure.include=prometheus,health \
  -Dspring.datasource.hikari.maximum-pool-size=${POOL} \
  -Dengine.rule.trace.enabled=${TRACE} \
  -jar rule-app/target/rule-app-1.0.0-SNAPSHOT.jar
```
> baseline：POOL=10（默认，可省）、TRACE=true（默认，可省）。

## 臂矩阵（不做全笛卡尔积；锚定候选=50）
| run | 候选 | POOL | TRACE | 看什么 |
|---|---|---|---|---|
| 基线 | 50 | 10 | true | 现状拐点 |
| 池-50 | 50 | 50 | true | 池放大收益 |
| 池-100 | 50 | 100 | true | 池是否还是墙 |
| trace-off | 50 | <最优池> | false | 异步 trace 开销 |
| 候选-10 | 10 | <最优> | <最优> | 候选↓ 成本 |
| 候选-200 | 200 | <最优> | <最优> | 候选↑ 成本 |

## 每个 run 的步骤
1. seed 该档位（如换候选数）；2. 重启 app（带该臂参数）；
3. `k6 run load-test/k6/evaluate.js`（出 p50/p95/p99 + req/s）；
4. k6 跑到高 VU 平台时执行 `load-test/scripts/capture-prometheus.sh` 抓一帧（看 Hikari pending）；
5. 若该臂是拐点关注点，平台期 `load-test/scripts/profile.sh <pid>` 抓 30s flame graph（需装 async-profiler）；
6. 记一行结果到 `README.md` 结果表。

## 注意
- 压测端（k6）与被测 app 同机争 CPU：本机探顶时关注 app 进程是否被 k6 抢核；必要时给 k6 限核或分机。
- 清理：seeder 每次 `seedN` 已按租户 9001 自清理；手动清 `DELETE ... WHERE tenant_id=9001`（FK 序 rule_version→rule_definition→metric_definition→scene）。
