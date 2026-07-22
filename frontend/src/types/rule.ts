import type { AstNode } from './ast';
import type { FlowGraph } from './flow';

export type RuleKind = 'AST_BOOLEAN' | 'SCORECARD' | 'DECISION_TREE' | 'DECISION_TABLE' | 'EXPRESSION_SCRIPT' | 'DECISION_FLOW';
export type RuleStatus = 'DRAFT' | 'PUBLISHED' | 'DISABLED';
export type VersionStatus = 'DRAFT' | 'ACTIVE' | 'SUPERSEDED';

/** 规则列表项——字段对齐 GET /admin/v1/rules 实际响应 */
export interface RuleListItem {
  tenantId: number;
  ruleDefinitionId: number;
  code: string;
  name: string;
  kind: RuleKind;
  sceneCode: string;
  status: RuleStatus;
  currentVersion?: number | null;
  publishedAt?: string | null;
  createdAt?: string;
}

export interface RuleDetail extends RuleListItem {
  kind: RuleKind;           // 详情接口返回，列表不返回
  sceneCode: string;        // 详情接口返回，列表不返回
  conditionAst?: AstNode | null;
  script?: { source: string; lang: string } | null;
  /** DECISION_FLOW 规则的决策图；其它 kind 为 null。与 conditionAst/script 平级三选一。 */
  flowGraph?: FlowGraph | null;
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

/** 单个规则版本的完整内容——对齐 GET /admin/v1/rules/{ruleId}/versions/{versionId} */
export interface RuleVersionContent {
  ruleVersionId: number;
  version: number;
  status: string;
  kind: string;
  conditionAst?: AstNode | null;
  decisionBindings: DecisionBinding[];
  preGates: PreGate[];
  triggerEventTypes: string[];
  script?: { source: string; lang: string } | null;
  /** DECISION_FLOW 规则的决策图；其它 kind 为 null。 */
  flowGraph?: FlowGraph | null;
  createdAt?: string | null;
  publishedBy?: string | null;
  publishedAt?: string | null;
}

export interface DecisionBinding {
  decisionCode: string;
}

export interface RolloutPreGate {
  gateType: 'ROLLOUT';
  params: RolloutParams;
}

export interface TimeWindowPreGate {
  gateType: 'TIME_WINDOW';
  params: TimeWindowParams;
}

/** 前置门控配置：按 gateType 判别的联合，读取 params 时无需强转。 */
export type PreGate = RolloutPreGate | TimeWindowPreGate;

export interface RolloutParams {
  percentage?: number;
  bucketStart?: number;
  bucketEnd?: number;
  experimentId?: string;
}

export interface TimeWindowParams {
  fromEpochMilli?: number;
  toEpochMilli?: number;
}
