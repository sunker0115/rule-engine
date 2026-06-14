import { useEffect, useState } from 'react';
import { Table, Select, Input, DatePicker, Tag, Space } from 'antd';
import { useTranslation } from 'react-i18next';
import { useTenantStore } from '@/store/tenantStore';
import { listAuditLogs } from '@/api/audit';
import { colorOf, labelOf, AUDIT_ACTION_OPTIONS, AUDIT_TARGET_TYPE_OPTIONS, ACTOR_TYPE_OPTIONS } from '@/constants/enums';
import JsonDiffViewer from '@/components/json-diff-viewer';
import type { AuditLogItem } from '@/types';
import type { ColumnsType } from 'antd/es/table';

const { RangePicker } = DatePicker;

export default function AuditLogList() {
  const { currentId, activeList } = useTenantStore();
  const { t } = useTranslation('audit');
  const [logs, setLogs] = useState<AuditLogItem[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(20);
  const [tenantFilter, setTenantFilter] = useState<number | undefined>(undefined);
  const [filters, setFilters] = useState<Record<string, unknown>>({});
  const tenantId = tenantFilter ?? currentId ?? 0;

  const load = async () => {
    if (!tenantId) return;
    setLoading(true);
    try {
      const data = await listAuditLogs({ tenantId, page, size: pageSize, ...filters });
      setLogs(data.items ?? []);
      setTotal(data.total ?? 0);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { load(); }, [tenantId, page, pageSize, filters]);

  const columns: ColumnsType<AuditLogItem> = [
    { title: 'ID', dataIndex: 'id', key: 'id', width: 60 },
    { title: '租户', dataIndex: 'tenantId', key: 'tenantId', width: 60 },
    {
      title: t('column.actor'), dataIndex: 'actorId', key: 'actorId', width: 100,
    },
    {
      title: t('column.actorType'), dataIndex: 'actorType', key: 'actorType', width: 60,
      render: (v: string) => labelOf(ACTOR_TYPE_OPTIONS, v as never),
    },
    {
      title: t('column.action'), dataIndex: 'action', key: 'action', width: 80,
      render: (v: string) => <Tag color={colorOf(AUDIT_ACTION_OPTIONS, v as never)}>{labelOf(AUDIT_ACTION_OPTIONS, v as never)}</Tag>,
    },
    {
      title: t('column.targetType'), dataIndex: 'resourceType', key: 'resourceType', width: 90,
      render: (v: string) => <Tag>{labelOf(AUDIT_TARGET_TYPE_OPTIONS, v as never)}</Tag>,
    },
    { title: t('column.targetId'), dataIndex: 'resourceId', key: 'resourceId', width: 70 },
    { title: t('column.operatedAt'), dataIndex: 'occurredAt', key: 'occurredAt', width: 170, render: (v: string) => v?.slice(0, 19) },
  ];

  return (
    <div>
      <h2 style={{ marginBottom: 16 }}>{t('title.list')}</h2>
      <Space style={{ marginBottom: 16 }} wrap>
        <Select
          placeholder="租户"
          value={tenantFilter}
          onChange={setTenantFilter}
          allowClear
          options={activeList.map((t) => ({ value: t.id, label: `${t.name} (${t.code})` }))}
          style={{ width: 180 }}
        />
        <Select
          placeholder={t('filter.targetType')}
          style={{ width: 110 }}
          allowClear
          options={[...AUDIT_TARGET_TYPE_OPTIONS]}
          onChange={(v) => setFilters((f) => ({ ...f, resourceType: v || undefined }))}
        />
        <Input
          placeholder={t('filter.targetId')}
          style={{ width: 100 }}
          allowClear
          onChange={(e) => setFilters((f) => ({ ...f, resourceId: e.target.value || undefined }))}
        />
        <Input
          placeholder={t('filter.actor')}
          style={{ width: 120 }}
          allowClear
          onChange={(e) => setFilters((f) => ({ ...f, actorId: e.target.value || undefined }))}
        />
        <Select
          placeholder={t('filter.action')}
          style={{ width: 110 }}
          allowClear
          options={[...AUDIT_ACTION_OPTIONS]}
          onChange={(v) => setFilters((f) => ({ ...f, action: v || undefined }))}
        />
        <RangePicker
          showTime
          style={{ width: 340 }}
          placeholder={[t('filter.from'), t('filter.to')]}
          onChange={(dates) => {
            setFilters((f) => ({
              ...f,
              from: dates?.[0]?.toISOString() || undefined,
              to: dates?.[1]?.toISOString() || undefined,
            }));
          }}
        />
      </Space>
      <Table
        columns={columns}
        dataSource={logs}
        rowKey="id"
        loading={loading}
        scroll={{ x: 900 }}
        pagination={{
          current: page,
          pageSize,
          total,
          showSizeChanger: true,
          showTotal: (total) => `共 ${total} 条`,
          onChange: (p, ps) => { setPage(p); setPageSize(ps); },
        }}
        expandable={{
          expandedRowRender: (record) => (
            <JsonDiffViewer
              before={typeof record.beforeSnapshot === 'string' ? JSON.parse(record.beforeSnapshot) : record.beforeSnapshot}
              after={typeof record.afterSnapshot === 'string' ? JSON.parse(record.afterSnapshot) : record.afterSnapshot}
            />
          ),
          rowExpandable: (record) => !!(record.beforeSnapshot || record.afterSnapshot),
        }}
      />
    </div>
  );
}
