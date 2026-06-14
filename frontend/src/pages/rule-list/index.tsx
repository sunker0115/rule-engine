import { useEffect, useState } from 'react';
import { Table, Button, Modal, Form, Input, Select, message } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import { useNavigate, useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useTenantStore } from '@/store/tenantStore';
import { listRules, createRule } from '@/api/rule';
import { RULE_COLUMNS } from '@/config/columns/rule';
import { ROUTES, route } from '@/constants/routes';
import { RULE_KIND_OPTIONS } from '@/constants/enums';
import type { RuleListItem } from '@/types';

export default function RuleList() {
  const { sceneCode } = useParams<{ sceneCode: string }>();
  const navigate = useNavigate();
  const { currentId } = useTenantStore();
  const { t } = useTranslation('rule');
  const tc = useTranslation('common').t;
  const [rules, setRules] = useState<RuleListItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [confirmLoading, setConfirmLoading] = useState(false);
  const [form] = Form.useForm();

  const load = async () => {
    if (!currentId || !sceneCode) return;
    setLoading(true);
    try { const data = await listRules(currentId, sceneCode); setRules(data.items ?? []); }
    finally { setLoading(false); }
  };

  useEffect(() => { load(); }, [currentId, sceneCode]);

  const handleCreate = async () => {
    const values = await form.validateFields();
    setConfirmLoading(true);
    try {
      await createRule(currentId!, { ...values, sceneCode: sceneCode! });
      message.success(tc('message.createSuccess'));
      setModalOpen(false);
      form.resetFields();
      load();
    } catch { /* handled by interceptor */ }
    finally { setConfirmLoading(false); }
  };

  return (<>
    <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 16 }}>
      <h2>{t('title.list')} — {sceneCode}</h2>
      <Button type="primary" icon={<PlusOutlined />} onClick={() => { form.resetFields(); setModalOpen(true); }}>
        {t('action.create')}
      </Button>
    </div>
    <Table
      columns={RULE_COLUMNS}
      dataSource={rules}
      rowKey="ruleDefinitionId"
      loading={loading}
      onRow={(r) => ({
        onClick: () => navigate(route(ROUTES.RULE_EDITOR, { sceneCode: sceneCode!, ruleId: r.ruleDefinitionId })),
        style: { cursor: 'pointer' },
      })}
    />
    <Modal
      title={t('action.create')}
      open={modalOpen}
      onOk={handleCreate}
      onCancel={() => { setModalOpen(false); form.resetFields(); }}
      confirmLoading={confirmLoading}
    >
      <Form form={form} layout="vertical">
        <Form.Item name="code" label={t('column.code')} rules={[{ required: true, message: tc('validation.required') }]}>
          <Input placeholder={t('column.code')} />
        </Form.Item>
        <Form.Item name="name" label={tc('label.name')} rules={[{ required: true, message: tc('validation.required') }]}>
          <Input />
        </Form.Item>
        <Form.Item name="kind" label={t('column.kind')} initialValue="AST_BOOLEAN">
          <Select options={[...RULE_KIND_OPTIONS]} />
        </Form.Item>
        <Form.Item name="triggerEventTypes" label="Trigger Events">
          <Select mode="tags" placeholder="输入后回车添加" />
        </Form.Item>
      </Form>
    </Modal>
  </>);
}
