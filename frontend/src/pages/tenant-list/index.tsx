import { useEffect, useState } from 'react';
import { Table } from 'antd';
import { useTenantStore } from '@/store/tenantStore';
import type { ColumnsType } from 'antd/es/table';

interface TenantRow {
  id: number;
  code: string;
  name: string;
}

const columns: ColumnsType<TenantRow> = [
  { title: 'ID', dataIndex: 'id', key: 'id', width: 80 },
  { title: 'Code', dataIndex: 'code', key: 'code' },
  { title: '名称', dataIndex: 'name', key: 'name' },
];

export default function TenantList() {
  const { list, loadList, setCurrent, current } = useTenantStore();
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    setLoading(true);
    loadList().finally(() => setLoading(false));
  }, [loadList]);

  return (
    <div>
      <h2 style={{ marginBottom: 16 }}>租户列表</h2>
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
