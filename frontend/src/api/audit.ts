import apiClient from './client';
import { ENDPOINTS } from '@/constants/api-endpoints';
import type { PageResponse, AuditLogItem } from '@/types';

export async function listAuditLogs(params: Record<string, unknown>) {
  const res = await apiClient.get<PageResponse<AuditLogItem>>(ENDPOINTS.AUDIT_LOG_LIST, { params });
  return res.data;
}
