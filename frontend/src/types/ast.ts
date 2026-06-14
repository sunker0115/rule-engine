export type AstNode = AndNode | OrNode | NotNode | ConditionNode | ScorecardRootNode;

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

/** kind=SCORECARD 的规则 conditionAst 顶层节点 */
export interface ScorecardRootNode {
  type: 'ScorecardRootNode';
  conditions: ConditionNode[];
  threshold: number;
}
