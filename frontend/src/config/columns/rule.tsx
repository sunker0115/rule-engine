import { Tag } from 'antd';
import { Link } from 'react-router-dom';
import { ROUTES, route } from '@/constants/routes';
import { colorOf, RULE_STATUS_OPTIONS } from '@/constants/enums';
import type { RuleListItem } from '@/types';
import type { ColumnsType } from 'antd/es/table';

/** sceneCode 由页面 URL 参数提供，不在列表 API 响应中 */
export const RULE_COLUMNS: ColumnsType<RuleListItem & { sceneCode?: string }> = [
  { title: 'Code', dataIndex: 'code', key: 'code' },
  { title: 'Name', dataIndex: 'name', key: 'name' },
  {
    title: 'Status', dataIndex: 'status', key: 'status',
    render: (v: string) => <Tag color={colorOf(RULE_STATUS_OPTIONS, v as never)}>{v}</Tag>,
  },
  { title: 'Version', dataIndex: 'currentVersion', key: 'currentVersion', render: (v: number | null) => v ?? '-' },
  { title: 'Published', dataIndex: 'publishedAt', key: 'publishedAt', render: (v: string | null) => v?.slice(0, 19) ?? '-' },
  {
    title: 'Actions', key: 'actions',
    render: (_: unknown, record: RuleListItem & { sceneCode?: string }) => (
      <Link to={route(ROUTES.RULE_EDITOR, { sceneCode: record.sceneCode ?? '', ruleId: record.ruleDefinitionId })}>Edit</Link>
    ),
  },
];
