export type SessionStatus = 'HIT' | 'MISS' | 'BLOCKED' | 'ERROR' | 'PENDING' | 'FAILED';

/** 评估会话列表项——字段对齐 GET /admin/v1/evaluation-sessions 实际响应 */
export interface EvalSessionItem {
  sessionId: number;
  tenantId: string;
  sceneCode: string;
  eventId: string;
  status: SessionStatus;
  startedAt: string;
}
