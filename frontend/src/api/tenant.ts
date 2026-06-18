import apiClient from './client';
import { ENDPOINTS } from '@/constants/api-endpoints';
import type { ApiResponse, TenantInfo } from '@/types';

export async function listTenants(params?: Record<string, unknown>) {
  const res = await apiClient.get<ApiResponse<TenantInfo[]>>(ENDPOINTS.TENANT_LIST, { params });
  return res.data.data;
}
