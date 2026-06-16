import { useEffect, useState, useMemo } from 'react';
import { Table, Button, Input, Select, Space, message } from 'antd';
import { PlusOutlined, SearchOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useTenantStore } from '@/store/tenantStore';
import { listConnectors, disableConnector } from '@/api/connector';
import { getConnectorColumns } from '@/config/columns/connector';
import { ROUTES, route } from '@/constants/routes';
import { getStatusOptions } from '@/constants/enums';
import type { ConnectorListItem } from '@/types';

export default function ConnectorList() {
  const navigate = useNavigate();
  const { t } = useTranslation('connector');
  const tc = useTranslation('common').t;
  const { currentId, activeList } = useTenantStore();
  const [tenantFilter, setTenantFilter] = useState<number | undefined>(undefined);
  const [connectors, setConnectors] = useState<ConnectorListItem[]>([]);
  // 未选租户时自动用第一个可用租户，确保列表默认有内容
  const tenantId = tenantFilter ?? currentId ?? activeList?.[0]?.id ?? 0;
  const [loading, setLoading] = useState(false);
  const [keyword, setKeyword] = useState('');
  const [statusFilter, setStatusFilter] = useState<string | undefined>(undefined);

  const load = async () => {
    if (!tenantId) return;
    setLoading(true);
    try { const data = await listConnectors(tenantId); setConnectors(data.data ?? []); }
    finally { setLoading(false); }
  };

  useEffect(() => { load(); }, [tenantId]);

  const dataSource = useMemo(() => {
    let result = connectors;
    if (keyword.trim()) {
      const kw = keyword.toLowerCase();
      result = result.filter((c) => c.connectorCode.toLowerCase().includes(kw) || (c.name ?? '').toLowerCase().includes(kw));
    }
    if (statusFilter) {
      result = result.filter((c) => c.status === statusFilter);
    }
    return result;
  }, [connectors, keyword, statusFilter]);

  return (<>
    <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 16 }}>
      <h2>{t('title.list')}</h2>
      <Button type="primary" icon={<PlusOutlined />} onClick={() => navigate(ROUTES.CONNECTOR_NEW)}>{t('action.create')}</Button>
    </div>
    <Space style={{ marginBottom: 16 }}>
      <Select
        placeholder={tc('label.tenant')}
        value={tenantFilter}
        onChange={setTenantFilter}
        allowClear
        options={activeList.map((tenant) => ({ value: tenant.id, label: `${tenant.name} (${tenant.code})` }))}
        style={{ width: 180 }}
      />
      <Input
        prefix={<SearchOutlined />}
        placeholder={t('searchPlaceholder')}
        value={keyword}
        onChange={(e) => setKeyword(e.target.value)}
        allowClear
        style={{ width: 220 }}
      />
      <Select
        placeholder={tc('label.status')}
        value={statusFilter}
        onChange={setStatusFilter}
        allowClear
        options={getStatusOptions(tc)}
        style={{ width: 120 }}
      />
    </Space>
    <Table
      columns={getConnectorColumns(t, tc, async (code) => {
        await disableConnector(code, tenantId);
        message.success(tc('message.disabled'));
        load();
      })}
      dataSource={dataSource}
      rowKey="connectorCode"
      loading={loading}
      scroll={{ y: 'calc(100vh - 312px)' }}
      onRow={(r) => ({ onClick: () => navigate(route(ROUTES.CONNECTOR_DETAIL, { connectorCode: r.connectorCode })), style: { cursor: 'pointer' } })}
    />
  </>);
}
