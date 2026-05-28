# 06 — 前端架构（占位草稿）

> **位置定位**：本文档承载规则**编辑器与运营平台**的前端架构——三栏布局 / 元数据驱动渲染 / dry-run 交互 / 灰度配置 / 审计日志查看。当前**占位**，仅章节就位，内部具体内容待定。
>
> **前置阅读**：[`01-concepts.md`](./01-concepts.md)、[`04-extension.md`](./04-extension.md) §五 元数据契约、[`10-api-contract.md`](./10-api-contract.md)
>
> **解决什么疑问**："运营怎么自助配规则？""前端怎么知道有哪些 ConditionType / ActionType 可选？""dry-run 怎么交互？""灰度配置 UI 是什么样？"
>
> **职责边界**——
> - ✅ 前端布局 / 渲染机制 / 交互流程 / 与后端的契约对齐
> - ❌ 不写后端 API 接口（→ 10-api-contract）、不写 SPI 接口（→ 04-extension）、不写运行时调度（→ 02-runtime）、不写表结构（→ 05-storage）

---

## 一、文档状态

| 章节 | 状态 |
|------|------|
| §二 三栏布局 | ⏳ 未展开 |
| §三 元数据驱动渲染机制 | ⏳ 未展开 |
| §四 dry-run UI | ⏳ 未展开 |
| §五 灰度配置 UI | ⏳ 未展开 |
| §六 审计日志查看 UI | ⏳ 未展开 |

---

## 二、三栏布局

⏳ 未展开。

> 展开时落定：左栏（Scene / Rule 导航树）+ 中栏（AST 编辑画布）+ 右栏（属性面板 + 元数据预览 + dry-run 结果） 的视觉结构 + 交互流转。

---

## 三、元数据驱动渲染机制

⏳ 未展开。

> 展开时落定：
>
> - 前端启动 / 切换 Scene 时拉取该 Scene 可见的 ConditionType / ActionType / MetricSource 元数据（[`10-api-contract.md`](./10-api-contract.md) 接口对齐）
> - 根据元数据中 `paramSchema` 自动渲染参数表单（不写死字段）
> - 元数据变更 → 前端热刷新可选项清单（无需前端代码改动配合后端扩展）
> - i18n key 在元数据声明，文案在前端按当前语言取
>
> 这是 README §六 "注册中心 + 元数据驱动" 抽象在前端的落地。

---

## 四、dry-run UI

⏳ 未展开。

> 展开时落定：用户在编辑器内构造 mockEvent + 选择某个 RuleVersion → 同步调用后端 dry-run 接口 → 返回每个节点的求值 trace → 前端可视化（每个 AST 节点上叠加 ✅/❌/⏭ 图标 + hover 显示输入与输出 + Pre-Gate 失败的节点显示 `PRE_GATE_BLOCKED`）。dry-run 接口定义在 [`10-api-contract.md`](./10-api-contract.md)。

---

## 五、灰度配置 UI

⏳ 未展开。

> 展开时落定：`Rule.rollout` 结构子表（type / percentage / tagConditions）的可视化配置 + 灰度桶基于 `(subjectId, ruleVersionId)` hash 的算法说明（[`01-concepts.md`](./01-concepts.md) §3.4 + D17 派生）+ 灰度状态实时展示（多少主体落在灰度桶内）。

---

## 六、审计日志查看 UI

⏳ 未展开。

> 展开时落定：`audit_log` 表（D14）的检索 UI——按 actor / target / action / 时间过滤 + before/after 快照对比可视化 + `after_snapshot.errorCode` 已知值清单（`UNRESOLVED_VARIABLE` / `ZOMBIE_PUBLISHING` / `HANDLER_EXCEPTION`，[`01-concepts.md`](./01-concepts.md) §3.11）。

---

## 七、维护原则

- 本文档只描述**前端架构与交互**，不复制后端 API 字段（→ 10-api-contract）、不写元数据 schema 细节（→ 04-extension §五）。
- 新增前端模块（如 v2 嵌入式 SDK 模式下的"无 UI 接入"）要在本文档对应位置标注 v1 / v2 差异。
- 前端拉元数据的接口变更必须先在 10-api-contract 落定，本文档再回写。
