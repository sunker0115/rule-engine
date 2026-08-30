# 模板编辑器授权 UX — 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development(推荐)或 superpowers:executing-plans。步骤用 `- [ ]` 勾选。

**Goal:** 把 Task 12 占位模板编辑器(JSON 文本框 + 手打 JsonPointer + 仅 AST kind)正式化为架构清晰、复用规则编辑器的授权界面:全 6 kind、脚本 params 表、点选式声明参数化(不暴露 JsonPointer)、参照场景。

> **后端 V2 已落地（2026-07-25）**：本计划写于后端 V2 实现之前。后端 V2 已完成的变更会影响前端，见下文「后端 V2 契约变更」。
> 
> **V2 对齐提交（`01d2dbc0`）只修了类型/API 断裂**——让 `npx tsc -b` 编译通过、类型不崩。**不涉及任何计划中的功能实现**（L0 params round-trip / L1 原语 / L2 ScriptEditor 参数表 / L3 模板编辑器重写都还没做）。**实现时按本计划设计走，不被已有 placeholder 代码牵制；已有代码该删就删，不修补旧骨架**。

**Architecture(按关注点分层,自底向上):**

```
L0 载体一致性   params 贯穿脚本载体全链路(round-trip 单一正确性关注点)
L1 可复用原语   expressionCompletions+params · SlotValueInput · introspectPositions · extractPayloadSchema 抽共享
L2 授权组件     ScriptEditor(参数表+editableParams+onParamSlotToggle)· RuleBodyEditor 透传(body-editor 门面)
L3 编排组合     template-list 创建 · template-editor(参照场景+复用 body 编辑器+slot 声明面板)
L4 收尾         i18n · 全量 tsc/vitest · 手动 e2e
```

**Tech Stack:** React + TS + AntD + zustand + CodeMirror + i18next;Vite + Vitest(jsdom + @testing-library)。门槛 = `npm test` + `npx tsc -b` 干净 + L4 手验。

## 后端 V2 契约变更（2026-07-25 对齐）

本计划最初写于后端 V2 实现之前。后端 V2 已于 2026-07-25 落地（13 commits），以下变更影响前端类型和 API 调用：

### 类型层断裂

| 原前端类型 | 后端 V2 实际 | 影响 |
|-----------|-------------|------|
| `RuleTemplate` 含 `bodySkeleton/slots/bindings/version` | 列表返回**身份字段 only** | `template-editor` 读 `data.slots` 会 `undefined` |
| `TemplateSlot` 无 `kind`，`dataType` 必填 | 后端 `TemplateSlot{kind: SlotKind, dataType?: ValueDataType, required}` | 创建/更新缺 `kind` 后端直接拒绝 |
| 无 `TemplateDetail`/`RuleTemplateVersion`/`SlotKind` 类型 | 后端新增 | GET 详情返回 `TemplateDetail{template, version}` |

### API 层断裂

| 缺失 | 后端端点 | 说明 |
|------|---------|------|
| `enableTemplate()` | `POST /{code}/enable` | 后端收尾时新增，前端计划未覆盖 |
| `getVersions()` | `GET /{code}/versions` | 版本历史查询 |
| `getTemplate` 返回类型变更 | `GET /{code}` → `TemplateDetail` | 不再是 `RuleTemplate` |
| `publish`/`disable` 缺少 `X-Actor-Id` header | 后端 publish/disable/enable 都需要 | 当前请求只传 `X-Tenant-Id` |

### 已修复（2026-07-25 前端对齐提交）

- `types/template.ts`：重写，新增 `SlotKind`、`ValueDataType`、`TemplateDetail`、`RuleTemplateVersion`；`TemplateSlot` 加 `kind`；`SlotConstraint` 加 `allowedDataTypes`；`DataType` 保留为 `ValueDataType` 别名
- `api/template.ts`：新增 `enableTemplate`/`listVersions`/`getVersion`；`publishTemplate`/`disableTemplate`/`enableTemplate` 加 `actorId` 参数；`getTemplate` 返回 `TemplateDetail`
- `constants/api-endpoints.ts`：新增 `TEMPLATE_ENABLE`/`TEMPLATE_VERSIONS`
- `pages/template-instantiate`：`TemplateDetail` 解包（`tmpl.slots`→`tmpl.version.slots`）；REF slot 文本输入占位
- `pages/template-editor`：`TemplateDetail` 解包；slot 构造加 `kind: 'VALUE'`；`DataType`→`ValueDataType`
- `pages/template-list`：新增 `enable` 按钮（DISABLED 行）；publish/disable 补 `actorId`
- `introspect.ts`：`DataType`→`ValueDataType`

## Global Constraints

