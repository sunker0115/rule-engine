import type { AstNode } from './ast';

export type RuleKind = 'AST_BOOLEAN' | 'SCORECARD' | 'DECISION_TREE' | 'DECISION_TABLE' | 'EXPRESSION_SCRIPT';
export type RuleStatus = 'DRAFT' | 'PUBLISHED' | 'DISABLED';
export type VersionStatus = 'DRAFT' | 'ACTIVE' | 'SUPERSEDED';

/** 规则列表项——字段对齐 GET /admin/v1/rules 实际响应 */
export interface RuleListItem {
  ruleDefinitionId: number;
  code: string;
  name: string;
  status: RuleStatus;
  currentVersion?: number | null;
  publishedAt?: string | null;
}

export interface RuleDetail extends RuleListItem {
  kind: RuleKind;           // 详情接口返回，列表不返回
  sceneCode: string;        // 详情接口返回，列表不返回
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
