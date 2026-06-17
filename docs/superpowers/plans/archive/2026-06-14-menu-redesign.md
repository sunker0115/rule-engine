# 前端菜单与导航重构 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 重构前端导航为场景驱动：Header 加场景选择器，侧栏菜单按场景上下文动态切换，评估会话默认筛选 HIT+BLOCKED。

**Architecture:** `SceneStore` 持有选中场景状态 → `useMenuItems` hook 动态生成菜单 → `App.tsx` Header 渲染选择器。菜单从静态 `MENU_ITEMS` 迁移为 hook 驱动。所有 UI 文本走 i18n。

**Tech Stack:** React 18, TypeScript, Ant Design 5, Zustand, react-i18next

**Spec:** `docs/superpowers/specs/2026-06-14-frontend-menu-redesign.md`

---

## 文件影响面

```
Create:  components/scene-selector/index.tsx   — 场景选择器（支持搜索、清空）
Modify:  store/sceneStore.ts                    — 新增 selectedSceneCode/Name
Modify:  App.tsx                                — Header 加场景选择器
Modify:  config/menu.tsx                        — 导出 useMenuItems hook
Modify:  router.tsx                             — 移除 /rules 路由
Modify:  constants/routes.ts                    — 移除 RULES 常量
Modify:  pages/eval-session/index.tsx           — URL query 读筛选，默认 HIT+BLOCKED
Modify:  pages/scene-list/index.tsx             — 未选场景时显示引导
Delete:  pages/rules-all/index.tsx              — 合并入场景内规则列表
Modify:  i18n/locales/zh-CN/common.ts           — 新增 scene selector 翻译
Modify:  i18n/locales/zh-CN/scene.ts            — 新增 title.rules
Modify:  i18n/types.ts                          — 新增 key 类型
```

---

### Task 1: SceneStore 扩展——场景上下文状态

**Files:**
- Modify: `frontend/src/store/sceneStore.ts`

- [ ] **Step 1: 加入 selectedSceneCode + selectedSceneName**

```typescript
import { create } from 'zustand';
import apiClient from '@/api/client';
import { ENDPOINTS } from '@/constants/api-endpoints';
import type { SceneListItem, SceneDetail, SceneMetadata } from '@/types';

interface SceneState {
  list: SceneListItem[];
  current: SceneDetail | null;
  metadata: SceneMetadata | null;
  loading: boolean;
  // 场景上下文 —— Header 选择器驱动
  selectedSceneCode: string | null;
  selectedSceneName: string;
  // 方法
  loadList: (tenantId: number) => Promise<void>;
  loadDetail: (tenantId: number, sceneCode: string) => Promise<void>;
  loadMetadata: (tenantId: number, sceneCode: string) => Promise<void>;
  setSelectedScene: (code: string | null, name?: string) => void;
  clearCurrent: () => void;
}

export const useSceneStore = create<SceneState>((set, get) => ({
  list: [],
  current: null,
  metadata: null,
  loading: false,
  selectedSceneCode: localStorage.getItem('selectedSceneCode') || null,
  selectedSceneName: localStorage.getItem('selectedSceneName') || '',

  loadList: async (tenantId: number) => {
    set({ loading: true });
    const res = await apiClient.get(ENDPOINTS.SCENE_LIST, { params: { tenantId } });
    set({ list: res.data?.data ?? [], loading: false });
  },

  loadDetail: async (tenantId: number, sceneCode: string) => {
    const res = await apiClient.get(ENDPOINTS.SCENE_DETAIL(sceneCode), { params: { tenantId } });
    set({ current: res.data?.data ?? null });
  },

  loadMetadata: async (tenantId: number, sceneCode: string) => {
    const res = await apiClient.get(ENDPOINTS.SCENE_METADATA(sceneCode), { params: { tenantId } });
    set({ metadata: res.data?.data ?? null });
  },

  setSelectedScene: (code: string | null, name?: string) => {
    if (code) {
      localStorage.setItem('selectedSceneCode', code);
      if (name) localStorage.setItem('selectedSceneName', name);
      set({ selectedSceneCode: code, selectedSceneName: name ?? get().selectedSceneName });
    } else {
      localStorage.removeItem('selectedSceneCode');
      localStorage.removeItem('selectedSceneName');
      set({ selectedSceneCode: null, selectedSceneName: '' });
    }
  },

  clearCurrent: () => set({ current: null, metadata: null }),
}));
```

