/** 租户扁平信息（列表/下拉项共享） */
export interface TenantInfo {
  id: number;
  code: string;
  name: string;
  status: string;
  createdAt?: string;
  updatedAt?: string;
}
