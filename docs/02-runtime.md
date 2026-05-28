# 02 — 运行时全链路（占位草稿）

> **位置定位**：本文档承载"一个 RuleEvent 进来到 Action 落地"的**全链路时序**——Trigger 接入 / Matcher 检索 / Pre-Gate 拦截 / EvalContext 装配 / AST 评估 / Action 派发各阶段衔接。当前**占位**，仅章节就位，内部具体内容待定。
>
> **前置阅读**：[`README.md`](./README.md)、[`01-concepts.md`](./01-concepts.md)、[`00-decisions.md`](./00-decisions.md) D6 / D17 / D20 / D21
>
> **解决什么疑问**："事件进来后引擎内部依次发生了什么？""evaluation_session 在哪一步开始 / 结束？""metric 在哪一步预拉？Action 在哪一步派发？"
>
> **职责边界**——
> - ✅ 阶段时序 / 各阶段输入输出契约 / evaluation_session 生命周期 / 失败语义聚合
> - ❌ 不写 AST 节点字段（→ 03-rule-expression）、不写扩展接口签名（→ 04-extension）、不写表结构（→ 05-storage）、不写运维参数（→ 07-operability）、不写决策权衡（→ 00-decisions）

---

## 一、文档状态

| 章节 | 状态 |
|------|------|
| §二 整体时序 | ⏳ 未展开 |
| §三 各阶段细节 | ⏳ 未展开（Trigger / Matcher / Pre-Gate / EvalContext / Evaluator / Dispatcher） |
| §四 evaluation_session 生命周期 | ⏳ 未展开 |
| §五 失败语义聚合 | ⏳ 未展开（汇总 D15 单节点 / 单规则 / Action 失败的传播规则） |

---

## 二、整体时序

⏳ 未展开。

> 展开时落定：从 RuleEvent 进入到最后一个 Action 完成（或 PULL 返回 EvalResult）的全链路 ASCII 时序图 + 每一跳的输入输出 + 同步/异步边界标注（D20 §2 异步 Dispatcher / D21 异步 TraceWriter）。

---

## 三、各阶段细节

⏳ 未展开。

> 展开时落定各阶段一节（Trigger / Matcher / Pre-Gate / EvalContext 装配 / AST Evaluator / Action Dispatcher）——
>
> - **输入契约**（上一阶段产物 + EvalContext 标准字段）
> - **核心动作**（核心代码逻辑级描述，不贴具体类名）
> - **输出契约**（下一阶段消费的产物）
> - **失败时怎么办**（指向 §五）
> - **trace 落点**（指向 [`05-storage.md`](./05-storage.md) node_trace 表）

---

## 四、evaluation_session 生命周期

⏳ 未展开。

> 展开时落定：session 创建时机（Matcher 命中后 / Pre-Gate 通过后）+ session 三层角色复用（D21 §3 派生：幂等收口 / 对账分母 / 外键时序）+ session 状态机（INIT → RUNNING → COMPLETED / ERROR）+ v1 同步落库 vs v2 异步路径（指向 [`08-evolution.md`](./08-evolution.md) §2.15）。

---

## 五、失败语义聚合

⏳ 未展开。

> 展开时落定：D15 决策的运行时落点——ConditionNode 失败如何短路 / 多 Rule 间隔离 / Action 失败语义（D18 continue-on-error vs failFast）/ ERROR 三态对账。明示 PUSH vs PULL 的失败可见性差异。

---

## 六、维护原则

- 本文档只描述**运行时时序与契约**，不重复字段表（→ 01-concepts）、不贴 SQL（→ 05-storage）、不写参数默认值（→ 07-operability）。
- 新增运行时阶段（如未来 §2.13 评估期预编译切换）必须更新 §二 时序图 + §三 对应阶段。
- v1 同步路径变更或 v2 异步路径锚点更新时，§四 evaluation_session 章节同步回写。
