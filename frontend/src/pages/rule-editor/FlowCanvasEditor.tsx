import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  ReactFlow, ReactFlowProvider, Background, Controls, Handle, Position, MarkerType,
  useReactFlow, type Node, type Edge, type NodeProps, type NodeChange, type EdgeChange, type Connection,
} from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import { Select, Tag, Typography, Empty } from 'antd';
import { useTranslation } from 'react-i18next';
import { listRules } from '@/api/rule';
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
}

/** 画布节点 data 载荷（RF 要求 data extends Record）。 */
interface FlowNodeData extends Record<string, unknown> {
  flowNode: FlowNode;
  isInput: boolean;
  dead: boolean;
  sceneRules: SceneRuleItem[];
  onSelectRule: (id: string, ruleCode: string) => void;
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
  // 不可达节点堆到最后一层之下
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
      <Handle type="target" position={Position.Left} />
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
      <Handle type="source" position={Position.Right} />
    </NodeShell>
  );
}

function SwitchNodeView({ data, selected }: NodeProps<CanvasNode>) {
  const node = data.flowNode as SwitchNode;
  return (
    <NodeShell type="SwitchNode" isInput={data.isInput} dead={data.dead} selected={!!selected}>
      <Handle type="target" position={Position.Left} />
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
      <Handle type="source" position={Position.Right} />
    </NodeShell>
  );
}

function TransformNodeView({ data, selected }: NodeProps<CanvasNode>) {
  const node = data.flowNode as TransformNode;
  return (
    <NodeShell type="TransformNode" isInput={data.isInput} dead={data.dead} selected={!!selected}>
      <Handle type="target" position={Position.Left} />
      <div style={{ fontSize: 11.5 }}>
        <span style={{ fontSize: 10, color: '#0ea5e9', background: '#e8f6fd', borderRadius: 4, padding: '1px 5px', fontWeight: 600 }}>
          flow.{node.outputKey || '?'}
        </span>
      </div>
      <div style={{ fontFamily: 'ui-monospace,Menlo,monospace', fontSize: 11, color: '#5b6672', marginTop: 2, wordBreak: 'break-all' }}>
        = {node.expression || '—'}
      </div>
      <Handle type="source" position={Position.Right} />
    </NodeShell>
  );
}

