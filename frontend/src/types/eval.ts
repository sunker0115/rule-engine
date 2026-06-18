/** eval API 请求体——字段名对齐后端 EvalEventRequest record */
export interface EvalEventRequest {
  tenantCode: string;    // 租户业务标识 (tenant.code)，非数字 id
  sceneCode: string;
  eventType: string;
  subjectId: string;
  eventId: string;
  occurredAt: string;
  payload: Record<string, unknown>;
  asOf?: string;          // 可选的求值时钟
}

/** dry-run 请求体——ruleId/ruleVersionId 是 query param，不在 body */
export interface DryRunRequest extends Omit<EvalEventRequest, 'asOf'> {
  // query params: ruleVersionId / ruleId (二选一)
}

export type ValueSource = 'PROVIDED' | 'FETCHED' | 'PAYLOAD';

/** Trace 节点——字段名对齐后端 NodeTraceItem */
export interface NodeTraceItem {
  nodeType: string;
  conditionType: string | null;
  metricCode: string | null;
  result: boolean | null;
  actualValue: unknown;
  valueSource: ValueSource | null;
  errorCode: string | null;
  errorMessage?: string;
  children: NodeTraceItem[];
}

export interface DecisionRef {
  code: string;
  name: string;
  priority: number;
  fromRuleVersionId: number;
}

export interface EvalResult {
  eventId: string;
  ruleHit: boolean;
  finalDecision: DecisionRef | null;
  hitDecisions: DecisionRef[];
  nodeTrace: NodeTraceItem[];
  errorCode: string | null;
}