- **后端 V2 已落地**；不再"后端 0 改动"；前端需对齐 `TemplateDetail` / `SlotKind` / `enable` / `actorId` 等契约变更。
- **能复用就复用,不重造**:body 编辑走 `RuleBodyEditor`/`FlowCanvasEditor`;kind 下拉走 `getRuleKindOptions(t)`;payload 提取走抽共享的 `extractPayloadSchema`;值输入 `SlotValueInput`;补全 `expressionCompletions`。新增仅限"确无可复用"的原语。
- **单一真相源**:脚本参数 = `script.params`(喂补全/默认/slot 候选);kind 选项 = `getRuleKindOptions`;可参数化位置 = `introspectPositions`(AST/Flow)+ 参数表(Script)。
- **用户不接触 JsonPointer**:位置靠"选/勾"派生,pointer 藏在 binding 背后。
- **载体一致**:凡构造/改写/序列化脚本载体处,一律 `{source,lang,params}` 三件齐全,不得只带 `{source,lang}`(L0 钉死)。
- `editableParams`:模板 true(可编辑参数表)/ 规则 false(只读 round-trip)。
- Flow 本轮**仅结构参数化**(ruleCode/decisionCode/caseKeys);flow 表达式 `params.x` 常量 UI 不做(后端已 ready)。
- dataType 用 `types/template.ts` 的 `DataType`(8 值);slot 无 defaultValue(默认=skeleton 位置值)。
- 每 Task 收尾 `npm test`(有测试时)+ `npx tsc -b` 干净;纯逻辑必写 vitest,组件写渲染+关键行为 smoke,编排靠 L4 手验;i18n key 用到处补齐(en+zh-CN+types.ts)。
- 匹配现有 React/AntD/i18n 风格,不引新依赖(vitest 基座已就绪)。

## 复用锚点(实现前先读真实代码,不臆造签名)

| 复用物 | 位置 | 关键事实 |
|---|---|---|
| `RuleBodyEditor` | `pages/rule-editor/RuleBodyEditor.tsx` | prop 驱动、不读 store;props `{kind,ast,script,onAstChange,onScriptChange,conditionTypes,availableMetrics,payloadFieldNames,payloadFieldTypes,decisions,tenantId,sceneCode}`;覆盖非 flow 5 kind;line 66-78 渲染 ScriptEditor(**当前固定 props,不转发 editableParams**——L2 要改) |
| `FlowCanvasEditor` | `pages/rule-editor/FlowCanvasEditor.tsx` | flow 画布;接线范例见 `CenterPanel.tsx` |
| `CenterPanel` | `pages/rule-editor/CenterPanel.tsx` | RuleBodyEditor/FlowCanvasEditor 接线 + metadata(SceneMetadata)/decisions(listDecisions)来源范例 |
| `ScriptEditor` | `pages/rule-editor/ScriptEditor.tsx` | CodeMirror;props `{script,onChange,availableMetrics,payloadFieldNames,payloadFieldTypes,tenantId,sceneCode}`;**line 113-118 updateListener 每次 onChange 只带 {lang,source}**(L0 要修);completeFn line 73 调 expressionCompletions |
| `expressionCompletions` | `pages/rule-editor/expressionCompletions.ts` | metrics/payload/subject namespace 补全 |
| `getRuleKindOptions(t)` | `constants/enums.ts:64` | 全 6 kind + `enum.kind.*` i18n(rule ns);rule-list/rules-all 已用 |
| `getSceneMetadata` | **`@/api/metadata`**(非 scene) | `getScene` 在 `@/api/scene` |
| `extractPayloadSchema` | `pages/rule-editor/index.tsx:27-46`(**局部未导出**) | payload 字段提取;参照场景需抽共享(L1) |
| `RightPanel` | `pages/rule-editor/RightPanel.tsx` | 切脚本 lang 处 `setScript({lang,source})` **丢 params**(L0 要修) |
| 类型 | `types/rule.ts` / `types/template.ts` | `RuleBody`/`BodyCarriers`/`carriersToBody`;`TemplateSlot{kind,dataType?,required,constraint?}`/`SlotBinding`/`JsonPointerTarget`/`ValueDataType`；**注：已对齐 V2**——`TemplateSlot` 加 `kind:SlotKind`、`dataType` 改为 `ValueDataType?`；新增 `SlotKind`/`TemplateDetail`/`RuleTemplateVersion`；`DataType` 保留为 `ValueDataType` 别名 |
| 实例化页 | `pages/template-instantiate/` | 现用 DatePicker(dayjs)+constraint+enum Select;**本轮不动**(见 L1 SlotValueInput 说明) |

---

## L0 — 载体一致性:params 贯穿脚本载体全链路

### Task 1: 脚本载体处处带 params(round-trip 唯一正确性关注点)

