import { useEffect, useState, useRef, useCallback, useMemo } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { Card, Table, Button, Tag, Space } from 'antd';
import { useTranslation } from 'react-i18next';
import { useTenantStore } from '@/store/tenantStore';
import { getJobExecutions } from '@/api/job';
import { colorOf, labelOf, getJobExecStatusOptions } from '@/constants/enums';
import type { JobExecutionItem } from '@/types';
import type { ColumnsType } from 'antd/es/table';

export default function JobDetail() {
  const { jobId } = useParams<{ jobId: string }>();
  const navigate = useNavigate();
  const { currentId } = useTenantStore();
  const { t } = useTranslation('job');
  const tc = useTranslation('common').t;
  const jobExecStatusOpts = useMemo(() => getJobExecStatusOptions(t), [t]);
  const [executions, setExecutions] = useState<JobExecutionItem[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(20);
  const pollingRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const load = useCallback(async () => {
    if (!currentId || !jobId) return;
    setLoading(true);
    try {
      const data = await getJobExecutions(currentId, Number(jobId), { page, size: pageSize });
      setExecutions(data.items ?? []);
      setTotal(data.total ?? 0);
    } finally {
      setLoading(false);
    }
  }, [currentId, jobId, page, pageSize]);

  useEffect(() => { load(); }, [load]);

  // 如果存在 RUNNING 状态的执行记录，每 5s 轮询刷新
  useEffect(() => {
    const hasRunning = executions.some((e) => e.status === 'RUNNING');
    if (hasRunning && !pollingRef.current) {
      pollingRef.current = setInterval(load, 5000);
    } else if (!hasRunning && pollingRef.current) {
      clearInterval(pollingRef.current);
      pollingRef.current = null;
    }
    return () => {
      if (pollingRef.current) { clearInterval(pollingRef.current); pollingRef.current = null; }
    };
  }, [executions, load]);

  const columns: ColumnsType<JobExecutionItem> = [
    { title: t('execution.column.id'), dataIndex: 'id', key: 'id', width: 80 },
    { title: t('execution.column.triggerAt'), dataIndex: 'triggerAt', key: 'triggerAt', width: 160 },
    { title: t('execution.column.finishedAt'), dataIndex: 'finishedAt', key: 'finishedAt', width: 160, render: (v: string) => v || '-' },
    { title: t('execution.column.subjectCount'), dataIndex: 'subjectCount', key: 'subjectCount', width: 70 },
    { title: t('execution.column.successCount'), dataIndex: 'successCount', key: 'successCount', width: 70 },
    { title: t('execution.column.errorCount'), dataIndex: 'errorCount', key: 'errorCount', width: 70 },
    {
      title: t('execution.column.status'), dataIndex: 'status', key: 'status', width: 90,
      render: (v: string) => <Tag color={colorOf(jobExecStatusOpts, v as never)}>{labelOf(jobExecStatusOpts, v as never)}</Tag>,
    },
    { title: t('execution.column.errorSummary'), dataIndex: 'errorSummary', key: 'errorSummary', ellipsis: true, render: (v: string) => v || '-' },
  ];

  return (
    <div>
      <Space style={{ marginBottom: 16 }}>
        <Button onClick={() => navigate(-1)}>{tc('button.back')}</Button>
        <h2 style={{ margin: 0 }}>{t('title.detail')} — Job #{jobId}</h2>
      </Space>
      <Card title={t('execution.title')} style={{ marginTop: 16 }}>
        <Table
          columns={columns}
          dataSource={executions}
          rowKey="id"
          loading={loading}
          pagination={{
            current: page,
            pageSize,
            total,
            showSizeChanger: true,
            showTotal: (total) => tc('label.paginationTotal', { total }),
            onChange: (p, ps) => { setPage(p); setPageSize(ps); },
          }}
        />
      </Card>
    </div>
  );
}
