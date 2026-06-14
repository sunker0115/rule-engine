import apiClient from './client';
import { ENDPOINTS } from '@/constants/api-endpoints';
import type { ApiResponse, DecisionItem } from '@/types';

export async function listDecisions(tenantId: number) {
  const res = await apiClient.get<ApiResponse<DecisionItem[]>>(ENDPOINTS.DECISION_LIST, { params: { tenantId } });
  return res.data;
}

export async function createDecision(tenantId: number, body: Record<string, unknown>) {
  return apiClient.post(ENDPOINTS.DECISION_CREATE, body, { params: { tenantId } });
}

export async function updateDecision(tenantId: number, code: string, body: Record<string, unknown>) {
  return apiClient.put(`${ENDPOINTS.DECISION_LIST}/${code}`, body, { params: { tenantId } });
}
