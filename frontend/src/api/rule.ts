import apiClient from './client';
import { ENDPOINTS } from '@/constants/api-endpoints';
import type { ApiResponse, PageResponse, RuleListItem, RuleDetail, DraftCreatedResult } from '@/types';

export async function listRules(tenantId: number, sceneCode: string, params?: Record<string, unknown>) {
  const res = await apiClient.get<PageResponse<RuleListItem>>(ENDPOINTS.RULE_LIST, { params: { tenantId, sceneCode, ...params } });
  return res.data;
}

export async function getRule(tenantId: number, ruleDefinitionId: number) {
  const res = await apiClient.get<ApiResponse<RuleDetail>>(ENDPOINTS.RULE_DETAIL(ruleDefinitionId), { params: { tenantId } });
  return res.data;
}

export async function createRule(tenantId: number, body: Record<string, unknown>) {
  const res = await apiClient.post<DraftCreatedResult>(ENDPOINTS.RULE_CREATE, { ...body, tenantId });
  return res.data;
}

export async function editDraft(ruleDefinitionId: number, body: Record<string, unknown>) {
  return apiClient.put(ENDPOINTS.RULE_DRAFT(ruleDefinitionId), body);
}

export async function publishRule(ruleDefinitionId: number) {
  return apiClient.post(ENDPOINTS.RULE_PUBLISH(ruleDefinitionId));
}

export async function disableRule(ruleDefinitionId: number) {
  return apiClient.post(ENDPOINTS.RULE_DISABLE(ruleDefinitionId));
}

export async function newVersion(ruleDefinitionId: number, fromVersionId?: number) {
  return apiClient.post(ENDPOINTS.RULE_VERSIONS(ruleDefinitionId), { fromVersionId });
}

export async function deleteRule(ruleDefinitionId: number) {
  return apiClient.delete(ENDPOINTS.RULE_DELETE(ruleDefinitionId));
}

export async function deleteDraftVersion(ruleDefinitionId: number, versionId: number) {
  return apiClient.delete(ENDPOINTS.RULE_DELETE_VERSION(ruleDefinitionId, versionId));
}
