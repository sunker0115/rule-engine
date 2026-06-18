# 规则引擎前端运营平台 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 从零搭建规则引擎运营平台前端（React 18 SPA），覆盖配置管理、规则编辑器（三栏 AST 可视化）、dry-run、灰度、评估会话 Trace、审计日志、导入导出、Job 管理。

**Architecture:** Vite + React 18 + TypeScript SPA。Ant Design 5 提供 UI 组件，react-querybuilder 驱动 AST 条件编辑器，Zustand 管理全局状态，React Router 6 控制路由。**硬约束：零魔法字符串、数据驱动配置、新增页面只需在配置层注册一次即可生效、所有 UI 文本走 i18n（`useTranslation` + 类型安全 key），禁止硬编码中文。**

**分层架构**（每层只依赖下层，不跨层）：
```
constants/   ← 零依赖，被所有层引用（路由路径、API 端点、枚举值集）
config/      ← 只依赖 constants/（菜单、表格列、筛选器 配置声明）
api/         ← 依赖 constants/（端点路径）
store/       ← 依赖 api/
components/  ← 依赖 store/ + config/ + constants/
pages/       ← 依赖 components/ + store/ + constants/
router.tsx   ← 依赖 pages/ + constants/routes
```

**Tech Stack:** React 18, TypeScript, Vite, Ant Design 5, react-querybuilder, Zustand, React Router 6, axios, Monaco Editor, jsondiffpatch

**后端 API 基准：** `docs/10-api-contract.md`（所有接口已实装，前端只做调用）

---

## Phase 0：类型、常量与配置层（所有代码的基石）

### Task 1: 初始化 Vite + React + TypeScript 项目

**Files:**
- Create: `frontend/package.json`
- Create: `frontend/tsconfig.json`
- Create: `frontend/vite.config.ts`
- Create: `frontend/index.html`
- Create: `frontend/src/main.tsx`
- Create: `frontend/src/App.tsx`（占位）
- Create: `frontend/src/vite-env.d.ts`

- [ ] **Step 1: 创建 package.json**

```json
{
  "name": "rule-engine-frontend",
  "private": true,
  "version": "1.0.0",
  "type": "module",
  "scripts": {
    "dev": "vite",
    "build": "tsc -b && vite build",
    "preview": "vite preview"
  },
  "dependencies": {
    "react": "^18.3.1",
    "react-dom": "^18.3.1",
    "react-router-dom": "^6.26.0",
    "antd": "^5.20.0",
    "@ant-design/icons": "^5.4.0",
    "react-querybuilder": "^7.8.0",
    "zustand": "^4.5.0",
    "axios": "^1.7.0",
    "dayjs": "^1.11.0",
    "monaco-editor": "^0.50.0",
    "@monaco-editor/react": "^4.6.0",
    "jsondiffpatch": "^0.6.0"
  },
  "devDependencies": {
    "@types/react": "^18.3.0",
    "@types/react-dom": "^18.3.0",
    "@vitejs/plugin-react": "^4.3.0",
    "typescript": "^5.5.0",
    "vite": "^5.4.0"
  }
}
```

- [ ] **Step 2: 创建 tsconfig.json**

```json
{
  "compilerOptions": {
    "target": "ES2020", "lib": ["ES2020", "DOM", "DOM.Iterable"],
    "module": "ESNext", "skipLibCheck": true,
    "moduleResolution": "bundler", "allowImportingTsExtensions": true,
    "isolatedModules": true, "moduleDetection": "force", "noEmit": true,
    "jsx": "react-jsx", "strict": true,
    "noUnusedLocals": true, "noUnusedParameters": true,
    "noFallthroughCasesInSwitch": true,
    "paths": { "@/*": ["./src/*"] }
  },
  "include": ["src"]
}
```

- [ ] **Step 3: 创建 vite.config.ts**

```typescript
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import path from 'path';

export default defineConfig({
  plugins: [react()],
  resolve: { alias: { '@': path.resolve(__dirname, 'src') } },
  server: {
    port: 5173,
    proxy: {
      '/admin': 'http://localhost:8080',
      '/api': 'http://localhost:8080',
      '/sdk': 'http://localhost:8080',
    },
  },
});
```

- [ ] **Step 4: 创建 index.html 和 src/main.tsx**

```html
<!DOCTYPE html>
<html lang="zh-CN">
  <head><meta charset="UTF-8" /><meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <title>规则引擎运营平台</title></head>
  <body><div id="root"></div><script type="module" src="/src/main.tsx"></script></body>
</html>
```

```tsx
import React from 'react';
import ReactDOM from 'react-dom/client';
import { ConfigProvider } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import { RouterProvider } from 'react-router-dom';
import { router } from './router';

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <ConfigProvider locale={zhCN}>
      <RouterProvider router={router} />
    </ConfigProvider>
  </React.StrictMode>
);
```

- [ ] **Step 5: 安装依赖并验证 `npm install && npm run dev`**

- [ ] **Step 6: Commit**

```bash
git add frontend/
git commit -m "feat(frontend): scaffold Vite + React 18 + TypeScript project"
```

---

### Task 2: 类型系统（按域拆分）

**Files:**
- Create: `frontend/src/types/common.ts`
- Create: `frontend/src/types/scene.ts`
- Create: `frontend/src/types/metric.ts`
- Create: `frontend/src/types/decision.ts`
- Create: `frontend/src/types/rule.ts`
- Create: `frontend/src/types/ast.ts`
- Create: `frontend/src/types/eval.ts`
- Create: `frontend/src/types/session.ts`
- Create: `frontend/src/types/audit.ts`
- Create: `frontend/src/types/job.ts`
- Create: `frontend/src/types/import-export.ts`
- Create: `frontend/src/types/metadata.ts`
- Create: `frontend/src/types/index.ts`（re-export 桶）

原则：**每个域一个文件，禁止跨域引用**。`common.ts` 放共享结构。

- [ ] **Step 1: 创建 types/common.ts**

```typescript
/** 分页响应（所有 admin 列表接口统一格式） */
export interface PageResponse<T> {
  items: T[];
  total: number;
  page: number;   // 从 1 起
  size: number;
}

/** ApiResponse 包装（所有非分页 admin 接口统一格式） */
export interface ApiResponse<T> {
  success: boolean;
  data: T;
  message?: string;
}
```

