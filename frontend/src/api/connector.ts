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

/** 取单个连接器完整信息（含 typed descriptor），供编辑态回填 */
export async function getConnector(connectorCode: string, tenantId: number) {
  const res = await apiClient.get<ApiResponse<ConnectorListItem>>(
    ENDPOINTS.CONNECTOR_DETAIL(connectorCode),
    { params: { tenantId } },
  );
  return res.data;
}

/** 禁用连接器（仅 disable，无 enable；X-Actor-Id 由请求拦截器注入） */
export async function disableConnector(connectorCode: string, tenantId: number) {
  return apiClient.post(ENDPOINTS.CONNECTOR_DISABLE(connectorCode), null, { params: { tenantId } });
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
