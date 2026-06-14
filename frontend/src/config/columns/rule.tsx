import { Tag } from 'antd';
import { Link } from 'react-router-dom';
import { ROUTES, route } from '@/constants/routes';
import { colorOf, RULE_STATUS_OPTIONS, RULE_KIND_OPTIONS, labelOf } from '@/constants/enums';
import type { RuleListItem } from '@/types';
import type { ColumnsType } from 'antd/es/table';

export const RULE_COLUMNS: ColumnsType<RuleListItem> = [
  { title: 'Code', dataIndex: 'code', key: 'code' },
  { title: 'Name', dataIndex: 'name', key: 'name' },
  {
    title: 'Kind', dataIndex: 'kind', key: 'kind',
    render: (v: string) => <Tag>{labelOf(RULE_KIND_OPTIONS, v as never)}</Tag>,
  },
  { title: 'Scene', dataIndex: 'sceneCode', key: 'sceneCode' },
  {
    title: 'Status', dataIndex: 'status', key: 'status',
    render: (v: string) => <Tag color={colorOf(RULE_STATUS_OPTIONS, v as never)}>{v}</Tag>,
  },
  { title: 'Version', dataIndex: 'currentVersion', key: 'currentVersion' },
  { title: 'Published', dataIndex: 'publishedAt', key: 'publishedAt' },
  {
    title: 'Actions', key: 'actions',
    render: (_: unknown, record: RuleListItem) => (
      <Link to={route(ROUTES.RULE_EDITOR, { sceneCode: record.sceneCode, ruleId: record.ruleDefinitionId })}>Edit</Link>
    ),
  },
];