**架构意图:** round-trip 丢 params 不是三个孤立 bug,是"脚本载体 = `{source,lang,params}`"这一事实**未贯穿所有构造/改写/序列化点**。本 Task 一次性把全链路对齐,用一个 round-trip 测试锁死;后续任何新构造点都须遵此。

**Files:**
- Modify: `types/rule.ts`(已起改:`ScriptParams`;`ScriptBody.script`/`BodyCarriers.script` 带 `params?`;`carriersToBody`/`bodyToCarriers` 透传)
- Modify: `store/ruleStore.ts`(已起改:5 处 script 类型带 `params?`)
- Modify: `pages/rule-editor/ScriptEditor.tsx`(**updateListener 主泄漏点**:line 113-118 每次编辑 `onChange({lang,source})` 丢 params;闭包在空依赖 init effect,须用 `paramsRef` 随 `script?.params` 同步,onChange 补 `params: paramsRef.current`)
- Modify: `pages/rule-editor/RightPanel.tsx`(切 lang 处 `setScript({lang,source})` 补 `params: script?.params`)

**Interfaces:**
- Produces: 脚本载体在 types/store/编辑器 onChange/lang 切换/序列化各点均 `{source,lang,params}` 保真。

- [ ] **Step 1: 审全部脚本载体构造/改写点**

```bash
cd frontend && grep -rn "setScript(\|onScriptChange(\|{ source\|source:\|carriersToBody\|ScriptBody" src --include="*.ts" --include="*.tsx" | grep -iv "params"
```
逐一确认:凡构造 `{source,lang}` 的地方都要带上 `params`。已知点:ScriptEditor updateListener、RightPanel lang 切换、carriersToBody。若 grep 出更多(如别处 setScript),一并修。

- [ ] **Step 2: 写 round-trip vitest(锁死)**

`pages/rule-editor/ScriptEditor.test.tsx` 或 `types/rule.test.ts`:
- `bodyToCarriers({type:'ScriptBody',script:{source:'x',lang:'CEL',params:{a:1}}})` → carriers.script.params = {a:1}。
- `carriersToBody('EXPRESSION_SCRIPT', {script:{source:'x',lang:'CEL',params:{a:1}}})` → body.script.params = {a:1}。
- (若可)ScriptEditor 组件测:挂载带 params 的 script → 模拟源码编辑触发 onChange → 断言回调对象**仍含原 params**(覆盖 updateListener 修复)。

- [ ] **Step 3: 修 ScriptEditor updateListener + RightPanel**

按上述:ScriptEditor 加 `const paramsRef = useRef(script?.params); useEffect(() => { paramsRef.current = script?.params; }, [script?.params]);`,updateListener 内 `onChange({ lang: langRef.current, source: ..., params: paramsRef.current })`。RightPanel lang 切换补 params。

- [ ] **Step 4: 验证 + Commit**

```bash
cd frontend && npm test -- ScriptEditor rule && npx tsc -b
git add frontend/src/types/rule.ts frontend/src/store/ruleStore.ts frontend/src/pages/rule-editor/ScriptEditor.tsx frontend/src/pages/rule-editor/RightPanel.tsx frontend/src/pages/rule-editor/*.test.tsx frontend/src/types/*.test.ts
git commit -m "fix(frontend): 脚本载体 params 贯穿全链路(types/store/ScriptEditor updateListener/RightPanel lang 切换)+ round-trip 测试"
```

---

## L1 — 可复用原语

### Task 2: expressionCompletions 加 params 命名空间

**架构意图:** `params` 是第 4 个补全命名空间,与 metrics/payload/subject 平权,接进现有机制,不另造。

**Files:** Modify `pages/rule-editor/expressionCompletions.ts`(+ 调用点)

**Interfaces:**
- `expressionCompletions(ctx, metrics, payloadFields, payloadTypes, paramKeys: string[])` —— 顶层含 `params`;`params.` 补全 paramKeys。

- [ ] **Step 1: 改函数**

顶层 builtins 加 `{ label:'params', type:'namespace', detail:'模板常量', info:'本规则冻结常量(params.<键>)' }`;命名空间正则扩为 `/(?:metrics|payload|subject|params)\.(\w*)/`;加 params 分支返回 `paramKeys.filter(k=>k.startsWith(partial)).map(k=>({label:k,type:'property',detail:'常量'}))`;签名末位加 `paramKeys: string[]`。

- [ ] **Step 2: 调用点先传 `[]`(真源留给 L2)**

`grep -rn "expressionCompletions(" src`:`ScriptEditor.tsx:73`、`ExpressionInput.tsx:55` 两处。**本轮都传 `[]`**——ScriptEditor 参数表在 L2(Task 6)才有,此刻无真源,传真键会 tsc 红。

