import { useEffect, useState, useMemo } from 'react';
import { Table, Button, Modal, Form, Input, InputNumber, Select, Switch, message, Space, Empty } from 'antd';
import { PlusOutlined, SearchOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useTenantStore } from '@/store/tenantStore';
import { listMetrics, createMetric } from '@/api/metric';
import { listConnectors } from '@/api/connector';
import { getMetricColumns } from '@/config/columns/metric';
import apiClient from '@/api/client';
import { ENDPOINTS } from '@/constants/api-endpoints';
import { ROUTES, route } from '@/constants/routes';
import { getSourceTypeOptions, getDataTypeOptions, getStatusOptions } from '@/constants/enums';
import type { MetricDescriptor, SourceType } from '@/types';

export default function MetricList() {
  const navigate = useNavigate();
  const { t } = useTranslation('metric');
  const tc = useTranslation('common').t;
  const { currentId, activeList } = useTenantStore();
  const [tenantFilter, setTenantFilter] = useState<number | undefined>(undefined);
  const [metrics, setMetrics] = useState<MetricDescriptor[]>([]);
  const tenantId = tenantFilter ?? currentId ?? 0;
  const [loading, setLoading] = useState(false);
  const [keyword, setKeyword] = useState('');
  const [statusFilter, setStatusFilter] = useState<string | undefined>(undefined);
  const [modalOpen, setModalOpen] = useState(false);
  const [confirmLoading, setConfirmLoading] = useState(false);
  const [form] = Form.useForm();
  const sourceType: SourceType = Form.useWatch('sourceType', form);
  const [connectors, setConnectors] = useState<{ value: string; label: string }[]>([]);

  useEffect(() => {
    if (!tenantId || sourceType !== 'EXTERNAL_HTTP') return;
    listConnectors(tenantId).then((r) => {
      setConnectors((r.data ?? []).map((c) => ({ value: c.connectorCode, label: `${c.name} (${c.connectorCode})` })));
    }).catch(() => {});
  }, [tenantId, sourceType]);

  const load = async () => {
    if (!tenantId) return;
    setLoading(true);
    try { const data = await listMetrics(tenantId); setMetrics(data.data ?? []); }
    finally { setLoading(false); }
  };

  useEffect(() => { load(); }, [tenantId]);

  const dataSource = useMemo(() => {
    let result = metrics;
    if (keyword.trim()) {
      const kw = keyword.toLowerCase();
      result = result.filter((m) => m.metricCode.toLowerCase().includes(kw) || (m.name ?? '').toLowerCase().includes(kw));
    }
    if (statusFilter) {
      result = result.filter((m) => m.status === statusFilter);
    }
    return result;
  }, [metrics, keyword, statusFilter]);

  const handleToggleStatus = async (code: string, enabled: boolean) => {
    await apiClient.put(ENDPOINTS.METRIC_TOGGLE_STATUS(code), null, {
      params: { tenantId, enable: enabled },
      headers: { 'X-Actor-Id': localStorage.getItem('actorId') || 'anonymous' },
    });
    message.success(enabled ? tc('message.enabled') : tc('message.disabled'));
    load();
  };

  const handleCreate = async () => {
    const values = await form.validateFields();
    setConfirmLoading(true);
    try {
      // EXTERNAL_HTTP 的 vars 是 JSON 文本框，提交前 parse 成對象；parse 失敗給空對象
      const parsedParams = { ...(values.params ?? {}) };
      if (values.sourceType === 'EXTERNAL_HTTP' && typeof parsedParams.vars === 'string') {
        try { parsedParams.vars = parsedParams.vars.trim() ? JSON.parse(parsedParams.vars) : {}; }
        catch { parsedParams.vars = {}; }
      }
      await createMetric(tenantId, values.metricCode, { ...values, params: parsedParams, tenantId });
      message.success(tc('message.createSuccess'));
      setModalOpen(false);
      form.resetFields();
      // 新建后直接跳详情页（避免列表→再点进去的断点）
      navigate(route(ROUTES.METRIC_DETAIL, { metricCode: values.metricCode }));
    } catch { /* handled by interceptor */ }
    finally { setConfirmLoading(false); }
  };

  const renderParamsFields = () => {
    switch (sourceType) {
      case 'ATTRIBUTE':
        return (<>
          <Form.Item name={['params', 'table']} label={t('form.params.table')} rules={[{ required: true }]}><Input /></Form.Item>
          <Form.Item name={['params', 'column']} label={t('form.params.column')} rules={[{ required: true }]}><Input /></Form.Item>
        </>);
      case 'SQL_AGGREGATE':
        return (<>
          <Form.Item name={['params', 'datasource']} label={t('form.params.datasource')} rules={[{ required: true }]}><Input /></Form.Item>
          <Form.Item name={['params', 'sql']} label={t('form.params.sql')} rules={[{ required: true }]}><Input.TextArea rows={4} style={{ fontFamily: 'monospace' }} /></Form.Item>
        </>);
      case 'EXTERNAL_HTTP':
        return (<>
          <Form.Item name={['params', 'connector']} label={t('form.params.connector')} rules={[{ required: true }]}>
            <Select
              showSearch
              placeholder={t('form.params.connectorPlaceholder')}
              options={connectors}
              notFoundContent={t('form.params.connectorEmpty')}
            />
          </Form.Item>
          <Form.Item name={['params', 'dataType']} label={t('form.dataType')} rules={[{ required: true }]}>
            <Select options={getDataTypeOptions(t)} />
          </Form.Item>
          <Form.Item name={['params', 'vars']} label={t('form.params.vars')} extra={t('form.params.varsHint')}>
            <Input.TextArea rows={3} style={{ fontFamily: 'monospace' }} placeholder={'{"featureA":"val"}'} />
          </Form.Item>
        </>);
      case 'STREAM':
        return (<>
          <Form.Item name={['params', 'topic']} label={t('form.params.topic')}><Input disabled /></Form.Item>
          <Form.Item name={['params', 'keyExpr']} label={t('form.params.keyExpr')}><Input disabled /></Form.Item>
          <div style={{ color: '#999', fontSize: 12 }}>{t('form.streamDisabled')}</div>
        </>);
      default: return null;
    }
  };

  return (<>
    <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 16 }}>
      <h2>{t('title.list')}</h2>
      <Button type="primary" icon={<PlusOutlined />} onClick={() => setModalOpen(true)}>{t('action.create')}</Button>
    </div>
    <Space style={{ marginBottom: 16 }}>
      <Select
        placeholder={tc('label.tenant')}
        value={tenantFilter}
        onChange={setTenantFilter}
        allowClear
        options={activeList.map((t) => ({ value: t.id, label: `${t.name} (${t.code})` }))}
        style={{ width: 180 }}
      />
      <Input
        prefix={<SearchOutlined />}
        placeholder={t('searchPlaceholder')}
        value={keyword}
        onChange={(e) => setKeyword(e.target.value)}
        allowClear
        style={{ width: 220 }}
      />
      <Select
        placeholder={tc('label.status')}
        value={statusFilter}
        onChange={setStatusFilter}
        allowClear
        options={getStatusOptions(tc)}
        style={{ width: 120 }}
      />
    </Space>
    {tenantId ? (
      <Table columns={getMetricColumns(t, tc, handleToggleStatus)} dataSource={dataSource} rowKey="metricCode" loading={loading}
        scroll={{ y: 'calc(100vh - 312px)' }}
        onRow={(r) => ({ onClick: () => navigate(route(ROUTES.METRIC_DETAIL, { metricCode: r.metricCode })), style: { cursor: 'pointer' } })} />
    ) : (
      <Empty description={tc('tenant.notSelected')} style={{ marginTop: 80 }}>
        <Button type="primary" onClick={() => navigate(ROUTES.TENANTS)}>{tc('tenant.goSelect')}</Button>
      </Empty>
    )}
    <Modal title={t('action.create')} open={modalOpen} onOk={handleCreate} onCancel={() => { setModalOpen(false); form.resetFields(); }} confirmLoading={confirmLoading} width={640}>
      <Form form={form} layout="vertical">
        <Form.Item name="metricCode" label={t('form.code')} rules={[{ required: true, pattern: /^[a-z][a-z0-9_.]*$/ }]}>
          <Input placeholder={t('form.codePlaceholder')} />
        </Form.Item>
        <Form.Item name="name" label={t('form.name')} rules={[{ required: true }]}><Input /></Form.Item>
        <Form.Item name="sourceType" label={t('form.sourceType')} initialValue="ATTRIBUTE">
          <Select options={getSourceTypeOptions(t)} />
        </Form.Item>
        <Form.Item name="dataType" label={t('form.dataType')} initialValue="LONG">
          <Select options={getDataTypeOptions(t)} />
        </Form.Item>
        <Form.Item name="cacheTtlSeconds" label={t('form.cacheTtl')} initialValue={60}>
          <InputNumber min={0} style={{ width: '100%' }} />
        </Form.Item>
        <Form.Item name="allowProvided" label={t('form.allowProvided')} valuePropName="checked" initialValue={false}>
          <Switch />
        </Form.Item>
        {renderParamsFields()}
      </Form>
    </Modal>
  </>);
}
