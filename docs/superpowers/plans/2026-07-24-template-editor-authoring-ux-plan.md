# 模板编辑器授权 UX — 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 Task 12 的占位模板编辑器(JSON 文本框 + 手打 JsonPointer + 仅 AST kind)正式化:复用 `RuleBodyEditor` 搭 skeleton + 按钮唤起的位置选择器(不暴露 pointer)+ 脚本 params 表 + 参照场景;Flow 仅结构参数化。

**Architecture:** 纯前端,后端 0 改动。单一真相源 `script.params`;`RuleBodyEditor`/`FlowCanvasEditor`/`expressionCompletions`/实例化值输入全部复用;`editableParams` 一个组件分流模板(可编辑)/规则(只读)两场景。

**Tech Stack:** React + TypeScript + Ant Design + zustand + CodeMirror + i18next;Vite + **Vitest(jsdom + @testing-library/react)**。验证门槛 = `npm test`(vitest run)+ `npx tsc -b` 干净 + §末手动核对清单。

**测试基座(已由 controller 建好,Task 从此依赖):** vitest 已接线——`package.json` `test`=`vitest run`;`vite.config.ts` 有 `test` 段(globals/jsdom/setupFiles);`src/test/setup.ts` 引 `@testing-library/jest-dom/vitest`;已装 jsdom/@testing-library/{react,jest-dom,user-event}。纯逻辑测 node 侧、组件测 jsdom+render 均验证可跑。测试文件就近放 `*.test.ts(x)`。

## Global Constraints

- 后端 0 改动;不碰 kernel/config/api。
- 用户**不接触 JsonPointer**:位置靠"选位置"派生,pointer 藏背后。
- `params` 是单一真相源(`script.params`),喂补全/默认/slot 候选;补全只浮出已声明键,不做创建入口。
- `editableParams`:模板编辑器 true(可增删改),规则编辑器 false(只读 round-trip)。
- Flow 本轮**仅结构参数化**(RuleRefNode.ruleCode/OutputNode.decisionCode/SwitchNode.caseKeys);flow 表达式 `params.x` 常量 UI 不做。
- dataType 用 `types/template.ts` 的 `DataType`(8 值,不含 UNKNOWN);slot 无 defaultValue(默认=skeleton 位置值)。
- 每 Task 收尾 `npm test`(vitest,若该 Task 有测试)+ `npx tsc -b` 必须干净;i18n key 在用到处补齐(en + zh-CN + types.ts)。
- **纯逻辑必写 vitest**(introspect / expressionCompletions);组件写渲染 smoke + 关键行为(SlotValueInput 按类型渲染、参数表增删改);集成/编辑器整体靠手动核对(§Task 7)。
- 匹配现有 React/AntD/i18n 风格,不引新依赖。

**Task 5 与 Task 6 的接口契约(参数化列 ↔ slots/bindings 接线):**
- ScriptEditor 新增 optional prop `onParamSlotToggle?: (key: string, enabled: boolean, dataType: DataType) => void`。
  - 模板编辑器(Task 6)传入:勾选 → 自动 `push({key,label,dataType,required:false})` 到 slots + 对应 binding `/script/params/<key>`;取消 → 移除 slot+binding。
  - 规则编辑器(editableParams=false):不传 → 参数化列隐藏。
- 模板编辑器的 `+ 参数化` 按钮(AST/Flow 位选)和脚本参数表的参数化列**走同一条 slots/bindings 面板**——前者从内省器候选生成,后者从 param key + callback 生成,两者统一对接模板编辑器的 slots/bindings state。

## 复用锚点(实现者先读这些,别臆造)