- [ ] **Step 3: vitest**

`expressionCompletions.test.ts`:顶层 `par` 建议含 params;`params.th` + paramKeys=['threshold'] → 含 threshold;metrics./payload. 回归不变。构造最小 `CompletionContext`(手搓 `matchBefore` stub)。

- [ ] **Step 4: 验证 + Commit**

```bash
cd frontend && npm test -- expressionCompletions && npx tsc -b
git add frontend/src/pages/rule-editor/expressionCompletions.ts frontend/src/pages/rule-editor/expressionCompletions.test.ts frontend/src/pages/rule-editor/ScriptEditor.tsx frontend/src/pages/rule-editor/ExpressionInput.tsx
git commit -m "feat(frontend): expressionCompletions 加 params 命名空间(paramKeys 入参,调用点先传空)+ vitest"
```

### Task 3: SlotValueInput(DataType→primitive 值输入,仅供参数表)

**架构意图:** 一个按 `DataType` 渲染 primitive 值输入的小原语,供脚本参数表默认值格。**不重构实例化页**——实例化用 DatePicker(dayjs)+constraint+enum Select+`valuePropName=checked`,值表示(dayjs)与参数表(primitive)天然冲突,强行共用会丢 constraint/DATE 崩/checked 错位。DRY 让步给安全,实例化页原样不动;真要统一另立任务。

**Files:** Create `components/SlotValueInput.tsx` + `.test.tsx`

**Interfaces:** `<SlotValueInput dataType value onChange disabled? />`,值为 primitive(number/string/boolean/string[]),DATE/DATETIME 存 ISO 字符串。

- [ ] **Step 1: 写组件**

```tsx
// frontend/src/components/SlotValueInput.tsx
import { InputNumber, Input, Switch, Select } from 'antd';
import type { DataType } from '@/types/template';

interface Props { dataType: DataType; value?: unknown; onChange?: (v: unknown) => void; disabled?: boolean; }

/** 按 DataType 渲染 primitive 值输入(脚本参数表默认值格)。 */
export default function SlotValueInput({ dataType, value, onChange, disabled }: Props) {
  switch (dataType) {
    case 'LONG': case 'DOUBLE': case 'DECIMAL':
      return <InputNumber style={{ width: '100%' }} value={value as number} onChange={onChange} disabled={disabled}
        stringMode={dataType === 'DECIMAL'} precision={dataType === 'LONG' ? 0 : undefined} />;
    case 'BOOLEAN':
      return <Switch checked={!!value} onChange={onChange} disabled={disabled} />;
    case 'LIST':
      return <Select mode="tags" style={{ width: '100%' }} value={(value as string[]) ?? []} onChange={onChange} disabled={disabled} open={false} placeholder="回车分隔" />;
    case 'DATE': case 'DATETIME':
      return <Input value={value as string} onChange={(e) => onChange?.(e.target.value)} disabled={disabled} placeholder={dataType === 'DATE' ? 'YYYY-MM-DD' : 'ISO-8601'} />;
    case 'STRING': default:
      return <Input value={value as string} onChange={(e) => onChange?.(e.target.value)} disabled={disabled} />;
  }
}
```

- [ ] **Step 2: vitest**

`SlotValueInput.test.tsx`:BOOLEAN→Switch+onChange、LONG→数值输入、STRING→文本、LIST→tags,各分支渲染 + 一个 onChange 行为。

- [ ] **Step 3: 验证 + Commit**

```bash
cd frontend && npm test -- SlotValueInput && npx tsc -b
git add frontend/src/components/SlotValueInput.tsx frontend/src/components/SlotValueInput.test.tsx
git commit -m "feat(frontend): SlotValueInput 按 DataType 渲染 primitive 值输入(供脚本参数表)+ vitest"
```

### Task 4: introspectPositions(可参数化位置的单一权威 —— AST/Flow)

**架构意图:** "哪些位置可参数化、怎么寻址(JsonPointer)、标签、推断类型"由此一处产出。AST/Flow 结构位置走它;Script 位置走参数表(不走本函数)。创作期便利,非运行时机制。

**Files:** `pages/template-editor/introspect.ts`（已有骨架——V2 对齐时改过 `DataType→ValueDataType`，代码与下方 Step 2 基本一致）+ **缺** `introspect.test.ts`（vitest 未写）

**Interfaces:** `interface Candidate { jsonPointer; label; currentValue; dataType }`;`introspectPositions(kind: RuleKind, body: RuleBody): Candidate[]`。

- [ ] **Step 1: vitest(纯逻辑,最高价值,先写)**

