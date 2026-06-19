import { useEffect, useMemo, useState } from 'react';
import { Table, Button, Switch, Modal, Alert, message, Space, Form, Input, Select } from 'antd';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useTenantStore } from '@/store/tenantStore';
import { listScheduledTasks, triggerScheduledTask, enableScheduledTask, disableScheduledTask, createIngestionTask, fetchDatasources } from '@/api/scheduledTask';
import { ROUTES, route } from '@/constants/routes';
import type { ScheduledTaskItem } from '@/types';
import type { ColumnsType } from 'antd/es/table';

const OPERATORS = [
  { value: '=', label: '= 等于' },
  { value: '!=', label: '!= 不等于' },
  { value: '>', label: '> 大于' },
  { value: '>=', label: '>= 大于等于' },
  { value: '<', label: '< 小于' },
  { value: '<=', label: '<= 小于等于' },
  { value: 'LIKE', label: 'LIKE 模糊' },
  { value: 'IS NULL', label: 'IS NULL 为空' },
  { value: 'IS NOT NULL', label: 'IS NOT NULL 非空' },
];

// 把动态过滤条件拼成 WHERE 追加片段；纯数字不加引号，其余加单引号
function buildExtraWhere(conditions: Array<{ field: string; op: string; value?: string }> | undefined): string {
  if (!conditions?.length) return '';
  const parts = conditions
    .filter((c) => c?.field?.trim())
    .map((c) => {
      const field = c.field.trim();
      const op = c.op ?? '=';
      if (op === 'IS NULL' || op === 'IS NOT NULL') return `${field} ${op}`;
      const raw = (c.value ?? '').trim();
      const val = /^-?\d+(\.\d+)?$/.test(raw) ? raw : `'${raw}'`;
      return `${field} ${op} ${val}`;
    });
  return parts.length ? '\n  AND ' + parts.join('\n  AND ') : '';
}

