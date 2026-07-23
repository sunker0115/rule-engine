import { create } from 'zustand';
import type { AstNode, DecisionBinding, PreGate, RuleKind, FlowGraph } from '@/types';

/** 编辑器快照——undo/redo 的最小还原面 */
interface EditSnapshot {
  ast: AstNode | null;
  script: { source: string; lang: string } | null;
  flowGraph: FlowGraph | null;
  decisionBindings: DecisionBinding[];
  preGates: PreGate[];
  triggerEventTypes: string[];
}

/** 场景规则精简项（供 RuleRef 下拉选择） */
export interface SceneRuleItem {
  code: string;
  name: string;
  ruleDefinitionId: number;
  kind: string;
  /** 规则所属场景编码，供跨 Scene 引用下拉分组展示 */
  sceneCode?: string;
}

const MAX_UNDO = 50;

function takeSnapshot(state: RuleState): EditSnapshot {
  return {
    ast: state.ast,
    script: state.script,
    flowGraph: state.flowGraph,
    decisionBindings: state.decisionBindings,
    preGates: state.preGates,
    triggerEventTypes: state.triggerEventTypes,
  };
}

function applySnapshot(set: (s: Partial<RuleState>) => void, snap: EditSnapshot) {
  set({
    ast: snap.ast, script: snap.script, flowGraph: snap.flowGraph,
    decisionBindings: snap.decisionBindings, preGates: snap.preGates,
    triggerEventTypes: snap.triggerEventTypes, dirty: true,
  });
}

interface RuleState {
  ast: AstNode | null;
  decisionBindings: DecisionBinding[];
  preGates: PreGate[];
  triggerEventTypes: string[];
  kind: RuleKind;
  displayLabel: string;
  dirty: boolean;
  script: { source: string; lang: string } | null;
  flowGraph: FlowGraph | null;

  setAst: (ast: AstNode) => void;
  setDecisionBindings: (bindings: DecisionBinding[]) => void;
  setPreGates: (gates: PreGate[]) => void;
  setTriggerEventTypes: (types: string[]) => void;
  setKind: (kind: RuleKind) => void;
  setDisplayLabel: (label: string) => void;
  setScript: (script: { source: string; lang: string } | null) => void;
  setFlowGraph: (flowGraph: FlowGraph | null) => void;
  selectedFlowNodeId: string | null;
  selectedFlowEdgeIndex: number | null;
  setSelectedFlowNodeId: (id: string | null) => void;
  setSelectedFlowEdgeIndex: (index: number | null) => void;
  drillFlowNodeId: string | null;
  setDrillFlowNodeId: (id: string | null) => void;
  flowSceneRules: SceneRuleItem[];
  setFlowSceneRules: (rules: SceneRuleItem[]) => void;
  addFlowRuleRef: (ruleCode: string) => void;

  /** undo/redo */
  undoStack: EditSnapshot[];
  redoStack: EditSnapshot[];
  undo: () => void;
  redo: () => void;
  canUndo: () => boolean;
  canRedo: () => boolean;

  loadFromDetail: (
    ast: AstNode | null, bindings: DecisionBinding[], gates: PreGate[],
    types: string[], kind: RuleKind, script: { source: string; lang: string } | null,
    flowGraph: FlowGraph | null,
  ) => void;
  reset: () => void;
}

const initialState = {
  ast: null as AstNode | null,
  decisionBindings: [] as DecisionBinding[],
  preGates: [] as PreGate[],
  triggerEventTypes: [] as string[],
  kind: 'AST_BOOLEAN' as RuleKind,
  displayLabel: '',
  dirty: false,
  script: null as { source: string; lang: string } | null,
  flowGraph: null as FlowGraph | null,
  selectedFlowNodeId: null as string | null,
  selectedFlowEdgeIndex: null as number | null,
  drillFlowNodeId: null as string | null,
  flowSceneRules: [] as SceneRuleItem[],
  undoStack: [] as EditSnapshot[],
  redoStack: [] as EditSnapshot[],
};

