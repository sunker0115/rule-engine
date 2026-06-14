import apiClient from './client';
import { ENDPOINTS } from '@/constants/api-endpoints';
import type { ApiResponse, PageResponse, JobItem, JobExecutionItem } from '@/types';

export async function listJobs(tenantId: number) {
  const res = await apiClient.get<ApiResponse<JobItem[]>>(ENDPOINTS.JOB_LIST, { params: { tenantId } });
  return res.data;
}

export async function triggerJob(tenantId: number, jobId: number) {
  return apiClient.post(ENDPOINTS.JOB_TRIGGER(jobId), null, { params: { tenantId } });
}

export async function getJobExecutions(tenantId: number, jobId: number, params?: Record<string, unknown>) {
  const res = await apiClient.get<ApiResponse<PageResponse<JobExecutionItem>>>(ENDPOINTS.JOB_EXECUTIONS(jobId), { params: { tenantId, ...params } });
  return res.data.data; // unwrap ApiResponse → PageResponse
}
