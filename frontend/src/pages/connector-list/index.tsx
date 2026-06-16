import { useEffect, useState } from 'react';
import { Table, Button, Input, Select, Space, message, Empty } from 'antd';
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
  const { currentId, activeList, setCurrentById } = useTenantStore();

  // 筛选条件（服务端）
  const [tenantFilter, setTenantFilter] = useState<number | undefined>(undefined);
  const [keyword, setKeyword] = useState('');
  const [statusFilter, setStatusFilter] = useState<string | undefined>(undefined);

  // 分页状态
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(20);
  const [total, setTotal] = useState(0);

  const [connectors, setConnectors] = useState<ConnectorListItem[]>([]);
  const [loading, setLoading] = useState(false);

  // 与其他列表页一致：筛选器选中 > store 当前租户，没选则为 0（守卫不加载）
  const tenantId = tenantFilter ?? currentId ?? 0;

  const load = async () => {
    if (!tenantId) return;
    setLoading(true);
    try {
      const res = await listConnectors({
        tenantId,
        keyword: keyword.trim() || undefined,
        status: statusFilter,
        page,
        size: pageSize,
      });
      setConnectors(res.data?.items ?? []);
      setTotal(res.data?.total ?? 0);
    } finally {
      setLoading(false);
    }
  };

  // 筛选/分页变化时重新加载（单一触发源，无重复请求）
  useEffect(() => { load(); }, [tenantId, keyword, statusFilter, page, pageSize]);

  // 筛选变化时重置页码
  const handleFilterChange = (setter: (v: unknown) => void) => (v: unknown) => {
    setter(v);
    setPage(1);
  };

  return (<>
    <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 16 }}>
      <h2>{t('title.list')}</h2>
      <Button type="primary" icon={<PlusOutlined />} onClick={() => navigate(ROUTES.CONNECTOR_NEW)}>
        {t('action.create')}
      </Button>
    </div>
    <Space style={{ marginBottom: 16 }}>
      <Select
        placeholder={tc('label.tenant')}
        value={tenantFilter ?? currentId ?? undefined}
        onChange={(v) => { setTenantFilter(v as number | undefined); setCurrentById(v as number | undefined); setPage(1); }}
        allowClear
        options={activeList.map((tenant) => ({ value: tenant.id, label: `${tenant.name} (${tenant.code})` }))}
        style={{ width: 180 }}
      />
      <Input
        prefix={<SearchOutlined />}
        placeholder={t('searchPlaceholder')}
        value={keyword}
        onChange={(e) => { setKeyword(e.target.value); setPage(1); }}
        allowClear
        style={{ width: 220 }}
      />
      <Select
        placeholder={tc('label.status')}
        value={statusFilter}
        onChange={handleFilterChange(setStatusFilter as (v: unknown) => void)}
        allowClear
        options={getStatusOptions(tc)}
        style={{ width: 120 }}
      />
    </Space>
    {tenantId ? (
    <Table
      columns={getConnectorColumns(t, tc, async (code) => {
        await disableConnector(code, tenantId);
        message.success(tc('message.disabled'));
        load();
      })}
      dataSource={connectors}
      rowKey="connectorCode"
      loading={loading}
      scroll={{ y: 'calc(100vh - 360px)' }}
      pagination={{
        current: page,
        pageSize,
        total,
        showSizeChanger: true,
        showTotal: (t) => `共 ${t} 条`,
        onChange: (p, ps) => { setPage(p); setPageSize(ps); },
      }}
      onRow={(r) => ({
        onClick: () => navigate(route(ROUTES.CONNECTOR_DETAIL, { connectorCode: r.connectorCode })),
        style: { cursor: 'pointer' },
      })}
    />
    ) : (
      <Empty description={tc('tenant.notSelected')} style={{ marginTop: 80 }}>
        <Button type="primary" onClick={() => navigate('/tenants')}>{tc('tenant.goSelect')}</Button>
      </Empty>
    )}
  </>);
}
