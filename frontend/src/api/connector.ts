import apiClient from './client';
import { ENDPOINTS } from '@/constants/api-endpoints';
import type {
  ApiResponse,
  ConnectorListItem,
  ConnectorWriteBody,
  FetchTestSample,
  FetchTrace,
} from '@/types';

/** 列出租户下全部连接器 */
export async function listConnectors(tenantId: number) {
  const res = await apiClient.get<ApiResponse<ConnectorListItem[]>>(ENDPOINTS.CONNECTORS, {
    params: { tenantId },
  });
  return res.data;
}

/** 新建连接器（connectorCode 走 query，X-Actor-Id 由请求拦截器注入） */
export async function createConnector(tenantId: number, connectorCode: string, body: ConnectorWriteBody) {
  return apiClient.post(ENDPOINTS.CONNECTORS, body, { params: { tenantId, connectorCode } });
}

/** 更新连接器描述符 */
export async function updateConnector(connectorCode: string, tenantId: number, body: ConnectorWriteBody) {
  return apiClient.put(ENDPOINTS.CONNECTOR_UPDATE(connectorCode), body, { params: { tenantId } });
}

/** 自助测试连接器：返回分阶段取数链路 trace */
export async function testConnector(connectorCode: string, tenantId: number, sample: FetchTestSample) {
  const res = await apiClient.post<ApiResponse<FetchTrace>>(
    ENDPOINTS.CONNECTOR_TEST(connectorCode),
    sample,
    { params: { tenantId } },
  );
  return res.data;
}

/** 自助测试 metric 取数：返回分阶段取数链路 trace */
export async function testMetric(metricCode: string, tenantId: number, sample: FetchTestSample) {
  const res = await apiClient.post<ApiResponse<FetchTrace>>(
    ENDPOINTS.METRIC_TEST(metricCode),
    sample,
    { params: { tenantId } },
  );
  return res.data;
}
