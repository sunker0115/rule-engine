import { useEffect, useState } from 'react';
import { Drawer, Table, Select, Tag, Space } from 'antd';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { getRuleSessions } from '@/api/evalSession';
import { ROUTES, route } from '@/constants/routes';
import { colorOf, labelOf, getSessionStatusOptions } from '@/constants/enums';
import { formatDateTime } from '@/utils/format';
import type { EvalSessionItem } from '@/types';
import type { ColumnsType } from 'antd/es/table';

interface Props {
  open: boolean;
  onClose: () => void;
  tenantId: number;
  ruleDefinitionId: number;
}

/** 规则近期评估记录抽屉——按规则维度列出历史评估会话，复用会话列表的列与格式 */
export default function RuleSessionsDrawer({ open, onClose, tenantId, ruleDefinitionId }: Props) {
  const navigate = useNavigate();
  const { t } = useTranslation('eval');
  const [sessions, setSessions] = useState<EvalSessionItem[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(10);
  const [status, setStatus] = useState<string | undefined>(undefined);

  useEffect(() => {
    if (!open || !tenantId) return;
    let cancelled = false;
    (async () => {
      setLoading(true);
      try {
        const data = await getRuleSessions(tenantId, ruleDefinitionId, { page, size: pageSize, status });
        if (cancelled) return;
        setSessions(data.items ?? []);
        setTotal(data.total ?? 0);
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => { cancelled = true; };
  }, [open, tenantId, ruleDefinitionId, page, pageSize, status]);

  const columns: ColumnsType<EvalSessionItem> = [
    {
      title: t('session.column.sessionId'), dataIndex: 'sessionId', key: 'sessionId', width: 160,
      render: (v: string) => (
        <a onClick={() => navigate(route(ROUTES.SESSION_DETAIL, { sessionId: v }))}>{v}</a>
      ),
    },
    { title: t('session.column.eventId'), dataIndex: 'eventId', key: 'eventId', width: 180, ellipsis: true },
    { title: t('session.column.subjectId'), dataIndex: 'subjectId', key: 'subjectId', width: 120, ellipsis: true },
    {
      title: t('session.column.finalDecision'), dataIndex: 'finalDecision', key: 'finalDecision', width: 110, ellipsis: true,
      render: (v: string) => v || '-',
    },
    {
      title: t('session.column.status'), dataIndex: 'status', key: 'status', width: 90,
      render: (v: string) => <Tag color={colorOf(getSessionStatusOptions(t), v as never)}>{labelOf(getSessionStatusOptions(t), v as never)}</Tag>,
    },
    { title: t('session.column.occurredAt'), dataIndex: 'startedAt', key: 'startedAt', width: 170, render: (v: string) => formatDateTime(v) },
  ];

  return (
    <Drawer title={t('title.ruleSessions')} open={open} onClose={onClose} width={760}>
      <Space style={{ marginBottom: 16 }}>
        <Select
          placeholder={t('session.filter.status')}
          style={{ width: 140 }}
          allowClear
          value={status}
          options={getSessionStatusOptions(t)}
          onChange={(v) => { setStatus(v || undefined); setPage(1); }}
        />
      </Space>
      <Table
        columns={columns}
        dataSource={sessions}
        rowKey="sessionId"
        size="small"
        loading={loading}
        scroll={{ x: 'max-content' }}
        pagination={{
          current: page,
          pageSize,
          total,
          size: 'small',
          showSizeChanger: true,
          onChange: (p, ps) => { setPage(p); setPageSize(ps); },
        }}
      />
    </Drawer>
  );
}
