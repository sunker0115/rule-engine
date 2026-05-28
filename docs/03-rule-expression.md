# 03 — 规则表达式（占位草稿）

> **位置定位**：本文档承载规则的**可写性边界**——AST 节点结构 / 操作符清单 / 短路求值规则 / 节点级 trace 落点 / 哪些表达式 v1 不支持。当前**占位**，仅章节就位，内部具体内容待定。
>
> **前置阅读**：[`01-concepts.md`](./01-concepts.md) §3.5 AST 与"分组心智" + §3.6 Condition（含 ConditionType）、[`00-decisions.md`](./00-decisions.md) D12 / D15 / D20
>
> **解决什么疑问**："我能写多复杂的规则？""嵌套 AND/OR/NOT 怎么表达？""短路求值的边界是什么？""trace 在哪儿能看到节点求值结果？"
>
> **职责边界**——
> - ✅ AST 节点字段语义 / 操作符语义 / 短路规则 / trace 输出 / v1 不支持的表达式（指向 08-evolution）
> - ❌ 不写运行时调度（→ 02-runtime）、不写 ConditionType 扩展指南（→ 04-extension）、不写 node_trace 表结构（→ 05-storage）、不写编辑器 UI（→ 06-frontend）

---

## 一、文档状态

| 章节 | 状态 |
|------|------|
| §二 AST 节点结构 | ⏳ 未展开 |
| §三 操作符清单 | ⏳ 未展开 |
| §四 短路求值规则 | ⏳ 未展开 |
| §五 节点级 trace | ⏳ 未展开 |
| §六 v1 不支持的表达式 | ⏳ 未展开 |

---

## 二、AST 节点结构

⏳ 未展开。

> 展开时落定：AndNode / OrNode / NotNode / ConditionNode 各自的字段表 + 嵌套约束 + `displayLabel`（D19 派生：原 RuleGroup 信息降级为 AndNode/OrNode 的可选字段）+ `weight`（D12 派生：SCORECARD kind 启用）。

---

## 三、操作符清单

⏳ 未展开。

> 展开时落定：v1 支持的操作符（==/!=/>/</>=/<=/IN/NOT_IN/CONTAINS/MATCHES/...）+ 每个操作符的入参类型矩阵 + null 处理语义 + 类型强转规则（D20 §3 闭合校验前置：所有变量类型在发布时已知）。

---

## 四、短路求值规则

⏳ 未展开。

> 展开时落定：AND/OR/NOT 三种节点的短路顺序（按 sortOrder） + 节点失败时是否影响兄弟节点（D15 单节点失败 → satisfied=false 但整树继续）+ `EvalResult.partial=true` 何时置位 + `failedNodeIds` 收集规则。

---

## 五、节点级 trace

⏳ 未展开。

> 展开时落定：每个节点求值后输出的 trace 结构（nodeId / nodeType / satisfied / inputs / errorCode? / errorMessage?） + Pre-Gate 失败节点的 `PRE_GATE_BLOCKED` 类型标记（[`01-concepts.md`](./01-concepts.md) §3.14 派生）+ 落库通道（D21 TraceWriter 异步批写，指向 [`05-storage.md`](./05-storage.md) node_trace 表）。

---

## 六、v1 不支持的表达式

⏳ 未展开。

> 展开时落定：v1 明确不支持的表达式形态 + 替代方案 + 演进锚点——
>
> - 用户自定义 Java 函数调用（urule 风格 FunctionLibrary，已否决见 [`08-evolution.md`](./08-evolution.md) §四）
> - EXPRESSION_SCRIPT 叶子节点（D20 v1 不做，演进锚点 [`08-evolution.md`](./08-evolution.md) §2.1 / §2.13）
> - 跨规则引用 / 子规则调用（D6 评估即版本快照不可变 → 跨规则引用违背快照语义，留 v2）

---

## 七、维护原则

- 本文档只描述**可写性边界**，不重复 ConditionType 接口（→ 04-extension）、不写运行时短路实现细节（→ 02-runtime）。
- 新增操作符或节点类型必须更新 §二 + §三 + §五 trace 输出结构。
- v1 明确"不支持"的表达式如果未来在 08-evolution 中开放，§六 同步迁移到 §三。
