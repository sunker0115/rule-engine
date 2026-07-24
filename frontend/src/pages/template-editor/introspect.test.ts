import { describe, it, expect } from 'vitest';
import type { RuleBody } from '@/types';
import { introspectPositions, type Candidate } from './introspect';

/** 按 jsonPointer 取单个候选，断言唯一命中。 */
function at(cands: Candidate[], ptr: string): Candidate {
  const hit = cands.filter((c) => c.jsonPointer === ptr);
  expect(hit, `expected exactly one candidate at ${ptr}`).toHaveLength(1);
  return hit[0];
}

function ast(conditionAst: unknown): RuleBody {
  return { type: 'AstBody', conditionAst: conditionAst as never };
}

describe('introspectPositions — AST 值位', () => {
  it('AndNode 深层 ConditionNode.params → /conditionAst/children/0/params/threshold (LONG) + weight → /conditionAst/children/0/weight (DOUBLE)', () => {
    const body = ast({
      type: 'AndNode',
      children: [
        {
          type: 'ConditionNode',
          conditionType: 'GTE',
          metricCode: 'amount',
          params: { threshold: 100 },
          weight: 1.5,
        },
      ],
    });
    const cands = introspectPositions('AST_BOOLEAN', body);

    const th = at(cands, '/conditionAst/children/0/params/threshold');
    expect(th.currentValue).toBe(100);
    expect(th.dataType).toBe('LONG');
    expect(th.label).toBe('amount GTE › threshold');

    const w = at(cands, '/conditionAst/children/0/weight');
    expect(w.currentValue).toBe(1.5);
    expect(w.dataType).toBe('DOUBLE');
  });

  it('weight 为 0 也应产出候选（!= null 而非 falsy 判定）', () => {
    const body = ast({
      type: 'AndNode',
      children: [{ type: 'ConditionNode', conditionType: 'EQ', metricCode: 'flag', params: {}, weight: 0 }],
    });
    const cands = introspectPositions('AST_BOOLEAN', body);
    expect(at(cands, '/conditionAst/children/0/weight').currentValue).toBe(0);
  });

  it('OrNode / XorNode 同样递归 children', () => {
    const body = ast({
      type: 'OrNode',
      children: [
        {
          type: 'XorNode',
          children: [{ type: 'ConditionNode', conditionType: 'EQ', metricCode: 'm', params: { v: 'x' } }],
        },
      ],
    });
    const cands = introspectPositions('AST_BOOLEAN', body);
    expect(at(cands, '/conditionAst/children/0/children/0/params/v').dataType).toBe('STRING');
  });

  it('NotNode 递归 child', () => {
    const body = ast({
      type: 'NotNode',
      child: { type: 'ConditionNode', conditionType: 'EQ', metricCode: 'm', params: { v: true } },
    });
    const cands = introspectPositions('AST_BOOLEAN', body);
    expect(at(cands, '/conditionAst/child/params/v').dataType).toBe('BOOLEAN');
  });

  it('IfNode.condition 深层 + thenBranch + elseBranch', () => {
    const body = ast({
      type: 'IfNode',
      condition: {
        type: 'AndNode',
        children: [{ type: 'ConditionNode', conditionType: 'GTE', metricCode: 'score', params: { threshold: 60 } }],
      },
      thenBranch: { type: 'ConditionNode', conditionType: 'EQ', metricCode: 't', params: { v: 1 } },
      elseBranch: { type: 'ConditionNode', conditionType: 'EQ', metricCode: 'e', params: { v: 2 } },
    });
    const cands = introspectPositions('DECISION_TREE', body);
    expect(at(cands, '/conditionAst/condition/children/0/params/threshold').currentValue).toBe(60);
    expect(at(cands, '/conditionAst/thenBranch/params/v').currentValue).toBe(1);
    expect(at(cands, '/conditionAst/elseBranch/params/v').currentValue).toBe(2);
  });

  it('IfNode.elseBranch 为 null 时不产候选', () => {
    const body = ast({
      type: 'IfNode',
      condition: { type: 'ConditionNode', conditionType: 'GTE', metricCode: 's', params: { t: 1 } },
      thenBranch: { type: 'ConditionNode', conditionType: 'EQ', metricCode: 't', params: { v: 1 } },
      elseBranch: null,
    });
    const cands = introspectPositions('DECISION_TREE', body);
    expect(cands.some((c) => c.jsonPointer.startsWith('/conditionAst/elseBranch'))).toBe(false);
  });

  it('ScorecardRootNode threshold → /conditionAst/threshold + conditions weight', () => {
    const body = ast({
      type: 'ScorecardRootNode',
      threshold: 80,
      conditions: [{ type: 'ConditionNode', conditionType: 'GTE', metricCode: 'age', params: { min: 18 }, weight: 2.0 }],
    });
    const cands = introspectPositions('SCORECARD', body);
    const th = at(cands, '/conditionAst/threshold');
    expect(th.currentValue).toBe(80);
    expect(th.dataType).toBe('LONG');
    expect(th.label).toBe('评分卡 › 阈值');
    expect(at(cands, '/conditionAst/conditions/0/params/min').currentValue).toBe(18);
    expect(at(cands, '/conditionAst/conditions/0/weight').dataType).toBe('DOUBLE');
  });

  it('DecisionTable Row cell → /conditionAst/rows/0/conditions/0', () => {
    const body = ast({
      type: 'DecisionTableNode',
      columns: [{ metricCode: 'amount', operator: 'GTE', dataType: 'LONG' }],
      rows: [
        { conditions: [1000, 'HIGH'], decisionCode: 'REJECT' },
        { conditions: [50], decisionCode: 'PASS' },
      ],
    });
    const cands = introspectPositions('DECISION_TABLE', body);
    const c0 = at(cands, '/conditionAst/rows/0/conditions/0');
    expect(c0.currentValue).toBe(1000);
    expect(c0.dataType).toBe('LONG');
    expect(c0.label).toBe('决策表 › 行1 › 列1');
    expect(at(cands, '/conditionAst/rows/0/conditions/1').dataType).toBe('STRING');
    expect(at(cands, '/conditionAst/rows/1/conditions/0').currentValue).toBe(50);
  });

  it('AstBody 无 conditionAst 时返回空', () => {
    expect(introspectPositions('AST_BOOLEAN', { type: 'AstBody', conditionAst: null })).toEqual([]);
  });
});

