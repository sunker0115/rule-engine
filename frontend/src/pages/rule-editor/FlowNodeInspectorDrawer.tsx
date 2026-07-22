import { useEffect, useState } from 'react';
import { Drawer, Descriptions, Select, Input, Button, Tag, Alert, Space, Spin, Modal, Form, message, Divider, Typography } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { getRule, editDraft, createRule } from '@/api/rule';
import RuleBodyEditor from './RuleBodyEditor';
import type {
  FlowNode, RuleRefNode, SwitchNode, TransformNode, OutputNode,
  SceneMetadata, DecisionItem, RuleDetail, AstNode,
} from '@/types';

/** 场景内可被 RuleRef 引用的规则精简项。 */
export interface SceneRuleItem {
  code: string;
  name: string;
  ruleDefinitionId: number;
  kind: string;
}

interface Props {
  open: boolean;
  node: FlowNode | null;
  isInput: boolean;
  onClose: () => void;
  onChangeNode: (node: FlowNode) => void;
  onSetInput: (id: string) => void;
  sceneRules: SceneRuleItem[];
  tenantId: number;
  sceneCode: string;
  metadata: SceneMetadata | null;
  decisions: DecisionItem[];
  /** 新建叶子规则成功回调（参数为新规则 code）。 */
  onLeafCreated: (code: string) => void | Promise<void>;
  /** 下钻保存被引规则草稿成功回调。 */
  onLeafSaved?: () => void;
}

/**
 * flow 节点下钻编辑抽屉：RuleRef → 复用被引规则现有编辑器（冻结隔离提示 + 可新建叶子规则）；
 * Switch/Transform/Output → 轻量表达式 / 决策码编辑。
 */