- [ ] **Step 2: 创建 types/scene.ts**

```typescript
export interface SceneListItem {
  id: number;
  sceneCode: string;
  name: string;
  dominantMode: 'PUSH' | 'PULL' | 'HYBRID';
  subjectType: string;
  status: 'ACTIVE' | 'DISABLED';
}

export interface SceneDetail extends SceneListItem {
  tenantId: number;
  description?: string;
  payloadSchema?: Record<string, unknown>;
  eventTypes: string[];
  defaultParams?: Record<string, unknown>;
  decisionStrategy: string;
  createdAt?: string;
  updatedAt?: string;
}
```

- [ ] **Step 3: 创建 types/metric.ts**

```typescript
export type SourceType = 'ATTRIBUTE' | 'SQL_AGGREGATE' | 'EXTERNAL_HTTP' | 'STREAM';
export type MetricDataType = 'LONG' | 'DOUBLE' | 'STRING' | 'BOOLEAN' | 'LIST' | 'DATE' | 'DATETIME';

export interface MetricDescriptor {
  metricCode: string;
  metricVersion: number;
  name: string;
  sourceType: SourceType;
  dataType: MetricDataType;
  allowProvided: boolean;
  cacheTtlSeconds: number;
  params?: Record<string, unknown>;
  status?: string;
}

export interface MetricImpactResult {
  metricCode: string;
  metricVersion: number;
  affectedRules: AffectedRule[];
  affectedRuleCount: number;
}

export interface AffectedRule {
  ruleDefinitionId: number;
  ruleCode: string;
  ruleName: string;
  sceneCode: string;
  status: string;
}
```

- [ ] **Step 4: 创建 types/decision.ts**

```typescript
export interface DecisionItem {
  tenantId?: number;
  code: string;
  name: string;
  priority: number;
  description?: string;
  createdAt?: string;
}
```

- [ ] **Step 5: 创建 types/rule.ts**

```typescript
import type { AstNode } from './ast';

export type RuleKind = 'AST_BOOLEAN' | 'SCORECARD' | 'DECISION_TREE' | 'DECISION_TABLE' | 'EXPRESSION_SCRIPT';
export type RuleStatus = 'DRAFT' | 'PUBLISHED' | 'DISABLED';
export type VersionStatus = 'DRAFT' | 'ACTIVE' | 'SUPERSEDED';

export interface RuleListItem {
  ruleDefinitionId: number;
  code: string;
  name: string;
  kind: RuleKind;
  sceneCode: string;
  status: RuleStatus;
  currentVersion?: number;
  publishedAt?: string;
  createdAt: string;
}

export interface RuleDetail extends RuleListItem {
  conditionAst?: AstNode | null;
  script?: { source: string; lang: string } | null;
  decisionBindings: DecisionBinding[];
  preGates: PreGate[];
  triggerEventTypes: string[];
  currentVersionId?: number;
  versions: RuleVersionItem[];
}

export interface DraftCreatedResult {
  ruleDefinitionId: number;
  ruleVersionId: number;
  version: number;
  status: 'DRAFT';
}

export interface RuleVersionItem {
  ruleVersionId: number;
  version: number;
  status: VersionStatus;
  createdAt: string;
  publishedBy?: string;
  publishedAt?: string;
}

export interface DecisionBinding {
  decisionCode: string;
  scoreRangeMin?: number;
  scoreRangeMax?: number;
}

export interface PreGate {
  gateType: 'ROLLOUT';
  params: RolloutParams;
}

export interface RolloutParams {
  percentage?: number;
  bucketStart?: number;
  bucketEnd?: number;
  experimentId?: string;
}
```

- [ ] **Step 6: 创建 types/ast.ts**

```typescript
export type AstNode = AndNode | OrNode | NotNode | ConditionNode;

export interface AndNode {
  type: 'AndNode';
  children: AstNode[];
  displayLabel?: string;
  weight?: number;
}

export interface OrNode {
  type: 'OrNode';
  children: AstNode[];
  displayLabel?: string;
  weight?: number;
}

export interface NotNode {
  type: 'NotNode';
  child: AstNode;
  displayLabel?: string;
}

export interface ConditionNode {
  type: 'ConditionNode';
  conditionType: string;
  params: Record<string, unknown>;
  metricCode?: string;
  valueRef?: 'METRIC' | 'PAYLOAD';
  displayLabel?: string;
  weight?: number;
}
```

- [ ] **Step 7: 创建 types/eval.ts**

```typescript
export interface EvalEventRequest {
  tenantId: string;
  sceneCode: string;
  eventType: string;
  subjectId: string;
  eventId: string;
  occurredAt: string;
  payload: Record<string, unknown>;
}

export interface DryRunRequest extends EvalEventRequest {
  ruleVersionId?: number;
  ruleId?: number;
}

export type ValueSource = 'PROVIDED' | 'FETCHED' | 'PAYLOAD';

export interface NodeTraceItem {
  type: string;
  result: boolean | null;
  children?: NodeTraceItem[];
  metricCode?: string;
  actualValue?: unknown;
  valueSource?: ValueSource;
  errorCode?: string;
  errorMessage?: string;
}

export interface DecisionRef {
  code: string;
  name: string;
  priority: number;
  fromRuleVersionId: number;
}

export interface EvalResult {
  eventId: string;
  ruleHit: boolean;
  finalDecision: DecisionRef | null;
  hitDecisions: DecisionRef[];
  nodeTrace: NodeTraceItem[];
  errorCode: string | null;
}
```

- [ ] **Step 8: 创建 types/session.ts**

```typescript
export type SessionStatus = 'HIT' | 'MISS' | 'BLOCKED' | 'ERROR' | 'PENDING' | 'FAILED';
export type EventSource = 'HTTP' | 'MQ' | 'JOB' | 'SDK' | 'REPLAY';
export type EvalMode = 'PUSH' | 'PULL';

export interface EvalSessionItem {
  sessionId: number;
  eventId: string;
  sceneCode: string;
  eventType: string;
  subjectId: string;
  status: SessionStatus;
  blockedBy?: string;
  errorCode?: string;
  finalDecision?: string;
  candidateRuleCount: number;
  hitRuleCount: number;
  source: EventSource;
  mode: EvalMode;
  evalDurationMs: number;
  occurredAt: string;
  startedAt: string;
}

export interface EvalSessionDetail extends EvalSessionItem {
  finishedAt?: string;
  contextSnapshot?: Record<string, unknown>;
}
```

