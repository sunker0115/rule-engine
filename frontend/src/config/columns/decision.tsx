import type { DecisionItem } from '@/types';
import type { ColumnsType } from 'antd/es/table';

export const DECISION_COLUMNS: ColumnsType<DecisionItem> = [
  { title: 'Code', dataIndex: 'code', key: 'code' },
  { title: '名称', dataIndex: 'name', key: 'name' },
  { title: '优先级', dataIndex: 'priority', key: 'priority', sorter: (a, b) => a.priority - b.priority },
  { title: '说明', dataIndex: 'description', key: 'description', render: (v: string) => v || '-' },
  { title: '创建时间', dataIndex: 'createdAt', key: 'createdAt' },
];
