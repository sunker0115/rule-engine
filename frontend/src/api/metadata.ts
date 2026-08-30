import apiClient from './client';
import { ENDPOINTS } from '@/constants/api-endpoints';
import type { ApiResponse, SceneMetadata } from '@/types';

export async function getSceneMetadata(tenantId: number, sceneCode: string) {
  const res = await apiClient.get<ApiResponse<SceneMetadata>>(ENDPOINTS.SCENE_METADATA(sceneCode), { params: { tenantId } });
  return res.data.data;
}

/**
 * 租户级元数据——conditionTypes(SPI 全局) + tenant 全量 ACTIVE metrics + expressionLangs。
 * 不依赖 scene，供模板编辑器等 scene-agnostic 上下文直接使用。
 */
export async function getTenantMetadata(tenantId: number): Promise<SceneMetadata> {
  const res = await apiClient.get<ApiResponse<SceneMetadata>>(ENDPOINTS.TENANT_METADATA, { params: { tenantId } });
  // payloadFieldNames/Types 为空（tenant 级不含 payload schema）
  const data = res.data.data;
  return { ...data, payloadFieldNames: [], payloadFieldTypes: {} };
}
