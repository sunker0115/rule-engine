import { create } from 'zustand';
import type { DryRunRequest, EvalResult } from '@/types';

interface DryRunState {
  request: DryRunRequest | null;
  result: EvalResult | null;
  loading: boolean;
  setRequest: (req: DryRunRequest) => void;
  setResult: (result: EvalResult) => void;
  setLoading: (loading: boolean) => void;
  reset: () => void;
}

export const useDryRunStore = create<DryRunState>((set) => ({
  request: null,
  result: null,
  loading: false,
  setRequest: (request) => set({ request }),
  setResult: (result) => set({ result, loading: false }),
  setLoading: (loading) => set({ loading }),
  reset: () => set({ request: null, result: null, loading: false }),
}));
