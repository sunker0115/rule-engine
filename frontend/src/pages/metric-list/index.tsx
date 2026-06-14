import { useEffect, useState } from 'react';
import { Table, Button, Modal, Form, Input, InputNumber, Select, Switch, message } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { useTenantStore } from '@/store/tenantStore';
import { listMetrics, createMetric } from '@/api/metric';
import { METRIC_COLUMNS } from '@/config/columns/metric';
import { ROUTES, route } from '@/constants/routes';
import { SOURCE_TYPE_OPTIONS, DATA_TYPE_OPTIONS } from '@/constants/enums';
import type { MetricDescriptor, SourceType } from '@/types';

export default function MetricList() {
  const navigate = useNavigate();
  const { currentId } = useTenantStore();
  const [metrics, setMetrics] = useState<MetricDescriptor[]>([]);
  const [loading, setLoading] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [confirmLoading, setConfirmLoading] = useState(false);
  const [form] = Form.useForm();
  const sourceType: SourceType = Form.useWatch('sourceType', form);

  const load = async () => {
    if (!currentId) return;
    setLoading(true);
    try { const data = await listMetrics(currentId); setMetrics(data.data ?? []); }
    finally { setLoading(false); }
  };

  useEffect(() => { load(); }, [currentId]);

  const handleCreate = async () => {
    const values = await form.validateFields();
    setConfirmLoading(true);
    try {
      await createMetric(currentId!, values.metricCode, { ...values, tenantId: currentId });
      message.success('Metric 注册成功');
      setModalOpen(false);
      form.resetFields();
      load();
    } catch { /* handled by interceptor */ }
    finally { setConfirmLoading(false); }
  };

  const renderParamsFields = () => {
    switch (sourceType) {
      case 'ATTRIBUTE':
        return (<>
          <Form.Item name={['params', 'table']} label="表名" rules={[{ required: true }]}><Input /></Form.Item>
          <Form.Item name={['params', 'column']} label="列名" rules={[{ required: true }]}><Input /></Form.Item>
        </>);
      case 'SQL_AGGREGATE':
        return (<>
          <Form.Item name={['params', 'datasource']} label="数据源" rules={[{ required: true }]}><Input /></Form.Item>
          <Form.Item name={['params', 'sql']} label="SQL" rules={[{ required: true }]}><Input.TextArea rows={4} style={{ fontFamily: 'monospace' }} /></Form.Item>
        </>);
      case 'EXTERNAL_HTTP':
        return (<>
          <Form.Item name={['params', 'endpoint']} label="Endpoint" rules={[{ required: true }]}><Input /></Form.Item>
          <Form.Item name={['params', 'path']} label="路径" rules={[{ required: true }]}><Input placeholder="/api/v1/risk/{payload.userId}" /></Form.Item>
          <Form.Item name={['params', 'jsonPath']} label="JSON Path" rules={[{ required: true }]}><Input placeholder="$.data.riskScore" /></Form.Item>
        </>);
      case 'STREAM':
        return (<>
          <Form.Item name={['params', 'topic']} label="Topic"><Input disabled /></Form.Item>
          <Form.Item name={['params', 'keyExpr']} label="Key 表达式"><Input disabled /></Form.Item>
          <div style={{ color: '#999', fontSize: 12 }}>STREAM 类型 v2 接入，当前不可用</div>
        </>);
      default: return null;
    }
  };

  return (<>
    <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 16 }}>
      <h2>Metric 列表</h2>
      <Button type="primary" icon={<PlusOutlined />} onClick={() => setModalOpen(true)}>注册 Metric</Button>
    </div>
    <Table columns={METRIC_COLUMNS} dataSource={metrics} rowKey="metricCode" loading={loading}
      onRow={(r) => ({ onClick: () => navigate(route(ROUTES.METRIC_DETAIL, { metricCode: r.metricCode })), style: { cursor: 'pointer' } })} />
    <Modal title="注册 Metric" open={modalOpen} onOk={handleCreate} onCancel={() => { setModalOpen(false); form.resetFields(); }} confirmLoading={confirmLoading} width={640}>
      <Form form={form} layout="vertical">
        <Form.Item name="metricCode" label="Metric Code" rules={[{ required: true, pattern: /^[a-z][a-z0-9_.]*$/ }]}>
          <Input placeholder="如 user.trade.sum.7d" />
        </Form.Item>
        <Form.Item name="name" label="名称" rules={[{ required: true }]}><Input /></Form.Item>
        <Form.Item name="sourceType" label="取数方式" initialValue="ATTRIBUTE">
          <Select options={[...SOURCE_TYPE_OPTIONS]} />
        </Form.Item>
        <Form.Item name="dataType" label="数据类型" initialValue="LONG">
          <Select options={[...DATA_TYPE_OPTIONS]} />
        </Form.Item>
        <Form.Item name="cacheTtlSeconds" label="缓存 TTL (秒)" initialValue={60}>
          <InputNumber min={0} style={{ width: '100%' }} />
        </Form.Item>
        <Form.Item name="allowProvided" label="允许外部注入" valuePropName="checked" initialValue={false}>
          <Switch />
        </Form.Item>
        {renderParamsFields()}
      </Form>
    </Modal>
  </>);
}
