import { Tag } from 'antd';
import type { DecisionItem } from '@/types';
import type { ColumnsType } from 'antd/es/table';

export const DECISION_COLUMNS: ColumnsType<DecisionItem> = [
  { title: 'Code', dataIndex: 'code', key: 'code' },
  { title: '名称', dataIndex: 'name', key: 'name' },
  { title: '优先级', dataIndex: 'priority', key: 'priority', sorter: (a, b) => a.priority - b.priority },
  {
    title: '状态', dataIndex: 'status', key: 'status',
    render: (v: string) => {
      if (v === 'ACTIVE') return <Tag color="green">{v}</Tag>;
      if (v === 'DISABLED') return <Tag color="red">{v}</Tag>;
      return v;
    },
  },
  { title: '说明', dataIndex: 'description', key: 'description', render: (v: string) => v || '-' },
];
