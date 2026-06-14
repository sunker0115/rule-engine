import apiClient from './client';
import { ENDPOINTS } from '@/constants/api-endpoints';
import type { ApiResponse, PageResponse, RuleListItem, RuleDetail, DraftCreatedResult } from '@/types';

export async function listRules(tenantId: number, sceneCode: string, params?: Record<string, unknown>) {
  const res = await apiClient.get<ApiResponse<PageResponse<RuleListItem>>>(ENDPOINTS.RULE_LIST, { params: { tenantId, sceneCode, ...params } });
  return res.data.data; // unwrap ApiResponse → PageResponse
}

export async function getRule(tenantId: number, ruleDefinitionId: number) {
  const res = await apiClient.get<ApiResponse<RuleDetail>>(ENDPOINTS.RULE_DETAIL(ruleDefinitionId), { params: { tenantId } });
  return res.data;
}

export async function createRule(tenantId: number, body: Record<string, unknown>) {
  const res = await apiClient.post<DraftCreatedResult>(ENDPOINTS.RULE_CREATE, { ...body, tenantId });
  return res.data;
}

/** 编辑草稿——tenantId 在 body 中 */
export async function editDraft(tenantId: number, ruleDefinitionId: number, body: Record<string, unknown>) {
  return apiClient.put(ENDPOINTS.RULE_DRAFT(ruleDefinitionId), { ...body, tenantId });
}

/** 发布——tenantId 是 query param */
export async function publishRule(tenantId: number, ruleDefinitionId: number) {
  return apiClient.post(ENDPOINTS.RULE_PUBLISH(ruleDefinitionId), null, { params: { tenantId } });
}

/** 禁用 */
export async function disableRule(tenantId: number, ruleDefinitionId: number) {
  return apiClient.post(ENDPOINTS.RULE_DISABLE(ruleDefinitionId), null, { params: { tenantId } });
}

/** 新版本/回退 */
export async function newVersion(tenantId: number, ruleDefinitionId: number, fromVersionId?: number) {
  return apiClient.post(ENDPOINTS.RULE_VERSIONS(ruleDefinitionId), { fromVersionId }, { params: { tenantId } });
}

export async function deleteRule(tenantId: number, ruleDefinitionId: number) {
  return apiClient.delete(ENDPOINTS.RULE_DELETE(ruleDefinitionId), { params: { tenantId } });
}

export async function deleteDraftVersion(tenantId: number, ruleDefinitionId: number, versionId: number) {
  return apiClient.delete(ENDPOINTS.RULE_DELETE_VERSION(ruleDefinitionId, versionId), { params: { tenantId } });
}
