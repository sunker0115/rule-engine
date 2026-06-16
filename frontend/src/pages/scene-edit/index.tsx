import { useEffect, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { Form, Input, Select, Switch, Button, Space, message, Spin, Table } from 'antd';
import { PlusOutlined, DeleteOutlined, ArrowLeftOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { useTenantStore } from '@/store/tenantStore';
import { getScene } from '@/api/scene';
import apiClient from '@/api/client';
import { ENDPOINTS } from '@/constants/api-endpoints';
import { ROUTES, route } from '@/constants/routes';
import type { SceneDetail as SceneDetailType } from '@/types';

// ---- payloadSchema 编辑器 ----
interface FieldDef { name: string; type: string; required: boolean; sensitive: boolean; }
const TYPE_OPTIONS = ['string', 'number', 'integer', 'boolean'].map(v => ({ value: v, label: v }));

// 输出格式对齐后端 PayloadFieldSpec 数组
function toSchema(fields: FieldDef[]): Record<string, unknown>[] | null {
  if (fields.length === 0) return null;
  return fields.map(f => ({
    name: f.name,
    type: f.type,
    required: f.required,
    sensitive: f.sensitive,
  }));
}
function fromSchema(schema: unknown): FieldDef[] {
  if (Array.isArray(schema)) {
    return (schema as Record<string, unknown>[]).map((f: Record<string, unknown>) => ({
      name: f.name as string ?? '',
      type: (f.type || f.dataType) as string ?? 'string',
      required: f.required as boolean ?? true,
      sensitive: f.sensitive as boolean ?? false,
    }));
  }
  // 兼容旧 JSON Schema 格式
  if (schema && typeof schema === 'object') {
    const s = schema as Record<string, unknown>;
    const props = (s.properties ?? {}) as Record<string, { type?: string; sensitive?: boolean }>;
    const req: string[] = (s.required as string[]) ?? [];
    return Object.entries(props).map(([name, def]) => ({
      name, type: def.type ?? 'string', required: req.includes(name), sensitive: def.sensitive === true,
    }));
  }
  return [];
}

function PayloadSchemaEditor({ value, onChange }: { value?: unknown; onChange?: (v: unknown) => void }) {
  const { t } = useTranslation('scene');
  const fields: FieldDef[] = fromSchema(value);
  const update = (newFields: FieldDef[]) => { onChange?.(toSchema(newFields)); };
  return (
    <div>
      <Table dataSource={fields.map((f, i) => ({ ...f, _key: i }))} rowKey="_key" size="small" pagination={false} locale={{ emptyText: t('edit.noFields') }}>
        <Table.Column title={t('edit.fieldName')} dataIndex="name" width={130} render={(v: string, _: FieldDef, i: number) => (
          <Input size="small" value={v} onChange={e => { const next = [...fields]; next[i] = { ...next[i], name: e.target.value }; update(next); }} style={{ width: 120 }} />
        )} />
        <Table.Column title={t('edit.fieldType')} dataIndex="type" width={100} render={(v: string, _: FieldDef, i: number) => (
          <Select size="small" value={v} onChange={val => { const next = [...fields]; next[i] = { ...next[i], type: val }; update(next); }} options={TYPE_OPTIONS} style={{ width: 90 }} />
        )} />
        <Table.Column title={t('edit.fieldRequired')} dataIndex="required" width={55} render={(v: boolean, _: FieldDef, i: number) => (
          <Switch size="small" checked={v} onChange={checked => { const next = [...fields]; next[i] = { ...next[i], required: checked }; update(next); }} />
        )} />
        <Table.Column title={t('edit.fieldSensitive')} dataIndex="sensitive" width={55} render={(v: boolean, _: FieldDef, i: number) => (
          <Switch size="small" checked={v} onChange={checked => { const next = [...fields]; next[i] = { ...next[i], sensitive: checked }; update(next); }} />
        )} />
        <Table.Column title="" width={40} render={(_: unknown, __: FieldDef, i: number) => (
          <Button type="text" size="small" icon={<DeleteOutlined />} onClick={() => update(fields.filter((_, j) => j !== i))} />
        )} />
      </Table>
      <Button type="dashed" size="small" icon={<PlusOutlined />} onClick={() => update([...fields, { name: '', type: 'string', required: true, sensitive: false }])} style={{ marginTop: 8 }}>{t('edit.addField')}</Button>
    </div>
  );
}

// ---- defaultParams 编辑器 ----
function DefaultParamsEditor({ value, onChange }: { value?: unknown; onChange?: (v: Record<string, string> | null) => void }) {
  const { t } = useTranslation('scene');
  const obj = (value && typeof value === 'object' ? value : {}) as Record<string, string>;
  const entries: [string, string][] = Object.entries(obj);

  const sync = (newEntries: [string, string][]) => {
    const next: Record<string, string> = {};
    for (const [k, v] of newEntries) if (k) next[k] = v;
    onChange?.(Object.keys(next).length > 0 ? next : null);
  };
  return (
    <div>
      {entries.map(([key, val], i) => (
        <Space key={i} style={{ marginBottom: 6 }}>
          <Input size="small" placeholder={t('edit.paramName')} value={key}
            onChange={e => { const next = [...entries]; next[i] = [e.target.value, val]; sync(next); }} style={{ width: 140 }} />
          <Input size="small" placeholder={t('edit.paramValue')} value={val}
            onChange={e => { const next = [...entries]; next[i] = [key, e.target.value]; sync(next); }} style={{ width: 180 }} />
          <Button type="text" size="small" icon={<DeleteOutlined />} onClick={() => sync(entries.filter((_, j) => j !== i))} />
        </Space>
      ))}
      <Button type="dashed" size="small" icon={<PlusOutlined />} onClick={() => sync([...entries, [`param${entries.length + 1}`, '']])}>{t('edit.addParam')}</Button>
    </div>
  );
}

// ---- 主页面 ----
export default function SceneEdit() {
  const { sceneCode } = useParams<{ sceneCode: string }>();
  const navigate = useNavigate();
  const { t } = useTranslation('scene');
  const tc = useTranslation('common').t;
  const { currentId } = useTenantStore();
  const [scene, setScene] = useState<SceneDetailType | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [form] = Form.useForm();

  useEffect(() => {
    if (!currentId || !sceneCode) return;
    // cancelled 守卫:sceneCode/tenant 切换时丢弃旧请求结果，避免竞态把旧 scene 写进表单（原 loaded ref 永不重置会导致切换后不重载）
    let cancelled = false;
    (async () => {
      setLoading(true);
      try { const data = await getScene(currentId, sceneCode); if (!cancelled) setScene(data.data ?? null); }
      finally { if (!cancelled) setLoading(false); }
    })();
    return () => { cancelled = true; };
  }, [currentId, sceneCode]);

  // scene 加载后填充表单
  useEffect(() => {
    if (scene) {
      form.setFieldsValue({
        ...scene,
        payloadSchema: scene.payloadSchema ?? null,
        defaultParams: scene.defaultParams ?? null,
      });
    }
  }, [scene, form]);

  const handleSave = async () => {
    const values = await form.validateFields();
    // payloadSchema 过滤空名字段
    if (Array.isArray(values.payloadSchema)) {
      values.payloadSchema = values.payloadSchema.filter((f: Record<string, unknown>) => f.name);
    }
    setSaving(true);
    try {
      await apiClient.patch(ENDPOINTS.SCENE_DETAIL(sceneCode!), { ...values, tenantId: currentId });
      message.success(tc('message.saveSuccess'));
      navigate(route(ROUTES.SCENE_DETAIL, { sceneCode: sceneCode! }));
    } finally { setSaving(false); }
  };

  if (loading) return <Spin size="large" style={{ display: 'block', margin: '100px auto' }} />;
  if (!scene) return <div>{t('detail.notFound')}</div>;

  return (
    <div>
      <div style={{ marginBottom: 16, display: 'flex', alignItems: 'center', gap: 16 }}>
        <Button icon={<ArrowLeftOutlined />} onClick={() => navigate(-1)}>{tc('button.back')}</Button>
        <h2 style={{ margin: 0 }}>{t('edit.title', { code: scene.sceneCode })}</h2>
      </div>
      <Form form={form} layout="vertical" style={{ maxWidth: 800 }}>
        <Form.Item name="sceneCode" label={t('form.code')}><Input disabled /></Form.Item>
        <Form.Item name="name" label={t('form.name')} rules={[{ required: true }]}><Input /></Form.Item>
        <Form.Item name="description" label={t('form.description')}><Input.TextArea rows={2} /></Form.Item>
        <Form.Item name="eventTypes" label={t('form.eventTypes')}>
          <Select mode="tags" placeholder={t('form.eventTypesPlaceholder')} />
        </Form.Item>
        <Form.Item name="payloadSchema" label={t('form.payloadSchema')} extra={t('form.payloadSchemaExtra')}>
          <PayloadSchemaEditor />
        </Form.Item>
        <Form.Item name="defaultParams" label={t('form.defaultParams')} extra={t('form.defaultParamsExtra')}>
          <DefaultParamsEditor />
        </Form.Item>
        <Space>
          <Button type="primary" onClick={handleSave} loading={saving}>{tc('button.save')}</Button>
          <Button onClick={() => navigate(-1)}>{tc('button.cancel')}</Button>
        </Space>
      </Form>
    </div>
  );
}
