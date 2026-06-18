import apiClient from './client';
import { ENDPOINTS } from '@/constants/api-endpoints';
import type { ApiResponse, SceneListItem, SceneDetail, RuleSetAnalysisReport } from '@/types';

export async function listScenes(tenantId: number, params?: Record<string, unknown>) {
  const res = await apiClient.get<ApiResponse<SceneListItem[]>>(ENDPOINTS.SCENE_LIST, {
    params: { tenantId, ...params },
  });
  return res.data.data;
}

export async function getScene(tenantId: number, sceneCode: string) {
  const res = await apiClient.get<ApiResponse<SceneDetail>>(ENDPOINTS.SCENE_DETAIL(sceneCode), {
    params: { tenantId },
  });
  return res.data.data;
}

export async function createScene(body: Record<string, unknown>) {
  return apiClient.post(ENDPOINTS.SCENE_CREATE, body);
}

/** 规则集静态分析——只读、按需触发，返回 6 类告警的报告。 */
export async function getAnalysis(sceneCode: string, tenantId: number) {
  const res = await apiClient.get<ApiResponse<RuleSetAnalysisReport>>(ENDPOINTS.SCENE_ANALYSIS(sceneCode), {
    params: { tenantId },
  });
  return res.data.data;
}
