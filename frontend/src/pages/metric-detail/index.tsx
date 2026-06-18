import { useEffect, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { Descriptions, Button, Tabs, Spin, message, Form, Input, InputNumber, Select, Switch, Modal, Space } from 'antd';
import { ArrowLeftOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { useTenantStore } from '@/store/tenantStore';
import { getMetric, updateMetric, getMetricImpact } from '@/api/metric';
import { ROUTES } from '@/constants/routes';
import { getSourceTypeOptions, getDataTypeOptions } from '@/constants/enums';
import LineageTable from '@/components/lineage/LineageTable';
import TestPanel from './TestPanel';
import type { MetricDescriptor, LineageRuleRef } from '@/types';

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
  const [activeTab, setActiveTab] = useState('info');
  const [impactLoading, setImpactLoading] = useState(false);
  const [impactRows, setImpactRows] = useState<LineageRuleRef[]>([]);
  // 影响面查询版本：默认当前版本，可切到历史版本重查
  const [impactVersion, setImpactVersion] = useState<number | undefined>(undefined);

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

  // metric 加载后把影响面查询版本初始化为当前版本
  useEffect(() => { if (metric) setImpactVersion(metric.metricVersion); }, [metric?.metricVersion]);

  // 切到 impact Tab 或切换版本时自动查影响面（去掉手动按钮）；受影响规则与 LineageRuleRef 同构，直接 map
  useEffect(() => {
    if (activeTab !== 'impact' || !currentId || !metricCode || impactVersion === undefined) return;
    let cancelled = false;
    setImpactLoading(true);
    getMetricImpact(currentId, metricCode, impactVersion)
      .then((data) => {
        if (cancelled) return;
        const rows: LineageRuleRef[] = (data?.affectedRules ?? []).map((r) => ({
          ruleDefinitionId: r.ruleDefinitionId,
          ruleCode: r.ruleCode,
          ruleName: r.ruleName,
          sceneCode: r.sceneCode,
          status: r.status,
        }));
        setImpactRows(rows);
      })
      .finally(() => { if (!cancelled) setImpactLoading(false); });
    return () => { cancelled = true; };
  }, [activeTab, currentId, metricCode, impactVersion]);

  if (loading) return <Spin size="large" style={{ display: 'block', margin: '100px auto' }} />;
  if (!metric) return <div>{t('detail.notFound')}</div>;

  // 版本选项：1..当前版本（版本号顺序递增，历史版本均存在）
  const versionOptions = Array.from({ length: metric.metricVersion }, (_, i) => {
    const v = i + 1;
    return { value: v, label: `v${v}` };
  });

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
          <Space style={{ marginBottom: 16 }}>
            <span>{t('detail.version')}</span>
            <Select
              value={impactVersion}
              onChange={setImpactVersion}
              options={versionOptions}
              style={{ width: 120 }}
            />
          </Space>
          <LineageTable rows={impactRows} loading={impactLoading} />
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
      <Tabs items={tabItems} activeKey={activeTab} onChange={setActiveTab} />
    </div>
  );
}
