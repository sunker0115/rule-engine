import { useEffect, useState } from 'react';
import { Table, Select, Tag } from 'antd';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useTenantStore } from '@/store/tenantStore';
import { listSessions } from '@/api/evalSession';
import { ROUTES, route } from '@/constants/routes';
import { colorOf, labelOf, SESSION_STATUS_OPTIONS } from '@/constants/enums';
import type { EvalSessionItem } from '@/types';
import type { ColumnsType } from 'antd/es/table';

export default function EvalSessionList() {
  const navigate = useNavigate();
  const { currentId } = useTenantStore();
  const { t } = useTranslation('eval');
  const [sessions, setSessions] = useState<EvalSessionItem[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(20);
  const [searchParams] = useSearchParams();

  const [filters, setFilters] = useState<Record<string, unknown>>(() => {
    const init: Record<string, unknown> = {};
    const sceneCode = searchParams.get('sceneCode');
    if (sceneCode) init.sceneCode = sceneCode;
    const statuses = searchParams.getAll('status');
    if (statuses.length > 0) init.status = statuses.join(',');
    else init.status = 'HIT,BLOCKED'; // 默认只看命中和被拦截
    return init;
  });

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
        <a onClick={(e) => { e.stopPropagation(); navigate(route(ROUTES.SESSION_DETAIL, { sessionId: v })); }}>{v}</a>
      ),
    },
    { title: t('session.column.eventId'), dataIndex: 'eventId', key: 'eventId', width: 180, ellipsis: true },
    { title: t('session.column.sceneCode'), dataIndex: 'sceneCode', key: 'sceneCode', width: 120 },
    {
      title: t('session.column.status'), dataIndex: 'status', key: 'status', width: 80,
      render: (v: string) => <Tag color={colorOf(SESSION_STATUS_OPTIONS, v as never)}>{labelOf(SESSION_STATUS_OPTIONS, v as never)}</Tag>,
    },
    { title: t('session.column.occurredAt'), dataIndex: 'startedAt', key: 'startedAt', width: 180 },
  ];

  return (
    <div>
      <h2 style={{ marginBottom: 16 }}>{t('title.sessionList')}</h2>
      <div style={{ marginBottom: 16, display: 'flex', gap: 8, flexWrap: 'wrap' }}>
        <Select
          placeholder={t('session.filter.status')}
          style={{ width: 120 }}
          allowClear
          options={[...SESSION_STATUS_OPTIONS]}
          onChange={(v) => setFilters((f) => ({ ...f, status: v || undefined }))}
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
