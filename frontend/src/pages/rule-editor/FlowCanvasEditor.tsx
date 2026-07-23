import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  ReactFlow, ReactFlowProvider, Background, Controls, Handle, Position, MarkerType,
  useReactFlow, type Node, type Edge, type NodeProps, type NodeChange, type EdgeChange, type Connection,
} from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import { Select, Tag, Empty } from 'antd';
import { useTranslation } from 'react-i18next';
import { useRuleStore } from '@/store/ruleStore';
import { flowCyclesForRule, flowDeadNodesForRule } from './analysisSummary';
import FlowNodeInspectorDrawer, { type SceneRuleItem } from './FlowNodeInspectorDrawer';
import type {
  FlowGraph, FlowNode, FlowNodeType, RuleRefNode, SwitchNode, TransformNode, OutputNode,
  SceneMetadata, DecisionItem, RuleSetAnalysisReport,
} from '@/types';

interface Props {
  value: FlowGraph | null;
  onChange: (graph: FlowGraph) => void;
  sceneCode: string;
  ruleCode: string;
  tenantId: number;
  metadata: SceneMetadata | null;
  decisions: DecisionItem[];
  analysisReport?: RuleSetAnalysisReport | null;
  onLeafChanged?: () => void;
  /** 画布上选中节点的 id 变化时回调（null = 取消选中） */
  onSelectedNodeChange?: (nodeId: string | null) => void;
  /** 画布上选中边（在 graph.edges 中的 index）变化时回调（null = 取消选中） */
  onSelectedEdgeChange?: (edgeIndex: number | null) => void;
  /** 双击节点/边时打开右栏 */
  onOpenRightPanel?: () => void;
  /** 点空白处关闭右栏 */
  onCloseRightPanel?: () => void;
}

/** 画布节点 data 载荷（RF 要求 data extends Record）。 */
interface FlowNodeData extends Record<string, unknown> {
  flowNode: FlowNode;
  isInput: boolean;
  dead: boolean;
  sceneRules: SceneRuleItem[];
  onSelectRule: (id: string, ruleCode: string) => void;
}

/** ReactFlow 内部状态（不持久化到 FlowGraph，仅 UI 层需要）。 */
interface NodeInternal {
  /** 用户拖拽后的位置；未拖拽时由 autoLayout 初始播种 */
  position: { x: number; y: number };
  /** ReactFlow 测量上报的节点像素尺寸；受控模式下必须写回 nodes prop，否则 hit-test 失效 */
  measured?: { width?: number; height?: number };
}

type CanvasNode = Node<FlowNodeData>;

/** 节点类型 → 配色（与 mockup / trace-tree 对齐）。 */
const ACCENT: Record<FlowNodeType, string> = {
  RuleRefNode: '#2f6bff',
  SwitchNode: '#8b5cf6',
  TransformNode: '#0ea5e9',
  OutputNode: '#16a34a',
};

const XY = { EMPTY: { x: 0, y: 0 } };

/** 生成图内唯一节点 id。 */
function nextNodeId(type: FlowNodeType, existing: Set<string>): string {
  const prefix = { RuleRefNode: 'ref', SwitchNode: 'switch', TransformNode: 'transform', OutputNode: 'output' }[type];
  let i = 1;
  while (existing.has(`${prefix}_${i}`)) i += 1;
  return `${prefix}_${i}`;
}

/** 按类型创建一个空 FlowNode。 */
function makeNode(type: FlowNodeType, id: string, defaultLang: string): FlowNode {
  switch (type) {
    case 'RuleRefNode': return { type, id, ruleCode: '' };
    case 'SwitchNode': return { type, id, lang: defaultLang, expression: '', caseKeys: [] };
    case 'TransformNode': return { type, id, lang: defaultLang, expression: '', outputKey: '' };
    case 'OutputNode': return { type, id, decisionCode: '' };
  }
}

