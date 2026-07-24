import { useEffect, useState } from 'react';
import { Button, Card, Form, Input, Select, Switch, InputNumber, Space, message, Tag, Collapse, List, Popconfirm, Typography, Row, Col } from 'antd';
import { SaveOutlined, PlusOutlined, DeleteOutlined, ArrowLeftOutlined } from '@ant-design/icons';
import { useParams, useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useTenantStore } from '@/store/tenantStore';
import { getTemplate, updateTemplate } from '@/api/template';
import { ROUTES } from '@/constants/routes';
import type { RuleTemplate, TemplateSlot, SlotBinding, DataType } from '@/types/template';
import type { RuleBody, RuleKind } from '@/types';

const { Text } = Typography;
const AST_KINDS: RuleKind[] = ['AST_BOOLEAN', 'SCORECARD', 'DECISION_TREE', 'DECISION_TABLE'];
const DATA_TYPES: DataType[] = ['LONG', 'DOUBLE', 'DECIMAL', 'STRING', 'BOOLEAN', 'DATE', 'DATETIME', 'LIST'];

export default function TemplateEditor() {
  const { code } = useParams<{ code: string }>();
  const navigate = useNavigate();
  const { currentId } = useTenantStore();
  const { t } = useTranslation('template');
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [tmpl, setTmpl] = useState<RuleTemplate | null>(null);
  const [form] = Form.useForm();
  const [slotForm] = Form.useForm();
  const [bindingForm] = Form.useForm();
  const [slots, setSlots] = useState<TemplateSlot[]>([]);
  const [bindings, setBindings] = useState<SlotBinding[]>([]);
  // bodySkeleton 以 JSON 文本编辑，保存时解析回 RuleBody
  const [bodyText, setBodyText] = useState('');

  const editable = tmpl?.status === 'DRAFT';

  const load = async () => {
    if (!currentId || !code) return;
    setLoading(true);
    try {
      const data = await getTemplate(currentId, code);
      setTmpl(data);
      form.setFieldsValue({ name: data.name, kind: data.kind, description: data.description });
      setSlots(data.slots ?? []);
      setBindings(data.bindings ?? []);
      setBodyText(JSON.stringify(data.bodySkeleton ?? {}, null, 2));
    } finally { setLoading(false); }
  };

  useEffect(() => { load(); }, [currentId, code]);

  const handleSave = async () => {
    if (!tmpl) return;
    const values = await form.validateFields();
    let bodySkeleton: RuleBody;
    try {
      bodySkeleton = JSON.parse(bodyText) as RuleBody;
    } catch {
      message.error('Body 骨架 JSON 解析失败');
      return;
    }
    setSaving(true);
    try {
      await updateTemplate(currentId!, code!, { ...values, bodySkeleton, slots, bindings });
      message.success(t('action.saveSuccess'));
    } catch { /* interceptor */ }
    finally { setSaving(false); }
  };

  const addSlot = () => {
    const values = slotForm.getFieldsValue();
    if (!values.key || !values.label) { message.warning('请填写 slot key 和 label'); return; }
    if (slots.some((s) => s.key === values.key)) { message.warning('key 已存在'); return; }
    const enumValues = values.constraintEnum
      ? String(values.constraintEnum).split(',').map((v: string) => v.trim()).filter(Boolean)
      : undefined;
    const hasConstraint = values.constraintMin != null || values.constraintMax != null || (enumValues && enumValues.length > 0);
    setSlots([...slots, {
      key: values.key,
      label: values.label,
      dataType: (values.dataType ?? 'STRING') as DataType,
      required: values.required ?? false,
      constraint: hasConstraint ? { min: values.constraintMin, max: values.constraintMax, enumValues } : undefined,
    }]);
    slotForm.resetFields();
  };

  const addBinding = () => {
    const values = bindingForm.getFieldsValue();
    if (!values.slotKey || !values.jsonPointer) { message.warning('请选择 slot 并填写 JSON Pointer'); return; }
    setBindings([...bindings, {
      slotKey: values.slotKey,
      target: { type: 'JsonPointerTarget', jsonPointer: values.jsonPointer },
    }]);
    bindingForm.resetFields();
  };

  if (loading) return <div style={{ padding: 24 }}>加载中...</div>;
  if (!tmpl) return <div style={{ padding: 24 }}>模板不存在</div>;

  return (
    <div style={{ padding: 24 }}>
      <Space style={{ marginBottom: 16 }}>
        <Button icon={<ArrowLeftOutlined />} onClick={() => navigate(ROUTES.TEMPLATES)}>{t('action.back')}</Button>
        <h2 style={{ margin: 0 }}>{t('title.editor')}: {tmpl.name}</h2>
        <Tag color="blue">{t(`enum.status.${tmpl.status}`)}</Tag>
        <Tag>{t('enum.version')}{tmpl.version}</Tag>
      </Space>

      <Row gutter={16}>
        <Col span={12}>
          <Card title={t('form.basicInfo')} style={{ marginBottom: 16 }}>
            <Form form={form} layout="vertical">
              <Form.Item name="name" label={t('form.name')} rules={[{ required: true }]}>
                <Input />
              </Form.Item>
              <Form.Item name="kind" label={t('form.kind')}>
                <Select options={AST_KINDS.map((k) => ({ value: k, label: k }))} disabled={!editable} />
              </Form.Item>
              <Form.Item name="description" label={t('form.description')}>
                <Input.TextArea rows={2} />
              </Form.Item>
            </Form>
          </Card>
        </Col>

        <Col span={12}>
          <Card title={t('form.bodySkeleton')} style={{ marginBottom: 16 }}>
            <Input.TextArea
              value={bodyText}
              onChange={(e) => setBodyText(e.target.value)}
              readOnly={!editable}
              autoSize={{ minRows: 10, maxRows: 18 }}
              style={{ fontFamily: 'monospace', fontSize: 12 }}
            />
          </Card>
        </Col>
      </Row>

      <Card title={t('form.slots')} style={{ marginBottom: 16 }}>
        <List
          dataSource={slots}
          renderItem={(item, index) => (
            <List.Item
              actions={editable ? [
                <Popconfirm key="del" title="删除此 slot？" onConfirm={() => setSlots(slots.filter((_, i) => i !== index))}>
                  <Button size="small" danger icon={<DeleteOutlined />} />
                </Popconfirm>,
              ] : []}
            >
              <Space direction="vertical" size={2}>
                <Text strong>{item.key}</Text>
                <Text type="secondary">{item.label} — {item.dataType} {item.required ? `(${t('form.slotRequired')})` : ''}</Text>
              </Space>
            </List.Item>
          )}
        />
        {editable && (
          <Collapse ghost items={[{
            key: 'add', label: t('form.addSlot'), children: (
              <Form form={slotForm} layout="inline" style={{ flexWrap: 'wrap', gap: 8 }}>
                <Form.Item name="key" label={t('form.slotKey')} rules={[{ required: true }]}>
                  <Input style={{ width: 100 }} placeholder={t('form.slotKeyPlaceholder')} />
                </Form.Item>
                <Form.Item name="label" label={t('form.slotLabel')} rules={[{ required: true }]}>
                  <Input style={{ width: 100 }} placeholder={t('form.slotLabelPlaceholder')} />
                </Form.Item>
                <Form.Item name="dataType" label={t('form.slotDataType')} initialValue="STRING">
                  <Select style={{ width: 110 }} options={DATA_TYPES.map((v) => ({ value: v, label: t(`enum.dataType.${v}`) }))} />
                </Form.Item>
                <Form.Item name="required" label={t('form.slotRequired')} valuePropName="checked">
                  <Switch />
                </Form.Item>
                <Form.Item name="constraintEnum" label="Enum">
                  <Input style={{ width: 120 }} placeholder="a,b,c" />
                </Form.Item>
                <Form.Item name="constraintMin" label="Min">
                  <InputNumber style={{ width: 80 }} />
                </Form.Item>
                <Form.Item name="constraintMax" label="Max">
                  <InputNumber style={{ width: 80 }} />
                </Form.Item>
                <Button type="dashed" icon={<PlusOutlined />} onClick={addSlot}>{t('form.addSlot')}</Button>
              </Form>
            ),
          }]} />
        )}
      </Card>

      <Card title={t('form.bindings')} style={{ marginBottom: 16 }}>
        <List
          dataSource={bindings}
          renderItem={(item, index) => (
            <List.Item
              actions={editable ? [
                <Popconfirm key="del" title="删除此绑定？" onConfirm={() => setBindings(bindings.filter((_, i) => i !== index))}>
                  <Button size="small" danger icon={<DeleteOutlined />} />
                </Popconfirm>,
              ] : []}
            >
              <Space direction="vertical" size={2}>
                <Text strong>{item.slotKey}</Text>
                <Text type="secondary" code>{item.target.jsonPointer}</Text>
              </Space>
            </List.Item>
          )}
        />
        {editable && (
          <Collapse ghost items={[{
            key: 'add', label: t('form.addBinding'), children: (
              <Form form={bindingForm} layout="inline" style={{ flexWrap: 'wrap', gap: 8 }}>
                <Form.Item name="slotKey" label={t('form.bindingSlot')} rules={[{ required: true }]}>
                  <Select style={{ width: 140 }} options={slots.map((s) => ({ value: s.key, label: s.key }))} />
                </Form.Item>
                <Form.Item name="jsonPointer" label={t('form.jsonPointer')} rules={[{ required: true }]}>
                  <Input style={{ width: 320 }} placeholder={t('form.jsonPointerPlaceholder')} />
                </Form.Item>
                <Button type="dashed" icon={<PlusOutlined />} onClick={addBinding}>{t('form.addBinding')}</Button>
              </Form>
            ),
          }]} />
        )}
      </Card>

      {editable && (
        <Button type="primary" icon={<SaveOutlined />} loading={saving} onClick={handleSave}>
          {t('action.save')}
        </Button>
      )}
    </div>
  );
}
