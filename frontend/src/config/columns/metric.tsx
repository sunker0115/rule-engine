import { Tag } from 'antd';
import { Link } from 'react-router-dom';
import { ROUTES, route } from '@/constants/routes';
import { colorOf, STATUS_OPTIONS } from '@/constants/enums';
import type { MetricDescriptor } from '@/types';
import type { ColumnsType } from 'antd/es/table';

export function getMetricColumns(t: (key: string) => string, tc: (key: string) => string): ColumnsType<MetricDescriptor> {
  return [
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
      title: t('column.status'), dataIndex: 'status', key: 'status',
      render: (v: string) => v ? <Tag color={colorOf(STATUS_OPTIONS, v as never)}>{v}</Tag> : '-',
    },
    { title: tc('label.createdAt'), dataIndex: 'createdAt', key: 'createdAt', render: (v: string) => v?.slice(0, 19) ?? '-' },
    { title: tc('label.updatedAt'), dataIndex: 'updatedAt', key: 'updatedAt', render: (v: string) => v?.slice(0, 19) ?? '-' },
  ];
}
