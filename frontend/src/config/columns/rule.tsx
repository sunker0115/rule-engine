import { Tag, Space } from 'antd';
import { Link } from 'react-router-dom';
import { ROUTES, route } from '@/constants/routes';
import { colorOf, RULE_STATUS_OPTIONS } from '@/constants/enums';
import type { RuleListItem } from '@/types';
import type { ColumnsType } from 'antd/es/table';

export function getRuleColumns(onDetail?: (id: number) => void): ColumnsType<RuleListItem> {
  return [
    { title: 'Code', dataIndex: 'code', key: 'code' },
    { title: 'Name', dataIndex: 'name', key: 'name' },
    {
      title: 'Kind', dataIndex: 'kind', key: 'kind',
      render: (v: string) => <Tag>{v}</Tag>,
    },
    { title: 'Scene', dataIndex: 'sceneCode', key: 'sceneCode' },
    {
      title: 'Status', dataIndex: 'status', key: 'status',
      render: (v: string) => <Tag color={colorOf(RULE_STATUS_OPTIONS, v as never)}>{v}</Tag>,
    },
    { title: 'Version', dataIndex: 'currentVersion', key: 'currentVersion', render: (v: number | null) => v ?? '-' },
    { title: 'Published', dataIndex: 'publishedAt', key: 'publishedAt', render: (v: string | null) => v?.slice(0, 19) ?? '-' },
    {
      title: 'Actions', key: 'actions',
      render: (_: unknown, record: RuleListItem) => (
        <Space>
          {onDetail && <a onClick={(e) => { e.stopPropagation(); onDetail(record.ruleDefinitionId); }}>详情</a>}
          <Link
            to={route(ROUTES.RULE_EDITOR, { ruleId: record.ruleDefinitionId })}
            onClick={(e) => e.stopPropagation()}
          >编辑</Link>
        </Space>
      ),
    },
  ];
}
