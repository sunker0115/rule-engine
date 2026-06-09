# 案例：用户注册风控（providedMetrics 上报 + 同步评估）

> 🛑 **已过期（2026-06-09）· 当前实现下跑不通** — 使用了废弃形态（`scene.json` 内嵌 `metricBindings`/`decisions`、条件用 `METRIC_COMPARE`/`PAYLOAD_COMPARE`、mock 用 `_mockMetrics` 等），与当前 API 不符，待按新范式重写。
> 👉 可跑通的当前样板：[`../../risk-control/high-risk-login/`](../../risk-control/high-risk-login/)（过期点逐项对照见其 README §五）。

## 业务背景

用户注册完成后，业务系统需要同步拿到风控结论（REJECT/REVIEW/PASS），决定是否允许账号激活。

业务方在注册时已经采集了设备指纹分、IP 信誉分、渠道来源等数据，不需要引擎再绕回去查。通过 `providedMetrics` 随评估请求一起传入，引擎直接使用，跳过 sourceType 取数。

## 关键配置

| 维度 | 值 |
|------|----|
| Scene | `risk.register`（PULL 模式，同步返回） |
| Decision | REJECT(1) / REVIEW(2) / PASS(100) |
| 触发事件 | `user.registered` |
| providedMetrics | `user.device.trust.score` / `user.ip.reputation.score` / `user.kyc.level` |
| allowProvided=false | `user.blacklist.hit`（权威黑名单，不允许业务方覆盖） |

## 评估流程

```
业务系统完成用户注册
        │
        ▼
POST /api/scenes/risk.register/evaluate
{
  "eventType": "user.registered",
  "subjectId": "user-001",
  "payload": { "channel": "mobile", "ip_country": "CN" },
  "providedMetrics": {
    "user.device.trust.score": 35,
    "user.ip.reputation.score": 20,
    "user.kyc.level": 0
    // user.blacklist.hit 不传 → allowProvided=false，引擎走 SQL 权威查询
  }
}
        │
        ▼
EvalContext 构建
  user.device.trust.score  → 来源 PROVIDED（35）
  user.ip.reputation.score → 来源 PROVIDED（20）
  user.kyc.level           → 来源 PROVIDED（0）
  user.blacklist.hit       → 来源 FETCHED（SQL 查询）
        │
        ▼
AST 求值
        │
  true  │  false
        │
        ▼
finalDecision = REJECT / REVIEW / PASS
        │
        ▼
同步返回 EvalResult，业务方决定是否激活账号
```

## AST 条件树（rule-register-risk.json）

```
OrNode("注册风险命中其一")
├── AndNode("高风险设备 + 低信誉 IP")
│   ├── user.device.trust.score < 40     设备信任分低
│   └── user.ip.reputation.score < 30   IP 信誉分低
├── user.blacklist.hit == true           命中黑名单（权威源）
└── AndNode("未完成 KYC + 高风险渠道")
    ├── user.kyc.level == 0              未完成任何 KYC
    └── payload.channel == "unknown"     来源渠道未知
```

## 目录

```
provided-metrics/
├── README.md
├── scene.json
├── metrics/
│   └── metrics.json        4 个指标（含 allowProvided=false 示例）
├── rules/
│   └── rule-register-risk.json
├── mock-events.json         5 个测试事件
└── expected-results.json
```

## 相关决策

- D30 providedMetrics：`user.device.trust.score` / `user.ip.reputation.score` / `user.kyc.level` 来自 `providedMetrics`
- D30 allowProvided=false：`user.blacklist.hit` 不允许业务方覆盖，引擎走 SQL 权威查询
- D15 评估失败语义：若 `user.blacklist.hit` SQL 取数失败 → METRIC_FETCH_FAIL，调用方按 fail-secure 拦截
- D26 Decision 合成：HIGHEST_PRIORITY，REJECT(1) 优先于 REVIEW(2)
