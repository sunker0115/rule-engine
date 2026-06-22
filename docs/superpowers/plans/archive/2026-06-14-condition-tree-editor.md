# 条件组合树编辑器 — 自建替换 react-querybuilder

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 移除 react-querybuilder，自建 condition 组合树编辑器，组件直读 AST，CenterPanel 按 kind 路由到不同编辑器。

**Architecture:** `CenterPanel` 按 `kind` 分发到不同编辑器组件。`AST_BOOLEAN` 走自建的 `ConditionTreeEditor`，内部 `GroupEditor`(|NestedGroup|) 和 `ConditionCard`(|ConditionNode|) 递归渲染，直接读写 `ruleStore.ast`。`ast-converter` 转换层和 `react-querybuilder` 依赖一并删除。

**Tech Stack:** React + TypeScript + Ant Design + zustand，零第三方编辑器依赖。

---

### Task 1: 修复 ConditionTypeMeta 类型匹配后端实际响应

**Files:**
- Modify: `frontend/src/types/metadata.ts:3-8`

- [ ] **Step 1: 更新类型定义**

后端 `OperatorSpec` JSON 序列化后是 `requiredParamKeys`（数组）+ `allowedDataTypes`（数组）+ `requiresMetric`（布尔），不存在 `paramsSchema` 字段。实际运行时 `paramsSchema` 为 `undefined`。

```typescript
export interface ConditionTypeMeta {
  code: string;
  displayName: string;
  /** 必填参数键名列表（来自 OperatorSpec.requiredParamKeys） */
  requiredParamKeys: string[];
  /** 允许的 metric/payload dataType 标签（来自 OperatorSpec.allowedDataTypes） */
  allowedDataTypes: string[];
  /** 是否需绑定 metric/payload */
  requiresMetric: boolean;
}
```

- [ ] **Step 2: 确认类型修改后编译通过**

```bash
cd frontend && npx tsc --noEmit
```

Expected: 无新增类型错误。`paramsSchema` 在现有代码中未被使用（仅 `CenterPanel.tsx` 用到 `metadata.conditionTypes` 取 `code` 和 `displayName` 映射 field 列表），修改后编译应通过。

- [ ] **Step 3: Commit**

```bash
git add frontend/src/types/metadata.ts
git commit -m "fix(frontend): ConditionTypeMeta 匹配后端 OperatorSpec 实际字段"
```

---

### Task 2: 添加参数键注册表

**Files:**
- Create: `frontend/src/utils/param-registry.ts`

- [ ] **Step 1: 创建 param-registry.ts**

将 `ConditionParams` 常量映射到前端显示名和输入控件类型。未知键回退到 `Input`。

```typescript
/** 参数控件类型 */
export type ParamWidget = 'text' | 'number' | 'array' | 'time-range' | 'operator-select';

/** 已知参数键 → 显示名 + 控件类型 */
const PARAM_REGISTRY: Record<string, { label: string; widget: ParamWidget }> = {
  threshold:    { label: '阈值', widget: 'number' },
  min:          { label: '下限', widget: 'number' },
  max:          { label: '上限', widget: 'number' },
  values:       { label: '候选值', widget: 'array' },
  element:      { label: '元素', widget: 'text' },
  prefix:       { label: '前缀', widget: 'text' },
  suffix:       { label: '后缀', widget: 'text' },
  regex:        { label: '正则', widget: 'text' },
  operator:     { label: '运算符', widget: 'operator-select' },
  start:        { label: '开始时间', widget: 'text' },
  end:          { label: '结束时间', widget: 'text' },
  value:        { label: '比较值', widget: 'text' },
  timezone:     { label: '时区', widget: 'text' },
  datesExclude: { label: '排除日期', widget: 'array' },
  daysOfWeek:   { label: '生效星期', widget: 'array' },
};

/** 获取参数显示名 */
export function paramLabel(key: string): string {
  return PARAM_REGISTRY[key]?.label ?? key;
}

/** 获取参数控件类型；未知键默认 text */
export function paramWidget(key: string): ParamWidget {
  return PARAM_REGISTRY[key]?.widget ?? 'text';
}
```

- [ ] **Step 2: Commit**

