import apiClient from './client';
import { ENDPOINTS } from '@/constants/api-endpoints';
import type { ApiResponse, InputManifest } from '@/types';

export async function getInputManifest(tenantCode: string, sceneCode: string, eventType?: string) {
  const params: Record<string, string> = { tenantCode };
  if (eventType) params.eventType = eventType;
  const res = await apiClient.get<ApiResponse<InputManifest>>(ENDPOINTS.INPUT_MANIFEST(sceneCode), { params });
  return res.data.data;
}
