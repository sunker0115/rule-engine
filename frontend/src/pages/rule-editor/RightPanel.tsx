import { Tabs, Select, Typography, Input, Button, Tag, Popconfirm } from 'antd';
import { DeleteOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { useRuleStore } from '@/store/ruleStore';
import RolloutSlider from '@/components/rollout-slider';
import TimeWindowPicker from '@/components/time-window-picker';
import DecisionBindingEditor from './DecisionBindingEditor';
import type {
  PreGate, SwitchNode, TransformNode, OutputNode, RuleRefNode,
  RuleDetail as RuleDetailType, SceneMetadata as SceneMetadataType,
} from '@/types';

interface Props {
  metadata: SceneMetadataType | null;
  ruleDetail: RuleDetailType;
}

/**
 * 统一右栏面板：非 DECISION_FLOW 展示原有的 executor / preGate / binding tabs；
 * DECISION_FLOW 时顶部展示选中节点属性（选中画布节点时），下方保留 preGate tab。
 */
export default function RightPanel({ metadata, ruleDetail }: Props) {
  const { t } = useTranslation('rule');
  const tc = useTranslation('common').t;
  const {
    preGates, decisionBindings, script, flowGraph,
    setPreGates, setDecisionBindings, setScript, setFlowGraph,
    selectedFlowNodeId, selectedFlowEdgeIndex,
    setSelectedFlowNodeId, setSelectedFlowEdgeIndex, setDrillFlowNodeId, flowSceneRules,
  } = useRuleStore();

  const isFlow = ruleDetail.kind === 'DECISION_FLOW';
  const graph = flowGraph ?? { nodes: [], edges: [], inputNodeId: '' };
  const selectedNode = isFlow && selectedFlowNodeId ? graph.nodes.find((n) => n.id === selectedFlowNodeId) ?? null : null;
  const selectedEdge = isFlow && selectedFlowEdgeIndex !== null ? graph.edges[selectedFlowEdgeIndex] ?? null : null;
  const selectedEdgeSrc = selectedEdge ? graph.nodes.find((n) => n.id === selectedEdge.from) ?? null : null;

  // 评分卡/决策树/决策表决策由自身结构内联，DECISION_FLOW 决策由 Output 节点内联，均不单独配置绑定
  const showBinding =
    ruleDetail.kind !== 'DECISION_TREE' && ruleDetail.kind !== 'DECISION_TABLE' &&
    ruleDetail.kind !== 'SCORECARD' && ruleDetail.kind !== 'DECISION_FLOW';

  const langs = metadata?.expressionLangs ?? ['CEL'];
  const langOptions = langs.map((l) => ({ value: l, label: l }));
  const currentLang = script?.lang ?? langs[0] ?? 'CEL';

  const upsertGate = (gate: PreGate) => {
    const others = (preGates ?? []).filter((g) => g.gateType !== gate.gateType);
    const isEmpty = Object.values(gate.params).every((v) => v === undefined || v === null);
    setPreGates(isEmpty ? others : [...others, gate]);
  };

  const rolloutParams = preGates?.find((g): g is Extract<PreGate, { gateType: 'ROLLOUT' }> => g.gateType === 'ROLLOUT')?.params ?? {};
  const timeWindowParams = preGates?.find((g): g is Extract<PreGate, { gateType: 'TIME_WINDOW' }> => g.gateType === 'TIME_WINDOW')?.params ?? {};

  // flow 节点/边更新
  const updateNode = (updated: typeof graph.nodes[0]) => {
    setFlowGraph({ ...graph, nodes: graph.nodes.map((n) => (n.id === updated.id ? updated : n)) });
  };
  const deleteNode = (id: string) => {
    const remainSet = new Set(graph.nodes.map((n) => n.id).filter((nid) => nid !== id));
    setFlowGraph({
      nodes: graph.nodes.filter((n) => remainSet.has(n.id)),
      edges: graph.edges.filter((e) => remainSet.has(e.from) && remainSet.has(e.to)),
      inputNodeId: remainSet.has(graph.inputNodeId) ? graph.inputNodeId : (graph.nodes.find((n) => remainSet.has(n.id))?.id ?? ''),
    });
    setSelectedFlowNodeId(null);
    setSelectedFlowEdgeIndex(null);
  };
  const updateEdge = (index: number, caseKey: string | null) => {
    setFlowGraph({ ...graph, edges: graph.edges.map((e, i) => i === index ? { ...e, caseKey } : e) as typeof graph.edges });
  };
  const setAsInput = (id: string) => { setFlowGraph({ ...graph, inputNodeId: id }); };

  // ---- flow 节点属性编辑区 ----
  const renderFlowNodeEditor = () => {
    if (selectedEdge) {
      return (
        <div style={{ padding: '10px 0' }}>
          <div style={{ fontSize: 11, color: '#5b6672', marginBottom: 4, fontWeight: 600 }}>
            {selectedEdgeSrc?.type === 'RuleRefNode' ? t('editor.flow.inspector.ruleResult') : t('editor.flow.inspector.caseKeys')}
          </div>
          <Select
            size="small" style={{ width: '100%' }}
            value={selectedEdge.caseKey ?? '__none__'}
            options={[
              { value: '__none__', label: '— 无条件（默认出边）' },
              ...(selectedEdgeSrc?.type === 'RuleRefNode'
                ? [{ value: 'true', label: 'true — 规则命中' } as const, { value: 'false', label: 'false — 规则未命中' } as const]
                : selectedEdgeSrc?.type === 'SwitchNode'
                  ? (selectedEdgeSrc as SwitchNode).caseKeys.map((k) => ({ value: k, label: k }))
                  : []
              ),
            ]}
            onChange={(v) => updateEdge(selectedFlowEdgeIndex!, v === '__none__' ? null : v)}
          />
          <div style={{ fontSize: 10, color: '#8c959f', marginTop: 4 }}>{selectedEdge.from} → {selectedEdge.to}</div>
        </div>
      );
    }

    if (!selectedNode) {
      return <Typography.Text type="secondary" style={{ fontSize: 12 }}>{t('editor.flow.rightPanel.emptyHint')}</Typography.Text>;
    }

    const isInput = selectedNode.id === graph.inputNodeId;
    return (
      <>
        <div style={{ marginBottom: 8 }}>
          {isInput ? <Tag color="blue">{t('editor.flow.entry')}</Tag> : <Button size="small" type="link" onClick={() => setAsInput(selectedNode.id)} style={{ padding: 0 }}>{t('editor.flow.entry')} ←</Button>}
        </div>

        {selectedNode.type === 'SwitchNode' && (() => {
          const s = selectedNode as SwitchNode;
          return (<>
            <div style={{ marginBottom: 10 }}>
              <div style={{ fontSize: 10.5, color: '#5b6672', marginBottom: 3, fontWeight: 600 }}>{t('editor.flow.inspector.lang')}</div>
              <Select size="small" style={{ width: '100%' }} value={s.lang} options={langOptions} onChange={(lang) => updateNode({ ...s, lang })} />
            </div>
            <div style={{ marginBottom: 10 }}>
              <div style={{ fontSize: 10.5, color: '#5b6672', marginBottom: 3, fontWeight: 600 }}>{t('editor.flow.inspector.expression')}</div>
              <Input.TextArea rows={3} value={s.expression} onChange={(e) => updateNode({ ...s, expression: e.target.value })} />
            </div>
            <div>
              <div style={{ fontSize: 10.5, color: '#5b6672', marginBottom: 3, fontWeight: 600 }}>{t('editor.flow.inspector.caseKeys')}</div>
              <Select mode="tags" size="small" style={{ width: '100%' }} value={s.caseKeys} onChange={(caseKeys) => updateNode({ ...s, caseKeys })} placeholder={t('editor.flow.node.addCase')} />
            </div>
          </>);
        })()}

        {selectedNode.type === 'TransformNode' && (() => {
          const tr = selectedNode as TransformNode;
          return (<>
            <div style={{ marginBottom: 10 }}>
              <div style={{ fontSize: 10.5, color: '#5b6672', marginBottom: 3, fontWeight: 600 }}>{t('editor.flow.inspector.lang')}</div>
              <Select size="small" style={{ width: '100%' }} value={tr.lang} options={langOptions} onChange={(lang) => updateNode({ ...tr, lang })} />
            </div>
            <div style={{ marginBottom: 10 }}>
              <div style={{ fontSize: 10.5, color: '#5b6672', marginBottom: 3, fontWeight: 600 }}>{t('editor.flow.inspector.outputKey')}</div>
              <Input size="small" value={tr.outputKey} onChange={(e) => updateNode({ ...tr, outputKey: e.target.value })} addonBefore="flow." />
            </div>
            <div>
              <div style={{ fontSize: 10.5, color: '#5b6672', marginBottom: 3, fontWeight: 600 }}>{t('editor.flow.inspector.expression')}</div>
              <Input.TextArea rows={3} value={tr.expression} onChange={(e) => updateNode({ ...tr, expression: e.target.value })} />
            </div>
          </>);
        })()}

        {selectedNode.type === 'OutputNode' && (() => {
          const o = selectedNode as OutputNode;
          return (
            <div>
              <div style={{ fontSize: 10.5, color: '#5b6672', marginBottom: 3, fontWeight: 600 }}>{t('editor.flow.inspector.decisionCode')}</div>
              <Input size="small" value={o.decisionCode} onChange={(e) => updateNode({ ...o, decisionCode: e.target.value })} />
            </div>
          );
        })()}

        {selectedNode.type === 'RuleRefNode' && (() => {
          const ref = selectedNode as RuleRefNode;
          return (
            <div style={{ marginBottom: 10 }}>
              <div style={{ fontSize: 10.5, color: '#5b6672', marginBottom: 3, fontWeight: 600 }}>{t('editor.flow.inspector.ruleCode')}</div>
              <Select size="small" style={{ width: '100%', marginBottom: 8 }}
                value={ref.ruleCode || undefined}
                showSearch optionFilterProp="label"
                placeholder={t('editor.flow.node.selectRule')}
                options={Object.entries(
                  flowSceneRules.reduce((acc, r) => {
                    const sc = r.sceneCode ?? '';
                    (acc[sc] = acc[sc] ?? []).push(r);
                    return acc;
                  }, {} as Record<string, typeof flowSceneRules>),
                ).map(([sc, items]) => ({
                  label: sc || '—',
                  options: items.map((r) => ({ value: r.code, label: `${r.name} (${r.code})` })),
                }))}
                onChange={(v) => updateNode({ ...ref, ruleCode: v })} />
              <Button size="small" type="link" disabled={!ref.ruleCode} onClick={() => setDrillFlowNodeId(selectedNode.id)}>
                {t('editor.flow.drill.title')} ›
              </Button>
            </div>
          );
        })()}

        <div style={{ marginTop: 20, paddingTop: 12, borderTop: '1px solid #f0f0f0' }}>
          <Popconfirm title={tc('confirmDelete')} onConfirm={() => deleteNode(selectedNode.id)} okText={tc('confirm')} cancelText={tc('cancel')} okButtonProps={{ danger: true }}>
            <Button danger size="small" icon={<DeleteOutlined />} block>{tc('delete')}</Button>
          </Popconfirm>
        </div>
      </>
    );
  };

  // ---- tabs ----
  const tabItems = [
    // flow 节点属性（仅 DECISION_FLOW）
    ...(isFlow ? [{
      key: 'flowNode',
      label: t('editor.flow.inspector.title'),
      children: <div style={{ padding: 12 }}>{renderFlowNodeEditor()}</div>,
    }] : []),
    ...(ruleDetail.kind === 'EXPRESSION_SCRIPT' ? [{
      key: 'executor',
      label: t('editor.rightPanel.executor'),
      children: (
        <div style={{ padding: 16 }}>
          <Select size="small" style={{ width: '100%', marginBottom: 12 }} value={currentLang} onChange={(lang) => setScript({ lang, source: script?.source ?? '' })} options={langOptions} />
          <Typography.Paragraph type="secondary" style={{ fontSize: 12, whiteSpace: 'pre-line', background: '#f5f5f5', padding: 8, borderRadius: 4, margin: 0 }}>
            {t(`editor.script.syntaxHints.${currentLang}`)}
          </Typography.Paragraph>
        </div>
      ),
    }] : []),
    {
      key: 'pregate',
      label: t('editor.rightPanel.preGate'),
      children: (
        <div style={{ padding: 12 }}>
          <Typography.Title level={5} style={{ marginTop: 0, fontSize: 13 }}>
            {t('preGate.rolloutTitle')}
          </Typography.Title>
          <RolloutSlider value={rolloutParams} onChange={(params) => upsertGate({ gateType: 'ROLLOUT', params })} />
          <Typography.Title level={5} style={{ marginTop: 20, fontSize: 13 }}>
            {t('preGate.timeWindowTitle')}
          </Typography.Title>
          <TimeWindowPicker value={timeWindowParams} onChange={(params) => upsertGate({ gateType: 'TIME_WINDOW', params })} />
        </div>
      ),
    },
    ...(showBinding ? [{
      key: 'binding',
      label: t('editor.rightPanel.decisionBinding'),
      children: (
        <div style={{ padding: 12 }}>
          <DecisionBindingEditor kind={ruleDetail.kind} value={decisionBindings} onChange={setDecisionBindings} />
        </div>
      ),
    }] : []),
  ];

  return <Tabs items={tabItems} />;
}
