import { create } from 'zustand';

/** 血缘抽屉的打开请求：由子组件（ConditionCard / DecisionBindingEditor）发起，编排层据 kind 决定标题与 fetcher。 */
export interface LineageOpenRequest {
  code: string;
  kind: 'metric' | 'decision';
}

interface LineageState {
  /** metric code → 被引用计数 */
  metricUsage: Record<string, number>;
  /** decision code → 被引用计数 */
  decisionUsage: Record<string, number>;
  /** 待打开的血缘抽屉请求；null 表示无 */
  openRequest: LineageOpenRequest | null;

  /** 编辑器加载时一次性写入两类计数 map */
  setUsage: (metricUsage: Record<string, number>, decisionUsage: Record<string, number>) => void;
  /** 子组件点击徽标时发起打开请求 */
  requestOpen: (req: LineageOpenRequest) => void;
  /** 编排层关闭抽屉时清空请求 */
  clearOpen: () => void;
}

/**
 * 规则编辑器内反向血缘的跨组件状态：计数 map 由编排层 index.tsx 一次性加载，
 * 深层叶子组件（ConditionCard / DecisionBindingEditor）直接读取并发起打开请求，
 * 避免穿透多层 props（沿用编辑器既有的 zustand store 取数惯例）。
 */
export const useLineageStore = create<LineageState>((set) => ({
  metricUsage: {},
  decisionUsage: {},
  openRequest: null,

  setUsage: (metricUsage, decisionUsage) => set({ metricUsage, decisionUsage }),
  requestOpen: (req) => set({ openRequest: req }),
  clearOpen: () => set({ openRequest: null }),
}));
