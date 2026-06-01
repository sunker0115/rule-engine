# 06 — 前端架构

> **位置定位**：本文档承载规则**编辑器与运营平台**的前端架构——三栏布局 / 元数据驱动渲染 / dry-run 交互 / 灰度配置 / 审计日志查看。
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
| §二 三栏布局 | ✅ |
| §三 元数据驱动渲染机制 | ✅ |
| §四 dry-run UI | ✅ |
| §五 灰度配置 UI | ✅ |
| §六 审计日志查看 UI | ✅ |
| §七 技术栈与工程目录 | ✅ |

---

## 二、三栏布局

规则编辑器采用三栏布局：

```
┌──────────────────┬────────────────────────────────┬──────────────────┐
│  左栏            │  中栏（主编辑区）                │  右栏            │
│  Scene / Rule 树  │  AST 可视化编辑器               │  属性面板        │
│  ─────────────── │  ──────────────────────────── │  ─────────────── │
│  · Scene 列表    │  · 拖拽 / 点击编辑节点           │  · dry-run 结果  │
│  · Rule 列表     │  · 节点类型下拉（元数据驱动）     │  · 每节点 ✅/❌/⏭ │
│  · 版本历史      │  · 参数表单（动态渲染）           │  · actualValue   │
│  · 状态标签      │  · Pre-Gate 配置                │  · 错误详情      │
│                  │  · Decision 绑定配置             │  · audit_log 条目 │
└──────────────────┴────────────────────────────────┴──────────────────┘
```

**交互原则：**
- 左栏选中 Rule → 中栏加载对应 RuleVersion 的 conditionAst
- 中栏编辑 → 临时草稿态（不触发自动保存）；点"保存草稿"才提交 POST
- 右栏默认显示最近 dry-run 结果；每次点"试算"刷新

---

## 三、元数据驱动渲染机制

编辑器不硬编码 ConditionType / ActionType 表单，而是：

1. 进入编辑器时调用 `GET /api/v1/scenes/{sceneCode}/metadata`，拿到：
   - `conditionTypes[]`（含 paramsSchema）
   - `actionTypes[]`（含 paramsSchema）
   - `availableMetrics[]`
2. 用户选择节点类型后，按 `paramsSchema`（JSON Schema）动态渲染参数表单
3. `requiresMetric=true` 的 ConditionType（如 `metric.threshold`）同时渲染 metric 下拉框（来自 availableMetrics）

**JSON Schema → 表单组件映射（v1 最小集）：**

| JSON Schema 类型 | 表单控件 |
|-----------------|---------|
| `string` | 文本输入框 |
| `string` + `enum` | 下拉选择框 |
| `number` | 数字输入框 |
| `boolean` | 开关 |
| `object` | 嵌套表单 |

**好处：** 业务方新注册 ConditionType（`@ConditionType` 注解 + paramsSchema）后，前端编辑器无需改代码即可渲染新类型的参数表单。详见 `04-extension.md §五` 元数据契约。

---

## 四、dry-run UI

1. 右栏点击"试算"按钮 → 弹出 mockEvent 编辑框（JSON 编辑器，预填 Schema 必填字段）
2. 可选：指定 `ruleVersionId`（默认当前版本）；填写 `providedMetrics`（D30）
3. 调用 `POST /api/v1/rule/dry-run` → 返回含 `nodeTrace` 的 Response（见 10-api-contract §3.3）
4. 右栏渲染 AST trace 树：
   - `result=true` → 节点显示 ✅
   - `result=false` → 节点显示 ❌
   - `result=null`（短路跳过）→ 节点显示 ⏭
   - hover 节点 → tooltip 展示 `actualValue / valueSource / errorCode`
5. Pre-Gate 失败时：显示 `PRE_GATE_BLOCKED: <gateType>`（大红色提示），AST 部分灰化

**dry-run 不派发 Action**，`actionResults` 中所有 Action 显示 `SKIPPED`（见 10-api-contract §3.3）。

---

## 五、灰度配置 UI

在 Rule 编辑器的 Pre-Gate 配置区：
- **ROLLOUT Gate**：百分比滑块（0–100%）+ 实时显示"约 X% 流量命中此规则"
- **WHITELIST / BLACKLIST**：名单 key 下拉框（来自平台预设名单列表）
- **RATE_LIMIT**：QPS / QPM 数字输入框 + 时间窗口选择

灰度发布建议工作流显示在侧边面板（仅 ROLLOUT Gate 显示）：

