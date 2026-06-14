import { create } from 'zustand';
import apiClient from '@/api/client';
import { ENDPOINTS } from '@/constants/api-endpoints';
import type { MetricDescriptor } from '@/types';

interface MetricState {
  list: MetricDescriptor[];
  loadList: (tenantId: number) => Promise<void>;
}

export const useMetricStore = create<MetricState>((set) => ({
  list: [],
  loadList: async (tenantId: number) => {
    const res = await apiClient.get(ENDPOINTS.METRIC_LIST, { params: { tenantId } });
    set({ list: res.data?.data ?? [] });
  },
}));
