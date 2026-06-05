# Worked Example：夜间大额转账风控（B19 + B20 + B21 端到端）

> 设计期集成示例，日期 2026-06-05。**不是** `docs/examples/`（那是已实装能力的规约示例）——本例引用的 DATE/DATETIME、`time.window`、FETCHED 取数均**尚未实装**，仅用于验证三份设计 spec 的闭环：
> [B19 比较策略](./2026-06-05-b19-comparison-strategy-design.md) · [B20 时间框架](./2026-06-05-time-framework-design.md) · [B21 取数层](./2026-06-05-fetch-layer-design.md)

## 业务

夜间高危时段（上海时间 22:00–06:00、排除节假日），对【本次转账 ≥ 50000 且 KYC 已过期 且 近 7 天频繁转账 且 收款方高风险地区 且 事件非补录未来时间】的转账，判 `REVIEW`。

---

## 0. infra 预注册的命名句柄（运维配，不在 metric 表）

账密在 secrets，metric 只认逻辑名；外部目标白名单锁死。

```yaml
# application.yml
rule-engine:
  datasources:
    accountRo:                       # 只读副本
      url: ${ACCOUNT_RO_URL}
      username: ${ACCOUNT_RO_USER}
      password: ${ACCOUNT_RO_PASS}
      read-only: true
  http-endpoints:
    riskSvc:
      base-url: https://risk.internal
      auth: { type: header, name: X-Api-Key, value: ${RISK_API_KEY} }
      timeout-ms: 300
```

## 1. metric_definition（配置表，只引用逻辑名）

```jsonc
// txn.amount —— PROVIDED：转账事件自带，引擎不取
{ "metricCode":"txn.amount", "sourceType":"ATTRIBUTE", "dataType":"DOUBLE", "allowProvided":true }

// account.kyc_expire_at —— FETCHED / SQL，命名只读库
{ "metricCode":"account.kyc_expire_at", "sourceType":"SQL_AGGREGATE", "dataType":"DATETIME",
  "allowProvided":false, "cacheTtlSeconds":300,
  "params":{ "datasource":"accountRo",
             "sql":"SELECT kyc_expire_at FROM account WHERE id = :subjectId" } }

// account.txn_count_7d —— FETCHED / SQL，滚动窗口用 :now（不是 DB NOW()）
{ "metricCode":"account.txn_count_7d", "sourceType":"SQL_AGGREGATE", "dataType":"LONG",
  "allowProvided":false, "cacheTtlSeconds":0,           // 实时，不缓存
  "params":{ "datasource":"accountRo",
             "sql":"SELECT COUNT(*) FROM transfer WHERE user_id = :subjectId AND created_at >= :now - INTERVAL 7 DAY" } }

// payee.risk_level —— FETCHED / HTTP，命名端点
{ "metricCode":"payee.risk_level", "sourceType":"EXTERNAL_HTTP", "dataType":"STRING",
  "allowProvided":false, "cacheTtlSeconds":60,
  "params":{ "endpoint":"riskSvc", "path":"/region/{payload.payeeId}", "jsonPath":"$.level" } }
```

发布期校验（B21 §8）：`datasource:accountRo`、`endpoint:riskSvc` 必须已注册；SQL 不含 `NOW()`/`${}`（`account.txn_count_7d` 用 `:now` 通过）。

## 2. 规则 AST（6 个条件，B19 + B20 混用）

```jsonc
{ "type":"AndNode", "children":[
  { "conditionType":"time.window",                          // B20 conditionType，读 ctx.now
    "params":{ "start":"22:00","end":"06:00","timezone":"Asia/Shanghai",
               "datesExclude":["01-01","10-01","05-01"] } },
  { "conditionType":"time.occurred_at",                     // B20 conditionType，读 occurredAt
    "params":{ "operator":"BEFORE","value":"$now" } },
  { "conditionType":"GTE","metricCode":"txn.amount","dataType":"DOUBLE",
    "params":{ "threshold":50000 } },                       // B19 Numeric / BigDecimal
  { "conditionType":"DATE_BEFORE","metricCode":"account.kyc_expire_at","dataType":"DATETIME",
    "params":{ "threshold":"$now" } },                      // B20 DATETIME 策略 + $now
  { "conditionType":"GTE","metricCode":"account.txn_count_7d","dataType":"LONG",
    "params":{ "threshold":3 } },                           // B19 Numeric；值来自 :now SQL
  { "conditionType":"IN","metricCode":"payee.risk_level","dataType":"STRING",
    "params":{ "values":["HIGH","SANCTIONED"] } }           // B19 String
]}
```