```
当前 percentage = 5%
→ 可调至 20%（需二次确认）
→ 可调至 100%（需二次确认）
```

回退路径：将 ROLLOUT.percentage 调回 0，或将 rule_definition.status 改为 DISABLED（UI 提供"立即停用"按钮）。

---

## 六、审计日志查看 UI

在 Rule 详情页右侧抽屉（通过 `GET /api/v1/audit-logs` 查询）：
- 按时间倒序列出 `audit_log` 条目（PUBLISH / DISABLE / UPDATE 等，枚举值同 05-storage DDL `audit_log.action`）
- 每条目展开 → diff 视图（before_snapshot vs after_snapshot，JSON diff 高亮）
- 点击"操作人"→ 可按 actor 过滤；同时显示 actorType（USER / SYSTEM / JOB）
- 发布失败条目（`action=PUBLISH_FAILED`）：红色标记 + `after_snapshot.errorCode` tooltip（`UNRESOLVED_VARIABLE` / `METRIC_NOT_BOUND` 等，完整清单见 10-api-contract §七）

---

## 七、技术栈与工程目录

技术栈决策见 [`00-decisions.md`](./00-decisions.md) D31。前端工程放 `frontend/` 目录，与 `src/` 平级。

**技术栈**：

| 层 | 选择 |
|---|---|
| 框架 | React 18 |
| 条件编辑器 | react-querybuilder |
| UI 组件库 | Ant Design 5 |
| 构建工具 | Vite |
| 状态管理 | Zustand |

**工程目录**：

```
frontend/
├── index.html
├── vite.config.ts
├── package.json
├── tsconfig.json
├── src/
│   ├── main.tsx                        # React 入口
│   ├── App.tsx                         # 路由根组件
│   ├── store/                          # Zustand store
│   │   ├── sceneStore.ts               # Scene / Rule 树状态
│   │   └── dryRunStore.ts              # dry-run 执行状态与结果
│   ├── api/                            # HTTP 请求封装（对接 10-api-contract.md）
│   │   ├── client.ts                   # axios 实例，注入 X-Actor-Id header
│   │   ├── scene.ts                    # Scene / Rule CRUD
│   │   ├── eval.ts                     # 评估 / dry-run 接口
│   │   └── audit.ts                    # 审计日志接口
│   ├── pages/
│   │   ├── scene-list/                 # Scene 列表页
│   │   ├── rule-editor/                # 规则编辑主页（三栏布局）
│   │   │   ├── index.tsx               # 布局骨架（左：规则树 | 中：AST 编辑器 | 右：属性面板）
│   │   │   ├── RuleTree.tsx            # 左栏：Scene/Rule 树（Ant Design Tree）
│   │   │   ├── ConditionEditor.tsx     # 中栏：react-querybuilder 封装
│   │   │   └── PropertyPanel.tsx       # 右栏：规则属性 / paramsSchema 动态表单
│   │   ├── dry-run/                    # dry-run 执行与结果展示
│   │   └── audit/                      # 审计日志查询与 diff 展示
│   └── components/
│       ├── dry-run-result/             # 节点级 ✅/❌/⏭ + actualValue 叠加层
│       └── params-schema-form/         # 根据 paramsSchema JSON 动态渲染表单控件
└── public/
```

**关键设计约定**：

| 约定 | 说明 |
|------|------|
| `X-Actor-Id` | `api/client.ts` 统一注入，值从 localStorage 读取（无登录，D14） |
| AST ↔ react-querybuilder 转换 | `ConditionEditor.tsx` 内完成，其他组件只看引擎 AST 格式 |
| paramsSchema 渲染 | `ParamsSchemaForm` 根据 `conditionType / actionType` 的 schema 动态生成输入控件 |
| dry-run 结果叠加 | `DryRunResult` 用 react-querybuilder 的 `ruleGroupProps` 覆盖，不修改 AST 数据结构 |
| 前后端跨域 | Vite dev proxy 转发到本地后端；生产由 Nginx 同域反代 |

---

## 八、维护原则

- 本文档只描述**前端架构与交互**，不复制后端 API 字段（→ 10-api-contract）、不写元数据 schema 细节（→ 04-extension §五）。
- 新增前端模块（如 v2 嵌入式 SDK 模式下的"无 UI 接入"）要在本文档对应位置标注 v1 / v2 差异。
- 前端拉元数据的接口变更必须先在 10-api-contract 落定，本文档再回写。
