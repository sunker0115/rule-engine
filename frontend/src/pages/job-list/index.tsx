import { useEffect, useState } from 'react';
import { Table, Button, Switch, Modal, Alert, message, Space } from 'antd';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useTenantStore } from '@/store/tenantStore';
import { listJobs, triggerJob, enableJob, disableJob } from '@/api/job';
import { ROUTES, route } from '@/constants/routes';
import type { JobItem } from '@/types';
import type { ColumnsType } from 'antd/es/table';

export default function JobList() {
  const navigate = useNavigate();
  const { currentId } = useTenantStore();
  const { t } = useTranslation('job');
  const tc = useTranslation('common').t;
  const [jobs, setJobs] = useState<JobItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [triggering, setTriggering] = useState<number | null>(null);

  const load = async () => {
    if (!currentId) return;
    setLoading(true);
    try {
      const data = await listJobs(currentId);
      setJobs(data.data ?? []);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { load(); }, [currentId]);

  const handleTrigger = async (job: JobItem) => {
    Modal.confirm({
      title: t('action.trigger'),
      content: t('execution.triggerConfirm', { name: job.name }),
      onOk: async () => {
        if (!currentId) return;
        setTriggering(job.id);
        try {
          await triggerJob(currentId, job.id);
          message.success(t('triggerSuccess'));
          load();
        } finally {
          setTriggering(null);
        }
      },
    });
  };

  const handleStatusToggle = async (job: JobItem, checked: boolean) => {
    if (!currentId) return;
    if (checked) await enableJob(currentId, job.id);
    else await disableJob(currentId, job.id);
    message.success(checked ? tc('message.enabled') : tc('message.disabled'));
    load();
  };

  const columns: ColumnsType<JobItem> = [
    { title: t('column.name'), dataIndex: 'name', key: 'name', ellipsis: true },
    { title: t('column.code'), dataIndex: 'code', key: 'code', ellipsis: true },
    { title: t('column.sceneCode'), dataIndex: 'sceneCode', key: 'sceneCode', ellipsis: true },
    { title: t('column.eventType'), dataIndex: 'eventType', key: 'eventType', ellipsis: true },
    { title: t('column.cronExpr'), dataIndex: 'cronExpression', key: 'cronExpression', ellipsis: true, render: (v: string) => v || '-' },
    {
      title: t('column.status'), dataIndex: 'status', key: 'status', width: 80,
      render: (v: string, record: JobItem) => (
        <Switch
          checked={v === 'ACTIVE'}
          checkedChildren={tc('message.enabled')}
          unCheckedChildren={tc('message.disabled')}
          onChange={(checked) => handleStatusToggle(record, checked)}
        />
      ),
    },
    {
      title: t('column.subjectQueryType'), dataIndex: 'subjectQuery', key: 'subjectQueryType', ellipsis: true,
      render: (v: JobItem['subjectQuery']) => v?.type || '-',
    },
    {
      title: t('column.actions'), key: 'actions', width: 160,
      render: (_: unknown, record: JobItem) => (
        <Space>
          <Button size="small" loading={triggering === record.id} onClick={() => handleTrigger(record)}>
            {t('action.trigger')}
          </Button>
          <Button size="small" onClick={() => navigate(route(ROUTES.JOB_DETAIL, { jobId: record.id }))}>
            {t('action.viewDetail')}
          </Button>
        </Space>
      ),
    },
  ];

  return (
    <div>
      <h2 style={{ marginBottom: 16 }}>{t('title.list')}</h2>
      <Alert message={t('notice')} type="info" showIcon style={{ marginBottom: 16 }} />
      <Table
        columns={columns}
        dataSource={jobs}
        rowKey="id"
        loading={loading}
        pagination={false}
        scroll={{ y: 'calc(100vh - 260px)' }}
      />
    </div>
  );
}