```bash
git add frontend/src/utils/param-registry.ts
git commit -m "feat(frontend): 添加条件参数键注册表 param-registry"
```

---

### Task 3: 创建 ConditionCard 组件

**Files:**
- Create: `frontend/src/pages/rule-editor/ConditionCard.tsx`

- [ ] **Step 1: 创建 ConditionCard**

单条条件卡片：conditionType 选择器 + valueRef 切换 + metric/payload 选择器 + 参数动态表单。

```typescript
import { useMemo, useCallback } from 'react';
import { Card, Select, Input, InputNumber, Tag, Button, Space } from 'antd';
import { DeleteOutlined } from '@ant-design/icons';
import type { ConditionNode, ConditionTypeMeta, MetricDescriptor } from '@/types';
import { paramLabel, paramWidget } from '@/utils/param-registry';

interface Props {
  node: ConditionNode;
  /** 可用条件类型（来自场景元数据） */
  conditionTypes: ConditionTypeMeta[];
  /** 可用指标（来自场景元数据） */
  availableMetrics: MetricDescriptor[];
  onChange: (node: ConditionNode) => void;
  onDelete: () => void;
}

/** 按 conditionType code 查元数据 */
function findMeta(types: ConditionTypeMeta[], code: string): ConditionTypeMeta | undefined {
  return types.find(t => t.code === code);
}

export default function ConditionCard({
  node, conditionTypes, availableMetrics, onChange, onDelete,
}: Props) {
  const meta = useMemo(() => findMeta(conditionTypes, node.conditionType), [conditionTypes, node.conditionType]);
  const requiresMetric = meta?.requiresMetric ?? true;

  // 更新 params 中单个字段
  const setParam = useCallback((key: string, value: unknown) => {
    onChange({ ...node, params: { ...node.params, [key]: value } });
  }, [node, onChange]);

  // 渲染参数控件
  const renderParamField = (key: string) => {
    const widget = paramWidget(key);
    const label = paramLabel(key);
    const val = node.params[key];

    switch (widget) {
      case 'number':
        return (
          <div key={key} style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
            <span style={{ fontSize: 12, color: '#999', whiteSpace: 'nowrap' }}>{label}</span>
            <InputNumber
              size="small"
              style={{ width: 100 }}
              value={val as number | undefined}
              onChange={(v) => setParam(key, v)}
            />
          </div>
        );
      case 'array':
        return (
          <div key={key} style={{ display: 'flex', alignItems: 'center', gap: 6, flex: 1 }}>
            <span style={{ fontSize: 12, color: '#999', whiteSpace: 'nowrap' }}>{label}</span>
            <Select
              mode="tags"
              size="small"
              style={{ minWidth: 120, flex: 1 }}
              value={Array.isArray(val) ? val as string[] : []}
              onChange={(v) => setParam(key, v)}
              placeholder={label}
            />
          </div>
        );
      case 'operator-select':
        return (
          <div key={key} style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
            <span style={{ fontSize: 12, color: '#999', whiteSpace: 'nowrap' }}>{label}</span>
            <Select
              size="small"
              style={{ width: 100 }}
              value={val as string}
              onChange={(v) => setParam(key, v)}
              options={[
                { value: 'BEFORE', label: '早于' },
                { value: 'AFTER', label: '晚于' },
                { value: 'BETWEEN', label: '在…之间' },
              ]}
            />
          </div>
        );
      default:
        return (
          <div key={key} style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
            <span style={{ fontSize: 12, color: '#999', whiteSpace: 'nowrap' }}>{label}</span>
            <Input
              size="small"
              style={{ width: 100 }}
              value={val as string ?? ''}
              onChange={(e) => setParam(key, e.target.value)}
            />
          </div>
        );
    }
  };

  const requiredKeys = meta?.requiredParamKeys ?? Object.keys(node.params);

  const valueRefColor = node.valueRef === 'PAYLOAD' ? '#fa8c16' : '#1890ff';

  return (
    <Card
      size="small"
      style={{
        marginBottom: 4,
        borderLeft: `3px solid ${valueRefColor}`,
      }}
      bodyStyle={{ padding: '8px 12px' }}
    >
      {/* 头部：条件类型 + valueRef 标签 + 删除 */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 6 }}>
        <Select
          size="small"
          style={{ width: 160 }}
          value={node.conditionType}
          onChange={(code) => {
            const newMeta = findMeta(conditionTypes, code);
            // 切换 conditionType 时重置 params 为必填键的默认值
            const defaultParams: Record<string, unknown> = {};
            if (newMeta) {
              for (const k of newMeta.requiredParamKeys) defaultParams[k] = undefined;
            }
            onChange({ ...node, conditionType: code, params: defaultParams });
          }}
          options={conditionTypes.map((ct) => ({ value: ct.code, label: ct.displayName }))}
        />
        {requiresMetric && (
          <Select
            size="small"
            style={{ width: 80 }}
            value={node.valueRef ?? 'METRIC'}
            onChange={(ref) => onChange({ ...node, valueRef: ref as 'METRIC' | 'PAYLOAD' })}
            options={[
              { value: 'METRIC', label: '指标' },
              { value: 'PAYLOAD', label: 'Payload' },
            ]}
          />
        )}
        <div style={{ flex: 1 }} />
        <Button type="text" size="small" icon={<DeleteOutlined />} danger onClick={onDelete} />
      </div>

      {/* 参数区域 */}
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8, alignItems: 'center' }}>
        {/* valueRef 对应的值选择 */}
        {requiresMetric && node.valueRef !== 'PAYLOAD' && (
          <Select
            size="small"
            showSearch
            style={{ width: 180 }}
            value={node.metricCode || undefined}
            onChange={(code) => onChange({ ...node, metricCode: code })}
            placeholder="选择指标"
            options={availableMetrics.map((m) => ({ value: m.metricCode, label: m.metricCode }))}
          />
        )}
        {node.valueRef === 'PAYLOAD' && (
          <Input
            size="small"
            style={{ width: 120 }}
            value={node.metricCode ?? ''}
            onChange={(e) => onChange({ ...node, metricCode: e.target.value })}
            placeholder="payload 字段名"
          />
        )}
        {requiredKeys.map(renderParamField)}
      </div>
    </Card>
  );
}
```