> `dataType` 在发布期由 B19 的 `AstDataTypeResolver` 从 metric_definition 冻结进 ConditionNode，并校验算子×dataType 兼容（`GTE`↔DOUBLE/LONG、`DATE_BEFORE`↔DATETIME、`IN`↔STRING）。

## 3. 一次评估端到端

```
事件到达
 └─ ① 引擎入口取一次 now = Instant.now() → 注入 EvalContext.now（整树 + 取数共用）  [B20]
 └─ ② Pre-Gate（ROLLOUT 等）通过
 └─ ③ assemble 取数  [B21]:
       metric_dependencies = {txn.amount, account.kyc_expire_at, account.txn_count_7d, payee.risk_level}
       · txn.amount        → providedMetrics 有 + allowProvided=true → 直接用（PROVIDED）
       · 其余三个 FETCHED，并发（CompletableFuture）:
           account.kyc_expire_at → accountRo 只读库，SQL 绑 :subjectId
           account.txn_count_7d  → accountRo，SQL 绑 :subjectId + :now（= EvalContext.now）
           payee.risk_level      → riskSvc 端点 GET /region/{payeeId}，取 $.level
         失败/超时 → 该 metric ERROR，引用节点判 false，整树继续（D15）
       · MetricQuery.now 绑 EvalContext.now
 └─ ④ 评估 AST，每个二元节点走"解析 → 比较"两段式  [B19/B20]:
       time.window      : ctx.now 投影 Asia/Shanghai 墙上时间 → 判 22:00–06:00 + 非节假日
       time.occurred_at : occurredAt < ctx.now（$now 解析为 ctx.now）
       GTE txn.amount   : 解析段恒等 → Numeric 策略 BigDecimal 比 50000
       DATE_BEFORE kyc  : 解析段把 "$now"→Instant(ctx.now) → DATETIME 策略 Instant.compareTo
       GTE txn_count_7d : 解析段恒等 → Numeric 策略 比 3
       IN payee.risk    : 解析段恒等 → String 策略 逐元素 equals
 └─ ⑤ 全 true → 命中 → 判定 REVIEW
```

## 4. 闭环点（设计验证）

- **统一时钟 `now` 流到两处且一致**：AST 里的 `$now`（kyc、occurred_at）和 SQL 里的 `:now`（近 7 天窗口）绑的是**同一个 `EvalContext.now`**。
- **dry-run 重放**：注入历史 `now` → 时间条件 + 滚动窗口 SQL **一起平移**，结果与当时一致（若 SQL 用 DB `NOW()` 就废了——这正是 B21 §3.2 禁 DB 时间函数的原因）。
- **比较段全程纯**：`$now`/时区在 evaluator 解析段用 `ctx` 处理掉，Numeric/String/DATETIME 策略只拿到 `BigDecimal`/`String`/`Instant`，不碰 `ctx`（B19 接口不被破坏）。
- **外部资源零凭证落表 + 无 SSRF**：metric 只写 `accountRo`/`riskSvc` 逻辑名，连哪个库 / 打哪个服务由 infra 白名单定。
- **dataType 发布期冻结**（B19）：求值期 `forType(dataType)` 直接路由，不靠运行时猜类型。

## 5. 三份 spec 各自承担

| 节点 / 步骤 | 归属 spec |
|---|---|
| dataType 冻结、算子×dataType 校验、Numeric(BigDecimal)/String 策略、解析→比较两段式管线 | B19 |
| `EvalContext.now` 注入、`time.window`、`time.occurred_at`、DATE/DATETIME 类型与策略、`$now` 解析、时区解析序 | B20 |
| assemble 接线、并发 fetch、`MetricQuery.now`、SQL `:now` 范式、命名 DataSource(只读)/HTTP 端点、缓存、失败降级、发布期 SQL 安全扫描 | B21 |
