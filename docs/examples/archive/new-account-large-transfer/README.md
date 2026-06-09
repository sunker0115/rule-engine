# 案例：新账户大额转账拦截

> 🛑 **已过期（2026-06-09）· 当前实现下跑不通** — 使用了废弃形态（`scene.json` 内嵌 `metricBindings`/`decisions`、条件用 `METRIC_COMPARE`/`PAYLOAD_COMPARE`、mock 用 `_mockMetrics` 等），与当前 API 不符，待按新范式重写。
> 👉 可跑通的当前样板：[`../../risk-control/high-risk-login/`](../../risk-control/high-risk-login/)（过期点逐项对照见其 README §五）。

## 业务背景

用户发起转账时，引擎**同步**返回 REJECT / REVIEW / PASS，业务方据此决定放行还是拦截。

典型风险画像：账户开立不足 30 天 **或** KYC 未完成，且转账金额 ≥ 5 万 CNY，且近 7 天历史转账次数极少，且收款方信任分低。

## 关键配置

| 维度 | 值 |
|------|----|
| Scene | `risk.transfer`（PULL 模式） |
| Decision | REJECT(priority=1) / REVIEW(priority=2) / PASS(priority=100) |
| 触发事件 | `transfer.initiated` |
| Pre-Gate | 频次上限（每用户每天最多命中 1 次）+ 白名单豁免 |
| decisionStrategy | `HIGHEST_PRIORITY` |

## 目录

```
new-account-large-transfer/
├── README.md                 本文件
├── scene.json                Scene 定义
├── rules/
│   └── block-new-account.json  规则定义（含 AST）
├── metrics/
│   └── metrics.json          用到的 Metric 注册
├── mock-events.json          6 个测试事件
└── expected-results.json     各事件预期 EvalResult
```

## 评估流程说明

```
transfer.initiated 事件进入
        │
        ▼
Pre-Gate 检查
  ✓ ROLLOUT: 100% 放行
  ✓ RATE_LIMIT: 今日首次命中才继续（同用户每天限 1 次）
  ✓ WHITELIST: 白名单用户直接跳过本规则
        │
        ▼
metric 并发预拉
  user.account.age.days / user.kyc.level /
  user.transfer.count.7d / user.transfer.dest.trust.score
        │
        ▼
AST 求值（见下方条件树）
        │
  true  │  false
        │
        ▼
finalDecision = REJECT  →  EvalResult 返回调用方
调用方据此抛出拦截异常
```

## AST 条件树

```
AndNode("全部满足")
├── OrNode("账户风险")                  ← 满足其一即可
│   ├── account.age.days < 30           开户不足 30 天
│   └── kyc.level < 2                   KYC 未完成
├── AndNode("大额 CNY 交易")            ← 两个条件同时满足
│   ├── payload.amount >= 50000         金额 ≥ 5 万
│   └── payload.currency == "CNY"       币种为 CNY
├── transfer.count.7d < 3               近 7 天转账次数 < 3
└── NotNode                             收款方信任分不高
    └── dest.trust.score >= 80
```

## 相关决策

- D3 多租户：`tenant_id` 贯穿所有配置
- D5 触发模型：单事件触发，metric 内部 SQL 聚合时间窗
- D6 版本与灰度：Pre-Gate ROLLOUT 100%，无灰度
- D15 评估失败语义：任一 metric 取数失败 → errorCode 非空，调用方按 fail-secure 拦截
- D20 EvalContext 批量预拉：4 个 metric 并发一次取完
- D26 Decision 合成：HIGHEST_PRIORITY，单规则命中直接取 REJECT
- D27 Action 归属：PULL 模式，Decision.actions 为空
