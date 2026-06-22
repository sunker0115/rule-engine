# 连接器标准化 P4 — 前端编辑器 + 文档/端到端实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 前端连接器编辑器（JSON Schema 驱动表单 + 信封预设 + 内联自助测试面板）让业务方零代码接入；落决策日志 + 可复制端到端剧本；全链路 e2e 收口。

**Architecture:** 复用 `frontend/src/components/params-schema-form/` 渲染描述符字段；连接器 CRUD 走 `api/connector.ts`；测试面板调 `:test` 端点展示分阶段 trace。镜像现有 metric-list/metric-detail 页结构。

**Tech Stack:** React + TypeScript + i18n（zh-CN/en）。

**前置：** P1（写 API）、P2（运行时）、P3（`:test` 端点）已合。

**环境：** 前端构建/测试照 `frontend/` 现有脚本（`package.json`）。后端 e2e 用打包产物起服务。

---

## 文件结构

**Create（frontend）：**
- `frontend/src/types/connector.ts` — descriptor TS 类型（镜像后端 record）
- `frontend/src/api/connector.ts` — CRUD + test 调用
- `frontend/src/pages/connector-list/index.tsx`
- `frontend/src/pages/connector-detail/index.tsx`（编辑器 + 预设 + 测试面板）
- `frontend/src/i18n/locales/zh-CN/connector.ts` + `frontend/src/i18n/locales/en/connector.ts`

**Modify（frontend）：**
- `frontend/src/constants/api-endpoints.ts`（加 CONNECTOR_*）
- `frontend/src/router.tsx`、`frontend/src/config/menu.tsx`
- i18n locale 索引（注册新 connector 命名空间）

**Modify（docs）：**
- `docs/00-decisions.md`（追加决策，不改历史）
- `docs/examples/`（新增 connector 端到端剧本目录）

> 前端测试：照 `frontend/` 现有测试范式（若有 vitest/jest + RTL 则写组件测试；若前端无测试基建，则以"构建通过 + 手测要点清单"为验收，**明确标注未自动验证**，CLAUDE.md UI 改动纪律）。每步先查 `frontend/package.json` 的 test 脚本确定路径。

---

## Task 1: 类型 + API 客户端 + 端点常量

**Files:**
- Create: `frontend/src/types/connector.ts` `frontend/src/api/connector.ts`
- Modify: `frontend/src/constants/api-endpoints.ts`

参考：`frontend/src/types/metric.ts`、`frontend/src/api/metric.ts`。

- [ ] **Step 1: 写 TS 类型（镜像 ConnectorDescriptor）**

`connector.ts`：定义 `HttpMethod`/`AuthKind`/`CompareOp`/`RetryTrigger` union、`TemplateParam`/`Predicate`/`ResponseMapping`/`HttpRequestTemplate`/`AuthScheme`（按 kind 区分）/`ResiliencePolicy`/`CircuitBreakerPolicy`/`ErrorRule`/`ConnectorDescriptor`、`ConnectorListItem`、`FetchTrace`。字段名与后端 JSON 完全一致。

- [ ] **Step 2: 加端点常量**

`api-endpoints.ts` 加 `CONNECTORS`（`/admin/v1/connectors`）、`CONNECTOR_TEST(code)`（`/admin/v1/connectors/${code}:test`）、`METRIC_TEST(code)`（`/admin/v1/metrics/${code}:test`）。

- [ ] **Step 3: 写 API 客户端**

`api/connector.ts`：`listConnectors(tenantId)`、`createConnector(tenantId, code, body)`、`updateConnector(code, tenantId, body)`、`testConnector(code, tenantId, sample)` / `testMetric(code, tenantId, sample)`。照 `api/metric.ts` 的请求封装 + header `X-Actor-Id`。

- [ ] **Step 4: 构建检查 → Commit**