`introspect.test.ts` 覆盖:AndNode 深层 ConditionNode.params → `/conditionAst/children/0/params/threshold`(LONG);weight → `/conditionAst/children/0/weight`;IfNode.condition 深层;决策表 Row cell → `/conditionAst/rows/0/conditions/0`;Scorecard threshold → `/conditionAst/threshold` + conditions weight;Flow RuleRefNode→`/flowGraph/nodes/0/ruleCode`、OutputNode→decisionCode、SwitchNode→caseKeys(LIST);inferType 各分支。pointer 字段名须与后端 `JsonPointerBinderTest` 逐字一致。

- [ ] **Step 2: 实现**

```typescript
// frontend/src/pages/template-editor/introspect.ts
import type { RuleBody, RuleKind } from '@/types';
import type { DataType } from '@/types/template';

export interface Candidate { jsonPointer: string; label: string; currentValue: unknown; dataType: DataType; }

function inferType(v: unknown): DataType {
  if (typeof v === 'boolean') return 'BOOLEAN';
  if (typeof v === 'number') return Number.isInteger(v) ? 'LONG' : 'DOUBLE';
  if (Array.isArray(v)) return 'LIST';
  return 'STRING';
}

/** 可参数化位置候选。AST 走值位(params/weight/scorecard threshold/决策表 cell);Flow 仅结构字段;Script 不走本函数(由参数表管)。 */
export function introspectPositions(_kind: RuleKind, body: RuleBody): Candidate[] {
  const out: Candidate[] = [];
  if (body.type === 'AstBody' && body.conditionAst) walkAst(body.conditionAst, '/conditionAst', out);
  else if (body.type === 'FlowBody') walkFlowStructural(body.flowGraph, out);
  return out;
}

function walkAst(node: any, ptr: string, out: Candidate[]): void {
  if (!node || typeof node !== 'object') return;
  switch (node.type) {
    case 'ConditionNode':
      Object.entries(node.params ?? {}).forEach(([k, v]) => out.push({ jsonPointer: `${ptr}/params/${k}`, label: `${node.metricCode ?? ''} ${node.conditionType ?? ''} › ${k}`.trim(), currentValue: v, dataType: inferType(v) }));
      if (node.weight != null) out.push({ jsonPointer: `${ptr}/weight`, label: `${node.metricCode ?? ''} › 权重`, currentValue: node.weight, dataType: 'DOUBLE' });
      break;
    case 'AndNode': case 'OrNode': case 'XorNode':
      (node.children ?? []).forEach((c: any, i: number) => walkAst(c, `${ptr}/children/${i}`, out)); break;
    case 'NotNode': walkAst(node.child, `${ptr}/child`, out); break;
    case 'IfNode':
      walkAst(node.condition, `${ptr}/condition`, out); walkAst(node.thenBranch, `${ptr}/thenBranch`, out);
      if (node.elseBranch) walkAst(node.elseBranch, `${ptr}/elseBranch`, out); break;
    case 'ScorecardRootNode':
      if (node.threshold != null) out.push({ jsonPointer: `${ptr}/threshold`, label: '评分卡 › 阈值', currentValue: node.threshold, dataType: inferType(node.threshold) });
      (node.conditions ?? []).forEach((c: any, i: number) => walkAst(c, `${ptr}/conditions/${i}`, out)); break;
    case 'DecisionTableNode':
      (node.rows ?? []).forEach((row: any, ri: number) => (row.conditions ?? []).forEach((cell: unknown, ci: number) =>
        out.push({ jsonPointer: `${ptr}/rows/${ri}/conditions/${ci}`, label: `决策表 › 行${ri + 1} › 列${ci + 1}`, currentValue: cell, dataType: inferType(cell) }))); break;
  }
}

function walkFlowStructural(graph: any, out: Candidate[]): void {
  (graph?.nodes ?? []).forEach((n: any, i: number) => {
    if (n.type === 'RuleRefNode') out.push({ jsonPointer: `/flowGraph/nodes/${i}/ruleCode`, label: `节点 ${n.id} › 被引规则`, currentValue: n.ruleCode, dataType: 'STRING' });
    if (n.type === 'OutputNode') out.push({ jsonPointer: `/flowGraph/nodes/${i}/decisionCode`, label: `节点 ${n.id} › 决策`, currentValue: n.decisionCode, dataType: 'STRING' });
    if (n.type === 'SwitchNode') out.push({ jsonPointer: `/flowGraph/nodes/${i}/caseKeys`, label: `节点 ${n.id} › 分支键`, currentValue: n.caseKeys, dataType: 'LIST' });
  });
}
```
(先读 `types/ast.ts`/`types/flow.ts` 核对字段名与 ScorecardRootNode.threshold 是否存在——建默认骨架处 `{type:'ScorecardRootNode',conditions:[],threshold:0}` 佐证有 threshold。)

