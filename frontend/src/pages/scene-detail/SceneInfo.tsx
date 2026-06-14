import { useState } from 'react';
import { Descriptions, Button, Form, Input, Select, Switch, message, Space, Table } from 'antd';
import { PlusOutlined, DeleteOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import apiClient from '@/api/client';
import { ENDPOINTS } from '@/constants/api-endpoints';
import { DOMINANT_MODE_OPTIONS } from '@/constants/enums';
import type { SceneDetail as SceneDetailType } from '@/types';

interface Props {
  scene: SceneDetailType;
  tenantId: number;
  onUpdated: () => void;
  autoEdit?: boolean;
}

export default function SceneInfo({ scene, tenantId, onUpdated, autoEdit }: Props) {
  const { t } = useTranslation('scene');
  const tc = useTranslation('common').t;
  const autoEditing = !!autoEdit;
  const [editing, setEditing] = useState(autoEditing);
  const [saving, setSaving] = useState(false);
  const [form] = Form.useForm();

  const initialValues = autoEditing ? {
    ...scene,
    payloadSchema: scene.payloadSchema ?? null,
    defaultParams: scene.defaultParams ?? null,
    eventTypes: scene.eventTypes ?? [],
  } : undefined;

  const handleSave = async () => {
    const values = await form.validateFields();
    const body: Record<string, unknown> = { ...values, tenantId };

    setSaving(true);
    try {
      await apiClient.patch(ENDPOINTS.SCENE_DETAIL(scene.sceneCode), body);
      message.success(tc('message.saveSuccess'));
      setEditing(false);
      onUpdated();
    } finally {
      setSaving(false);
    }
  };

  const startEdit = () => {
    form.setFieldsValue({
      ...scene,
      payloadSchema: scene.payloadSchema ?? null,
      defaultParams: scene.defaultParams ?? null,
      eventTypes: scene.eventTypes ?? [],
    });
    setEditing(true);
  };


  if (!editing) {
    return (
      <div>
        <Descriptions bordered column={2} size="small">
          <Descriptions.Item label={t('form.code')}>{scene.sceneCode}</Descriptions.Item>
          <Descriptions.Item label={t('form.name')}>{scene.name}</Descriptions.Item>
          <Descriptions.Item label={t('form.dominantMode')}>{scene.dominantMode}</Descriptions.Item>
          <Descriptions.Item label={t('form.subjectType')}>{scene.subjectType}</Descriptions.Item>
          <Descriptions.Item label={t('form.decisionStrategy')}>{scene.decisionStrategy}</Descriptions.Item>
          <Descriptions.Item label={t('form.status')}>{scene.status}</Descriptions.Item>
          <Descriptions.Item label={t('form.description')} span={2}>{scene.description || '-'}</Descriptions.Item>
          <Descriptions.Item label={t('form.eventTypes')} span={2}>
            {(scene.eventTypes ?? []).join(', ') || '-'}
          </Descriptions.Item>
        </Descriptions>
        <div style={{ marginTop: 16 }}>
          <Button type="primary" onClick={startEdit}>{tc('button.edit')}</Button>
        </div>
      </div>
    );
  }

  return (
    <Form form={form} layout="vertical" initialValues={initialValues}>
      <Form.Item name="code" label={t('form.code')}>
        <Input disabled />
      </Form.Item>
      <Form.Item name="name" label={t('form.name')} rules={[{ required: true }]}>
        <Input />
      </Form.Item>
      <Form.Item name="dominantMode" label={t('form.dominantMode')}>
        <Select options={[...DOMINANT_MODE_OPTIONS]} />
      </Form.Item>
      <Form.Item name="subjectType" label={t('form.subjectType')}>
        <Select options={[
          { value: 'USER', label: 'USER' },
          { value: 'ACCOUNT', label: 'ACCOUNT (v2)', disabled: true },
          { value: 'DEVICE', label: 'DEVICE (v2)', disabled: true },
        ]} />
      </Form.Item>
      <Form.Item name="decisionStrategy" label={t('form.decisionStrategy')}>
        <Select options={[
          { value: 'HIGHEST_PRIORITY', label: 'HIGHEST_PRIORITY' },
          { value: 'MAJORITY', label: 'MAJORITY (v2)', disabled: true },
        ]} />
      </Form.Item>
      <Form.Item name="status" label={t('form.status')} valuePropName="checked">
        <Switch checkedChildren="ACTIVE" unCheckedChildren="DISABLED" checked={form.getFieldValue('status') === 'ACTIVE'} />
      </Form.Item>
      <Form.Item name="description" label={t('form.description')}>
        <Input.TextArea rows={2} />
      </Form.Item>
      <Form.Item name="eventTypes" label={t('form.eventTypes')}>
        <Select mode="tags" placeholder={t('form.eventTypesPlaceholder')} />
      </Form.Item>

      {/* payloadSchema 可视化编辑 */}
      <Form.Item label={t('form.payloadSchema')} extra={t('form.payloadSchemaExtra')}>
        <PayloadSchemaEditor
          value={form.getFieldValue('payloadSchema')}
          onChange={(v) => form.setFieldValue('payloadSchema', v)}
        />
      </Form.Item>

      {/* defaultParams 可视化编辑 */}
      <Form.Item label={t('form.defaultParams')} extra={t('form.defaultParamsExtra')}>
        <DefaultParamsEditor
          value={form.getFieldValue('defaultParams')}
          onChange={(v) => form.setFieldValue('defaultParams', v)}
        />
      </Form.Item>

      <Space>
        <Button type="primary" onClick={handleSave} loading={saving}>{tc('button.save')}</Button>
        <Button onClick={() => setEditing(false)}>{tc('button.cancel')}</Button>
      </Space>
    </Form>
  );
}

// ---- payloadSchema 可视化编辑器 ----
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
  return Object.entries(props).map(([name, def]) => ({
    name,
    type: def.type ?? 'string',
    required: req.includes(name),
    sensitive: def.sensitive === true,
  }));
}