export default function FlowNodeInspectorDrawer({
  open, node, isInput, onClose, onChangeNode, onSetInput,
  sceneRules, tenantId, sceneCode, metadata, decisions, onLeafCreated, onLeafSaved,
}: Props) {
  const { t } = useTranslation('rule');
  const tc = useTranslation('common').t;

  // 被引规则下钻编辑的本地态
  const [refDetail, setRefDetail] = useState<RuleDetail | null>(null);
  const [refAst, setRefAst] = useState<AstNode | null>(null);
  const [refScript, setRefScript] = useState<{ source: string; lang: string } | null>(null);
  const [loadingRef, setLoadingRef] = useState(false);
  const [savingRef, setSavingRef] = useState(false);
  const [newLeafOpen, setNewLeafOpen] = useState(false);
  const [newLeafForm] = Form.useForm();
  const [creating, setCreating] = useState(false);

  const langs = metadata?.expressionLangs ?? ['CEL'];
  const isRuleRef = node?.type === 'RuleRefNode';
  const refRuleCode = isRuleRef ? (node as RuleRefNode).ruleCode : '';
  const refDefId = sceneRules.find((r) => r.code === refRuleCode)?.ruleDefinitionId;

  // 打开 RuleRef 且已选规则 → 拉取被引规则详情做下钻编辑
  useEffect(() => {
    if (!open || !isRuleRef || !refDefId || !tenantId) { setRefDetail(null); return; }
    setLoadingRef(true);
    getRule(tenantId, refDefId)
      .then((d) => {
        setRefDetail(d ?? null);
        setRefAst(d?.conditionAst ?? null);
        setRefScript(d?.script ?? null);
      })
      .finally(() => setLoadingRef(false));
  }, [open, isRuleRef, refDefId, tenantId]);

  const saveRef = async () => {
    if (!refDetail || !refDefId) return;
    setSavingRef(true);
    try {
      await editDraft(tenantId, refDefId, {
        conditionAst: refAst,
        script: refScript,
        kind: refDetail.kind,
        name: refDetail.name,
        decisionBindings: refDetail.decisionBindings,
        preGates: refDetail.preGates,
        triggerEventTypes: refDetail.triggerEventTypes,
      });
      message.success(tc('message.saveSuccess'));
      onLeafSaved?.();
    } catch { /* 由拦截器透出（如非草稿态不可编辑） */ }
    finally { setSavingRef(false); }
  };

  const createLeaf = async () => {
    const values = await newLeafForm.validateFields();
    setCreating(true);
    try {
      // 最小叶子：AST_BOOLEAN 空规则，建完自动被当前 RuleRef 引用，用户再下钻编辑其条件
      await createRule(tenantId, { sceneCode, code: values.code, name: values.name, kind: 'AST_BOOLEAN' });
      message.success(tc('message.createSuccess'));
      setNewLeafOpen(false);
      newLeafForm.resetFields();
      await onLeafCreated(values.code);
    } catch { /* handled by interceptor */ }
    finally { setCreating(false); }
  };

  // ---- 轻量节点属性编辑（Switch / Transform / Output）----
  const renderLightEditor = () => {
    if (!node) return null;
    if (node.type === 'SwitchNode') {
      const s = node as SwitchNode;
      return (
        <>
          <div style={{ marginBottom: 12 }}>
            <div style={{ fontSize: 12, color: '#5b6672', marginBottom: 5, fontWeight: 600 }}>{t('editor.flow.inspector.lang')}</div>
            <Select style={{ width: '100%' }} value={s.lang} options={langs.map((l) => ({ value: l, label: l }))} onChange={(lang) => onChangeNode({ ...s, lang })} />
          </div>
          <div style={{ marginBottom: 12 }}>
            <div style={{ fontSize: 12, color: '#5b6672', marginBottom: 5, fontWeight: 600 }}>{t('editor.flow.inspector.expression')}</div>
            <Input.TextArea rows={3} value={s.expression} onChange={(e) => onChangeNode({ ...s, expression: e.target.value })} />
          </div>
          <div>
            <div style={{ fontSize: 12, color: '#5b6672', marginBottom: 5, fontWeight: 600 }}>{t('editor.flow.inspector.caseKeys')}</div>
            <Select mode="tags" style={{ width: '100%' }} value={s.caseKeys} onChange={(caseKeys) => onChangeNode({ ...s, caseKeys })} placeholder={t('editor.flow.node.addCase')} />
          </div>
        </>
      );
    }
    if (node.type === 'TransformNode') {
      const tr = node as TransformNode;
      return (
        <>
          <div style={{ marginBottom: 12 }}>
            <div style={{ fontSize: 12, color: '#5b6672', marginBottom: 5, fontWeight: 600 }}>{t('editor.flow.inspector.lang')}</div>
            <Select style={{ width: '100%' }} value={tr.lang} options={langs.map((l) => ({ value: l, label: l }))} onChange={(lang) => onChangeNode({ ...tr, lang })} />
          </div>
          <div style={{ marginBottom: 12 }}>
            <div style={{ fontSize: 12, color: '#5b6672', marginBottom: 5, fontWeight: 600 }}>{t('editor.flow.inspector.outputKey')}</div>
            <Input value={tr.outputKey} onChange={(e) => onChangeNode({ ...tr, outputKey: e.target.value })} addonBefore="flow." />
          </div>
          <div>
            <div style={{ fontSize: 12, color: '#5b6672', marginBottom: 5, fontWeight: 600 }}>{t('editor.flow.inspector.expression')}</div>
            <Input.TextArea rows={3} value={tr.expression} onChange={(e) => onChangeNode({ ...tr, expression: e.target.value })} />
          </div>
        </>
      );
    }
    if (node.type === 'OutputNode') {
      const o = node as OutputNode;
      return (
        <div>
          <div style={{ fontSize: 12, color: '#5b6672', marginBottom: 5, fontWeight: 600 }}>{t('editor.flow.inspector.decisionCode')}</div>
          <Select
            style={{ width: '100%' }}
            value={o.decisionCode || undefined}
            showSearch
            optionFilterProp="label"
            options={decisions.map((d) => ({ value: d.code, label: `${d.name} (${d.code})` }))}
            onChange={(decisionCode) => onChangeNode({ ...o, decisionCode })}
          />
        </div>
      );
    }
    return null;
  };

  // ---- RuleRef 下钻编辑区 ----
  const renderRuleRef = () => {
    if (!node || node.type !== 'RuleRefNode') return null;
    const ref = node as RuleRefNode;
    return (
      <>
        <div style={{ marginBottom: 12 }}>
          <div style={{ fontSize: 12, color: '#5b6672', marginBottom: 5, fontWeight: 600 }}>{t('editor.flow.inspector.ruleCode')}</div>
          <Space.Compact style={{ width: '100%' }}>
            <Select
              style={{ width: '100%' }}
              value={ref.ruleCode || undefined}
              showSearch
              optionFilterProp="label"
              placeholder={t('editor.flow.node.selectRule')}
              options={sceneRules.map((r) => ({ value: r.code, label: `${r.name} (${r.code})` }))}
              onChange={(v) => onChangeNode({ ...ref, ruleCode: v })}
            />
            <Button icon={<PlusOutlined />} onClick={() => setNewLeafOpen(true)}>{t('editor.flow.drill.newLeaf')}</Button>
          </Space.Compact>
        </div>

        {!ref.ruleCode ? (
          <Alert type="info" showIcon message={t('editor.flow.drill.ruleRefEmpty')} />
        ) : loadingRef ? (
          <Spin style={{ display: 'block', margin: '40px auto' }} />
        ) : refDetail ? (
          <>
            <Alert
              type="warning"
              showIcon
              style={{ marginBottom: 12 }}
              message={t('editor.flow.drill.isolationNote', { code: refDetail.code })}
            />
            {refDetail.kind === 'DECISION_FLOW' ? (
              <Alert type="info" showIcon message={t('editor.flow.drill.unsupportedKind', { kind: refDetail.kind })} />
            ) : (
              <>
                <Typography.Text type="secondary" style={{ fontSize: 11, display: 'block', marginBottom: 8 }}>
                  {t('editor.flow.drill.reuseHint')} · <Tag color="blue">{t(`enum.kind.${refDetail.kind}`)}</Tag>
                </Typography.Text>
                <RuleBodyEditor
                  kind={refDetail.kind}
                  ast={refAst}
                  script={refScript}
                  onAstChange={setRefAst}
                  onScriptChange={setRefScript}
                  conditionTypes={metadata?.conditionTypes ?? []}
                  availableMetrics={metadata?.availableMetrics ?? []}
                  payloadFieldNames={metadata?.payloadFieldNames ?? []}
                  decisions={decisions}
                />
                <div style={{ marginTop: 12, textAlign: 'right' }}>
                  <Button type="primary" loading={savingRef} onClick={saveRef}>{t('editor.flow.drill.save')}</Button>
                </div>
              </>
            )}
          </>
        ) : null}
      </>
    );
  };

  const title = node ? `${t('editor.flow.drill.title')} · ${node.type.replace('Node', '')}` : t('editor.flow.inspector.title');

  return (
    <Drawer title={title} open={open} onClose={onClose} width={isRuleRef ? 560 : 400}>
      {!node ? (
        <Typography.Text type="secondary">{t('editor.flow.inspector.emptyHint')}</Typography.Text>
      ) : (
        <>
          <Descriptions column={1} size="small" style={{ marginBottom: 12 }}>
            <Descriptions.Item label={t('editor.flow.inspector.nodeId')}>
              <Tag>{node.id}</Tag>
              {isInput ? <Tag color="blue">{t('editor.flow.entry')}</Tag> : (
                <Button size="small" type="link" onClick={() => onSetInput(node.id)}>{t('editor.flow.entry')} ←</Button>
              )}
            </Descriptions.Item>
          </Descriptions>
          <Divider style={{ margin: '8px 0 16px' }} />
          {isRuleRef ? renderRuleRef() : renderLightEditor()}
        </>
      )}

      <Modal
        title={t('editor.flow.drill.newLeafTitle')}
        open={newLeafOpen}
        onOk={createLeaf}
        confirmLoading={creating}
        onCancel={() => { setNewLeafOpen(false); newLeafForm.resetFields(); }}
      >
        <Form form={newLeafForm} layout="vertical">
          <Form.Item name="code" label={t('editor.createModal.code')} rules={[{ required: true, message: tc('validation.required') }]}>
            <Input />
          </Form.Item>
          <Form.Item name="name" label={tc('label.name')} rules={[{ required: true, message: tc('validation.required') }]}>
            <Input />
          </Form.Item>
        </Form>
      </Modal>
    </Drawer>
  );
}