describe('introspectPositions — Flow 结构字段', () => {
  const flow = (nodes: unknown[]): RuleBody => ({
    type: 'FlowBody',
    flowGraph: { nodes: nodes as never, edges: [], inputNodeId: 'n0' },
  });

  it('RuleRefNode → /flowGraph/nodes/0/ruleCode (STRING)', () => {
    const cands = introspectPositions('DECISION_FLOW', flow([{ type: 'RuleRefNode', id: 'n0', ruleCode: 'RULE_A' }]));
    const c = at(cands, '/flowGraph/nodes/0/ruleCode');
    expect(c.currentValue).toBe('RULE_A');
    expect(c.dataType).toBe('STRING');
  });

  it('OutputNode → /flowGraph/nodes/1/decisionCode (STRING)', () => {
    const cands = introspectPositions('DECISION_FLOW', flow([
      { type: 'RuleRefNode', id: 'n0', ruleCode: 'RULE_A' },
      { type: 'OutputNode', id: 'n1', decisionCode: 'APPROVE' },
    ]));
    const c = at(cands, '/flowGraph/nodes/1/decisionCode');
    expect(c.currentValue).toBe('APPROVE');
    expect(c.dataType).toBe('STRING');
  });

  it('SwitchNode → /flowGraph/nodes/0/caseKeys (LIST)', () => {
    const cands = introspectPositions('DECISION_FLOW', flow([
      { type: 'SwitchNode', id: 'n0', lang: 'CEL', expression: 'x', caseKeys: ['a', 'b'] },
    ]));
    const c = at(cands, '/flowGraph/nodes/0/caseKeys');
    expect(c.currentValue).toEqual(['a', 'b']);
    expect(c.dataType).toBe('LIST');
  });

  it('TransformNode 不产候选', () => {
    const cands = introspectPositions('DECISION_FLOW', flow([
      { type: 'TransformNode', id: 'n0', lang: 'CEL', expression: 'x', outputKey: 'k' },
    ]));
    expect(cands).toEqual([]);
  });
});

describe('inferType 各分支（经 introspectPositions 值位覆盖）', () => {
  const cellType = (v: unknown) => {
    const body = ast({
      type: 'DecisionTableNode',
      columns: [{ metricCode: 'm', operator: 'EQ', dataType: null }],
      rows: [{ conditions: [v], decisionCode: 'D' }],
    });
    return at(introspectPositions('DECISION_TABLE', body), '/conditionAst/rows/0/conditions/0').dataType;
  };

  it('int → LONG', () => expect(cellType(42)).toBe('LONG'));
  it('float → DOUBLE', () => expect(cellType(3.14)).toBe('DOUBLE'));
  it('bool → BOOLEAN', () => expect(cellType(false)).toBe('BOOLEAN'));
  it('array → LIST', () => expect(cellType([1, 2])).toBe('LIST'));
  it('string → STRING', () => expect(cellType('hi')).toBe('STRING'));
  it('object/null 落 STRING', () => {
    expect(cellType({ a: 1 })).toBe('STRING');
    expect(cellType(null)).toBe('STRING');
  });
});
