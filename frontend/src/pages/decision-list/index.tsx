import { useEffect, useState } from 'react';
import { Table, Button, Modal, Form, Input, InputNumber, Select, message, Space } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { useTenantStore } from '@/store/tenantStore';
import { listDecisions, createDecision, updateDecision } from '@/api/decision';
import { getDecisionColumns } from '@/config/columns/decision';
import type { DecisionItem } from '@/types';

export default function DecisionList() {
  const { t } = useTranslation('decision');
  const tc = useTranslation('common').t;
  const { currentId, activeList, setCurrentById } = useTenantStore();
  const [tenantFilter, setTenantFilter] = useState<number | undefined>(undefined);
  const [decisions, setDecisions] = useState<DecisionItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [editingCode, setEditingCode] = useState<string | null>(null);
  const [confirmLoading, setConfirmLoading] = useState(false);
  const [form] = Form.useForm();
  const tenantId = tenantFilter ?? currentId ?? 0;

  const load = async () => {
    if (!tenantId) return;
    setLoading(true);
    try { const data = await listDecisions(tenantId); setDecisions(data.data ?? []); }
    finally { setLoading(false); }
  };

  useEffect(() => { load(); }, [tenantId]);

  const handleSubmit = async () => {
    const values = await form.validateFields();
    setConfirmLoading(true);
    try {
      if (editingCode) {
        await updateDecision(tenantId, editingCode, values);
        message.success(tc('message.updateSuccess'));
      } else {
        await createDecision(tenantId, { ...values, tenantId });
        message.success(tc('message.createSuccess'));
      }
      setModalOpen(false);
      setEditingCode(null);
      form.resetFields();
      load();
    } catch { /* handled by interceptor */ }
    finally { setConfirmLoading(false); }
  };

  const openCreate = () => {
    setEditingCode(null);
    form.resetFields();
    setModalOpen(true);
  };

  const openEdit = (record: DecisionItem) => {
    setEditingCode(record.code);
    form.setFieldsValue(record);
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
      columns={getDecisionColumns(t, tc, async (code, enabled) => {
        await updateDecision(tenantId, code, { status: enabled ? 'ACTIVE' : 'DISABLED' });
        message.success(enabled ? tc('message.enabled') : tc('message.disabled'));
        load();
      })}
      dataSource={decisions}
      rowKey="code"
      loading={loading}
      scroll={{ y: 'calc(100vh - 312px)' }}
      onRow={(record) => ({ onClick: () => openEdit(record), style: { cursor: 'pointer' } })}
    />
    <Modal
      title={editingCode ? `${t('action.edit')}: ${editingCode}` : t('action.create')}
      open={modalOpen}
      onOk={handleSubmit}
      onCancel={() => { setModalOpen(false); setEditingCode(null); form.resetFields(); }}
      confirmLoading={confirmLoading}
    >
      <Form form={form} layout="vertical">
        <Form.Item name="tenantId" label={tc('label.tenant')} rules={[{ required: true }]} initialValue={tenantId || undefined}>
          <Select options={activeList.map((ten) => ({ value: ten.id, label: `${ten.name} (${ten.code})` }))} placeholder={tc('label.tenant')} />
        </Form.Item>
        <Form.Item name="code" label={t('form.code')} rules={[{ required: true }]}>
          <Input disabled={!!editingCode} placeholder={t('form.codePlaceholder')} />
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