- [ ] **Step 2: Commit**

```bash
git add frontend/src/pages/rule-editor/ConditionCard.tsx
git commit -m "feat(frontend): 创建 ConditionCard —— 单条条件卡片组件"
```

---

### Task 4: 创建 GroupEditor 组件

**Files:**
- Create: `frontend/src/pages/rule-editor/GroupEditor.tsx`

- [ ] **Step 1: 创建 GroupEditor**

递归 AND/OR 组合器组件：组合器切换 + 子节点列表（嵌套 GroupEditor 或 ConditionCard）+ 添加/包装 NOT 按钮。直接操作 `AstNode` — AndNode/OrNode 的 `children` 是 `AstNode[]`，每项可能是 AndNode/OrNode（递归 GroupEditor）或 ConditionNode（ConditionCard）或 NotNode（NotWrapper）。

```typescript
import { Button, Select, Space, Typography } from 'antd';
import { PlusOutlined, ExceptionOutlined } from '@ant-design/icons';
import type { AstNode, AndNode, OrNode, NotNode, ConditionTypeMeta, MetricDescriptor } from '@/types';
import ConditionCard from './ConditionCard';

interface Props {
  node: AndNode | OrNode | NotNode;
  conditionTypes: ConditionTypeMeta[];
  availableMetrics: MetricDescriptor[];
  onChange: (node: AstNode) => void;
  /** 当前节点在父节点中的索引（用于删除） */
  onDelete?: () => void;
}

/** 创建空 ConditionNode */
function emptyCondition(): AstNode {
  return {
    type: 'ConditionNode',
    conditionType: '',
    params: {},
  };
}

/** 创建空 AndGroup */
function emptyGroup(): AstNode {
  return { type: 'AndNode', children: [] };
}

export default function GroupEditor({
  node, conditionTypes, availableMetrics, onChange, onDelete,
}: Props) {
  // NotNode: 包裹层
  if (node.type === 'NotNode') {
    const child = node.child;
    return (
      <div style={{ border: '1px dashed #ff4d4f', borderRadius: 6, padding: '8px 12px', marginBottom: 4 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 4 }}>
          <Typography.Text type="danger" strong style={{ fontSize: 12 }}>NOT</Typography.Text>
          <div style={{ flex: 1 }} />
          <Button
            type="text" size="small"
            onClick={() => {
              // 移除 NOT 包装：直接返回内层子节点
              onChange(child);
            }}
          >
            解除 NOT
          </Button>
          {onDelete && (
            <Button type="text" size="small" danger onClick={onDelete}>删除组</Button>
          )}
        </div>
        {/* 递归渲染内层 */}
        {child.type === 'ConditionNode' ? (
          <ConditionCard
            node={child}
            conditionTypes={conditionTypes}
            availableMetrics={availableMetrics}
            onChange={(n) => onChange({ ...node, child: n })}
            onDelete={() => onChange({ type: 'AndNode', children: [] })}
          />
        ) : (
          <GroupEditor
            node={child as AndNode | OrNode}
            conditionTypes={conditionTypes}
            availableMetrics={availableMetrics}
            onChange={(n) => onChange({ ...node, child: n })}
          />
        )}
      </div>
    );
  }

  // AndNode / OrNode
  const combinator = node.type === 'OrNode' ? 'or' : 'and';

  const updateChild = (index: number, child: AstNode) => {
    const children = [...node.children];
    children[index] = child;
    onChange({ ...node, children });
  };

  const removeChild = (index: number) => {
    const children = node.children.filter((_, i) => i !== index);
    onChange({ ...node, children });
  };

  const addCondition = () => {
    onChange({ ...node, children: [...node.children, emptyCondition()] });
  };

  const addGroup = () => {
    onChange({ ...node, children: [...node.children, emptyGroup()] });
  };

  const wrapWithNot = (index: number) => {
    const children = [...node.children];
    children[index] = { type: 'NotNode', child: children[index] };
    onChange({ ...node, children });
  };

  return (
    <div
      style={{
        border: '1px solid #d9d9d9',
        borderRadius: 6,
        padding: '10px 12px',
        marginBottom: 4,
        background: '#fafafa',
      }}
    >
      {/* 组头 */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 8 }}>
        <Select
          size="small"
          style={{ width: 70 }}
          value={combinator}
          onChange={(val) => {
            if (val === 'or') {
              onChange({ type: 'OrNode', children: node.children });
            } else {
              onChange({ type: 'AndNode', children: node.children });
            }
          }}
          options={[
            { value: 'and', label: 'AND' },
            { value: 'or', label: 'OR' },
          ]}
        />
        <span style={{ fontSize: 12, color: '#999' }}>
          {combinator === 'and' ? '满足以下全部条件：' : '满足以下任一条件：'}
        </span>
        <div style={{ flex: 1 }} />
        <Button size="small" icon={<PlusOutlined />} onClick={addCondition}>条件</Button>
        <Button size="small" onClick={addGroup}>子组</Button>
        {onDelete && (
          <Button size="small" danger onClick={onDelete}>删除组</Button>
        )}
      </div>

      {/* 子节点列表 */}
      <div style={{ marginLeft: 12 }}>
        {node.children.map((child, index) => (
          <div key={index} style={{ display: 'flex', alignItems: 'flex-start', gap: 4 }}>
            <div style={{ flex: 1 }}>
              {child.type === 'ConditionNode' ? (
                <ConditionCard
                  node={child}
                  conditionTypes={conditionTypes}
                  availableMetrics={availableMetrics}
                  onChange={(n) => updateChild(index, n)}
                  onDelete={() => removeChild(index)}
                />
              ) : child.type === 'NotNode' ? (
                <GroupEditor
                  node={child}
                  conditionTypes={conditionTypes}
                  availableMetrics={availableMetrics}
                  onChange={(n) => updateChild(index, n)}
                  onDelete={() => removeChild(index)}
                />
              ) : (
                <GroupEditor
                  node={child as AndNode | OrNode}
                  conditionTypes={conditionTypes}
                  availableMetrics={availableMetrics}
                  onChange={(n) => updateChild(index, n)}
                  onDelete={() => removeChild(index)}
                />
              )}
            </div>
            {/* NOT 包装按钮 */}
            {child.type !== 'NotNode' && (
              <Button
                type="text"
                size="small"
                icon={<ExceptionOutlined />}
                title="包装为 NOT"
                onClick={() => wrapWithNot(index)}
              />
            )}
          </div>
        ))}
        {node.children.length === 0 && (
          <div style={{ padding: 16, textAlign: 'center', color: '#ccc', fontSize: 13 }}>
            暂无条件，点击"条件"或"子组"添加
          </div>
        )}
      </div>
    </div>
  );
}
```

