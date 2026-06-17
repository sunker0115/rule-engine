import { useEffect, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { Descriptions, Button, Tabs, Table, Spin, message, Form, Input, InputNumber, Select, Switch, Modal, Space } from 'antd';
import { ArrowLeftOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { useTenantStore } from '@/store/tenantStore';
import { getMetric, updateMetric, getMetricImpact } from '@/api/metric';
import { ROUTES } from '@/constants/routes';
import { getSourceTypeOptions, getDataTypeOptions, colorOf, getStatusOptions } from '@/constants/enums';
import TestPanel from './TestPanel';
import type { MetricDescriptor, AffectedRule } from '@/types';
import type { ColumnsType } from 'antd/es/table';

export default function MetricDetail() {
  const { metricCode } = useParams<{ metricCode: string }>();
  const navigate = useNavigate();
  const { t } = useTranslation('metric');
  const tc = useTranslation('common').t;
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
      const data = await getMetric(metricCode, currentId);
      setMetric(data ?? null);
    } finally { setLoading(false); }
  };

  useEffect(() => { load(); }, [currentId, metricCode]);

  const handleSave = async () => {
    const values = await form.validateFields();
    const breakingChange = values.sourceType !== metric?.sourceType || values.dataType !== metric?.dataType;
    if (breakingChange) {
      Modal.confirm({
        title: t('form.breakingChangeTitle'),
        content: t('form.breakingChangeContent'),
        onOk: () => doSave(values, true),
      });
    } else {
      doSave(values, false);
    }
  };

  const doSave = async (values: Record<string, unknown>, breaking: boolean) => {
    setSaving(true);
    try {
      // params 不在編輯表單裡，需從原始 metric 帶入，避免傳 undefined 導致後端清空 params
      const payload = { ...values, params: metric?.params ?? {} };
      await updateMetric(currentId!, metricCode!, breaking, payload);
      message.success(tc('message.saveSuccess'));
      setEditing(false);
      load();
    } finally { setSaving(false); }
  };

  const loadImpact = async () => {
    if (!currentId || !metricCode || !metric) return;
    setImpactLoading(true);
    try {
      const data = await getMetricImpact(currentId, metricCode, metric.metricVersion);
      setAffectedRules(data?.affectedRules ?? []);
    } finally { setImpactLoading(false); }
  };

  if (loading) return <Spin size="large" style={{ display: 'block', margin: '100px auto' }} />;
  if (!metric) return <div>{t('detail.notFound')}</div>;

  const impactColumns: ColumnsType<AffectedRule> = [
    { title: t('impact.column.ruleCode'), dataIndex: 'ruleCode', key: 'ruleCode' },
    { title: t('impact.column.ruleName'), dataIndex: 'ruleName', key: 'ruleName' },
    { title: t('impact.column.sceneCode'), dataIndex: 'sceneCode', key: 'sceneCode' },
    {
      title: t('impact.column.status'), dataIndex: 'status', key: 'status',
      render: (v: string) => <span style={{ color: colorOf(getStatusOptions(tc), v as never) }}>{v}</span>,
    },
  ];

  const tabItems = [
    {
      key: 'info',
      label: t('detail.basicInfo'),
      children: editing ? (
        <Form form={form} layout="vertical">
          <Form.Item name="metricCode" label={t('form.code')}><Input disabled /></Form.Item>
          <Form.Item name="name" label={t('form.name')} rules={[{ required: true }]}><Input /></Form.Item>
          <Form.Item name="sourceType" label={t('form.sourceType')}><Select options={getSourceTypeOptions(t)} /></Form.Item>
          <Form.Item name="dataType" label={t('form.dataType')}><Select options={getDataTypeOptions(t)} /></Form.Item>
          <Form.Item name="cacheTtlSeconds" label={t('form.cacheTtl')}><InputNumber min={0} style={{ width: '100%' }} /></Form.Item>
          <Form.Item name="allowProvided" label={t('form.allowProvided')} valuePropName="checked"><Switch /></Form.Item>
          <Space><Button type="primary" onClick={handleSave} loading={saving}>{tc('button.save')}</Button><Button onClick={() => setEditing(false)}>{tc('button.cancel')}</Button></Space>
        </Form>
      ) : (
        <div>
          <Descriptions bordered column={2} size="small">
            <Descriptions.Item label={t('form.code')}>{metric.metricCode}</Descriptions.Item>
            <Descriptions.Item label={t('form.name')}>{metric.name}</Descriptions.Item>
            <Descriptions.Item label={t('form.sourceType')}>{metric.sourceType}</Descriptions.Item>
            <Descriptions.Item label={t('form.dataType')}>{metric.dataType}</Descriptions.Item>
            <Descriptions.Item label={t('detail.version')}>{metric.metricVersion}</Descriptions.Item>
            <Descriptions.Item label={t('form.cacheTtl')}>{metric.cacheTtlSeconds}</Descriptions.Item>
            <Descriptions.Item label={t('form.allowProvided')}>{metric.allowProvided ? tc('label.yes') : tc('label.no')}</Descriptions.Item>
          </Descriptions>
          <div style={{ marginTop: 16 }}>
            <Button type="primary" onClick={() => { form.setFieldsValue(metric); setEditing(true); }}>{tc('button.edit')}</Button>
          </div>
        </div>
      ),
    },
    {
      key: 'impact',
      label: t('action.queryImpact'),
      children: (
        <div>
          <Button type="primary" onClick={loadImpact} loading={impactLoading} style={{ marginBottom: 16 }}>{t('action.queryImpact')}</Button>
          <Table columns={impactColumns} dataSource={affectedRules} rowKey="ruleDefinitionId" loading={impactLoading} size="small" />
        </div>
      ),
    },
    {
      key: 'test',
      label: t('test.title'),
      children: <TestPanel metricCode={metric.metricCode} />,
    },
  ];

  return (
    <div>
      <div style={{ marginBottom: 16, display: 'flex', alignItems: 'center', gap: 16 }}>
        <Button icon={<ArrowLeftOutlined />} onClick={() => navigate(ROUTES.METRICS)}>{tc('button.back')}</Button>
        <h2 style={{ margin: 0 }}>{metric.name} ({metric.metricCode})</h2>
      </div>
      <Tabs items={tabItems} />
    </div>
  );
}