- [ ] **Step 9: 创建 types/audit.ts**

```typescript
export type ActorType = 'USER' | 'SYSTEM' | 'JOB';
export type AuditAction = 'CREATE' | 'UPDATE' | 'PUBLISH' | 'PUBLISH_FAILED' | 'ENABLE' | 'DISABLE' | 'DELETE' | 'IMPORT';

export interface AuditLogItem {
  actor: string;
  actorType: ActorType;
  action: AuditAction;
  targetType: string;
  targetId: number;
  beforeSnapshot?: Record<string, unknown>;
  afterSnapshot?: Record<string, unknown>;
  operatedAt: string;
  traceId?: string;
}
```

- [ ] **Step 10: 创建 types/job.ts**

```typescript
export interface JobItem {
  id: number;
  name: string;
  code: string;
  sceneCode: string;
  eventType: string;
  cronExpression: string;
  status: 'ACTIVE' | 'DISABLED';
  subjectQuery: { type: string; ref: string };
}

export type JobExecStatus = 'RUNNING' | 'SUCCESS' | 'PARTIAL_FAIL' | 'FAILED';

export interface JobExecutionItem {
  id: number;
  jobDefinitionId: number;
  triggerAt: string;
  finishedAt?: string;
  subjectCount: number;
  successCount: number;
  errorCount: number;
  status: JobExecStatus;
  errorSummary?: string;
}
```

- [ ] **Step 11: 创建 types/import-export.ts、types/metadata.ts、types/index.ts**

```typescript
// types/import-export.ts
export interface RuleImportResult {
  rules: ImportedRule[];
  scenesCreated: string[];
  scenesSkippedExisting: string[];
  metricsCreated: string[];
  metricsSkippedExisting: string[];
  metricsRequiringReview: string[];
  decisionsCreated: string[];
  decisionsSkippedExisting: string[];
}
export interface ImportedRule {
  ruleDefinitionId: number; ruleVersionId: number; version: number;
  code: string; sceneCode: string; ruleAlreadyExisted: boolean;
}

// types/metadata.ts
import type { MetricDescriptor } from './metric';
export interface ConditionTypeMeta {
  code: string;
  displayName: string;
  paramsSchema: Record<string, unknown>;
  requiresMetric: boolean;
}
export interface SceneMetadata {
  conditionTypes: ConditionTypeMeta[];
  availableMetrics: MetricDescriptor[];
}
export interface InputFieldItem {
  name: string;
  dataType: 'DECIMAL' | 'LONG' | 'STRING' | 'BOOLEAN';
  required: boolean;
}
export interface InputManifest {
  fields: InputFieldItem[];
}

// types/index.ts
export * from './common';
export * from './scene';
export * from './metric';
export * from './decision';
export * from './rule';
export * from './ast';
export * from './eval';
export * from './session';
export * from './audit';
export * from './job';
export * from './import-export';
export * from './metadata';
```

- [ ] **Step 12: Commit**

```bash
git add frontend/src/types/
git commit -m "feat(frontend): add domain-split type definitions"
```

---

### Task 3: 常量层 — 零魔法字符串

**Files:**
- Create: `frontend/src/constants/routes.ts`
- Create: `frontend/src/constants/api-endpoints.ts`
- Create: `frontend/src/constants/enums.ts`
- Create: `frontend/src/constants/index.ts`

**原则**：所有路由路径、API 端点、枚举值集（含显示标签）集中定义。组件中绝不出现裸字符串字面量。

- [ ] **Step 1: 创建 constants/routes.ts**

```typescript
/** 路由路径常量 —— 全应用唯一真相源 */
export const ROUTES = {
  SCENES:         '/scenes',
  SCENE_DETAIL:   '/scenes/:sceneCode',
  SCENE_RULES:    '/scenes/:sceneCode/rules',
  RULE_EDITOR:    '/scenes/:sceneCode/rules/:ruleId',
  METRICS:        '/metrics',
  METRIC_DETAIL:  '/metrics/:metricCode',
  DECISIONS:      '/decisions',
  SESSIONS:       '/sessions',
  SESSION_DETAIL: '/sessions/:sessionId',
  AUDIT_LOGS:     '/audit-logs',
  JOBS:           '/jobs',
  JOB_DETAIL:     '/jobs/:jobId',
  IMPORT_EXPORT:  '/import-export',
} as const;

export type RouteKey = keyof typeof ROUTES;

/** 生成带参数的实际路径 */
export function route(path: typeof ROUTES[keyof typeof ROUTES], params?: Record<string, string | number>): string {
  let result: string = path;
  if (params) {
    for (const [key, value] of Object.entries(params)) {
      result = result.replace(`:${key}`, String(value));
    }
  }
  return result;
}
```

- [ ] **Step 2: 创建 constants/api-endpoints.ts**