- [ ] **Step 2: Commit**

```bash
git add frontend/src/pages/rule-editor/GroupEditor.tsx
git commit -m "feat(frontend): 创建 GroupEditor —— 递归 AND/OR/NOT 组合器组件"
```

---

### Task 5: 创建 ConditionTreeEditor 顶层组件

**Files:**
- Create: `frontend/src/pages/rule-editor/ConditionTreeEditor.tsx`

- [ ] **Step 1: 创建 ConditionTreeEditor**

顶层入口：处理空 AST、单 ConditionNode 必须包裹为 Group、NotNode 在最外层的情况。

```typescript
import { Button, Empty } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import type { AstNode, ConditionTypeMeta, MetricDescriptor } from '@/types';
import GroupEditor from './GroupEditor';

interface Props {
  ast: AstNode | null;
  conditionTypes: ConditionTypeMeta[];
  availableMetrics: MetricDescriptor[];
  onChange: (ast: AstNode) => void;
}

/** 创建带一个空条件的 AndGroup */
function emptyAst(): AstNode {
  return { type: 'AndNode', children: [] };
}

export default function ConditionTreeEditor({
  ast, conditionTypes, availableMetrics, onChange,
}: Props) {
  // null / 空 AndNode / 空 OrNode → 显示空状态
  const node = ast ?? { type: 'AndNode' as const, children: [] };
  const isEmpty =
    (node.type === 'AndNode' || node.type === 'OrNode') &&
    node.children.length === 0;

  if (isEmpty) {
    return (
      <div style={{ padding: 40, textAlign: 'center' }}>
        <Empty description="暂无条件">
          <Button
            type="primary"
            icon={<PlusOutlined />}
            onClick={() => onChange(emptyAst())}
          >
            添加第一个条件
          </Button>
        </Empty>
      </div>
    );
  }

  return (
    <GroupEditor
      node={node as never}
      conditionTypes={conditionTypes}
      availableMetrics={availableMetrics}
      onChange={onChange}
    />
  );
}
```

