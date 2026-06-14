import { useEffect, useState } from 'react';
import { Table, Button, Modal, Form, Input, InputNumber, message } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import { useTenantStore } from '@/store/tenantStore';
import { listDecisions, createDecision, updateDecision } from '@/api/decision';
import { DECISION_COLUMNS } from '@/config/columns/decision';
import type { DecisionItem } from '@/types';

export default function DecisionList() {
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
        message.success('更新成功');
      } else {
        await createDecision(currentId!, { ...values, tenantId: currentId });
        message.success('创建成功');
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
      <h2>Decision 列表</h2>
      <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>新建 Decision</Button>
    </div>
    <Table
      columns={DECISION_COLUMNS}
      dataSource={decisions}
      rowKey="code"
      loading={loading}
      onRow={(record) => ({ onClick: () => openEdit(record), style: { cursor: 'pointer' } })}
    />
    <Modal
      title={editingCode ? `编辑 Decision: ${editingCode}` : '新建 Decision'}
      open={modalOpen}
      onOk={handleSubmit}
      onCancel={() => { setModalOpen(false); setEditingCode(null); form.resetFields(); }}
      confirmLoading={confirmLoading}
    >
      <Form form={form} layout="vertical">
        <Form.Item name="code" label="Code" rules={[{ required: true }]}>
          <Input disabled={!!editingCode} placeholder="如 REJECT / REVIEW / PASS" />
        </Form.Item>
        <Form.Item name="name" label="名称" rules={[{ required: true }]}>
          <Input />
        </Form.Item>
        <Form.Item name="priority" label="优先级" extra="数值越小优先级越高" rules={[{ required: true }]}>
          <InputNumber min={1} style={{ width: '100%' }} />
        </Form.Item>
        <Form.Item name="description" label="说明">
          <Input.TextArea rows={2} />
        </Form.Item>
      </Form>
    </Modal>
  </>);
}