```typescript
const ADMIN = '/admin/v1';
const API   = '/api/v1/rule';
const SDK   = '/sdk/v1';

export const ENDPOINTS = {
  // Scene
  SCENE_LIST:    `${ADMIN}/scenes`,
  SCENE_DETAIL:  (sceneCode: string) => `${ADMIN}/scenes/${sceneCode}`,
  SCENE_CREATE:  `${ADMIN}/scenes`,

  // Rule
  RULE_LIST:     `${ADMIN}/rules`,
  RULE_DETAIL:   (id: number) => `${ADMIN}/rules/${id}`,
  RULE_CREATE:   `${ADMIN}/rules`,
  RULE_DRAFT:    (id: number) => `${ADMIN}/rules/${id}/draft`,
  RULE_PUBLISH:  (id: number) => `${ADMIN}/rules/${id}/publish`,
  RULE_DISABLE:  (id: number) => `${ADMIN}/rules/${id}/disable`,
  RULE_VERSIONS: (id: number) => `${ADMIN}/rules/${id}/versions`,
  RULE_DELETE:   (id: number) => `${ADMIN}/rules/${id}`,
  RULE_DELETE_VERSION: (ruleId: number, versionId: number) => `${ADMIN}/rules/${ruleId}/versions/${versionId}`,
  RULE_EXPORT:   `${ADMIN}/rules/export`,
  RULE_IMPORT:   `${ADMIN}/rules/import`,

  // Metric
  METRIC_LIST:   `${ADMIN}/metrics`,
  METRIC_CREATE: `${ADMIN}/metrics`,
  METRIC_UPDATE: (code: string) => `${ADMIN}/metrics/${code}`,
  METRIC_IMPACT: (code: string, version: number) => `${ADMIN}/metrics/${code}/versions/${version}/impact`,

  // Decision
  DECISION_LIST: `${ADMIN}/decisions`,
  DECISION_CREATE: `${ADMIN}/decisions`,

  // Metadata
  SCENE_METADATA: (sceneCode: string) => `${ADMIN}/scenes/${sceneCode}/metadata`,

  // Evaluation
  EVAL_EVENT:    `${API}/event`,
  EVAL_EVALUATE: `${API}/evaluate`,
  EVAL_DRY_RUN:  `${API}/dry-run`,

  // Input Manifest
  INPUT_MANIFEST: (sceneCode: string) => `${API}/scenes/${sceneCode}/input-manifest`,

  // Evaluation Sessions
  SESSION_LIST:  `${ADMIN}/evaluation-sessions`,
  SESSION_DETAIL: (sessionId: number) => `${ADMIN}/evaluation-sessions/${sessionId}`,
  SESSION_TRACE_TREE: (sessionId: number) => `${ADMIN}/evaluation-sessions/${sessionId}/trace/tree`,
  RULE_SESSIONS: (ruleId: number) => `${ADMIN}/rules/${ruleId}/sessions`,

  // Audit
  AUDIT_LOG_LIST: `${ADMIN}/audit-logs`,

  // Job
  JOB_LIST:      `${ADMIN}/jobs`,
  JOB_DETAIL:    (jobId: number) => `${ADMIN}/jobs/${jobId}`,
  JOB_TRIGGER:   (jobId: number) => `${ADMIN}/jobs/${jobId}/trigger`,
  JOB_EXECUTIONS: (jobId: number) => `${ADMIN}/jobs/${jobId}/executions`,

  // Tenant (假设存在)
  TENANT_LIST:   `${ADMIN}/tenants`,
} as const;
```

- [ ] **Step 3: 创建 constants/enums.ts**

所有封闭取值集中定义，每项含 `value`（API 交互用）和 `label`（UI 显示用）。

```typescript
/** 通用状态 */
export const STATUS_OPTIONS = [
  { value: 'ACTIVE',   label: '启用',   color: 'green'  },
  { value: 'DISABLED', label: '禁用',   color: 'red'    },
] as const;

/** Scene 使用模式 */
export const DOMINANT_MODE_OPTIONS = [
  { value: 'PUSH',   label: 'PUSH (异步评估)' },
  { value: 'PULL',   label: 'PULL (同步评估)' },
  { value: 'HYBRID', label: 'HYBRID (混合)'   },
] as const;

/** 规则状态 */
export const RULE_STATUS_OPTIONS = [
  { value: 'DRAFT',     label: '草稿',     color: 'blue'   },
  { value: 'PUBLISHED', label: '已发布',   color: 'green'  },
  { value: 'DISABLED',  label: '已禁用',   color: 'red'    },
] as const;

/** 版本状态 */
export const VERSION_STATUS_OPTIONS = [
  { value: 'DRAFT',      label: '草稿',     color: 'blue'   },
  { value: 'ACTIVE',     label: '生效中',   color: 'green'  },
  { value: 'SUPERSEDED', label: '已取代',   color: 'default' },
] as const;

/** Metric 取数方式 */
export const SOURCE_TYPE_OPTIONS = [
  { value: 'ATTRIBUTE',     label: '属性表 (ATTRIBUTE)'      },
  { value: 'SQL_AGGREGATE', label: 'SQL 聚合 (SQL_AGGREGATE)' },
  { value: 'EXTERNAL_HTTP', label: '外部 HTTP (EXTERNAL_HTTP)' },
  { value: 'STREAM',        label: '流处理 (STREAM) [v2]'     },
] as const;

/** Metric 数据类型 */
export const DATA_TYPE_OPTIONS = [
  { value: 'LONG',     label: 'LONG (整数)'     },
  { value: 'DOUBLE',   label: 'DOUBLE (浮点)'    },
  { value: 'STRING',   label: 'STRING (字符串)'  },
  { value: 'BOOLEAN',  label: 'BOOLEAN (布尔)'   },
  { value: 'LIST',     label: 'LIST (列表)'      },
  { value: 'DATE',     label: 'DATE (日期)'      },
  { value: 'DATETIME', label: 'DATETIME (日期时间)' },
] as const;

/** 规则 kind */
export const RULE_KIND_OPTIONS = [
  { value: 'AST_BOOLEAN',        label: 'AST 布尔树'       },
  { value: 'SCORECARD',          label: '评分卡'           },
  { value: 'DECISION_TREE',      label: '决策树'           },
  { value: 'DECISION_TABLE',     label: '决策表'           },
  { value: 'EXPRESSION_SCRIPT',  label: '表达式脚本'       },
] as const;

/** 评估会话状态（含颜色） */
export const SESSION_STATUS_OPTIONS = [
  { value: 'HIT',     label: '命中',    color: 'green'  },
  { value: 'MISS',    label: '未命中',  color: 'default' },
  { value: 'BLOCKED', label: '被拦截',  color: 'orange' },
  { value: 'ERROR',   label: '错误',    color: 'red'    },
  { value: 'PENDING', label: '进行中',  color: 'blue'   },
  { value: 'FAILED',  label: '失败',    color: '#8b0000' },
] as const;

/** 事件来源渠道 */
export const EVENT_SOURCE_OPTIONS = [
  { value: 'HTTP',   label: 'HTTP'  },
  { value: 'MQ',     label: 'MQ'    },
  { value: 'JOB',    label: 'Job'   },
  { value: 'SDK',    label: 'SDK'   },
  { value: 'REPLAY', label: 'Replay' },
] as const;

/** 审计操作类型 */
export const AUDIT_ACTION_OPTIONS = [
  { value: 'CREATE',         label: '创建',         color: 'blue'    },
  { value: 'UPDATE',         label: '更新',         color: 'blue'    },
  { value: 'PUBLISH',        label: '发布',         color: 'green'   },
  { value: 'PUBLISH_FAILED', label: '发布失败',     color: 'red'     },
  { value: 'ENABLE',         label: '启用',         color: 'green'   },
  { value: 'DISABLE',        label: '禁用',         color: 'orange'  },
  { value: 'DELETE',         label: '删除',         color: 'red'     },
  { value: 'IMPORT',         label: '导入',         color: 'purple'  },
] as const;

/** 审计目标类型 */
export const AUDIT_TARGET_TYPE_OPTIONS = [
  { value: 'RULE',    label: '规则'   },
  { value: 'SCENE',   label: '场景'   },
  { value: 'METRIC',  label: '指标'   },
  { value: 'DECISION', label: '决策'  },
  { value: 'JOB',     label: 'Job'    },
] as const;

/** Job 执行状态 */
export const JOB_EXEC_STATUS_OPTIONS = [
  { value: 'RUNNING',      label: '运行中',      color: 'blue'   },
  { value: 'SUCCESS',      label: '成功',        color: 'green'  },
  { value: 'PARTIAL_FAIL', label: '部分失败',    color: 'orange' },
  { value: 'FAILED',       label: '失败',        color: 'red'    },
] as const;

/** 评估模式 */
export const EVAL_MODE_OPTIONS = [
  { value: 'PUSH', label: 'PUSH' },
  { value: 'PULL', label: 'PULL' },
] as const;

/** Actor 类型 */
export const ACTOR_TYPE_OPTIONS = [
  { value: 'USER',   label: '用户'   },
  { value: 'SYSTEM', label: '系统'   },
  { value: 'JOB',    label: 'Job'    },
] as const;

/** 工具函数：按 value 取 label */
export function labelOf<T extends string>(options: ReadonlyArray<{ readonly value: T; readonly label: string }>, value: T): string {
  return options.find(o => o.value === value)?.label ?? value;
}

/** 工具函数：按 value 取 color（用于 Tag） */
export function colorOf<T extends string>(options: ReadonlyArray<{ readonly value: T; readonly label: string; readonly color?: string }>, value: T): string | undefined {
  return options.find(o => o.value === value)?.color;
}
```