- `frontend/src/pages/rule-editor/RuleBodyEditor.tsx` —— prop 驱动,props: `{kind, ast, script, onAstChange, onScriptChange, conditionTypes, availableMetrics, payloadFieldNames, payloadFieldTypes, decisions, tenantId, sceneCode}`;覆盖 5 kind(非 flow)。
- `frontend/src/pages/rule-editor/CenterPanel.tsx` —— RuleBodyEditor / FlowCanvasEditor 的接线范例(metadata 来自 `SceneMetadata`,decisions 来自 `listDecisions`)。
- `frontend/src/pages/rule-editor/ScriptEditor.tsx` —— props `{script:{source,lang}, onChange, availableMetrics, payloadFieldNames, payloadFieldTypes, tenantId, sceneCode}`;CodeMirror + `expressionCompletions`。
- `frontend/src/pages/rule-editor/expressionCompletions.ts` —— 补全源(metrics/payload/subject namespace)。
- `frontend/src/pages/template-instantiate/` —— 现有按 slot dataType 渲染填值控件(抽 SlotValueInput 的来源)。
- `frontend/src/api/scene.ts` / `getSceneMetadata` / `getScene` —— 参照场景元数据加载(见 rule-editor/index.tsx:106-115 用法)。
- `frontend/src/types/template.ts` —— `TemplateSlot`/`SlotBinding`/`JsonPointerTarget`/`DataType`(已定义,勿改契约)。

---

### Task 1: 脚本 params round-trip(types + store)

**Files:**
- Modify: `frontend/src/types/rule.ts`(已起改,收尾)
- Modify: `frontend/src/store/ruleStore.ts`(已起改,收尾)

**Interfaces:**
- Produces: `ScriptParams = Record<string, unknown>`;`RuleBody` 的 `ScriptBody.script` 与 `BodyCarriers.script` 带 `params?: ScriptParams`;`carriersToBody`/`bodyToCarriers` 透传 params。

**Context:** 修数据丢失 bug——模板实例化出的 script 规则 body 带 `params`,规则编辑器打开→保存若不透传 params 会丢。本 Task 已在会话中起改(types/rule.ts 加 `ScriptParams` + ScriptBody/BodyCarriers/carriersToBody 带 params;ruleStore 5 处 script 类型加 params)。收尾验证。

- [ ] **Step 1: 确认改动完整**

检查 `types/rule.ts`:`ScriptParams` 已导出;`RuleBody` 的 ScriptBody = `{ type:'ScriptBody'; script:{source;lang;params?:ScriptParams} }`;`BodyCarriers.script` 带 params;`carriersToBody` 参数类型 + 返回带 params;`bodyToCarriers` 透传 `body.script`(整对象,自动含 params)。
检查 `store/ruleStore.ts`:import `ScriptParams`;5 处 `{source;lang}` → `{source;lang;params?:ScriptParams}`。

- [ ] **Step 2: 验证**

```bash
cd /Users/sunke/dev/ai-project/rule-engine/frontend && npx tsc -b
```
预期:exit 0。

- [ ] **Step 3: Commit**

```bash
git add frontend/src/types/rule.ts frontend/src/store/ruleStore.ts
git commit -m "fix(frontend): 脚本 params round-trip——ScriptBody/carriers/store 透传 params,修实例化脚本规则编辑丢 params"
```

---

### Task 2: expressionCompletions 加 params 命名空间

**Files:**
- Modify: `frontend/src/pages/rule-editor/expressionCompletions.ts`

**Interfaces:**
- Consumes: 新增入参 `paramKeys: string[]`(当前脚本已声明的 param 键)。
- Produces: `expressionCompletions(ctx, metrics, payloadFields, payloadTypes, paramKeys)` —— 顶层补全含 `params`;`params.` 后补全 paramKeys。

**Context:** `params` 作为第 4 命名空间接进现有补全,与 metrics/payload/subject 平级,触发同为 `.`。补全源 paramKeys 由调用方(ScriptEditor)从 `script.params` 键派生。

- [ ] **Step 1: 改 expressionCompletions**

