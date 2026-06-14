import { Tag, Space } from 'antd';
import { Link } from 'react-router-dom';
import { ROUTES, route } from '@/constants/routes';
import { colorOf, RULE_STATUS_OPTIONS } from '@/constants/enums';
import type { RuleListItem } from '@/types';
import type { ColumnsType } from 'antd/es/table';

export function getRuleColumns(t: (key: string) => string, tc: (key: string) => string, onDetail?: (id: number) => void): ColumnsType<RuleListItem> {
  return [
    { title: tc('label.tenant'), dataIndex: 'tenantId', key: 'tenantId', width: 70 },
    { title: t('column.code'), dataIndex: 'code', key: 'code' },
    { title: tc('label.name'), dataIndex: 'name', key: 'name' },
    { title: t('column.kind'), dataIndex: 'kind', key: 'kind', render: (v: string) => <Tag>{v}</Tag> },
    { title: t('column.sceneCode'), dataIndex: 'sceneCode', key: 'sceneCode' },
    { title: t('column.currentVersion'), dataIndex: 'currentVersion', key: 'currentVersion', render: (v: number | null) => v ?? '-' },
    { title: t('column.status'), dataIndex: 'status', key: 'status', render: (v: string) => <Tag color={colorOf(RULE_STATUS_OPTIONS, v as never)}>{v}</Tag> },
    { title: t('column.publishedAt'), dataIndex: 'publishedAt', key: 'publishedAt', render: (v: string | null) => v?.slice(0, 19) ?? '-' },
    { title: tc('label.createdAt'), dataIndex: 'createdAt', key: 'createdAt', render: (v: string) => v?.slice(0, 19) ?? '-' },
    { title: tc('label.actions'), key: 'actions',
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
