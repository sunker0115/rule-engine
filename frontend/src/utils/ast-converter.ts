import type { AstNode, AndNode, OrNode, NotNode, ConditionNode } from '@/types';
import type { RuleGroupType, RuleType } from 'react-querybuilder';

/** 保存 ConditionNode 的 params，供 QB↔AST 往返时恢复 */
const paramsCache = new Map<string, Record<string, unknown>>();

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
      const nodeId = crypto.randomUUID();
      if (c.params && Object.keys(c.params).length > 0) {
        paramsCache.set(nodeId, c.params);
      }
      return {
        id: nodeId,
        field: c.conditionType,
        operator: c.valueRef ?? 'METRIC',
        value: c.metricCode ?? '',
      } as unknown as RuleType;
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
    const r = rule as unknown as {
      id?: string;
      field: string;
      operator: string;
      value: string;
    };
    const params = (r.id && paramsCache.get(r.id)) || {};
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