顶层 builtins 数组(现 metrics/payload/subject 那段)加一项:
```typescript
{ label: 'params', type: 'namespace', detail: '模板常量', info: '本规则冻结常量(params.<键>)' },
```
命名空间正则(现 `/(?:metrics|payload|subject)\.(\w*)/`)扩为:
```typescript
const nsWord = ctx.matchBefore(/(?:metrics|payload|subject|params)\.(\w*)/);
```
在 metrics/payload/subject 分支后加 params 分支:
```typescript
if (prefix === 'params') {
  return {
    from: nsWord.from + 'params.'.length,
    options: paramKeys
      .filter((k) => k.startsWith(partial))
      .map((k) => ({ label: k, type: 'property', detail: '常量' })),
  };
}
```
函数签名加 `paramKeys: string[]`(放末位)。

- [ ] **Step 2: 改所有调用点传 paramKeys(先传空,Task 5 再接真源)**

grep `expressionCompletions(` 找调用点(ScriptEditor 的 `completeFn`、flow 的 ExpressionInput 若有)。**本轮全部传 `[]`**(空数组)——ScriptEditor 还没接 params 表,params 尚未存在,不能传真键(tsc 也报错)。Task 5 再来把 ScriptEditor 调用改为真源。

- [ ] **Step 3: 写 vitest(纯逻辑)**

`frontend/src/pages/rule-editor/expressionCompletions.test.ts`:构造一个最小 `CompletionContext`(可用 vitest 手搓 `{ matchBefore: (re) => ... , pos, state }` 或读 CodeMirror 测试范式),断言:
- 顶层输入 `par` → 建议含 `params` namespace 项。
- `params.th` + paramKeys=['threshold','thd'] → options 含 `threshold`/`thd`,过滤掉不匹配。
- `metrics.`/`payload.` 分支不受影响(回归)。
先读 `expressionCompletions` 现有测试范式(若无则参考 `ctx.matchBefore` 签名手构 stub)。

- [ ] **Step 4: 验证**

```bash
cd frontend && npm test -- expressionCompletions && npx tsc -b
```

- [ ] **Step 5: Commit**

```bash
git add frontend/src/pages/rule-editor/expressionCompletions.ts frontend/src/pages/rule-editor/expressionCompletions.test.ts frontend/src/pages/rule-editor/ScriptEditor.tsx frontend/src/pages/rule-editor/ExpressionInput.tsx
git commit -m "feat(frontend): expressionCompletions 加 params 命名空间(params.<键> 补全)+ vitest"
```

---

### Task 3: SlotValueInput 共享值输入组件

**Files:**
- Create: `frontend/src/components/SlotValueInput.tsx`
- Modify: `frontend/src/pages/template-instantiate/`(改用 SlotValueInput)

**Interfaces:**
- Produces: `<SlotValueInput dataType={DataType} value={unknown} onChange={(v:unknown)=>void} disabled?={boolean} />`

**Context:** 按 DataType 渲染值输入。实例化表单已有等价的按类型渲染逻辑——**先读 `template-instantiate/` 现有实现,把它抽成本组件**再回填两处(参数表默认值 + 实例化)。

- [ ] **Step 1: 读实例化现有值输入逻辑**

```bash
cat frontend/src/pages/template-instantiate/index.tsx
```
看它现在怎么按 `slot.dataType` 渲染 number/text/boolean/list/date 输入,抽取规则。

- [ ] **Step 2: 写 SlotValueInput**