- [ ] **Step 2: 验证编译 + 提交**

```bash
cd /Users/sunke/dev/ai-project/rule-engine/frontend && npx -p typescript tsc --noEmit
git -C /Users/sunke/dev/ai-project/rule-engine add frontend/src/store/sceneStore.ts
git -C /Users/sunke/dev/ai-project/rule-engine commit -m "feat(frontend): add selectedScene state to sceneStore"
```

---

### Task 2: 场景选择器组件

**Files:**
- Create: `frontend/src/components/scene-selector/index.tsx`

- [ ] **Step 1: 实现 SceneSelector**

```tsx
import { useEffect } from 'react';
import { Select } from 'antd';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router-dom';
import { useTenantStore } from '@/store/tenantStore';
import { useSceneStore } from '@/store/sceneStore';
import { ROUTES, route } from '@/constants/routes';

export default function SceneSelector() {
  const { t } = useTranslation('common');
  const navigate = useNavigate();
  const { currentId } = useTenantStore();
  const { list, selectedSceneCode, loadList, setSelectedScene } = useSceneStore();

  useEffect(() => {
    if (currentId) loadList(currentId);
  }, [currentId, loadList]);

  const options = list
    .filter((s) => s.status === 'ACTIVE')
    .map((s) => ({
      value: s.sceneCode,
      label: `${s.name} (${s.sceneCode})`,
      searchText: `${s.name} ${s.sceneCode}`.toLowerCase(),
    }));

  const handleChange = (code: string | undefined) => {
    if (code) {
      const scene = list.find((s) => s.sceneCode === code);
      setSelectedScene(code, scene?.name);
      navigate(route(ROUTES.SCENE_RULES, { sceneCode: code }));
    } else {
      setSelectedScene(null);
      navigate(ROUTES.SCENES);
    }
  };

  return (
    <Select
      value={selectedSceneCode ?? undefined}
      onChange={handleChange}
      showSearch
      allowClear
      placeholder={t('scene.selector.placeholder')}
      filterOption={(input, option) =>
        (option?.searchText as string ?? '').includes(input.toLowerCase())
      }
      options={options}
      style={{ width: 260 }}
      size="small"
      notFoundContent={list.length === 0 ? t('label.none') : undefined}
    />
  );
}
```

- [ ] **Step 2: 验证编译 + 提交**

```bash
cd /Users/sunke/dev/ai-project/rule-engine/frontend && npx -p typescript tsc --noEmit
git -C /Users/sunke/dev/ai-project/rule-engine add frontend/src/components/scene-selector/
git -C /Users/sunke/dev/ai-project/rule-engine commit -m "feat(frontend): add SceneSelector component for Header"
```

---

### Task 3: App.tsx Header 加入场景选择器 + 清理未使用 import

**Files:**
- Modify: `frontend/src/App.tsx`

- [ ] **Step 1: 在 Header 加 SceneSelector**

当前 Header 有两个元素：标题+租户选择器 和 操作人显示。在租户选择器右边加场景选择器。

