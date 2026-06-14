import type { AstNode } from './ast';

export type RuleKind = 'AST_BOOLEAN' | 'SCORECARD' | 'DECISION_TREE' | 'DECISION_TABLE' | 'EXPRESSION_SCRIPT';
export type RuleStatus = 'DRAFT' | 'PUBLISHED' | 'DISABLED';
export type VersionStatus = 'DRAFT' | 'ACTIVE' | 'SUPERSEDED';

export interface RuleListItem {
  ruleDefinitionId: number;
  code: string;
  name: string;
  kind: RuleKind;
  sceneCode: string;
  status: RuleStatus;
  currentVersion?: number;
  publishedAt?: string;
  createdAt: string;
}

export interface RuleDetail extends RuleListItem {
  conditionAst?: AstNode | null;
  script?: { source: string; lang: string } | null;
  decisionBindings: DecisionBinding[];
  preGates: PreGate[];
  triggerEventTypes: string[];
  currentVersionId?: number;
  versions: RuleVersionItem[];
}

export interface DraftCreatedResult {
  ruleDefinitionId: number;
  ruleVersionId: number;
  version: number;
  status: 'DRAFT';
}

export interface RuleVersionItem {
  ruleVersionId: number;
  version: number;
  status: VersionStatus;
  createdAt: string;
  publishedBy?: string;
  publishedAt?: string;
}

export interface DecisionBinding {
  decisionCode: string;
  scoreRangeMin?: number;
  scoreRangeMax?: number;
}

export interface PreGate {
  gateType: 'ROLLOUT';
  params: RolloutParams;
}

export interface RolloutParams {
  percentage?: number;
  bucketStart?: number;
  bucketEnd?: number;
  experimentId?: string;
}
