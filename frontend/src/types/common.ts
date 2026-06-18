/** 分页响应（所有 admin 列表接口统一格式） */
export interface PageResponse<T> {
  items: T[];
  total: number;
  page: number;
  size: number;
}

/** ApiResponse 包装（所有非分页 admin 接口统一格式） */
export interface ApiResponse<T> {
  success: boolean;
  data: T;
  message?: string;
}
