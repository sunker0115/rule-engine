import apiClient from './client';
import { ENDPOINTS } from '@/constants/api-endpoints';
import type { ApiResponse, TenantInfo } from '@/types';

export async function listTenants(params?: Record<string, unknown>) {
  const res = await apiClient.get<ApiResponse<TenantInfo[]>>(ENDPOINTS.TENANT_LIST, { params });
  return res.data.data;
}

export async function createTenant(code: string, name: string, actorId: string) {
  const res = await apiClient.post<ApiResponse<number>>(
    ENDPOINTS.TENANT_LIST,
    null,
    { params: { code, name }, headers: { 'X-Actor-Id': actorId } },
  );
  return res.data.data;
}
