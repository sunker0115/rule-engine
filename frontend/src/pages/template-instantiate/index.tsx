import { useEffect, useState } from 'react';
import { Button, Card, Form, Input, Select, InputNumber, DatePicker, Switch, message, Space, Typography, Row, Col, Spin } from 'antd';
import { ArrowLeftOutlined, ThunderboltOutlined } from '@ant-design/icons';
import { useParams, useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useSceneStore } from '@/store/sceneStore';
import { getTemplate, instantiateTemplate } from '@/api/template';
import { listTenants } from '@/api/tenant';
import { ROUTES, route } from '@/constants/routes';
import type { TemplateDetail, TemplateSlot } from '@/types/template';
import type { TenantInfo } from '@/types';
import dayjs from 'dayjs';

const { Title, Text } = Typography;

export default function TemplateInstantiate() {
  const { code } = useParams<{ code: string }>();
  const navigate = useNavigate();
  const { list: scenes, loadList: loadScenes } = useSceneStore();
  const { t } = useTranslation('template');
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [tmpl, setTmpl] = useState<TemplateDetail | null>(null);
  const [tenants, setTenants] = useState<TenantInfo[]>([]);
  const [targetTenantId, setTargetTenantId] = useState<number | null>(null);
  const [form] = Form.useForm();

  useEffect(() => {
    listTenants().then((list) => {
      const filtered = (list ?? []).filter((t) => t.code !== 'SYSTEM');
      setTenants(filtered);
      if (filtered.length > 0 && !targetTenantId) setTargetTenantId(filtered[0].id);
    });
  }, []);

  useEffect(() => {
    if (code) {
      setLoading(true);
      getTemplate(1, code).then(setTmpl).finally(() => setLoading(false));
    }
  }, [code]);

  useEffect(() => {
    if (targetTenantId) loadScenes(targetTenantId);
  }, [targetTenantId, loadScenes]);

  const handleSubmit = async () => {
    const values = await form.validateFields();
    const slotValues: Record<string, unknown> = {};
    for (const slot of tmpl?.version.slots ?? []) {
      let val = values[`slot_${slot.key}`];
      if (slot.dataType === 'DATE' && val) {
        val = (val as dayjs.Dayjs).format('YYYY-MM-DD');
      } else if (slot.dataType === 'DATETIME' && val) {
        val = (val as dayjs.Dayjs).toISOString();
      }
      slotValues[slot.key] = val ?? values[`slot_${slot.key}`];
    }
    setSubmitting(true);
    try {
      const result = await instantiateTemplate(code!, {
        tenantId: targetTenantId,
        ruleCode: values.ruleCode,
        ruleName: values.ruleName,
        sceneCode: values.sceneCode,
        triggerEventTypes: values.triggerEventTypes || [],
        slotValues,
      }, 'admin');
      message.success(t('instantiate.success'));
      navigate(route(ROUTES.RULE_EDITOR, { ruleId: result.ruleDefinitionId }));
    } catch { /* interceptor */ }
    finally { setSubmitting(false); }
  };

  if (loading) return <Spin size="large" style={{ display: 'block', margin: '100px auto' }} />;
  if (!tmpl) return <div style={{ padding: 24 }}>模板不存在</div>;

  const renderSlotInput = (slot: TemplateSlot) => {
    const name = `slot_${slot.key}`;
    const rules = slot.required ? [{ required: true, message: `请填写 ${slot.label}` }] : [];
    const labelSuffix = slot.kind !== 'VALUE' ? ` (${slot.kind})` : ` (${slot.dataType ?? '?'}${slot.required ? ', 必填' : ''})`;
    const fieldProps: Record<string, unknown> = { name, label: `${slot.label}${labelSuffix}`, rules };

    const placeholder = slot.kind === 'VALUE'
      ? `输入${slot.dataType ?? '值'}（实际值，非表达式）`
      : `输入${slot.kind}引用的 code`;

    // REF slot：暂时用文本输入（后续计划加 picker）
    if (slot.kind !== 'VALUE') {
      return <Form.Item {...fieldProps}><Input placeholder={placeholder} /></Form.Item>;
    }

    switch (slot.dataType) {
      case 'STRING':
        return <Form.Item {...fieldProps}><Input placeholder={placeholder} /></Form.Item>;
      case 'DECIMAL':
      case 'DOUBLE':
        return <Form.Item {...fieldProps}><InputNumber style={{ width: '100%' }} placeholder={placeholder} step="0.01" min={slot.constraint?.min ?? undefined} max={slot.constraint?.max ?? undefined} /></Form.Item>;
      case 'LONG':
        return <Form.Item {...fieldProps}><InputNumber style={{ width: '100%' }} placeholder={placeholder} step={1} min={slot.constraint?.min ?? undefined} max={slot.constraint?.max ?? undefined} /></Form.Item>;
      case 'DATE':
        return <Form.Item {...fieldProps}><DatePicker style={{ width: '100%' }} /></Form.Item>;
      case 'DATETIME':
        return <Form.Item {...fieldProps}><DatePicker showTime style={{ width: '100%' }} /></Form.Item>;
      case 'BOOLEAN':
        return <Form.Item {...fieldProps} valuePropName="checked"><Switch /></Form.Item>;
      default:
        if (slot.constraint?.enumValues?.length) {
          return <Form.Item {...fieldProps}><Select options={slot.constraint.enumValues.map((v) => ({ value: v, label: v }))} /></Form.Item>;
        }
        return <Form.Item {...fieldProps}><Input placeholder={placeholder} /></Form.Item>;
    }
  };

  return (
    <div style={{ padding: 24, maxWidth: 800 }}>
      <Space style={{ marginBottom: 16 }}>
        <Button icon={<ArrowLeftOutlined />} onClick={() => navigate(ROUTES.TEMPLATES)}>返回</Button>
        <Title level={3} style={{ margin: 0 }}>{t('title.instantiate')}: {tmpl.template.name}</Title>
      </Space>

      <Card size="small" style={{ marginBottom: 16 }}>
        <Text type="secondary">模板: {tmpl.template.code} | Kind: {tmpl.template.kind} | Slots: {tmpl.version.slots.length}</Text>
      </Card>

      <Card>
        <Form form={form} layout="vertical">
          <Row gutter={16}>
            <Col span={12}>
              <Form.Item name="ruleCode" label={t('instantiate.ruleCode')} rules={[{ required: true }]}>
                <Input />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="ruleName" label={t('instantiate.ruleName')} rules={[{ required: true }]}>
                <Input />
              </Form.Item>
            </Col>
          </Row>
          <Form.Item label={t('instantiate.selectTenant')} required>
            <Select
              showSearch
              optionFilterProp="label"
              value={targetTenantId}
              onChange={(v) => setTargetTenantId(v)}
              options={tenants.map((t) => ({ value: t.id, label: `${t.name} (${t.code})` }))}
            />
          </Form.Item>
          <Form.Item name="sceneCode" label={t('instantiate.selectScene')} rules={[{ required: true }]}>
            <Select
              showSearch
              optionFilterProp="label"
              options={(scenes ?? []).map((s) => ({ value: s.sceneCode, label: s.name ? `${s.name} (${s.sceneCode})` : s.sceneCode }))}
            />
          </Form.Item>

          <Form.Item name="triggerEventTypes" label={t('instantiate.triggerEventTypes')}>
            <Select mode="tags" placeholder={t('instantiate.triggerEventTypes')} />
          </Form.Item>

          <Title level={5}>{t('instantiate.fillSlots')}</Title>
          {(tmpl.version.slots ?? []).map((slot) => (
            <Row key={slot.key} gutter={16}>
              <Col span={12}>{renderSlotInput(slot)}</Col>
            </Row>
          ))}
        </Form>
        <Button type="primary" size="large" icon={<ThunderboltOutlined />} loading={submitting} onClick={handleSubmit} block>
          {t('instantiate.submit') ?? '实例化'}
        </Button>
      </Card>
    </div>
  );
}
