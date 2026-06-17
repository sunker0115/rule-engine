import apiClient from './client';
import { ENDPOINTS } from '@/constants/api-endpoints';
import type { ApiResponse, SceneMetadata } from '@/types';

export async function getSceneMetadata(tenantId: number, sceneCode: string) {
  const res = await apiClient.get<ApiResponse<SceneMetadata>>(ENDPOINTS.SCENE_METADATA(sceneCode), { params: { tenantId } });
  return res.data.data;
}
