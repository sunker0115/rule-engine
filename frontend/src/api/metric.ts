import apiClient from './client';
import { ENDPOINTS } from '@/constants/api-endpoints';
import type { ApiResponse, FetchTestSample, FetchTrace, MetricDescriptor, MetricImpactResult } from '@/types';

export async function listMetrics(tenantId: number) {
  const res = await apiClient.get<ApiResponse<MetricDescriptor[]>>(ENDPOINTS.METRIC_LIST, { params: { tenantId } });
  return res.data.data;
}

/** 取单个 metric 完整定义（结构同 list item） */
export async function getMetric(metricCode: string, tenantId: number) {
  const res = await apiClient.get<ApiResponse<MetricDescriptor>>(
    ENDPOINTS.METRIC_DETAIL(metricCode),
    { params: { tenantId } },
  );
  return res.data.data;
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
  return res.data.data;
}

/** 自助试算 metric 取数：用样例输入实打实取数一次，返回分阶段取数链路 trace */
export async function testMetric(metricCode: string, tenantId: number, sample: FetchTestSample) {
  const res = await apiClient.post<ApiResponse<FetchTrace>>(
    ENDPOINTS.METRIC_TEST(metricCode),
    sample,
    { params: { tenantId } },
  );
  return res.data.data;
}

