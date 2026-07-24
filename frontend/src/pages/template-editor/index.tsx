import { useEffect, useMemo, useState } from 'react';
import { Button, Card, Form, Input, Select, Switch, InputNumber, Space, message, Tag, List, Popconfirm, Typography, Row, Col, Alert, Empty } from 'antd';
import { SaveOutlined, DeleteOutlined, ArrowLeftOutlined, PlusOutlined } from '@ant-design/icons';
import { useParams, useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useTenantStore } from '@/store/tenantStore';
import { useRuleStore } from '@/store/ruleStore';
import { getTemplate, updateTemplate } from '@/api/template';
import { listScenes, getScene } from '@/api/scene';
import { getSceneMetadata } from '@/api/metadata';
import { listDecisions } from '@/api/decision';
import { listRules } from '@/api/rule';
import { extractPayloadSchema } from '@/utils/payloadSchema';
import { getRuleKindOptions } from '@/constants/enums';
import { ROUTES } from '@/constants/routes';
import { bodyToCarriers, carriersToBody } from '@/types';
import type { RuleTemplate, TemplateSlot, SlotBinding, SlotConstraint, DataType } from '@/types/template';
import type { RuleBody, RuleKind, SceneListItem, SceneMetadata, DecisionItem } from '@/types';
import RuleBodyEditor from '@/pages/rule-editor/RuleBodyEditor';
import FlowCanvasEditor from '@/pages/rule-editor/FlowCanvasEditor';
import { introspectPositions } from './introspect';

const { Text } = Typography;
const DATA_TYPES: DataType[] = ['LONG', 'DOUBLE', 'DECIMAL', 'STRING', 'BOOLEAN', 'DATE', 'DATETIME', 'LIST'];
const EMPTY_BODY: RuleBody = { type: 'AstBody', conditionAst: null };

/** 由 JsonPointer 末段（数字段回退父段）派生一个稳定、去重的 slotKey。 */
function deriveSlotKey(jsonPointer: string, taken: Set<string>): string {
  const seg = jsonPointer.split('/').filter(Boolean);
  let base = seg[seg.length - 1] || 'slot';
  if (/^\d+$/.test(base)) base = seg[seg.length - 2] ?? 'slot';
  base = base.replace(/[^A-Za-z0-9_]/g, '_') || 'slot';
  let key = base;
  let i = 1;
  while (taken.has(key)) key = `${base}_${i++}`;
  return key;
}