/** 从入口按边分层的简单自动布局（BFS 层级 → x，层内序号 → y）。 */
function autoLayout(graph: FlowGraph): Record<string, { x: number; y: number }> {
  const adj = new Map<string, string[]>();
  graph.nodes.forEach((n) => adj.set(n.id, []));
  graph.edges.forEach((e) => { if (adj.has(e.from)) adj.get(e.from)!.push(e.to); });

  const layer = new Map<string, number>();
  const queue: string[] = [];
  if (graph.inputNodeId && adj.has(graph.inputNodeId)) {
    layer.set(graph.inputNodeId, 0);
    queue.push(graph.inputNodeId);
  }
  while (queue.length) {
    const cur = queue.shift()!;
    const d = layer.get(cur)!;
    for (const to of adj.get(cur) ?? []) {
      if (!layer.has(to)) { layer.set(to, d + 1); queue.push(to); }
    }
  }
  let maxLayer = 0;
  layer.forEach((v) => { maxLayer = Math.max(maxLayer, v); });
  graph.nodes.forEach((n) => { if (!layer.has(n.id)) layer.set(n.id, maxLayer + 1); });

  const perLayerCount = new Map<number, number>();
  const pos: Record<string, { x: number; y: number }> = {};
  for (const n of graph.nodes) {
    const l = layer.get(n.id)!;
    const idx = perLayerCount.get(l) ?? 0;
    perLayerCount.set(l, idx + 1);
    pos[n.id] = { x: 60 + l * 240, y: 40 + idx * 130 };
  }
  return pos;
}

/** 边的方向键，用于成环标红比对。 */
function edgeKey(from: string, to: string): string {
  return `${from}->${to}`;
}

// ---- 自定义节点视图 ----

/** 连线手柄统一样式：放大 + hover 高亮，用户一眼能看到"可以拖线"。 */
const handleStyle: React.CSSProperties = {
  width: 12, height: 12,
  background: '#fff',
  border: '2px solid #94a3b8',
  borderRadius: '50%',
  transition: 'border-color 0.15s, transform 0.15s',
};
const handleHover = (color: string) => ({
  borderColor: color,
  transform: 'scale(1.4)',
});

function NodeShell({ type, isInput, dead, selected, children }: {
  type: FlowNodeType; isInput: boolean; dead: boolean; selected: boolean; children: React.ReactNode;
}) {
  const accent = ACCENT[type];
  return (
    <div style={{
      width: 180, background: '#fff', borderRadius: 10, overflow: 'hidden',
      border: `1px solid ${selected ? accent : '#e3e6ea'}`,
      boxShadow: selected ? `0 0 0 3px ${accent}26` : '0 1px 4px rgba(16,24,40,.08)',
      opacity: dead ? 0.45 : 1, borderStyle: dead ? 'dashed' : 'solid',
    }}>
      <div style={{ height: 4, background: dead ? '#8a95a1' : accent }} />
      <div style={{ padding: '6px 10px 3px', display: 'flex', alignItems: 'center', gap: 6 }}>
        <span style={{ fontSize: 10.5, fontWeight: 700, letterSpacing: 0.5, textTransform: 'uppercase', color: accent }}>{type.replace('Node', '')}</span>
        {isInput && <Tag color="blue" style={{ marginInlineEnd: 0, fontSize: 10, lineHeight: '16px', padding: '0 5px' }}>入口</Tag>}
      </div>
      <div style={{ padding: '2px 10px 10px' }}>{children}</div>
    </div>
  );
}

function RuleRefNodeView({ data, selected }: NodeProps<CanvasNode>) {
  const { t } = useTranslation('rule');
  const node = data.flowNode as RuleRefNode;
  return (
    <NodeShell type="RuleRefNode" isInput={data.isInput} dead={data.dead} selected={!!selected}>
      <Handle type="target" position={Position.Left} style={handleStyle} onMouseEnter={(e) => Object.assign((e.target as HTMLElement).style, handleHover(ACCENT.RuleRefNode))} onMouseLeave={(e) => Object.assign((e.target as HTMLElement).style, handleStyle)} />
      <div className="nodrag">
        <Select
          size="small"
          style={{ width: '100%' }}
          value={node.ruleCode || undefined}
          placeholder={t('editor.flow.node.selectRule')}
          options={data.sceneRules.map((r) => ({ value: r.code, label: `${r.name} (${r.code})` }))}
          onChange={(v) => data.onSelectRule(node.id, v)}
          showSearch
          optionFilterProp="label"
        />
      </div>
      <Handle type="source" position={Position.Right} style={handleStyle} onMouseEnter={(e) => Object.assign((e.target as HTMLElement).style, handleHover(ACCENT.RuleRefNode))} onMouseLeave={(e) => Object.assign((e.target as HTMLElement).style, handleStyle)} />
    </NodeShell>
  );
}

