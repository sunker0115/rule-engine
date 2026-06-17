import { useEffect, useState, useMemo } from 'react';
import { Table, Button, Modal, Form, Input, InputNumber, Select, Switch, message, Space, Empty } from 'antd';
import { PlusOutlined, SearchOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useTenantStore } from '@/store/tenantStore';
import { listMetrics, createMetric } from '@/api/metric';
import { listConnectors, getConnector } from '@/api/connector';
import type { ConnectorDescriptor } from '@/types';
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
  const { currentId, activeList, setCurrentById } = useTenantStore();
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
  // 选定连接器后从描述符里提取的 vars 占位符名列表（去重有序）
  const [varsKeys, setVarsKeys] = useState<string[]>([]);
  const [loadingConnector, setLoadingConnector] = useState(false);

  // 从 descriptor 各模板字段提取 {vars.xxx} 名
  const extractVarsKeys = (d: ConnectorDescriptor): string[] => {
    const targets = [
      d.request?.pathTemplate ?? '',
      d.request?.bodyTemplate ?? '',
      ...(d.request?.query ?? []).map((p) => p.valueTemplate),
      ...(d.request?.headers ?? []).map((p) => p.valueTemplate),
    ];
    const seen = new Set<string>();
    targets.forEach((tmpl) => {
      [...tmpl.matchAll(/\{vars\.([a-zA-Z_][\w.]*)\}/g)].forEach((m) => seen.add(m[1]));
    });
    return [...seen];
  };

  useEffect(() => {
    if (!tenantId || sourceType !== 'EXTERNAL_HTTP') return;
    listConnectors({ tenantId, size: 200 }).then((r) => {
      setConnectors((r?.items ?? []).map((c) => ({ value: c.connectorCode, label: `${c.name} (${c.connectorCode})` })));
    }).catch(() => {});
    // 切类型时清空 vars 状态
    setVarsKeys([]);
    form.setFieldValue(['params', 'connector'], undefined);
  }, [tenantId, sourceType]);

  const handleConnectorChange = async (code: string) => {
    setVarsKeys([]);
    if (!code || !tenantId) return;
    setLoadingConnector(true);
    try {
      const res = await getConnector(code, tenantId);
      const keys = extractVarsKeys(res?.descriptor ?? {} as ConnectorDescriptor);
      setVarsKeys(keys);
      // 清空旧 vars 值
      keys.forEach((k) => form.setFieldValue(['params', 'vars', k], undefined));
    } catch { /* ignore */ }
    finally { setLoadingConnector(false); }
  };

  const load = async () => {
    if (!tenantId) return;
    setLoading(true);
    try { const data = await listMetrics(tenantId); setMetrics(data ?? []); }
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
      // params 直接来自表单嵌套字段（vars 已是对象形式，不再需要 JSON.parse）
      await createMetric(tenantId, values.metricCode, { ...values, tenantId });
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
              loading={loadingConnector}
              placeholder={t('form.params.connectorPlaceholder')}
              options={connectors}
              notFoundContent={t('form.params.connectorEmpty')}
              onChange={handleConnectorChange}
            />
          </Form.Item>
          <Form.Item name={['params', 'dataType']} label={t('form.dataType')} rules={[{ required: true }]}>
            <Select options={getDataTypeOptions(t)} />
          </Form.Item>
          {varsKeys.length > 0 && (
            <div style={{ marginBottom: 8 }}>
              <div style={{ marginBottom: 4, fontWeight: 500 }}>{t('form.params.vars')}</div>
              <div style={{ color: '#999', fontSize: 12, marginBottom: 8 }}>{t('form.params.varsHint')}</div>
              {varsKeys.map((k) => (
                <Form.Item key={k} name={['params', 'vars', k]} label={k} style={{ marginBottom: 8 }}>
                  <Input placeholder={t('form.params.varsKeyPlaceholder', { key: k })} />
                </Form.Item>
              ))}
            </div>
          )}
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
        value={tenantFilter ?? currentId ?? undefined}
        onChange={(v) => { setTenantFilter(v); setCurrentById(v); }}
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
        <Form.Item name="tenantId" label={tc('label.tenant')} initialValue={currentId ?? undefined} rules={[{ required: true, message: tc('tenant.notSelected') }]}>
          <Select options={activeList.map((t) => ({ value: t.id, label: `${t.name} (${t.code})` }))} placeholder={tc('label.tenant')} />
        </Form.Item>
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
