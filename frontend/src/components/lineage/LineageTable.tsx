import { useMemo } from 'react';
import { Table, Tag, Empty } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { ROUTES, route } from '@/constants/routes';
import { colorOf, getRuleStatusOptions } from '@/constants/enums';
import type { LineageRuleRef } from '@/types';

interface Props {
  /** 血缘行（由父用 useLineage 提供）。 */
  rows: LineageRuleRef[];
  loading: boolean;
}

const MONO = 'ui-monospace, SFMono-Regular, Menlo, monospace';

/**
 * 详情页 Tab 用的全宽血缘表格：纯展示。
 * 列=规则码(mono,可点)/名称/场景/状态(Tag)；行点击下钻到规则编辑器（按 ruleDefinitionId）。
 * 场景/状态列支持基于 rows 动态生成的筛选。
 */
export default function LineageTable({ rows, loading }: Props) {
  const { t } = useTranslation('lineage');
  const tr = useTranslation('rule').t;
  const navigate = useNavigate();
  const ruleStatusOpts = getRuleStatusOptions(tr as never);

  // 场景/状态筛选项由当前 rows 去重生成
  const sceneFilters = useMemo(
    () => [...new Set(rows.map((r) => r.sceneCode))].map((s) => ({ text: s, value: s })),
    [rows],
  );
  const statusFilters = useMemo(
    () => [...new Set(rows.map((r) => r.status))].map((s) => ({ text: s, value: s })),
    [rows],
  );

  const columns: ColumnsType<LineageRuleRef> = [
    {
      title: t('col.ruleCode'),
      dataIndex: 'ruleCode',
      key: 'ruleCode',
      render: (v: string) => <span style={{ fontFamily: MONO }}>{v}</span>,
    },
    { title: t('col.ruleName'), dataIndex: 'ruleName', key: 'ruleName', ellipsis: true },
    {
      title: t('col.scene'),
      dataIndex: 'sceneCode',
      key: 'sceneCode',
      filters: sceneFilters,
      onFilter: (value, record) => record.sceneCode === value,
    },
    {
      title: t('col.status'),
      dataIndex: 'status',
      key: 'status',
      filters: statusFilters,
      onFilter: (value, record) => record.status === value,
      render: (v: string) => <Tag color={colorOf(ruleStatusOpts, v as never)}>{v}</Tag>,
    },
  ];

  return (
    <Table<LineageRuleRef>
      rowKey="ruleDefinitionId"
      columns={columns}
      dataSource={rows}
      loading={loading}
      size="small"
      pagination={false}
      locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('empty')} /> }}
      onRow={(record) => ({
        style: { cursor: 'pointer' },
        onClick: () => navigate(route(ROUTES.RULE_EDITOR, { ruleId: record.ruleDefinitionId })),
      })}
    />
  );
}
