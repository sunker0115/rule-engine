import apiClient from './client';
import { ENDPOINTS } from '@/constants/api-endpoints';
import type { EvalEventRequest, DryRunRequest, EvalResult } from '@/types';

export async function dryRun(request: DryRunRequest) {
  const res = await apiClient.post<EvalResult>(ENDPOINTS.EVAL_DRY_RUN, request);
  return res.data;
}

export async function pullEvaluate(request: EvalEventRequest) {
  const res = await apiClient.post<EvalResult>(ENDPOINTS.EVAL_EVALUATE, request);
  return res.data;
}
