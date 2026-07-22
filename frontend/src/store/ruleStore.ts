import { create } from 'zustand';
import type { AstNode, DecisionBinding, PreGate, RuleKind, FlowGraph } from '@/types';

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
  loadFromDetail: (
    ast: AstNode | null,
    bindings: DecisionBinding[],
    gates: PreGate[],
    types: string[],
    kind: RuleKind,
    script: { source: string; lang: string } | null,
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
};

export const useRuleStore = create<RuleState>((set) => ({
  ...initialState,

  setAst: (ast) => set({ ast, dirty: true }),
  setDecisionBindings: (bindings) => set({ decisionBindings: bindings, dirty: true }),
  setPreGates: (gates) => set({ preGates: gates, dirty: true }),
  setTriggerEventTypes: (types) => set({ triggerEventTypes: types, dirty: true }),
  setKind: (kind) => set({ kind, dirty: true }),
  setDisplayLabel: (label) => set({ displayLabel: label, dirty: true }),
  setScript: (script) => set({ script, dirty: true }),
  setFlowGraph: (flowGraph) => set({ flowGraph, dirty: true }),

  loadFromDetail: (ast, bindings, gates, types, kind, script, flowGraph) =>
    set({ ast, decisionBindings: bindings, preGates: gates, triggerEventTypes: types, kind, script, flowGraph, dirty: false }),

  reset: () => set({ ...initialState }),
}));
