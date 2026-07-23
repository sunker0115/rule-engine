import { create } from 'zustand';
import type { NodeTraceItem } from '@/types';

interface FlowTraceState {
  /** 最近一次试算/评估的 trace 树 */
  traces: NodeTraceItem[] | null;
  /** 最终命中的决策码 */
  hitDecisions: string[];
  /** 是否在结果展示模式 */
  showTrace: boolean;

  setTraceResult: (traces: NodeTraceItem[] | null, hitDecisions: string[]) => void;
  clearTrace: () => void;
}

export const useFlowTraceStore = create<FlowTraceState>((set) => ({
  traces: null,
  hitDecisions: [],
  showTrace: false,

  setTraceResult: (traces, hitDecisions) => set({ traces, hitDecisions, showTrace: true }),
  clearTrace: () => set({ traces: null, hitDecisions: [], showTrace: false }),
}));