Run（在 frontend）：`npm run build`（或现有构建脚本）
```bash
git add frontend/src/types/connector.ts frontend/src/api/connector.ts frontend/src/constants/api-endpoints.ts
git commit -m "feat(frontend): 连接器类型 + API 客户端 + 端点常量"
```

---

## Task 2: 连接器列表页

**Files:**
- Create: `frontend/src/pages/connector-list/index.tsx`
- Modify: `frontend/src/router.tsx` `frontend/src/config/menu.tsx`

参考：`frontend/src/pages/`(metric/scene 列表页)、现有列表自适应 + 时间格式（近期 commit `8013eda8`）。

- [ ] **Step 1: 写列表页**

列 connectorCode/name/status，行操作进详情/新建。复用现有列表组件与 i18n（`t()` 调用，标签走 connector 命名空间）。

- [ ] **Step 2: 挂路由 + 菜单**

`router.tsx` 加 `/connectors` 路由；`menu.tsx` 加菜单项（i18n key）。

- [ ] **Step 3: 构建 + 手测要点 → Commit**

手测：菜单进入、列表加载、字段显示。**若无自动化前端测试，提交说明标"未自动验证，手测通过"**。
```bash
git add frontend/src/pages/connector-list/ frontend/src/router.tsx frontend/src/config/menu.tsx
git commit -m "feat(frontend): 连接器列表页 + 路由菜单"
```

---

## Task 3: 连接器编辑器（JSON Schema 表单 + 信封预设）

**Files:**
- Create: `frontend/src/pages/connector-detail/index.tsx`
- 可能新增子组件：`frontend/src/pages/connector-detail/EnvelopePresets.tsx`

复用 `frontend/src/components/params-schema-form/index.tsx`（已支持 string/number/boolean/object/enum/array 动态渲染）渲染 descriptor 各字段。

- [ ] **Step 1: 写编辑器主体**

按 descriptor 结构分区渲染：endpointRef（下拉，来源待 endpoint 列表 API——若无则文本输入 + 校验留后端）、request（method/path/query/headers/body）、response（successWhen path/op/value + valuePath）、auth（按 kind 切换字段）、resilience、errorMapping。用 `ParamsSchemaForm` 或受控表单。create/update 调 API。

- [ ] **Step 2: 信封预设**

`EnvelopePresets`：三按钮 `{code,msg,data}` / 裸 JSON / `{success,data}`，点击填充 `response.successWhen` + `valuePath` 默认值（如 `{code,msg,data}` → successWhen `code EQ 0`、valuePath `data.<字段>`）。设计 §10。

- [ ] **Step 3: 构建 + 手测 → Commit**

手测：新建连接器、切换 auth kind、点预设填充、保存成功。标注验证方式。
```bash
git add frontend/src/pages/connector-detail/
git commit -m "feat(frontend): 连接器编辑器 + 信封预设"
```

---

## Task 4: 内联自助测试面板

**Files:**
- Modify: `frontend/src/pages/connector-detail/index.tsx`（加测试面板区）
- 可能新增：`frontend/src/pages/connector-detail/TestPanel.tsx`

- [ ] **Step 1: 写测试面板**

输入样例 vars/payload/subjectId → 调 `:test` 端点 → 分阶段展示 `FetchTrace`：渲染后 request / 原始响应 / successWhen 判定 / 映射值 / errorCode（失败高亮）。设计 §9.3「映射写错当场可见」。

- [ ] **Step 2: 构建 + 手测（连真实/桩后端）→ Commit**

手测：填样例点测试，看到分阶段 trace；故意写错 valuePath → 面板显示 PARSE_ERROR。
```bash
git add frontend/src/pages/connector-detail/
git commit -m "feat(frontend): 连接器内联自助测试面板"
```

---

## Task 5: i18n 补全

**Files:**
- Create: `frontend/src/i18n/locales/zh-CN/connector.ts` `frontend/src/i18n/locales/en/connector.ts`
- Modify: locale 索引注册

