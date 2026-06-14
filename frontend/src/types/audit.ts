export type ActorType = 'USER' | 'SYSTEM' | 'JOB';
export type AuditAction = 'CREATE' | 'UPDATE' | 'PUBLISH' | 'PUBLISH_FAILED' | 'ENABLE' | 'DISABLE' | 'DELETE' | 'IMPORT';

export interface AuditLogItem {
  actor: string;
  actorType: ActorType;
  action: AuditAction;
  targetType: string;
  targetId: number;
  beforeSnapshot?: Record<string, unknown>;
  afterSnapshot?: Record<string, unknown>;
  operatedAt: string;
  traceId?: string;
}
