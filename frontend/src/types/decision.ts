export interface DecisionItem {
  tenantId?: number;
  code: string;
  name: string;
  priority: number;
  description?: string;
  status?: string;
  createdAt?: string;
  updatedAt?: string;
}