```tsx
// frontend/src/components/SlotValueInput.tsx
import { InputNumber, Input, Switch, Select, DatePicker } from 'antd';
import type { DataType } from '@/types/template';

interface Props {
  dataType: DataType;
  value?: unknown;
  onChange?: (v: unknown) => void;
  disabled?: boolean;
}

/** 按 slot/param 的 DataType 渲染对应值输入控件;参数表默认值格与实例化填值共用。 */
export default function SlotValueInput({ dataType, value, onChange, disabled }: Props) {
  switch (dataType) {
    case 'LONG':
    case 'DOUBLE':
    case 'DECIMAL':
      return <InputNumber style={{ width: '100%' }} value={value as number} onChange={onChange} disabled={disabled}
        stringMode={dataType === 'DECIMAL'} precision={dataType === 'LONG' ? 0 : undefined} />;
    case 'BOOLEAN':
      return <Switch checked={!!value} onChange={onChange} disabled={disabled} />;
    case 'LIST':
      return <Select mode="tags" style={{ width: '100%' }} value={(value as string[]) ?? []} onChange={onChange} disabled={disabled}
        placeholder="回车分隔多个值" open={false} />;
    case 'DATE':
    case 'DATETIME':
      // 存 ISO 字符串;DatePicker 值转换由调用侧或此处 dayjs 适配(读现有实例化实现对齐)
      return <Input value={value as string} onChange={(e) => onChange?.(e.target.value)} disabled={disabled}
        placeholder={dataType === 'DATE' ? 'YYYY-MM-DD' : 'ISO-8601'} />;
    case 'STRING':
    default:
      return <Input value={value as string} onChange={(e) => onChange?.(e.target.value)} disabled={disabled} />;
  }
}
```
(DATE/DATETIME 若实例化现有实现用了 DatePicker + dayjs,则对齐那套,不要退化成纯文本——以现有实现为准。)

- [ ] **Step 3: 实例化表单改用 SlotValueInput**

把 `template-instantiate/` 里按类型 render 的那段替换为 `<SlotValueInput dataType={slot.dataType} value={...} onChange={...} />`,删重复逻辑。行为不变。

- [ ] **Step 4: 写 vitest(组件渲染 + 行为)**

`frontend/src/components/SlotValueInput.test.tsx`(testing-library):
- `dataType=BOOLEAN` → 渲染 Switch;点击触发 `onChange(true/false)`。
- `dataType=LONG` → 渲染数值输入;输入触发 `onChange(number)`。
- `dataType=STRING` → 渲染文本框。
- `dataType=LIST` → 渲染多值(tags)输入。
覆盖各分支渲染 + 至少一个 onChange 行为。

- [ ] **Step 5: 验证 + Commit**

```bash
cd frontend && npm test -- SlotValueInput && npx tsc -b
git add frontend/src/components/SlotValueInput.tsx frontend/src/components/SlotValueInput.test.tsx frontend/src/pages/template-instantiate/
git commit -m "feat(frontend): 抽 SlotValueInput 按 DataType 渲染值输入,实例化表单复用 + vitest"
```

---

### Task 4: 位置内省器(AST + Flow 结构)

**Files:**
- Create: `frontend/src/pages/template-editor/introspect.ts`

**Interfaces:**
- Produces:
  - `interface Candidate { jsonPointer: string; label: string; currentValue: unknown; dataType: DataType }`
  - `function introspectPositions(kind: RuleKind, body: RuleBody): Candidate[]`

**Context:** 遍历 skeleton 产可参数化位置候选(标签 + pointer + 当前值 + 推断 dataType)。创作期便利,非运行时机制。AST 走值位;Flow 只走结构字段。

- [ ] **Step 1: 写 vitest(纯函数,最高价值,先写)**

`frontend/src/pages/template-editor/introspect.test.ts`——这是本轮最该单测的纯逻辑,覆盖:
- AST:`AndNode[ConditionNode{params:{threshold:100}}]` → 候选含 `{jsonPointer:'/conditionAst/children/0/params/threshold', dataType:'LONG', currentValue:100}`。
- AST 深层:`IfNode.condition` 里的 ConditionNode → pointer 含 `/conditionAst/condition/children/0/params/...`。
- 决策表:Row.conditions cell → `/conditionAst/rows/0/conditions/0`。
- Scorecard:`/conditionAst/conditions/0/...`。
- Flow 结构:RuleRefNode → `/flowGraph/nodes/0/ruleCode`;OutputNode → `.../decisionCode`;SwitchNode → `.../caseKeys`(dataType LIST)。
- inferType:integer→LONG、小数→DOUBLE、bool→BOOLEAN、array→LIST、其余→STRING。
pointer 字段名须与后端 `JsonPointerBinderTest` 路径逐字一致(children/child/condition/thenBranch/elseBranch/conditions/rows/params)。

- [ ] **Step 2: 实现 introspect**

