import { useEffect, useState } from 'react';
import { Table, Select, Tag, Space } from 'antd';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useTenantStore } from '@/store/tenantStore';
import { listSessions } from '@/api/evalSession';
import { ROUTES, route } from '@/constants/routes';
import { colorOf, labelOf, SESSION_STATUS_OPTIONS } from '@/constants/enums';
import type { EvalSessionItem } from '@/types';
import type { ColumnsType } from 'antd/es/table';

export default function EvalSessionList() {
  const navigate = useNavigate();
  const { currentId, activeList } = useTenantStore();
  const { t } = useTranslation('eval');
  const tc = useTranslation('common').t;
  const [sessions, setSessions] = useState<EvalSessionItem[]>([]);
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
      const data = await listSessions({ tenantId, page, size: pageSize, ...filters });
      setSessions(data.items ?? []);
      setTotal(data.total ?? 0);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { load(); }, [tenantId, page, pageSize, filters]);

  const columns: ColumnsType<EvalSessionItem> = [
    {
      title: t('session.column.sessionId'), dataIndex: 'sessionId', key: 'sessionId', width: 80,
      render: (v: number) => (
        <a onClick={(e) => { e.stopPropagation(); navigate(route(ROUTES.SESSION_DETAIL, { sessionId: v })); }}>{v}</a>
      ),
    },
    { title: tc('label.tenant'), dataIndex: 'tenantId', key: 'tenantId', width: 60 },
    { title: t('session.column.eventId'), dataIndex: 'eventId', key: 'eventId', width: 180, ellipsis: true },
    { title: t('session.column.sceneCode'), dataIndex: 'sceneCode', key: 'sceneCode', width: 100 },
    { title: '主体', dataIndex: 'subjectId', key: 'subjectId', width: 100 },
    { title: '来源', dataIndex: 'source', key: 'source', width: 60 },
    { title: '模式', dataIndex: 'mode', key: 'mode', width: 60 },
    { title: t('session.column.candidateRuleCount'), dataIndex: 'candidateRuleCount', key: 'candidateRuleCount', width: 70 },
    { title: t('session.column.hitRuleCount'), dataIndex: 'hitRuleCount', key: 'hitRuleCount', width: 70 },
    {
      title: t('session.column.finalDecision'), dataIndex: 'finalDecision', key: 'finalDecision', width: 100,
      render: (v: string) => v || '-',
    },
    {
      title: t('session.column.evalDuration'), dataIndex: 'evalDurationMs', key: 'evalDurationMs', width: 100,
      render: (v: number) => v != null ? v : '-',
    },
    { title: t('session.column.occurredAt'), dataIndex: 'startedAt', key: 'startedAt', width: 180 },
    {
      title: t('session.column.status'), dataIndex: 'status', key: 'status', width: 80,
      render: (v: string) => <Tag color={colorOf(SESSION_STATUS_OPTIONS, v as never)}>{labelOf(SESSION_STATUS_OPTIONS, v as never)}</Tag>,
    },
  ];

  return (
    <div>
      <h2 style={{ marginBottom: 16 }}>{t('title.sessionList')}</h2>
      <Space style={{ marginBottom: 16 }}>
        <Select
          placeholder="租户"
          value={tenantFilter}
          onChange={setTenantFilter}
          allowClear
          options={activeList.map((t) => ({ value: t.id, label: `${t.name} (${t.code})` }))}
          style={{ width: 200 }}
        />
        <Select
          placeholder={t('session.filter.status')}
          style={{ width: 120 }}
          allowClear
          options={[...SESSION_STATUS_OPTIONS]}
          onChange={(v) => setFilters((f) => ({ ...f, status: v || undefined }))}
        />
      </Space>
      <Table
        columns={columns}
        dataSource={sessions}
        rowKey="sessionId"
        loading={loading}
        scroll={{ x: 1400 }}
        pagination={{
          current: page,
          pageSize,
          total,
          showSizeChanger: true,
          showTotal: (t) => `共 ${t} 条`,
          onChange: (p, ps) => { setPage(p); setPageSize(ps); },
        }}
        onRow={(record) => ({
          onClick: () => navigate(route(ROUTES.SESSION_DETAIL, { sessionId: record.sessionId }), { state: { session: record } }),
          style: { cursor: 'pointer' },
        })}
      />
    </div>
  );
}