- [ ] **Step 2: Commit**

```bash
git add frontend/src/pages/rule-editor/ConditionTreeEditor.tsx
git commit -m "feat(frontend): 创建 ConditionTreeEditor —— 条件树顶层入口"
```

---

### Task 6: 改造 CenterPanel 为 kind 路由

**Files:**
- Modify: `frontend/src/pages/rule-editor/CenterPanel.tsx`
- Remove: `import 'react-querybuilder/dist/query-builder.css'` (第 2 行)
- Remove: `import type { Field, RuleGroupType, RuleType, ValueEditorProps } from 'react-querybuilder'` (第 7 行)
- Remove: `ValueEditor` 组件 (第 13-52 行)
- Remove: `VALUE_SOURCE_OPS` 常量 (第 54-57 行)
- Remove: `astToQueryBuilder`/`queryBuilderToAst`/`getParams` imports
- Remove: `QueryBuilder` 渲染及 `controlElements`

- [ ] **Step 1: 重写 CenterPanel.tsx**

```typescript
import { useTranslation } from 'react-i18next';
import { useRuleStore } from '@/store/ruleStore';
import type { SceneMetadata as SceneMetadataType } from '@/types';
import ConditionTreeEditor from './ConditionTreeEditor';

interface Props { metadata: SceneMetadataType | null; }

export default function CenterPanel({ metadata }: Props) {
  const { t } = useTranslation('rule');
  const { ast, setAst, kind } = useRuleStore();

  if (kind !== 'AST_BOOLEAN') {
    return (
      <div style={{ padding: 40, textAlign: 'center', color: '#999' }}>
        {t('editor.centerPanel.placeholder')} ({kind})
      </div>
    );
  }

  return (
    <ConditionTreeEditor
      ast={ast}
      conditionTypes={metadata?.conditionTypes ?? []}
      availableMetrics={metadata?.availableMetrics ?? []}
      onChange={setAst}
    />
  );
}
```

