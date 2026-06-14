import { describe, it, expect } from 'vitest';
import { astToQueryBuilder, queryBuilderToAst } from '../ast-converter';
import type { AstNode, AndNode, NotNode, ConditionNode } from '@/types';

describe('ast-converter roundtrip', () => {
  it('空 AST 往返', () => {
    const ast: AstNode = { type: 'AndNode', children: [] };
    const qb = astToQueryBuilder(ast);
    const result = queryBuilderToAst(qb);
    expect(result).toEqual(ast);
  });

  it('单 ConditionNode 往返', () => {
    // 注：单 ConditionNode → QB 必包裹为 AndNode（QB 无裸条件概念）
    const ast: ConditionNode = {
      type: 'ConditionNode',
      conditionType: 'GT',
      params: { threshold: 1000 },
      metricCode: 'user.trade.sum.7d',
      valueRef: 'METRIC',
    };
    const wrappedAst: AndNode = { type: 'AndNode', children: [ast] };
    const qb = astToQueryBuilder(wrappedAst);
    const result = queryBuilderToAst(qb);
    expect(result).toEqual(wrappedAst);
  });

  it('嵌套 AndNode + OrNode 往返', () => {
    const ast: AndNode = {
      type: 'AndNode',
      children: [
        {
          type: 'ConditionNode',
          conditionType: 'GT',
          params: { threshold: 1000 },
          metricCode: 'amount',
          valueRef: 'METRIC',
        },
        {
          type: 'OrNode',
          children: [
            {
              type: 'ConditionNode',
              conditionType: 'EQ',
              params: {},
              metricCode: 'country',
              valueRef: 'PAYLOAD',
            },
            {
              type: 'ConditionNode',
              conditionType: 'IN',
              params: { list: ['CN', 'HK'] },
              metricCode: 'region',
              valueRef: 'METRIC',
            },
          ],
        },
      ],
    };
    const qb = astToQueryBuilder(ast);
    const result = queryBuilderToAst(qb);
    expect(result).toEqual(ast);
  });

  it('NotNode 往返', () => {
    const ast: NotNode = {
      type: 'NotNode',
      child: {
        type: 'ConditionNode',
        conditionType: 'EQ',
        params: {},
        metricCode: 'blocked',
        valueRef: 'METRIC',
      },
    };
    const qb = astToQueryBuilder(ast);
    const result = queryBuilderToAst(qb);

    // NotNode 往返有损 —— queryBuilder 用 .not 标记在 group 上
    // 验证逻辑结构保留
    expect(result.type).toBe('NotNode');
    const notNode = result as NotNode;
    expect(notNode.child.type).toBe('AndNode');
    const inner = notNode.child as AndNode;
    expect(inner.children.length).toBe(1);
    expect(inner.children[0].type).toBe('ConditionNode');
    expect((inner.children[0] as ConditionNode).conditionType).toBe('EQ');
  });

  it('null AST 返回空 group', () => {
    const qb = astToQueryBuilder(null);
    expect(qb.combinator).toBe('and');
    expect(qb.rules).toEqual([]);
  });
});
