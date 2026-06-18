import { Switch } from 'antd';
import { formatDateTime } from '@/utils/format';
import type { DecisionItem } from '@/types';
import type { ColumnsType } from 'antd/es/table';

export function getDecisionColumns(
  t: (key: string) => string,
  tc: (key: string) => string,
  onToggleStatus?: (code: string, enabled: boolean) => void,
  // 血缘：lineage 命名空间翻译 + code→被引用计数 + 点击徽标打开抽屉
  tl?: (key: string, opts?: Record<string, unknown>) => string,
  usageMap?: Record<string, number>,
  onOpenLineage?: (code: string) => void,
): ColumnsType<DecisionItem> {
  return [
    { title: tc('label.tenant'), dataIndex: 'tenantId', key: 'tenantId', width: 70 },
    { title: t('column.code'), dataIndex: 'code', key: 'code' },
    { title: t('column.name'), dataIndex: 'name', key: 'name' },
    { title: t('column.priority'), dataIndex: 'priority', key: 'priority', sorter: (a, b) => a.priority - b.priority },
    { title: t('column.description'), dataIndex: 'description', key: 'description', ellipsis: true, render: (v: string) => v || '-' },
    {
      title: t('column.usage'), key: 'usage', width: 90,
      render: (_v: unknown, r: DecisionItem) => {
        const count = usageMap?.[r.code] ?? 0;
        if (count > 0) {
          return (
            <a
              onClick={(e) => { e.stopPropagation(); onOpenLineage?.(r.code); }}
              style={{ color: '#0969da' }}
            >
              {tl?.('badge', { n: count })}
            </a>
          );
        }
        return <span style={{ color: '#999' }}>{tl?.('badge', { n: 0 })}</span>;
      },
    },
    {
      title: t('column.status'), dataIndex: 'status', key: 'status', width: 80,
      render: (_v: string, r: DecisionItem) => (
        <Switch
          checked={r.status === 'ACTIVE'}
          onChange={(enabled) => onToggleStatus?.(r.code, enabled)}
          size="small"
          onClick={(_checked, e) => e.stopPropagation()}
        />
      ),
    },
    { title: tc('label.createdAt'), dataIndex: 'createdAt', key: 'createdAt', render: (v: string) => formatDateTime(v) },
    { title: tc('label.updatedAt'), dataIndex: 'updatedAt', key: 'updatedAt', render: (v: string) => formatDateTime(v) },
  ];
}