export default function ScheduledTaskList() {
  const navigate = useNavigate();
  const { currentId, activeList, setCurrentById } = useTenantStore();
  const { t } = useTranslation('scheduledTask');
  const tc = useTranslation('common').t;
  const [tasks, setTasks] = useState<ScheduledTaskItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [triggering, setTriggering] = useState<number | null>(null);
  const [createOpen, setCreateOpen] = useState(false);
  const [createForm] = Form.useForm();
  const [datasources, setDatasources] = useState<string[]>([]);
  const [tenantFilter, setTenantFilter] = useState<number | undefined>(undefined);

  const tenantId = tenantFilter ?? currentId ?? 0;

  // 已知任务类型 label，未知类型兜底显示原始串（RETENTION/ALARM 等新类型无需改前端）
  const taskTypeLabels = useMemo<Record<string, string>>(() => ({
    TRIGGER: t('type.trigger'),
    OUTCOME_INGESTION: t('type.ingestion'),
  }), [t]);

  const load = async () => {
    if (!tenantId) return;
    setLoading(true);
    try {
      const data = await listScheduledTasks(tenantId);
      setTasks(data ?? []);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { load(); }, [tenantId]);

  const handleTrigger = async (task: ScheduledTaskItem) => {
    Modal.confirm({
      title: t('action.trigger'),
      content: t('execution.triggerConfirm', { name: task.name }),
      onOk: async () => {
        if (!tenantId) return;
        setTriggering(task.id);
        try {
          await triggerScheduledTask(tenantId, task.id);
          message.success(t('triggerSuccess'));
          load();
        } finally {
          setTriggering(null);
        }
      },
    });
  };

  const handleStatusToggle = async (task: ScheduledTaskItem, checked: boolean) => {
    if (!tenantId) return;
    if (checked) await enableScheduledTask(tenantId, task.id);
    else await disableScheduledTask(tenantId, task.id);
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
        <Space>
          <Select
            placeholder={tc('label.tenant')}
            value={tenantFilter ?? currentId ?? undefined}
            onChange={(v: number) => { setTenantFilter(v); setCurrentById(v); }}
            allowClear
            options={activeList.map((ten) => ({ value: ten.id, label: `${ten.name} (${ten.code})` }))}
            style={{ width: 200 }}
          />
          <Button type="primary" onClick={async () => {
            createForm.resetFields();
            setCreateOpen(true);
            const names = await fetchDatasources().catch(() => []);
            setDatasources(names);
          }}>
            {t('action.createIngestion')}
          </Button>
        </Space>
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
          onFinish={async (values: {
            code: string; name: string; cron: string;
            datasource: string; tableName: string;
            conditions?: Array<{ field: string; op: string; value?: string }>;
            limitRows?: number | string;
          }) => {
            if (!tenantId) {
              message.error(t('create.selectTenant'));
              return;
            }
            const limit = Number(values.limitRows) || 1000;
            const extraWhere = buildExtraWhere(values.conditions);
            const sql =
              `SELECT event_id, outcome_label, outcome_value, labeled_at\n` +
              `FROM ${values.tableName}\n` +
              `WHERE tenant_id = :tenantId\n` +
              `  AND (:watermark IS NULL OR labeled_at > :watermark)${extraWhere}\n` +
              `ORDER BY labeled_at ASC LIMIT ${limit}`;
            try {
              await createIngestionTask({
                tenantId,
                code: values.code,
                name: values.name,
                cron: values.cron,
                datasource: values.datasource,
                sql,
              });
              message.success(t('create.createSuccess'));
              setCreateOpen(false);
              load();
            } catch (err: unknown) {
              const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message;
              message.error(msg ?? t('create.createFailed'));
            }
          }}
        >
          {/* 任务编码 */}
          <Form.Item
            label={t('create.field.code')}
            name="code"
            rules={[{ required: true, message: t('create.field.codeRequired') }]}
            extra={t('create.field.codeExtra')}
          >
            <Input placeholder="fraud-ingest-daily" />
          </Form.Item>
          {/* 任务名称 */}
          <Form.Item
            label={t('create.field.name')}
            name="name"
            rules={[{ required: true, message: t('create.field.nameRequired') }]}
          >
            <Input />
          </Form.Item>
          {/* Cron 表达式 */}
          <Form.Item
            label={t('create.field.cron')}
            name="cron"
            rules={[{ required: true, message: t('create.field.cronRequired') }]}
            extra={t('create.field.cronExtra')}
          >
            <Input placeholder="0 0 2 * * *" />
          </Form.Item>
          {/* 数据源 Select */}
          <Form.Item
            label={t('create.field.datasource')}
            name="datasource"
            rules={[{ required: true, message: t('create.field.datasourceRequired') }]}
            extra={t('create.field.datasourceExtra')}
          >
            <Select
              placeholder={t('create.field.datasourcePlaceholder')}
              options={datasources.map((n) => ({ value: n, label: n }))}
              showSearch
              allowClear
            />
          </Form.Item>
          {/* 标签表名 */}
          <Form.Item
            label={t('create.field.tableName')}
            name="tableName"
            rules={[{ required: true, message: t('create.field.tableNameRequired') }]}
            extra={t('create.field.tableNameExtra')}
          >
            <Input placeholder="biz_fraud_label" />
          </Form.Item>
          {/* 附加过滤条件（可选，动态条件构建器） */}
          <Form.Item label={t('create.field.conditions')} extra={t('create.field.conditionsExtra')}>
            <Form.List name="conditions">
              {(fields, { add, remove }) => (
                <>
                  {fields.map(({ key, name, ...restField }) => (
                    <Space key={key} style={{ display: 'flex', marginBottom: 8 }} align="baseline">
                      <Form.Item {...restField} name={[name, 'field']} noStyle>
                        <Input placeholder={t('create.field.conditionFieldPlaceholder')} style={{ width: 140 }} />
                      </Form.Item>
                      <Form.Item {...restField} name={[name, 'op']} noStyle initialValue="=">
                        <Select options={OPERATORS} style={{ width: 150 }} />
                      </Form.Item>
                      <Form.Item noStyle shouldUpdate>
                        {({ getFieldValue }) => {
                          const op = getFieldValue(['conditions', name, 'op']);
                          const noValue = op === 'IS NULL' || op === 'IS NOT NULL';
                          return (
                            <Form.Item {...restField} name={[name, 'value']} noStyle>
                              <Input
                                placeholder={noValue ? '' : t('create.field.conditionValuePlaceholder')}
                                disabled={noValue}
                                style={{ width: 160 }}
                              />
                            </Form.Item>
                          );
                        }}
                      </Form.Item>
                      <Button type="text" danger onClick={() => remove(name)}>✕</Button>
                    </Space>
                  ))}
                  <Button type="dashed" onClick={() => add()} style={{ width: '100%' }}>
                    + {t('create.field.addCondition')}
                  </Button>
                </>
              )}
            </Form.List>
          </Form.Item>
          {/* 每批行数上限 */}
          <Form.Item
            label={t('create.field.limitRows')}
            name="limitRows"
            initialValue={1000}
            extra={t('create.field.limitRowsExtra')}
          >
            <Input type="number" min={1} max={10000} />
          </Form.Item>
          {/* 预览 SQL */}
          <Form.Item noStyle shouldUpdate>
            {({ getFieldsValue }) => {
              const { tableName, conditions, limitRows } = getFieldsValue();
              if (!tableName) return null;
              const limit = Number(limitRows) || 1000;
              const extraWhere = buildExtraWhere(conditions as Array<{ field: string; op: string; value?: string }>);
              const preview =
                `SELECT event_id, outcome_label, outcome_value, labeled_at\n` +
                `FROM ${tableName as string}\n` +
                `WHERE tenant_id = :tenantId\n` +
                `  AND (:watermark IS NULL OR labeled_at > :watermark)${extraWhere}\n` +
                `ORDER BY labeled_at ASC LIMIT ${limit}`;
              return (
                <Form.Item label={t('create.field.sqlPreview')}>
                  <pre style={{
                    background: '#f5f5f5', padding: 8, borderRadius: 4,
                    fontSize: 12, margin: 0, whiteSpace: 'pre-wrap', wordBreak: 'break-all',
                  }}>
                    {preview}
                  </pre>
                </Form.Item>
              );
            }}
          </Form.Item>
          <Form.Item>
            <Button type="primary" htmlType="submit">{t('create.submit')}</Button>
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
