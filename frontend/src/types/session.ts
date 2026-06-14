export type SessionStatus = 'HIT' | 'MISS' | 'BLOCKED' | 'ERROR' | 'PENDING' | 'FAILED';
export type EventSource = 'HTTP' | 'MQ' | 'JOB' | 'SDK' | 'REPLAY';
export type EvalMode = 'PUSH' | 'PULL';

export interface EvalSessionItem {
  sessionId: number;
  eventId: string;
  sceneCode: string;
  eventType: string;
  subjectId: string;
  status: SessionStatus;
  blockedBy?: string;
  errorCode?: string;
  finalDecision?: string;
  candidateRuleCount: number;
  hitRuleCount: number;
  source: EventSource;
  mode: EvalMode;
  evalDurationMs: number;
  occurredAt: string;
  startedAt: string;
}

export interface EvalSessionDetail extends EvalSessionItem {
  finishedAt?: string;
  contextSnapshot?: Record<string, unknown>;
}
