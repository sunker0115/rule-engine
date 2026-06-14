import apiClient from './client';
import { ENDPOINTS } from '@/constants/api-endpoints';
import type { ApiResponse, SceneListItem, SceneDetail } from '@/types';

export async function listScenes(tenantId: number, params?: Record<string, unknown>) {
  const res = await apiClient.get<ApiResponse<SceneListItem[]>>(ENDPOINTS.SCENE_LIST, {
    params: { tenantId, ...params },
  });
  return res.data;
}

export async function getScene(tenantId: number, sceneCode: string) {
  const res = await apiClient.get<ApiResponse<SceneDetail>>(ENDPOINTS.SCENE_DETAIL(sceneCode), {
    params: { tenantId },
  });
  return res.data;
}

export async function createScene(body: Record<string, unknown>) {
  return apiClient.post(ENDPOINTS.SCENE_CREATE, body);
}