- [ ] **Step 3: 验证 + Commit**

```bash
cd frontend && npm test -- introspect && npx tsc -b
git add frontend/src/pages/template-editor/introspect.ts frontend/src/pages/template-editor/introspect.test.ts
git commit -m "feat(frontend): introspectPositions 位置内省器(AST 值位/weight/scorecard 阈值/决策表 cell + Flow 结构)+ vitest"
```

### Task 5: 抽共享 extractPayloadSchema

**架构意图:** 参照场景的 payload 补全依赖 `rule-editor/index.tsx` 的局部 `extractPayloadSchema`。抽成共享导出,规则编辑器与模板编辑器共用,不复制。

**Files:** Create `utils/payloadSchema.ts`(或并入 `@/api/metadata`);Modify `pages/rule-editor/index.tsx`(改 import 共享)

- [ ] **Step 1:** 把 `rule-editor/index.tsx:27-46` 的 `extractPayloadSchema` 移到 `utils/payloadSchema.ts` 导出,rule-editor 改为 import。行为不变。
- [ ] **Step 2: 验证 + Commit**

```bash
cd frontend && npx tsc -b
git add frontend/src/utils/payloadSchema.ts frontend/src/pages/rule-editor/index.tsx
git commit -m "refactor(frontend): 抽共享 extractPayloadSchema 供模板参照场景复用"
```

---

## L2 — 授权组件:ScriptEditor 参数表 + RuleBodyEditor 门面透传

### Task 6: ScriptEditor 参数表 + editableParams + onParamSlotToggle;RuleBodyEditor 透传

**架构意图:** ScriptEditor 是脚本授权面。参数表以 `script.params` 为单一真相源(喂补全/默认/slot 候选)。`editableParams` 分流模板(可编辑)/规则(只读)。`onParamSlotToggle` 是参数表↔模板 slots/bindings 的接线契约。**RuleBodyEditor 作为唯一 body-editor 门面,增两个 optional 透传 prop**(默认 undefined → 规则编辑器零影响),模板编辑器统一经它驱动 script,不特判、不绕过。

**Files:** Modify `pages/rule-editor/ScriptEditor.tsx`、`pages/rule-editor/RuleBodyEditor.tsx`

**Interfaces:**
- ScriptEditor 加 props:`editableParams?: boolean`(默认 false)、`onParamSlotToggle?: (key: string, enabled: boolean, dataType: DataType) => void`。
- RuleBodyEditor 加同名两 optional props,渲染 ScriptEditor 时**转发**(line 66-78)。
- 补全 paramKeys 源改为 `Object.keys(script?.params ?? {})`(接 Task 2)。

- [ ] **Step 1: ScriptEditor 参数表**

源码框下方加 params 表:
- 只读(editableParams=false):`Descriptions`/`List` 展示 `Object.entries(script.params)` 每项 `名 = 值`。
- 可编辑(true):表格行 = 参数名(Input)/类型(Select DataType)/默认值(`SlotValueInput`)/`参数化`开关/🗑;底部 `+ 添加参数`。增删改都构造新 params → `onChange({source,lang,params:next})`(注意与 L0 updateListener 一致:改 params 也走带 params 的 onChange)。类型不入 body(script.params 只存值),编辑期用局部 `Record<string,DataType>` state 记类型(或从值推断)。
- `参数化`开关:`onChange` 时调 `onParamSlotToggle?.(key, enabled, dataType)`;`editableParams=false` 或未传 callback 时该列不渲染。
- completeFn 传 `Object.keys(script?.params ?? {})`。

- [ ] **Step 2: RuleBodyEditor 透传**

Props 加 `editableParams?`、`onParamSlotToggle?`;line 66-78 渲染 `<ScriptEditor ... editableParams={editableParams} onParamSlotToggle={onParamSlotToggle} />`。CenterPanel(规则编辑器)不传 → 规则侧只读、无参数化列(零影响)。

- [ ] **Step 3: vitest(组件行为)**

`ScriptEditor.test.tsx` 补:editableParams=true 时渲染参数表 + `+ 添加参数` 增行 → onChange 带新 params;`参数化`开关点击 → `onParamSlotToggle` 被调;editableParams=false 时无增删改控件。

- [ ] **Step 4: 验证 + Commit**

```bash
cd frontend && npm test -- ScriptEditor && npx tsc -b
git add frontend/src/pages/rule-editor/ScriptEditor.tsx frontend/src/pages/rule-editor/RuleBodyEditor.tsx frontend/src/pages/rule-editor/ScriptEditor.test.tsx
git commit -m "feat(frontend): ScriptEditor 参数表+editableParams+onParamSlotToggle;RuleBodyEditor 门面透传"
```

---

## L3 — 编排组合:模板创建 + 模板编辑器

