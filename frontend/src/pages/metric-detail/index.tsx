import { useEffect, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { Descriptions, Button, Tabs, Table, Spin, message, Form, Input, InputNumber, Select, Switch, Modal, Space } from 'antd';
import { ArrowLeftOutlined } from '@ant-design/icons';
import { useTenantStore } from '@/store/tenantStore';
import { listMetrics, updateMetric, getMetricImpact } from '@/api/metric';
import { ROUTES } from '@/constants/routes';
import { SOURCE_TYPE_OPTIONS, DATA_TYPE_OPTIONS, colorOf, STATUS_OPTIONS } from '@/constants/enums';
import type { MetricDescriptor, AffectedRule } from '@/types';
import type { ColumnsType } from 'antd/es/table';

export default function MetricDetail() {
  const { metricCode } = useParams<{ metricCode: string }>();
  const navigate = useNavigate();
  const { currentId } = useTenantStore();
  const [metric, setMetric] = useState<MetricDescriptor | null>(null);
  const [loading, setLoading] = useState(true);
  const [editing, setEditing] = useState(false);
  const [saving, setSaving] = useState(false);
  const [form] = Form.useForm();
  const [impactLoading, setImpactLoading] = useState(false);
  const [affectedRules, setAffectedRules] = useState<AffectedRule[]>([]);

  const load = async () => {
    if (!currentId || !metricCode) return;
    setLoading(true);
    try {
      const data = await listMetrics(currentId);
      setMetric((data.data ?? []).find((m) => m.metricCode === metricCode) ?? null);
    } finally { setLoading(false); }
  };

  useEffect(() => { load(); }, [currentId, metricCode]);

  const handleSave = async () => {
    const values = await form.validateFields();
    const breakingChange = values.sourceType !== metric?.sourceType || values.dataType !== metric?.dataType;
    if (breakingChange) {
      Modal.confirm({
        title: '破坏性变更',
        content: 'sourceType 或 dataType 变更将产生新版本，已有规则仍绑定旧版本。确认继续？',
        onOk: () => doSave(values, true),
      });
    } else {
      doSave(values, false);
    }
  };

  const doSave = async (values: Record<string, unknown>, breaking: boolean) => {
    setSaving(true);
    try {
      await updateMetric(currentId!, metricCode!, breaking, values);
      message.success('保存成功');
      setEditing(false);
      load();
    } finally { setSaving(false); }
  };

  const loadImpact = async () => {
    if (!currentId || !metricCode || !metric) return;
    setImpactLoading(true);
    try {
      const data = await getMetricImpact(currentId, metricCode, metric.metricVersion);
      setAffectedRules(data.data?.affectedRules ?? []);
    } finally { setImpactLoading(false); }
  };

  if (loading) return <Spin size="large" style={{ display: 'block', margin: '100px auto' }} />;
  if (!metric) return <div>Metric 不存在</div>;

  const impactColumns: ColumnsType<AffectedRule> = [
    { title: '规则 Code', dataIndex: 'ruleCode', key: 'ruleCode' },
    { title: '规则名称', dataIndex: 'ruleName', key: 'ruleName' },
    { title: 'Scene', dataIndex: 'sceneCode', key: 'sceneCode' },
    {
      title: '状态', dataIndex: 'status', key: 'status',
      render: (v: string) => <span style={{ color: colorOf(STATUS_OPTIONS, v as never) }}>{v}</span>,
    },
  ];

  const tabItems = [
    {
      key: 'info',
      label: '基本信息',
      children: editing ? (
        <Form form={form} layout="vertical">
          <Form.Item name="metricCode" label="Metric Code"><Input disabled /></Form.Item>
          <Form.Item name="name" label="名称" rules={[{ required: true }]}><Input /></Form.Item>
          <Form.Item name="sourceType" label="取数方式"><Select options={[...SOURCE_TYPE_OPTIONS]} /></Form.Item>
          <Form.Item name="dataType" label="数据类型"><Select options={[...DATA_TYPE_OPTIONS]} /></Form.Item>
          <Form.Item name="cacheTtlSeconds" label="缓存 TTL (秒)"><InputNumber min={0} style={{ width: '100%' }} /></Form.Item>
          <Form.Item name="allowProvided" label="allowProvided" valuePropName="checked"><Switch /></Form.Item>
          <Space><Button type="primary" onClick={handleSave} loading={saving}>保存</Button><Button onClick={() => setEditing(false)}>取消</Button></Space>
        </Form>
      ) : (
        <div>
          <Descriptions bordered column={2} size="small">
            <Descriptions.Item label="Metric Code">{metric.metricCode}</Descriptions.Item>
            <Descriptions.Item label="名称">{metric.name}</Descriptions.Item>
            <Descriptions.Item label="取数方式">{metric.sourceType}</Descriptions.Item>
            <Descriptions.Item label="数据类型">{metric.dataType}</Descriptions.Item>
            <Descriptions.Item label="版本">{metric.metricVersion}</Descriptions.Item>
            <Descriptions.Item label="缓存 TTL(s)">{metric.cacheTtlSeconds}</Descriptions.Item>
            <Descriptions.Item label="allowProvided">{metric.allowProvided ? '是' : '否'}</Descriptions.Item>
          </Descriptions>
          <div style={{ marginTop: 16 }}>
            <Button type="primary" onClick={() => { form.setFieldsValue(metric); setEditing(true); }}>编辑</Button>
          </div>
        </div>
      ),
    },
    {
      key: 'impact',
      label: '影响面查询',
      children: (
        <div>
          <Button type="primary" onClick={loadImpact} loading={impactLoading} style={{ marginBottom: 16 }}>查询引用的规则</Button>
          <Table columns={impactColumns} dataSource={affectedRules} rowKey="ruleDefinitionId" loading={impactLoading} size="small" />
        </div>
      ),
    },
  ];

  return (
    <div>
      <div style={{ marginBottom: 16, display: 'flex', alignItems: 'center', gap: 16 }}>
        <Button icon={<ArrowLeftOutlined />} onClick={() => navigate(ROUTES.METRICS)}>返回</Button>
        <h2 style={{ margin: 0 }}>{metric.name} ({metric.metricCode})</h2>
      </div>
      <Tabs items={tabItems} />
    </div>
  );
}
