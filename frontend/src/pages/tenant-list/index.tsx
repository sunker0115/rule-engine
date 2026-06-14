import { useEffect, useState } from 'react';
import { Table, Input, Select, Switch, message, Space } from 'antd';
import { SearchOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { useTenantStore } from '@/store/tenantStore';
import { STATUS_OPTIONS } from '@/constants/enums';
import apiClient from '@/api/client';
import { ENDPOINTS } from '@/constants/api-endpoints';
import type { ColumnsType } from 'antd/es/table';

interface TenantRow {
  id: number;
  code: string;
  name: string;
  status: string;
}

export default function TenantList() {
  const tc = useTranslation('common').t;
  const { current, searchTenants, setCurrent } = useTenantStore();
  const [list, setList] = useState<TenantRow[]>([]);
  const [loading, setLoading] = useState(false);
  const [keyword, setKeyword] = useState('');
  const [statusFilter, setStatusFilter] = useState<string | undefined>(undefined);

  const doLoad = () => {
    setLoading(true);
    searchTenants(keyword || undefined, statusFilter)
      .then(setList)
      .finally(() => setLoading(false));
  };

  useEffect(() => { doLoad(); }, [keyword, statusFilter]);

  /** 停用当前选中的租户后，Header 需同步刷新 */
  const refreshHeader = () => {
    searchTenants(undefined, 'ACTIVE').then((tenants) => {
      useTenantStore.setState({ activeList: tenants });
      // 如果当前选中的租户被停用了，清空选中
      if (!tenants.find((t) => t.code === current)) {
        setCurrent(tenants[0]?.code ?? null);
      }
    });
  };

  const toggleStatus = async (id: number, enabled: boolean) => {
    await apiClient.put(ENDPOINTS.TENANT_TOGGLE_STATUS(id), null, { params: { enable: enabled } });
    message.success(enabled ? tc('message.enabled') : tc('message.disabled'));
    refreshHeader();
    doLoad();
  };

  const columns: ColumnsType<TenantRow> = [
    { title: tc('label.id'), dataIndex: 'id', key: 'id', width: 80 },
    { title: tc('label.code'), dataIndex: 'code', key: 'code' },
    { title: tc('label.name'), dataIndex: 'name', key: 'name' },
    { title: tc('label.createdAt'), dataIndex: 'createdAt', key: 'createdAt', render: (v: string) => v?.slice(0, 19) ?? '-' },
    {
      title: tc('label.status'), dataIndex: 'status', key: 'status', width: 80,
      render: (_v: string, r: TenantRow) => (
        <Switch
          checked={r.status === 'ACTIVE'}
          onChange={(enabled) => toggleStatus(r.id, enabled)}
          size="small"
        />
      ),
    },
    { title: tc('label.updatedAt'), dataIndex: 'updatedAt', key: 'updatedAt', render: (v: string) => v?.slice(0, 19) ?? '-' },
  ];

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 16 }}>
        <h2 style={{ margin: 0 }}>{tc('title.tenantList')}</h2>
      </div>
      <Space style={{ marginBottom: 16 }}>
        <Input
          prefix={<SearchOutlined />}
          placeholder={tc('label.searchPlaceholder')}
          value={keyword}
          onChange={(e) => setKeyword(e.target.value)}
          allowClear
          style={{ width: 240 }}
        />
        <Select
          placeholder={tc('label.status')}
          value={statusFilter}
          onChange={setStatusFilter}
          allowClear
          options={[...STATUS_OPTIONS]}
          style={{ width: 120 }}
        />
      </Space>
      <Table
        columns={columns}
        dataSource={list}
        rowKey="id"
        loading={loading}
        onRow={(r) => ({
          onClick: () => setCurrent(r.code),
          style: { cursor: 'pointer', background: r.code === current ? '#e6f7ff' : undefined },
        })}
        size="middle"
      />
    </div>
  );
}
