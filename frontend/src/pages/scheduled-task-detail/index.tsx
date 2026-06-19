import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { Card, Table, Button, Tag, Space, Descriptions, message } from 'antd';
import { useTranslation } from 'react-i18next';
import { useTenantStore } from '@/store/tenantStore';
import { getScheduledTask, listScheduledTaskExecutions, triggerScheduledTask } from '@/api/scheduledTask';
import { colorOf, labelOf, getScheduledTaskExecStatusOptions } from '@/constants/enums';
import { formatDateTime } from '@/utils/format';
import type { ScheduledTaskItem, ScheduledTaskExecutionItem } from '@/types';
import type { ColumnsType } from 'antd/es/table';

export default function ScheduledTaskDetail() {
  const { taskId } = useParams<{ taskId: string }>();
  const navigate = useNavigate();
  const { currentId } = useTenantStore();
  const { t } = useTranslation('scheduledTask');
  const tc = useTranslation('common').t;
  const execStatusOpts = useMemo(() => getScheduledTaskExecStatusOptions(t), [t]);
  const [task, setTask] = useState<ScheduledTaskItem | null>(null);
  const [executions, setExecutions] = useState<ScheduledTaskExecutionItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [triggering, setTriggering] = useState(false);
  const pollingRef = useRef<ReturnType<typeof setInterval> | null>(null);

  // 已知任务类型 label，未知类型兜底显示原始串
  const taskTypeLabels = useMemo<Record<string, string>>(() => ({
    TRIGGER: t('type.trigger'),
    OUTCOME_INGESTION: t('type.ingestion'),
  }), [t]);

  const loadExecutions = useCallback(async () => {
    if (!currentId || !taskId) return;
    const data = await listScheduledTaskExecutions(currentId, Number(taskId));
    setExecutions(data ?? []);
  }, [currentId, taskId]);

  const load = useCallback(async () => {
    if (!currentId || !taskId) return;
    setLoading(true);
    try {
      const [detail] = await Promise.all([
        getScheduledTask(currentId, Number(taskId)),
        loadExecutions(),
      ]);
      setTask(detail ?? null);
    } finally {
      setLoading(false);
    }
  }, [currentId, taskId, loadExecutions]);

  useEffect(() => { load(); }, [load]);

  // 存在 RUNNING 执行记录时每 5s 轮询刷新
  useEffect(() => {
    const hasRunning = executions.some((e) => e.status === 'RUNNING');
    if (hasRunning && !pollingRef.current) {
      pollingRef.current = setInterval(loadExecutions, 5000);
    } else if (!hasRunning && pollingRef.current) {
      clearInterval(pollingRef.current);
      pollingRef.current = null;
    }
    return () => {
      if (pollingRef.current) { clearInterval(pollingRef.current); pollingRef.current = null; }
    };
  }, [executions, loadExecutions]);

  const handleTrigger = async () => {
    if (!currentId || !taskId) return;
    setTriggering(true);
    try {
      await triggerScheduledTask(currentId, Number(taskId));
      message.success(t('triggerSuccess'));
      loadExecutions();
    } finally {
      setTriggering(false);
    }
  };

  const columns: ColumnsType<ScheduledTaskExecutionItem> = [
    { title: t('execution.column.id'), dataIndex: 'id', key: 'id', width: 80 },
    { title: t('execution.column.triggerAt'), dataIndex: 'triggerAt', key: 'triggerAt', render: (v: string) => formatDateTime(v) },
    { title: t('execution.column.finishedAt'), dataIndex: 'finishedAt', key: 'finishedAt', render: (v: string) => formatDateTime(v) },
    { title: t('execution.column.processedCount'), dataIndex: 'processedCount', key: 'processedCount', width: 80 },
    { title: t('execution.column.successCount'), dataIndex: 'successCount', key: 'successCount', width: 80 },
    { title: t('execution.column.errorCount'), dataIndex: 'errorCount', key: 'errorCount', width: 80 },
    {
      title: t('execution.column.status'), dataIndex: 'status', key: 'status', width: 90,
      render: (v: string) => <Tag color={colorOf(execStatusOpts, v as never)}>{labelOf(execStatusOpts, v as never)}</Tag>,
    },
    { title: t('execution.column.errorSummary'), dataIndex: 'errorSummary', key: 'errorSummary', ellipsis: true, render: (v: string) => v || '-' },
  ];

  return (
    <div>
      <Space style={{ marginBottom: 16 }}>
        <Button onClick={() => navigate(-1)}>{tc('button.back')}</Button>
        <h2 style={{ margin: 0 }}>{t('title.detail')} — #{taskId}</h2>
        <Button type="primary" loading={triggering} onClick={handleTrigger}>{t('action.trigger')}</Button>
      </Space>

      <Card title={t('detail.basicInfo')} loading={loading}>
        {task && (
          <Descriptions column={2} size="small" bordered>
            <Descriptions.Item label={t('column.code')}>{task.code}</Descriptions.Item>
            <Descriptions.Item label={t('column.name')}>{task.name}</Descriptions.Item>
            <Descriptions.Item label={t('column.taskType')}>{taskTypeLabels[task.taskType] ?? task.taskType}</Descriptions.Item>
            <Descriptions.Item label={t('column.cronExpr')}>{task.cron || '-'}</Descriptions.Item>
            <Descriptions.Item label={t('column.status')}>
              <Tag color={task.status === 'ACTIVE' ? 'green' : 'default'}>
                {task.status === 'ACTIVE' ? t('enum.status.ACTIVE') : task.status === 'DISABLED' ? t('enum.status.DISABLED') : task.status}
              </Tag>
            </Descriptions.Item>
            <Descriptions.Item label={tc('label.createdAt')}>{formatDateTime(task.createdAt)}</Descriptions.Item>
            <Descriptions.Item label={tc('label.updatedAt')}>{formatDateTime(task.updatedAt)}</Descriptions.Item>
          </Descriptions>
        )}
      </Card>

      {task && (
        <Card title={t('detail.config')} style={{ marginTop: 16 }}>
          {/* 配置去中心化:通用 JSON 渲染,不绑定具体 config 形状(TRIGGER/OUTCOME_INGESTION/未来类型同一渲染) */}
          <pre style={{ margin: 0, whiteSpace: 'pre-wrap', wordBreak: 'break-all' }}>
            {task.config == null ? '-' : JSON.stringify(task.config, null, 2)}
          </pre>
        </Card>
      )}

      <Card title={t('execution.title')} style={{ marginTop: 16 }}>
        <Table
          columns={columns}
          dataSource={executions}
          rowKey="id"
          loading={loading}
          pagination={false}
        />
      </Card>
    </div>
  );
}
