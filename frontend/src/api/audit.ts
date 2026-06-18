import apiClient from './client';
import { ENDPOINTS } from '@/constants/api-endpoints';
import type { ApiResponse, PageResponse, AuditLogItem } from '@/types';

export async function listAuditLogs(params: Record<string, unknown>) {
  const res = await apiClient.get<ApiResponse<PageResponse<AuditLogItem>>>(ENDPOINTS.AUDIT_LOG_LIST, { params });
  return res.data.data; // unwrap ApiResponse → PageResponse
}
