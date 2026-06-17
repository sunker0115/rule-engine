# 前端菜单与导航重构设计

> **背景**：当前菜单扁平化排列所有功能入口（Scene/Metric/Decision/会话/审计/Job/导入导出），不符合运营"以场景为锚点"的心智模型。运营日常工作流是"选场景 → 管规则 → 试算发布 → 看命中效果"。

---

## 一、菜单结构

### 1.1 未选场景

```
  Scene 管理
  ──────────
  Metric 管理
  Decision 管理
  ──────────
  审计日志
  Job 管理
  导入导出
```

### 1.2 选中场景后（顶部动态插入 3 项）

```
  规则列表          ← 默认激活
  评估会话          ← badge：该场景 HIT+BLOCKED 数量（v2）
  场景设置
  ──────────
  Scene 管理
  ──────────
  Metric 管理
  Decision 管理
  ──────────
  审计日志
  Job 管理
  导入导出
```

### 1.3 菜单实现

从静态 `MENU_ITEMS` 常量迁移为 hook `useMenuItems(sceneCode: string | null): ItemType[]`。场景选中时顶部动态插入场景上下文菜单项，未选时不插入。

---

## 二、Header 布局

```
规则引擎运营平台  [租户 ▼]  [场景 ▼]                    操作人：admin [中]
```

### 2.1 租户选择器

- 数据源：`GET /admin/v1/tenants`（后端补），fallback 内存常量
- `localStorage` 持久化所选租户 code
- 切换租户 → 清空场景选中 + 刷新全量数据

### 2.2 场景选择器

- 数据源：`GET /admin/v1/scenes?tenantId=`
- `localStorage` 持久化所选 sceneCode
- 选中场景 → 自动跳转 `/scenes/:sceneCode/rules`
- 支持输入搜索（场景名/场景 code 模糊匹配）
- 允许不选（null 态，侧栏仅显示后半部分）

### 2.3 场景上下文状态

`store/sceneStore.ts` 扩展：`selectedSceneCode` + `selectedSceneName`，写入/读取 localStorage。

---

## 三、路由表

| 路由 | 页面 | 说明 |
|------|------|------|
| `/scenes/:sceneCode/rules` | 规则列表 | **默认页**，场景选中后自动跳转 |
| `/scenes/:sceneCode` | 场景设置 | payloadSchema/eventTypes/defaultParams 编辑 |
| `/scenes/:sceneCode/rules/:ruleId` | 规则编辑器（三栏） | 不变 |
| `/scenes` | Scene 列表 | 新建/禁用场景 |
| `/sessions?sceneCode=&status=HIT&status=BLOCKED` | 评估会话列表 | 默认筛选 HIT+BLOCKED，从 URL query 读筛选条件 |
| `/sessions/:sessionId` | 会话详情 + Trace 树 | 路由 state 传基础信息 |
| `/metrics` | Metric 列表 | 不变 |
| `/decisions` | Decision 列表 | 不变 |
| `/audit-logs` | 审计日志 | 不变 |
| `/jobs` | Job 列表 | 不变 |
| `/jobs/:jobId` | Job 详情 + 执行历史 | 不变 |
| `/import-export` | 导入导出 | 不变 |

移除：`/rules`（原跨场景规则列表，合并入场景内规则列表）。

---

## 四、默认交互行为

- **进入系统**：从 localStorage 恢复 tenantCode + sceneCode。有 sceneCode → 跳转规则列表；无 → 停在 Scene 管理页。
- **规则列表页**：标题 = 场景名。表格列：name / code / kind / status / 操作。顶部 `[+ 新建规则]` + 状态筛选。点行进入编辑器。
- **规则编辑器**：三栏布局不变，dry-run 按钮已在左栏。
- **评估会话页**：默认筛选 HIT + BLOCKED。点行进入详情（路由 state 传基础信息）。详情页：基本信息卡片 + Trace 树。
- **场景设置页**：编辑表单（复用已有组件）。Back button 返回规则列表。
- **跨页面 Back**：规则编辑器/会话详情均 `navigate(-1)`。

---

## 五、后端待补 API

| # | 接口 | 用途 | 优先级 |
|---|------|------|--------|
| 1 | `GET /admin/v1/tenants` | 租户下拉列表 | P0 |
| 2 | `PUT /admin/v1/scenes/{sceneCode}?tenantId=` | 更新 Scene（name/description/payloadSchema/eventTypes/defaultParams/status 等） | P0 |
| 3 | `GET /admin/v1/evaluation-sessions/{sessionId}?tenantId=` | 单条 session 详情（含 eventType/subjectId/source/mode/evalDurationMs/blockedBy/errorCode/finalDecision/contextSnapshot 等） | P1 |
| 4 | `GET /admin/v1/rules?tenantId=` | 规则列表不强制 sceneCode 参数（当前必填），支持跨场景查询 | P1 |

P1 接口有前端 workaround：session 详情走路由 state 传数据，规则列表逐 scene 合并（当前规则列表页仅在场景内工作，P1 非阻塞）。

---

## 六、前端改动文件清单

| 文件 | 改动 |
|------|------|
| `App.tsx` | Header 加场景选择器 |
| `config/menu.tsx` | 导出 `useMenuItems` hook 替代静态常量 |
| `store/sceneStore.ts` | 新增 `selectedSceneCode/Name` + localStorage 持久化 |
| `router.tsx` | 移除 `/rules` 路由 |
| `constants/routes.ts` | 移除 `RULES` 常量 |
| `pages/eval-session/index.tsx` | 默认筛选从 URL query 读取，默认 HIT+BLOCKED |
| `pages/scene-list/index.tsx` | 未选场景时显示引导提示"请选择场景" |
| `pages/rules-all/index.tsx` | 删除 |

---

## 七、i18n 新增 key

| 命名空间 | Key | 中文 |
|---------|-----|------|
| `common` | `scene.selector.placeholder` | 选择场景 |
| `common` | `scene.selector.notSelected` | 请选择场景开始工作 |
| `scene` | `title.rules` | `{name} — 规则列表` |
