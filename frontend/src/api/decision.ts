import apiClient from './client';
import { ENDPOINTS } from '@/constants/api-endpoints';
import type { ApiResponse, DecisionItem, DecisionSources, UsageCount } from '@/types';

export async function listDecisions(tenantId: number) {
  const res = await apiClient.get<ApiResponse<DecisionItem[]>>(ENDPOINTS.DECISION_LIST, { params: { tenantId } });
  return res.data.data;
}

export async function createDecision(tenantId: number, body: Record<string, unknown>) {
  return apiClient.post(ENDPOINTS.DECISION_CREATE, body, { params: { tenantId } });
}

export async function updateDecision(tenantId: number, code: string, body: Record<string, unknown>) {
  return apiClient.put(`${ENDPOINTS.DECISION_LIST}/${code}`, body, { params: { tenantId } });
}

/** 取单个 decision 完整定义 */
export async function getDecision(tenantId: number, code: string) {
  const res = await apiClient.get<ApiResponse<DecisionItem>>(ENDPOINTS.DECISION_GET(code), { params: { tenantId } });
  return res.data.data;
}

/** 血缘：取产出该 decision 的规则来源 */
export async function getDecisionSources(tenantId: number, code: string): Promise<DecisionSources> {
  const res = await apiClient.get<ApiResponse<DecisionSources>>(ENDPOINTS.DECISION_SOURCES(code), { params: { tenantId } });
  return res.data.data;
}

/** 血缘：批量取 decision 被引用计数 */
export async function getDecisionUsageCounts(tenantId: number): Promise<UsageCount[]> {
  const res = await apiClient.get<ApiResponse<UsageCount[]>>(ENDPOINTS.DECISION_USAGE_COUNTS, { params: { tenantId } });
  return res.data.data;
}
