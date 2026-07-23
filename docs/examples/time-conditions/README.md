# 时间条件三种写法（time-conditions）

> **状态**：✅ 对齐当前实现（D55 公开评估只收 payload / D76 多态 body / D54 decision 独立写）。按 [`../risk-control/high-risk-login/`](../risk-control/high-risk-login/) 范式从旧案例重写。

## 一、场景与业务目标

同一营销 Scene（`ops.promotion`）内，三条规则演示时间条件的三种写法，命中即 `REWARD`：

| 规则 | 条件类型 | 时间来源 | 业务含义 |
|------|---------|---------|---------|
| `rule-time-window` | `time.window` | `EvalContext.now`（引擎注入，可经 `asOf` 覆盖） | 规则只在工作日 09:00–22:00 生效 |
| `rule-occurred-at` | `time.occurred_at` | `event.occurredAt`（业务时间） | 事件业务时间落在活动期（2026-06 整月） |
| `rule-metric-window` | 间接（Metric SQL 内嵌窗口） | SQL 内 `NOW() - INTERVAL 7 DAY` | 近 7 天登录 ≥ 3 次（活跃用户），条件层只看数值 |

三者对比：`time.window` 判"当前时刻在不在时段"、`time.occurred_at` 判"业务时间在不在区间"、Metric 内嵌窗口把时间逻辑封进 SQL、条件层不感知时间。

## 二、配置概览

| 组成 | 取值 |
|---|---|
| Scene | `ops.promotion`，PULL，subjectType=USER，eventTypes=`[user.action.login, user.action.purchase]`，payloadSchema=`action_type(string,必填)` |
| Metric | `user.login.count.7d`（SQL_AGGREGATE，`datasource=loadtest_ro`，窗口在 SQL 内，`allowProvided=false` 引擎自取） |
| Decision | `REWARD`(priority 1)/`PASS`(100)，tenant 级，PULL 无 action |
| Rules | 三条见上表，均 AST_BOOLEAN + preGate `ROLLOUT 100%` + 绑定 `REWARD` |

文件：[`scene.json`](./scene.json) · [`decisions/decisions.json`](./decisions/decisions.json) · [`metrics/metrics.json`](./metrics/metrics.json) · [`rules/`](./rules/) · [`mock-events.json`](./mock-events.json) · [`expected-results.json`](./expected-results.json)

## 三、端到端 curl 剧本

> 前置：`rule-app` 已起（`localhost:8080`）；`user.login.count.7d` 依赖 `loadtest_ro` 只读源（见 `application.yml` `engine.rule.fetch.datasources`）。`tenantId=9001`=`tenant.id`，`tenantCode=loadtest`=`tenant.code`。

```bash
BASE=http://localhost:8080
H='-H Content-Type:application/json -H X-Actor-Id:demo-admin'

# 1) 建场景
curl -s $H -X POST "$BASE/admin/v1/scenes" --data @scene.json

# 2) 建 decision（tenant 级独立实体，发布期校验 decisionCode 存在）
python3 -c 'import json;[print(json.dumps(d)) for d in json.load(open("decisions/decisions.json"))]' | while read d; do
  curl -s $H -X POST "$BASE/admin/v1/decisions?tenantId=9001" -d "$d"; echo
done

# 3) 建 metric（SQL_AGGREGATE；rule-metric-window 用）
python3 -c 'import json;[print(json.dumps(m)) for m in json.load(open("metrics/metrics.json"))]' | while read m; do
  curl -s $H -X POST "$BASE/admin/v1/metrics?tenantId=9001" -d "$m"; echo
done

# 4) 建三条规则草稿 + 发布
for f in rules/rule-time-window.json rules/rule-occurred-at.json rules/rule-metric-window.json; do
  RID=$(curl -s $H -X POST "$BASE/admin/v1/rules" --data @"$f" | python3 -c 'import json,sys;print(json.load(sys.stdin)["data"]["ruleDefinitionId"])')
  echo "$f → ruleDefinitionId=$RID"
  curl -s $H -X POST "$BASE/admin/v1/rules/$RID/publish?tenantId=9001"; echo
done

# 5) 评估：time.window 命中（asOf=周三 10:00 在窗口内）
curl -s -H Content-Type:application/json -X POST "$BASE/api/v1/rule/evaluate" \
  -d '{"tenantCode":"loadtest","sceneCode":"ops.promotion","eventType":"user.action.login","subjectId":"user-001","eventId":"evt-tw-001","occurredAt":"2026-06-10T10:00:00+08:00","asOf":"2026-06-10T10:00:00+08:00","payload":{"action_type":"login"}}'

# 6) 评估：time.occurred_at 命中（occurredAt 在活动期，asOf 超期也命中——补录按业务时间判断）
curl -s -H Content-Type:application/json -X POST "$BASE/api/v1/rule/evaluate" \
  -d '{"tenantCode":"loadtest","sceneCode":"ops.promotion","eventType":"user.action.purchase","subjectId":"user-004","eventId":"evt-oat-001","occurredAt":"2026-06-15T14:30:00+08:00","asOf":"2026-07-05T10:00:00+08:00","payload":{"action_type":"purchase"}}'

# 7) 评估：metric 窗口（user.login.count.7d ≥ 3 → REWARD；命中与否取决于 loadtest_ro 库实际行数）
curl -s -H Content-Type:application/json -X POST "$BASE/api/v1/rule/evaluate" \
  -d '{"tenantCode":"loadtest","sceneCode":"ops.promotion","eventType":"user.action.login","subjectId":"user-007","eventId":"evt-mw-001","occurredAt":"2026-06-10T09:00:00+08:00","payload":{"action_type":"login"}}'
```

预期结果见 [`expected-results.json`](./expected-results.json)。

## 四、注意点

1. **时间基准经 `asOf` 注入**：`time.window` 判 `EvalContext.now`，请求传 `asOf`（ISO-8601）覆盖当前时钟，便于复现历史时刻；`time.occurred_at` 判 `event.occurredAt`，与 `asOf` 无关。
2. **Metric 内嵌窗口 = 引擎自取**：`user.login.count.7d` 是 SQL_AGGREGATE，时间窗口在 SQL（`NOW() - INTERVAL 7 DAY`），`allowProvided=false`，公开评估不携带指标值（D55），引擎按 subjectId 查 `loadtest_ro`。
3. **payload 字段直接引用**：`action_type` 用 `valueRef=PAYLOAD` + 标准算子 `EQ`（`params.value`），不注册成 metric。
4. **PULL 同步 + decision 无 action**：`POST /api/v1/rule/evaluate` 当场返回 `finalDecision`；decision 均无 action（PULL 场景发布期拒绝带 action 的 decision）。

## 五、相关契约

- 时间条件参数契约见 [`../../03-rule-expression.md`](../../03-rule-expression.md) §七。
- API 字段以 [`../../10-api-contract.md`](../../10-api-contract.md) 为准。