### Task 7: 模板创建入口(template-list)全 6 kind + Script/Flow 骨架

**架构意图:** 创建入口 kind 复用 `getRuleKindOptions`(与 rule 单一真相源),骨架播种补齐 Script/Flow。

**Files:** `pages/template-list/index.tsx`（已有占位代码——V2 对齐时改了 type imports + 加 enable 按钮 + 补 actorId，但创建弹窗/骨架播种仍是旧逻辑。按本 task 设计重写，不修补旧骨架）

- [ ] **Step 1:** 创建弹窗 kind `Select` 改 `options={getRuleKindOptions(t)}`(删自造 `AST_KINDS`);`enum.kind.*` 在 rule ns,用 `useTranslation(['template','rule'])` 并 `t('rule:enum.kind.X')` 解析(或传能解析该 key 的 t;勿在 template ns 重复定义)。
- [ ] **Step 2:** `handleCreate` 骨架播种加两分支:
  - `EXPRESSION_SCRIPT` → `{ type:'ScriptBody', script:{ source:'', lang:'CEL', params:{} } }`
  - `DECISION_FLOW` → `{ type:'FlowBody', flowGraph:{ nodes:[], edges:[], inputNodeId:'' }, referencedSnapshots:{} }`(**不加 `params`**——前端 FlowGraph 类型无此字段,flow params UI 本轮不做)
  AST 四种维持;`else` 兜底 AndNode。
- [ ] **Step 3: 验证 + Commit**

```bash
cd frontend && npx tsc -b
git add frontend/src/pages/template-list/index.tsx
git commit -m "feat(frontend): 模板创建入口 kind 复用 getRuleKindOptions 全 6 种 + Script/Flow 骨架播种"
```

### Task 8: 模板编辑器重写(复用 body 编辑器 + 参照场景 + slot 声明面板)

**架构意图:** 模板 = skeleton(复用 body 编辑器搭)+ slot 声明覆盖层。skeleton 经 `RuleBodyEditor`/`FlowCanvasEditor`(与规则编辑器同一套,经 L2 门面支持脚本参数表可编辑)。slot 声明统一汇入模板编辑器的 `slots`/`bindings` state,两个入口:AST/Flow 走 `introspectPositions` 位置选择器,Script 走参数表 `onParamSlotToggle`。用户全程不见 JsonPointer。

**Files:** `pages/template-editor/index.tsx`（已有 ~17KB placeholder——V2 对齐时改了 `TemplateDetail` 解包 + `ValueDataType` + slot `kind`。**按本 task 设计重写，不修补旧骨架。旧的手填 jsonPointer 表单、`addBinding` 手打逻辑全部删除**）

- [ ] **Step 1: skeleton 复用 body 编辑器 + 参照场景 + kind 复用**

- kind Select 改 `getRuleKindOptions(t)`(删自造 AST_KINDS;编辑态通常 disabled)。
- 顶部加**参照场景 Select**(tenant 场景列表,`refSceneCode` state);选后 `getSceneMetadata(tenantId, refSceneCode)`(`@/api/metadata`)+ `getScene` + 共享 `extractPayloadSchema` 得 `availableMetrics/payloadFieldNames/payloadFieldTypes/conditionTypes`,`listDecisions` 得 decisions(接线照 CenterPanel)。
- bodySkeleton 从 JSON 文本框改为:`bodyToCarriers(bodySkeleton)` → 非 flow 用 `RuleBodyEditor`(传 `editableParams` + `onParamSlotToggle`)、flow 用 `FlowCanvasEditor`;`onChange` 收回 `carriersToBody(kind, carriers)`。保存直接用受控 state(不再 JSON.parse)。

- [ ] **Step 2: slot 声明面板(两入口,统一 state)**

- **AST/Flow**:`+ 参数化` 按钮 → 可搜索 Select,options=`introspectPositions(kind, bodySkeleton)`(排除已绑 pointer)→ 选中即 push binding `{slotKey, target:{type:'JsonPointerTarget', jsonPointer:c.jsonPointer}}` + 预填 slot `{key,label:c.label,dataType:c.dataType,required:false}`(可改)。slotKey 取 pointer 末段/派生去重。
- **Script**:不走位置选择器(introspect 对 ScriptBody 返回 `[]`);由 RuleBodyEditor→ScriptEditor 的参数表 `参数化`开关经 `onParamSlotToggle(key,enabled,dataType)` 回调 → 同样 push/remove 模板编辑器的 slot+binding(`/script/params/<key>`)。
- slots/bindings 列表:展示 **slot 的 label**(不显示 pointer);🗑 删 slot 连带 binding(及脚本侧回填参数表开关态)。
- 移除旧的手填 jsonPointer 表单 + `addBinding` 手打逻辑;slots 结构化表单(constraint 编辑)保留。

