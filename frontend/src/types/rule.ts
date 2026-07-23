import type { AstNode } from './ast';
import type { FlowGraph } from './flow';

export type RuleKind = 'AST_BOOLEAN' | 'SCORECARD' | 'DECISION_TREE' | 'DECISION_TABLE' | 'EXPRESSION_SCRIPT' | 'DECISION_FLOW';
export type RuleStatus = 'DRAFT' | 'PUBLISHED' | 'DISABLED';
export type VersionStatus = 'DRAFT' | 'ACTIVE' | 'SUPERSEDED';

/** 判定主体多态载体（三承载收敛，与后端 RuleBody 对齐，type 判别）。 */
export type RuleBody =
  | { type: 'AstBody'; conditionAst?: AstNode | null }
  | { type: 'ScriptBody'; script: { source: string; lang: string } }
  | { type: 'FlowBody'; flowGraph: FlowGraph; referencedSnapshots?: Record<string, unknown> };

/** 编辑态平铺载体（store/编辑器内部用，与 body 在 API 边界互转）。 */
export interface BodyCarriers {
  conditionAst: AstNode | null;
  script: { source: string; lang: string } | null;
  flowGraph: FlowGraph | null;
}

/** body → 平铺载体（详情/版本加载时 unwrap）。 */
export function bodyToCarriers(body: RuleBody | null | undefined): BodyCarriers {
  if (!body) return { conditionAst: null, script: null, flowGraph: null };
  switch (body.type) {
    case 'AstBody': return { conditionAst: body.conditionAst ?? null, script: null, flowGraph: null };
    case 'ScriptBody': return { conditionAst: null, script: body.script, flowGraph: null };
    case 'FlowBody': return { conditionAst: null, script: null, flowGraph: body.flowGraph };
    default: return { conditionAst: null, script: null, flowGraph: null };
  }
}

/** 平铺载体 → body（请求组装时 wrap，按 kind 判别）。 */
export function carriersToBody(
  kind: RuleKind,
  c: { conditionAst?: AstNode | null; script?: { source: string; lang: string } | null; flowGraph?: FlowGraph | null },
): RuleBody {
  if (kind === 'EXPRESSION_SCRIPT') return { type: 'ScriptBody', script: c.script as { source: string; lang: string } };
  if (kind === 'DECISION_FLOW') return { type: 'FlowBody', flowGraph: c.flowGraph as FlowGraph, referencedSnapshots: {} };
  return { type: 'AstBody', conditionAst: c.conditionAst ?? null };
}

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
  /** 判定主体多态载体（三承载收敛）；用 bodyToCarriers 拆成编辑态。 */
  body?: RuleBody | null;
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
  body?: RuleBody | null;
  decisionBindings: DecisionBinding[];
  preGates: PreGate[];
  triggerEventTypes: string[];
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
