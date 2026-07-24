import apiClient from './client';
import { ENDPOINTS } from '@/constants/api-endpoints';
import type { ApiResponse, DraftCreatedResult } from '@/types';
import type { RuleTemplate } from '@/types/template';

export async function listTemplates(tenantId: number, status?: string) {
  const res = await apiClient.get<ApiResponse<RuleTemplate[]>>(ENDPOINTS.TEMPLATE_LIST, {
    params: { tenantId, status },
  });
  return res.data.data;
}

export async function getTemplate(tenantId: number, code: string) {
  const res = await apiClient.get<ApiResponse<RuleTemplate>>(ENDPOINTS.TEMPLATE_DETAIL(code), {
    headers: { 'X-Tenant-Id': tenantId },
  });
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

export async function publishTemplate(tenantId: number, code: string) {
  return apiClient.post(ENDPOINTS.TEMPLATE_PUBLISH(code), null, {
    headers: { 'X-Tenant-Id': tenantId },
  });
}

export async function disableTemplate(tenantId: number, code: string) {
  return apiClient.post(ENDPOINTS.TEMPLATE_DISABLE(code), null, {
    headers: { 'X-Tenant-Id': tenantId },
  });
}

export async function instantiateTemplate(code: string, body: Record<string, unknown>) {
  const res = await apiClient.post<ApiResponse<DraftCreatedResult>>(
    ENDPOINTS.TEMPLATE_INSTANTIATE(code),
    body,
  );
  return res.data.data;
}