- [ ] **Step 2: 编译检查**

```bash
cd frontend && npx tsc --noEmit
```

Expected: 无类型错误。

- [ ] **Step 3: Commit**

```bash
git add frontend/src/pages/rule-editor/CenterPanel.tsx
git commit -m "feat(frontend): CenterPanel 按 kind 路由，AST_BOOLEAN 走自建条件树"
```

---

### Task 7: 清理 react-querybuilder 和 ast-converter

**Files:**
- Delete: `frontend/src/utils/ast-converter.ts`
- Delete: `frontend/src/utils/__tests__/ast-converter.test.ts`
- Modify: `frontend/package.json` — 删除 `"react-querybuilder": "^8.19.0"`

- [ ] **Step 1: 删除 ast-converter 相关文件**

```bash
rm frontend/src/utils/ast-converter.ts
rm frontend/src/utils/__tests__/ast-converter.test.ts
```

- [ ] **Step 2: 卸载 react-querybuilder**

```bash
cd frontend && npm uninstall react-querybuilder
```

- [ ] **Step 3: 编译 + 测试检查**

```bash
cd frontend && npx tsc --noEmit && npm test -- --run
```

Expected: 编译通过，无失败测试。

- [ ] **Step 4: Commit**

```bash
git add frontend/src/utils/ast-converter.ts frontend/src/utils/__tests__/ast-converter.test.ts frontend/package.json frontend/package-lock.json
git commit -m "chore(frontend): 移除 react-querybuilder 及 ast-converter 转换层"
```

---

### Task 8: 端到端验证

**前置条件：** 后端服务运行中，存在至少一条 AST_BOOLEAN 规则。

- [ ] **Step 1: 启动前端 dev server**

```bash
cd frontend && npm run dev
```

- [ ] **Step 2: 打开规则编辑器页面**

访问一条 AST_BOOLEAN 规则的编辑页。验证：
- 条件树渲染正常（AND/OR 组合器切换、条件卡片、参数表单）
- conditionType 下拉可选择、切换后参数重置
- 指标/Payload 切换可用，metric 下拉展示 `availableMetrics`
- AND ↔ OR 切换正常
- 添加/删除条件、子组正常
- NOT 包装/解除正常
- 空树显示 "添加第一个条件" 按钮
- 左侧面板保存按钮可用，保存后刷新数据不变

- [ ] **Step 3: 验证非 AST_BOOLEAN 的 kind**

打开一条 EXPRESSION_SCRIPT 规则编辑页，确认显示 placeholder（非条件树）。

- [ ] **Step 4: 清理验证过程中产生的测试数据**

---

### 影响面总结

| 文件 | 操作 | 说明 |
|---|---|---|
| `types/metadata.ts` | 修改 | `paramsSchema` → `requiredParamKeys` + `allowedDataTypes` |
| `utils/param-registry.ts` | 新建 | 参数键 → 显示名/控件类型映射 |
| `pages/rule-editor/ConditionCard.tsx` | 新建 | 单条件卡片 |
| `pages/rule-editor/GroupEditor.tsx` | 新建 | 递归 AND/OR/NOT 组 |
| `pages/rule-editor/ConditionTreeEditor.tsx` | 新建 | 条件树顶层入口 |
| `pages/rule-editor/CenterPanel.tsx` | 重写 | 移除 react-querybuilder，改为 kind 路由 |
| `utils/ast-converter.ts` | 删除 | 不再需要 |
| `utils/__tests__/ast-converter.test.ts` | 删除 | 不再需要 |
| `package.json` | 修改 | 移除 react-querybuilder 依赖 |
| `store/ruleStore.ts` | 不变 | AST 直接读写，store 不感知编辑器变化 |
| `pages/rule-editor/LeftPanel.tsx` | 不变 | 保存逻辑不感知编辑器变化 |
| `pages/rule-editor/index.tsx` | 不变 | 布局不感知编辑器变化 |