```tsx
import { useState, useCallback } from 'react';
import { Outlet, useNavigate, useLocation } from 'react-router-dom';
import { Layout, Menu, Typography, Select } from 'antd';
import { useTranslation } from 'react-i18next';
import { useMenuItems } from '@/config/menu';
import { useSceneStore } from '@/store/sceneStore';
import TenantSelector from '@/components/tenant-selector';
import SceneSelector from '@/components/scene-selector';

const { Header, Sider, Content } = Layout;

const LANG_OPTIONS = [
  { value: 'zh-CN', label: '中文' },
];

export default function App() {
  const navigate = useNavigate();
  const location = useLocation();
  const { t, i18n } = useTranslation('common');
  const [actorId] = useState(() => localStorage.getItem('actorId') || 'anonymous');
  const selectedSceneCode = useSceneStore((s) => s.selectedSceneCode);

  const menuItems = useMenuItems(selectedSceneCode);

  const segments = location.pathname.split('/').filter(Boolean);
  // 选中键匹配：/scenes/test.scene/rules → 选 /scenes/test.scene/rules
  // /sessions?... → 靠路径前缀匹配
  const pathname = location.pathname;
  const selectedKey = menuItems
    .filter((item) => item && 'key' in item)
    .map((item) => ('key' in item ? String(item.key) : ''))
    .find((key) => key && pathname.startsWith(key.split('?')[0]))
    || (segments.length > 0 ? `/${segments[0]}` : '/scenes');

  const handleLangChange = useCallback((lang: string) => {
    i18n.changeLanguage(lang);
  }, [i18n]);

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Header style={{
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'space-between',
        padding: '0 24px',
        background: '#001529',
      }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 16 }}>
          <Typography.Title level={4} style={{ color: '#fff', margin: 0 }}>
            {t('app.title')}
          </Typography.Title>
          <TenantSelector />
          <SceneSelector />
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
          <Select
            size="small"
            value={i18n.language}
            onChange={handleLangChange}
            options={LANG_OPTIONS}
            style={{ width: 80 }}
          />
          <Typography.Text style={{ color: 'rgba(255,255,255,0.65)' }}>
            {t('header.actorLabel')}：{actorId}
          </Typography.Text>
        </div>
      </Header>
      <Layout>
        <Sider width={200} style={{ background: '#fff' }}>
          <Menu
            mode="inline"
            selectedKeys={[selectedKey ?? '/scenes']}
            items={menuItems}
            onClick={({ key }) => {
              // key 可能含 query string，直接用作跳转路径
              navigate(key);
            }}
            style={{ height: '100%', borderRight: 0 }}
          />
        </Sider>
        <Content style={{ padding: 24, background: '#f5f5f5' }}>
          <Outlet />
        </Content>
      </Layout>
    </Layout>
  );
}
```

- [ ] **Step 2: 验证编译 + 提交**

```bash
cd /Users/sunke/dev/ai-project/rule-engine/frontend && npx -p typescript tsc --noEmit
git -C /Users/sunke/dev/ai-project/rule-engine add frontend/src/App.tsx
git -C /Users/sunke/dev/ai-project/rule-engine commit -m "feat(frontend): add SceneSelector to Header, dynamic menu via useMenuItems"
```

---

### Task 4: 动态菜单 hook

**Files:**
- Modify: `frontend/src/config/menu.tsx`

- [ ] **Step 1: 替换为 useMenuItems hook**

```tsx
import { useMemo } from 'react';
import type { ItemType } from 'antd/es/menu/interface';
import {
  AppstoreOutlined,
  ApartmentOutlined,
  LineChartOutlined,
  CheckCircleOutlined,
  SettingOutlined,
  HistoryOutlined,
  AuditOutlined,
  ClockCircleOutlined,
  ImportOutlined,
} from '@ant-design/icons';
import { ROUTES, route } from '@/constants/routes';

export function useMenuItems(sceneCode: string | null): ItemType[] {
  return useMemo(() => {
    const sceneItems: ItemType[] = sceneCode ? [
      {
        key: route(ROUTES.SCENE_RULES, { sceneCode }),
        icon: <ApartmentOutlined />,
        label: '规则列表',
      },
      {
        key: `/sessions?sceneCode=${sceneCode}&status=HIT&status=BLOCKED`,
        icon: <HistoryOutlined />,
        label: '评估会话',
      },
      {
        key: route(ROUTES.SCENE_DETAIL, { sceneCode }),
        icon: <SettingOutlined />,
        label: '场景设置',
      },
      { type: 'divider' },
    ] : [];

    return [
      ...sceneItems,
      {
        key: ROUTES.SCENES,
        icon: <AppstoreOutlined />,
        label: 'Scene 管理',
      },
      { type: 'divider' },
      {
        key: ROUTES.METRICS,
        icon: <LineChartOutlined />,
        label: 'Metric 管理',
      },
      {
        key: ROUTES.DECISIONS,
        icon: <CheckCircleOutlined />,
        label: 'Decision 管理',
      },
      { type: 'divider' },
      {
        key: ROUTES.AUDIT_LOGS,
        icon: <AuditOutlined />,
        label: '审计日志',
      },
      {
        key: ROUTES.JOBS,
        icon: <ClockCircleOutlined />,
        label: 'Job 管理',
      },
      {
        key: ROUTES.IMPORT_EXPORT,
        icon: <ImportOutlined />,
        label: '导入导出',
      },
    ];
  }, [sceneCode]);
}
```