- [ ] **Step 3: 验证 + Commit**

```bash
cd frontend && npx tsc -b
git add frontend/src/pages/template-editor/index.tsx frontend/src/i18n/
git commit -m "feat(frontend): 模板编辑器重写——复用 RuleBodyEditor/FlowCanvasEditor 搭 skeleton + 参照场景 + slot 声明面板(AST/Flow 选位置、Script 参数表开关,不暴露 JsonPointer)"
```

---

## L4 — 收尾

### Task 9: i18n + 全量 tsc/vitest + 手动 e2e

**Files:** Modify i18n locales(en/zh-CN/types.ts)

- [ ] **Step 1: i18n 补齐**

grep 本轮新增 `t('...')`;补 en/zh-CN + types.ts。特别:**`enum.dataType.DECIMAL` 在 rule ns 缺失**(`en/rule.ts`/`getDataTypeOptions` 只 7 种)——参数表类型下拉在 rule ns 渲染 DECIMAL 会显 raw key,须补。参照场景/参数化/参数表相关 key 一并补。

- [ ] **Step 2: 全量**

```bash
cd frontend && npm test && npx tsc -b
```
全绿。

- [ ] **Step 3: 手动 e2e(前端验收门)**

起前后端,核对:
0. 创建弹窗 kind 下拉全 6 种;选 Script/Flow 建出 ScriptBody/FlowBody 骨架(非兜底 AndNode)。
1. 建 Script 模板:选参照场景 → 写 `metrics.x > params.threshold`(`metrics.`/`params.` 补全出现)→ 参数表加 threshold=100 → `参数化`开关生成 slot+binding(界面不见 pointer)→ 保存 → 发布。
2. 实例化填 threshold=500 → 查 `rule_version.body.script.params.threshold=500`。
3. 该实例化规则在**规则编辑器**打开:参数表只读显 500;**改一行源码保存后 params 不丢**(L0 覆盖的主路径);切脚本语言后 params 不丢。
4. 建 AST 模板:条件树填阈值 → `+ 参数化` 选"条件1 › 阈值"/"权重"/评分卡"阈值" → slot+binding 生成。
5. 建 Flow 模板:`+ 参数化` 可选 RuleRefNode"被引规则"/OutputNode"决策"。

- [ ] **Step 4: Commit**

```bash
git add frontend/src/i18n/
git commit -m "chore(frontend): 模板编辑器授权 UX i18n 补齐(含 rule ns DECIMAL)+ 全量 tsc/vitest 绿"
```

---

## 依赖顺序

```
L0 Task1 ─┐
L1 Task2 ─┤(并行)
   Task3 ─┤
   Task4 ─┤
   Task5 ─┘
            └─→ L2 Task6(依赖 1/2/3)
                     └─→ L3 Task7(独立,可与 6 并行)
                         L3 Task8(依赖 4/5/6/7)
                              └─→ L4 Task9
```

## 自检(spec 覆盖 + review findings 归零)

- spec §1 Scope:AST(T4/T8)+Script(T1/T2/T3/T6/T8)+Flow 结构(T4/T8)+SlotValueInput(T3)+参照场景(T5/T8)+round-trip(T1) ✓
- spec §3 不暴露 pointer(T8)✓;§4 参数表单一真相源(T6)✓;§7 参照场景 A(T5/T8)✓;§8 editableParams(T6)✓;§9 组件清单全覆盖 ✓;§10 不做项均未含 ✓
- **Review Blocker/Important 全归零**:① ScriptEditor updateListener 主泄漏(T1 paramsRef)✓ ② RuleBodyEditor 透传(T6 门面)✓ ③ SlotValueInput 缩范围不碰实例化(T3)✓ ④ Script 走参数表非 introspect(T4/T8 澄清)✓ ⑤ flow 骨架去 params(T7)✓ ⑥ getSceneMetadata@metadata/extractPayloadSchema 抽共享(锚点表/T5)✓ ⑦ DECIMAL i18n(T9)✓ ⑧ weight/scorecard 阈值候选(T4)✓

## 实际执行记录（2026-07-25 收尾）

L0-L4 全部 9 个 task 完成。大部分 task（1/2/3/5/6/7/9）为预存 commit；本次新增：
- Task 4：introspectPositions vitest 21 tests
- Task 8：参照场景选择器 + publish 按钮
- V2 对齐：类型/API/页面适配后端模板系统 V2（TemplateDetail/SlotKind/enable/actorId/模板市场/规则列表隔离/DryRun eventType/实例化表单）
- e2e 验证：6/6 场景通过，发现并修复 JsonPointerBinder 的 @Select→LambdaQueryWrapper 问题
