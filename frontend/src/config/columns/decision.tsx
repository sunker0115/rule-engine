import { Switch } from 'antd';
import type { DecisionItem } from '@/types';
import type { ColumnsType } from 'antd/es/table';

export function getDecisionColumns(onToggleStatus?: (code: string, enabled: boolean) => void): ColumnsType<DecisionItem> {
  return [
    { title: 'Code', dataIndex: 'code', key: 'code' },
    { title: '名称', dataIndex: 'name', key: 'name' },
    { title: '优先级', dataIndex: 'priority', key: 'priority', sorter: (a, b) => a.priority - b.priority },
    {
      title: '状态', dataIndex: 'status', key: 'status', width: 80,
      render: (_v: string, r: DecisionItem) => (
        <Switch
          checked={r.status === 'ACTIVE'}
          onChange={(enabled) => onToggleStatus?.(r.code, enabled)}
          size="small"
        />
      ),
    },
    { title: '说明', dataIndex: 'description', key: 'description', render: (v: string) => v || '-' },
    { title: '创建时间', dataIndex: 'createdAt', key: 'createdAt', render: (v: string) => v?.slice(0, 19) ?? '-' },
    { title: '更新时间', dataIndex: 'updatedAt', key: 'updatedAt', render: (v: string) => v?.slice(0, 19) ?? '-' },
  ];
}
