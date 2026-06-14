export interface EvalEventRequest {
  tenantId: string;
  sceneCode: string;
  eventType: string;
  subjectId: string;
  eventId: string;
  occurredAt: string;
  payload: Record<string, unknown>;
}

export interface DryRunRequest extends EvalEventRequest {
  ruleVersionId?: number;
  ruleId?: number;
}

export type ValueSource = 'PROVIDED' | 'FETCHED' | 'PAYLOAD';

export interface NodeTraceItem {
  type: string;
  result: boolean | null;
  children?: NodeTraceItem[];
  metricCode?: string;
  actualValue?: unknown;
  valueSource?: ValueSource;
  errorCode?: string;
  errorMessage?: string;
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
