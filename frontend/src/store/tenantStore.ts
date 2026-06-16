import { create } from 'zustand';
import apiClient from '@/api/client';
import { ENDPOINTS } from '@/constants/api-endpoints';

interface TenantInfo {
  id: number;
  code: string;
  name: string;
  status: string;
  createdAt?: string;
  updatedAt?: string;
}

interface TenantState {
  current: string | null;
  currentId: number | null;
  /** Header 专用：全部 ACTIVE 租户列表（始终全量，不受页面筛选影响） */
  activeList: TenantInfo[];
  /** 应用启动时自动初始化 */
  init: () => Promise<void>;
  /** 页面专用：按关键词+状态查询，不污染 activeList */
  searchTenants: (keyword?: string, status?: string) => Promise<TenantInfo[]>;
  setCurrent: (code: string) => void;
  /** 按租户 id 设为全局当前（各列表页租户选择器联动写回全局）；null 清空 */
  setCurrentById: (id?: number | null) => void;
}

export const useTenantStore = create<TenantState>((set, get) => ({
  current: localStorage.getItem('tenantCode') || null,
  currentId: Number(localStorage.getItem('tenantId')) || null,
  activeList: [],

  /** 应用启动时自动加载 ACTIVE 租户列表并选中第一个 */
  init: async () => {
    const tenants = await get().searchTenants(undefined, 'ACTIVE');
    set({ activeList: tenants });
    const { current, currentId } = get();
    if ((!current || !currentId) && tenants.length > 0) {
      const t = tenants[0];
      localStorage.setItem('tenantCode', t.code);
      localStorage.setItem('tenantId', String(t.id));
      set({ current: t.code, currentId: t.id });
    }
  },

  searchTenants: async (keyword?: string, status?: string) => {
    const params: Record<string, string> = {};
    if (keyword) params.keyword = keyword;
    if (status) params.status = status;
    const res = await apiClient.get(ENDPOINTS.TENANT_LIST, { params });
    return res.data?.data ?? [];
  },

  setCurrent: (code: string | null) => {
    if (!code) {
      localStorage.removeItem('tenantCode');
      localStorage.removeItem('tenantId');
      set({ current: null, currentId: null });
      return;
    }
    // 先从 activeList 查（Header 加载的 ACTIVE 列表）
    let tenant = get().activeList.find((t) => t.code === code);
    if (!tenant) {
      // activeList 里没有 → 临时 set（如从页面点击了 DISABLED 租户）
      localStorage.setItem('tenantCode', code);
      set({ current: code, currentId: null });
      return;
    }
    localStorage.setItem('tenantCode', tenant.code);
    localStorage.setItem('tenantId', String(tenant.id));
    set({ current: tenant.code, currentId: tenant.id });
  },

  setCurrentById: (id?: number | null) => {
    if (id == null) {
      localStorage.removeItem('tenantCode');
      localStorage.removeItem('tenantId');
      set({ current: null, currentId: null });
      return;
    }
    const tenant = get().activeList.find((t) => t.id === id);
    localStorage.setItem('tenantId', String(id));
    if (tenant) localStorage.setItem('tenantCode', tenant.code);
    set({ current: tenant?.code ?? get().current, currentId: id });
  },
}));
