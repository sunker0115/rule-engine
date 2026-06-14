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
import { DOMINANT_MODE_OPTIONS } from '@/constants/enums';
import type { SceneDetail as SceneDetailType } from '@/types';

// ---- payloadSchema 编辑器 ----
interface FieldDef { name: string; type: string; required: boolean; sensitive: boolean; }
const TYPE_OPTIONS = ['string', 'number', 'integer', 'boolean'].map(v => ({ value: v, label: v }));

function toSchema(fields: FieldDef[]): Record<string, unknown> | null {
  if (fields.length === 0) return null;
  const props: Record<string, unknown> = {};
  const required: string[] = [];
  for (const f of fields) {
    const prop: Record<string, unknown> = { type: f.type };
    if (f.sensitive) prop.sensitive = true;
    props[f.name] = prop;
    if (f.required) required.push(f.name);
  }
  return { type: 'object', properties: props, ...(required.length > 0 ? { required } : {}) };
}
function fromSchema(schema: unknown): FieldDef[] {
  if (!schema || typeof schema !== 'object') return [];
  const s = schema as Record<string, unknown>;
  const props = (s.properties ?? {}) as Record<string, { type?: string; sensitive?: boolean }>;
  const req: string[] = (s.required as string[]) ?? [];
  return Object.entries(props).map(([name, def]) => ({ name, type: def.type ?? 'string', required: req.includes(name), sensitive: def.sensitive === true }));
}

function PayloadSchemaEditor({ value, onChange }: { value?: unknown; onChange?: (v: Record<string, unknown> | null) => void }) {
  const [fields, setFields] = useState<FieldDef[]>(() => fromSchema(value));
  useEffect(() => { setFields(fromSchema(value)); }, [value]);
  const update = (newFields: FieldDef[]) => { setFields(newFields); onChange?.(toSchema(newFields)); };
  return (
    <div>
      <Table dataSource={fields.map((f, i) => ({ ...f, _key: i }))} rowKey="_key" size="small" pagination={false} locale={{ emptyText: '暂无字段' }}>
        <Table.Column title="字段名" dataIndex="name" width={130} render={(v: string, _: FieldDef, i: number) => (
          <Input size="small" value={v} onChange={e => { const next = [...fields]; next[i] = { ...next[i], name: e.target.value }; update(next); }} style={{ width: 120 }} />
        )} />
        <Table.Column title="类型" dataIndex="type" width={100} render={(v: string, _: FieldDef, i: number) => (
          <Select size="small" value={v} onChange={val => { const next = [...fields]; next[i] = { ...next[i], type: val }; update(next); }} options={TYPE_OPTIONS} style={{ width: 90 }} />
        )} />
        <Table.Column title="必填" dataIndex="required" width={55} render={(v: boolean, _: FieldDef, i: number) => (
          <Switch size="small" checked={v} onChange={checked => { const next = [...fields]; next[i] = { ...next[i], required: checked }; update(next); }} />
        )} />
        <Table.Column title="敏感" dataIndex="sensitive" width={55} render={(v: boolean, _: FieldDef, i: number) => (
          <Switch size="small" checked={v} onChange={checked => { const next = [...fields]; next[i] = { ...next[i], sensitive: checked }; update(next); }} />
        )} />
        <Table.Column title="" width={40} render={(_: unknown, __: FieldDef, i: number) => (
          <Button type="text" size="small" icon={<DeleteOutlined />} onClick={() => update(fields.filter((_, j) => j !== i))} />
        )} />
      </Table>
      <Button type="dashed" size="small" icon={<PlusOutlined />} onClick={() => update([...fields, { name: '', type: 'string', required: true, sensitive: false }])} style={{ marginTop: 8 }}>添加字段</Button>
    </div>
  );
}

// ---- defaultParams 编辑器 ----
function DefaultParamsEditor({ value, onChange }: { value?: unknown; onChange?: (v: Record<string, string> | null) => void }) {
  const [entries, setEntries] = useState<[string, string][]>(() => {
    const obj = (value && typeof value === 'object' ? value : {}) as Record<string, string>;
    return Object.entries(obj);
  });
  useEffect(() => {
    const obj = (value && typeof value === 'object' ? value : {}) as Record<string, string>;
    setEntries(Object.entries(obj));
  }, [value]);
  const sync = (newEntries: [string, string][]) => {
    setEntries(newEntries);
    const obj: Record<string, string> = {};
    for (const [k, v] of newEntries) if (k) obj[k] = v;
    onChange?.(Object.keys(obj).length > 0 ? obj : null);
  };
  return (
    <div>
      {entries.map(([key, val], i) => (
        <Space key={i} style={{ marginBottom: 6 }}>
          <Input size="small" placeholder="参数名" value={key}
            onChange={e => { const next = [...entries]; next[i] = [e.target.value, val]; sync(next); }} style={{ width: 140 }} />
          <Input size="small" placeholder="参数值" value={val}
            onChange={e => { const next = [...entries]; next[i] = [key, e.target.value]; sync(next); }} style={{ width: 180 }} />
          <Button type="text" size="small" icon={<DeleteOutlined />} onClick={() => sync(entries.filter((_, j) => j !== i))} />
        </Space>
      ))}
      <Button type="dashed" size="small" icon={<PlusOutlined />} onClick={() => sync([...entries, [`param${entries.length + 1}`, '']])}>添加参数</Button>
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
    (async () => {
      setLoading(true);
      try { const data = await getScene(currentId, sceneCode); setScene(data.data ?? null); }
      finally { setLoading(false); }
    })();
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
    setSaving(true);
    try {
      await apiClient.patch(ENDPOINTS.SCENE_DETAIL(sceneCode!), { ...values, tenantId: currentId });
      message.success(tc('message.saveSuccess'));
      navigate(route(ROUTES.SCENE_DETAIL, { sceneCode: sceneCode! }));
    } finally { setSaving(false); }
  };

  if (loading) return <Spin size="large" style={{ display: 'block', margin: '100px auto' }} />;
  if (!scene) return <div>场景不存在</div>;

  return (
    <div>
      <div style={{ marginBottom: 16, display: 'flex', alignItems: 'center', gap: 16 }}>
        <Button icon={<ArrowLeftOutlined />} onClick={() => navigate(-1)}>返回</Button>
        <h2 style={{ margin: 0 }}>编辑场景 — {scene.sceneCode}</h2>
      </div>
      <Form form={form} layout="vertical" style={{ maxWidth: 800 }}>
        <Form.Item name="code" label={t('form.code')}><Input disabled /></Form.Item>
        <Form.Item name="name" label={t('form.name')} rules={[{ required: true }]}><Input /></Form.Item>
        <Form.Item name="dominantMode" label={t('form.dominantMode')}>
          <Select options={[...DOMINANT_MODE_OPTIONS]} />
        </Form.Item>
        <Form.Item name="subjectType" label={t('form.subjectType')}>
          <Select options={[{ value: 'USER', label: 'USER' }, { value: 'ACCOUNT', label: 'ACCOUNT (v2)', disabled: true }]} />
        </Form.Item>
        <Form.Item name="decisionStrategy" label={t('form.decisionStrategy')}>
          <Select options={[{ value: 'HIGHEST_PRIORITY', label: 'HIGHEST_PRIORITY' }]} />
        </Form.Item>
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
