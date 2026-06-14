import { useState } from 'react';
import { Descriptions, Button, Form, Input, Select, Switch, message, Space } from 'antd';
import { useTranslation } from 'react-i18next';
import apiClient from '@/api/client';
import { ENDPOINTS } from '@/constants/api-endpoints';
import { DOMINANT_MODE_OPTIONS } from '@/constants/enums';
import type { SceneDetail as SceneDetailType } from '@/types';

interface Props {
  scene: SceneDetailType;
  tenantId: number;
  onUpdated: () => void;
}

export default function SceneInfo({ scene, tenantId, onUpdated }: Props) {
  const { t } = useTranslation('scene');
  const tc = useTranslation('common').t;
  const [editing, setEditing] = useState(false);
  const [saving, setSaving] = useState(false);
  const [form] = Form.useForm();

  const handleSave = async () => {
    const values = await form.validateFields();
    const body: Record<string, unknown> = { ...values, tenantId };
    try {
      if (body.payloadSchema && typeof body.payloadSchema === 'string') {
        body.payloadSchema = JSON.parse(body.payloadSchema as string);
      }
    } catch { message.error(`payloadSchema ${tc('validation.jsonFormat')}`); return; }
    try {
      if (body.defaultParams && typeof body.defaultParams === 'string') {
        body.defaultParams = JSON.parse(body.defaultParams as string);
      }
    } catch { message.error(`defaultParams ${tc('validation.jsonFormat')}`); return; }

    setSaving(true);
    try {
      await apiClient.put(ENDPOINTS.SCENE_DETAIL(scene.sceneCode), body);
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
      payloadSchema: scene.payloadSchema ? JSON.stringify(scene.payloadSchema, null, 2) : '',
      defaultParams: scene.defaultParams ? JSON.stringify(scene.defaultParams, null, 2) : '',
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
    <Form form={form} layout="vertical">
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
      <Form.Item name="payloadSchema" label={t('form.payloadSchema')} extra={t('form.payloadSchemaExtra')}>
        <Input.TextArea rows={8} style={{ fontFamily: 'monospace' }} />
      </Form.Item>
      <Form.Item name="eventTypes" label={t('form.eventTypes')}>
        <Select mode="tags" placeholder={t('form.eventTypesPlaceholder')} />
      </Form.Item>
      <Form.Item name="defaultParams" label={t('form.defaultParams')} extra={t('form.defaultParamsExtra')}>
        <Input.TextArea rows={4} style={{ fontFamily: 'monospace' }} />
      </Form.Item>
      <Space>
        <Button type="primary" onClick={handleSave} loading={saving}>{tc('button.save')}</Button>
        <Button onClick={() => setEditing(false)}>{tc('button.cancel')}</Button>
      </Space>
    </Form>
  );
}
