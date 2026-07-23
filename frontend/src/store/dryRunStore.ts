import { create } from 'zustand';
import type { DryRunRequest, EvalResult } from '@/types';

interface DryRunState {
  request: DryRunRequest | null;
  result: EvalResult | null;
  loading: boolean;
  /** 画布执行路径高亮开关 */
  showTrace: boolean;
  /** What-if 自动重算开关 */
  autoRerun: boolean;

  setRequest: (req: DryRunRequest) => void;
  setResult: (result: EvalResult) => void;
  setLoading: (loading: boolean) => void;
  setAutoRerun: (on: boolean) => void;
  /** 打开画布高亮 + 写入结果 */
  showTraceResult: (traces: EvalResult['nodeTrace'], decisions: string[]) => void;
  /** 关闭画布高亮 */
  clearTrace: () => void;
  reset: () => void;
}

/** 合并试算结果 + 画布高亮 + What-if 联动 —— 一个 store 管理全部试算相关状态 */
export const useDryRunStore = create<DryRunState>((set) => ({
  request: null,
  result: null,
  loading: false,
  showTrace: false,
  autoRerun: false,

  setRequest: (request) => set({ request }),
  setResult: (result) => set({ result, loading: false, showTrace: true }),
  setLoading: (loading) => set({ loading }),
  setAutoRerun: (autoRerun) => set({ autoRerun }),
  showTraceResult: (_traces, _decisions) => {
    // showTrace 在 setResult 时已置 true，此处为兼容旧调用保留
    set({ showTrace: true });
  },
  clearTrace: () => set({ showTrace: false }),
  reset: () => set({ request: null, result: null, loading: false }),
  /** 只清结果和 loading，保留 showTrace */
  resetResult: () => set({ result: null, loading: false }),
}));
