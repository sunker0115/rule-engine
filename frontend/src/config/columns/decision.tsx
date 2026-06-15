import { Switch } from 'antd';
import { formatDateTime } from '@/utils/format';
import type { DecisionItem } from '@/types';
import type { ColumnsType } from 'antd/es/table';

export function getDecisionColumns(t: (key: string) => string, tc: (key: string) => string, onToggleStatus?: (code: string, enabled: boolean) => void): ColumnsType<DecisionItem> {
  return [
    { title: tc('label.tenant'), dataIndex: 'tenantId', key: 'tenantId', width: 70 },
    { title: t('column.code'), dataIndex: 'code', key: 'code' },
    { title: t('column.name'), dataIndex: 'name', key: 'name' },
    { title: t('column.priority'), dataIndex: 'priority', key: 'priority', sorter: (a, b) => a.priority - b.priority },
    { title: t('column.description'), dataIndex: 'description', key: 'description', ellipsis: true, render: (v: string) => v || '-' },
    {
      title: t('column.status'), dataIndex: 'status', key: 'status', width: 80,
      render: (_v: string, r: DecisionItem) => (
        <Switch
          checked={r.status === 'ACTIVE'}
          onChange={(enabled) => onToggleStatus?.(r.code, enabled)}
          size="small"
        />
      ),
    },
    { title: tc('label.createdAt'), dataIndex: 'createdAt', key: 'createdAt', render: (v: string) => formatDateTime(v) },
    { title: tc('label.updatedAt'), dataIndex: 'updatedAt', key: 'updatedAt', render: (v: string) => formatDateTime(v) },
  ];
}
