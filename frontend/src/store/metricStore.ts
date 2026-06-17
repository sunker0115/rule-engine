import { create } from 'zustand';
import { listMetrics } from '@/api/metric';
import type { MetricDescriptor } from '@/types';

interface MetricState {
  list: MetricDescriptor[];
  loadList: (tenantId: number) => Promise<void>;
}

export const useMetricStore = create<MetricState>((set) => ({
  list: [],
  loadList: async (tenantId: number) => {
    set({ list: await listMetrics(tenantId) });
  },
}));