function SwitchNodeView({ data, selected }: NodeProps<CanvasNode>) {
  const node = data.flowNode as SwitchNode;
  return (
    <NodeShell type="SwitchNode" isInput={data.isInput} dead={data.dead} selected={!!selected}>
      <Handle type="target" position={Position.Left} style={handleStyle} onMouseEnter={(e) => Object.assign((e.target as HTMLElement).style, handleHover(ACCENT.SwitchNode))} onMouseLeave={(e) => Object.assign((e.target as HTMLElement).style, handleStyle)} />
      <div style={{ fontFamily: 'ui-monospace,Menlo,monospace', fontSize: 11, color: '#5b6672', wordBreak: 'break-all' }}>
        {node.expression || '—'}
      </div>
      {node.caseKeys.length > 0 && (
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 4, marginTop: 6 }}>
          {node.caseKeys.map((c) => (
            <span key={c} style={{ fontSize: 10.5, padding: '1px 7px', borderRadius: 10, background: '#f3effe', color: '#8b5cf6', fontWeight: 600 }}>{c}</span>
          ))}
        </div>
      )}
      <Handle type="source" position={Position.Right} style={handleStyle} onMouseEnter={(e) => Object.assign((e.target as HTMLElement).style, handleHover(ACCENT.SwitchNode))} onMouseLeave={(e) => Object.assign((e.target as HTMLElement).style, handleStyle)} />
    </NodeShell>
  );
}

function TransformNodeView({ data, selected }: NodeProps<CanvasNode>) {
  const node = data.flowNode as TransformNode;
  return (
    <NodeShell type="TransformNode" isInput={data.isInput} dead={data.dead} selected={!!selected}>
      <Handle type="target" position={Position.Left} style={handleStyle} onMouseEnter={(e) => Object.assign((e.target as HTMLElement).style, handleHover(ACCENT.TransformNode))} onMouseLeave={(e) => Object.assign((e.target as HTMLElement).style, handleStyle)} />
      <div style={{ fontSize: 11.5 }}>
        <span style={{ fontSize: 10, color: '#0ea5e9', background: '#e8f6fd', borderRadius: 4, padding: '1px 5px', fontWeight: 600 }}>
          flow.{node.outputKey || '?'}
        </span>
      </div>
      <div style={{ fontFamily: 'ui-monospace,Menlo,monospace', fontSize: 11, color: '#5b6672', marginTop: 2, wordBreak: 'break-all' }}>
        = {node.expression || '—'}
      </div>
      <Handle type="source" position={Position.Right} style={handleStyle} onMouseEnter={(e) => Object.assign((e.target as HTMLElement).style, handleHover(ACCENT.TransformNode))} onMouseLeave={(e) => Object.assign((e.target as HTMLElement).style, handleStyle)} />
    </NodeShell>
  );
}

function OutputNodeView({ data, selected }: NodeProps<CanvasNode>) {
  const node = data.flowNode as OutputNode;
  return (
    <NodeShell type="OutputNode" isInput={data.isInput} dead={data.dead} selected={!!selected}>
      <Handle type="target" position={Position.Left} style={handleStyle} onMouseEnter={(e) => Object.assign((e.target as HTMLElement).style, handleHover(ACCENT.OutputNode))} onMouseLeave={(e) => Object.assign((e.target as HTMLElement).style, handleStyle)} />
      <div style={{ fontWeight: 600, fontSize: 13 }}>{node.decisionCode || '—'}</div>
    </NodeShell>
  );
}

const nodeTypes = {
  RuleRefNode: RuleRefNodeView,
  SwitchNode: SwitchNodeView,
  TransformNode: TransformNodeView,
  OutputNode: OutputNodeView,
};

const PALETTE: FlowNodeType[] = ['RuleRefNode', 'SwitchNode', 'TransformNode', 'OutputNode'];

