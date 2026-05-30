# 案例：时间条件三种写法

## 业务背景

同一个营销 Scene（`ops.promotion`）内，三条规则分别演示时间条件的三种写法：

| 规则 | 时间条件类型 | 业务含义 |
|------|-------------|---------|
| `rule-time-window` | `time.window` | 规则只在工作时段（工作日 09:00–22:00）生效 |
| `rule-occurred-at` | `time.occurred_at` | 事件业务时间落在活动期间（2026-06-01 至 2026-06-30） |
| `rule-metric-window` | 间接时间（Metric 内嵌窗口） | 近 7 天登录次数 ≥ 3 次（活跃用户判定） |

每条规则命中时输出 `REWARD` Decision（营销奖励），不命中则 `PASS`。

## 目录

```
time-conditions/
├── README.md
├── scene.json
├── rules/
│   ├── rule-time-window.json      规则一：当前时刻在时间窗口内
│   ├── rule-occurred-at.json      规则二：事件业务时间在活动期间
│   └── rule-metric-window.json    规则三：间接时间窗口（Metric 聚合）
├── metrics/
│   └── metrics.json               规则三用到的 Metric
├── mock-events.json               9 个测试事件（每条规则 3 个）
└── expected-results.json          各事件预期结果
```

## 关键参数对比

| | `time.window` | `time.occurred_at` | 间接时间（Metric） |
|--|--|--|--|
| 时间来源 | `EvalContext.now`（引擎注入） | `EvalContext.event.occurredAt` | SQL 内部 `NOW() - INTERVAL N DAY` |
| 条件层感知时间 | 是 | 是 | 否（只看数值） |
| 适合场景 | 规则生效时段 | 活动有效期 / 事件时间范围 | 历史聚合行为 |

详细参数契约见 [`docs/03-rule-expression.md`](../../03-rule-expression.md) §七。

## Mock 事件设计说明

- E1–E3：测试 `rule-time-window`，分别对应工作时段内 / 工作时段外 / 周末
- E4–E6：测试 `rule-occurred-at`，分别对应活动期间内 / 活动期间前 / 活动期间后
- E7–E9：测试 `rule-metric-window`，分别对应高活跃用户(7d登录≥3) / 低活跃用户 / 零活跃用户
