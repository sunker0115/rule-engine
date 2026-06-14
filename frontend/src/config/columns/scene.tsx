import { Tag, Space, Switch } from 'antd';
import { Link } from 'react-router-dom';
import { ROUTES, route } from '@/constants/routes';
import type { SceneListItem } from '@/types';
import type { ColumnsType } from 'antd/es/table';

export function getSceneColumns(t: (key: string) => string, tc: (key: string) => string, onToggleStatus?: (code: string, enabled: boolean) => void): ColumnsType<SceneListItem> {
  return [
    { title: tc('label.tenant'), dataIndex: 'tenantId', key: 'tenantId', width: 70 },
    {
      title: t('column.sceneCode'),
      dataIndex: 'sceneCode',
      key: 'sceneCode',
      render: (v: string, record: SceneListItem) => (
        <Link to={route(ROUTES.SCENE_DETAIL, { sceneCode: record.sceneCode })}>{v}</Link>
      ),
    },
    { title: t('column.name'), dataIndex: 'name', key: 'name' },
    {
      title: t('column.dominantMode'),
      dataIndex: 'dominantMode',
      key: 'dominantMode',
      render: (v: string) => <Tag>{v}</Tag>,
    },
    { title: t('column.subjectType'), dataIndex: 'subjectType', key: 'subjectType' },
    {
      title: t('column.status'),
      dataIndex: 'status',
      key: 'status',
      render: (_v: string, record: SceneListItem) => (
        <Switch
          onClick={(_, e) => e?.stopPropagation?.()}
          checked={record.status === 'ACTIVE'}
          onChange={(enabled) => onToggleStatus?.(record.sceneCode, enabled)}
          size="small"
        />
      ),
    },
    { title: tc('label.createdAt'), dataIndex: 'createdAt', key: 'createdAt', render: (v: string) => v?.slice(0, 19) ?? '-' },
    { title: tc('label.updatedAt'), dataIndex: 'updatedAt', key: 'updatedAt', render: (v: string) => v?.slice(0, 19) ?? '-' },
    {
      title: t('column.actions'),
      key: 'actions',
      render: (_: unknown, record: SceneListItem) => (
        <Space>
          <Link to={route(ROUTES.SCENE_DETAIL, { sceneCode: record.sceneCode })}>详情</Link>
          <Link to={route(ROUTES.SCENE_DETAIL, { sceneCode: record.sceneCode })}>编辑</Link>
          <Link to={route(ROUTES.SCENE_RULES, { sceneCode: record.sceneCode })}>规则</Link>
        </Space>
      ),
    },
  ];
}
