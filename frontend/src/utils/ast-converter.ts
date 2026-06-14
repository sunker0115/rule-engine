import type { AstNode, AndNode, OrNode, NotNode, ConditionNode } from '@/types';
import type { RuleGroupType, RuleType } from 'react-querybuilder';

/** 扩展 RuleType，携带 AST 中 ConditionNode 的额外字段 */
interface RuleTypeExt extends RuleType {
  _params?: Record<string, unknown>;
  _valueRef?: string;
}

/** params 缓存：QB handleOnChange 会重建 RuleType 丢 _params，靠此 cache 跨渲染保留 */
const paramsCache = new Map<string, Record<string, unknown>>();

/** 从缓存读取 params */
export function getParams(ruleId: string): Record<string, unknown> {
  return paramsCache.get(ruleId) ?? {};
}

/** 更新缓存中单个 param */
export function setParam(ruleId: string, key: string, value: unknown) {
  const existing = paramsCache.get(ruleId) ?? {};
  paramsCache.set(ruleId, { ...existing, [key]: value });
}

/** AST → react-querybuilder RuleGroupType */
export function astToQueryBuilder(ast: AstNode | null): RuleGroupType {
  if (!ast) {
    return { combinator: 'and', rules: [] };
  }

  switch (ast.type) {
    case 'AndNode':
      return {
        combinator: 'and',
        rules: (ast as AndNode).children.map(astToQueryBuilderRule),
      };
    case 'OrNode':
      return {
        combinator: 'or',
        rules: (ast as OrNode).children.map(astToQueryBuilderRule),
      };
    case 'NotNode': {
      const child = (ast as NotNode).child;
      return {
        combinator: 'and',
        rules: [astToQueryBuilderRule(child)],
        not: true,
      } as RuleGroupType;
    }
    case 'ConditionNode':
      return {
        combinator: 'and',
        rules: [astToQueryBuilderRule(ast)],
      };
    default:
      return { combinator: 'and', rules: [] };
  }
}

function astToQueryBuilderRule(node: AstNode): RuleType | RuleGroupType {
  switch (node.type) {
    case 'AndNode':
    case 'OrNode':
      return astToQueryBuilder(node);
    case 'NotNode':
      return astToQueryBuilder(node);
    case 'ConditionNode': {
      const c = node as ConditionNode;
      const id = crypto.randomUUID();
      if (c.params) paramsCache.set(id, { ...c.params });
      return {
        id,
        field: c.conditionType,
        operator: c.valueRef ?? 'METRIC',
        value: c.metricCode ?? '',
      } as RuleType;
    }
    default:
      return { field: 'unknown', operator: '=', value: '' } as unknown as RuleType;
  }
}

/** react-querybuilder RuleGroupType → AST */
export function queryBuilderToAst(group: RuleGroupType): AstNode {
  if (group.rules.length === 0) {
    return { type: 'AndNode', children: [] };
  }

  const combinator = group.combinator ?? 'and';

  if (group.not) {
    const childGroup: RuleGroupType = { combinator, rules: group.rules };
    const child = queryBuilderToAst(childGroup);
    return { type: 'NotNode', child };
  }

  const children: AstNode[] = group.rules.map((rule) => {
    if ('combinator' in rule) {
      return queryBuilderToAst(rule as RuleGroupType);
    }
    const r = rule as unknown as RuleTypeExt;
    const cached = r.id ? paramsCache.get(r.id) : undefined;
    const params = cached ?? r._params ?? {};
    return {
      type: 'ConditionNode',
      conditionType: r.field,
      params,
      metricCode: r.value || undefined,
      valueRef: (r.operator === 'PAYLOAD' ? 'PAYLOAD' : 'METRIC') as 'METRIC' | 'PAYLOAD',
    } satisfies ConditionNode;
  });

  if (combinator === 'and') return { type: 'AndNode', children } as AndNode;
  return { type: 'OrNode', children } as OrNode;
}
