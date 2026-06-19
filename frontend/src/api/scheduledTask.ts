import apiClient from './client';
import { ENDPOINTS } from '@/constants/api-endpoints';
import type { ApiResponse, ScheduledTaskItem, ScheduledTaskExecutionItem } from '@/types';

export async function listScheduledTasks(tenantId: number) {
  const res = await apiClient.get<ApiResponse<ScheduledTaskItem[]>>(ENDPOINTS.SCHEDULED_TASK_LIST, { params: { tenantId } });
  return res.data.data;
}

export async function getScheduledTask(tenantId: number, taskId: number) {
  const res = await apiClient.get<ApiResponse<ScheduledTaskItem>>(ENDPOINTS.SCHEDULED_TASK_DETAIL(taskId), { params: { tenantId } });
  return res.data.data;
}

export async function enableScheduledTask(tenantId: number, taskId: number) {
  return apiClient.post(ENDPOINTS.SCHEDULED_TASK_ENABLE(taskId), null, { params: { tenantId } });
}

export async function disableScheduledTask(tenantId: number, taskId: number) {
  return apiClient.post(ENDPOINTS.SCHEDULED_TASK_DISABLE(taskId), null, { params: { tenantId } });
}

export async function triggerScheduledTask(tenantId: number, taskId: number) {
  const res = await apiClient.post<ApiResponse<ScheduledTaskExecutionItem>>(ENDPOINTS.SCHEDULED_TASK_TRIGGER(taskId), null, { params: { tenantId } });
  return res.data.data;
}

export async function listScheduledTaskExecutions(tenantId: number, taskId: number, limit = 20) {
  const res = await apiClient.get<ApiResponse<ScheduledTaskExecutionItem[]>>(ENDPOINTS.SCHEDULED_TASK_EXECUTIONS(taskId), { params: { tenantId, limit } });
  return res.data.data;
}