```typescript
// frontend/src/pages/template-editor/introspect.ts
import type { RuleBody, RuleKind } from '@/types';
import type { DataType } from '@/types/template';

export interface Candidate {
  jsonPointer: string;
  label: string;
  currentValue: unknown;
  dataType: DataType;
}

/** 从字面值推断 DataType(供 slot 预填;粗粒度,作者可改)。 */
function inferType(v: unknown): DataType {
  if (typeof v === 'boolean') return 'BOOLEAN';
  if (typeof v === 'number') return Number.isInteger(v) ? 'LONG' : 'DOUBLE';
  if (Array.isArray(v)) return 'LIST';
  return 'STRING';
}

/** 遍历 skeleton 产可参数化位置候选。AST 走值位,Flow 仅结构字段,Script 走 params 键(由参数表直接管,一般不用本函数)。 */
export function introspectPositions(kind: RuleKind, body: RuleBody): Candidate[] {
  const out: Candidate[] = [];
  if (body.type === 'AstBody' && body.conditionAst) {
    walkAst(body.conditionAst, '/conditionAst', out);
  } else if (body.type === 'FlowBody') {
    walkFlowStructural(body.flowGraph, out);
  }
  return out;
}

// AST:ConditionNode.params.* 值 + weight;决策表 Row.conditions cell;递归 And/Or/Not/If/Xor/Scorecard/DecisionTree
function walkAst(node: any, ptr: string, out: Candidate[]): void {
  if (!node || typeof node !== 'object') return;
  switch (node.type) {
    case 'ConditionNode':
      Object.entries(node.params ?? {}).forEach(([k, v]) => out.push({
        jsonPointer: `${ptr}/params/${k}`, label: `${node.metricCode ?? ''} ${node.conditionType ?? ''} › ${k}`.trim(),
        currentValue: v, dataType: inferType(v),
      }));
      break;
    case 'AndNode': case 'OrNode': case 'XorNode':
      (node.children ?? []).forEach((c: any, i: number) => walkAst(c, `${ptr}/children/${i}`, out));
      break;
    case 'NotNode':
      walkAst(node.child, `${ptr}/child`, out);
      break;
    case 'IfNode':
      walkAst(node.condition, `${ptr}/condition`, out);
      walkAst(node.thenBranch, `${ptr}/thenBranch`, out);
      if (node.elseBranch) walkAst(node.elseBranch, `${ptr}/elseBranch`, out);
      break;
    case 'ScorecardRootNode':
      (node.conditions ?? []).forEach((c: any, i: number) => walkAst(c, `${ptr}/conditions/${i}`, out));
      break;
    case 'DecisionTableNode':
      (node.rows ?? []).forEach((row: any, ri: number) =>
        (row.conditions ?? []).forEach((cell: unknown, ci: number) => out.push({
          jsonPointer: `${ptr}/rows/${ri}/conditions/${ci}`, label: `决策表 › 行${ri + 1} › 列${ci + 1}`,
          currentValue: cell, dataType: inferType(cell),
        })));
      break;
  }
}

// Flow 结构字段:RuleRefNode.ruleCode / OutputNode.decisionCode / SwitchNode.caseKeys
function walkFlowStructural(graph: any, out: Candidate[]): void {
  (graph?.nodes ?? []).forEach((n: any, i: number) => {
    if (n.type === 'RuleRefNode') out.push({ jsonPointer: `/flowGraph/nodes/${i}/ruleCode`, label: `节点 ${n.id} › 被引规则`, currentValue: n.ruleCode, dataType: 'STRING' });
    if (n.type === 'OutputNode') out.push({ jsonPointer: `/flowGraph/nodes/${i}/decisionCode`, label: `节点 ${n.id} › 决策`, currentValue: n.decisionCode, dataType: 'STRING' });
    if (n.type === 'SwitchNode') out.push({ jsonPointer: `/flowGraph/nodes/${i}/caseKeys`, label: `节点 ${n.id} › 分支键`, currentValue: n.caseKeys, dataType: 'LIST' });
  });
}
```
(pointer 字段名须与后端 AST record 序列化对齐:children/child/condition/thenBranch/elseBranch/conditions/rows/params —— 与后端 JsonPointerBinderTest 的路径一致。)