function PayloadSchemaEditor({ value, onChange }: { value: unknown; onChange: (v: Record<string, unknown> | null) => void }) {
  const [fields, setFields] = useState<FieldDef[]>(() => fromSchema(value));

  const update = (newFields: FieldDef[]) => {
    setFields(newFields);
    onChange(toSchema(newFields));
  };

  return (
    <div>
      <Table
        dataSource={fields.map((f, i) => ({ ...f, _key: i }))}
        rowKey="_key"
        size="small"
        pagination={false}
        locale={{ emptyText: '暂无字段，点击下方添加' }}
      >
        <Table.Column title="字段名" dataIndex="name" width={130} render={(v: string, _: FieldDef, i: number) => (
          <Input size="small" value={v} onChange={e => {
            const next = [...fields]; next[i] = { ...next[i], name: e.target.value }; update(next);
          }} style={{ width: 120 }} />
        )} />
        <Table.Column title="类型" dataIndex="type" width={100} render={(v: string, _: FieldDef, i: number) => (
          <Select size="small" value={v} onChange={val => {
            const next = [...fields]; next[i] = { ...next[i], type: val }; update(next);
          }} options={TYPE_OPTIONS} style={{ width: 90 }} />
        )} />
        <Table.Column title="必填" dataIndex="required" width={55} render={(v: boolean, _: FieldDef, i: number) => (
          <Switch size="small" checked={v} onChange={checked => {
            const next = [...fields]; next[i] = { ...next[i], required: checked }; update(next);
          }} />
        )} />
        <Table.Column title="敏感" dataIndex="sensitive" width={55} render={(v: boolean, _: FieldDef, i: number) => (
          <Switch size="small" checked={v} onChange={checked => {
            const next = [...fields]; next[i] = { ...next[i], sensitive: checked }; update(next);
          }} />
        )} />
        <Table.Column title="" width={40} render={(_: unknown, __: FieldDef, i: number) => (
          <Button type="text" size="small" icon={<DeleteOutlined />} onClick={() => update(fields.filter((_, j) => j !== i))} />
        )} />
      </Table>
      <Button type="dashed" size="small" icon={<PlusOutlined />} onClick={() => update([...fields, { name: '', type: 'string', required: true, sensitive: false }])} style={{ marginTop: 8 }}>
        添加字段
      </Button>
    </div>
  );
}

// ---- defaultParams 可视化编辑器 ----
function DefaultParamsEditor({ value, onChange }: { value: unknown; onChange: (v: Record<string, string> | null) => void }) {
  const [entries, setEntries] = useState<[string, string][]>(() => {
    const obj = (value && typeof value === 'object' ? value : {}) as Record<string, string>;
    return Object.entries(obj);
  });

  const sync = (newEntries: [string, string][]) => {
    setEntries(newEntries);
    const obj: Record<string, string> = {};
    for (const [k, v] of newEntries) if (k) obj[k] = v;
    onChange(Object.keys(obj).length > 0 ? obj : null);
  };

  return (
    <div>
      {entries.map(([key, val], i) => (
        <Space key={i} style={{ marginBottom: 6 }}>
          <Input
            size="small"
            placeholder="参数名"
            value={key}
            onChange={e => {
              const next = [...entries];
              next[i] = [e.target.value, val];
              sync(next);
            }}
            style={{ width: 140 }}
          />
          <Input
            size="small"
            placeholder="参数值"
            value={val}
            onChange={e => {
              const next = [...entries];
              next[i] = [key, e.target.value];
              sync(next);
            }}
            style={{ width: 180 }}
          />
          <Button type="text" size="small" icon={<DeleteOutlined />} onClick={() => sync(entries.filter((_, j) => j !== i))} />
        </Space>
      ))}
      <Button type="dashed" size="small" icon={<PlusOutlined />} onClick={() => sync([...entries, [`param${entries.length + 1}`, '']])}>
        添加参数
      </Button>
    </div>
  );
}
