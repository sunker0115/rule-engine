# 10 — 对外 API 契约（占位草稿）

> **位置定位**：本文档承载 rule-engine 对调用方的**外部接口契约**——HTTP / RPC / SDK 签名 / 请求响应 DTO / errorCode + i18n 清单。前后端联调、对接方接入、SDK 升级都以本文档为准。当前**占位**，仅章节就位，内部具体内容待定。
>
> **前置阅读**：[`01-concepts.md`](./01-concepts.md) §3.3 RuleEvent / §3.4 Rule（含 EvalResult 输出契约）/ §3.8 Context（含 EvalContext 标准字段）、[`04-extension.md`](./04-extension.md) §五 元数据契约
>
> **解决什么疑问**："调用方要传什么 / 收到什么？""有哪些错误码 / 怎么对应文案？""SDK 怎么用？""dry-run 接口签名是什么？""前端拉元数据走哪个接口？"
>
> **职责边界**——
> - ✅ 对外接口签名 / 请求响应 DTO / errorCode 清单 / SDK 用法 / 版本兼容策略
> - ❌ 不写内部 SPI 接口（→ 04-extension）、不写概念字段语义（→ 01-concepts 字段表，本文档只贴 API 字段命名 + JSON 类型）、不写运行时调度（→ 02-runtime）、不写表结构（→ 05-storage）

---

## 一、文档状态

| 章节 | 状态 |
|------|------|
| §二 接口分组总览 | ⏳ 未展开 |
| §三 评估接口 | ⏳ 未展开（PUSH 异步 / PULL 同步 / dry-run） |
| §四 规则管理接口 | ⏳ 未展开（CRUD + 发布 + 灰度 + DISABLED 切换） |
| §五 元数据接口 | ⏳ 未展开（前端拉 ConditionType / ActionType / MetricSource） |
| §六 审计与查询接口 | ⏳ 未展开（audit_log / node_trace / evaluation_session 检索） |
| §七 errorCode 清单与 i18n | ⏳ 未展开 |
| §八 SDK 用法 | ⏳ 未展开（v1 调用方 SDK；v2 嵌入式 SDK 见 [`08-evolution.md`](./08-evolution.md) §2.14） |
| §九 版本兼容策略 | ⏳ 未展开（API 版本号 / 弃用流程 / 灰度切换） |

---

## 二、接口分组总览

⏳ 未展开。

> 展开时落定：四大接口分组的一句话职责 + 协议（HTTP / RPC / SDK）+ 调用方典型角色——
>
> | 分组 | 职责 | 协议 | 典型调用方 |
> |------|------|------|----------|
> | 评估 | RuleEvent → EvalResult / Action | HTTP + RPC + SDK | 业务系统（PUSH / PULL）|
> | 规则管理 | Rule / Scene CRUD + 发布 | HTTP | 运营平台前端 |
> | 元数据 | ConditionType / ActionType / MetricSource 元数据查询 | HTTP | 运营平台前端 + 接入方校验 |
> | 审计与查询 | audit_log / node_trace / evaluation_session 检索 | HTTP | 运营平台 + SRE |

---

## 三、评估接口

⏳ 未展开。

> 展开时落定：
>
> - **PUSH 异步**：`POST /api/v1/rule/event`，传 `RuleEvent`，返回 `{ accepted: true, eventId }`，Action 异步派发，调用方不等 EvalResult（D20 §2 派生）
> - **PULL 同步**：`POST /api/v1/rule/evaluate`，传 `RuleEvent`，返回 `EvalResult { satisfied, output?, errorCode?, errorMessage?, failedNodeIds?, partial }`，调用方按 fail-secure / fail-open 决策（D15 派生）
> - **dry-run**：`POST /api/v1/rule/dry-run`，传 `mockEvent + ruleVersionId`，返回节点级 trace + 命中 Action 列表但**不实际派发**（[`07-operability.md`](./07-operability.md) §四 派生）
> - Scene 必须声明 `dominantMode`，PUSH-only Scene 不开放 PULL 接口，反之亦然（[`README.md`](./README.md) §七 版本史 2026-05-25 派生决策）

---

## 四、规则管理接口

⏳ 未展开。

> 展开时落定：Rule / Scene CRUD + 发布（D19 状态机：DRAFT → PUBLISHING → PUBLISHED / PUBLISH_FAILED）+ DISABLED 切换 + 灰度 rollout 配置 + 回滚 = 用旧版本快照建新草稿 + 批量发布由前端逐条提交（v1 不提供批量原子 API）。

---

## 五、元数据接口

⏳ 未展开。

> 展开时落定：前端 / 接入方拉取 Scene 可见的 ConditionType / ActionType / MetricSource 元数据接口 + 元数据 JSON schema（type / displayName / paramSchema / i18nKey，[`04-extension.md`](./04-extension.md) §五 派生）+ 元数据变更后前端热刷新机制。

---

## 六、审计与查询接口

⏳ 未展开。

> 展开时落定：audit_log（D14） / evaluation_session / node_trace（D21） 的检索接口——按 actor / target / 时间过滤 + 分页 + 字段投影。SRE 监控查询通常走本组接口。

---

## 七、errorCode 清单与 i18n

⏳ 未展开。

> 展开时落定：v1 全部 errorCode 集中表（与 [`01-concepts.md`](./01-concepts.md) §3.7 / §3.11 / §3.14 同步）——
>
> - `UNRESOLVED_VARIABLE`（D20 §3 派生）
> - `METRIC_FETCH_FAIL`（D15 派生）
> - `HANDLER_EXCEPTION`（D18 派生）
> - `TIMEOUT`（v1 缺口补充派生）
> - `QUEUE_OVERFLOW`（D20 §2 派生）
> - `ZOMBIE_PUBLISHING`（D19 v1 落地范围派生）
> - `PRE_GATE_BLOCKED`（§3.14 派生，仅作为 node_trace 节点类型，不进 EvalResult.errorCode）
> - i18n key 映射 + 各语言文案路径（文案存前端 / 资源包，本文档只列 key 与场景）

---

## 八、SDK 用法

⏳ 未展开。

> 展开时落定：v1 调用方 SDK（Java / 可选其他语言）的初始化 / 调用 / 异常处理 + 配置项 + 与裸 HTTP 调用的能力差异。v2 嵌入式 SDK 模式（评估下沉到业务进程）锚点见 [`08-evolution.md`](./08-evolution.md) §2.14。

---

## 九、版本兼容策略

⏳ 未展开。

> 展开时落定：
>
> - URL 版本号约定（`/api/v1/...` vs `/api/v2/...`）
> - 字段新增 / 弃用流程（新增字段必须可选；弃用字段保留 N 个版本 + 弃用警告 header）
> - errorCode 新增 / 收紧规则
> - SDK 主版本号语义化（major 不兼容 / minor 兼容 / patch 修复）

---

## 十、维护原则

- 本文档**唯一持有对外 API 字段命名**——01-concepts 字段表与本文档 API 字段命名必须保持一一对应（语义在 01-concepts，JSON 字段名在本表）。
- 新增对外接口必须在 §二 + 对应分组登记 + §七 errorCode 同步。
- API 变更走 §九 版本兼容策略，破坏性变更必须先在 [`README.md`](./README.md) §七 版本史登记。
- 前后端联调 / 接入方接入以本文档为契约依据，发现描述与实际不一致以本文档为准（实现要回头改）。
