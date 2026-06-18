import { Tag, Space } from 'antd';
import { Link } from 'react-router-dom';
import { ROUTES, route } from '@/constants/routes';
import { colorOf, getRuleStatusOptions } from '@/constants/enums';
import { formatDateTime } from '@/utils/format';
import type { RuleListItem } from '@/types';
import type { ColumnsType } from 'antd/es/table';

export function getRuleColumns(t: (key: string) => string, tc: (key: string) => string, onDetail?: (id: number) => void): ColumnsType<RuleListItem> {
  const ruleStatusOpts = getRuleStatusOptions(t as never);
  return [
    { title: tc('label.tenant'), dataIndex: 'tenantId', key: 'tenantId', width: 70 },
    { title: t('column.code'), dataIndex: 'code', key: 'code', width: 120, ellipsis: true },
    { title: tc('label.name'), dataIndex: 'name', key: 'name', width: 140, ellipsis: true },
    { title: t('column.kind'), dataIndex: 'kind', key: 'kind', width: 100, render: (v: string) => <Tag>{v}</Tag> },
    { title: t('column.sceneCode'), dataIndex: 'sceneCode', key: 'sceneCode', width: 100 },
    { title: t('column.currentVersion'), dataIndex: 'currentVersion', key: 'currentVersion', width: 90, render: (v: number | null) => v ?? '-' },
    { title: t('column.status'), dataIndex: 'status', key: 'status', width: 80, render: (v: string) => <Tag color={colorOf(ruleStatusOpts, v as never)}>{v}</Tag> },
    { title: t('column.publishedAt'), dataIndex: 'publishedAt', key: 'publishedAt', width: 160, render: (v: string | null) => formatDateTime(v) },
    { title: tc('label.createdAt'), dataIndex: 'createdAt', key: 'createdAt', width: 160, render: (v: string) => formatDateTime(v) },
    { title: tc('label.actions'), key: 'actions', width: 120,
      render: (_: unknown, record: RuleListItem) => (
        <Space>
          {onDetail && <a onClick={(e) => { e.stopPropagation(); onDetail(record.ruleDefinitionId); }}>{t('column.actionsDetail')}</a>}
          <Link
            to={route(ROUTES.RULE_EDITOR, { ruleId: record.ruleDefinitionId })}
            onClick={(e) => e.stopPropagation()}
          >{t('column.actionsEdit')}</Link>
        </Space>
      ),
    },
  ];
}
