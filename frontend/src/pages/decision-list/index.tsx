import { useEffect, useState } from 'react';
import { Table, Button, Modal, Form, Input, InputNumber, message } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { useTenantStore } from '@/store/tenantStore';
import { listDecisions, createDecision, updateDecision } from '@/api/decision';
import { DECISION_COLUMNS } from '@/config/columns/decision';
import type { DecisionItem } from '@/types';

export default function DecisionList() {
  const { t } = useTranslation('decision');
  const tc = useTranslation('common').t;
  const { currentId } = useTenantStore();
  const [decisions, setDecisions] = useState<DecisionItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [editingCode, setEditingCode] = useState<string | null>(null);
  const [confirmLoading, setConfirmLoading] = useState(false);
  const [form] = Form.useForm();

  const load = async () => {
    if (!currentId) return;
    setLoading(true);
    try { const data = await listDecisions(currentId); setDecisions(data.data ?? []); }
    finally { setLoading(false); }
  };

  useEffect(() => { load(); }, [currentId]);

  const handleSubmit = async () => {
    const values = await form.validateFields();
    setConfirmLoading(true);
    try {
      if (editingCode) {
        await updateDecision(currentId!, editingCode, values);
        message.success(tc('message.updateSuccess'));
      } else {
        await createDecision(currentId!, { ...values, tenantId: currentId });
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
    <Table
      columns={DECISION_COLUMNS}
      dataSource={decisions}
      rowKey="code"
      loading={loading}
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