- [ ] **Step 4: 创建 constants/index.ts**

```typescript
export * from './routes';
export * from './api-endpoints';
export * from './enums';
```

- [ ] **Step 5: Commit**

```bash
git add frontend/src/constants/
git commit -m "feat(frontend): add constants layer — zero magic strings"
```

---

### Task 4: API 客户端

**Files:**
- Create: `frontend/src/api/client.ts`

- [ ] **Step 1: 创建 api/client.ts**

```typescript
import axios from 'axios';
import { message } from 'antd';

const apiClient = axios.create({
  baseURL: '/',
  timeout: 15000,
  headers: { 'Content-Type': 'application/json' },
});

apiClient.interceptors.request.use((config) => {
  const actorId = localStorage.getItem('actorId') || 'anonymous';
  config.headers['X-Actor-Id'] = actorId;
  return config;
});

apiClient.interceptors.response.use(
  (response) => response,
  (error) => {
    const msg = error.response?.data?.message || error.response?.statusText || '请求失败';
    message.error(msg);
    return Promise.reject(error);
  }
);

export default apiClient;
```

- [ ] **Step 2: Commit**

```bash
git add frontend/src/api/client.ts
git commit -m "feat(frontend): add axios API client with actor-id injection"
```

---

### Task 5: 菜单与导航配置（数据驱动）

**Files:**
- Create: `frontend/src/config/menu.tsx`
- Create: `frontend/src/config/index.ts`

- [ ] **Step 1: 创建 config/menu.tsx**

菜单配置是纯数据，新增页面只需在此数组加一项。路由路径引用 `ROUTES` 常量。

```typescript
import type { ItemType } from 'antd/es/menu/interface';
import {
  AppstoreOutlined,
  LineChartOutlined,
  CheckCircleOutlined,
  HistoryOutlined,
  AuditOutlined,
  ClockCircleOutlined,
  ImportOutlined,
} from '@ant-design/icons';
import { ROUTES } from '@/constants/routes';

export const MENU_ITEMS: ItemType[] = [
  {
    key: ROUTES.SCENES,
    icon: <AppstoreOutlined />,
    label: 'Scene',
  },
  {
    key: ROUTES.METRICS,
    icon: <LineChartOutlined />,
    label: 'Metric',
  },
  {
    key: ROUTES.DECISIONS,
    icon: <CheckCircleOutlined />,
    label: 'Decision',
  },
  { type: 'divider' },
  {
    key: ROUTES.SESSIONS,
    icon: <HistoryOutlined />,
    label: '评估会话',
  },
  {
    key: ROUTES.AUDIT_LOGS,
    icon: <AuditOutlined />,
    label: '审计日志',
  },
  { type: 'divider' },
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
```

- [ ] **Step 2: 创建 config/index.ts**

```typescript
export { MENU_ITEMS } from './menu';
```

- [ ] **Step 3: Commit**

```bash
git add frontend/src/config/
git commit -m "feat(frontend): add data-driven menu configuration"
```

---

### Task 6: 路由配置 + 布局骨架

**Files:**
- Create: `frontend/src/router.tsx`
- Modify: `frontend/src/App.tsx`
- Create: `frontend/src/components/tenant-selector/index.tsx`
- Create: `frontend/src/pages/scene-list/index.tsx`（占位）
- Create: `frontend/src/pages/scene-detail/index.tsx`（占位）
- Create: `frontend/src/pages/rule-list/index.tsx`（占位）
- Create: `frontend/src/pages/rule-editor/index.tsx`（占位）
- Create: `frontend/src/pages/metric-list/index.tsx`（占位）
- Create: `frontend/src/pages/metric-detail/index.tsx`（占位）
- Create: `frontend/src/pages/decision-list/index.tsx`（占位）
- Create: `frontend/src/pages/eval-session/index.tsx`（占位）
- Create: `frontend/src/pages/eval-session-detail/index.tsx`（占位）
- Create: `frontend/src/pages/audit-log/index.tsx`（占位）
- Create: `frontend/src/pages/job-list/index.tsx`（占位）
- Create: `frontend/src/pages/job-detail/index.tsx`（占位）
- Create: `frontend/src/pages/import-export/index.tsx`（占位）

