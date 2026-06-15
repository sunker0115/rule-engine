# Examples — 端到端真实场景案例库

> **目录用途**：承载端到端真实业务案例，作为新人首次接入 / 接入新场景 / 评审参考的具象材料。本目录与决策 / 概念 / 运行时文档互补——文档讲"为什么 + 怎么落"，案例讲"长什么样"。

> **案例有效性(2026-06-09)**：
> - ✅ 对齐当前实现、HTTP API 跑通：[`risk-control/high-risk-login/`](./risk-control/high-risk-login/)（新范式样板，含完整 curl 剧本）
> - ✅ 对齐当前实现：[`connector-standardization/`](./connector-standardization/)（D72 声明式 HTTP 连接器端到端：建 connector → 引用它的 EXTERNAL_HTTP metric → 发布 → 评估 → 热失效 → `:test` 验 trace → 清理）
> - 🛑 已过期、归档于 [`archive/`](./archive/)：`new-account-large-transfer`、`ticket-creation`、`user-register`、`time-conditions`——使用了废弃的 `metricBindings`/`decisions`/`PAYLOAD_COMPARE`/`_mockMetrics` 等形态（过期点对照见 high-risk-login README §五），按新范式重写后再移回业务域目录。

---

## 一、目录组织

按业务域分目录：

```
examples/
├── README.md           本文件
├── risk-control/       风控类（额度校验 / 黑名单 / 异常行为拦截）
├── marketing/          营销类（VIP 升级 / 满减 / 画像投放）
├── activity/           活动类（任务进度 / 奖励发放 / 兑换券）
├── patterns/           跨域技术模式（时间条件写法 / Metric sourceType 对比 / Pre-Gate 组合等）
└── archive/           已过期案例归档（用废弃形态，待按新范式重写后移回业务域目录）
```

⏳ 子目录内案例待逐步沉淀。

## 二、单个案例必含文件清单

每个案例独立目录，包含：

```
<example-name>/
├── README.md          场景描述 + 业务目标 + 关键决策引用
├── scene.json         Scene 定义（含 payloadSchema / eventTypes / dominantMode /
│                      subjectType；metric 为 tenant 级、decision 独立建，见 D54）
├── rules/             该 Scene 下的 Rule 定义（含 AST + preGates + decisionBindings；
│                      Action 挂在 Decision 上，rules/ 不含 Action 配置，见 D27）
├── metrics/           Scene 用到的 metric 注册（含 sourceType / dataType /
│                      cacheTtlSeconds / allowProvided）
├── mock-events.json   mock 的 RuleEvent 样本（payload 驱动；公开评估只收 payload，D55）
└── expected-results.json   各 mock event 的预期 EvalResult
                            （含 finalDecision / hitDecisions / trace.metricSources）
```

## 三、案例编写约定

- **聚焦一个业务问题**：一个案例只解一个典型业务问题，不试图穷尽
- **可 dry-run 验证**：所有案例必须能用 [`10-api-contract.md`](../10-api-contract.md) §三 dry-run 接口跑通
- **指向相关决策**：案例 README 在末尾列出与该案例相关的决策（如"本案例展示 D11 Job 模式 + D17 灰度桶"）
- **真实而脱敏**：业务背景真实，但字段命名 / 数值 / 主体 ID 全部脱敏
- **可演进**：案例数据被框架 v2 演进影响时（如 D12 kind 多态扩展），同步更新或归档

## 四、阅读路径建议

- **新人首次** → 选 `risk-control/` 或 `marketing/` 下最简单案例 → 对照 [`../01-concepts.md`](../01-concepts.md) 一等概念
- **接入新场景** → 找业务域最接近的案例 → 复制 → 改 Scene / Rule
- **学习某个技术模式** → 看 `patterns/`（时间条件 / sourceType 对比 / payload 直接引用 等）
- **评审 / 设计参考** → 按相关决策反查案例

## 五、维护原则

- 新增案例必须自带 dry-run 跑通的 expected-results.json
- 框架破坏性变更后扫一遍 examples/，过时案例归档到 `archive/`（与 [`../README.md`](../README.md) §五 文档导航中的归档约定一致）
- 案例 README 引用主文档时用相对路径
