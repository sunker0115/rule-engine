import { create } from 'zustand';
import type { AstNode, DecisionBinding, PreGate, RuleKind } from '@/types';

interface RuleState {
  ast: AstNode | null;
  decisionBindings: DecisionBinding[];
  preGates: PreGate[];
  triggerEventTypes: string[];
  kind: RuleKind;
  displayLabel: string;
  dirty: boolean;

  setAst: (ast: AstNode) => void;
  setDecisionBindings: (bindings: DecisionBinding[]) => void;
  setPreGates: (gates: PreGate[]) => void;
  setTriggerEventTypes: (types: string[]) => void;
  setKind: (kind: RuleKind) => void;
  setDisplayLabel: (label: string) => void;
  loadFromDetail: (
    ast: AstNode | null,
    bindings: DecisionBinding[],
    gates: PreGate[],
    types: string[],
    kind: RuleKind,
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
};

export const useRuleStore = create<RuleState>((set) => ({
  ...initialState,

  setAst: (ast) => set({ ast, dirty: true }),
  setDecisionBindings: (bindings) => set({ decisionBindings: bindings, dirty: true }),
  setPreGates: (gates) => set({ preGates: gates, dirty: true }),
  setTriggerEventTypes: (types) => set({ triggerEventTypes: types, dirty: true }),
  setKind: (kind) => set({ kind, dirty: true }),
  setDisplayLabel: (label) => set({ displayLabel: label, dirty: true }),

  loadFromDetail: (ast, bindings, gates, types, kind) =>
    set({ ast, decisionBindings: bindings, preGates: gates, triggerEventTypes: types, kind, dirty: false }),

  reset: () => set({ ...initialState }),
}));
