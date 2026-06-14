import { create } from 'zustand';

interface Tenant {
  code: string;
  name: string;
}

interface TenantStore {
  current: string | null;
  list: Tenant[];
  setCurrent: (code: string) => void;
  loadList: () => Promise<void>;
}

/** 租户状态 —— 完整实现见 Task 7 */
export const useTenantStore = create<TenantStore>((set) => ({
  current: localStorage.getItem('tenantCode'),
  list: [],
  setCurrent: (code: string) => {
    localStorage.setItem('tenantCode', code);
    set({ current: code });
  },
  loadList: async () => {
    set({ list: [{ code: 'default', name: '默认租户' }] });
  },
}));
