import { Tag } from 'antd';
import { Link } from 'react-router-dom';
import { ROUTES, route } from '@/constants/routes';
import { colorOf, STATUS_OPTIONS } from '@/constants/enums';
import type { MetricDescriptor } from '@/types';
import type { ColumnsType } from 'antd/es/table';

export const METRIC_COLUMNS: ColumnsType<MetricDescriptor> = [
  {
    title: 'Metric Code',
    dataIndex: 'metricCode',
    key: 'metricCode',
    render: (v: string) => <Link to={route(ROUTES.METRIC_DETAIL, { metricCode: v })}>{v}</Link>,
  },
  { title: '名称', dataIndex: 'name', key: 'name' },
  { title: '取数方式', dataIndex: 'sourceType', key: 'sourceType', render: (v: string) => <Tag>{v}</Tag> },
  { title: '数据类型', dataIndex: 'dataType', key: 'dataType', render: (v: string) => <Tag>{v}</Tag> },
  { title: '缓存 TTL(s)', dataIndex: 'cacheTtlSeconds', key: 'cacheTtlSeconds' },
  {
    title: '状态', dataIndex: 'status', key: 'status',
    render: (v: string) => v ? <Tag color={colorOf(STATUS_OPTIONS, v as never)}>{v}</Tag> : '-',
  },
];
