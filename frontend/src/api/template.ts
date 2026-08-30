import apiClient from './client';
import { ENDPOINTS } from '@/constants/api-endpoints';
import type { ApiResponse, DraftCreatedResult } from '@/types';
import type { RuleTemplate, RuleTemplateVersion, TemplateDetail } from '@/types/template';

export async function listTemplates(tenantId: number, status?: string) {
  const res = await apiClient.get<ApiResponse<RuleTemplate[]>>(ENDPOINTS.TEMPLATE_LIST, {
    params: { status },
    headers: { 'X-Tenant-Id': tenantId },
  });
  return res.data.data;
}

/** 查模板详情（身份 + 最新版本快照）。 */
export async function getTemplate(tenantId: number, code: string) {
  const res = await apiClient.get<ApiResponse<TemplateDetail>>(ENDPOINTS.TEMPLATE_DETAIL(code), {
    headers: { 'X-Tenant-Id': tenantId },
  });
  return res.data.data;
}

/** 查模板版本历史。 */
export async function listVersions(tenantId: number, code: string) {
  const res = await apiClient.get<ApiResponse<RuleTemplateVersion[]>>(ENDPOINTS.TEMPLATE_VERSIONS(code), {
    headers: { 'X-Tenant-Id': tenantId },
  });
  return res.data.data;
}

/** 查指定版本快照。 */
export async function getVersion(tenantId: number, code: string, version: number) {
  const res = await apiClient.get<ApiResponse<TemplateDetail>>(
    `${ENDPOINTS.TEMPLATE_VERSIONS(code)}/${version}`,
    { headers: { 'X-Tenant-Id': tenantId } },
  );
  return res.data.data;
}

export async function createTemplate(tenantId: number, body: Record<string, unknown>) {
  const res = await apiClient.post<ApiResponse<number>>(ENDPOINTS.TEMPLATE_CREATE, {
    ...body,
    tenantId,
  });
  return res.data.data;
}

export async function updateTemplate(tenantId: number, code: string, body: Record<string, unknown>) {
  return apiClient.put(ENDPOINTS.TEMPLATE_UPDATE(code), { ...body, tenantId });
}

export async function publishTemplate(tenantId: number, code: string, actorId: string) {
  return apiClient.post(ENDPOINTS.TEMPLATE_PUBLISH(code), null, {
    headers: { 'X-Tenant-Id': tenantId, 'X-Actor-Id': actorId },
  });
}

export async function disableTemplate(tenantId: number, code: string, actorId: string) {
  return apiClient.post(ENDPOINTS.TEMPLATE_DISABLE(code), null, {
    headers: { 'X-Tenant-Id': tenantId, 'X-Actor-Id': actorId },
  });
}

export async function enableTemplate(tenantId: number, code: string, actorId: string) {
  return apiClient.post(ENDPOINTS.TEMPLATE_ENABLE(code), null, {
    headers: { 'X-Tenant-Id': tenantId, 'X-Actor-Id': actorId },
  });
}

export async function instantiateTemplate(code: string, body: Record<string, unknown>, actorId: string) {
  const res = await apiClient.post<ApiResponse<DraftCreatedResult>>(
    ENDPOINTS.TEMPLATE_INSTANTIATE(code),
    body,
    { headers: { 'X-Actor-Id': actorId } },
  );
  return res.data.data;
}
