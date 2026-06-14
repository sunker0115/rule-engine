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
