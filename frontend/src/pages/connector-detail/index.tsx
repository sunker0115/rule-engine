import { useEffect, useMemo, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import {
  Button, Card, Input, InputNumber, Select, Switch, Space, Spin,
  Table, Typography, Divider, message,
} from 'antd';
import { ArrowLeftOutlined, PlusOutlined, DeleteOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { useTenantStore } from '@/store/tenantStore';
import { getConnector, createConnector, updateConnector } from '@/api/connector';
import { ROUTES } from '@/constants/routes';
import EnvelopePresets from './EnvelopePresets';
import TestPanel from './TestPanel';
import type {
  ConnectorDescriptor, HttpMethod, AuthKind, AuthScheme, CompareOp,
  RetryTrigger, TemplateParam, ErrorRule, ResponseMapping,
} from '@/types';

const HTTP_METHODS: HttpMethod[] = ['GET', 'POST', 'PUT'];
const COMPARE_OPS: CompareOp[] = ['EQ', 'NE', 'GT', 'GE', 'LT', 'LE'];
const RETRY_TRIGGERS: RetryTrigger[] = ['TIMEOUT', 'UPSTREAM_5XX'];
const AUTH_KINDS: AuthKind[] = ['STATIC_HEADER', 'BEARER', 'OAUTH2_CLIENT_CREDENTIALS'];

/** 新建态默认描述符——结构齐全，避免受控字段 undefined */
function emptyDescriptor(): ConnectorDescriptor {
  return {
    endpointRef: '',
    request: { method: 'GET', pathTemplate: '', query: [], headers: [] },
    response: { successWhen: { path: '', op: 'EQ', value: '' }, valuePath: '' },
    resilience: { connectTimeoutMs: 1000, readTimeoutMs: 2000, retries: 0, retryOn: [] },
    errorMapping: [],
  };
}

/** 按 kind 构造对应鉴权对象的初始值（可辨识联合 kind 判别） */
function defaultAuth(kind: AuthKind): AuthScheme {
  switch (kind) {
    case 'STATIC_HEADER': return { kind, headerName: '', credentialRef: '' };
    case 'BEARER': return { kind, tokenRef: '' };
    case 'OAUTH2_CLIENT_CREDENTIALS':
      return { kind, tokenUrl: '', clientIdRef: '', clientSecretRef: '', scopes: [] };
  }
}

export default function ConnectorDetail() {
  const { connectorCode: routeCode } = useParams<{ connectorCode: string }>();
  const isEdit = !!routeCode;
  const navigate = useNavigate();
  const { t } = useTranslation('connector');
  const tc = useTranslation('common').t;
  const { currentId, activeList } = useTenantStore();
  // 租户：编辑态跟随全局，新建态允许选择
  const [selectedTenant, setSelectedTenant] = useState<number | undefined>(undefined);
  const tenantId = selectedTenant ?? currentId ?? 0;

  const [loading, setLoading] = useState(isEdit);
  const [saving, setSaving] = useState(false);
  const [connectorCode, setConnectorCode] = useState('');
  const [name, setName] = useState('');
  const [descriptor, setDescriptor] = useState<ConnectorDescriptor>(emptyDescriptor());

  // 编辑态：按 code 直取单条详情，descriptor 为 typed 对象直接消费
  useEffect(() => {
    if (!isEdit || !tenantId || !routeCode) return;
    setLoading(true);
    getConnector(routeCode, tenantId)
      .then((res) => {
        const found = res;
        if (found) {
          setConnectorCode(found.connectorCode);
          setName(found.name ?? '');
          if (found.descriptor) setDescriptor({ ...emptyDescriptor(), ...found.descriptor });
        }
      })
      .finally(() => setLoading(false));
  }, [isEdit, currentId, routeCode]);

  // ----- 不可变更新工具 -----
  const patchRequest = (p: Partial<ConnectorDescriptor['request']>) =>
    setDescriptor((d) => ({ ...d, request: { ...d.request, ...p } }));
  const patchResponse = (m: ResponseMapping) => setDescriptor((d) => ({ ...d, response: m }));
  const patchResilience = (p: Partial<NonNullable<ConnectorDescriptor['resilience']>>) =>
    setDescriptor((d) => ({
      ...d,
      resilience: { ...(d.resilience ?? emptyDescriptor().resilience!), ...p },
    }));

  // query / headers 动态行
  const updateParamRow = (field: 'query' | 'headers', idx: number, p: Partial<TemplateParam>) =>
    patchRequest({ [field]: (descriptor.request[field] ?? []).map((r, i) => (i === idx ? { ...r, ...p } : r)) });
  const addParamRow = (field: 'query' | 'headers') =>
    patchRequest({ [field]: [...(descriptor.request[field] ?? []), { name: '', valueTemplate: '' }] });
  const removeParamRow = (field: 'query' | 'headers', idx: number) =>
    patchRequest({ [field]: (descriptor.request[field] ?? []).filter((_, i) => i !== idx) });

  // errorMapping 动态行
  const errorRows = descriptor.errorMapping ?? [];
  const updateErrorRow = (idx: number, rule: ErrorRule) =>
    setDescriptor((d) => ({ ...d, errorMapping: (d.errorMapping ?? []).map((r, i) => (i === idx ? rule : r)) }));
  const addErrorRow = () =>
    setDescriptor((d) => ({ ...d, errorMapping: [...(d.errorMapping ?? []), { when: {}, to: '' }] }));
  const removeErrorRow = (idx: number) =>
    setDescriptor((d) => ({ ...d, errorMapping: (d.errorMapping ?? []).filter((_, i) => i !== idx) }));

  // 鉴权 kind 切换：换 kind 即重建对应字段集（可辨识联合）
  const authKind: AuthKind | undefined = descriptor.auth?.kind;
  const changeAuthKind = (kind?: AuthKind) =>
    setDescriptor((d) => ({ ...d, auth: kind ? defaultAuth(kind) : undefined }));
  const patchAuth = (p: Record<string, unknown>) =>
    setDescriptor((d) => (d.auth ? { ...d, auth: { ...d.auth, ...p } as AuthScheme } : d));

  // 熔断开关
  const cbEnabled = !!descriptor.resilience?.circuitBreaker;
  const toggleCircuitBreaker = (on: boolean) =>
    patchResilience({
      circuitBreaker: on ? { failureRateThreshold: 0.5, windowSeconds: 60, openSeconds: 30 } : undefined,
    });
  const patchCircuitBreaker = (p: Partial<NonNullable<NonNullable<ConnectorDescriptor['resilience']>['circuitBreaker']>>) =>
    patchResilience({
      circuitBreaker: { ...(descriptor.resilience?.circuitBreaker ?? { failureRateThreshold: 0.5, windowSeconds: 60, openSeconds: 30 }), ...p },
    });

  const authKindOptions = useMemo(
    () => AUTH_KINDS.map((k) => ({ value: k, label: t(`enum.authKind.${k}`) })),
    [t],
  );

  const handleSave = async () => {
    if (!tenantId) { message.error(tc('tenant.notSelected')); return; }
    if (!connectorCode.trim() || !name.trim()) {
      message.error(tc('validation.required'));
      return;
    }
    setSaving(true);
    try {
      const body = { name: name.trim(), descriptor };
      if (isEdit) await updateConnector(connectorCode, tenantId, body);
      else await createConnector(tenantId, connectorCode.trim(), body);
      message.success(tc('message.saveSuccess'));
      navigate(ROUTES.CONNECTORS);
    } catch {
      // 错误信息由 axios 响应拦截器统一 message.error 透出；此处仅阻止跳转
    } finally {
      setSaving(false);
    }
  };

  if (loading) return <Spin size="large" style={{ display: 'block', margin: '100px auto' }} />;

  const paramTable = (field: 'query' | 'headers') => {
    const rows = descriptor.request[field] ?? [];
    return (
      <Table
        dataSource={rows.map((r, i) => ({ ...r, _key: i }))}
        rowKey="_key"
        size="small"
        pagination={false}
        locale={{ emptyText: tc('label.none') }}
        columns={[
          {
            title: t('form.paramName'),
            dataIndex: 'name',
            render: (_: string, _r, i: number) => (
              <Input value={rows[i]?.name} onChange={(e) => updateParamRow(field, i, { name: e.target.value })} />
            ),
          },
          {
            title: t('form.paramValue'),
            dataIndex: 'valueTemplate',
            render: (_: string, _r, i: number) => (
              <Input value={rows[i]?.valueTemplate} onChange={(e) => updateParamRow(field, i, { valueTemplate: e.target.value })} />
            ),
          },
          {
            title: '',
            width: 40,
            render: (_: unknown, _r, i: number) => (
              <Button type="text" danger size="small" icon={<DeleteOutlined />} onClick={() => removeParamRow(field, i)} />
            ),
          },
        ]}
        footer={() => (
          <Button type="dashed" size="small" block icon={<PlusOutlined />} onClick={() => addParamRow(field)}>
            {t(field === 'query' ? 'action.addQuery' : 'action.addHeader')}
          </Button>
        )}
      />
    );
  };

  return (
    <div>
      <div style={{ marginBottom: 16, display: 'flex', alignItems: 'center', gap: 16 }}>
        <Button icon={<ArrowLeftOutlined />} onClick={() => navigate(ROUTES.CONNECTORS)}>{tc('button.back')}</Button>
        <h2 style={{ margin: 0 }}>{isEdit ? t('title.edit') : t('title.create')}</h2>
      </div>

      {/* 基本信息 */}
      <Card title={t('section.basic')} size="small" style={{ marginBottom: 16 }}>
        <Space direction="vertical" style={{ width: '100%' }} size="middle">
          {!isEdit && (
            <div>
              <Typography.Text>{tc('label.tenant')}</Typography.Text>
              <Select
                value={tenantId || undefined}
                onChange={setSelectedTenant}
                placeholder={tc('label.tenant')}
                style={{ width: '100%', display: 'block' }}
                options={activeList.map((ten) => ({ value: ten.id, label: `${ten.name} (${ten.code})` }))}
              />
            </div>
          )}
          <div>
            <Typography.Text>{t('form.connectorCode')}</Typography.Text>
            <Input
              value={connectorCode}
              disabled={isEdit}
              placeholder={t('form.connectorCodePlaceholder')}
              onChange={(e) => setConnectorCode(e.target.value)}
            />
          </div>
          <div>
            <Typography.Text>{t('form.name')}</Typography.Text>
            <Input value={name} onChange={(e) => setName(e.target.value)} />
          </div>
          <div>
            <Typography.Text>{t('form.endpointRef')}</Typography.Text>
            <Input value={descriptor.endpointRef} onChange={(e) => setDescriptor((d) => ({ ...d, endpointRef: e.target.value }))} />
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>{t('form.endpointRefExtra')}</Typography.Text>
          </div>
        </Space>
      </Card>

      {/* 请求 */}
      <Card title={t('section.request')} size="small" style={{ marginBottom: 16 }}>
        <Space direction="vertical" style={{ width: '100%' }} size="middle">
          <div style={{ display: 'flex', gap: 8, alignItems: 'flex-end' }}>
            <div>
              <Typography.Text>{t('form.method')}</Typography.Text>
              <Select
                value={descriptor.request.method}
                style={{ width: 120, display: 'block' }}
                options={HTTP_METHODS.map((m) => ({ value: m, label: t(`enum.httpMethod.${m}`) }))}
                onChange={(v) => patchRequest({ method: v })}
              />
            </div>
            <div style={{ flex: 1 }}>
              <Typography.Text>{t('form.pathTemplate')}</Typography.Text>
              <Input
                style={{ width: '100%' }}
                value={descriptor.request.pathTemplate}
                placeholder={t('form.pathTemplatePlaceholder')}
                onChange={(e) => patchRequest({ pathTemplate: e.target.value })}
              />
            </div>
          </div>
          <div>
            <Typography.Text strong>{t('form.query')}</Typography.Text>
            {paramTable('query')}
          </div>
          <div>
            <Typography.Text strong>{t('form.headers')}</Typography.Text>
            {paramTable('headers')}
          </div>
          <div>
            <Typography.Text>{t('form.bodyTemplate')}</Typography.Text>
            <Input.TextArea
              rows={3}
              style={{ fontFamily: 'monospace' }}
              value={descriptor.request.bodyTemplate ?? ''}
              placeholder={t('form.bodyTemplatePlaceholder')}
              onChange={(e) => patchRequest({ bodyTemplate: e.target.value || undefined })}
            />
          </div>
        </Space>
      </Card>

      {/* 响应映射 */}
      <Card title={t('section.response')} size="small" style={{ marginBottom: 16 }}>
        <EnvelopePresets onApply={patchResponse} />
        <Divider style={{ margin: '8px 0' }} />
        <Typography.Text strong>{t('form.successWhen')}</Typography.Text>
        <Space style={{ display: 'flex', marginTop: 8, marginBottom: 16 }} align="end">
          <div>
            <Typography.Text>{t('form.successPath')}</Typography.Text>
            <Input
              style={{ width: 160 }}
              value={descriptor.response.successWhen.path}
              onChange={(e) => patchResponse({ ...descriptor.response, successWhen: { ...descriptor.response.successWhen, path: e.target.value } })}
            />
          </div>
          <div>
            <Typography.Text>{t('form.successOp')}</Typography.Text>
            <Select
              style={{ width: 90, display: 'block' }}
              value={descriptor.response.successWhen.op}
              options={COMPARE_OPS.map((o) => ({ value: o, label: t(`enum.compareOp.${o}`) }))}
              onChange={(v) => patchResponse({ ...descriptor.response, successWhen: { ...descriptor.response.successWhen, op: v } })}
            />
          </div>
          <div>
            <Typography.Text>{t('form.successValue')}</Typography.Text>
            <Input
              style={{ width: 160 }}
              value={descriptor.response.successWhen.value == null ? '' : String(descriptor.response.successWhen.value)}
              onChange={(e) => patchResponse({ ...descriptor.response, successWhen: { ...descriptor.response.successWhen, value: e.target.value } })}
            />
          </div>
        </Space>
        <div>
          <Typography.Text>{t('form.valuePath')}</Typography.Text>
          <Input
            value={descriptor.response.valuePath}
            placeholder={t('form.valuePathPlaceholder')}
            onChange={(e) => patchResponse({ ...descriptor.response, valuePath: e.target.value })}
          />
        </div>
      </Card>

      {/* 鉴权 */}
      <Card title={t('section.auth')} size="small" style={{ marginBottom: 16 }}>
        <div style={{ marginBottom: 12 }}>
          <Typography.Text>{t('form.authKind')}</Typography.Text>
          <Select
            style={{ width: 240, display: 'block' }}
            allowClear
            placeholder={t('enum.authKind.NONE')}
            value={authKind}
            options={authKindOptions}
            onChange={(v) => changeAuthKind(v)}
          />
        </div>
        {descriptor.auth?.kind === 'STATIC_HEADER' && (
          <Space direction="vertical" style={{ width: '100%' }}>
            <div><Typography.Text>{t('form.headerName')}</Typography.Text>
              <Input value={descriptor.auth.headerName} onChange={(e) => patchAuth({ headerName: e.target.value })} /></div>
            <div><Typography.Text>{t('form.credentialRef')}</Typography.Text>
              <Input value={descriptor.auth.credentialRef} onChange={(e) => patchAuth({ credentialRef: e.target.value })} /></div>
          </Space>
        )}
        {descriptor.auth?.kind === 'BEARER' && (
          <div><Typography.Text>{t('form.tokenRef')}</Typography.Text>
            <Input value={descriptor.auth.tokenRef} onChange={(e) => patchAuth({ tokenRef: e.target.value })} /></div>
        )}
        {descriptor.auth?.kind === 'OAUTH2_CLIENT_CREDENTIALS' && (
          <Space direction="vertical" style={{ width: '100%' }}>
            <div><Typography.Text>{t('form.tokenUrl')}</Typography.Text>
              <Input value={descriptor.auth.tokenUrl} onChange={(e) => patchAuth({ tokenUrl: e.target.value })} /></div>
            <div><Typography.Text>{t('form.clientIdRef')}</Typography.Text>
              <Input value={descriptor.auth.clientIdRef} onChange={(e) => patchAuth({ clientIdRef: e.target.value })} /></div>
            <div><Typography.Text>{t('form.clientSecretRef')}</Typography.Text>
              <Input value={descriptor.auth.clientSecretRef} onChange={(e) => patchAuth({ clientSecretRef: e.target.value })} /></div>
            <div><Typography.Text>{t('form.scopes')}</Typography.Text>
              <Select
                mode="tags"
                style={{ width: '100%' }}
                value={descriptor.auth.scopes ?? []}
                onChange={(v) => patchAuth({ scopes: v })}
              />
            </div>
          </Space>
        )}
      </Card>

      {/* 弹性 */}
      <Card title={t('section.resilience')} size="small" style={{ marginBottom: 16 }}>
        <Space wrap size="large">
          <div>
            <Typography.Text>{t('form.connectTimeoutMs')}</Typography.Text>
            <InputNumber
              min={0} style={{ width: 140, display: 'block' }}
              value={descriptor.resilience?.connectTimeoutMs}
              onChange={(v) => patchResilience({ connectTimeoutMs: v ?? 0 })}
            />
          </div>
          <div>
            <Typography.Text>{t('form.readTimeoutMs')}</Typography.Text>
            <InputNumber
              min={0} style={{ width: 140, display: 'block' }}
              value={descriptor.resilience?.readTimeoutMs}
              onChange={(v) => patchResilience({ readTimeoutMs: v ?? 0 })}
            />
          </div>
          <div>
            <Typography.Text>{t('form.retries')}</Typography.Text>
            <InputNumber
              min={0} style={{ width: 120, display: 'block' }}
              value={descriptor.resilience?.retries}
              onChange={(v) => patchResilience({ retries: v ?? 0 })}
            />
          </div>
          <div>
            <Typography.Text>{t('form.retryOn')}</Typography.Text>
            <Select
              mode="multiple"
              style={{ width: 220, display: 'block' }}
              value={descriptor.resilience?.retryOn ?? []}
              options={RETRY_TRIGGERS.map((r) => ({ value: r, label: t(`enum.retryTrigger.${r}`) }))}
              onChange={(v) => patchResilience({ retryOn: v })}
            />
          </div>
        </Space>
        <Divider style={{ margin: '12px 0' }} />
        <Space align="center" style={{ marginBottom: cbEnabled ? 12 : 0 }}>
          <Switch checked={cbEnabled} onChange={toggleCircuitBreaker} />
          <Typography.Text>{t('form.enableCircuitBreaker')}</Typography.Text>
        </Space>
        {cbEnabled && descriptor.resilience?.circuitBreaker && (
          <Space wrap size="large">
            <div>
              <Typography.Text>{t('form.failureRateThreshold')}</Typography.Text>
              <InputNumber
                min={0} max={1} step={0.1} style={{ width: 140, display: 'block' }}
                value={descriptor.resilience.circuitBreaker.failureRateThreshold}
                onChange={(v) => patchCircuitBreaker({ failureRateThreshold: v ?? 0 })}
              />
            </div>
            <div>
              <Typography.Text>{t('form.windowSeconds')}</Typography.Text>
              <InputNumber
                min={1} style={{ width: 120, display: 'block' }}
                value={descriptor.resilience.circuitBreaker.windowSeconds}
                onChange={(v) => patchCircuitBreaker({ windowSeconds: v ?? 0 })}
              />
            </div>
            <div>
              <Typography.Text>{t('form.openSeconds')}</Typography.Text>
              <InputNumber
                min={1} style={{ width: 120, display: 'block' }}
                value={descriptor.resilience.circuitBreaker.openSeconds}
                onChange={(v) => patchCircuitBreaker({ openSeconds: v ?? 0 })}
              />
            </div>
          </Space>
        )}
      </Card>

      {/* 错误映射 */}
      <Card title={t('section.errorMapping')} size="small" style={{ marginBottom: 16 }}>
        <Table
          dataSource={errorRows.map((r, i) => ({ ...r, _key: i }))}
          rowKey="_key"
          size="small"
          pagination={false}
          locale={{ emptyText: tc('label.none') }}
          columns={[
            {
              title: t('form.errorWhenStatusFrom'),
              width: 110,
              render: (_: unknown, _r, i: number) => (
                <InputNumber
                  style={{ width: '100%' }}
                  value={errorRows[i]?.when.statusFrom}
                  onChange={(v) => updateErrorRow(i, { ...errorRows[i], when: { ...errorRows[i].when, statusFrom: v ?? undefined } })}
                />
              ),
            },
            {
              title: t('form.errorWhenStatusTo'),
              width: 110,
              render: (_: unknown, _r, i: number) => (
                <InputNumber
                  style={{ width: '100%' }}
                  value={errorRows[i]?.when.statusTo}
                  onChange={(v) => updateErrorRow(i, { ...errorRows[i], when: { ...errorRows[i].when, statusTo: v ?? undefined } })}
                />
              ),
            },
            {
              title: t('form.errorWhenEnvelopeCode'),
              render: (_: unknown, _r, i: number) => (
                <Input
                  value={errorRows[i]?.when.envelopeCode}
                  onChange={(e) => updateErrorRow(i, { ...errorRows[i], when: { ...errorRows[i].when, envelopeCode: e.target.value || undefined } })}
                />
              ),
            },
            {
              title: t('form.errorTo'),
              render: (_: unknown, _r, i: number) => (
                <Input
                  value={errorRows[i]?.to}
                  onChange={(e) => updateErrorRow(i, { ...errorRows[i], to: e.target.value })}
                />
              ),
            },
            {
              title: '',
              width: 40,
              render: (_: unknown, _r, i: number) => (
                <Button type="text" danger size="small" icon={<DeleteOutlined />} onClick={() => removeErrorRow(i)} />
              ),
            },
          ]}
          footer={() => (
            <Button type="dashed" size="small" block icon={<PlusOutlined />} onClick={addErrorRow}>
              {t('action.addError')}
            </Button>
          )}
        />
      </Card>

      {/* 内联自助测试 */}
      <Card title={t('test.title')} size="small" style={{ marginBottom: 16 }}>
        <TestPanel connectorCode={connectorCode} isEdit={isEdit} descriptor={descriptor} tenantId={tenantId} />
      </Card>

      <Space>
        <Button type="primary" loading={saving} onClick={handleSave}>{tc('button.save')}</Button>
        <Button onClick={() => navigate(ROUTES.CONNECTORS)}>{tc('button.cancel')}</Button>
      </Space>
    </div>
  );
}