function OutputNodeView({ data, selected }: NodeProps<CanvasNode>) {
  const node = data.flowNode as OutputNode;
  return (
    <NodeShell type="OutputNode" isInput={data.isInput} dead={data.dead} selected={!!selected}>
      <Handle type="target" position={Position.Left} />
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

function FlowCanvasInner({ value, onChange, sceneCode, ruleCode, tenantId, metadata, decisions, analysisReport, onLeafChanged }: Props) {
  const { t } = useTranslation('rule');
  const { screenToFlowPosition } = useReactFlow();
  const wrapperRef = useRef<HTMLDivElement>(null);
  const [positions, setPositions] = useState<Record<string, { x: number; y: number }>>({});
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [inspectId, setInspectId] = useState<string | null>(null);
  const [sceneRules, setSceneRules] = useState<SceneRuleItem[]>([]);

  const graph: FlowGraph = value ?? { nodes: [], edges: [], inputNodeId: '' };
  const defaultLang = metadata?.expressionLangs?.[0] ?? 'CEL';

  // 拉取同场景规则供 RuleRef 引用（排除自身）
  const reloadSceneRules = useCallback(async () => {
    if (!tenantId || !sceneCode) return;
    const data = await listRules(tenantId, sceneCode, { page: 1, size: 500 });
    setSceneRules((data.items ?? [])
      .filter((r) => r.code !== ruleCode)
      .map((r) => ({ code: r.code, name: r.name, ruleDefinitionId: r.ruleDefinitionId, kind: r.kind })));
  }, [tenantId, sceneCode, ruleCode]);

  useEffect(() => { reloadSceneRules(); }, [reloadSceneRules]);

  // 为缺失位置的节点补自动布局（新增节点 / 首次加载）
  useEffect(() => {
    const missing = graph.nodes.some((n) => !positions[n.id]);
    if (missing) {
      setPositions((prev) => {
        const laid = autoLayout(graph);
        const next = { ...prev };
        for (const n of graph.nodes) if (!next[n.id]) next[n.id] = laid[n.id] ?? XY.EMPTY;
        return next;
      });
    }
  }, [graph.nodes, positions]);

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

  // FlowGraph → RF 节点/边
  const rfNodes: CanvasNode[] = useMemo(() => graph.nodes.map((n) => ({
    id: n.id,
    type: n.type,
    position: positions[n.id] ?? XY.EMPTY,
    selected: n.id === selectedId,
    data: { flowNode: n, isInput: n.id === graph.inputNodeId, dead: deadNodes.has(n.id), sceneRules, onSelectRule },
  })), [graph, positions, selectedId, deadNodes, sceneRules, onSelectRule]);

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

  const onNodesChange = useCallback((changes: NodeChange<CanvasNode>[]) => {
    const removed: string[] = [];
    setPositions((prev) => {
      const next = { ...prev };
      for (const c of changes) if (c.type === 'position' && c.position) next[c.id] = c.position;
      return next;
    });
    for (const c of changes) {
      if (c.type === 'select') { if (c.selected) setSelectedId(c.id); else if (selectedId === c.id) setSelectedId(null); }
      if (c.type === 'remove') removed.push(c.id);
    }
    if (removed.length) {
      const remainSet = new Set(graph.nodes.map((n) => n.id).filter((id) => !removed.includes(id)));
      const nextNodes = graph.nodes.filter((n) => remainSet.has(n.id));
      const nextEdges = graph.edges.filter((e) => remainSet.has(e.from) && remainSet.has(e.to));
      const nextInput = remainSet.has(graph.inputNodeId) ? graph.inputNodeId : (nextNodes[0]?.id ?? '');
      onChange({ nodes: nextNodes, edges: nextEdges, inputNodeId: nextInput });
    }
  }, [graph, onChange, selectedId]);

  const onEdgesChange = useCallback((changes: EdgeChange<Edge>[]) => {
    const removedIds = new Set(changes.filter((c) => c.type === 'remove').map((c) => c.id));
    if (!removedIds.size) return;
    // rfEdges 与 graph.edges 一一对应同序，按下标 id 过滤，规避同 from/to/caseKey 复合键碰撞（重复连同一对节点时）
    onChange({ ...graph, edges: graph.edges.filter((_, i) => !removedIds.has(rfEdges[i].id)) });
  }, [graph, onChange, rfEdges]);

  const onConnect = useCallback((conn: Connection) => {
    if (!conn.source || !conn.target) return;
    const src = graph.nodes.find((n) => n.id === conn.source);
    // Switch 出边默认取第一个未占用的 caseKey；其余为无条件边（null）
    let caseKey: string | null = null;
    if (src?.type === 'SwitchNode') {
      const used = new Set(graph.edges.filter((e) => e.from === src.id).map((e) => e.caseKey));
      caseKey = src.caseKeys.find((k) => !used.has(k)) ?? null;
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
    setPositions((prev) => ({ ...prev, [id]: pos }));
    // 图为空时首个节点即入口
    onChange({ ...graph, nodes: [...graph.nodes, node], inputNodeId: graph.inputNodeId || id });
  }, [graph, onChange, screenToFlowPosition, defaultLang]);

  const onDragOver = useCallback((e: React.DragEvent) => { e.preventDefault(); e.dataTransfer.dropEffect = 'move'; }, []);

  const updateNode = useCallback((updated: FlowNode) => {
    onChange({ ...graph, nodes: graph.nodes.map((n) => (n.id === updated.id ? updated : n)) });
  }, [graph, onChange]);

  const setAsInput = useCallback((id: string) => { onChange({ ...graph, inputNodeId: id }); }, [graph, onChange]);

  const inspectNode = inspectId ? graph.nodes.find((n) => n.id === inspectId) ?? null : null;
  const hasIssues = cyclicEdges.size > 0 || deadNodes.size > 0;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: 'calc(100vh - 220px)', minHeight: 480 }}>
      {/* palette + 分析状态 */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '4px 8px 10px', flexWrap: 'wrap' }}>
        <Typography.Text type="secondary" style={{ fontSize: 11 }}>{t('editor.flow.palette.title')}：</Typography.Text>
        {PALETTE.map((type) => (
          <div
            key={type}
            draggable
            onDragStart={(e) => { e.dataTransfer.setData('application/rf-flownode', type); e.dataTransfer.effectAllowed = 'move'; }}
            style={{
              display: 'flex', alignItems: 'center', gap: 6, cursor: 'grab',
              border: '1px solid #e3e6ea', borderRadius: 7, padding: '4px 9px', background: '#fff', fontSize: 12.5, fontWeight: 600,
            }}
          >
            <span style={{ width: 8, height: 16, borderRadius: 3, background: ACCENT[type] }} />
            {type.replace('Node', '')}
          </div>
        ))}
        <span style={{ marginLeft: 'auto', display: 'flex', alignItems: 'center', gap: 6, fontSize: 12, fontWeight: 600, color: hasIssues ? '#e5484d' : '#16a34a' }}>
          <span style={{ width: 8, height: 8, borderRadius: '50%', background: hasIssues ? '#e5484d' : '#16a34a' }} />
          {hasIssues ? t('editor.flow.toolbar.analysisIssues') : t('editor.flow.toolbar.analysisPass')}
        </span>
      </div>

      <div ref={wrapperRef} style={{ flex: 1, border: '1px solid #e3e6ea', borderRadius: 8, overflow: 'hidden' }} onDrop={onDrop} onDragOver={onDragOver}>
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
            onNodeDoubleClick={(_, n) => setInspectId(n.id)}
            fitView
            proOptions={{ hideAttribution: true }}
          >
            <Background gap={18} />
            <Controls />
          </ReactFlow>
        )}
      </div>

      <FlowNodeInspectorDrawer
        open={inspectNode !== null}
        node={inspectNode}
        isInput={inspectNode?.id === graph.inputNodeId}
        onClose={() => setInspectId(null)}
        onChangeNode={updateNode}
        onSetInput={setAsInput}
        sceneRules={sceneRules}
        tenantId={tenantId}
        sceneCode={sceneCode}
        metadata={metadata}
        decisions={decisions}
        onLeafCreated={async (code) => { await reloadSceneRules(); if (inspectNode?.type === 'RuleRefNode') onSelectRule(inspectNode.id, code); onLeafChanged?.(); }}
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
