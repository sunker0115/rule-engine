export type ActorType = 'USER' | 'SYSTEM' | 'JOB';
export type AuditAction = 'CREATE' | 'UPDATE' | 'PUBLISH' | 'ENABLE' | 'DISABLE' | 'DELETE' | 'IMPORT';

/** 审计日志项——字段对齐 GET /admin/v1/audit-logs 实际响应 */
export interface AuditLogItem {
  id: number;
  tenantId: number;
  resourceType: string;   // API 字段名
  resourceId: number;      // API 字段名
  action: AuditAction;
  actorId: string;         // API 字段名
  actorType: ActorType;
  beforeSnapshot?: string; // JSON 字符串
  afterSnapshot?: string;  // JSON 字符串
  occurredAt: string;      // API 字段名
}