- [ ] **Step 3: 验证 + Commit**

```bash
cd frontend && npx tsc -b
git add frontend/src/pages/template-editor/introspect.ts
git commit -m "feat(frontend): 模板 skeleton 位置内省器(AST 值位 + Flow 结构字段)"
```

---

### Task 5: ScriptEditor 参数表 + editableParams

**Files:**
- Modify: `frontend/src/pages/rule-editor/ScriptEditor.tsx`

**Interfaces:**
- Consumes: `SlotValueInput`(Task 3),`DataType`。
- Produces: ScriptEditor 新增 props `editableParams?: boolean`(默认 false);内部渲染 params 表;补全源从 `script.params` 键派生(接 Task 2 的 paramKeys);`onChange` 携带 `params`。

**Context:** 脚本源码框下方一张 params 表:参数名/类型/默认值(SlotValueInput)/操作。`editableParams=true` 可增删改,false 只读展示。改 params 走 `onChange({source, lang, params})`。

- [ ] **Step 1: 扩 ScriptEditor Props + params 表**

Props 加 `editableParams?: boolean`。`script` 类型已含 `params?`(Task 1)。在源码 `<div containerRef>` 下方加 params 表区块:
- 只读(editableParams=false):`List`/`Descriptions` 展示 `Object.entries(script.params)` 每项 `名 = 值`。
- 可编辑(true):表格,每行 参数名(Input)/类型(Select DataType)/默认值(SlotValueInput)/🗑;底部 `+ 添加参数`。新增/改/删都构造新 params 对象 → `onChange({ source, lang, params: next })`。
- 类型不持久化到 body(script.params 只存值);编辑期用一个 `Record<string, DataType>` 局部 state 记类型(仅 UI,或从值推断)。默认值走 SlotValueInput。
- 补全:`completeFn` 里把 `Object.keys(script?.params ?? {})` 作为 paramKeys 传给 `expressionCompletions`(接 Task 2)。
- **顺带修 RightPanel 切 lang 丢 params(数据丢失 bug):**  `frontend/src/pages/rule-editor/RightPanel.tsx` 切换脚本语言时 `setScript({ lang, source: script?.source ?? '' })` 没带 `params`——补 `params: script?.params`。与 Task 1 修的 round-trip 同宗。

(完整实现读现有 ScriptEditor/RightPanel 后照其风格写;params 表是新增 UI 块,不改 CodeMirror 部分,只在 return 里追加。)

- [ ] **Step 2b: 加 `onParamSlotToggle` prop(接口契约,供 Task 6 接线)**:
  类型签名为 `(key: string, enabled: boolean, dataType: DataType) => void`。模板编辑器传入——勾选时在模板编辑器的 slots/bindings state 里 push/remove。规则编辑器不传 →"参数化"列不渲染。
- [ ] **Step 2c: 修复 RightPanel 丢 params**

- [ ] **Step 2: 验证**

```bash
cd frontend && npx tsc -b
```

- [ ] **Step 3: Commit**

```bash
git add frontend/src/pages/rule-editor/ScriptEditor.tsx
git commit -m "feat(frontend): ScriptEditor 加 params 表 + editableParams 分流,补全接 script.params"
```

---

### Task 6: 模板编辑器重写(核心)

**Files:**
- Modify(重写): `frontend/src/pages/template-editor/index.tsx`
- Modify: `frontend/src/pages/template-list/index.tsx`(创建弹窗 kind 扩展 + Script/Flow 骨架播种)
- 依赖: `RuleBodyEditor`/`FlowCanvasEditor`(复用)、`introspect.ts`(Task 4)、`ScriptEditor` editableParams(Task 5)、`getSceneMetadata`/`getScene`/`listDecisions`。

**Interfaces:**
- Consumes: Task 3/4/5;`RuleBodyEditor` props(见复用锚点);`introspectPositions`。