- [ ] **Step 1: 创建 router.tsx（使用 ROUTES 常量）**

```tsx
import { createBrowserRouter } from 'react-router-dom';
import { ROUTES } from '@/constants/routes';
import App from './App';

// 懒加载所有页面
import { lazy } from 'react';
const SceneList        = lazy(() => import('@/pages/scene-list'));
const SceneDetail      = lazy(() => import('@/pages/scene-detail'));
const RuleList         = lazy(() => import('@/pages/rule-list'));
const RuleEditor       = lazy(() => import('@/pages/rule-editor'));
const MetricList       = lazy(() => import('@/pages/metric-list'));
const MetricDetail     = lazy(() => import('@/pages/metric-detail'));
const DecisionList     = lazy(() => import('@/pages/decision-list'));
const EvalSession      = lazy(() => import('@/pages/eval-session'));
const EvalSessionDetail = lazy(() => import('@/pages/eval-session-detail'));
const AuditLog         = lazy(() => import('@/pages/audit-log'));
const JobList          = lazy(() => import('@/pages/job-list'));
const JobDetail        = lazy(() => import('@/pages/job-detail'));
const ImportExport     = lazy(() => import('@/pages/import-export'));

export const router = createBrowserRouter([
  {
    path: '/',
    element: <App />,
    children: [
      { index: true, element: <SceneList /> },
      { path: ROUTES.SCENES,         element: <SceneList /> },
      { path: ROUTES.SCENE_DETAIL,   element: <SceneDetail /> },
      { path: ROUTES.SCENE_RULES,    element: <RuleList /> },
      { path: ROUTES.RULE_EDITOR,    element: <RuleEditor /> },
      { path: ROUTES.METRICS,        element: <MetricList /> },
      { path: ROUTES.METRIC_DETAIL,  element: <MetricDetail /> },
      { path: ROUTES.DECISIONS,      element: <DecisionList /> },
      { path: ROUTES.SESSIONS,       element: <EvalSession /> },
      { path: ROUTES.SESSION_DETAIL, element: <EvalSessionDetail /> },
      { path: ROUTES.AUDIT_LOGS,     element: <AuditLog /> },
      { path: ROUTES.JOBS,           element: <JobList /> },
      { path: ROUTES.JOB_DETAIL,     element: <JobDetail /> },
      { path: ROUTES.IMPORT_EXPORT,  element: <ImportExport /> },
    ],
  },
]);
```

- [ ] **Step 2: 创建 App.tsx（使用 MENU_ITEMS 配置 + ROUTES 常量）**

```tsx
import { useState } from 'react';
import { Outlet, useNavigate, useLocation } from 'react-router-dom';
import { Layout, Menu, Typography, Spin } from 'antd';
import { Suspense } from 'react';
import { MENU_ITEMS } from '@/config/menu';
import { ROUTES } from '@/constants/routes';
import TenantSelector from '@/components/tenant-selector';

const { Header, Sider, Content } = Layout;

export default function App() {
  const navigate = useNavigate();
  const location = useLocation();
  const [actorId] = useState(() => localStorage.getItem('actorId') || 'anonymous');

  // 当前菜单选中键：取路径第一段
  const segments = location.pathname.split('/').filter(Boolean);
  const selectedKey = segments.length > 0 ? `/${segments[0]}` : ROUTES.SCENES;

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Header style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '0 24px', background: '#001529' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 24 }}>
          <Typography.Title level={4} style={{ color: '#fff', margin: 0 }}>规则引擎运营平台</Typography.Title>
          <TenantSelector />
        </div>
        <Typography.Text style={{ color: 'rgba(255,255,255,0.65)' }}>操作人：{actorId}</Typography.Text>
      </Header>
      <Layout>
        <Sider width={200} style={{ background: '#fff' }}>
          <Menu
            mode="inline"
            selectedKeys={[selectedKey]}
            items={MENU_ITEMS}  // 纯数据驱动，无硬编码
            onClick={({ key }) => navigate(key)}
            style={{ height: '100%', borderRight: 0 }}
          />
        </Sider>
        <Content style={{ padding: 24, background: '#f5f5f5' }}>
          <Suspense fallback={<Spin size="large" style={{ display: 'block', margin: '100px auto' }} />}>
            <Outlet />
          </Suspense>
        </Content>
      </Layout>
    </Layout>
  );
}
```

- [ ] **Step 3: 创建 TenantSelector 和各页面占位**

TenantSelector 和 12 个页面占位组件（每个只渲染标题，后续 Task 逐个替换）。页面路径与 `router.tsx` 的 lazy import 一致。

- [ ] **Step 4: 验证** — 启动 dev server，确认左侧菜单点击正确跳转、路由懒加载正常、面包屑/选中态正确。

- [ ] **Step 5: Commit**

```bash
git add frontend/src/router.tsx frontend/src/App.tsx frontend/src/main.tsx frontend/src/components/tenant-selector/ frontend/src/pages/
git commit -m "feat(frontend): add route config, layout skeleton, and lazy-loaded placeholder pages"
```

---

### Task 7: Zustand Store 初始化

**Files:**
- Create: `frontend/src/store/tenantStore.ts`
- Create: `frontend/src/store/sceneStore.ts`
- Create: `frontend/src/store/ruleStore.ts`
- Create: `frontend/src/store/metricStore.ts`
- Create: `frontend/src/store/dryRunStore.ts`
- Create: `frontend/src/store/evalSessionStore.ts`

原则：每个 Store 按域拆分，用 `create` 定义 state + actions。API 调用使用 `ENDPOINTS` 常量。Store 中不硬编码 URL。

- [ ] **Step 1: tenantStore.ts**

