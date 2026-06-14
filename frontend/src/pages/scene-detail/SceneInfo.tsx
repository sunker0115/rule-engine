import { useState } from 'react';
import { Descriptions, Button, Form, Input, Select, Switch, message, Space } from 'antd';
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
    } catch { message.error('payloadSchema JSON 格式错误'); return; }
    try {
      if (body.defaultParams && typeof body.defaultParams === 'string') {
        body.defaultParams = JSON.parse(body.defaultParams as string);
      }
    } catch { message.error('defaultParams JSON 格式错误'); return; }

    setSaving(true);
    try {
      await apiClient.put(ENDPOINTS.SCENE_DETAIL(scene.sceneCode), body);
      message.success('保存成功');
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
          <Descriptions.Item label="Scene Code">{scene.sceneCode}</Descriptions.Item>
          <Descriptions.Item label="名称">{scene.name}</Descriptions.Item>
          <Descriptions.Item label="使用模式">{scene.dominantMode}</Descriptions.Item>
          <Descriptions.Item label="主体类型">{scene.subjectType}</Descriptions.Item>
          <Descriptions.Item label="决策策略">{scene.decisionStrategy}</Descriptions.Item>
          <Descriptions.Item label="状态">{scene.status}</Descriptions.Item>
          <Descriptions.Item label="说明" span={2}>{scene.description || '-'}</Descriptions.Item>
          <Descriptions.Item label="事件类型" span={2}>
            {(scene.eventTypes ?? []).join(', ') || '-'}
          </Descriptions.Item>
        </Descriptions>
        <div style={{ marginTop: 16 }}>
          <Button type="primary" onClick={startEdit}>编辑</Button>
        </div>
      </div>
    );
  }

  return (
    <Form form={form} layout="vertical">
      <Form.Item name="code" label="Scene Code">
        <Input disabled />
      </Form.Item>
      <Form.Item name="name" label="名称" rules={[{ required: true }]}>
        <Input />
      </Form.Item>
      <Form.Item name="dominantMode" label="使用模式">
        <Select options={[...DOMINANT_MODE_OPTIONS]} />
      </Form.Item>
      <Form.Item name="subjectType" label="主体类型">
        <Select options={[
          { value: 'USER', label: 'USER' },
          { value: 'ACCOUNT', label: 'ACCOUNT (v2)', disabled: true },
          { value: 'DEVICE', label: 'DEVICE (v2)', disabled: true },
        ]} />
      </Form.Item>
      <Form.Item name="decisionStrategy" label="决策策略">
        <Select options={[
          { value: 'HIGHEST_PRIORITY', label: 'HIGHEST_PRIORITY' },
          { value: 'MAJORITY', label: 'MAJORITY (v2)', disabled: true },
        ]} />
      </Form.Item>
      <Form.Item name="status" label="状态" valuePropName="checked">
        <Switch checkedChildren="ACTIVE" unCheckedChildren="DISABLED" checked={form.getFieldValue('status') === 'ACTIVE'} />
      </Form.Item>
      <Form.Item name="description" label="说明">
        <Input.TextArea rows={2} />
      </Form.Item>
      <Form.Item name="payloadSchema" label="payloadSchema (JSON)" extra="定义 payload 允许的字段与类型">
        <Input.TextArea rows={8} style={{ fontFamily: 'monospace' }} />
      </Form.Item>
      <Form.Item name="eventTypes" label="事件类型白名单">
        <Select mode="tags" placeholder="输入后回车添加" />
      </Form.Item>
      <Form.Item name="defaultParams" label="defaultParams (JSON)" extra="Scene 级缺省参数">
        <Input.TextArea rows={4} style={{ fontFamily: 'monospace' }} />
      </Form.Item>
      <Space>
        <Button type="primary" onClick={handleSave} loading={saving}>保存</Button>
        <Button onClick={() => setEditing(false)}>取消</Button>
      </Space>
    </Form>
  );
}
