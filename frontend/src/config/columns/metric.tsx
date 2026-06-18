import { Switch, Tag } from 'antd';
import { Link } from 'react-router-dom';
import { ROUTES, route } from '@/constants/routes';
import { formatDateTime } from '@/utils/format';
import type { MetricDescriptor } from '@/types';
import type { ColumnsType } from 'antd/es/table';

export function getMetricColumns(
  t: (key: string) => string,
  tc: (key: string) => string,
  onToggleStatus?: (code: string, enabled: boolean) => void,
  // 血缘：lineage 命名空间翻译 + code→被引用计数 + 点击徽标打开抽屉
  tl?: (key: string, opts?: Record<string, unknown>) => string,
  usageMap?: Record<string, number>,
  onOpenLineage?: (code: string) => void,
): ColumnsType<MetricDescriptor> {
  return [
    { title: tc('label.tenant'), dataIndex: 'tenantId', key: 'tenantId', width: 70 },
    {
      title: t('column.metricCode'),
      dataIndex: 'metricCode',
      key: 'metricCode',
      render: (v: string) => <Link to={route(ROUTES.METRIC_DETAIL, { metricCode: v })}>{v}</Link>,
    },
    { title: t('column.name'), dataIndex: 'name', key: 'name' },
    { title: t('column.sourceType'), dataIndex: 'sourceType', key: 'sourceType', render: (v: string) => <Tag>{v}</Tag> },
    { title: t('column.dataType'), dataIndex: 'dataType', key: 'dataType', render: (v: string) => <Tag>{v}</Tag> },
    {
      title: t('column.usage'), key: 'usage', width: 90,
      render: (_v: unknown, r: MetricDescriptor) => {
        const count = usageMap?.[r.metricCode] ?? 0;
        if (count > 0) {
          return (
            <a
              onClick={(e) => { e.stopPropagation(); onOpenLineage?.(r.metricCode); }}
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
      render: (_v: string, r: MetricDescriptor) => (
        <Switch
          checked={r.status === 'ACTIVE'}
          onChange={(enabled) => onToggleStatus?.(r.metricCode, enabled)}
          size="small"
          onClick={(_checked, e) => e.stopPropagation()}
        />
      ),
    },
    { title: tc('label.createdAt'), dataIndex: 'createdAt', key: 'createdAt', render: (v: string) => formatDateTime(v) },
    { title: tc('label.updatedAt'), dataIndex: 'updatedAt', key: 'updatedAt', render: (v: string) => formatDateTime(v) },
  ];
}
