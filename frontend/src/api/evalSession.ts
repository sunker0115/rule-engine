import apiClient from './client';
import { ENDPOINTS } from '@/constants/api-endpoints';
import type { ApiResponse, PageResponse, EvalSessionItem, EvalSessionDetail, NodeTraceItem } from '@/types';

export async function listSessions(params: Record<string, unknown>) {
  const res = await apiClient.get<PageResponse<EvalSessionItem>>(ENDPOINTS.SESSION_LIST, { params });
  return res.data;
}

export async function getSession(tenantId: number, sessionId: number) {
  const res = await apiClient.get<ApiResponse<EvalSessionDetail>>(ENDPOINTS.SESSION_DETAIL(sessionId), { params: { tenantId } });
  return res.data;
}

export async function getSessionTrace(tenantId: number, sessionId: number) {
  const res = await apiClient.get<ApiResponse<NodeTraceItem[]>>(ENDPOINTS.SESSION_TRACE_TREE(sessionId), { params: { tenantId } });
  return res.data;
}

export async function getRuleSessions(tenantId: number, ruleDefinitionId: number, params?: Record<string, unknown>) {
  const res = await apiClient.get<PageResponse<EvalSessionItem>>(ENDPOINTS.RULE_SESSIONS(ruleDefinitionId), { params: { tenantId, ...params } });
  return res.data;
}
