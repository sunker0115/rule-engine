import { create } from 'zustand';
import apiClient from '@/api/client';
import { ENDPOINTS } from '@/constants/api-endpoints';

interface TenantInfo {
  id: number;
  code: string;
  name: string;
  status: string;
}

interface TenantState {
  current: string | null;
  currentId: number | null;
  list: TenantInfo[];
  loadList: (keyword?: string, status?: string) => Promise<void>;
  setCurrent: (code: string) => void;
}

export const useTenantStore = create<TenantState>((set, get) => ({
  current: localStorage.getItem('tenantCode') || null,
  currentId: Number(localStorage.getItem('tenantId')) || null,
  list: [],

  loadList: async (keyword?: string, status?: string) => {
    const params: Record<string, string> = {};
    if (keyword) params.keyword = keyword;
    if (status) params.status = status;
    const res = await apiClient.get(ENDPOINTS.TENANT_LIST, { params });
    const list: TenantInfo[] = res.data?.data ?? [];
    set({ list });
    const { current, currentId } = get();
    if ((!current || !currentId) && list.length > 0) {
      get().setCurrent(list[0].code);
    }
  },

  setCurrent: (code: string) => {
    const tenant = get().list.find((t) => t.code === code);
    if (tenant) {
      localStorage.setItem('tenantCode', tenant.code);
      localStorage.setItem('tenantId', String(tenant.id));
      set({ current: tenant.code, currentId: tenant.id });
    }
  },
}));