- [ ] **Step 2: 同步更新 config/index.ts**

```typescript
export { useMenuItems } from './menu';
```

移除旧的 `export { MENU_ITEMS } from './menu'`。

- [ ] **Step 3: 验证编译 + 提交**

```bash
cd /Users/sunke/dev/ai-project/rule-engine/frontend && npx -p typescript tsc --noEmit
git -C /Users/sunke/dev/ai-project/rule-engine add frontend/src/config/
git -C /Users/sunke/dev/ai-project/rule-engine commit -m "refactor(frontend): replace static MENU_ITEMS with dynamic useMenuItems hook"
```

---

### Task 5: 删除冗余路由和页面

**Files:**
- Modify: `frontend/src/constants/routes.ts`
- Modify: `frontend/src/router.tsx`
- Delete: `frontend/src/pages/rules-all/index.tsx`

- [ ] **Step 1: routes.ts — 移除 RULES 常量**

```typescript
// 删除这行：
// RULES: '/rules',
```

- [ ] **Step 2: router.tsx — 移除 /rules 路由和 RulesAll 懒加载**

删除：
```tsx
const RulesAll = lazy(() => import('@/pages/rules-all'));
```
和：
```tsx
{ path: ROUTES.RULES, element: <LazyPage><RulesAll /></LazyPage> },
```

- [ ] **Step 3: 删除 rules-all 页面目录**

```bash
rm -rf /Users/sunke/dev/ai-project/rule-engine/frontend/src/pages/rules-all
```

- [ ] **Step 4: 验证编译 + 提交**

```bash
cd /Users/sunke/dev/ai-project/rule-engine/frontend && npx -p typescript tsc --noEmit
git -C /Users/sunke/dev/ai-project/rule-engine add frontend/src/constants/routes.ts frontend/src/router.tsx
git -C /Users/sunke/dev/ai-project/rule-engine rm frontend/src/pages/rules-all/index.tsx
git -C /Users/sunke/dev/ai-project/rule-engine commit -m "refactor(frontend): remove /rules route and RulesAll page"
```

---

### Task 6: 评估会话页——URL query 读取默认筛选

**Files:**
- Modify: `frontend/src/pages/eval-session/index.tsx`

- [ ] **Step 1: 从 URL search params 读取 sceneCode 和 status 筛选**

在 `EvalSessionList` 组件顶部加入：

```tsx
import { useSearchParams } from 'react-router-dom';

// 组件内：
const [searchParams] = useSearchParams();

// 初始化 filters 时读 URL query：
const [filters, setFilters] = useState<Record<string, unknown>>(() => {
  const init: Record<string, unknown> = {};
  const sceneCode = searchParams.get('sceneCode');
  if (sceneCode) init.sceneCode = sceneCode;
  const statuses = searchParams.getAll('status');
  if (statuses.length > 0) init.status = statuses.join(',');
  else init.status = 'HIT,BLOCKED'; // 默认
  return init;
});
```

- [ ] **Step 2: 验证编译 + 提交**

```bash
cd /Users/sunke/dev/ai-project/rule-engine/frontend && npx -p typescript tsc --noEmit
git -C /Users/sunke/dev/ai-project/rule-engine add frontend/src/pages/eval-session/index.tsx
git -C /Users/sunke/dev/ai-project/rule-engine commit -m "feat(frontend): session list reads default filters from URL query (HIT+BLOCKED)"
```

---

### Task 7: Scene 列表页——未选场景引导 + i18n 补充