参考近期 i18n commit（`66ffb33b`/`7a6e5324`：枚举标签工厂函数、zh-CN/en 完整 locale）。

- [ ] **Step 1: 写双语 locale**

connector 命名空间所有字段标签、按钮、枚举值（HttpMethod/AuthKind/CompareOp）、预设名、测试面板文案，zh-CN + en 各一份。注册进 locale 索引。

- [ ] **Step 2: 构建（无硬编码字符串残留）→ Commit**

全局扫 connector 页面无硬编码中文/英文（走 `t()`）。
```bash
git add frontend/src/i18n/
git commit -m "feat(frontend): 连接器 i18n 双语 locale"
```

---

## Task 6: 决策日志 + 端到端剧本

**Files:**
- Modify: `docs/00-decisions.md`（append-only，不改历史条目）
- Create: `docs/examples/connector-standardization/`（README + curl 脚本 + 预期 + 清理）

- [ ] **Step 1: 追加决策日志**

`docs/00-decisions.md` 末尾追加新决策条目（取下一个 D 编号），记录：连接器为可复用命名资源（非塞 metric.params）、v1 可变热加载不冻结、共用脊调用无关、AI/ML+CEP 进演进位、errorMapping 细码不改 D15 降级。引用 spec 路径。

- [ ] **Step 2: 写端到端剧本**

`docs/examples/connector-standardization/README.md`：可复制 curl 脚本——注册 endpoint→建 connector→建引用 metric→建规则→发布→评估→改 connector 验热失效→`:test` 验 trace→清理。附预期结果。照 `docs/examples/` 现有剧本格式。

- [ ] **Step 3: 跑文档自洽 → Commit**

跑 `doc-consistency-review` skill（跨 00-decisions / 04-extension / examples 自洽）。
```bash
git add docs/00-decisions.md docs/examples/connector-standardization/
git commit -m "docs: 连接器标准化决策日志 + 端到端剧本"
```

---

## Task 7: 全链路 e2e 收口

- [ ] **Step 1: 后端全量**

Run: `$MVN clean test`（全绿）

- [ ] **Step 2: 前端构建**

Run（frontend）：`npm run build`（通过）

- [ ] **Step 3: 真实服务全链路 e2e（照 Task6 剧本）**

打包起服务（非 reactor run），按剧本走完整链路：endpoint→connector→metric→rule→publish→eval→改 connector 热失效→前端编辑器测试面板验 trace。逐步查持久层真落库（connector_definition descriptor JSON、metric 引用、评估 session）。失败/跳过态也算正确落库。清理测试数据回干净基线。

- [ ] **Step 4: DB 字段落库审计**

对 `connector_definition` 逐列查恒空字段，分类（遗漏=bug 必修 / 设计如此 / 测试数据未覆盖）。本轮新增列（descriptor、status）专门验证真落库。

- [ ] **Step 5: 收尾**

确认无脏数据残留；前端手测结论与"未自动验证"项在收尾说明中列清。

---

## Self-Review 记录

- **Spec 覆盖**：§10 用户便利（编辑器/预设/测试面板/JSON Schema 表单）全在 Task1-5；§9.3 前端测试面板（Task4）；决策日志 + 剧本（Task6）；§全量 e2e（Task7）。
- **类型一致**：前端 `connector.ts` 类型镜像后端 `ConnectorDescriptor`/`FetchTrace`，字段名与 JSON 一致；端点常量 `:test` 冒号风格与 P3 一致（若 P3 回退子路径，同步改 `api-endpoints.ts`）。
- **风险/标注**：前端若无自动化测试基建，相关 Task 明确标"未自动验证、手测通过"（CLAUDE.md UI 纪律）；`:test` URL 风格须与 P3 最终落地一致（Task1 Step2 注意）。
- **占位符**：endpointRef 下拉数据源（endpoint 列表 API 是否存在）待执行时确认，无则降级文本输入 + 后端校验——已在 Task3 Step1 注明。
