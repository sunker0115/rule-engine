import apiClient from './client';
import { ENDPOINTS } from '@/constants/api-endpoints';
import type { ApiResponse, PageResponse, EvalSessionItem, NodeTraceItem } from '@/types';

/**
 * 评估会话列表——返回已解包的 PageResponse。
 * 后端统一用 ApiResponse 包裹，此处做 unwrap。
 */
export async function listSessions(params: Record<string, unknown>) {
  const res = await apiClient.get<ApiResponse<PageResponse<EvalSessionItem>>>(ENDPOINTS.SESSION_LIST, { params });
  return res.data.data; // unwrap: ApiResponse → PageResponse
}

export async function getSession(tenantId: number, sessionId: string) {
  // sessionId 是 19 位大数，JS Number 会丢失精度，直接用字符串拼 URL
  const res = await apiClient.get<ApiResponse<EvalSessionItem>>(`${ENDPOINTS.SESSION_LIST}/${sessionId}`, { params: { tenantId } });
  return res.data.data;
}

export async function getSessionTrace(tenantId: number, sessionId: string) {
  const res = await apiClient.get<ApiResponse<NodeTraceItem[]>>(`${ENDPOINTS.SESSION_LIST}/${sessionId}/trace/tree`, { params: { tenantId } });
  return res.data.data;
}

export async function getRuleSessions(tenantId: number, ruleDefinitionId: number, params?: Record<string, unknown>) {
  const res = await apiClient.get<ApiResponse<PageResponse<EvalSessionItem>>>(ENDPOINTS.RULE_SESSIONS(ruleDefinitionId), { params: { tenantId, ...params } });
  return res.data.data;
}
