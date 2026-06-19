import { useEffect, useMemo, useState } from 'react';
import { Table, Button, Switch, Modal, Alert, message, Space, Form, Input } from 'antd';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useTenantStore } from '@/store/tenantStore';
import { listScheduledTasks, triggerScheduledTask, enableScheduledTask, disableScheduledTask, createIngestionTask } from '@/api/scheduledTask';
import type { CreateIngestionTaskParams } from '@/api/scheduledTask';
import { ROUTES, route } from '@/constants/routes';
import type { ScheduledTaskItem } from '@/types';
import type { ColumnsType } from 'antd/es/table';

export default function ScheduledTaskList() {
  const navigate = useNavigate();
  const { currentId } = useTenantStore();
  const { t } = useTranslation('scheduledTask');
  const tc = useTranslation('common').t;
  const [tasks, setTasks] = useState<ScheduledTaskItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [triggering, setTriggering] = useState<number | null>(null);
  const [createOpen, setCreateOpen] = useState(false);
  const [createForm] = Form.useForm();

  // 已知任务类型 label，未知类型兜底显示原始串（RETENTION/ALARM 等新类型无需改前端）
  const taskTypeLabels = useMemo<Record<string, string>>(() => ({
    TRIGGER: t('type.trigger'),
    OUTCOME_INGESTION: t('type.ingestion'),
  }), [t]);

  const load = async () => {
    if (!currentId) return;
    setLoading(true);
    try {
      const data = await listScheduledTasks(currentId);
      setTasks(data ?? []);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { load(); }, [currentId]);

  const handleTrigger = async (task: ScheduledTaskItem) => {
    Modal.confirm({
      title: t('action.trigger'),
      content: t('execution.triggerConfirm', { name: task.name }),
      onOk: async () => {
        if (!currentId) return;
        setTriggering(task.id);
        try {
          await triggerScheduledTask(currentId, task.id);
          message.success(t('triggerSuccess'));
          load();
        } finally {
          setTriggering(null);
        }
      },
    });
  };

  const handleStatusToggle = async (task: ScheduledTaskItem, checked: boolean) => {
    if (!currentId) return;
    if (checked) await enableScheduledTask(currentId, task.id);
    else await disableScheduledTask(currentId, task.id);
    message.success(checked ? tc('message.enabled') : tc('message.disabled'));
    load();
  };

  const columns: ColumnsType<ScheduledTaskItem> = [
    { title: t('column.code'), dataIndex: 'code', key: 'code', ellipsis: true },
    { title: t('column.name'), dataIndex: 'name', key: 'name', ellipsis: true },
    {
      title: t('column.taskType'), dataIndex: 'taskType', key: 'taskType', ellipsis: true,
      render: (v: string) => taskTypeLabels[v] ?? v,
    },
    { title: t('column.cronExpr'), dataIndex: 'cron', key: 'cron', ellipsis: true, render: (v: string) => v || '-' },
    {
      title: t('column.status'), dataIndex: 'status', key: 'status', width: 120,
      render: (v: string, record: ScheduledTaskItem) => (
        <Space size={4}>
          <Switch
            checked={v === 'ACTIVE'}
            checkedChildren={t('enum.status.ACTIVE')}
            unCheckedChildren={t('enum.status.DISABLED')}
            onChange={(checked) => handleStatusToggle(record, checked)}
          />
        </Space>
      ),
    },
    {
      title: t('column.actions'), key: 'actions', width: 160,
      render: (_: unknown, record: ScheduledTaskItem) => (
        <Space>
          <Button size="small" loading={triggering === record.id} onClick={() => handleTrigger(record)}>
            {t('action.trigger')}
          </Button>
          <Button size="small" onClick={() => navigate(route(ROUTES.SCHEDULED_TASK_DETAIL, { taskId: record.id }))}>
            {t('action.viewDetail')}
          </Button>
        </Space>
      ),
    },
  ];

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
        <h2 style={{ margin: 0 }}>{t('title.list')}</h2>
        <Button type="primary" onClick={() => { setCreateOpen(true); createForm.resetFields(); }}>
          {t('action.createIngestion')}
        </Button>
      </div>
      <Alert message={t('notice')} type="info" showIcon style={{ marginBottom: 16 }} />
      <Table
        columns={columns}
        dataSource={tasks}
        rowKey="id"
        loading={loading}
        pagination={false}
        scroll={{ y: 'calc(100vh - 260px)' }}
      />
      <Modal
        title={t('create.title')}
        open={createOpen}
        onCancel={() => setCreateOpen(false)}
        footer={null}
        width={640}
        destroyOnClose
      >
        <Form
          form={createForm}
          layout="vertical"
          onFinish={async (values: Omit<CreateIngestionTaskParams, 'tenantId'>) => {
            if (!currentId) {
              message.error(t('create.selectTenant'));
              return;
            }
            try {
              await createIngestionTask({ tenantId: currentId, ...values });
              message.success(t('create.createSuccess'));
              setCreateOpen(false);
              load();
            } catch (err: unknown) {
              const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message;
              message.error(msg ?? t('create.createFailed'));
            }
          }}
        >
          <Form.Item
            label={t('create.field.code')}
            name="code"
            rules={[{ required: true, message: t('create.field.codeRequired') }]}
            extra={t('create.field.codeExtra')}
          >
            <Input placeholder="fraud-ingest-daily" />
          </Form.Item>
          <Form.Item
            label={t('create.field.name')}
            name="name"
            rules={[{ required: true, message: t('create.field.nameRequired') }]}
          >
            <Input />
          </Form.Item>
          <Form.Item
            label={t('create.field.cron')}
            name="cron"
            rules={[{ required: true, message: t('create.field.cronRequired') }]}
            extra={t('create.field.cronExtra')}
          >
            <Input placeholder="0 0 2 * * *" />
          </Form.Item>
          <Form.Item
            label={t('create.field.datasource')}
            name="datasource"
            rules={[{ required: true, message: t('create.field.datasourceRequired') }]}
            extra={t('create.field.datasourceExtra')}
          >
            <Input />
          </Form.Item>
          <Form.Item
            label={t('create.field.sql')}
            name="sql"
            rules={[{ required: true, message: t('create.field.sqlRequired') }]}
            extra={t('create.field.sqlExtra')}
          >
            <Input.TextArea
              rows={4}
              placeholder={'SELECT event_id, outcome_label, outcome_value, labeled_at\nFROM biz_label\nWHERE tenant_id = :tenantId\n  AND (:watermark IS NULL OR labeled_at > :watermark)\nORDER BY labeled_at ASC LIMIT 1000'}
            />
          </Form.Item>
          <Form.Item>
            <Button type="primary" htmlType="submit">{t('create.submit')}</Button>
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
