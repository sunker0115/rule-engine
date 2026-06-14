import apiClient from './client';
import { ENDPOINTS } from '@/constants/api-endpoints';
import type { ApiResponse, MetricDescriptor, MetricImpactResult } from '@/types';

export async function listMetrics(tenantId: number) {
  const res = await apiClient.get<ApiResponse<MetricDescriptor[]>>(ENDPOINTS.METRIC_LIST, { params: { tenantId } });
  return res.data;
}

export async function createMetric(tenantId: number, metricCode: string, body: Record<string, unknown>) {
  return apiClient.post(ENDPOINTS.METRIC_CREATE, body, { params: { tenantId, metricCode } });
}

export async function updateMetric(tenantId: number, metricCode: string, breakingChange: boolean, body: Record<string, unknown>) {
  return apiClient.put(ENDPOINTS.METRIC_UPDATE(metricCode), body, { params: { tenantId, breakingChange } });
}

export async function getMetricImpact(tenantId: number, metricCode: string, metricVersion: number) {
  const res = await apiClient.get<ApiResponse<MetricImpactResult>>(
    ENDPOINTS.METRIC_IMPACT(metricCode, metricVersion), { params: { tenantId } }
  );
  return res.data;
}
