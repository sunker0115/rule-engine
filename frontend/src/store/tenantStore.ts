import { create } from 'zustand';

interface TenantInfo {
  id: number;
  code: string;
  name: string;
}

interface TenantState {
  current: string | null;
  currentId: number | null;
  list: TenantInfo[];
  loadList: () => Promise<void>;
  setCurrent: (code: string) => void;
}

// 默认租户列表（后端暂无 /admin/v1/tenants 接口，v1 硬编码）
const DEFAULT_TENANTS: TenantInfo[] = [
  { id: 1, code: 'default', name: '默认租户' },
];

export const useTenantStore = create<TenantState>((set, get) => ({
  current: localStorage.getItem('tenantCode') || 'default',
  currentId: Number(localStorage.getItem('tenantId')) || 1,
  list: DEFAULT_TENANTS,

  loadList: async () => {
    // 后端暂无 tenant 列表接口，使用默认列表
    // 后续有接口时替换为: apiClient.get(ENDPOINTS.TENANT_LIST)
    set({ list: DEFAULT_TENANTS });
    const { current } = get();
    if (!current) {
      get().setCurrent(DEFAULT_TENANTS[0].code);
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