export default function TemplateEditor() {
  const { code } = useParams<{ code: string }>();
  const navigate = useNavigate();
  const { currentId } = useTenantStore();
  const { setFlowSceneRules } = useRuleStore();
  const { t } = useTranslation('template');
  // enum.kind.* 键在 rule ns，复用 rule 命名空间 t（与 template-list 单一真相源一致）
  const tr = useTranslation('rule').t;
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [tmpl, setTmpl] = useState<RuleTemplate | null>(null);
  const [form] = Form.useForm();
  const [slots, setSlots] = useState<TemplateSlot[]>([]);
  const [bindings, setBindings] = useState<SlotBinding[]>([]);
  // bodySkeleton 受控：经 body 编辑器直接编辑，保存直传（不再 JSON.parse）
  const [bodySkeleton, setBodySkeleton] = useState<RuleBody>(EMPTY_BODY);
  // 参照场景：仅取元数据辅助编辑，不写入模板
  const [refSceneCode, setRefSceneCode] = useState<string | undefined>(undefined);
  const [scenes, setScenes] = useState<SceneListItem[]>([]);
  const [metadata, setMetadata] = useState<SceneMetadata | null>(null);
  const [decisions, setDecisions] = useState<DecisionItem[]>([]);

  const editable = tmpl?.status === 'DRAFT';
  const kind: RuleKind = tmpl?.kind ?? 'AST_BOOLEAN';

  const load = async () => {
    if (!currentId || !code) return;
    setLoading(true);
    try {
      const data = await getTemplate(currentId, code);
      setTmpl(data);
      form.setFieldsValue({ name: data.name, kind: data.kind, description: data.description });
      setSlots(data.slots ?? []);
      setBindings(data.bindings ?? []);
      setBodySkeleton(data.bodySkeleton ?? EMPTY_BODY);
    } finally { setLoading(false); }
  };

  useEffect(() => { load(); }, [currentId, code]);

  // 租户级资源：场景列表（参照场景下拉）+ 决策列表（下传 body 编辑器）
  useEffect(() => {
    if (!currentId) return;
    listScenes(currentId).then((d) => setScenes(d ?? []));
    listDecisions(currentId).then((d) => setDecisions(d ?? []));
  }, [currentId]);

  // DECISION_FLOW：拉取 tenant 全量已发布规则写入 store，供画布 RuleRef 选取（照 CenterPanel）
  useEffect(() => {
    if (!currentId || kind !== 'DECISION_FLOW') return;
    listRules(currentId, undefined, { page: 1, size: 500 }).then((data) => {
      setFlowSceneRules((data.items ?? [])
        .filter((r) => r.code !== tmpl?.code && r.status === 'PUBLISHED' && r.kind !== 'DECISION_FLOW')
        .map((r) => ({ code: r.code, name: r.name, ruleDefinitionId: r.ruleDefinitionId, kind: r.kind, sceneCode: r.sceneCode })));
    });
  }, [currentId, kind, tmpl?.code, setFlowSceneRules]);

  // 选参照场景：拉 metadata + payloadSchema（接线照 rule-editor index）
  const handleRefScene = async (sceneCode: string | undefined) => {
    setRefSceneCode(sceneCode);
    if (!currentId || !sceneCode) { setMetadata(null); return; }
    const [metaRes, sceneRes] = await Promise.all([
      getSceneMetadata(currentId, sceneCode),
      getScene(currentId, sceneCode),
    ]);
    const meta = metaRes ?? null;
    if (meta) {
      const schema = extractPayloadSchema(sceneRes?.payloadSchema);
      meta.payloadFieldNames = schema.names;
      meta.payloadFieldTypes = schema.types;
    }
    setMetadata(meta);
  };

  const carriers = bodyToCarriers(bodySkeleton);

  // Script 参数化开关的真相源：绑到 /script/params/<key> 的 binding，取指针尾段还原 param 键
  const slottedParamKeys = useMemo(
    () => bindings
      .filter((b) => b.target.jsonPointer.startsWith('/script/params/'))
      .map((b) => b.target.jsonPointer.slice('/script/params/'.length)),
    [bindings],
  );

  // 可参数化位置候选（AST/Flow）；排除已绑 pointer
  const candidates = useMemo(() => introspectPositions(kind, bodySkeleton), [kind, bodySkeleton]);
  const boundPointers = new Set(bindings.map((b) => b.target.jsonPointer));
  const availableCandidates = candidates.filter((c) => !boundPointers.has(c.jsonPointer));

  // AST/Flow 入口：选位置 → push slot + binding
  const handleAddCandidate = (jsonPointer: string) => {
    const c = candidates.find((x) => x.jsonPointer === jsonPointer);
    if (!c) return;
    const key = deriveSlotKey(c.jsonPointer, new Set(slots.map((s) => s.key)));
    setSlots((prev) => [...prev, { key, label: c.label, dataType: c.dataType, required: false }]);
    setBindings((prev) => [...prev, { slotKey: key, target: { type: 'JsonPointerTarget', jsonPointer: c.jsonPointer } }]);
  };

  // Script 入口：参数表开关 → slotKey==param 键，binding 指向 /script/params/<key>
  const handleParamSlotToggle = (key: string, enabled: boolean, dataType: DataType) => {
    const jsonPointer = `/script/params/${key}`;
    setBindings((prev) => {
      const exists = prev.some((b) => b.target.jsonPointer === jsonPointer);
      if (enabled && !exists) return [...prev, { slotKey: key, target: { type: 'JsonPointerTarget', jsonPointer } }];
      if (!enabled) return prev.filter((b) => b.target.jsonPointer !== jsonPointer);
      return prev;
    });
    setSlots((prev) => {
      const exists = prev.some((s) => s.key === key);
      if (enabled && !exists) return [...prev, { key, label: key, dataType, required: false }];
      if (!enabled) return prev.filter((s) => s.key !== key);
      return prev;
    });
  };

  const removeSlot = (key: string) => {
    setSlots((prev) => prev.filter((s) => s.key !== key));
    setBindings((prev) => prev.filter((b) => b.slotKey !== key));
  };

  const updateSlot = (key: string, patch: Partial<TemplateSlot>) => {
    setSlots((prev) => prev.map((s) => (s.key === key ? { ...s, ...patch } : s)));
  };

  const setSlotConstraint = (key: string, partial: Partial<SlotConstraint>) => {
    setSlots((prev) => prev.map((s) => {
      if (s.key !== key) return s;
      const merged: SlotConstraint = { ...(s.constraint ?? {}), ...partial };
      const empty = merged.min == null && merged.max == null && !(merged.enumValues && merged.enumValues.length);
      return { ...s, constraint: empty ? null : merged };
    }));
  };

  const handleSave = async () => {
    if (!tmpl) return;
    const values = await form.validateFields();
    setSaving(true);
    try {
      await updateTemplate(currentId!, code!, { ...values, bodySkeleton, slots, bindings });
      message.success(t('action.saveSuccess'));
    } catch { /* interceptor */ }
    finally { setSaving(false); }
  };

  const renderBodyEditor = () => {
    if (kind === 'DECISION_FLOW') {
      return (
        <FlowCanvasEditor
          value={carriers.flowGraph}
          onChange={(g) => setBodySkeleton(carriersToBody('DECISION_FLOW', { flowGraph: g }))}
          sceneCode={refSceneCode ?? ''}
          ruleCode={tmpl?.code ?? ''}
          tenantId={currentId ?? 0}
          metadata={metadata}
          decisions={decisions}
        />
      );
    }
    return (
      <RuleBodyEditor
        kind={kind}
        ast={carriers.conditionAst}
        script={carriers.script}
        onAstChange={(ast) => setBodySkeleton(carriersToBody(kind, { conditionAst: ast }))}
        onScriptChange={(s) => setBodySkeleton(carriersToBody(kind, { script: s }))}
        conditionTypes={metadata?.conditionTypes ?? []}
        availableMetrics={metadata?.availableMetrics ?? []}
        payloadFieldNames={metadata?.payloadFieldNames ?? []}
        payloadFieldTypes={metadata?.payloadFieldTypes}
        decisions={decisions}
        tenantId={currentId ?? undefined}
        sceneCode={refSceneCode}
        editableParams={editable}
        onParamSlotToggle={handleParamSlotToggle}
        slottedParamKeys={slottedParamKeys}
      />
    );
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
                <Input disabled={!editable} />
              </Form.Item>
              <Form.Item name="kind" label={t('form.kind')}>
                <Select options={getRuleKindOptions(tr)} disabled />
              </Form.Item>
              <Form.Item name="description" label={t('form.description')}>
                <Input.TextArea rows={2} disabled={!editable} />
              </Form.Item>
            </Form>
          </Card>
        </Col>

        <Col span={12}>
          <Card title={t('form.referenceScene')} style={{ marginBottom: 16 }}>
            <Select
              showSearch
              allowClear
              style={{ width: '100%' }}
              placeholder={t('form.referenceScenePlaceholder')}
              optionFilterProp="label"
              value={refSceneCode}
              onChange={handleRefScene}
              options={scenes.map((s) => ({ value: s.sceneCode, label: s.name ? `${s.name} (${s.sceneCode})` : s.sceneCode }))}
            />
            <Text type="secondary" style={{ display: 'block', marginTop: 8, fontSize: 12 }}>
              {t('form.referenceSceneHint')}
            </Text>
          </Card>
        </Col>
      </Row>

      <Card title={t('form.bodySkeleton')} style={{ marginBottom: 16 }}>
        {!metadata && kind !== 'DECISION_FLOW' && (
          <Alert type="info" showIcon style={{ marginBottom: 12 }} message={t('form.referenceScenePlaceholder')} />
        )}
        <div style={editable ? undefined : { pointerEvents: 'none', opacity: 0.6 }}>
          {kind === 'DECISION_FLOW'
            ? <div style={{ height: 520, border: '1px solid #f0f0f0', borderRadius: 6 }}>{renderBodyEditor()}</div>
            : renderBodyEditor()}
        </div>
      </Card>

      <Card title={t('form.slots')} style={{ marginBottom: 16 }}>
        {editable && (
          kind === 'EXPRESSION_SCRIPT'
            ? <Alert type="info" showIcon style={{ marginBottom: 12 }} message={t('form.parameterizeScriptHint')} />
            : (
              <Space style={{ marginBottom: 12 }}>
                <Text><PlusOutlined /> {t('form.parameterize')}</Text>
                <Select
                  showSearch
                  style={{ width: 420 }}
                  placeholder={t('form.parameterizePlaceholder')}
                  optionFilterProp="label"
                  value={null}
                  onChange={(ptr) => ptr && handleAddCandidate(ptr as string)}
                  options={availableCandidates.map((c) => ({ value: c.jsonPointer, label: c.label }))}
                  notFoundContent={<Empty image={Empty.PRESENTED_IMAGE_SIMPLE} />}
                />
              </Space>
            )
        )}
        <List
          dataSource={slots}
          locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} /> }}
          renderItem={(item) => (
            <List.Item
              actions={editable ? [
                <Popconfirm key="del" title={`${t('action.disable')} ${item.key}?`} onConfirm={() => removeSlot(item.key)}>
                  <Button size="small" danger icon={<DeleteOutlined />} />
                </Popconfirm>,
              ] : []}
            >
              <Space direction="vertical" size={6} style={{ width: '100%' }}>
                <Space wrap align="center">
                  <Text strong code>{item.key}</Text>
                  <Input
                    size="small" style={{ width: 180 }} disabled={!editable}
                    value={item.label} placeholder={t('form.slotLabel')}
                    onChange={(e) => updateSlot(item.key, { label: e.target.value })}
                  />
                  <Select
                    size="small" style={{ width: 110 }} disabled={!editable}
                    value={item.dataType}
                    options={DATA_TYPES.map((v) => ({ value: v, label: t(`enum.dataType.${v}`) }))}
                    onChange={(v) => updateSlot(item.key, { dataType: v })}
                  />
                  <span>{t('form.slotRequired')} <Switch size="small" disabled={!editable} checked={item.required} onChange={(c) => updateSlot(item.key, { required: c })} /></span>
                </Space>
                <Space wrap>
                  <Input
                    size="small" style={{ width: 200 }} disabled={!editable}
                    addonBefore={t('form.slotEnum')} placeholder="a,b,c"
                    value={item.constraint?.enumValues?.join(',') ?? ''}
                    onChange={(e) => setSlotConstraint(item.key, { enumValues: e.target.value.split(',').map((v) => v.trim()).filter(Boolean) })}
                  />
                  <InputNumber
                    size="small" disabled={!editable} addonBefore={t('form.slotMin')}
                    value={item.constraint?.min ?? undefined}
                    onChange={(v) => setSlotConstraint(item.key, { min: (v as number) ?? null })}
                  />
                  <InputNumber
                    size="small" disabled={!editable} addonBefore={t('form.slotMax')}
                    value={item.constraint?.max ?? undefined}
                    onChange={(v) => setSlotConstraint(item.key, { max: (v as number) ?? null })}
                  />
                </Space>
              </Space>
            </List.Item>
          )}
        />
      </Card>

      {editable && (
        <Button type="primary" icon={<SaveOutlined />} loading={saving} onClick={handleSave}>
          {t('action.save')}
        </Button>
      )}
    </div>
  );
}
