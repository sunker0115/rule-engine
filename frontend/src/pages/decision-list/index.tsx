import { useEffect, useState } from 'react';
import { Table, Button, Modal, Form, Input, InputNumber, Select, message, Space } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useTenantStore } from '@/store/tenantStore';
import { listDecisions, createDecision, updateDecision, getDecisionUsageCounts, getDecisionSources } from '@/api/decision';
import { getDecisionColumns } from '@/config/columns/decision';
import { ROUTES, route } from '@/constants/routes';
import LineageDrawer from '@/components/lineage/LineageDrawer';
import type { DecisionItem } from '@/types';

export default function DecisionList() {
  const navigate = useNavigate();
  const { t } = useTranslation('decision');
  const tc = useTranslation('common').t;
  const tl = useTranslation('lineage').t;
  const { currentId, activeList, setCurrentById } = useTenantStore();
  const [tenantFilter, setTenantFilter] = useState<number | undefined>(undefined);
  const [decisions, setDecisions] = useState<DecisionItem[]>([]);
  const [usageMap, setUsageMap] = useState<Record<string, number>>({});
  const [loading, setLoading] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [confirmLoading, setConfirmLoading] = useState(false);
  const [form] = Form.useForm();
  // 血缘抽屉：点击徽标打开，code 决定拉取目标
  const [lineageCode, setLineageCode] = useState<string | null>(null);
  const tenantId = tenantFilter ?? currentId ?? 0;

  const load = async () => {
    if (!tenantId) return;
    setLoading(true);
    try {
      const [data, counts] = await Promise.all([
        listDecisions(tenantId),
        getDecisionUsageCounts(tenantId),
      ]);
      setDecisions(data ?? []);
      setUsageMap(Object.fromEntries((counts ?? []).map((c) => [c.code, c.count])));
    } finally { setLoading(false); }
  };

  useEffect(() => { load(); }, [tenantId]);

  const handleCreate = async () => {
    const values = await form.validateFields();
    setConfirmLoading(true);
    try {
      await createDecision(tenantId, { ...values, tenantId });
      message.success(tc('message.createSuccess'));
      setModalOpen(false);
      form.resetFields();
      load();
    } catch { /* handled by interceptor */ }
    finally { setConfirmLoading(false); }
  };

  const openCreate = () => {
    form.resetFields();
    setModalOpen(true);
  };

  return (<>
    <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 16 }}>
      <h2>{t('title.list')}</h2>
      <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>{t('action.create')}</Button>
    </div>
    <Space style={{ marginBottom: 16 }}>
      <Select
        placeholder={tc('label.tenant')}
        value={tenantFilter ?? currentId ?? undefined}
        onChange={(v) => { setTenantFilter(v); setCurrentById(v); }}
        allowClear
        options={activeList.map((t) => ({ value: t.id, label: `${t.name} (${t.code})` }))}
        style={{ width: 200 }}
      />
    </Space>
    <Table
      columns={getDecisionColumns(
        t, tc,
        async (code, enabled) => {
          await updateDecision(tenantId, code, { status: enabled ? 'ACTIVE' : 'DISABLED' });
          message.success(enabled ? tc('message.enabled') : tc('message.disabled'));
          load();
        },
        tl, usageMap, (code) => setLineageCode(code),
      )}
      dataSource={decisions}
      rowKey="code"
      loading={loading}
      scroll={{ y: 'calc(100vh - 312px)' }}
      onRow={(record) => ({
        onClick: () => navigate(route(ROUTES.DECISION_DETAIL, { code: record.code })),
        style: { cursor: 'pointer' },
      })}
    />
    <LineageDrawer
      open={!!lineageCode}
      code={lineageCode ?? ''}
      title={tl('drawerTitle', { code: lineageCode ?? '' })}
      tenantId={tenantId}
      fetcher={getDecisionSources}
      onClose={() => setLineageCode(null)}
    />
    <Modal
      title={t('action.create')}
      open={modalOpen}
      onOk={handleCreate}
      onCancel={() => { setModalOpen(false); form.resetFields(); }}
      confirmLoading={confirmLoading}
    >
      <Form form={form} layout="vertical">
        <Form.Item name="tenantId" label={tc('label.tenant')} rules={[{ required: true }]} initialValue={tenantId || undefined}>
          <Select options={activeList.map((ten) => ({ value: ten.id, label: `${ten.name} (${ten.code})` }))} placeholder={tc('label.tenant')} />
        </Form.Item>
        <Form.Item name="code" label={t('form.code')} rules={[{ required: true }]}>
          <Input placeholder={t('form.codePlaceholder')} />
        </Form.Item>
        <Form.Item name="name" label={t('form.name')} rules={[{ required: true }]}>
          <Input />
        </Form.Item>
        <Form.Item name="priority" label={t('form.priority')} extra={t('form.priorityExtra')} rules={[{ required: true }]}>
          <InputNumber min={1} style={{ width: '100%' }} />
        </Form.Item>
        <Form.Item name="description" label={t('form.description')}>
          <Input.TextArea rows={2} />
        </Form.Item>
      </Form>
    </Modal>
  </>);
}