function FlowCanvasInner({ value, onChange, sceneCode, ruleCode, tenantId, metadata, decisions, analysisReport, onLeafChanged, onSelectedNodeChange, onSelectedEdgeChange, onOpenRightPanel, onCloseRightPanel }: Props) {
  const { drillFlowNodeId, setDrillFlowNodeId, flowSceneRules: sceneRules } = useRuleStore();
  const { t } = useTranslation('rule');
  const { screenToFlowPosition, fitView } = useReactFlow();
  const wrapperRef = useRef<HTMLDivElement>(null);

  // ---- ReactFlow 受控模式内部状态 ----
  const [nodeInternals, setNodeInternals] = useState<Record<string, NodeInternal>>({});
  const [selectedId, setSelectedId] = useState<string | null>(null);
  // RuleRef 下钻编辑抽屉（由 store 驱动，RightPanel 按钮触发）

  const graph: FlowGraph = value ?? { nodes: [], edges: [], inputNodeId: '' };
  const defaultLang = metadata?.expressionLangs?.[0] ?? 'CEL';

  // 为新节点补自动布局位置（新增 / 首次加载）；已有位置的节点不覆盖（保留用户拖拽结果）
  useEffect(() => {
    const missing = graph.nodes.some((n) => !nodeInternals[n.id]);
    if (missing) {
      const laid = autoLayout(graph);
      setNodeInternals((prev) => {
        const next = { ...prev };
        for (const n of graph.nodes) {
          if (!next[n.id]) next[n.id] = { position: laid[n.id] ?? XY.EMPTY };
        }
        return next;
      });
    }
  }, [graph.nodes]);

  // fitView 在 flex 容器尺寸稳定后重新适配（首屏偏小再撑大时避免节点显示不全）
  useEffect(() => {
    if (graph.nodes.length > 0) {
      const t = setTimeout(() => fitView?.({ padding: 0.2, duration: 200 }), 300);
      return () => clearTimeout(t);
    }
  }, [graph.nodes.length, fitView]);

  // 图内 finding → 成环边集 / 死节点集
  const cyclicEdges = useMemo(() => {
    const set = new Set<string>();
    if (analysisReport) {
      for (const f of flowCyclesForRule(analysisReport, ruleCode)) {
        const ids = f.cycleNodeIds;
        for (let i = 0; i < ids.length; i += 1) set.add(edgeKey(ids[i], ids[(i + 1) % ids.length]));
      }
    }
    return set;
  }, [analysisReport, ruleCode]);

  const deadNodes = useMemo(() => {
    const set = new Set<string>();
    if (analysisReport) for (const f of flowDeadNodesForRule(analysisReport, ruleCode)) set.add(f.deadNodeId);
    return set;
  }, [analysisReport, ruleCode]);

  const onSelectRule = useCallback((id: string, code: string) => {
    onChange({ ...graph, nodes: graph.nodes.map((n) => (n.id === id && n.type === 'RuleRefNode' ? { ...n, ruleCode: code } : n)) });
  }, [graph, onChange]);

  // FlowGraph → RF 节点（合并 position + measured 到受控 nodes，满足受控模式契约）
  const rfNodes: CanvasNode[] = useMemo(() => graph.nodes.map((n) => {
    const internal = nodeInternals[n.id];
    return {
      id: n.id,
      type: n.type,
      position: internal?.position ?? XY.EMPTY,
      measured: internal?.measured,
      selected: n.id === selectedId,
      data: { flowNode: n, isInput: n.id === graph.inputNodeId, dead: deadNodes.has(n.id), sceneRules, onSelectRule },
    };
  }), [graph, nodeInternals, selectedId, deadNodes, sceneRules, onSelectRule]);

  const rfEdges: Edge[] = useMemo(() => graph.edges.map((e, i) => {
    const cyclic = cyclicEdges.has(edgeKey(e.from, e.to));
    return {
      id: `e_${i}_${e.from}_${e.to}_${e.caseKey ?? ''}`,
      source: e.from,
      target: e.to,
      label: e.caseKey ?? undefined,
      animated: cyclic,
      style: cyclic ? { stroke: '#e5484d', strokeWidth: 2 } : { stroke: '#94a3b8', strokeWidth: 1.6 },
      markerEnd: { type: MarkerType.ArrowClosed, color: cyclic ? '#e5484d' : '#94a3b8' },
    };
  }), [graph.edges, cyclicEdges]);

  // 受控模式核心：完整处理 RF 上报的所有 change type，把 position / dimensions 写回 nodeInternals
  const onNodesChange = useCallback((changes: NodeChange<CanvasNode>[]) => {
    const removed: string[] = [];

    setNodeInternals((prev) => {
      const next = { ...prev };
      for (const c of changes) {
        switch (c.type) {
          case 'position':
            if (c.position) {
              const prevInternal = next[c.id];
              next[c.id] = { ...prevInternal, position: c.position, measured: prevInternal?.measured };
            }
            break;
          case 'dimensions':
            if (c.dimensions) {
              const prevInternal = next[c.id];
              next[c.id] = { ...prevInternal, position: prevInternal?.position ?? XY.EMPTY, measured: c.dimensions };
            }
            break;
          case 'remove':
            removed.push(c.id);
            break;
          // select / reset 等不改变 nodeInternals，由 selectedId 单独管理
        }
      }
      // 清理被删除节点
      if (removed.length) for (const id of removed) delete next[id];
      return next;
    });

    // selection 独立于 nodeInternals 管理（RF 通过 select change 告知选中变化）
    for (const c of changes) {
      if (c.type === 'select') {
        if (c.selected) setSelectedId(c.id);
        else setSelectedId((prev) => prev === c.id ? null : prev);
      }
    }

    // remove → 同步回 FlowGraph
    if (removed.length) {
      const remainSet = new Set(graph.nodes.map((n) => n.id).filter((id) => !removed.includes(id)));
      const nextNodes = graph.nodes.filter((n) => remainSet.has(n.id));
      const nextEdges = graph.edges.filter((e) => remainSet.has(e.from) && remainSet.has(e.to));
      const nextInput = remainSet.has(graph.inputNodeId) ? graph.inputNodeId : (nextNodes[0]?.id ?? '');
      onChange({ nodes: nextNodes, edges: nextEdges, inputNodeId: nextInput });
    }
  }, [graph, onChange]);

  const onEdgesChange = useCallback((changes: EdgeChange<Edge>[]) => {
    const removedIds = new Set(changes.filter((c) => c.type === 'remove').map((c) => c.id));
    if (!removedIds.size) return;
    onChange({ ...graph, edges: graph.edges.filter((_, i) => !removedIds.has(rfEdges[i].id)) });
  }, [graph, onChange, rfEdges]);

  const onConnect = useCallback((conn: Connection) => {
    if (!conn.source || !conn.target) return;
    const src = graph.nodes.find((n) => n.id === conn.source);
    let caseKey: string | null = null;
    if (src?.type === 'SwitchNode') {
      const used = new Set(graph.edges.filter((e) => e.from === src.id).map((e) => e.caseKey));
      caseKey = src.caseKeys.find((k) => !used.has(k)) ?? null;
    } else if (src?.type === 'RuleRefNode') {
      // 首条边默认 "true"，第二条默认 "false"，后续走默认（null）
      const used = new Set(graph.edges.filter((e) => e.from === src.id).map((e) => e.caseKey));
      if (!used.has('true')) caseKey = 'true';
      else if (!used.has('false')) caseKey = 'false';
    }
    onChange({ ...graph, edges: [...graph.edges, { from: conn.source, to: conn.target, caseKey }] });
  }, [graph, onChange]);

  const onDrop = useCallback((e: React.DragEvent) => {
    e.preventDefault();
    const type = e.dataTransfer.getData('application/rf-flownode') as FlowNodeType;
    if (!type || !PALETTE.includes(type)) return;
    const pos = screenToFlowPosition({ x: e.clientX, y: e.clientY });
    const id = nextNodeId(type, new Set(graph.nodes.map((n) => n.id)));
    const node = makeNode(type, id, defaultLang);
    // 拖放位置写入 nodeInternals
    setNodeInternals((prev) => ({ ...prev, [id]: { position: pos } }));
    onChange({ ...graph, nodes: [...graph.nodes, node], inputNodeId: graph.inputNodeId || id });
  }, [graph, onChange, screenToFlowPosition, defaultLang]);

  const onDragOver = useCallback((e: React.DragEvent) => { e.preventDefault(); e.dataTransfer.dropEffect = 'move'; }, []);

  const hasIssues = cyclicEdges.size > 0 || deadNodes.size > 0;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', flex: 1, minHeight: 0 }}>
      {/* palette + 分析状态 */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '4px 10px', background: '#fff', borderBottom: '1px solid #f0f0f0', flexWrap: 'wrap' }}>
        <span style={{ fontSize: 10.5, fontWeight: 600, color: '#8a95a1', letterSpacing: 0.4, textTransform: 'uppercase' }}>{t('editor.flow.palette.title')}</span>
        {PALETTE.map((type) => (
          <div key={type} draggable
            onDragStart={(e) => { e.dataTransfer.setData('application/rf-flownode', type); e.dataTransfer.effectAllowed = 'move'; }}
            style={{ display: 'flex', alignItems: 'center', gap: 5, cursor: 'grab', border: '1px solid #e3e6ea', borderRadius: 5, padding: '2px 8px', background: '#fff', fontSize: 11.5, fontWeight: 600, transition: 'border-color 0.12s' }}
            onMouseEnter={(e) => { e.currentTarget.style.borderColor = '#bcc4d0'; }}
            onMouseLeave={(e) => { e.currentTarget.style.borderColor = '#e3e6ea'; }}>
            <span style={{ width: 6, height: 14, borderRadius: 2, background: ACCENT[type] }} />
            {type.replace('Node', '')}
          </div>
        ))}
        <span style={{ marginLeft: 'auto', display: 'flex', alignItems: 'center', gap: 6, fontSize: 11.5, fontWeight: 600, color: hasIssues ? '#e5484d' : '#16a34a' }}>
          <span style={{ width: 7, height: 7, borderRadius: '50%', background: hasIssues ? '#e5484d' : '#16a34a' }} />
          {hasIssues ? t('editor.flow.toolbar.analysisIssues') : t('editor.flow.toolbar.analysisPass')}
        </span>
      </div>

      <div ref={wrapperRef} style={{ flex: 1, overflow: 'hidden' }} onDrop={onDrop} onDragOver={onDragOver}>
        {graph.nodes.length === 0 ? (
          <Empty description={t('editor.flow.emptyGraph')} style={{ marginTop: 120 }} />
        ) : (
          <ReactFlow
            nodes={rfNodes}
            edges={rfEdges}
            nodeTypes={nodeTypes}
            onNodesChange={onNodesChange}
            onEdgesChange={onEdgesChange}
            onConnect={onConnect}
            onNodeClick={(_, n) => { onSelectedNodeChange?.(n.id); onOpenRightPanel?.(); }}
            onEdgeClick={(_, edge) => {
              const idx = graph.edges.findIndex((_e, i) => rfEdges[i]?.id === edge.id);
              if (idx >= 0) { onSelectedEdgeChange?.(idx); onOpenRightPanel?.(); }
            }}
            onPaneClick={() => { onSelectedNodeChange?.(null); onSelectedEdgeChange?.(null); onCloseRightPanel?.(); }}
            fitView
            fitViewOptions={{ padding: 0.2 }}
            proOptions={{ hideAttribution: true }}
          >
            <Background gap={18} />
            <Controls />
          </ReactFlow>
        )}
      </div>

      <FlowNodeInspectorDrawer
        open={drillFlowNodeId !== null}
        node={drillFlowNodeId ? graph.nodes.find((n) => n.id === drillFlowNodeId) ?? null : null}
        isInput={drillFlowNodeId === graph.inputNodeId}
        onClose={() => setDrillFlowNodeId(null)}
        onChangeNode={(updated: FlowNode) => onChange({ ...graph, nodes: graph.nodes.map((n) => (n.id === updated.id ? updated : n)) })}
        onSetInput={(id: string) => onChange({ ...graph, inputNodeId: id })}
        sceneRules={sceneRules}
        tenantId={tenantId}
        sceneCode={sceneCode}
        metadata={metadata}
        decisions={decisions}
        onLeafCreated={async (_code: string) => { onLeafChanged?.(); }}
        onLeafSaved={onLeafChanged}
      />
    </div>
  );
}

/**
 * DECISION_FLOW 决策图画布编辑器：4 种节点拖放 + 连边 + Switch 出边标 caseKey +
 * RuleRef 内联选本场景规则；双击节点下钻编辑；消费图内 finding 把成环边标红、死节点置灰。
 */
export default function FlowCanvasEditor(props: Props) {
  return (
    <ReactFlowProvider>
      <FlowCanvasInner {...props} />
    </ReactFlowProvider>
  );
}
