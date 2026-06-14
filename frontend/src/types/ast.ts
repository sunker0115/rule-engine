export type AstNode = AndNode | OrNode | NotNode | XorNode | ConditionNode | ScorecardRootNode | IfNode | DecisionLeafNode | DecisionTableNode;

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

/** kind=DECISION_TREE 的规则条件分支节点 */
export interface IfNode {
  type: 'IfNode';
  condition: AstNode;
  thenBranch: AstNode;
  elseBranch: AstNode | null;
}

/** DECISION_TREE 叶子：命中后返回的决策码 */
export interface DecisionLeafNode {
  type: 'DecisionLeafNode';
  decisionCode: string;
  category: string | null;
}

/** XOR 逻辑节点：子节点中有且仅有一个 true 时节点才为 true */
export interface XorNode {
  type: 'XorNode';
  children: AstNode[];
  displayLabel: string | null;
}

/** kind=DECISION_TABLE 根节点：按行顺序 FIRST_HIT 匹配 */
export interface DecisionTableNode {
  type: 'DecisionTableNode';
  columns: DecisionTableColumn[];
  rows: DecisionTableRow[];
}

export interface DecisionTableColumn {
  metricCode: string;
  operator: string;
  dataType: string | null;
}

export interface DecisionTableRow {
  conditions: (unknown | null)[];
  decisionCode: string;
}
