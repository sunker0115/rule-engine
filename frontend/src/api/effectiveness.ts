import apiClient from './client';
import { ENDPOINTS } from '@/constants/api-endpoints';
import type {
  ApiResponse,
  EffectivenessReport,
  EffectivenessParams,
  RecordOutcomesRequest,
} from '@/types';

/** 按需聚合决策效果（B32）；positiveLabels 以逗号分隔传给后端。 */
export async function getEffectiveness(params: EffectivenessParams): Promise<EffectivenessReport> {
  const { positiveLabels, ...rest } = params;
  const res = await apiClient.get<ApiResponse<EffectivenessReport>>(ENDPOINTS.EFFECTIVENESS, {
    params: {
      ...rest,
      positiveLabels: positiveLabels && positiveLabels.length > 0 ? positiveLabels.join(',') : undefined,
    },
  });
  return res.data.data;
}

/** 批量回灌结果标签（幂等 upsert）；返回接受条数。 */
export async function recordOutcomes(body: RecordOutcomesRequest): Promise<{ accepted: number }> {
  const res = await apiClient.post<ApiResponse<{ accepted: number }>>(ENDPOINTS.DECISION_OUTCOMES, body);
  return res.data.data;
}
