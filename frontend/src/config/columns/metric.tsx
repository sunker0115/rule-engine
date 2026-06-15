import { Switch, Tag } from 'antd';
import { Link } from 'react-router-dom';
import { ROUTES, route } from '@/constants/routes';
import { formatDateTime } from '@/utils/format';
import type { MetricDescriptor } from '@/types';
import type { ColumnsType } from 'antd/es/table';

export function getMetricColumns(t: (key: string) => string, tc: (key: string) => string, onToggleStatus?: (code: string, enabled: boolean) => void): ColumnsType<MetricDescriptor> {
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
      title: t('column.status'), dataIndex: 'status', key: 'status', width: 80,
      render: (_v: string, r: MetricDescriptor) => (
        <Switch
          checked={r.status === 'ACTIVE'}
          onChange={(enabled) => onToggleStatus?.(r.metricCode, enabled)}
          size="small"
        />
      ),
    },
    { title: tc('label.createdAt'), dataIndex: 'createdAt', key: 'createdAt', render: (v: string) => formatDateTime(v) },
    { title: tc('label.updatedAt'), dataIndex: 'updatedAt', key: 'updatedAt', render: (v: string) => formatDateTime(v) },
  ];
}