export const useRuleStore = create<RuleState>((set, get) => ({
  ...initialState,

  // ---- 编辑器写操作（均记录 undo）----
  setAst: (ast) => {
    const s = get();
    set({ undoStack: [...s.undoStack.slice(-MAX_UNDO + 1), takeSnapshot(s)], redoStack: [], ast, dirty: true });
  },
  setDecisionBindings: (decisionBindings) => {
    const s = get();
    set({ undoStack: [...s.undoStack.slice(-MAX_UNDO + 1), takeSnapshot(s)], redoStack: [], decisionBindings, dirty: true });
  },
  setPreGates: (preGates) => {
    const s = get();
    set({ undoStack: [...s.undoStack.slice(-MAX_UNDO + 1), takeSnapshot(s)], redoStack: [], preGates, dirty: true });
  },
  setTriggerEventTypes: (triggerEventTypes) => {
    const s = get();
    set({ undoStack: [...s.undoStack.slice(-MAX_UNDO + 1), takeSnapshot(s)], redoStack: [], triggerEventTypes, dirty: true });
  },
  setKind: (kind) => set({ kind, dirty: true }),
  setDisplayLabel: (displayLabel) => set({ displayLabel, dirty: true }),
  setScript: (script) => {
    const s = get();
    set({ undoStack: [...s.undoStack.slice(-MAX_UNDO + 1), takeSnapshot(s)], redoStack: [], script, dirty: true });
  },
  setFlowGraph: (flowGraph) => {
    const s = get();
    set({ undoStack: [...s.undoStack.slice(-MAX_UNDO + 1), takeSnapshot(s)], redoStack: [], flowGraph, dirty: true });
  },

  // ---- flow 交互状态（不记 undo）----
  setSelectedFlowNodeId: (id) => set({ selectedFlowNodeId: id }),
  setSelectedFlowEdgeIndex: (index) => set({ selectedFlowEdgeIndex: index }),
  setDrillFlowNodeId: (id) => set({ drillFlowNodeId: id }),
  setFlowSceneRules: (rules) => set({ flowSceneRules: rules }),

  addFlowRuleRef: (ruleCode: string) => {
    const s = get();
    const graph = s.flowGraph ?? { nodes: [], edges: [], inputNodeId: '' };
    const existing = new Set(graph.nodes.map((n) => n.id));
    let id = 'ref_1';
    for (let i = 1; existing.has(`ref_${i}`); i += 1) id = `ref_${i}`;
    set({
      undoStack: [...s.undoStack.slice(-MAX_UNDO + 1), takeSnapshot(s)], redoStack: [],
      flowGraph: { ...graph, nodes: [...graph.nodes, { type: 'RuleRefNode', id, ruleCode }], inputNodeId: graph.inputNodeId || id },
      selectedFlowNodeId: id, dirty: true,
    });
  },

  // ---- undo/redo ----
  undo: () => {
    const s = get();
    if (s.undoStack.length === 0) return;
    const prev = s.undoStack[s.undoStack.length - 1];
    set({
      undoStack: s.undoStack.slice(0, -1),
      redoStack: [...s.redoStack, takeSnapshot(s)],
    });
    applySnapshot(set, prev);
  },
  redo: () => {
    const s = get();
    if (s.redoStack.length === 0) return;
    const next = s.redoStack[s.redoStack.length - 1];
    set({
      redoStack: s.redoStack.slice(0, -1),
      undoStack: [...s.undoStack, takeSnapshot(s)],
    });
    applySnapshot(set, next);
  },
  canUndo: () => get().undoStack.length > 0,
  canRedo: () => get().redoStack.length > 0,

  loadFromDetail: (ast, bindings, gates, types, kind, script, flowGraph) =>
    set({ ast, decisionBindings: bindings, preGates: gates, triggerEventTypes: types, kind, script, flowGraph,
      dirty: false, undoStack: [], redoStack: [] }),

  reset: () => set({ ...initialState, undoStack: [], redoStack: [] }),
}));
