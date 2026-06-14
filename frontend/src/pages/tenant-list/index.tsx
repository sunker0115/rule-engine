import { useEffect, useMemo, useState } from 'react';
import { Table, Input } from 'antd';
import { SearchOutlined } from '@ant-design/icons';
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
  const [keyword, setKeyword] = useState('');

  useEffect(() => {
    setLoading(true);
    loadList().finally(() => setLoading(false));
  }, [loadList]);

  const dataSource = useMemo(() => {
    if (!keyword.trim()) return list;
    const kw = keyword.toLowerCase();
    return list.filter((t) => t.code.toLowerCase().includes(kw) || t.name.toLowerCase().includes(kw));
  }, [list, keyword]);

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 16 }}>
        <h2 style={{ margin: 0 }}>租户列表</h2>
      </div>
      <Input
        prefix={<SearchOutlined />}
        placeholder="搜索 Code 或名称"
        value={keyword}
        onChange={(e) => setKeyword(e.target.value)}
        allowClear
        style={{ width: 280, marginBottom: 16 }}
      />
      <Table
        columns={columns}
        dataSource={dataSource}
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