**Context:** 把 JSON 文本框换成可视化 skeleton 编辑 + 参照场景 + 选位置声明 binding。这是最大一块;分两步提交(6a skeleton+参照场景+kind 扩展;6b 位置选择器+binding 派生)。

- [ ] **Step 0(6a): 创建入口(template-list)kind 扩展 + Script/Flow 骨架播种**

`frontend/src/pages/template-list/index.tsx` 的创建弹窗当前只列 AST 四种(自造 `AST_KINDS` + `label: k` 无 i18n),`handleCreate` 骨架播种也只覆盖那四种(`else` 兜底 AndNode)。改:
- **kind `Select` 复用现成共享 helper** `getRuleKindOptions(t)`(`@/constants/enums`,与 rule-list/rules-all **同一份**,全 6 kind + `enum.kind.*` i18n label),**删除自造的 `AST_KINDS` 常量**。即 `<Select options={getRuleKindOptions(t)} />`。
  - `enum.kind.*` key 在 **rule** i18n namespace 下——模板页用 `useTranslation(['template','rule'])` 并以 `t('rule:enum.kind.X')` 解析,或给 `getRuleKindOptions` 传能解析该 key 的 `t`(实现时确认 key 归属;不要在 template namespace 重复定义 kind label,避免又一处重复)。
- `handleCreate` 的 `bodySkeleton` 播种加两分支:
  - `EXPRESSION_SCRIPT` → `{ type: 'ScriptBody', script: { source: '', lang: 'CEL', params: {} } }`
  - `DECISION_FLOW` → `{ type: 'FlowBody', flowGraph: { nodes: [], edges: [], inputNodeId: '', params: {} }, referencedSnapshots: {} }`
  (AST 四种维持现状;`else` 兜底仍 AndNode。)
- 建完跳转到编辑器(现有行为),由 6a 的编辑器渲染对应 body。

- [ ] **Step 1(6a): skeleton 复用 RuleBodyEditor + 参照场景 + kind 扩展**

- kind Select **同样复用 `getRuleKindOptions(t)`**(删 `template-editor` 自造的 `AST_KINDS`);编辑态 kind 通常 disabled(kind 建后不改),但选项来源统一。
- 顶部加**参照场景 Select**(拉 tenant 场景列表,存 `refSceneCode` 局部 state);选后调 `getSceneMetadata(tenantId, refSceneCode)` + `getScene` 得 `availableMetrics/payloadFieldNames/payloadFieldTypes/conditionTypes`,`listDecisions` 得 decisions(参照 rule-editor/index.tsx:106-115 与 CenterPanel 的加载)。
- bodySkeleton 从 JSON 文本框改为:把 `bodySkeleton` 拆成 `bodyToCarriers` → 用 `RuleBodyEditor`(非 flow)或 `FlowCanvasEditor`(flow)渲染;`onChange` 收集回 `carriersToBody(kind, carriers)`。script kind 传 `editableParams`。
- 保存:`bodySkeleton` 直接用受控 state(不再 JSON.parse)。

- [ ] **Step 2(6a)验证 + Commit**

```bash
cd frontend && npx tsc -b
git add frontend/src/pages/template-editor/index.tsx frontend/src/pages/template-list/index.tsx frontend/src/i18n/
git commit -m "feat(frontend): 模板创建/编辑扩全 6 kind(Script/Flow 骨架播种)+ skeleton 改用 RuleBodyEditor/FlowCanvasEditor + 参照场景"
```

- [ ] **Step 3(6b): 位置选择器 + binding 派生(去手打 pointer)**

