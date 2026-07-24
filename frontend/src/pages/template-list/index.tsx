import { useEffect, useState } from 'react';
import { Table, Button, Modal, Form, Input, Select, message, Space, Tag, Popconfirm } from 'antd';
import { PlusOutlined, EditOutlined, SendOutlined, StopOutlined, ThunderboltOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useTenantStore } from '@/store/tenantStore';
import { listTemplates, createTemplate, publishTemplate, disableTemplate } from '@/api/template';
import { ROUTES, route } from '@/constants/routes';
import type { RuleTemplate } from '@/types/template';
import type { RuleKind } from '@/types';

const STATUS_COLOR: Record<string, string> = { DRAFT: 'blue', PUBLISHED: 'green', DISABLED: 'red' };
const AST_KINDS: RuleKind[] = ['AST_BOOLEAN', 'SCORECARD', 'DECISION_TREE', 'DECISION_TABLE'];

export default function TemplateList() {
  const navigate = useNavigate();
  const { currentId, activeList, setCurrentById } = useTenantStore();
  const { t } = useTranslation('template');
  const tc = useTranslation('common').t;
  const [templates, setTemplates] = useState<RuleTemplate[]>([]);
  const [loading, setLoading] = useState(false);
  const [tenantFilter, setTenantFilter] = useState<number | undefined>(undefined);
  const [modalOpen, setModalOpen] = useState(false);
  const [confirmLoading, setConfirmLoading] = useState(false);
  const [form] = Form.useForm();

  const tenantId = tenantFilter ?? currentId ?? 0;

  const load = async () => {
    if (!tenantId) return;
    setLoading(true);
    try {
      const data = await listTemplates(tenantId);
      setTemplates(data);
    } finally { setLoading(false); }
  };

  useEffect(() => { load(); }, [tenantId]);

  const handleCreate = async () => {
    const values = await form.validateFields();
    setConfirmLoading(true);
    try {
      // 按 kind 播种默认 body 骨架
      const { kind } = values;
      let bodySkeleton: Record<string, unknown>;
      if (kind === 'SCORECARD') {
        bodySkeleton = { type: 'AstBody', conditionAst: { type: 'ScorecardRootNode', conditions: [], threshold: 0 } };
      } else if (kind === 'DECISION_TREE') {
        bodySkeleton = { type: 'AstBody', conditionAst: { type: 'IfNode', condition: { type: 'AndNode', children: [] }, thenBranch: { type: 'DecisionLeafNode', decisionCode: '', category: null }, elseBranch: null } };
      } else if (kind === 'DECISION_TABLE') {
        bodySkeleton = { type: 'AstBody', conditionAst: { type: 'DecisionTableNode', columns: [], rows: [] } };
      } else {
        bodySkeleton = { type: 'AstBody', conditionAst: { type: 'AndNode', children: [] } };
      }
      await createTemplate(currentId!, {
        ...values, bodySkeleton, slots: [], bindings: [],
      });
      message.success(tc('message.createSuccess'));
      setModalOpen(false);
      form.resetFields();
      load();
    } catch { /* interceptor handled */ }
    finally { setConfirmLoading(false); }
  };

  const handlePublish = async (code: string) => {
    await publishTemplate(tenantId, code);
    message.success(tc('message.publishSuccess'));
    load();
  };

  const handleDisable = async (code: string) => {
    await disableTemplate(tenantId, code);
    message.success(t('action.disable'));
    load();
  };

  const columns = [
    { title: t('column.name'), dataIndex: 'name', key: 'name' },
    { title: t('column.code'), dataIndex: 'code', key: 'code' },
    { title: t('column.kind'), dataIndex: 'kind', key: 'kind', width: 150 },
    { title: t('column.version'), dataIndex: 'version', key: 'version', width: 80 },
    { title: t('column.slots'), dataIndex: 'slots', key: 'slots', width: 80, render: (s: unknown[]) => (s ?? []).length },
    {
      title: t('column.status'), dataIndex: 'status', key: 'status', width: 100,
      render: (status: string) => <Tag color={STATUS_COLOR[status]}>{t(`enum.status.${status}`)}</Tag>,
    },
    {
      title: t('column.actions'), key: 'actions', width: 280,
      render: (_: unknown, record: RuleTemplate) => (
        <Space>
          <Button size="small" icon={<EditOutlined />}
            onClick={() => navigate(route(ROUTES.TEMPLATE_EDITOR, { code: record.code }))}>
            {t('action.edit')}
          </Button>
          {record.status === 'DRAFT' && (
            <Popconfirm title={t('action.publishConfirm')} onConfirm={() => handlePublish(record.code)}>
              <Button size="small" type="primary" icon={<SendOutlined />}>{t('action.publish')}</Button>
            </Popconfirm>
          )}
          {record.status === 'PUBLISHED' && (
            <>
              <Button size="small" icon={<ThunderboltOutlined />}
                onClick={() => navigate(route(ROUTES.TEMPLATE_INSTANTIATE, { code: record.code }))}>
                {t('action.instantiate')}
              </Button>
              <Popconfirm title={t('action.disableConfirm')} onConfirm={() => handleDisable(record.code)}>
                <Button size="small" danger icon={<StopOutlined />}>{t('action.disable')}</Button>
              </Popconfirm>
            </>
          )}
        </Space>
      ),
    },
  ];

  return (<>
    <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 16 }}>
      <h2>{t('title.list')}</h2>
      <Button type="primary" icon={<PlusOutlined />} onClick={() => { form.resetFields(); setModalOpen(true); }}>
        {t('action.create')}
      </Button>
    </div>
    <Space style={{ marginBottom: 16 }}>
      <Select
        placeholder={tc('tenant.placeholder')}
        value={tenantFilter ?? currentId ?? undefined}
        onChange={(v) => { setTenantFilter(v); setCurrentById(v); }}
        allowClear
        options={activeList.map((t) => ({ value: t.id, label: `${t.name} (${t.code})` }))}
        style={{ width: 200 }}
      />
    </Space>
    <Table rowKey="id" columns={columns} dataSource={templates} loading={loading} pagination={false} />

    <Modal title={t('action.create')} open={modalOpen} confirmLoading={confirmLoading}
      onOk={handleCreate} onCancel={() => setModalOpen(false)}>
      <Form form={form} layout="vertical">
        <Form.Item name="code" label={t('form.code')} rules={[{ required: true }]}>
          <Input />
        </Form.Item>
        <Form.Item name="name" label={t('form.name')} rules={[{ required: true }]}>
          <Input />
        </Form.Item>
        <Form.Item name="kind" label={t('form.kind')} rules={[{ required: true }]} initialValue="AST_BOOLEAN">
          <Select options={AST_KINDS.map((k) => ({ value: k, label: k }))} />
        </Form.Item>
        <Form.Item name="description" label={t('form.description')}>
          <Input.TextArea rows={2} />
        </Form.Item>
      </Form>
    </Modal>
  </>);
}