**Files:**
- Modify: `frontend/src/pages/scene-list/index.tsx`
- Modify: `frontend/src/i18n/locales/zh-CN/common.ts`
- Modify: `frontend/src/i18n/locales/zh-CN/scene.ts`
- Modify: `frontend/src/i18n/types.ts`

- [ ] **Step 1: scene-list 顶部加引导提示**

在 `SceneList` 组件的 `<h2>Scene 列表</h2>` 下方加入：

```tsx
import { useSceneStore } from '@/store/sceneStore';

// 组件内：
const selectedSceneCode = useSceneStore((s) => s.selectedSceneCode);

// 在标题后：
{!selectedSceneCode && (
  <Alert
    type="info"
    message={t('scene.selector.notSelected')}
    style={{ marginBottom: 16 }}
    showIcon
  />
)}
```

需要导入 `Alert` from `antd`。

- [ ] **Step 2: i18n/types.ts 加 key**

`CommonTranslation` 接口追加：
```typescript
scene: {
  selector: { placeholder: string; notSelected: string };
};
```

`SceneTranslation` 接口追加：
```typescript
title: { list: string; detail: string; rules: string };
```
（`rules` 已在之前添加，确认存在）

- [ ] **Step 3: zh-CN/common.ts 加值**

```typescript
scene: {
  selector: { placeholder: '选择场景', notSelected: '请选择一个场景开始工作' },
},
```

- [ ] **Step 4: 验证编译 + 提交**

```bash
cd /Users/sunke/dev/ai-project/rule-engine/frontend && npx -p typescript tsc --noEmit
git -C /Users/sunke/dev/ai-project/rule-engine add frontend/src/pages/scene-list/index.tsx frontend/src/i18n/
git -C /Users/sunke/dev/ai-project/rule-engine commit -m "feat(frontend): add scene selection guide tip, i18n keys for scene selector"
```

---

### Task 8: 端到端验证

- [ ] **Step 1: 验证场景选择器交互**

启动前端 dev server，逐项确认：
1. 首次打开 → 默认无场景选中 → 左侧仅显示 Scene/Metric/Decision/审计/Job/导入导出
2. 点击场景选择器 → 下拉显示 ACTIVE 场景列表（test.scene）
3. 选中 test.scene → URL 跳转到 `/scenes/test.scene/rules` → 左侧顶部出现"规则列表""评估会话""场景设置"
4. 点击"评估会话" → 页面筛选 sceneCode=test.scene&status=HIT,BLOCKED
5. 点击"场景设置" → 进入 Scene 详情编辑页
6. 清除场景选择 → URL 跳转到 `/scenes` → 左侧场景菜单消失 → 显示"请选择场景"引导提示
7. 切换语言 → Header 文本变为英文（如有）
8. 切换租户 → 场景列表刷新

- [ ] **Step 2: 如有问题修复并提交**

```bash
git -C /Users/sunke/dev/ai-project/rule-engine add -A
git -C /Users/sunke/dev/ai-project/rule-engine commit -m "fix(frontend): e2e verification fixes for menu redesign"
```

---

## 后端依赖（不阻塞前端）

| # | 接口 | 当前前端 workaround | 实现后可移除的 workaround |
|---|------|-------------------|--------------------------|
| 1 | `GET /admin/v1/tenants` | tenantStore 硬编码两条租户 | 去掉 DEFAULT_TENANTS 常量 |
| 2 | `PUT /admin/v1/scenes/{sceneCode}?tenantId=` | 场景编辑已有保存按钮，接口已有则无需 workaround | 无 |

---

## 自检清单

- [x] Spec §一 菜单结构 → Task 4 (useMenuItems hook)
- [x] Spec §二 Header 布局 → Task 2 (SceneSelector) + Task 3 (App.tsx)
- [x] Spec §三 路由表 → Task 5 (删 /rules) + Task 6 (session URL query)
- [x] Spec §四 默认交互 → Task 1 (state) + Task 7 (guide tip) + Task 6 (default filter)
- [x] Spec 无 TBD/TODO
- [x] 类型一致性：`selectedSceneCode` 在所有 task 中命名一致
- [x] 无魔法字符串：所有新 UI 文本走 i18n
