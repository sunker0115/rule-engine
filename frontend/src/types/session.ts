export type SessionStatus = 'HIT' | 'MISS' | 'BLOCKED' | 'ERROR' | 'PENDING' | 'FAILED';

/** 评估会话——字段对齐 GET /admin/v1/evaluation-sessions 实际响应 */
export interface EvalSessionItem {
  sessionId: string;
  tenantId: string;
  sceneCode: string;
  eventId: string;
  eventType: string;
  subjectId: string;
  source: string;
  mode: string;
  status: SessionStatus;
  finalDecision?: string;
  blockedBy?: string;
  errorCode?: string;
  candidateRuleCount: number;
  hitRuleCount: number;
  score?: number;
  category?: string;
  evalDurationMs: number;
  occurredAt?: string;
  startedAt: string;
  finishedAt?: string;
  contextSnapshot?: string;
}
