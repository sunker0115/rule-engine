import apiClient from './client';
import { ENDPOINTS } from '@/constants/api-endpoints';
import type { ApiResponse, DryRunRequest, EvalEventRequest, EvalResult } from '@/types';

/**
 * dry-run 试算——ruleVersionId / ruleId 二选一，作为 query param 传递。
 */
export async function dryRun(
  request: DryRunRequest,
  targets: { ruleVersionId?: number; ruleId?: number },
) {
  const params: Record<string, unknown> = {};
  if (targets.ruleVersionId) params.ruleVersionId = targets.ruleVersionId;
  else if (targets.ruleId) params.ruleId = targets.ruleId;

  const res = await apiClient.post<ApiResponse<EvalResult>>(ENDPOINTS.EVAL_DRY_RUN, request, { params });
  return res.data.data; // 从 ApiResponse 包裹中解出 EvalResult
}

/**
 * PULL 同步评估
 */
export async function pullEvaluate(request: EvalEventRequest) {
  const res = await apiClient.post<ApiResponse<EvalResult>>(ENDPOINTS.EVAL_EVALUATE, request);
  return res.data.data;
}
