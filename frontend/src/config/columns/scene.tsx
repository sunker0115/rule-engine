import { Tag, Space, Switch } from 'antd';
import { Link } from 'react-router-dom';
import { ROUTES, route } from '@/constants/routes';
import type { SceneListItem } from '@/types';
import type { ColumnsType } from 'antd/es/table';

export function getSceneColumns(onToggleStatus?: (code: string, enabled: boolean) => void): ColumnsType<SceneListItem> {
  return [
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
      render: (_v: string, record: SceneListItem) => (
        <Switch
          checked={record.status === 'ACTIVE'}
          onChange={(enabled) => onToggleStatus?.(record.sceneCode, enabled)}
          size="small"
        />
      ),
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
    { title: '创建时间', dataIndex: 'createdAt', key: 'createdAt', render: (v: string) => v?.slice(0, 19) ?? '-' },
    { title: '更新时间', dataIndex: 'updatedAt', key: 'updatedAt', render: (v: string) => v?.slice(0, 19) ?? '-' },
  ];
}
