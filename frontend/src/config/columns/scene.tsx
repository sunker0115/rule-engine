import { Tag, Space } from 'antd';
import { Link } from 'react-router-dom';
import { ROUTES, route } from '@/constants/routes';
import { colorOf, STATUS_OPTIONS } from '@/constants/enums';
import type { SceneListItem } from '@/types';
import type { ColumnsType } from 'antd/es/table';

export const SCENE_COLUMNS: ColumnsType<SceneListItem> = [
  {
    title: 'Scene Code',
    dataIndex: 'sceneCode',
    key: 'sceneCode',
    render: (v: string, record: SceneListItem) => (
      <Link to={route(ROUTES.SCENE_DETAIL, { sceneCode: record.sceneCode })}>{v}</Link>
    ),
  },
  { title: '名称', dataIndex: 'name', key: 'name' },
  {
    title: '模式',
    dataIndex: 'dominantMode',
    key: 'dominantMode',
    render: (v: string) => <Tag>{v}</Tag>,
  },
  { title: '主体类型', dataIndex: 'subjectType', key: 'subjectType' },
  {
    title: '状态',
    dataIndex: 'status',
    key: 'status',
    render: (v: string) => <Tag color={colorOf(STATUS_OPTIONS, v as never)}>{v}</Tag>,
  },
  {
    title: '操作',
    key: 'actions',
    render: (_: unknown, record: SceneListItem) => (
      <Space>
        <Link to={route(ROUTES.SCENE_DETAIL, { sceneCode: record.sceneCode })}>详情</Link>
        <Link to={route(ROUTES.SCENE_RULES, { sceneCode: record.sceneCode })}>规则</Link>
      </Space>
    ),
  },
];
