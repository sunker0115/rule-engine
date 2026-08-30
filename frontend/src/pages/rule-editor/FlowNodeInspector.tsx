import { Select, Input, Button, Tag, Popconfirm } from 'antd';
import { DeleteOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { FlowGraph, FlowNode, DecisionItem, SwitchNode, TransformNode, OutputNode, RuleRefNode } from '@/types';
import type { SceneRuleItem } from './FlowNodeInspectorDrawer';

interface Props {
  /** 当前 flow 图（用于查找选中节点/边及入口） */
  graph: FlowGraph;
  selectedNodeId: string | null;
  selectedEdgeIndex: number | null;
  /** 可用决策列表（供 OutputNode 选择） */
  decisions: DecisionItem[];
  /** 可引用的已发布规则列表（供 RuleRefNode 选择） */
  sceneRules: SceneRuleItem[];
  /** 可用表达式引擎列表 */
  expressionLangs: string[];
  /** 节点属性变更 */
  onChangeNode: (updated: FlowNode) => void;
  /** 边 caseKey 变更 */
  onChangeEdge: (index: number, caseKey: string | null) => void;
  /** 删除节点 */
  onDeleteNode: (id: string) => void;
  /** 设为入口节点 */
  onSetInput: (id: string) => void;
  /**
   * 点击"下钻编辑"——仅规则编辑器传入（打开 FlowNodeInspectorDrawer 深入编辑被引规则）；
   * 模板编辑器不需要，留空即可。
   */
  onDrillNode?: (id: string) => void;
}

/**
 * Flow 节点/边属性编辑器（纯 prop 驱动，无 store 依赖）。
 *
 * 规则编辑器：把 store 值接进来（flowGraph/selectedFlowNodeId 等）。
 * 模板编辑器：把 bodySkeleton 的 flowGraph + local selectedNodeId 接进来。
 * 两处用同一个组件，无重复实现。
 */
export default function FlowNodeInspector({
  graph, selectedNodeId, selectedEdgeIndex,
  decisions, sceneRules, expressionLangs,
  onChangeNode, onChangeEdge, onDeleteNode, onSetInput, onDrillNode,
}: Props) {
  const { t } = useTranslation('rule');
  const tc = useTranslation('common').t;

  const langOptions = expressionLangs.map((l) => ({ value: l, label: l }));
  const selectedNode = selectedNodeId ? graph.nodes.find((n) => n.id === selectedNodeId) ?? null : null;
  const selectedEdge = selectedEdgeIndex !== null ? graph.edges[selectedEdgeIndex] ?? null : null;
  const selectedEdgeSrc = selectedEdge ? graph.nodes.find((n) => n.id === selectedEdge.from) ?? null : null;

  // ---- 边属性 ----
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
          onChange={(v) => onChangeEdge(selectedEdgeIndex!, v === '__none__' ? null : v)}
        />
        <div style={{ fontSize: 10, color: '#8c959f', marginTop: 4 }}>{selectedEdge.from} → {selectedEdge.to}</div>
      </div>
    );
  }

  // ---- 无选中节点 ----
  if (!selectedNode) return null;

  const isInput = selectedNode.id === graph.inputNodeId;

  return (
    <>
      <div style={{ marginBottom: 8 }}>
        {isInput
          ? <Tag color="blue">{t('editor.flow.entry')}</Tag>
          : <Button size="small" type="link" onClick={() => onSetInput(selectedNode.id)} style={{ padding: 0 }}>{t('editor.flow.entry')} ←</Button>
        }
      </div>

      {/* SwitchNode */}
      {selectedNode.type === 'SwitchNode' && (() => {
        const s = selectedNode as SwitchNode;
        return (<>
          <div style={{ marginBottom: 10 }}>
            <div style={{ fontSize: 10.5, color: '#5b6672', marginBottom: 3, fontWeight: 600 }}>{t('editor.flow.inspector.lang')}</div>
            <Select size="small" style={{ width: '100%' }} value={s.lang} options={langOptions} onChange={(lang) => onChangeNode({ ...s, lang })} />
          </div>
          <div style={{ marginBottom: 10 }}>
            <div style={{ fontSize: 10.5, color: '#5b6672', marginBottom: 3, fontWeight: 600 }}>{t('editor.flow.inspector.expression')}</div>
            <Input.TextArea rows={3} value={s.expression} onChange={(e) => onChangeNode({ ...s, expression: e.target.value })} />
          </div>
          <div>
            <div style={{ fontSize: 10.5, color: '#5b6672', marginBottom: 3, fontWeight: 600 }}>{t('editor.flow.inspector.caseKeys')}</div>
            <Select mode="tags" size="small" style={{ width: '100%' }} value={s.caseKeys} onChange={(caseKeys) => onChangeNode({ ...s, caseKeys })} placeholder={t('editor.flow.node.addCase')} />
          </div>
        </>);
      })()}

      {/* TransformNode */}
      {selectedNode.type === 'TransformNode' && (() => {
        const tr = selectedNode as TransformNode;
        return (<>
          <div style={{ marginBottom: 10 }}>
            <div style={{ fontSize: 10.5, color: '#5b6672', marginBottom: 3, fontWeight: 600 }}>{t('editor.flow.inspector.lang')}</div>
            <Select size="small" style={{ width: '100%' }} value={tr.lang} options={langOptions} onChange={(lang) => onChangeNode({ ...tr, lang })} />
          </div>
          <div style={{ marginBottom: 10 }}>
            <div style={{ fontSize: 10.5, color: '#5b6672', marginBottom: 3, fontWeight: 600 }}>{t('editor.flow.inspector.outputKey')}</div>
            <Input size="small" value={tr.outputKey} onChange={(e) => onChangeNode({ ...tr, outputKey: e.target.value })} addonBefore="flow." />
          </div>
          <div>
            <div style={{ fontSize: 10.5, color: '#5b6672', marginBottom: 3, fontWeight: 600 }}>{t('editor.flow.inspector.expression')}</div>
            <Input.TextArea rows={3} value={tr.expression} onChange={(e) => onChangeNode({ ...tr, expression: e.target.value })} />
          </div>
        </>);
      })()}

      {/* OutputNode */}
      {selectedNode.type === 'OutputNode' && (() => {
        const o = selectedNode as OutputNode;
        return (
          <div>
            <div style={{ fontSize: 10.5, color: '#5b6672', marginBottom: 3, fontWeight: 600 }}>{t('editor.flow.inspector.decisionCode')}</div>
            {/* 用 Select 让用户从已有决策中选,比自由文本输入更安全 */}
            <Select
              size="small" style={{ width: '100%' }}
              value={o.decisionCode || undefined}
              showSearch optionFilterProp="label"
              placeholder={t('editor.flow.node.selectDecision')}
              options={decisions.map((d) => ({ value: d.code, label: `${d.name ?? d.code} (${d.code})` }))}
              onChange={(v) => onChangeNode({ ...o, decisionCode: v })}
            />
          </div>
        );
      })()}

      {/* RuleRefNode */}
      {selectedNode.type === 'RuleRefNode' && (() => {
        const ref = selectedNode as RuleRefNode;
        return (
          <div style={{ marginBottom: 10 }}>
            <div style={{ fontSize: 10.5, color: '#5b6672', marginBottom: 3, fontWeight: 600 }}>{t('editor.flow.inspector.ruleCode')}</div>
            <Select
              size="small" style={{ width: '100%', marginBottom: 8 }}
              value={ref.ruleCode || undefined}
              showSearch optionFilterProp="label"
              placeholder={t('editor.flow.node.selectRule')}
              options={Object.entries(
                sceneRules.reduce((acc, r) => {
                  const sc = r.sceneCode ?? '';
                  (acc[sc] = acc[sc] ?? []).push(r);
                  return acc;
                }, {} as Record<string, SceneRuleItem[]>),
              ).map(([sc, items]) => ({
                label: sc || '—',
                options: items.map((r) => ({ value: r.code, label: `${r.name} (${r.code})` })),
              }))}
              onChange={(v) => onChangeNode({ ...ref, ruleCode: v })}
            />
            {/* 下钻仅规则编辑器需要（传了 onDrillNode 才显示） */}
            {onDrillNode && (
              <Button size="small" type="link" disabled={!ref.ruleCode} onClick={() => onDrillNode(selectedNode.id)}>
                {t('editor.flow.drill.title')} ›
              </Button>
            )}
          </div>
        );
      })()}

      <div style={{ marginTop: 20, paddingTop: 12, borderTop: '1px solid #f0f0f0' }}>
        <Popconfirm title={tc('confirmDelete')} onConfirm={() => onDeleteNode(selectedNode.id)} okText={tc('confirm')} cancelText={tc('cancel')} okButtonProps={{ danger: true }}>
          <Button danger size="small" icon={<DeleteOutlined />} block>{tc('delete')}</Button>
        </Popconfirm>
      </div>
    </>
  );
}
