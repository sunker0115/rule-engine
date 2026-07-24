import type { RuleBody, RuleKind } from '@/types';
import type { DataType } from '@/types/template';

/** 可参数化位置候选：JsonPointer 寻址 + 展示标签 + 当前值 + 推断类型。 */
export interface Candidate {
  jsonPointer: string;
  label: string;
  currentValue: unknown;
  dataType: DataType;
}

/** 按 JS 运行时值推断槽位 dataType（整数→LONG / 小数→DOUBLE / 布尔→BOOLEAN / 数组→LIST / 其余→STRING）。 */
function inferType(v: unknown): DataType {
  if (typeof v === 'boolean') return 'BOOLEAN';
  if (typeof v === 'number') return Number.isInteger(v) ? 'LONG' : 'DOUBLE';
  if (Array.isArray(v)) return 'LIST';
  return 'STRING';
}

/**
 * 可参数化位置的单一权威：给定 kind 与 body，产出全部可绑定槽位候选。
 * AST 走值位（ConditionNode.params / weight / 评分卡阈值 / 决策表 cell）；
 * Flow 仅结构字段（被引规则码 / 决策码 / 分支键）；Script 位置不走本函数（由参数表管理）。
 * @param _kind 规则类型（当前判别以 body.type 为准，保留形参对齐调用契约）
 * @param body 规则判定主体多态载体
 * @returns 候选位置数组，顺序即遍历顺序
 */
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
      Object.entries(node.params ?? {}).forEach(([k, v]) =>
        out.push({
          jsonPointer: `${ptr}/params/${k}`,
          label: `${node.metricCode ?? ''} ${node.conditionType ?? ''} › ${k}`.trim(),
          currentValue: v,
          dataType: inferType(v),
        }));
      if (node.weight != null) {
        out.push({
          jsonPointer: `${ptr}/weight`,
          label: `${node.metricCode ?? ''} › 权重`.trim(),
          currentValue: node.weight,
          dataType: 'DOUBLE',
        });
      }
      break;
    case 'AndNode':
    case 'OrNode':
    case 'XorNode':
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
      if (node.threshold != null) {
        out.push({
          jsonPointer: `${ptr}/threshold`,
          label: '评分卡 › 阈值',
          currentValue: node.threshold,
          dataType: inferType(node.threshold),
        });
      }
      (node.conditions ?? []).forEach((c: any, i: number) => walkAst(c, `${ptr}/conditions/${i}`, out));
      break;
    case 'DecisionTableNode':
      (node.rows ?? []).forEach((row: any, ri: number) =>
        (row.conditions ?? []).forEach((cell: unknown, ci: number) =>
          out.push({
            jsonPointer: `${ptr}/rows/${ri}/conditions/${ci}`,
            label: `决策表 › 行${ri + 1} › 列${ci + 1}`,
            currentValue: cell,
            dataType: inferType(cell),
          })));
      break;
  }
}

function walkFlowStructural(graph: any, out: Candidate[]): void {
  (graph?.nodes ?? []).forEach((n: any, i: number) => {
    if (n.type === 'RuleRefNode') {
      out.push({ jsonPointer: `/flowGraph/nodes/${i}/ruleCode`, label: `节点 ${n.id} › 被引规则`, currentValue: n.ruleCode, dataType: 'STRING' });
    }
    if (n.type === 'OutputNode') {
      out.push({ jsonPointer: `/flowGraph/nodes/${i}/decisionCode`, label: `节点 ${n.id} › 决策`, currentValue: n.decisionCode, dataType: 'STRING' });
    }
    if (n.type === 'SwitchNode') {
      out.push({ jsonPointer: `/flowGraph/nodes/${i}/caseKeys`, label: `节点 ${n.id} › 分支键`, currentValue: n.caseKeys, dataType: 'LIST' });
    }
  });
}
