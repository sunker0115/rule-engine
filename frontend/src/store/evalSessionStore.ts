import { create } from 'zustand';

interface SessionFilters {
  tenantId: number | null;
  sceneCode: string;
  subjectId: string;
  eventId: string;
  status: string[];
  source: string[];
  from: string;
  to: string;
  page: number;
  size: number;
}

interface EvalSessionState {
  filters: SessionFilters;
  setFilter: <K extends keyof SessionFilters>(key: K, value: SessionFilters[K]) => void;
  resetFilters: () => void;
}

const defaultFilters: SessionFilters = {
  tenantId: null,
  sceneCode: '',
  subjectId: '',
  eventId: '',
  status: [],
  source: [],
  from: '',
  to: '',
  page: 1,
  size: 20,
};

export const useEvalSessionStore = create<EvalSessionState>((set) => ({
  filters: { ...defaultFilters },
  setFilter: (key, value) => set((s) => ({ filters: { ...s.filters, [key]: value } })),
  resetFilters: () => set({ filters: { ...defaultFilters } }),
}));