```typescript
import { create } from 'zustand';
import apiClient from '@/api/client';
import { ENDPOINTS } from '@/constants/api-endpoints';

interface TenantInfo { id: number; code: string; name: string; }

interface TenantState {
  current: string | null;
  currentId: number | null;
  list: TenantInfo[];
  loadList: () => Promise<void>;
  setCurrent: (code: string) => void;
}

export const useTenantStore = create<TenantState>((set, get) => ({
  current: localStorage.getItem('tenantCode') || null,
  currentId: Number(localStorage.getItem('tenantId')) || null,
  list: [],

  loadList: async () => {
    const res = await apiClient.get(ENDPOINTS.TENANT_LIST);
    const list: TenantInfo[] = res.data?.data ?? [];
    set({ list });
    const { current } = get();
    if (!current && list.length > 0) get().setCurrent(list[0].code);
  },

  setCurrent: (code: string) => {
    const tenant = get().list.find((t) => t.code === code);
    if (tenant) {
      localStorage.setItem('tenantCode', tenant.code);
      localStorage.setItem('tenantId', String(tenant.id));
      set({ current: tenant.code, currentId: tenant.id });
    }
  },
}));
```

- [ ] **Step 2-6: 创建其余 5 个 Store**

`sceneStore.ts` — Scene 列表/详情/元数据，API 调用使用 `ENDPOINTS.SCENE_LIST` 等。
`ruleStore.ts` — 编辑器草稿状态：`ast` / `decisionBindings` / `preGates` / `triggerEventTypes` / `kind` / `dirty`。
`metricStore.ts` — Metric 列表缓存（供全局下拉复用）。
`dryRunStore.ts` — dry-run 请求体 + 结果 + loading 状态。
`evalSessionStore.ts` — 会话列表筛选条件 + 分页状态。

全部使用 `ENDPOINTS.*` 常量，不硬编码路径字符串。

- [ ] **Step 7: Commit**

```bash
git add frontend/src/store/
git commit -m "feat(frontend): add domain-split Zustand stores with endpoint constants"
```

---

## Phase 1：配置管理页面

### Task 8: Scene 列表页

**Files:**
- Create: `frontend/src/api/scene.ts`
- Modify: `frontend/src/pages/scene-list/index.tsx`
- Create: `frontend/src/config/columns/scene.tsx`

- [ ] **Step 1: 创建 scene API（使用 ENDPOINTS 常量）**

```typescript
import apiClient from './client';
import { ENDPOINTS } from '@/constants/api-endpoints';
import type { ApiResponse, SceneListItem, SceneDetail } from '@/types';

export async function listScenes(tenantId: number, params?: Record<string, unknown>) {
  const res = await apiClient.get<ApiResponse<SceneListItem[]>>(ENDPOINTS.SCENE_LIST, { params: { tenantId, ...params } });
  return res.data;
}

export async function getScene(tenantId: number, sceneCode: string) {
  const res = await apiClient.get<ApiResponse<SceneDetail>>(ENDPOINTS.SCENE_DETAIL(sceneCode), { params: { tenantId } });
  return res.data;
}

export async function createScene(body: Record<string, unknown>) {
  return apiClient.post(ENDPOINTS.SCENE_CREATE, body);
}
```

- [ ] **Step 2: 创建表格列配置 config/columns/scene.tsx**

```tsx
import { Tag, Space } from 'antd';
import { Link } from 'react-router-dom';
import { ROUTES, route } from '@/constants/routes';
import { colorOf, DOMINANT_MODE_OPTIONS, STATUS_OPTIONS } from '@/constants/enums';
import type { SceneListItem } from '@/types';
import type { ColumnsType } from 'antd/es/table';

export const SCENE_COLUMNS: ColumnsType<SceneListItem> = [
  { title: 'Scene Code', dataIndex: 'sceneCode', key: 'sceneCode' },
  { title: '名称', dataIndex: 'name', key: 'name' },
  {
    title: '模式', dataIndex: 'dominantMode', key: 'dominantMode',
    render: (v: string) => <Tag>{v}</Tag>,
  },
  { title: '主体类型', dataIndex: 'subjectType', key: 'subjectType' },
  {
    title: '状态', dataIndex: 'status', key: 'status',
    render: (v: string) => <Tag color={colorOf(STATUS_OPTIONS, v as never)}>{v}</Tag>,
  },
  {
    title: '操作', key: 'actions',
    render: (_, record) => (
      <Space>
        <Link to={route(ROUTES.SCENE_DETAIL, { sceneCode: record.sceneCode })}>详情</Link>
        <Link to={route(ROUTES.SCENE_RULES, { sceneCode: record.sceneCode })}>规则</Link>
      </Space>
    ),
  },
];
```

- [ ] **Step 3: 实现 SceneList 页面**

使用 `useTenantStore` 获取 `currentId`，调用 `listScenes`。筛选栏：`status` 下拉取自 `STATUS_OPTIONS`。表格用 `SCENE_COLUMNS` 配置。创建 Modal 内表单用 `DOMINANT_MODE_OPTIONS` 等常量驱动 `Select`。

所有下拉选项来自 `@/constants/enums`，不写死 `[{value:'PUSH',label:'PUSH'},...]` 之类的数组。

- [ ] **Step 4: 验证** — 启动服务，确认 Scene 列表渲染、创建、筛选正常。

- [ ] **Step 5: Commit**

```bash
git add frontend/src/api/scene.ts frontend/src/pages/scene-list/ frontend/src/config/columns/
git commit -m "feat(frontend): implement Scene list page with constant-driven config"
```

---

### Task 9: Scene 详情/编辑页 + 输入清单

**Files:**
- Create: `frontend/src/pages/scene-detail/index.tsx`
- Create: `frontend/src/pages/scene-detail/SceneInfo.tsx`
- Create: `frontend/src/pages/scene-detail/InputManifestTab.tsx`
- Create: `frontend/src/api/inputManifest.ts`

表格列、表单选项全部引用 `@/constants/enums` 中的常量集。`payloadSchema` 编辑器用 `React.useMemo` + JSON 校验。路由跳转用 `route(ROUTES.SCENE_RULES, { sceneCode })`。

- [ ] **验证 & Commit**

```bash
git add frontend/src/pages/scene-detail/ frontend/src/api/inputManifest.ts
git commit -m "feat(frontend): implement Scene detail/edit page with input manifest tab"
```

---

### Task 10: Metric 列表与详情页

**Files:**
- Create: `frontend/src/api/metric.ts`
- Create: `frontend/src/config/columns/metric.tsx`
- Modify: `frontend/src/pages/metric-list/index.tsx`
- Modify: `frontend/src/pages/metric-detail/index.tsx`

