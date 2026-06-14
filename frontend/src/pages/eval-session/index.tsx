import { useEffect, useState } from 'react';
import { Table, Select, Input, DatePicker, Tag } from 'antd';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useTenantStore } from '@/store/tenantStore';
import { listSessions } from '@/api/evalSession';
import { ROUTES, route } from '@/constants/routes';
import { colorOf, labelOf, SESSION_STATUS_OPTIONS, EVENT_SOURCE_OPTIONS } from '@/constants/enums';
import type { EvalSessionItem } from '@/types';
import type { ColumnsType } from 'antd/es/table';

const { RangePicker } = DatePicker;

export default function EvalSessionList() {
  const navigate = useNavigate();
  const { currentId } = useTenantStore();
  const { t } = useTranslation('eval');
  const [sessions, setSessions] = useState<EvalSessionItem[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(20);
  const [filters, setFilters] = useState<Record<string, unknown>>({});

  const load = async () => {
    if (!currentId) return;
    setLoading(true);
    try {
      const data = await listSessions({ tenantId: currentId, page, size: pageSize, ...filters });
      setSessions(data.items ?? []);
      setTotal(data.total ?? 0);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { load(); }, [currentId, page, pageSize, filters]);

  const columns: ColumnsType<EvalSessionItem> = [
    {
      title: t('session.column.sessionId'), dataIndex: 'sessionId', key: 'sessionId', width: 80,
      render: (v: number) => (
        <a onClick={() => navigate(route(ROUTES.SESSION_DETAIL, { sessionId: v }))}>{v}</a>
      ),
    },
    { title: t('session.column.eventId'), dataIndex: 'eventId', key: 'eventId', width: 140, ellipsis: true },
    { title: t('session.column.sceneCode'), dataIndex: 'sceneCode', key: 'sceneCode', width: 100 },
    { title: t('session.column.eventType'), dataIndex: 'eventType', key: 'eventType', width: 100 },
    { title: t('session.column.subjectId'), dataIndex: 'subjectId', key: 'subjectId', width: 100 },
    {
      title: t('session.column.status'), dataIndex: 'status', key: 'status', width: 80,
      render: (v: string) => <Tag color={colorOf(SESSION_STATUS_OPTIONS, v as never)}>{labelOf(SESSION_STATUS_OPTIONS, v as never)}</Tag>,
    },
    { title: t('session.column.finalDecision'), dataIndex: 'finalDecision', key: 'finalDecision', width: 100, render: (v: string) => v || '-' },
    { title: t('session.column.candidateRuleCount'), dataIndex: 'candidateRuleCount', key: 'candidateRuleCount', width: 80 },
    { title: t('session.column.hitRuleCount'), dataIndex: 'hitRuleCount', key: 'hitRuleCount', width: 80 },
    {
      title: t('session.column.source'), dataIndex: 'source', key: 'source', width: 80,
      render: (v: string) => <Tag color={colorOf(EVENT_SOURCE_OPTIONS, v as never)}>{v}</Tag>,
    },
    { title: t('session.column.mode'), dataIndex: 'mode', key: 'mode', width: 60 },
    { title: t('session.column.evalDuration'), dataIndex: 'evalDurationMs', key: 'evalDurationMs', width: 80 },
    { title: t('session.column.occurredAt'), dataIndex: 'occurredAt', key: 'occurredAt', width: 160 },
  ];

  return (
    <div>
      <h2 style={{ marginBottom: 16 }}>{t('title.sessionList')}</h2>
      <div style={{ marginBottom: 16, display: 'flex', gap: 8, flexWrap: 'wrap' }}>
        <Input
          placeholder={t('session.filter.sceneCode')}
          style={{ width: 120 }}
          allowClear
          onChange={(e) => setFilters((f) => ({ ...f, sceneCode: e.target.value || undefined }))}
        />
        <Input
          placeholder={t('session.filter.subjectId')}
          style={{ width: 120 }}
          allowClear
          onChange={(e) => setFilters((f) => ({ ...f, subjectId: e.target.value || undefined }))}
        />
        <Input
          placeholder={t('session.filter.eventId')}
          style={{ width: 180 }}
          allowClear
          onChange={(e) => setFilters((f) => ({ ...f, eventId: e.target.value || undefined }))}
        />
        <Select
          placeholder={t('session.filter.status')}
          style={{ width: 100 }}
          allowClear
          options={[...SESSION_STATUS_OPTIONS]}
          onChange={(v) => setFilters((f) => ({ ...f, status: v || undefined }))}
        />
        <Select
          placeholder={t('session.filter.source')}
          style={{ width: 100 }}
          allowClear
          options={[...EVENT_SOURCE_OPTIONS]}
          onChange={(v) => setFilters((f) => ({ ...f, source: v || undefined }))}
        />
        <RangePicker
          showTime
          style={{ width: 340 }}
          placeholder={[t('session.filter.from'), t('session.filter.to')]}
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