- bindings 面板的"手填 jsonPointer"改为:`+ 参数化` 按钮 → 弹可搜索 Select,options = `introspectPositions(kind, bodySkeleton).map(c => ({ value: c.jsonPointer, label: c.label }))`(排除已被 binding 占用的 pointer)。
- 选一个候选 → 自动:push 一条 binding `{slotKey, target:{type:'JsonPointerTarget', jsonPointer: c.jsonPointer}}` + 预填一个 slot `{key: 派生, label: c.label, dataType: c.dataType, required:false}`(作者可改 slot schema)。slotKey 默认取候选末段/param 名,去重。
- script kind:候选直接来自 `script.params` 键(参数表里勾"参数化"也可触发,二选一入口,先做位置选择器统一入口);flow:候选来自结构字段。
- bindings/slots 列表展示:binding 显示 **slot 的 label**(不显示 pointer),🗑 删除时连带 slot。
- 移除旧的手填 jsonPointer 表单 + `addBinding` 手打逻辑。

- [ ] **Step 4(6b)验证 + Commit**

```bash
cd frontend && npx tsc -b
git add frontend/src/pages/template-editor/index.tsx
git commit -m "feat(frontend): 模板 binding 改为选位置派生(不暴露 JsonPointer),接内省器候选"
```

---

### Task 7: 全量 tsc + 手动端到端核对 + i18n 收尾

**Files:**
- Modify: i18n locales(补齐本轮新增 key:参照场景/参数化/params 表相关,en + zh-CN + types.ts)

- [ ] **Step 1: i18n key 补齐**

grep 本轮新增 `t('...')` key,在 `en/template.ts`/`zh-CN/template.ts`(及 common 若用到)+ `i18n/types.ts` 补齐,tsc 不报缺 key。

- [ ] **Step 2: 全量 tsc**

```bash
cd frontend && npx tsc -b
```
exit 0。

- [ ] **Step 3: 手动端到端核对(前端无单测基座,此为验收门)**

起前端(`npm run dev`)+ 后端(模板功能已默认开),核对:
0. 创建弹窗 kind 下拉出现全 6 种;选 EXPRESSION_SCRIPT/DECISION_FLOW 建出的模板骨架为 ScriptBody/FlowBody(非兜底 AndNode)。
1. 建 EXPRESSION_SCRIPT 模板:选参照场景 → 写 `metrics.x > params.threshold`(`metrics.`/`params.` 补全出现)→ 参数表加 threshold=100 → `+ 参数化` 选中该位置生成 slot+binding(界面不见 pointer)→ 保存 → 发布。
2. 实例化该模板填 threshold=500 → 查生成规则 body `script.params.threshold=500`。
3. 该实例化规则在**规则编辑器**打开:params 表只读显示 500,改源码保存后 params 不丢(round-trip)。
4. 建 AST 模板:条件树填 threshold → `+ 参数化` 从下拉选"条件1 › 阈值"→ slot+binding 生成。
5. 建 DECISION_FLOW 模板:`+ 参数化` 可选 RuleRefNode 的"被引规则"/OutputNode 的"决策"。

- [ ] **Step 4: Commit**

```bash
git add frontend/src/i18n/
git commit -m "chore(frontend): 模板编辑器授权 UX i18n 补齐 + 全量 tsc 绿"
```

---

## 依赖顺序

```
Task 1(round-trip types/store) ─┐
Task 2(补全 params) ────────────┤
Task 3(SlotValueInput) ─────────┼─→ Task 5(ScriptEditor 参数表) ─┐
Task 4(内省器) ─────────────────┘                                ├─→ Task 6(模板编辑器重写 6a→6b) ─→ Task 7(i18n+tsc+手验)
                                                                  ┘
```

Task 1-4 相互独立可并行;Task 5 依赖 2/3;Task 6 依赖 3/4/5;Task 7 收尾。

## 自检(spec 覆盖)

- §1 Scope:AST(T4/T6)+ Script(T1/T2/T5/T6)+ Flow 结构(T4/T6)+ SlotValueInput(T3)+ 参照场景(T6a)+ round-trip(T1) ✓
- §3 位置选择器不暴露 pointer(T6b)✓;§4 参数表单一真相源(T5)✓;§5 变量语义(纯文档,无需实现)✓;§7 参照场景 A(T6a)✓;§8 editableParams(T5)✓
- §10 不做的:flow 表达式 params UI / 内联点选 / 触发字符 —— 计划中均未含 ✓
