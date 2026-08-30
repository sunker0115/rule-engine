import { Tabs, Select, Typography } from 'antd';
import { useTranslation } from 'react-i18next';
import { useRuleStore } from '@/store/ruleStore';
import RolloutSlider from '@/components/rollout-slider';
import TimeWindowPicker from '@/components/time-window-picker';
import DecisionBindingEditor from './DecisionBindingEditor';
import FlowNodeInspector from './FlowNodeInspector';
import type {
  PreGate,
  RuleDetail as RuleDetailType, SceneMetadata as SceneMetadataType,
  FlowNode,
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
  const {
    preGates, decisionBindings, script, flowGraph,
    setPreGates, setDecisionBindings, setScript, setFlowGraph,
    selectedFlowNodeId, selectedFlowEdgeIndex,
    setSelectedFlowNodeId, setSelectedFlowEdgeIndex, setDrillFlowNodeId, flowSceneRules,
  } = useRuleStore();

  const isFlow = ruleDetail.kind === 'DECISION_FLOW';
  const graph = flowGraph ?? { nodes: [], edges: [], inputNodeId: '' };

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

  // ---- FlowNodeInspector 回调：操作 store 里的 flowGraph ----
  const handleChangeNode = (updated: FlowNode) => {
    setFlowGraph({ ...graph, nodes: graph.nodes.map((n) => n.id === updated.id ? updated : n) });
  };
  const handleDeleteNode = (id: string) => {
    const remainSet = new Set(graph.nodes.map((n) => n.id).filter((nid) => nid !== id));
    setFlowGraph({
      nodes: graph.nodes.filter((n) => remainSet.has(n.id)),
      edges: graph.edges.filter((e) => remainSet.has(e.from) && remainSet.has(e.to)),
      inputNodeId: remainSet.has(graph.inputNodeId) ? graph.inputNodeId : (graph.nodes.find((n) => remainSet.has(n.id))?.id ?? ''),
    });
    setSelectedFlowNodeId(null);
    setSelectedFlowEdgeIndex(null);
  };
  const handleChangeEdge = (index: number, caseKey: string | null) => {
    setFlowGraph({ ...graph, edges: graph.edges.map((e, i) => i === index ? { ...e, caseKey } : e) as typeof graph.edges });
  };
  const handleSetInput = (id: string) => { setFlowGraph({ ...graph, inputNodeId: id }); };

  // ---- tabs ----
  const tabItems = [
    // flow 节点属性（仅 DECISION_FLOW）——规则编辑器传 store 值
    ...(isFlow ? [{
      key: 'flowNode',
      label: t('editor.flow.inspector.title'),
      children: (
        <div style={{ padding: 12 }}>
          {(selectedFlowNodeId || selectedFlowEdgeIndex !== null)
            ? <FlowNodeInspector
                graph={graph}
                selectedNodeId={selectedFlowNodeId}
                selectedEdgeIndex={selectedFlowEdgeIndex}
                decisions={[]}
                sceneRules={flowSceneRules}
                expressionLangs={metadata?.expressionLangs ?? ['CEL']}
                onChangeNode={handleChangeNode}
                onChangeEdge={handleChangeEdge}
                onDeleteNode={handleDeleteNode}
                onSetInput={handleSetInput}
                onDrillNode={setDrillFlowNodeId}
              />
            : <Typography.Text type="secondary" style={{ fontSize: 12 }}>{t('editor.flow.rightPanel.emptyHint')}</Typography.Text>
          }
        </div>
      ),
    }] : []),
    ...(ruleDetail.kind === 'EXPRESSION_SCRIPT' ? [{
      key: 'executor',
      label: t('editor.rightPanel.executor'),
      children: (
        <div style={{ padding: 16 }}>
          <Select size="small" style={{ width: '100%', marginBottom: 12 }} value={currentLang} onChange={(lang) => setScript({ lang, source: script?.source ?? '', params: script?.params })} options={langOptions} />
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
