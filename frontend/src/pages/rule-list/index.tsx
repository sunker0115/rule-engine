import { useEffect, useState, useMemo } from 'react';
import { Table, Button, Modal, Form, Input, Select, message, Space } from 'antd';
import { PlusOutlined, SearchOutlined } from '@ant-design/icons';
import { useNavigate, useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useTenantStore } from '@/store/tenantStore';
import { listRules, createRule } from '@/api/rule';
import { RULE_COLUMNS } from '@/config/columns/rule';
import { ROUTES, route } from '@/constants/routes';
import { RULE_KIND_OPTIONS, RULE_STATUS_OPTIONS } from '@/constants/enums';
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
  const [statusFilter, setStatusFilter] = useState<string | undefined>(undefined);
  const [keyword, setKeyword] = useState('');
  const [form] = Form.useForm();

  const load = async () => {
    if (!currentId || !sceneCode) return;
    setLoading(true);
    try {
      const params: Record<string, unknown> = {};
      if (statusFilter) params.status = statusFilter;
      const data = await listRules(currentId, sceneCode, params);
      setRules(data.items ?? []);
    } finally { setLoading(false); }
  };

  useEffect(() => { load(); }, [currentId, sceneCode, statusFilter]);

  // 客户端关键词过滤（名称或 code 模糊匹配）
  const filtered = useMemo(() => {
    if (!keyword.trim()) return rules;
    const kw = keyword.toLowerCase();
    return rules.filter((r) =>
      r.name.toLowerCase().includes(kw) || r.code.toLowerCase().includes(kw),
    );
  }, [rules, keyword]);

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
    <Space style={{ marginBottom: 16 }}>
      <Input
        prefix={<SearchOutlined />}
        placeholder="搜索名称或 Code"
        value={keyword}
        onChange={(e) => setKeyword(e.target.value)}
        allowClear
        style={{ width: 240 }}
      />
      <Select
        placeholder={t('column.status')}
        value={statusFilter}
        onChange={setStatusFilter}
        allowClear
        options={[...RULE_STATUS_OPTIONS]}
        style={{ width: 130 }}
      />
    </Space>
    <Table
      columns={RULE_COLUMNS}
      dataSource={filtered}
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
        <Form.Item name="code" label="Code" rules={[{ required: true, message: tc('validation.required') }]}>
          <Input />
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
