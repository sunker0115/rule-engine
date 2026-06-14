import { useEffect, useState } from 'react';
import { Table, Select, Input, DatePicker, Tag } from 'antd';
import { useTranslation } from 'react-i18next';
import { useTenantStore } from '@/store/tenantStore';
import { listAuditLogs } from '@/api/audit';
import { colorOf, labelOf, AUDIT_ACTION_OPTIONS, AUDIT_TARGET_TYPE_OPTIONS, ACTOR_TYPE_OPTIONS } from '@/constants/enums';
import JsonDiffViewer from '@/components/json-diff-viewer';
import type { AuditLogItem } from '@/types';
import type { ColumnsType } from 'antd/es/table';

const { RangePicker } = DatePicker;

export default function AuditLogList() {
  const { currentId } = useTenantStore();
  const { t } = useTranslation('audit');
  const [logs, setLogs] = useState<AuditLogItem[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(20);
  const [filters, setFilters] = useState<Record<string, unknown>>({});

  const load = async () => {
    if (!currentId) return;
    setLoading(true);
    try {
      const data = await listAuditLogs({ tenantId: currentId, page, size: pageSize, ...filters });
      setLogs(data.items ?? []);
      setTotal(data.total ?? 0);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { load(); }, [currentId, page, pageSize, filters]);

  const columns: ColumnsType<AuditLogItem> = [
    {
      title: t('column.actor'), dataIndex: 'actor', key: 'actor', width: 120,
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
      title: t('column.targetType'), dataIndex: 'targetType', key: 'targetType', width: 80,
      render: (v: string) => <Tag>{labelOf(AUDIT_TARGET_TYPE_OPTIONS, v as never)}</Tag>,
    },
    { title: t('column.targetId'), dataIndex: 'targetId', key: 'targetId', width: 80 },
    { title: t('column.operatedAt'), dataIndex: 'operatedAt', key: 'operatedAt', width: 160 },
  ];

  return (
    <div>
      <h2 style={{ marginBottom: 16 }}>{t('title.list')}</h2>
      <div style={{ marginBottom: 16, display: 'flex', gap: 8, flexWrap: 'wrap' }}>
        <Select
          placeholder={t('filter.targetType')}
          style={{ width: 110 }}
          allowClear
          options={[...AUDIT_TARGET_TYPE_OPTIONS]}
          onChange={(v) => setFilters((f) => ({ ...f, targetType: v || undefined }))}
        />
        <Input
          placeholder={t('filter.targetId')}
          style={{ width: 100 }}
          allowClear
          onChange={(e) => setFilters((f) => ({ ...f, targetId: e.target.value || undefined }))}
        />
        <Input
          placeholder={t('filter.actor')}
          style={{ width: 120 }}
          allowClear
          onChange={(e) => setFilters((f) => ({ ...f, actor: e.target.value || undefined }))}
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
      </div>
      <Table
        columns={columns}
        dataSource={logs}
        rowKey={(r) => `${r.targetType}-${r.targetId}-${r.operatedAt}`}
        loading={loading}
        pagination={{
          current: page,
          pageSize,
          total,
          showSizeChanger: true,
          showTotal: (t) => `共 ${t} 条`,
          onChange: (p, ps) => { setPage(p); setPageSize(ps); },
        }}
        expandable={{
          expandedRowRender: (record) => (
            <JsonDiffViewer before={record.beforeSnapshot} after={record.afterSnapshot} />
          ),
          rowExpandable: (record) => !!(record.beforeSnapshot || record.afterSnapshot),
        }}
      />
    </div>
  );
}