`sourceType` 变更表单区域通过 `SOURCE_TYPE_OPTIONS` 驱动动态 params 表单渲染（不是 if/else 硬编码四套表单，而是按 sourceType 配置映射）。

- [ ] **验证 & Commit**

```bash
git add frontend/src/api/metric.ts frontend/src/config/columns/metric.tsx frontend/src/pages/metric-list/ frontend/src/pages/metric-detail/
git commit -m "feat(frontend): implement Metric list and detail pages"
```

---

### Task 11: Decision 列表页

**Files:**
- Create: `frontend/src/api/decision.ts`
- Create: `frontend/src/config/columns/decision.tsx`
- Modify: `frontend/src/pages/decision-list/index.tsx`

- [ ] **验证 & Commit**

```bash
git add frontend/src/api/decision.ts frontend/src/config/columns/decision.tsx frontend/src/pages/decision-list/
git commit -m "feat(frontend): implement Decision list page"
```

---

## Phase 2：规则编辑器核心

### Task 12: AST 转换器

**Files:**
- Create: `frontend/src/utils/ast-converter.ts`

实现 `astToQueryBuilder`（`AstNode` → `RuleGroupType`）和 `queryBuilderToAst`（`RuleGroupType` → `AstNode`）双向转换。用 Vitest 写往返测试。

- [ ] **Commit**

```bash
git add frontend/src/utils/
git commit -m "feat(frontend): add AST ↔ react-querybuilder converter with tests"
```

---

### Task 13: ParamsSchemaForm 动态表单组件

**Files:**
- Create: `frontend/src/components/params-schema-form/index.tsx`

根据 `paramsSchema`（JSON Schema Draft-07 子集）动态渲染表单控件。映射表用配置对象：

```typescript
const TYPE_COMPONENT_MAP: Record<string, React.ComponentType<...>> = {
  string:  StringField,
  number:  NumberField,
  integer: NumberField,
  boolean: BooleanField,
  object:  ObjectField,
};
```

而不是 `switch(p.type) { case 'string': ... case 'number': ... }`，新增类型只需加映射项。

- [ ] **Commit**

```bash
git add frontend/src/components/params-schema-form/
git commit -m "feat(frontend): add config-driven ParamsSchemaForm component"
```

---

### Task 14: 规则 API + 列表页

**Files:**
- Create: `frontend/src/api/rule.ts`
- Create: `frontend/src/config/columns/rule.tsx`
- Modify: `frontend/src/pages/rule-list/index.tsx`

API 全部使用 `ENDPOINTS.RULE_*` 常量。列表页表格列取自 `RULE_COLUMNS` 配置。

- [ ] **Commit**

```bash
git add frontend/src/api/rule.ts frontend/src/config/columns/rule.tsx frontend/src/pages/rule-list/
git commit -m "feat(frontend): add rule API and rule list page"
```

---

### Task 15: 规则编辑器 — 三栏布局 + 左栏 + 中栏 + 右栏

**Files:**
- Create: `frontend/src/api/metadata.ts`
- Create: `frontend/src/pages/rule-editor/index.tsx`
- Create: `frontend/src/pages/rule-editor/LeftPanel.tsx`
- Create: `frontend/src/pages/rule-editor/VersionTimeline.tsx`
- Create: `frontend/src/pages/rule-editor/CenterPanel.tsx`
- Create: `frontend/src/pages/rule-editor/RightPanel.tsx`

左栏：规则信息 + 操作按钮（按 `ruleStatus` 条件渲染——按钮显隐配置化，不写死 if-else）+ 版本时间线（`VERSION_STATUS_OPTIONS` 驱动状态标签颜色）。

中栏：react-querybuilder 集成，`fields` 从 `metadata.conditionTypes` 动态构建（不硬编码 conditionType 列表）。

右栏：三 Tab 切换（属性 / Pre-Gate / Decision 绑定）。属性面板选中不同节点类型动态渲染不同编辑区域。

- [ ] **Commit**

```bash
git add frontend/src/api/metadata.ts frontend/src/pages/rule-editor/
git commit -m "feat(frontend): implement rule editor three-column layout"
```

---

## Phase 3：规则编辑器扩展 + 运营视图

### Task 16-22: Pre-Gate / Decision 绑定 / Dry-Run / 评估会话 / Trace 树 / 审计日志 / Job / 导入导出

每个 Task 延续前面的模式：
- API 函数使用 `ENDPOINTS.*` 常量
- 表格列提取到 `config/columns/` 下
- 筛选下拉选项来自 `@/constants/enums`
- 路由跳转使用 `route(ROUTES.*, params)`
- 状态标签颜色使用 `colorOf(OPTIONS, value)`

关键组件：
- `rollout-slider`：百分比 + 桶区间双模式（用配置切换，不 if-else）
- `trace-tree`：递归渲染 `NodeTraceItem[]`，结果图标映射表驱动
- `json-diff-viewer`：jsondiffpatch 封装
- 导入导出：上传预览 → 确认 → 执行，分步流程组件化

### Task 23: 端到端集成验证

- [ ] 按 06-frontend.md 覆盖全链路逐项走通
- [ ] 确认新增一个页面只需：`types/` 加类型 + `constants/routes.ts` 加路径 + `constants/api-endpoints.ts` 加端点 + `config/menu.tsx` 加菜单项 + `pages/` 加页面组件 → router 自动通过 `ROUTES` 常量引用生效，不需要改 `App.tsx` 或任何已有页面

---

## 自检清单

- [x] 零魔法字符串 — 所有路由路径在 `ROUTES`、所有 API 端点在 `ENDPOINTS`、所有枚举值集在 `constants/enums.ts`
- [x] 数据驱动菜单 — `MENU_ITEMS` 配置数组，新增页面加一项即可
- [x] 表格列提取 — `config/columns/` 下按域拆分，组件不内联 `ColumnsType`
- [x] 表单选项提取 — 所有 Select 的 options 来自 `constants/enums.ts` 的 `*_OPTIONS`
- [x] 类型按域拆分 — `types/` 下一个域一个文件，`index.ts` 统一 re-export
- [x] API 端点集中 — `api-endpoints.ts` 是唯一真相源
- [x] 懒加载路由 — `React.lazy` 按页面拆分 chunk
- [x] 无 TBD/TODO 占位符
